package hermes.api

import java.net.ConnectException
import java.net.URI
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import hermes.api.generated.rest.CronFireRequest
import hermes.api.generated.rest.CronFireResult
import hermes.api.generated.rest.LearningGraphStatsTopCategoriesItem
import hermes.api.generated.rest.ProfileActiveUpdate
import hermes.api.generated.rest.VoiceLiveStatusResponse
import hermes.api.generated.rest.VoiceLiveStatusResponseMode
import hermes.api.runtime.HermesREST
import hermes.api.runtime.HermesRESTAuth
import hermes.api.runtime.HermesRESTConfiguration
import hermes.api.runtime.HermesRESTException
import hermes.api.runtime.LocalTokenAuth
import hermes.api.runtime.NativeSessionAuth
import hermes.api.runtime.RESTFile
import hermes.api.runtime.RESTRedirect
import hermes.api.runtime.RESTResponse
import hermes.api.runtime.RESTRetryPolicy
import hermes.api.runtime.RESTTransport
import hermes.api.runtime.RESTTransportResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Answers each request from a script and records what was sent. */
private class ScriptedTransport(vararg steps: Step) : RESTTransport {
    sealed interface Step {
        data class Respond(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) : Step
        data class Fail(val error: Exception) : Step
        data object Hang : Step
    }

    data class Sent(val method: String, val uri: URI, val headers: Map<String, String>, val body: ByteArray?, val contentType: String?)

    private val steps = steps.toMutableList()
    val requests = mutableListOf<Sent>()

    override suspend fun request(
        method: String, uri: URI, headers: Map<String, String>, body: ByteArray?, contentType: String?,
    ): RESTTransportResponse {
        synchronized(this) { requests += Sent(method, uri, headers, body, contentType) }
        return when (val step = synchronized(this) { steps.removeFirst() }) {
            is Step.Respond -> RESTTransportResponse(step.status, step.headers, step.body.encodeToByteArray())
            is Step.Fail -> throw step.error
            Step.Hang -> awaitCancellation()
        }
    }
}

private val base = URI("https://dashboard.example")
private val fastRetry = RESTRetryPolicy(maxAttempts = 3, initialDelayMillis = 1, maximumDelayMillis = 5)

private fun client(transport: RESTTransport, auth: HermesRESTAuth? = null, retry: RESTRetryPolicy = fastRetry,
                   timeoutMillis: Long = 5_000) =
    HermesREST(HermesRESTConfiguration(base, auth, transport = transport, timeoutMillis = timeoutMillis, retry = retry))

class HermesRESTTest {
    @Test
    fun queryValuesAndPathSegmentsArePercentEncoded() = runTest {
        val transport = ScriptedTransport(
            ScriptedTransport.Step.Respond(200, """{"count":2}"""),
            ScriptedTransport.Step.Respond(404, """{"detail":"Session not found"}"""),
        )
        val rest = client(transport)
        assertEquals(2, rest.methods.sessions.emptyCount(profile = "qa & mobile+1/é").count)
        assertFailsWith<HermesRESTException.HTTP> { rest.methods.sessions.getBySessionId("a/b c?#é") }
        assertEquals("https://dashboard.example/api/sessions/empty/count?profile=qa%20%26%20mobile%2B1%2F%C3%A9",
            transport.requests[0].uri.toString())
        assertEquals("https://dashboard.example/api/sessions/a%2Fb%20c%3F%23%C3%A9", transport.requests[1].uri.toString())
    }

    @Test
    fun localTokenAuthenticatesAndJSONBodiesAreSent() = runTest {
        val transport = ScriptedTransport(ScriptedTransport.Step.Respond(200, """{"ok":true,"active":"default"}"""))
        val result = client(transport, LocalTokenAuth("secret")).methods.profiles.setActive(ProfileActiveUpdate("default"))
        assertTrue(result.ok && result.active == "default")
        val sent = transport.requests.single()
        assertEquals("POST", sent.method)
        assertEquals("secret", sent.headers["X-Hermes-Session-Token"])
        assertEquals("application/json", sent.contentType)
        assertEquals("""{"name":"default"}""", sent.body?.decodeToString())
    }

