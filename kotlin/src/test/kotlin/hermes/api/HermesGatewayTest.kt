package hermes.api

import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import hermes.api.generated.gateway.ApprovalChoice
import hermes.api.generated.gateway.ApprovalResult
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.PingParams
import hermes.api.generated.gateway.ServerRequestResult
import hermes.api.generated.gateway.SessionCloseParams
import hermes.api.generated.gateway.SessionCreateParams
import hermes.api.runtime.DashboardTicketAuth
import hermes.api.runtime.GatewayConnection
import hermes.api.runtime.GatewayConnectionState
import hermes.api.runtime.GatewayCredential
import hermes.api.runtime.GatewayHTTPTransport
import hermes.api.runtime.GatewayNetworkMonitor
import hermes.api.runtime.GatewayNetworkPath
import hermes.api.runtime.GatewaySessionRecovery
import hermes.api.runtime.GatewayTransport
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.LocalTokenAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HermesGatewayTest {
    /** One fake Hermes socket. It answers `client.capabilities`, optionally answers heartbeats, and hands
     *  every other frame the client sends to [outbound]. */
    private class FakeSocket(private val answersHeartbeats: Boolean = false) : GatewayConnection {
        val inbound = Channel<String>(Channel.UNLIMITED)
        val outbound = Channel<String>(Channel.UNLIMITED)
        val heartbeats = Channel<String>(Channel.UNLIMITED)
        @Volatile var isClosed = false

        override suspend fun send(text: String) {
            check(!isClosed) { "closed" }
            val frame = Json.parseToJsonElement(text).jsonObject
            when (frame["method"]?.jsonPrimitive?.content) {
                "client.capabilities" ->
                    inbound.send("""{"jsonrpc":"2.0","id":${frame["id"]},"result":{"server_requests":[]}}""")
                "gateway.ping" -> {
                    heartbeats.send(frame["id"]?.jsonPrimitive?.content.orEmpty())
                    if (answersHeartbeats) inbound.send("""{"jsonrpc":"2.0","id":${frame["id"]},"result":{"ok":true}}""")
                }
                else -> outbound.send(text)
            }
        }

        override suspend fun receive(): String = inbound.receive()

        override suspend fun close() {
            isClosed = true
            inbound.close()
        }

        /** Hermes (or the network) ends the socket. */
        fun sever() { inbound.close(IllegalStateException("severed")) }

        suspend fun sent(): JsonObject = Json.parseToJsonElement(withTimeout(3_000) { outbound.receive() }).jsonObject
    }

    private sealed interface Step {
        data class Socket(val socket: FakeSocket) : Step
        data class Failure(val error: HermesGatewayException) : Step
    }

    /** Hands out scripted sockets or failures in order; once exhausted, attempts wait until cancelled. */
    private class SequenceTransport(steps: List<Step>) : GatewayTransport {
        private val remaining = steps.toMutableList()
        @Volatile var attempts = 0

        override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection {
            attempts++
            if (remaining.isEmpty()) awaitCancellation()
            return when (val step = remaining.removeAt(0)) {
                is Step.Socket -> step.socket
                is Step.Failure -> throw step.error
            }
        }
    }

    private class TestNetworkMonitor(initial: GatewayNetworkPath) : GatewayNetworkMonitor {
        private val flow = MutableSharedFlow<GatewayNetworkPath>(replay = 1).also { it.tryEmit(initial) }

        override fun paths() = flow

        fun change(path: GatewayNetworkPath) { flow.tryEmit(path) }
    }

    private val wifi = GatewayNetworkPath(true, "wifi-1")
    private val cellular = GatewayNetworkPath(true, "cellular-2")
    private val offline = GatewayNetworkPath(false, null)

    private fun TestScope.client(
        transport: SequenceTransport, monitor: GatewayNetworkMonitor? = null, reconnectDelay: Long = 0,
        heartbeat: Long = 60_000, deadline: Long = 120_000, timeout: Long = 3_000,
    ) = HermesGateway(HermesGatewayConfiguration(
        URI("http://localhost:3000"), LocalTokenAuth("secret"), transport, networkMonitor = monitor,
        requestTimeoutMillis = timeout, reconnectDelayMillis = { reconnectDelay },
        heartbeatIntervalMillis = heartbeat, heartbeatDeadlineMillis = deadline,
    ), scope = backgroundScope)

    private fun sockets(vararg sockets: FakeSocket) = SequenceTransport(sockets.map { Step.Socket(it) })

    private fun event(type: String, session: String, seq: Int, payload: String = "{}") =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$session","seq":$seq,"payload":$payload}}"""

    private fun result(frame: JsonObject, json: String) = """{"jsonrpc":"2.0","id":${frame["id"]},"result":$json}"""

    private fun error(frame: JsonObject, code: Int, message: String) =
        """{"jsonrpc":"2.0","id":${frame["id"]},"error":{"code":$code,"message":"$message"}}"""

    private fun JsonObject.method() = this["method"]?.jsonPrimitive?.content

    private suspend fun HermesGateway.awaitState(matches: (GatewayConnectionState) -> Boolean) =
        withTimeout(5_000) { connectionStates.first(matches) }

    private suspend fun HermesGateway.awaitConnectedAfter(transport: SequenceTransport, attempts: Int) =
        withTimeout(10_000) {
            while (transport.attempts < attempts || connectionStates.value != GatewayConnectionState.Connected) delay(10)
        }

    /** Creates a session through the client and answers it with a stored id. */
    private suspend fun TestScope.createSession(
        gateway: HermesGateway, socket: FakeSocket, id: String, stored: String, closeOnDisconnect: Boolean? = null,
    ) {
        val created = async { gateway.methods.session.create(SessionCreateParams(closeOnDisconnect = closeOnDisconnect)) }
        val frame = socket.sent()
        assertEquals("session.create", frame.method())
        socket.inbound.send(result(frame,
            """{"session_id":"$id","stored_session_id":"$stored","message_count":0,"messages":[],"info":{}}"""))
        runCatching { created.await() }
    }

    // Calls

    @Test
    fun connectsAndCorrelatesOutOfOrderCalls() = runTest {
        val socket = FakeSocket()
        var observedURI: URI? = null
        val transport = object : GatewayTransport {
            override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection {
                observedURI = uri
                return socket
            }
        }
        val gateway = HermesGateway(HermesGatewayConfiguration(
            URI("http://localhost:3000"), LocalTokenAuth("secret"), transport, requestTimeoutMillis = 3_000,
        ), scope = backgroundScope)
        gateway.connect()
        assertEquals("ws://localhost:3000/api/ws?token=secret", observedURI.toString())
        val first = async { gateway.methods.ping(PingParams()) }
        val second = async { gateway.methods.ping(PingParams()) }
        val frame1 = socket.sent()
        val frame2 = socket.sent()
        socket.inbound.send(result(frame2, """{"pong":true}"""))
        socket.inbound.send(result(frame1, """{"pong":true}"""))
        assertTrue(first.await().pong)
        assertTrue(second.await().pong)
        gateway.disconnect()
    }

    @Test
    fun rpcErrorsAreTyped() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        gateway.connect()
        val request = async { runCatching { gateway.methods.ping(PingParams()) } }
        socket.inbound.send(error(socket.sent(), 4015, "missing session"))
        val failure = assertFailsWith<HermesGatewayException.RPC> { request.await().getOrThrow() }
        assertEquals(4015, failure.code)
        gateway.disconnect()
    }

    @Test
    fun callsDuringAReconnectWaitForTheNewSocket() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val gateway = client(sockets(first, second), reconnectDelay = 500)
        gateway.connect()
        first.sever()
        gateway.awaitState { it is GatewayConnectionState.Reconnecting }
        val probe = async { gateway.methods.ping(PingParams()) }
        val frame = second.sent()
        assertEquals("ping", frame.method())
        second.inbound.send(result(frame, """{"pong":true}"""))
        assertTrue(probe.await().pong)
        gateway.disconnect()
    }

    @Test
    fun retiringBackendTriggersAReconnect() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val transport = sockets(first, second)
        val gateway = client(transport)
        gateway.connect()
        val call = async { runCatching { gateway.methods.ping(PingParams()) } }
        first.inbound.send(error(first.sent(), 5035, "backend is retiring; reconnect to continue"))
        assertIs<HermesGatewayException.RPC>(call.await().exceptionOrNull())
        gateway.awaitConnectedAfter(transport, attempts = 2)
        assertTrue(first.isClosed)
        gateway.disconnect()
    }

    // Authentication

    @Test
    fun authenticatedTicketUsesPost() = runTest {
        var requested: URI? = null
        val http = object : GatewayHTTPTransport {
            override suspend fun post(uri: URI, headers: Map<String, String>): Pair<Int, String> {
                requested = uri
                assertEquals("Bearer example", headers["Authorization"])
                return 200 to """{"ticket":"single-use","ttl_seconds":30}"""
            }
        }
        val credential = DashboardTicketAuth { mapOf("Authorization" to "Bearer example") }
            .credential(URI("https://dashboard.example"), http)
        assertEquals("https://dashboard.example/api/auth/ws-ticket", requested.toString())
        assertEquals("single-use", assertIs<GatewayCredential.Ticket>(credential).value)
    }

    @Test
    fun rejectedTicketIsAnAuthenticationFailure() = runTest {
        val http = object : GatewayHTTPTransport {
            override suspend fun post(uri: URI, headers: Map<String, String>): Pair<Int, String> = 401 to "{}"
        }
        assertFailsWith<HermesGatewayException.AuthenticationFailed> {
            DashboardTicketAuth { emptyMap() }.credential(URI("https://dashboard.example"), http)
        }
    }

    @Test
    fun initialAuthenticationFailureIsTerminal() = runTest {
        val rejected = HermesGatewayException.AuthenticationFailed("WebSocket upgrade returned HTTP 403")
        val transport = SequenceTransport(listOf(Step.Failure(rejected)))
        val gateway = client(transport)
        assertEquals(rejected, assertFailsWith<HermesGatewayException.AuthenticationFailed> { gateway.connect() })
        assertEquals(GatewayConnectionState.Failed(rejected), gateway.connectionStates.value)
        delay(60_000)
        assertEquals(1, transport.attempts)
    }

    @Test
    fun authenticationFailureDuringReconnectStopsRetrying() = runTest {
        val first = FakeSocket()
        val rejected = HermesGatewayException.AuthenticationFailed("WebSocket upgrade returned HTTP 403")
        val transport = SequenceTransport(listOf(Step.Socket(first), Step.Failure(rejected)))
        val gateway = client(transport)
        gateway.connect()
        first.sever()
        gateway.awaitState { it == GatewayConnectionState.Failed(rejected) }
        delay(60_000)
        assertEquals(2, transport.attempts)
    }

    // Events

    @Test
    fun everyCollectorReceivesEveryEventOnce() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        val first = backgroundScope.async { gateway.events.take(2).toList() }
        val second = backgroundScope.async { gateway.events.take(2).toList() }
        gateway.connect()
        socket.inbound.send(event("message.start", "s", 1))
        socket.inbound.send(event("message.start", "s", 1))
        socket.inbound.send(event("message.start", "s", 2))
        assertEquals(listOf(1L, 2L), withTimeout(3_000) { first.await() }.map { it.seq })
        assertEquals(listOf(1L, 2L), withTimeout(3_000) { second.await() }.map { it.seq })
        gateway.disconnect()
    }

    @Test
    fun aStalledCollectorDoesNotStallTheSocket() = runTest {
        val socket = FakeSocket(answersHeartbeats = true)
        val transport = sockets(socket)
        val gateway = client(transport, heartbeat = 1_000, deadline = 3_000)
        val stuck = CompletableDeferred<Unit>()
        backgroundScope.launch { gateway.events.collect { stuck.await() } }
        gateway.connect()
        repeat(2_000) { socket.inbound.send(event("message.delta", "s", it + 1, """{"text":"x"}""")) }
        // Heartbeat replies still get through behind thousands of undelivered events.
        repeat(5) { withTimeout(5_000) { socket.heartbeats.receive() } }
        assertEquals(GatewayConnectionState.Connected, gateway.connectionStates.value)
        assertEquals(1, transport.attempts)
        gateway.disconnect()
    }

    @Test
    fun undecodableEventPayloadKeepsTheConnection() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        val received = backgroundScope.async { gateway.events.take(2).toList() }
        gateway.connect()
        socket.inbound.send(event("message.delta", "s", 1, """{"text":{"nested":true}}"""))
        socket.inbound.send("not json")
        socket.inbound.send(event("message.start", "s", 2))
        val events = withTimeout(3_000) { received.await() }
        assertEquals("message.delta", assertIs<GatewayEventPayload.Unknown>(events[0].payload).type)
        assertEquals(2L, events[1].seq)
        assertEquals(GatewayConnectionState.Connected, gateway.connectionStates.value)
        gateway.disconnect()
    }

    // Server requests

    @Test
    fun answersTypedServerRequest() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        gateway.setServerRequestHandler { ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Once)) }
        gateway.connect()
        socket.inbound.send("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s","request_id":"req"}}""")
        val reply = socket.sent()
        assertEquals("srq-1", reply["id"]?.jsonPrimitive?.content)
        assertEquals("once", checkNotNull(reply["result"]).jsonObject["choice"]?.jsonPrimitive?.content)
        gateway.disconnect()
    }

    @Test
    fun rejectsServerRequestWithInvalidParams() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        gateway.setServerRequestHandler { ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Deny)) }
        gateway.connect()
        socket.inbound.send("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"command":7}}""")
        val reply = socket.sent()
        assertEquals("srq-1", reply["id"]?.jsonPrimitive?.content)
        assertEquals("-32602", checkNotNull(reply["error"]).jsonObject["code"]?.jsonPrimitive?.content)
        gateway.disconnect()
    }

    @Test
    fun handlerThatGivesUpIsAnsweredWithAnError() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        gateway.setServerRequestHandler { throw kotlinx.coroutines.CancellationException("user dismissed") }
        gateway.connect()
        socket.inbound.send("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s","request_id":"req"}}""")
        val reply = socket.sent()
        assertEquals("srq-1", reply["id"]?.jsonPrimitive?.content)
        assertEquals("-32603", checkNotNull(reply["error"]).jsonObject["code"]?.jsonPrimitive?.content)
        gateway.disconnect()
    }

    @Test
    fun withdrawnRequestIsNotAnswered() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        gateway.setServerRequestHandler { awaitCancellation() }
        gateway.connect()
        socket.inbound.send("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s","request_id":"req"}}""")
        socket.inbound.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","session_id":"s","payload":{"id":"srq-1","method":"approval","reason":"timeout"}}}""")
        delay(100)
        // The next frame is the caller's own call, not an answer to the withdrawn request.
        val ping = async { gateway.methods.ping(PingParams()) }
        val next = socket.sent()
        assertEquals("ping", next.method())
        socket.inbound.send(result(next, """{"pong":true}"""))
        assertTrue(ping.await().pong)
        gateway.disconnect()
    }

    // Reconnect and session recovery

    @Test
    fun reconnectReplaysGapBeforeLiveEvents() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val gateway = client(sockets(first, second))
        val received = backgroundScope.async { gateway.events.take(3).toList() }
        gateway.connect()
        first.inbound.send(event("message.start", "s", 1))
        delay(100)
        first.sever()
        val activate = second.sent()
        assertEquals("session.activate", activate.method())
        // A live frame racing the rebind is held until the gap replay lands.
        second.inbound.send(event("message.start", "s", 3))
        second.inbound.send(result(activate, """{"session_id":"s"}"""))
        val replay = second.sent()
        assertEquals("session.events.since", replay.method())
        second.inbound.send(result(replay, """{"events":[{"type":"message.start","session_id":"s","seq":2,"payload":{}}],"latest_seq":3,"truncated":false,"count":1,"epoch":"same","open_requests":[]}"""))
        val events = withTimeout(3_000) { received.await() }
        assertEquals(listOf(1L, 2L, 3L), events.map { it.seq })
        assertEquals(listOf(false, true, false), events.map { it.replayed })
        gateway.disconnect()
    }

    @Test
    fun failedReplayReconnectsInsteadOfSkippingTheGap() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val third = FakeSocket()
        val gateway = client(sockets(first, second, third))
        val received = backgroundScope.async { gateway.events.take(3).toList() }
        gateway.connect()
        first.inbound.send(event("message.start", "s", 1))
        delay(100)
        first.sever()
        val activate = second.sent()
        second.inbound.send(event("message.start", "s", 3))
        second.inbound.send(result(activate, """{"session_id":"s"}"""))
        val replay = second.sent()
        // A busy backend fails the replay. Delivering the held seq 3 now would move past the gap for good.
        second.inbound.send(error(replay, -32000, "busy"))
        val reactivate = third.sent()
        assertEquals("session.activate", reactivate.method())
        third.inbound.send(result(reactivate, """{"session_id":"s"}"""))
        val retried = third.sent()
        assertEquals("session.events.since", retried.method())
        assertEquals("1", retried["params"]?.jsonObject?.get("last_seen")?.jsonPrimitive?.content)
        third.inbound.send(result(retried, """{"events":[{"type":"message.start","session_id":"s","seq":2,"payload":{}},{"type":"message.start","session_id":"s","seq":3,"payload":{}}],"latest_seq":3,"truncated":false,"count":2,"epoch":"same","open_requests":[]}"""))
        val events = withTimeout(3_000) { received.await() }
        assertEquals(listOf(1L, 2L, 3L), events.map { it.seq })
        assertEquals(listOf(false, true, true), events.map { it.replayed })
        gateway.disconnect()
    }

    @Test
    fun reconnectRebindsCreatedSessionsWithoutEvents() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val gateway = client(sockets(first, second))
        gateway.connect()
        createSession(gateway, first, "quiet", "stored-quiet")
        first.sever()
        val activate = second.sent()
        val params = checkNotNull(activate["params"]).jsonObject
        assertEquals("session.activate", activate.method())
        assertEquals("quiet", params["session_id"]?.jsonPrimitive?.content)
        assertEquals("true", params["omit_messages"]?.jsonPrimitive?.content)
        gateway.disconnect()
    }

    @Test
    fun reconnectResumesReclaimedSessions() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val gateway = client(sockets(first, second))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        createSession(gateway, first, "runtime-1", "stored-1")
        first.sever()
        second.inbound.send(error(second.sent(), 4001, "session not found"))
        val resume = second.sent()
        assertEquals("session.resume", resume.method())
        assertEquals("stored-1", checkNotNull(resume["params"]).jsonObject["session_id"]?.jsonPrimitive?.content)
        second.inbound.send(result(resume, """{"session_id":"runtime-2","session_key":"stored-1","message_count":3,"messages":[],"info":{}}"""))
        assertEquals(GatewaySessionRecovery.Resumed("runtime-1", "runtime-2", "stored-1"), withTimeout(3_000) { recovery.await() })
        gateway.awaitState { it == GatewayConnectionState.Connected }
        gateway.disconnect()
    }

    @Test
    fun reconnectReportsSessionsItCannotRecover() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val gateway = client(sockets(first, second))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        first.inbound.send(event("message.start", "gone", 4))
        delay(100)
        first.sever()
        val activate = second.sent()
        assertEquals("session.activate", activate.method())
        second.inbound.send(error(activate, 4001, "session not found"))
        // No stored id is known for a session only seen through events, so it cannot be resumed.
        assertEquals(GatewaySessionRecovery.Unavailable("gone", "session not found"), withTimeout(3_000) { recovery.await() })
        val ping = async { gateway.methods.ping(PingParams()) }
        val next = second.sent()
        assertEquals("ping", next.method())
        second.inbound.send(result(next, """{"pong":true}"""))
        assertTrue(ping.await().pong)
        gateway.disconnect()
    }

    @Test
    fun truncatedReplayIsReported() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val gateway = client(sockets(first, second))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        createSession(gateway, first, "long", "stored-long")
        first.sever()
        second.inbound.send(result(second.sent(), """{"session_id":"long"}"""))
        second.inbound.send(result(second.sent(), """{"events":[],"latest_seq":900,"truncated":true,"count":0,"epoch":"same","open_requests":[]}"""))
        assertEquals(GatewaySessionRecovery.ReplayTruncated("long"), withTimeout(3_000) { recovery.await() })
        gateway.disconnect()
    }

    @Test
    fun closedAndCloseOnDisconnectSessionsAreNotRebound() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val gateway = client(sockets(first, second))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        createSession(gateway, first, "done", "stored-done")
        createSession(gateway, first, "ephemeral", "stored-e", closeOnDisconnect = true)
        val closed = async { gateway.methods.session.close(SessionCloseParams("done")) }
        first.inbound.send(result(first.sent(), """{"closed":true}"""))
        assertTrue(closed.await().closed)
        first.sever()
        assertEquals(GatewaySessionRecovery.Unavailable("ephemeral", "Closed on disconnect"), withTimeout(3_000) { recovery.await() })
        gateway.awaitState { it == GatewayConnectionState.Connected }
        val ping = async { gateway.methods.ping(PingParams()) }
        val next = second.sent()
        assertEquals("ping", next.method())
        second.inbound.send(result(next, """{"pong":true}"""))
        assertTrue(ping.await().pong)
        gateway.disconnect()
    }

    @Test
    fun reclaimedSessionIsReportedAndForgotten() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        createSession(gateway, socket, "idle", "stored-idle")
        socket.inbound.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.reclaimed","session_id":"","payload":{"session_id":"idle","stored_session_id":"stored-idle","reason":"idle_timeout"}}}""")
        assertEquals(GatewaySessionRecovery.Reclaimed("idle", "stored-idle", "idle_timeout"), withTimeout(3_000) { recovery.await() })
        gateway.disconnect()
    }

    // Liveness

    @Test
    fun silentSocketIsReplacedAfterTheHeartbeatDeadline() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val transport = sockets(first, second)
        val gateway = client(transport, heartbeat = 1_000, deadline = 3_000)
        gateway.connect()
        assertTrue(withTimeout(5_000) { first.heartbeats.receive() }.startsWith("heartbeat-"))
        // The half-open first socket never answers; the gateway must move to the second one.
        gateway.awaitConnectedAfter(transport, attempts = 2)
        assertTrue(first.isClosed)
        gateway.disconnect()
    }

    @Test
    fun answeredHeartbeatsKeepTheConnection() = runTest {
        val socket = FakeSocket(answersHeartbeats = true)
        val transport = sockets(socket)
        val gateway = client(transport, heartbeat = 1_000, deadline = 3_000)
        gateway.connect()
        repeat(8) { withTimeout(5_000) { socket.heartbeats.receive() } }
        assertEquals(GatewayConnectionState.Connected, gateway.connectionStates.value)
        assertEquals(1, transport.attempts)
        gateway.disconnect()
    }

    @Test
    fun streamingTrafficNeedsNoHeartbeat() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket), heartbeat = 1_000, deadline = 3_000)
        gateway.connect()
        repeat(12) {
            socket.inbound.send(event("message.delta", "s", it + 1, """{"text":"x"}"""))
            delay(400)
        }
        assertTrue(socket.heartbeats.tryReceive().isFailure)
        gateway.disconnect()
    }

    // Network and app lifecycle

    @Test
    fun losingTheNetworkWaitsWithoutRetrying() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val transport = sockets(first, second)
        val monitor = TestNetworkMonitor(wifi)
        val gateway = client(transport, monitor = monitor)
        gateway.connect()
        monitor.change(offline)
        gateway.awaitState { it == GatewayConnectionState.WaitingForNetwork }
        assertTrue(first.isClosed)
        delay(60_000)
        assertEquals(1, transport.attempts)
        monitor.change(wifi)
        gateway.awaitConnectedAfter(transport, attempts = 2)
        gateway.disconnect()
    }

    @Test
    fun switchingNetworksReconnectsAtOnce() = runTest {
        val first = FakeSocket(answersHeartbeats = true)
        val second = FakeSocket()
        val transport = sockets(first, second)
        val monitor = TestNetworkMonitor(wifi)
        val gateway = client(transport, monitor = monitor, reconnectDelay = 30_000)
        gateway.connect()
        monitor.change(cellular)
        // The first retry after a network switch is immediate, so a 30 s backoff never applies.
        withTimeout(1_000) {
            while (transport.attempts < 2 || gateway.connectionStates.value != GatewayConnectionState.Connected) delay(10)
        }
        assertTrue(first.isClosed)
        gateway.disconnect()
    }

    @Test
    fun backgroundClosesTheSocketAndForegroundReconnects() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val transport = sockets(first, second)
        val gateway = client(transport)
        gateway.connect()
        gateway.enterBackground()
        assertEquals(GatewayConnectionState.Suspended, gateway.connectionStates.value)
        assertTrue(first.isClosed)
        delay(60_000)
        assertEquals(1, transport.attempts)
        gateway.enterForeground()
        gateway.awaitConnectedAfter(transport, attempts = 2)
        gateway.disconnect()
    }

    @Test
    fun backgroundLetsARunningTurnFinish() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        gateway.connect()
        socket.inbound.send(event("message.start", "s", 1))
        delay(100)
        val backgrounded = async { gateway.enterBackground(graceMillis = 20_000) }
        delay(5_000)
        assertFalse(socket.isClosed)
        socket.inbound.send(event("message.complete", "s", 2, """{"text":"done"}"""))
        backgrounded.await()
        assertTrue(socket.isClosed)
        assertEquals(GatewayConnectionState.Suspended, gateway.connectionStates.value)
        gateway.disconnect()
    }

    @Test
    fun backgroundGraceBoundsAStuckTurn() = runTest {
        val socket = FakeSocket()
        val gateway = client(sockets(socket))
        gateway.connect()
        socket.inbound.send(event("message.start", "s", 1))
        delay(100)
        gateway.enterBackground(graceMillis = 1_000)
        assertTrue(socket.isClosed)
        assertEquals(GatewayConnectionState.Suspended, gateway.connectionStates.value)
        gateway.disconnect()
    }
}
