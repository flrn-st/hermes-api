package hermes.api

import java.net.URI
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import hermes.api.testing.FakeGateway
import hermes.api.testing.GatewayFrames
import hermes.api.testing.ScriptedGatewaySocket
import hermes.api.testing.ScriptedGatewayTransport
import hermes.api.testing.ScriptedNetworkMonitor
import hermes.api.testing.ScriptedRESTCaller
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
}