    @Test
    fun strictDecodingRejectsUnknownFieldsAndEnumValues() = runTest {
        val transport = ScriptedTransport(
            ScriptedTransport.Step.Respond(200, """{"ticket":"fresh","ttl_seconds":30,"surprise":1}"""),
            ScriptedTransport.Step.Respond(200,
                """{"ok":true,"mode":"telepathy","available":false,"reason":null,"model":"m","voice":"v"}"""),
        )
        val rest = client(transport)
        assertFailsWith<HermesRESTException.Decoding> { rest.methods.auth.wsTicket() }
        val error = assertFailsWith<HermesRESTException.Decoding> { rest.methods.audio.voiceLiveStatus() }
        assertTrue(error.message.orEmpty().contains("telepathy"))
    }

    @Test
    fun requiredNullableFieldsKeepNullAndRequireTheKey() {
        val json = Json
        val body = """{"ok":true,"mode":"chained","available":false,"reason":null,"model":"gpt-live-1","voice":"marin"}"""
        val decoded = json.decodeFromString(VoiceLiveStatusResponse.serializer(), body)
        assertEquals(null, decoded.reason)
        assertEquals(VoiceLiveStatusResponseMode.Chained, decoded.mode)
        assertEquals(json.parseToJsonElement(body), json.encodeToJsonElement(VoiceLiveStatusResponse.serializer(), decoded))
        val missing = """{"ok":true,"mode":"chained","available":false,"model":"gpt-live-1","voice":"marin"}"""
        assertFailsWith<kotlinx.serialization.SerializationException> {
            json.decodeFromString(VoiceLiveStatusResponse.serializer(), missing)
        }
    }

    @Test
    fun tuplesDecodeFixedLengthArrays() {
        val item = Json.decodeFromString(LearningGraphStatsTopCategoriesItem.serializer(), """["coding",3]""")
        assertEquals(LearningGraphStatsTopCategoriesItem("coding", 3), item)
        assertEquals("""["coding",3]""", Json.encodeToString(LearningGraphStatsTopCategoriesItem.serializer(), item))
        assertFailsWith<kotlinx.serialization.SerializationException> {
            Json.decodeFromString(LearningGraphStatsTopCategoriesItem.serializer(), """["coding",3,4]""")
        }
    }

    @Test
    fun httpErrorsCarryFastAPIDetail() = runTest {
        val transport = ScriptedTransport(ScriptedTransport.Step.Respond(401, """{"detail":"Unauthorized"}"""))
        val error = assertFailsWith<HermesRESTException.HTTP> { client(transport).methods.auth.wsTicket() }
        assertEquals(401, error.status)
        assertEquals("Unauthorized", error.detail)
        assertTrue(error.isAuthenticationFailure)
    }

