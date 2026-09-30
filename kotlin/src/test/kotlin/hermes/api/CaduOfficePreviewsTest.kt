package hermes.api

import hermes.api.extensions.*
import hermes.api.runtime.*
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CaduOfficePreviewsTest {
    private val source = "a".repeat(64)
    private val pdf = "%PDF-fixture".encodeToByteArray()
    private val engine = "sha256:" + "b".repeat(64)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun response(data: ByteArray = pdf, headers: Map<String, String> = emptyMap()) = RESTResponse(200, mapOf(
        "content-type" to "application/pdf", "x-cadu-source-sha256" to source,
        "x-cadu-preview-sha256" to hash(data), "x-cadu-preview-engine" to engine,
    ) + headers, data)
    private class Caller(val respond: (RESTRequest) -> RESTResponse) : RESTCaller {
        val requests = mutableListOf<RESTRequest>()
        override suspend fun send(request: RESTRequest): RESTResponse {
            requests += request
            return respond(request)
        }
    }

    @Test fun requestPreservesFileAndSessionCoordinates() = runTest {
        val caller = Caller { response() }
        val request = OfficePreviewRequest("/résumé/a & b.docx", source, OfficePreviewScope.Filesystem, "writer", "session-1")
        CaduOfficePreviews(caller).preview(request)
        val sent = caller.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("/api/plugins/cadu-office-preview/preview", sent.path)
        assertEquals("application/json", sent.contentType)
        val body = RESTJson.parseToJsonElement(sent.body!!.decodeToString()).jsonObject
        assertEquals(request.path, body["path"]!!.jsonPrimitive.content)
        assertEquals("filesystem", body["scope"]!!.jsonPrimitive.content)
        assertEquals("session-1", body["session_id"]!!.jsonPrimitive.content)
        assertEquals(source, body["source_sha256"]!!.jsonPrimitive.content)
    }

    @Test fun invalidCoordinatesNeverReachTransport() = runTest {
        val caller = Caller { error("Invalid input sent") }
        for (request in listOf(OfficePreviewRequest("", source), OfficePreviewRequest("/x", "invalid"),
            OfficePreviewRequest("/x", source, profile = "writer"), OfficePreviewRequest("/x\u0000", source))) {
            assertFailsWith<IllegalArgumentException> { CaduOfficePreviews(caller).preview(request) }
        }
        assertTrue(caller.requests.isEmpty())
    }

    @Test fun validatesCapabilitiesAndKeepsHttpFailuresDistinct() = runTest {
        val body = """{"version":1,"engine":"$engine","extensions":["docx"],"max_input_bytes":33554432,"max_output_bytes":67108864}"""
        val capabilities = CaduOfficePreviews(Caller { RESTResponse(200, emptyMap(), body.encodeToByteArray()) }).capabilities()
        assertEquals(listOf("docx"), capabilities.extensions)
        for (bad in listOf(body.replace("\"version\":1", "\"version\":2"), body.replace("33554432", "-1"))) {
            assertFailsWith<HermesRESTException.Decoding> {
                CaduOfficePreviews(Caller { RESTResponse(200, emptyMap(), bad.encodeToByteArray()) }).capabilities()
            }
        }
        for (status in listOf(401, 403, 404, 409, 503)) {
            val caller = Caller { RESTResponse(status, emptyMap(), byteArrayOf()) }
            val failure = assertFailsWith<HermesRESTException.HTTP> {
                CaduOfficePreviews(caller).preview(OfficePreviewRequest("/x", source))
            }
            assertEquals(status, failure.status)
            assertEquals(1, caller.requests.size)
        }
    }

    @Test fun rejectsWrongResponseIdentityAndVerifiesStagedContent() = runTest {
        val request = OfficePreviewRequest("/x", source)
        for (headers in listOf(mapOf("x-cadu-source-sha256" to "c".repeat(64)),
            mapOf("x-cadu-preview-sha256" to "bad"), mapOf("x-cadu-preview-engine" to "bad"),
            mapOf("content-type" to "text/html"))) {
            assertFailsWith<HermesRESTException.Decoding> {
                CaduOfficePreviews(Caller { response(headers = headers) }).preview(request)
            }
        }
        val preview = CaduOfficePreviews(Caller { response() }).preview(request)
        assertEquals(pdf.size.toLong(), preview.verifyContent())
        assertFailsWith<HermesRESTException.Decoding> { preview.verifyContent("%PDF-changed".byteInputStream()) }
        // A disk-streaming transport may return an empty body while retaining the original headers.
        val streamed = CaduOfficePreviews(Caller { RESTResponse(200, response().headers, byteArrayOf()) }).preview(request)
        assertEquals(pdf.size.toLong(), streamed.verifyContent(pdf.inputStream()))
        assertFailsWith<HermesRESTException.Decoding> { streamed.verifyContent() }
        val oversized = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { bytes.fill(0, offset, offset + length); return length }
        }
        assertFailsWith<HermesRESTException.Decoding> { preview.verifyContent(oversized) }
    }

    @Test fun twoClientsKeepSamePathRequestsWithTheirOwnOriginAndCredentials() = runTest {
        val seen = mutableListOf<Pair<String, Map<String, String>>>()
        val transport = object : RESTTransport {
            override suspend fun request(method: String, uri: URI, headers: Map<String, String>, body: ByteArray?, contentType: String?): RESTTransportResponse {
                seen += uri.toString() to headers
                val result = response()
                return RESTTransportResponse(result.status, result.headers, result.body)
            }
        }
        val clients = listOf("a", "b").map { owner -> CaduOfficePreviews(HermesREST(HermesRESTConfiguration(
            HermesDashboardAddress(URI("https://$owner.example/hermes")), LocalTokenAuth("fixture-$owner"),
            transport = transport, retry = RESTRetryPolicy.None))) }
        clients.map { async { it.preview(OfficePreviewRequest("/same/document.docx", source)).verifyContent() } }.awaitAll()
        assertEquals(2, seen.size)
        for (owner in listOf("a", "b")) {
            val request = seen.single { it.first.startsWith("https://$owner.example/") }
            assertEquals("https://$owner.example/hermes/api/plugins/cadu-office-preview/preview", request.first)
            assertTrue(request.second.values.any { "fixture-$owner" in it })
            assertFalse(request.second.values.any { "fixture-${if (owner == "a") "b" else "a"}" in it })
        }
    }
}
