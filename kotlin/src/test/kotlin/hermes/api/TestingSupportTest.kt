package hermes.api

import java.net.URI
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import hermes.api.generated.gateway.ApprovalChoice
import hermes.api.generated.gateway.ApprovalResult
import hermes.api.generated.gateway.ClarifyRequestParams
import hermes.api.generated.gateway.ClarifyResult
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.PingParams
import hermes.api.generated.gateway.ServerRequest
import hermes.api.generated.gateway.ServerRequestResult
import hermes.api.runtime.GatewayConnectionState
import hermes.api.runtime.GatewayEvent
import hermes.api.runtime.HermesDashboardAddress
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayClient
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.HermesRESTException
import hermes.api.runtime.RESTCaller
import hermes.api.runtime.currentServerRequest
import hermes.api.testing.FakeGateway
import hermes.api.testing.GatewayFrames
import hermes.api.testing.ScriptedGatewaySocket
import hermes.api.testing.ScriptedGatewayTransport
import hermes.api.testing.ScriptedNetworkMonitor
import hermes.api.testing.ScriptedRESTCaller
import hermes.api.testing.SentAnswer
import hermes.api.testing.SentCall
import hermes.api.testing.StaticTicketAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** App code written against the interface, as an app would write it. */
private suspend fun connectAndAnswer(gateway: HermesGatewayClient): GatewayEvent = coroutineScope {
    gateway.setServerRequestHandler { request ->
        val clarify = request as? ServerRequest.Clarify ?: throw HermesGatewayException.Transport("Unexpected request")
        ServerRequestResult.Clarify(ClarifyResult(answer = "Answer for ${clarify.params.sessionId}"))
    }
    val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.first() }
    gateway.connect()
    gateway.methods.ping(PingParams())
    firstEvent.await()
}

class TestingSupportTest {
    @Test
    fun fakeGatewayStandsInForTheGateway() = runTest {
        val fake = FakeGateway(results = mapOf("ping" to JsonObject(mapOf("pong" to JsonPrimitive(true)))))
        val app = async { connectAndAnswer(fake) }
        fake.connectionStates.first { it == GatewayConnectionState.Connected }
        fake.emit("message.start", sessionId = "s1", seq = 1)
        val event = withTimeout(3_000) { app.await() }
        assertEquals("s1", event.sessionId)
        assertEquals(GatewayEventPayload.MessageStart, event.payload)
        assertEquals(listOf("ping"), fake.calls.map { it.method })
        assertEquals(listOf("connect"), fake.lifecycle)

        val answer = fake.request("clarify", JsonObject(mapOf("session_id" to JsonPrimitive("s1"))))
        assertEquals(ServerRequestResult.Clarify(ClarifyResult(answer = "Answer for s1")), answer)
    }

    @Test
    fun fakeGatewayRejectsAMismatchedAnswer() = runTest {
        val fake = FakeGateway()
        fake.setServerRequestHandler { ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Deny)) }
        assertFailsWith<HermesGatewayException.Protocol> {
            fake.request(ServerRequest.Clarify(ClarifyRequestParams(sessionId = "s1")))
        }
        assertEquals(-32601, assertFailsWith<HermesGatewayException.RPC> { fake.methods.ping(PingParams()) }.code)
    }

    @Test
    fun scriptedRESTCallerDecodesThroughGeneratedMethods() = runTest {
        val rest = ScriptedRESTCaller(routes = mapOf("GET /api/sessions/empty/count" to (200 to """{"count":4}""")))
        assertEquals(4, rest.methods.sessions.emptyCount(profile = "work").count)
        assertEquals(listOf(mapOf("profile" to "work")), rest.requests.map { it.query })
        assertEquals(404, assertFailsWith<HermesRESTException.HTTP> { rest.methods.sessions.getBySessionId("x") }.status)
    }

    @Test
    fun scriptedSocketDrivesARealGateway() = runTest {
        val socket = ScriptedGatewaySocket()
        val transport = ScriptedGatewayTransport(socket)
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(URI("https://hermes.test")), StaticTicketAuth(), transport,
            networkMonitor = ScriptedNetworkMonitor(),
        ), scope = backgroundScope)
        gateway.connect()
        val pong = async { gateway.methods.ping(PingParams()) }
        val call = SentCall(withTimeout(3_000) { socket.sent.receive() })
        assertEquals("ping", call.method)
        socket.inject(GatewayFrames.result(call.id, """{"pong":true}"""))
        assertTrue(pong.await().pong)
        assertEquals(listOf(listOf("hermes-gateway-v1", "hermes-gateway-ticket.test-ticket")), transport.protocols)
        gateway.disconnect()
    }

    @Test
    fun handlersSeeWhichRequestTheyAnswer() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(URI("https://hermes.test")), StaticTicketAuth(), ScriptedGatewayTransport(socket),
        ), scope = backgroundScope)
        gateway.setServerRequestHandler {
            val context = currentServerRequest() ?: error("No server request context")
            ServerRequestResult.Clarify(ClarifyResult(answer = "${context.method} ${context.id}"))
        }
        gateway.connect()
        socket.inject(GatewayFrames.serverRequest("srq-1", "clarify", """{"session_id":"s1"}"""))
        val answer = SentAnswer(withTimeout(3_000) { socket.sent.receive() })
        assertEquals("srq-1", answer.id)
        assertEquals(JsonObject(mapOf("answer" to JsonPrimitive("clarify srq-1"))), answer.result)
        gateway.disconnect()

        val fake = FakeGateway()
        fake.setServerRequestHandler { ServerRequestResult.Clarify(ClarifyResult(answer = currentServerRequest()?.id)) }
        val faked = fake.request(ServerRequest.Clarify(ClarifyRequestParams(sessionId = "s1")), id = "srq-2")
        assertEquals(ServerRequestResult.Clarify(ClarifyResult(answer = "srq-2")), faked)
        val caller: RESTCaller = ScriptedRESTCaller(routes = emptyMap())
        caller.methods.sessions
    }

    @Test
    fun openRequestsOfAResumedSessionReachTheHandler() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(URI("https://hermes.test")), StaticTicketAuth(), ScriptedGatewayTransport(socket),
        ), scope = backgroundScope)
        gateway.setServerRequestHandler { request ->
            assertIs<ServerRequest.Approval>(request)
            ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Once))
        }
        gateway.connect()
        val resume = async {
            gateway.call("session.resume", mapOf("session_id" to "stored-1"),
                MapSerializer(String.serializer(), String.serializer()), JsonElement.serializer())
        }
        val call = SentCall(withTimeout(3_000) { socket.sent.receive() })
        assertEquals("session.resume", call.method)
        socket.inject(GatewayFrames.result(call.id, """{"session_id":"s1","open_requests":[{"id":"srq-open",""" +
            """"method":"approval","params":{"session_id":"s1","request_id":"r1","command":"rm -rf /tmp/x"}}]}"""))
        resume.await()
        val answer = SentAnswer(withTimeout(3_000) { socket.sent.receive() })
        assertEquals("srq-open", answer.id)
        assertEquals(JsonObject(mapOf("choice" to JsonPrimitive("once"))), answer.result)
        gateway.disconnect()
    }
}