    @Test
    fun safeRequestsRetryTransientFailures() = runTest {
        val transport = ScriptedTransport(
            ScriptedTransport.Step.Fail(IOException("connection reset")),
            ScriptedTransport.Step.Respond(503, """{"detail":"starting"}""", mapOf("Retry-After" to "0")),
            ScriptedTransport.Step.Respond(200, """{"count":0}"""),
        )
        assertEquals(0, client(transport).methods.sessions.emptyCount().count)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun unsafeRequestsRetryOnlyWhenHermesNeverSawThem() = runTest {
        val unreachable = ScriptedTransport(
            ScriptedTransport.Step.Fail(ConnectException("refused")),
            ScriptedTransport.Step.Respond(200, """{"ok":true,"active":"x"}"""),
        )
        assertEquals("x", client(unreachable).methods.profiles.setActive(ProfileActiveUpdate("x")).active)
        assertEquals(2, unreachable.requests.size)

        val unavailable = ScriptedTransport(
            ScriptedTransport.Step.Respond(503, "{}"), ScriptedTransport.Step.Respond(200, """{"ok":true,"active":"x"}"""))
        assertEquals(503, assertFailsWith<HermesRESTException.HTTP> {
            client(unavailable).methods.profiles.setActive(ProfileActiveUpdate("x"))
        }.status)
        assertEquals(1, unavailable.requests.size)

        val lost = ScriptedTransport(
            ScriptedTransport.Step.Fail(IOException("reset")), ScriptedTransport.Step.Respond(200, """{"ok":true,"active":"x"}"""))
        assertFailsWith<HermesRESTException.Transport> { client(lost).methods.profiles.setActive(ProfileActiveUpdate("x")) }
        assertEquals(1, lost.requests.size)
    }

    @Test
    fun attemptsTimeOut() = runBlocking {
        val transport = ScriptedTransport(ScriptedTransport.Step.Hang)
        val started = System.nanoTime()
        assertFailsWith<HermesRESTException.Timeout> {
            client(transport, retry = RESTRetryPolicy.None, timeoutMillis = 100).methods.sessions.emptyCount()
        }
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun cancellationStopsARequest() = runBlocking {
        val transport = ScriptedTransport(ScriptedTransport.Step.Hang)
        val job = launch { client(transport).methods.sessions.emptyCount() }
        delay(50)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    private class RenewingAuth : HermesRESTAuth {
        var token = "stale"
        var renewals = 0

        override suspend fun authorizationHeaders(): Map<String, String> = mapOf("Authorization" to "Bearer $token")

        override suspend fun renew(rejected: Map<String, String>, response: RESTResponse): Boolean {
            renewals += 1
            token = "fresh"
            return true
        }
    }

    @Test
    fun unauthorizedRequestsRenewTheCredentialOnce() = runTest {
        val transport = ScriptedTransport(
            ScriptedTransport.Step.Respond(401, "{}"), ScriptedTransport.Step.Respond(200, """{"ticket":"t","ttl_seconds":30}"""))
        val auth = RenewingAuth()
        assertEquals("t", client(transport, auth).methods.auth.wsTicket().ticket)
        assertEquals(listOf("Bearer stale", "Bearer fresh"), transport.requests.map { it.headers["Authorization"] })
        assertEquals(1, auth.renewals)

        val rejected = ScriptedTransport(ScriptedTransport.Step.Respond(401, "{}"), ScriptedTransport.Step.Respond(401, "{}"))
        assertEquals(401, assertFailsWith<HermesRESTException.HTTP> { client(rejected, RenewingAuth()).methods.auth.wsTicket() }.status)
        assertEquals(2, rejected.requests.size)
    }

    private val issued =
        """{"access_token":"a2","refresh_token":"r2","token_type":"Bearer","expires_at":4102444800,"provider":"oidc","user_id":"u"}"""

    @Test
    fun nativeSessionRefreshesOnceForConcurrentRejections() = runTest {
        val refresh = ScriptedTransport(ScriptedTransport.Step.Respond(200, issued))
        val rotated = mutableListOf<NativeSessionAuth.Tokens>()
        val auth = NativeSessionAuth(base, NativeSessionAuth.Tokens("a1", "r1", provider = "oidc"), refresh,
            onRotate = { rotated += it })
        val rejection = RESTResponse(401, emptyMap(), ByteArray(0))
        val rejected = mapOf("Authorization" to "Bearer a1")
        assertEquals(listOf(true, true),
            listOf(async { auth.renew(rejected, rejection) }, async { auth.renew(rejected, rejection) }).awaitAll())
        // A rejection that arrives after the refresh finished retries with the new token, no second refresh.
        assertTrue(auth.renew(rejected, rejection))
        val sent = refresh.requests.single()
        assertEquals("/auth/native/refresh", sent.uri.path)
        assertEquals(Json.parseToJsonElement("""{"provider":"oidc","refresh_token":"r1"}"""),
            Json.parseToJsonElement(sent.body?.decodeToString().orEmpty()))
        assertEquals(mapOf("Authorization" to "Bearer a2"), auth.authorizationHeaders())
        assertEquals(listOf("r2"), rotated.map { it.refreshToken })
    }

    @Test
    fun nativeSessionRefreshesBeforeExpiry() = runTest {
        val refresh = ScriptedTransport(ScriptedTransport.Step.Respond(200, issued))
        val auth = NativeSessionAuth(base, NativeSessionAuth.Tokens("a1", "r1", expiresAt = 0), refresh)
        assertEquals(mapOf("Authorization" to "Bearer a2"), auth.authorizationHeaders())
        assertEquals(mapOf("Authorization" to "Bearer a2"), auth.authorizationHeaders())
        assertEquals(1, refresh.requests.size)
    }

    @Test
    fun redirectsAreResultsNotFollowed() = runTest {
        val transport = ScriptedTransport(
            ScriptedTransport.Step.Respond(302, "", mapOf("Location" to "https://idp.example/authorize?x=1")))
        assertEquals(RESTRedirect(302, "https://idp.example/authorize?x=1"), client(transport).methods.web.authLogin("oidc"))
    }

    @Test
    fun binaryAndTextBodiesKeepTheirBytes() = runTest {
        val transport = ScriptedTransport(
            ScriptedTransport.Step.Respond(200, "PK\u0003\u0004", mapOf("Content-Type" to "application/zip")),
            ScriptedTransport.Step.Respond(200, "body{}", mapOf("Content-Type" to "text/css")),
        )
        val rest = client(transport)
        val archive = rest.methods.files.download("backups/a.zip")
        assertContentEquals("PK\u0003\u0004".encodeToByteArray(), archive.data)
        assertEquals("application/zip", archive.contentType)
        assertEquals("body{}", rest.methods.web.assetsCss("index"))
    }

    @Test
    fun multipartUploadsCarryFilesAndFields() = runTest {
        val transport = ScriptedTransport(ScriptedTransport.Step.Respond(500, "{}"))
        runCatching {
            client(transport).methods.files.uploadStream(
                RESTFile("a.txt", "hello".encodeToByteArray(), "text/plain"), path = "notes/a.txt")
        }
        val sent = transport.requests.single()
        val contentType = sent.contentType.orEmpty()
        assertTrue(contentType.startsWith("multipart/form-data; boundary="))
        val boundary = contentType.removePrefix("multipart/form-data; boundary=")
        val body = sent.body?.decodeToString().orEmpty()
        assertTrue(body.contains("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n" +
            "Content-Type: text/plain\r\n\r\nhello\r\n"))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"path\"\r\n\r\nnotes/a.txt\r\n"))
        assertTrue("name=\"overwrite\"" !in body)
        assertTrue(body.endsWith("--$boundary--\r\n"))
    }

    @Test
    fun severalSuccessStatusesDecodeToTheirCase() = runTest {
        val transport = ScriptedTransport(
            ScriptedTransport.Step.Respond(202, """{"status":"accepted","job_id":"j"}"""),
            ScriptedTransport.Step.Respond(200, """{"status":"duplicate","job_id":"j"}"""),
        )
        val rest = client(transport)
        assertEquals("accepted", assertIs<CronFireResult.Accepted>(rest.methods.cron.fire(CronFireRequest("j"))).value.status)
        assertEquals("duplicate", assertIs<CronFireResult.Ok>(rest.methods.cron.fire(CronFireRequest("j"))).value.status)
    }

    @Test
    fun liveScenarioPlaceholdersResolve() {
        val captured = mapOf("id" to JsonPrimitive("abc"), "n" to JsonPrimitive(3))
        assertEquals(JsonPrimitive(3), hermes.api.live.RESTScenario.resolve(JsonPrimitive("\${n}"), captured))
        assertEquals(JsonPrimitive("job-abc"), hermes.api.live.RESTScenario.resolve(JsonPrimitive("job-\${id}"), captured))
        assertEquals(JsonPrimitive("c"), hermes.api.live.RESTScenario.lookup(
            Json.parseToJsonElement("""{"a":[{"b":"c"}]}"""), "a.0.b"))
        assertIs<JsonObject>(hermes.api.live.RESTScenario.resolve(Json.parseToJsonElement("""{"x":"${'$'}{id}"}"""), captured))
    }
}
