package hermes.api

import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import hermes.api.generated.gateway.ApprovalChoice
import hermes.api.generated.gateway.ApprovalResult
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.HermesGatewayContract
import hermes.api.generated.gateway.PingParams
import hermes.api.generated.gateway.PingResult
import hermes.api.generated.gateway.ServerRequestResult
import hermes.api.generated.gateway.SessionCloseParams
import hermes.api.generated.gateway.SessionCreateParams
import hermes.api.runtime.DashboardTicketAuth
import hermes.api.runtime.GatewayConnection
import hermes.api.runtime.GatewayConnectionState
import hermes.api.runtime.GatewayCredential
import hermes.api.runtime.GatewayHTTPTransport
import hermes.api.runtime.GatewayNetworkMonitor
import hermes.api.runtime.GatewaySessionRecovery
import hermes.api.runtime.GatewayTransport
import hermes.api.runtime.HermesAuth
import hermes.api.runtime.HermesDashboardAddress
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.LocalTokenAuth
import hermes.api.runtime.withHermesRequestTimeout
import hermes.api.testing.GatewayFrames
import hermes.api.testing.ScriptedGatewaySocket
import hermes.api.testing.ScriptedGatewayTransport
import hermes.api.testing.ScriptedNetworkMonitor
import hermes.api.testing.SentAnswer
import hermes.api.testing.SentCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HermesGatewayTest {
    private fun TestScope.client(
        transport: ScriptedGatewayTransport, monitor: GatewayNetworkMonitor? = null, reconnectDelay: Long = 0,
        heartbeat: Long = 60_000, deadline: Long = 120_000, timeout: Long = 3_000,
        minimumContract: Int = HermesGatewayContract.desktopContract,
    ) = HermesGateway(HermesGatewayConfiguration(
        HermesDashboardAddress(URI("http://localhost:3000")), LocalTokenAuth("secret"), transport, networkMonitor = monitor,
        requestTimeoutMillis = timeout, reconnectDelayMillis = { reconnectDelay },
        heartbeatIntervalMillis = heartbeat, heartbeatDeadlineMillis = deadline, minimumContract = minimumContract,
    ), scope = backgroundScope)

    private fun sockets(vararg sockets: ScriptedGatewaySocket) = ScriptedGatewayTransport(*sockets)

    /** The next frame the client sent, as a parsed call. */
    private suspend fun ScriptedGatewaySocket.next(): SentCall = SentCall(withTimeout(3_000) { sent.receive() })

    private fun event(type: String, session: String, seq: Int, payload: String = "{}") =
        GatewayFrames.event(type, session, seq.toLong(), payload)

    private fun result(call: SentCall, json: String) = GatewayFrames.result(call.id, json)

    private fun error(call: SentCall, code: Int, message: String) = GatewayFrames.error(call.id, code, message)

    private fun SentCall.method() = method

    private val SentCall.fields: JsonObject get() = params.jsonObject

    private suspend fun HermesGateway.awaitState(matches: (GatewayConnectionState) -> Boolean) =
        withTimeout(5_000) { connectionStates.first(matches) }

    private suspend fun HermesGateway.awaitConnectedAfter(transport: ScriptedGatewayTransport, attempts: Int) =
        withTimeout(10_000) {
            while (transport.attempts < attempts || connectionStates.value != GatewayConnectionState.Connected) delay(10)
        }

    /** Creates a session through the client and answers it with a stored id. */
    private suspend fun TestScope.createSession(
        gateway: HermesGateway, socket: ScriptedGatewaySocket, id: String, stored: String, closeOnDisconnect: Boolean? = null,
    ) {
        val created = async { gateway.methods.session.create(SessionCreateParams(closeOnDisconnect = closeOnDisconnect)) }
        val frame = socket.next()
        assertEquals("session.create", frame.method())
        socket.inject(result(frame,
            """{"session_id":"$id","stored_session_id":"$stored","message_count":0,"messages":[],"info":{}}"""))
        runCatching { created.await() }
    }

    // Calls

    @Test
    fun connectsAndCorrelatesOutOfOrderCalls() = runTest {
        val socket = ScriptedGatewaySocket()
        var observedURI: URI? = null
        val transport = object : GatewayTransport {
            override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection {
                observedURI = uri
                return socket
            }
        }
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(URI("http://localhost:3000")), LocalTokenAuth("secret"), transport, requestTimeoutMillis = 3_000,
        ), scope = backgroundScope)
        gateway.connect()
        assertEquals("ws://localhost:3000/api/ws?token=secret", observedURI.toString())
        val first = async { gateway.methods.ping(PingParams()) }
        val second = async { gateway.methods.ping(PingParams()) }
        val frame1 = socket.next()
        val frame2 = socket.next()
        socket.inject(result(frame2, """{"pong":true}"""))
        socket.inject(result(frame1, """{"pong":true}"""))
        assertTrue(first.await().pong)
        assertTrue(second.await().pong)
        gateway.disconnect()
    }

    @Test
    fun rpcErrorsAreTyped() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        gateway.connect()
        val request = async { runCatching { gateway.methods.ping(PingParams()) } }
        socket.inject(error(socket.next(), 4015, "missing session"))
        val failure = assertFailsWith<HermesGatewayException.RPC> { request.await().getOrThrow() }
        assertEquals(4015, failure.code)
        gateway.disconnect()
    }

    /** Answers one ping with a session result that reports [contract]. */
    private suspend fun TestScope.pingReporting(contract: Int, gateway: HermesGateway, socket: ScriptedGatewaySocket): Result<PingResult> {
        val request = async { runCatching { gateway.methods.ping(PingParams()) } }
        socket.inject(result(socket.next(), """{"pong":true,"info":{"desktop_contract":$contract}}"""))
        return request.await()
    }

    @Test
    fun rejectsABackendBelowTheMinimumContract() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        gateway.connect()
        val older = HermesGatewayContract.desktopContract - 1
        val failure = assertFailsWith<HermesGatewayException.IncompatibleServer> {
            pingReporting(older, gateway, socket).getOrThrow()
        }
        assertEquals(older, failure.contract)
        assertEquals(older, gateway.backendContract)
        gateway.disconnect()
    }

    @Test
    fun acceptsNewerContractsAndOlderOnesTheAppAllows() = runTest {
        val socket = ScriptedGatewaySocket()
        val older = HermesGatewayContract.desktopContract - 1
        val gateway = client(sockets(socket), minimumContract = older)
        gateway.connect()
        assertEquals(null, gateway.backendContract)
        assertTrue(pingReporting(HermesGatewayContract.desktopContract + 1, gateway, socket).getOrThrow().pong)
        assertEquals(HermesGatewayContract.desktopContract + 1, gateway.backendContract)
        assertTrue(pingReporting(older, gateway, socket).getOrThrow().pong)
        assertEquals(older, gateway.backendContract)
        gateway.disconnect()
    }

    @Test
    fun callsDuringAReconnectWaitForTheNewSocket() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second), reconnectDelay = 500)
        gateway.connect()
        first.sever()
        gateway.awaitState { it is GatewayConnectionState.Reconnecting }
        val probe = async { gateway.methods.ping(PingParams()) }
        val frame = second.next()
        assertEquals("ping", frame.method())
        second.inject(result(frame, """{"pong":true}"""))
        assertTrue(probe.await().pong)
        gateway.disconnect()
    }

    @Test
    fun readOnlyCallsAreRepeatedAfterALostConnection() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second), timeout = 5_000)
        gateway.connect()
        val call = async { gateway.methods.ping(PingParams()) }
        assertEquals("ping", first.next().method())
        first.sever()
        val frame = second.next()
        assertEquals("ping", frame.method())
        second.inject(result(frame, """{"pong":true}"""))
        assertTrue(call.await().pong)
        gateway.disconnect()
    }

    @Test
    fun callsThatMayHaveRunAreNotRepeated() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second), timeout = 5_000)
        gateway.connect()
        val call = async { runCatching { gateway.call("probe", PingParams(), PingParams.serializer(), PingResult.serializer()) } }
        assertEquals("probe", first.next().method())
        first.sever()
        assertIs<HermesGatewayException.Transport>(call.await().exceptionOrNull())
        // The next frame on the new socket is the next call, not a repeat of the one that may have run.
        gateway.awaitState { it == GatewayConnectionState.Connected }
        val next = async { gateway.methods.ping(PingParams()) }
        val frame = second.next()
        assertEquals("ping", frame.method())
        second.inject(result(frame, """{"pong":true}"""))
        next.await()
        gateway.disconnect()
    }

    @Test
    fun undeliveredCallsAreSentOnTheNextConnection() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second), timeout = 5_000)
        gateway.connect()
        first.failSends()
        val call = async { gateway.call("probe", PingParams(), PingParams.serializer(), PingResult.serializer()) }
        val frame = second.next()
        assertEquals("probe", frame.method())
        second.inject(result(frame, """{"pong":true}"""))
        assertTrue(call.await().pong)
        assertTrue(first.isClosed)
        gateway.disconnect()
    }

    @Test
    fun retiringBackendTriggersAReconnect() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val transport = sockets(first, second)
        val gateway = client(transport)
        gateway.connect()
        val call = async { runCatching { gateway.methods.ping(PingParams()) } }
        first.inject(error(first.next(), 5035, "backend is retiring; reconnect to continue"))
        assertIs<HermesGatewayException.RPC>(call.await().exceptionOrNull())
        gateway.awaitConnectedAfter(transport, attempts = 2)
        assertTrue(first.isClosed)
        gateway.disconnect()
    }

    @Test
    fun genericFailureWithTheRetiringCodeKeepsTheConnection() = runTest {
        val socket = ScriptedGatewaySocket()
        val transport = sockets(socket)
        val gateway = client(transport)
        gateway.connect()
        val call = async { runCatching { gateway.methods.ping(PingParams()) } }
        // tools.configure answers 5035 for its own failures too; only the retiring message means Hermes is leaving.
        socket.inject(error(socket.next(), 5035, "could not write the toolset config"))
        assertIs<HermesGatewayException.RPC>(call.await().exceptionOrNull())
        val ping = async { gateway.methods.ping(PingParams()) }
        socket.inject(result(socket.next(), """{"pong":true}"""))
        assertTrue(ping.await().pong)
        assertFalse(socket.isClosed)
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
    fun connectionsKeepTheBaseURLPath() = runTest {
        val socket = ScriptedGatewaySocket()
        var ticketURI: URI? = null
        var socketURI: URI? = null
        var socketProtocols: List<String> = emptyList()
        val http = object : GatewayHTTPTransport {
            override suspend fun post(uri: URI, headers: Map<String, String>): Pair<Int, String> {
                ticketURI = uri
                return 200 to """{"ticket":"single-use","ttl_seconds":30}"""
            }
        }
        val transport = object : GatewayTransport {
            override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection {
                socketURI = uri
                socketProtocols = protocols
                return socket
            }
        }
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(URI("https://relay.example/agents/box/")), DashboardTicketAuth { emptyMap() }, transport, http,
            requestTimeoutMillis = 3_000,
        ), scope = backgroundScope)
        gateway.connect()
        assertEquals("https://relay.example/agents/box/api/auth/ws-ticket", ticketURI.toString())
        assertEquals("wss://relay.example/agents/box/api/ws", socketURI.toString())
        assertEquals(listOf("hermes-gateway-v1", "hermes-gateway-ticket.single-use"), socketProtocols)
        gateway.disconnect()
    }

    @Test
    fun everyConnectionAttemptResolvesTheAddressAfterThePreviousFailure() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val addresses = ArrayDeque(listOf("http://primary.example", "http://fallback.example", "http://primary.example"))
        val failures = mutableListOf<String?>()
        val connected = mutableListOf<String>()
        val scripted = ScriptedGatewayTransport(listOf(
            ScriptedGatewayTransport.Step.Failure(HermesGatewayException.Transport("primary unreachable")), ScriptedGatewayTransport.Step.Socket(first), ScriptedGatewayTransport.Step.Socket(second),
        ))
        val transport = object : GatewayTransport {
            override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection {
                connected += uri.toString().substringBefore("?")
                return scripted.connect(uri, headers, protocols)
            }
        }
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress { failure ->
                failures += failure?.message
                URI(if (addresses.size > 1) addresses.removeFirst() else addresses.first())
            },
            LocalTokenAuth("secret"), transport, requestTimeoutMillis = 3_000, reconnectDelayMillis = { 0 },
        ), scope = backgroundScope)
        gateway.connect()
        first.sever()
        gateway.awaitConnectedAfter(scripted, 3)
        assertEquals(listOf("ws://primary.example/api/ws", "ws://fallback.example/api/ws", "ws://primary.example/api/ws"),
            connected)
        assertEquals(listOf(null, "primary unreachable", "closed"), failures)
        gateway.disconnect()
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

    /** A credential that renews on every rejection, counting the renewals. */
    private class RenewingAuth : HermesAuth {
        @Volatile var renewals = 0

        override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential =
            GatewayCredential.LocalToken("secret", emptyMap())

        override suspend fun renew(failure: HermesGatewayException.AuthenticationFailed): Boolean {
            renewals++
            return true
        }
    }

    @Test
    fun ticketTransportFailuresRecoverDuringInitialConnectAndBackgroundReconnect() = runTest {
        val first = ScriptedGatewaySocket()
        val transport = ScriptedGatewayTransport(first, ScriptedGatewaySocket())
        var credentials = 0
        val failures = mutableListOf<Throwable?>()
        val auth = object : HermesAuth {
            override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential {
                credentials++
                if (credentials == 1 || credentials == 3) throw java.io.IOException("Ticket endpoint ended its response")
                return GatewayCredential.LocalToken("fixture", emptyMap())
            }
        }
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress { failure -> failures += failure; URI("http://localhost:3000") }, auth, transport,
            reconnectDelayMillis = { 100 }, requestTimeoutMillis = 3_000,
        ), scope = backgroundScope)
        withTimeout(3_000) { gateway.connect() }
        assertEquals(2, credentials)
        first.sever()
        gateway.awaitState { it == GatewayConnectionState.Connected && credentials == 4 }
        assertEquals(2, transport.attempts)
        assertEquals(2, failures.count { it is HermesGatewayException.Transport && it.message == "Ticket endpoint ended its response" })
        gateway.disconnect()
    }

    @Test
    fun renewedAuthenticationResolvesTheAddressAgainBeforeRequestingItsTicketAndSocket() = runTest {
        val primary = URI("https://primary.test/main")
        val fallback = URI("https://fallback.test/backup")
        var selected = primary
        val credentialAddresses = mutableListOf<URI>()
        val resolutionFailures = mutableListOf<Throwable?>()
        val transport = ScriptedGatewayTransport(ScriptedGatewaySocket())
        var renewals = 0
        val auth = object : HermesAuth {
            override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential {
                credentialAddresses += baseURI
                if (baseURI == primary) throw HermesGatewayException.AuthenticationFailed("Primary session expired")
                return GatewayCredential.Ticket("fallback-ticket", emptyMap())
            }
            override suspend fun renew(failure: HermesGatewayException.AuthenticationFailed): Boolean {
                renewals++
                selected = fallback
                return true
            }
        }
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress { failure -> resolutionFailures += failure; selected }, auth, transport,
            requestTimeoutMillis = 3_000,
        ), scope = backgroundScope)
        gateway.connect()
        assertEquals(listOf(primary, fallback), credentialAddresses)
        assertEquals(1, renewals)
        assertIs<HermesGatewayException.AuthenticationFailed>(resolutionFailures.last())
        assertEquals("fallback.test", transport.uris.single().host)
        assertTrue(transport.uris.single().path.startsWith("/backup/"))
        gateway.disconnect()
    }

    @Test
    fun aRenewedCredentialRetriesTheRejectedAttempt() = runTest {
        val rejected = HermesGatewayException.AuthenticationFailed("WebSocket upgrade returned HTTP 401")
        val transport = ScriptedGatewayTransport(listOf(ScriptedGatewayTransport.Step.Failure(rejected), ScriptedGatewayTransport.Step.Socket(ScriptedGatewaySocket())))
        val auth = RenewingAuth()
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(URI("http://localhost:3000")), auth, transport, requestTimeoutMillis = 3_000,
            reconnectDelayMillis = { 60_000 },
        ), scope = backgroundScope)
        gateway.connect()
        assertEquals(2, transport.attempts)
        assertEquals(1, auth.renewals)
        gateway.disconnect()
    }

    @Test
    fun aCredentialRenewsOncePerAttempt() = runTest {
        val rejected = HermesGatewayException.AuthenticationFailed("WebSocket upgrade returned HTTP 403")
        val transport = ScriptedGatewayTransport(listOf(ScriptedGatewayTransport.Step.Failure(rejected), ScriptedGatewayTransport.Step.Failure(rejected)))
        val auth = RenewingAuth()
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(URI("http://localhost:3000")), auth, transport, requestTimeoutMillis = 3_000,
        ), scope = backgroundScope)
        assertFailsWith<HermesGatewayException.AuthenticationFailed> { gateway.connect() }
        assertEquals(2, transport.attempts)
        assertEquals(1, auth.renewals)
    }

    @Test
    fun initialAuthenticationFailureIsTerminal() = runTest {
        val rejected = HermesGatewayException.AuthenticationFailed("WebSocket upgrade returned HTTP 403")
        val transport = ScriptedGatewayTransport(listOf(ScriptedGatewayTransport.Step.Failure(rejected)))
        val gateway = client(transport)
        assertEquals(rejected, assertFailsWith<HermesGatewayException.AuthenticationFailed> { gateway.connect() })
        assertEquals(GatewayConnectionState.Failed(rejected), gateway.connectionStates.value)
        delay(60_000)
        assertEquals(1, transport.attempts)
    }

    @Test
    fun authenticationFailureDuringReconnectStopsRetrying() = runTest {
        val first = ScriptedGatewaySocket()
        val rejected = HermesGatewayException.AuthenticationFailed("WebSocket upgrade returned HTTP 403")
        val transport = ScriptedGatewayTransport(listOf(ScriptedGatewayTransport.Step.Socket(first), ScriptedGatewayTransport.Step.Failure(rejected)))
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
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        val first = backgroundScope.async { gateway.events.take(2).toList() }
        val second = backgroundScope.async { gateway.events.take(2).toList() }
        gateway.connect()
        socket.inject(event("message.start", "s", 1))
        socket.inject(event("message.start", "s", 1))
        socket.inject(event("message.start", "s", 2))
        assertEquals(listOf(1L, 2L), withTimeout(3_000) { first.await() }.map { it.seq })
        assertEquals(listOf(1L, 2L), withTimeout(3_000) { second.await() }.map { it.seq })
        gateway.disconnect()
    }

    @Test
    fun aStalledCollectorDoesNotStallTheSocket() = runTest {
        val socket = ScriptedGatewaySocket(answersHeartbeats = true)
        val transport = sockets(socket)
        val gateway = client(transport, heartbeat = 1_000, deadline = 3_000)
        val stuck = CompletableDeferred<Unit>()
        backgroundScope.launch { gateway.events.collect { stuck.await() } }
        gateway.connect()
        repeat(2_000) { socket.inject(event("message.delta", "s", it + 1, """{"text":"x"}""")) }
        // Heartbeat replies still get through behind thousands of undelivered events.
        repeat(5) { withTimeout(5_000) { socket.heartbeats.receive() } }
        assertEquals(GatewayConnectionState.Connected, gateway.connectionStates.value)
        assertEquals(1, transport.attempts)
        gateway.disconnect()
    }

    @Test
    fun undecodableEventPayloadKeepsTheConnection() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        val received = backgroundScope.async { gateway.events.take(2).toList() }
        gateway.connect()
        socket.inject(event("message.delta", "s", 1, """{"text":{"nested":true}}"""))
        socket.inject("not json")
        socket.inject(event("message.start", "s", 2))
        val events = withTimeout(3_000) { received.await() }
        assertEquals("message.delta", assertIs<GatewayEventPayload.Unknown>(events[0].payload).type)
        assertEquals(2L, events[1].seq)
        assertEquals(GatewayConnectionState.Connected, gateway.connectionStates.value)
        gateway.disconnect()
    }

    // Server requests

    @Test
    fun concurrentHandlersKeepTheirWireIdentityAcrossSuspension() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        val arrived = kotlinx.coroutines.channels.Channel<String>(2)
        val release = CompletableDeferred<Unit>()
        gateway.setServerRequestHandler {
            val context = checkNotNull(kotlinx.coroutines.currentCoroutineContext()[hermes.api.runtime.ServerRequestContext])
            arrived.send(context.id)
            release.await()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                assertEquals(context, kotlinx.coroutines.currentCoroutineContext()[hermes.api.runtime.ServerRequestContext])
                assertEquals("approval", context.method)
            }
            ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Once))
        }
        gateway.connect()
        try {
            for (id in listOf("first", "second")) socket.inject(GatewayFrames.serverRequest(id, "approval", """{"session_id":"s","request_id":"different-param-id"}"""))
            assertEquals(setOf("first", "second"), setOf(arrived.receive(), arrived.receive()))
            assertEquals(null, kotlinx.coroutines.currentCoroutineContext()[hermes.api.runtime.ServerRequestContext])
            release.complete(Unit)
            val replies = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                List(2) { SentAnswer(withTimeout(3_000) { socket.sent.receive() }) }
            }
            assertEquals(setOf("first", "second"), replies.map { it.id }.toSet())
            assertTrue(replies.all { it.error == null })
        } finally { gateway.disconnect() }
    }

    @Test
    fun withdrawnDeliveryCleanupCannotUnregisterItsReplacement() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        val started = kotlinx.coroutines.channels.Channel<Int>(2)
        val oldCancelling = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val replacementCancelled = CompletableDeferred<Unit>()
        var deliveries = 0
        gateway.setServerRequestHandler {
            val delivery = ++deliveries
            try {
                started.send(delivery)
                awaitCancellation()
            } finally {
                if (delivery == 1) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    oldCancelling.complete(Unit)
                    releaseOld.await()
                    oldFinished.complete(Unit)
                } else replacementCancelled.complete(Unit)
            }
        }
        gateway.connect()
        try {
            fun deliver() = socket.inject(GatewayFrames.serverRequest("same-id", "approval", """{"session_id":"s","request_id":"req"}"""))
            fun withdraw() = socket.inject(GatewayFrames.event("request.cancel", "s", payload = """{"id":"same-id","method":"approval","reason":"withdrawn"}"""))
            deliver()
            assertEquals(1, started.receive())
            withdraw()
            oldCancelling.await()
            deliver()
            assertEquals(2, started.receive())
            releaseOld.complete(Unit)
            oldFinished.await()
            delay(100)
            withdraw()
            withTimeout(3_000) { replacementCancelled.await() }
            assertTrue(socket.sent.tryReceive().isFailure)
        } finally { releaseOld.complete(Unit); gateway.disconnect() }
    }

    @Test
    fun answersTypedServerRequest() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        gateway.setServerRequestHandler { ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Once)) }
        gateway.connect()
        socket.inject(GatewayFrames.serverRequest("srq-1", "approval", """{"session_id":"s","request_id":"req"}"""))
        val reply = SentAnswer(withTimeout(3_000) { socket.sent.receive() })
        assertEquals("srq-1", reply.id)
        assertEquals("once", checkNotNull(reply.result).jsonObject["choice"]?.jsonPrimitive?.content)
        gateway.disconnect()
    }

    @Test
    fun rejectsServerRequestWithInvalidParams() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        gateway.setServerRequestHandler { ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Deny)) }
        gateway.connect()
        socket.inject(GatewayFrames.serverRequest("srq-1", "approval", """{"command":7}"""))
        val reply = SentAnswer(withTimeout(3_000) { socket.sent.receive() })
        assertEquals("srq-1", reply.id)
        assertEquals("-32602", checkNotNull(reply.error).jsonObject["code"]?.jsonPrimitive?.content)
        gateway.disconnect()
    }

    @Test
    fun handlerThatGivesUpIsAnsweredWithAnError() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        gateway.setServerRequestHandler { throw kotlinx.coroutines.CancellationException("user dismissed") }
        gateway.connect()
        socket.inject(GatewayFrames.serverRequest("srq-1", "approval", """{"session_id":"s","request_id":"req"}"""))
        val reply = SentAnswer(withTimeout(3_000) { socket.sent.receive() })
        assertEquals("srq-1", reply.id)
        assertEquals("-32603", checkNotNull(reply.error).jsonObject["code"]?.jsonPrimitive?.content)
        gateway.disconnect()
    }

    @Test
    fun withdrawnRequestIsNotAnswered() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        gateway.setServerRequestHandler { awaitCancellation() }
        gateway.connect()
        socket.inject(GatewayFrames.serverRequest("srq-1", "approval", """{"session_id":"s","request_id":"req"}"""))
        socket.inject(GatewayFrames.event("request.cancel", "s", payload = """{"id":"srq-1","method":"approval","reason":"timeout"}"""))
        delay(100)
        // The next frame is the caller's own call, not an answer to the withdrawn request.
        val ping = async { gateway.methods.ping(PingParams()) }
        val next = socket.next()
        assertEquals("ping", next.method())
        socket.inject(result(next, """{"pong":true}"""))
        assertTrue(ping.await().pong)
        gateway.disconnect()
    }

    // Reconnect and session recovery

    @Test
    fun reconnectReplaysGapBeforeLiveEvents() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second))
        val received = backgroundScope.async { gateway.events.take(3).toList() }
        gateway.connect()
        first.inject(event("message.start", "s", 1))
        delay(100)
        first.sever()
        val activate = second.next()
        assertEquals("session.activate", activate.method())
        // A live frame racing the rebind is held until the gap replay lands.
        second.inject(event("message.start", "s", 3))
        second.inject(result(activate, """{"session_id":"s"}"""))
        val replay = second.next()
        assertEquals("session.events.since", replay.method())
        second.inject(result(replay, """{"events":[{"type":"message.start","session_id":"s","seq":2,"payload":{}}],"latest_seq":3,"truncated":false,"count":1,"epoch":"same","open_requests":[]}"""))
        val events = withTimeout(3_000) { received.await() }
        assertEquals(listOf(1L, 2L, 3L), events.map { it.seq })
        assertEquals(listOf(false, true, false), events.map { it.replayed })
        gateway.disconnect()
    }

    @Test
    fun failedReplayRetriesTheGapInsteadOfSkippingIt() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val third = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second, third))
        val received = backgroundScope.async { gateway.events.take(3).toList() }
        gateway.connect()
        first.inject(event("message.start", "s", 1))
        delay(100)
        first.sever()
        val activate = second.next()
        second.inject(event("message.delta", "s", 3, """{"text":"b"}"""))
        second.inject(result(activate, """{"session_id":"s"}"""))
        val replay = second.next()
        // The socket stays up but the replay is unusable: releasing seq 3 now would skip seq 2 for good.
        second.inject(result(replay, """{"unexpected":true}"""))
        val retryActivate = third.next()
        assertEquals("session.activate", retryActivate.method())
        third.inject(result(retryActivate, """{"session_id":"s"}"""))
        val retryReplay = third.next()
        assertEquals("session.events.since", retryReplay.method())
        assertEquals(1L, retryReplay.fields["last_seen"]?.jsonPrimitive?.long)
        third.inject(result(retryReplay, """{"events":[{"type":"message.delta","session_id":"s","seq":2,"payload":{"text":"a"}},{"type":"message.delta","session_id":"s","seq":3,"payload":{"text":"b"}}],"latest_seq":3,"truncated":false,"count":2,"epoch":"same","open_requests":[]}"""))
        val events = withTimeout(3_000) { received.await() }
        assertEquals(listOf(1L, 2L, 3L), events.map { it.seq })
        gateway.disconnect()
    }

    @Test
    fun reconnectRebindsCreatedSessionsWithoutEvents() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second))
        gateway.connect()
        createSession(gateway, first, "quiet", "stored-quiet")
        first.sever()
        val activate = second.next()
        val params = activate.fields
        assertEquals("session.activate", activate.method())
        assertEquals("quiet", params["session_id"]?.jsonPrimitive?.content)
        assertEquals("true", params["omit_messages"]?.jsonPrimitive?.content)
        gateway.disconnect()
    }

    @Test
    fun reconnectResumesReclaimedSessions() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        createSession(gateway, first, "runtime-1", "stored-1")
        first.sever()
        second.inject(error(second.next(), 4001, "session not found"))
        val resume = second.next()
        assertEquals("session.resume", resume.method())
        assertEquals("stored-1", resume.fields["session_id"]?.jsonPrimitive?.content)
        second.inject(result(resume, """{"session_id":"runtime-2","session_key":"stored-1","message_count":3,"messages":[],"info":{}}"""))
        assertEquals(GatewaySessionRecovery.Resumed("runtime-1", "runtime-2", "stored-1"), withTimeout(3_000) { recovery.await() })
        gateway.awaitState { it == GatewayConnectionState.Connected }
        gateway.disconnect()
    }

    @Test
    fun turnOfADroppedSessionKeepsStreamingFromTheReplayBuffer() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        val received = backgroundScope.async { gateway.events.take(3).toList() }
        gateway.connect()
        createSession(gateway, first, "runtime-1", "stored-1")
        first.inject(event("message.start", "runtime-1", 1))
        delay(100)
        first.sever()
        // Hermes dropped the session while its turn kept writing to the replay buffer.
        second.inject(error(second.next(), 4001, "session not found"))
        val replay = second.next()
        assertEquals("session.events.since", replay.method())
        assertEquals("runtime-1", replay.fields["session_id"]?.jsonPrimitive?.content)
        second.inject(result(replay, """{"events":[{"type":"message.delta","session_id":"runtime-1","seq":2,"payload":{"text":"a"}}],"latest_seq":2,"truncated":false,"count":1,"epoch":"same","open_requests":[]}"""))
        val resume = second.next()
        assertEquals("session.resume", resume.method())
        second.inject(result(resume, """{"session_id":"runtime-2","session_key":"stored-1","message_count":1,"messages":[],"info":{}}"""))
        assertEquals(GatewaySessionRecovery.Resumed("runtime-1", "runtime-2", "stored-1"), withTimeout(3_000) { recovery.await() })
        // Connected again, the client keeps fetching the old id's buffer until the turn completes.
        val drain = second.next()
        assertEquals("session.events.since", drain.method())
        assertEquals(2L, drain.fields["last_seen"]?.jsonPrimitive?.long)
        second.inject(result(drain, """{"events":[{"type":"message.complete","session_id":"runtime-1","seq":3,"payload":{"text":"ab"}}],"latest_seq":3,"truncated":false,"count":1,"epoch":"same","open_requests":[]}"""))
        val events = withTimeout(5_000) { received.await() }
        assertEquals(listOf("message.start", "message.delta", "message.complete"), events.map { it.type })
        assertTrue(events.drop(1).all { it.replayed && it.sessionId == "runtime-1" })
        // The turn is over: the next frame is the caller's own call, not another fetch.
        val ping = async { gateway.methods.ping(PingParams()) }
        val next = second.next()
        assertEquals("ping", next.method())
        second.inject(result(next, """{"pong":true}"""))
        assertTrue(ping.await().pong)
        gateway.disconnect()
    }

    @Test
    fun reconnectReportsSessionsItCannotRecover() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        first.inject(event("message.start", "gone", 4))
        delay(100)
        first.sever()
        val activate = second.next()
        assertEquals("session.activate", activate.method())
        second.inject(error(activate, 4001, "session not found"))
        // Its turn was still running, so the client first fetches what Hermes buffered for it.
        val replay = second.next()
        assertEquals("session.events.since", replay.method())
        second.inject(result(replay, """{"events":[],"latest_seq":4,"truncated":false,"count":0,"epoch":"same","open_requests":[]}"""))
        // No stored id is known for a session only seen through events, so it cannot be resumed.
        assertEquals(GatewaySessionRecovery.Unavailable("gone", "session not found"), withTimeout(3_000) { recovery.await() })
        val ping = async { gateway.methods.ping(PingParams()) }
        val next = second.next()
        assertEquals("ping", next.method())
        second.inject(result(next, """{"pong":true}"""))
        assertTrue(ping.await().pong)
        gateway.disconnect()
    }

    @Test
    fun truncatedReplayIsReported() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        createSession(gateway, first, "long", "stored-long")
        first.sever()
        second.inject(result(second.next(), """{"session_id":"long"}"""))
        second.inject(result(second.next(), """{"events":[],"latest_seq":900,"truncated":true,"count":0,"epoch":"same","open_requests":[]}"""))
        assertEquals(GatewaySessionRecovery.ReplayTruncated("long"), withTimeout(3_000) { recovery.await() })
        gateway.disconnect()
    }

    @Test
    fun closedAndCloseOnDisconnectSessionsAreNotRebound() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val gateway = client(sockets(first, second))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        createSession(gateway, first, "done", "stored-done")
        createSession(gateway, first, "ephemeral", "stored-e", closeOnDisconnect = true)
        val closed = async { gateway.methods.session.close(SessionCloseParams("done")) }
        first.inject(result(first.next(), """{"closed":true}"""))
        assertTrue(closed.await().closed)
        first.sever()
        assertEquals(GatewaySessionRecovery.Unavailable("ephemeral", "Closed on disconnect"), withTimeout(3_000) { recovery.await() })
        gateway.awaitState { it == GatewayConnectionState.Connected }
        val ping = async { gateway.methods.ping(PingParams()) }
        val next = second.next()
        assertEquals("ping", next.method())
        second.inject(result(next, """{"pong":true}"""))
        assertTrue(ping.await().pong)
        gateway.disconnect()
    }

    @Test
    fun reclaimedSessionIsReportedAndForgotten() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        val recovery = backgroundScope.async { gateway.sessionRecoveries.first() }
        gateway.connect()
        createSession(gateway, socket, "idle", "stored-idle")
        socket.inject(GatewayFrames.event("session.reclaimed", "", payload = """{"session_id":"idle","stored_session_id":"stored-idle","reason":"idle_timeout"}"""))
        assertEquals(GatewaySessionRecovery.Reclaimed("idle", "stored-idle", "idle_timeout"), withTimeout(3_000) { recovery.await() })
        gateway.disconnect()
    }

    // Liveness

    @Test
    fun silentSocketIsReplacedAfterTheHeartbeatDeadline() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
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
        val socket = ScriptedGatewaySocket(answersHeartbeats = true)
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
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket), heartbeat = 1_000, deadline = 3_000)
        gateway.connect()
        repeat(12) {
            socket.inject(event("message.delta", "s", it + 1, """{"text":"x"}"""))
            delay(400)
        }
        assertTrue(socket.heartbeats.tryReceive().isFailure)
        gateway.disconnect()
    }

    // Network and app lifecycle

    @Test
    fun losingTheNetworkWaitsWithoutRetrying() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val transport = sockets(first, second)
        val monitor = ScriptedNetworkMonitor(ScriptedNetworkMonitor.wifi)
        val gateway = client(transport, monitor = monitor)
        gateway.connect()
        monitor.change(ScriptedNetworkMonitor.offline)
        gateway.awaitState { it == GatewayConnectionState.WaitingForNetwork }
        assertTrue(first.isClosed)
        delay(60_000)
        assertEquals(1, transport.attempts)
        monitor.change(ScriptedNetworkMonitor.wifi)
        gateway.awaitConnectedAfter(transport, attempts = 2)
        gateway.disconnect()
    }

    @Test
    fun switchingNetworksReconnectsAtOnce() = runTest {
        val first = ScriptedGatewaySocket(answersHeartbeats = true)
        val second = ScriptedGatewaySocket()
        val transport = sockets(first, second)
        val monitor = ScriptedNetworkMonitor(ScriptedNetworkMonitor.wifi)
        val gateway = client(transport, monitor = monitor, reconnectDelay = 30_000)
        gateway.connect()
        monitor.change(ScriptedNetworkMonitor.cellular)
        // The first retry after a network switch is immediate, so a 30 s backoff never applies.
        withTimeout(1_000) {
            while (transport.attempts < 2 || gateway.connectionStates.value != GatewayConnectionState.Connected) delay(10)
        }
        assertTrue(first.isClosed)
        gateway.disconnect()
    }

    @Test
    fun backgroundClosesTheSocketAndForegroundReconnects() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
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
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        gateway.connect()
        socket.inject(event("message.start", "s", 1))
        delay(100)
        val backgrounded = async { gateway.enterBackground(graceMillis = 20_000) }
        delay(5_000)
        assertFalse(socket.isClosed)
        socket.inject(event("message.complete", "s", 2, """{"text":"done"}"""))
        backgrounded.await()
        assertTrue(socket.isClosed)
        assertEquals(GatewayConnectionState.Suspended, gateway.connectionStates.value)
        gateway.disconnect()
    }

    @Test
    fun backgroundGraceBoundsAStuckTurn() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        gateway.connect()
        socket.inject(event("message.start", "s", 1))
        delay(100)
        gateway.enterBackground(graceMillis = 1_000)
        assertTrue(socket.isClosed)
        assertEquals(GatewayConnectionState.Suspended, gateway.connectionStates.value)
        gateway.disconnect()
    }

    // Delivery to several collectors

    @Test
    fun aSlowCollectorDoesNotDelayAFastOne() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        val stuck = CompletableDeferred<Unit>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { gateway.events.collect { stuck.await() } }
        val fast = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { gateway.events.take(2_000).toList() }
        gateway.connect()
        repeat(2_000) { socket.inject(event("message.delta", "s", it + 1, """{"text":"x"}""")) }
        // A shared buffer would fill behind the stuck collector and hold the fast one back.
        assertEquals((1L..2_000L).toList(), withTimeout(5_000) { fast.await() }.map { it.seq })
        gateway.disconnect()
    }

    @Test
    fun everyRecoveryReachesASlowCollector() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket))
        val count = 300
        val release = CompletableDeferred<Unit>()
        val recoveries = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            gateway.sessionRecoveries.onEach { release.await() }.take(count).toList()
        }
        val reclaimedAll = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filter { it.type == "session.reclaimed" }.take(count).toList()
        }
        gateway.connect()
        for (index in 1..count) socket.inject(event("message.start", "s$index", 1))
        for (index in 1..count) {
            socket.inject(GatewayFrames.event("session.reclaimed", "",
                payload = """{"session_id":"s$index","stored_session_id":"stored-$index","reason":"idle_timeout"}"""))
        }
        withTimeout(5_000) { reclaimedAll.await() }
        release.complete(Unit)
        val delivered = withTimeout(5_000) { recoveries.await() }
        assertEquals((1..count).map { GatewaySessionRecovery.Reclaimed("s$it", "stored-$it", "idle_timeout") }, delivered)
        gateway.disconnect()
    }

    // Scoped timeouts

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aScopedTimeoutBoundsAppCalls() = runTest {
        val socket = ScriptedGatewaySocket()
        val gateway = client(sockets(socket), timeout = 120_000)
        gateway.connect()
        val started = testScheduler.currentTime
        val failure = assertFailsWith<HermesGatewayException.Timeout> {
            withHermesRequestTimeout(1_000) { gateway.methods.ping(PingParams()) }
        }
        assertEquals("ping", failure.method)
        assertEquals(1_000, testScheduler.currentTime - started)
        // Outside the scope the configured timeout applies again.
        val call = async { runCatching { gateway.methods.ping(PingParams()) } }
        assertEquals("ping", socket.next().method)
        assertEquals("ping", socket.next().method)
        delay(60_000)
        assertFalse(call.isCompleted)
        assertIs<HermesGatewayException.Timeout>(call.await().exceptionOrNull())
        gateway.disconnect()
    }
}
