package hermes.api

import java.net.URI
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import hermes.api.runtime.HermesREST
import hermes.api.runtime.HermesRESTConfiguration
import hermes.api.runtime.HermesRESTException
import hermes.api.runtime.RESTTransport
import hermes.api.generated.rest.ProfilesSetActiveRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HermesRESTTest {
    @Test
    fun generatedRESTQueryIsEncoded() = runTest {
        val transport = object : RESTTransport {
            override suspend fun request(method: String, uri: URI, headers: Map<String, String>, body: String?): Pair<Int, String> {
                assertEquals("GET", method)
                assertEquals(null, body)
                assertEquals("/api/sessions/empty/count", uri.path)
                assertEquals("profile=qa+%26+mobile", uri.rawQuery)
                return 200 to """{"count":2}"""
            }
        }
        val rest = HermesREST(HermesRESTConfiguration(URI("https://dashboard.example"), transport = transport))
        assertEquals(2, rest.methods.sessions.emptyCount("qa & mobile").count)
    }

    @Test
    fun generatedAuthCallDecodesReviewedResponse() = runTest {
        val transport = object : RESTTransport {
            override suspend fun request(method: String, uri: URI, headers: Map<String, String>, body: String?): Pair<Int, String> {
                assertEquals("POST", method)
                assertEquals(null, body)
                assertEquals("https://dashboard.example/api/auth/ws-ticket", uri.toString())
                assertEquals("Bearer test", headers["Authorization"])
                return 200 to """{"ticket":"fresh","ttl_seconds":30}"""
            }
        }
        val rest = HermesREST(HermesRESTConfiguration(URI("https://dashboard.example"),
            headers = { mapOf("Authorization" to "Bearer test") }, transport = transport))
        assertEquals("fresh", rest.methods.auth.wsTicket().ticket)
    }

    @Test
    fun unexpectedFieldsFailStrictDecode() = runTest {
        val transport = object : RESTTransport {
            override suspend fun request(method: String, uri: URI, headers: Map<String, String>, body: String?): Pair<Int, String> =
                200 to """{"ticket":"fresh","ttl_seconds":30,"surprise":1}"""
        }
        val rest = HermesREST(HermesRESTConfiguration(URI("https://dashboard.example"), transport = transport))
        assertFailsWith<HermesRESTException.Decoding> { rest.methods.auth.wsTicket() }
    }

    @Test
    fun httpFailureIsTyped() = runTest {
        val transport = object : RESTTransport {
            override suspend fun request(method: String, uri: URI, headers: Map<String, String>, body: String?): Pair<Int, String> =
                401 to """{"detail":"Unauthorized"}"""
        }
        val rest = HermesREST(HermesRESTConfiguration(URI("https://dashboard.example"), transport = transport))
        assertEquals(401, assertFailsWith<HermesRESTException.HTTP> { rest.methods.auth.wsTicket() }.status)
    }

    @Test
    fun generatedProfileUpdateSendsJSONBody() = runTest {
        val transport = object : RESTTransport {
            override suspend fun request(method: String, uri: URI, headers: Map<String, String>, body: String?): Pair<Int, String> {
                assertEquals("POST", method)
                assertEquals("/api/profiles/active", uri.path)
                assertEquals("""{"name":"default"}""", body)
                return 200 to """{"ok":true,"active":"default"}"""
            }
        }
        val rest = HermesREST(HermesRESTConfiguration(URI("https://dashboard.example"), transport = transport))
        assertEquals("default", rest.methods.profiles.setActive(ProfilesSetActiveRequest("default")).active)
    }

    @Test
    fun requiredNullableRESTFieldPreservesNullAndRequiresKey() {
        val json = Json
        val body = """{"ok":true,"mode":"chained","available":false,"reason":null,"model":"gpt-live-1","voice":"marin"}"""
        val decoded = json.decodeFromString(hermes.api.generated.rest.AudioVoiceLiveStatusResponse.serializer(), body)
        assertEquals(null, decoded.reason)
        assertEquals(json.parseToJsonElement(body),
            json.encodeToJsonElement(hermes.api.generated.rest.AudioVoiceLiveStatusResponse.serializer(), decoded))
        val missing = """{"ok":true,"mode":"chained","available":false,"model":"gpt-live-1","voice":"marin"}"""
        assertFailsWith<kotlinx.serialization.MissingFieldException> {
            json.decodeFromString(hermes.api.generated.rest.AudioVoiceLiveStatusResponse.serializer(), missing)
        }
    }
}
