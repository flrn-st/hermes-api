package hermes.api.runtime

import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import hermes.api.generated.gateway.ClientCapabilitiesParams
import hermes.api.generated.gateway.ClientCapabilitiesResult
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.GatewayKnownError
import hermes.api.generated.gateway.GatewayMethodCatalog
import hermes.api.generated.gateway.HermesGatewayContract
import hermes.api.generated.gateway.ServerRequest
import hermes.api.generated.gateway.OpenRequestEntry
import hermes.api.generated.gateway.RequestCancelPayload
import hermes.api.generated.gateway.ServerRequestResult
import hermes.api.generated.gateway.SessionTitlePayload
import hermes.api.generated.gateway.SessionActivateParams
import hermes.api.generated.gateway.SessionEventsSinceParams
import hermes.api.generated.gateway.SessionEventsSinceResult
import hermes.api.generated.gateway.SessionResumeParams
import hermes.api.generated.gateway.SessionResumeResult

/** A session this client rebinds after a reconnect, with what it needs to resume it from storage. */
private data class TrackedSession(
    val storedId: String? = null,
    val profile: String? = null,
    val source: String? = null,
    /** Hermes tears these down as soon as the socket closes; they are never rebound. */
    val closeOnDisconnect: Boolean = false,
)

private sealed interface Rebind {
    data object Bound : Rebind
    data class Gone(val reason: String) : Rebind
    /** Hermes is still interrupting the turn it orphaned; no live event of the session reaches this socket. */
    data object Settling : Rebind
}

/**
 * A WebSocket JSON-RPC client for one Hermes dashboard.
 *
 * Once [connect] succeeds, the gateway keeps the connection alive until [disconnect]: it detects dead
 * sockets with a heartbeat, reconnects with jittered backoff when the network allows, rebinds the sessions
 * it created, replays the events it missed, re-delivers open server requests, and resumes sessions Hermes
 * reclaimed meanwhile. Apps call [enterBackground] and [enterForeground] from their lifecycle so no socket,
 * heartbeat or retry runs while the app is in the background.
 *
 * All state lives on one confined dispatcher, so the gateway behaves like an actor: its suspending work
 * interleaves only at suspension points.
 */
public class HermesGateway(
    private val configuration: HermesGatewayConfiguration,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : HermesGatewayClient {
    private val confined: CoroutineDispatcher =
        ((scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.IO).limitedParallelism(1)
    private val logger = configuration.logger

    private var socket: GatewayConnection? = null
    private var reader: Job? = null
    private var heartbeat: Job? = null
    private var maintainer: Job? = null
    private var maintainerRun = 0
    private var monitor: Job? = null
    private var generation = 0
    /** Set by [connect], cleared by [disconnect] or a terminal failure. */
    private var wantsConnection = false
    private var opening = false
    /** The socket is open, sessions are rebound, and app calls may use it. */
    private var ready = false
    private var inBackground = false
    private var networkKnown = false
    private var networkAvailable = true
    private var network: String? = null
    private var inboundSinceTick = false
    private var heartbeatMethod = "gateway.ping"
    private var heartbeatSequence = 0

    private val pending = mutableMapOf<Int, CompletableDeferred<JsonElement>>()
    private val serverJobs = mutableMapOf<String, Job>()
    private val settledSnapshotRequests = mutableSetOf<String>()
    @Volatile private var handler: (suspend (ServerRequest) -> ServerRequestResult)? = null
    private var nextID = 1

    private val sessions = mutableMapOf<String, TrackedSession>()
    private val lastSequence = mutableMapOf<String, Long>()
    private val activeTurns = mutableSetOf<String>()
    private var replayEpoch: String? = null
    private var hasConnected = false
    private val replayHold = mutableMapOf<String, MutableList<JsonObject>>()
    /** Turns still streaming when Hermes dropped their session, by runtime id, with when draining began
     *  (nanoTime). The reply keeps landing in Hermes' replay buffer under that id, so the client fetches it
     *  until the turn ends. */
    private val draining = mutableMapOf<String, Long>()
    private var drainJob: Job? = null

    /** The desktop contract Hermes last reported in a session result, or `null` before the first one. */
    @Volatile override var backendContract: Int? = null
        private set

    /** The current connection's `gateway.ready` reported `session_authority: true`. */
    private var authorityConnection = false
    /** Each authority session numbers its events within its own replay epoch, unrelated to `gateway.ready`'s. */
    private val sessionEpochs = mutableMapOf<String, String>()
    private val authority = SessionAuthority(
        epochOf = { sessionEpochs[it] },
        sequenceOf = { lastSequence[it] },
        onSnapshot = { snapshot, explicit -> adoptSnapshotWatermark(snapshot, explicit) },
    )

    @Volatile override var runtime: GatewayRuntime? = null
        private set

    override val sessionAuthority: Boolean get() = runtime?.sessionAuthority == true

    override suspend fun liveSessionId(storedSessionId: String, profile: String?): String? = withContext(confined) {
        fun name(value: String?) = value?.takeIf { it.isNotEmpty() } ?: "default"
        val candidates = sessions.filter { (id, session) ->
            (id == storedSessionId || session.storedId == storedSessionId) && name(session.profile) == name(profile)
        }
        if (storedSessionId in candidates) storedSessionId else candidates.keys.minOrNull()
    }

    // Every collector buffers independently and without bound, so a slow collector delays only itself and
    // never stalls the socket (and with it heartbeat replies).
    private val eventBroadcast = Broadcast<GatewayEvent>()
    private val mutableStates = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
    private val recoveryBroadcast = Broadcast<GatewaySessionRecovery>()
    private val turnActivity = MutableStateFlow(0)

    override val methods: GatewayMethodCatalog = GatewayMethodCatalog(this)
    override val events: Flow<GatewayEvent> = eventBroadcast.flow
    override val connectionStates: StateFlow<GatewayConnectionState> = mutableStates
    override val sessionRecoveries: Flow<GatewaySessionRecovery> = recoveryBroadcast.flow

    override fun setServerRequestHandler(value: suspend (ServerRequest) -> ServerRequestResult) {
        handler = value
    }

    override suspend fun restoreServerRequests(requests: List<OpenRequestEntry>): Unit = withContext(confined) {
        if (requests.isEmpty()) return@withContext
        val connection = socket?.takeIf { ready } ?: throw HermesGatewayException.Transport("Gateway is not connected")
        requests.forEach { handleServerRequest(it.id, it.method, JsonObject(it.params), connection, generation, snapshot = true) }
    }

    // Connection lifecycle

    /** Connects and keeps the connection alive until [disconnect]. Returns once connected; while the
     *  network is down or the server unreachable it keeps retrying. Throws only a failure retrying cannot
     *  fix ([HermesGatewayException.AuthenticationFailed], [HermesGatewayException.IncompatibleServer]). */
    override suspend fun connect() {
        val alreadyReady = withContext(confined) {
            if (ready) return@withContext true
            wantsConnection = true
            startNetworkMonitor()
            if (maintainer == null && !opening && !inBackground && networkAvailable) {
                // Publish before waiting so a stale Failed or Idle does not end the wait at once.
                publish(if (hasConnected) GatewayConnectionState.Reconnecting(1) else GatewayConnectionState.Connecting)
                startMaintaining(delayFirstAttempt = false)
            } else if (!networkAvailable) {
                publish(GatewayConnectionState.WaitingForNetwork)
            }
            false
        }
        if (!alreadyReady) awaitConnection(null)
    }

    /** Closes the connection and stops reconnecting. Tracked sessions stay known, so a later [connect]
     *  rebinds or resumes them. */
    override suspend fun disconnect(): Unit = withContext(confined + NonCancellable) {
        wantsConnection = false
        cancelMaintaining()
        monitor?.cancel()
        monitor = null
        networkKnown = false
        generation++
        drainJob?.cancel()
        drainJob = null
        closeConnection(HermesGatewayException.Transport("Gateway disconnected"))
        publish(GatewayConnectionState.Idle)
    }

    /** Call when the app moves to the background (for example from `ProcessLifecycleOwner.onStop`). A
     *  streaming turn gets up to [graceMillis] to finish; then the socket closes and nothing runs until
     *  [enterForeground]. Hermes keeps running turns alive and replays what the client missed. */
    override suspend fun enterBackground(graceMillis: Long): Unit = withContext(confined) {
        if (inBackground) return@withContext
        inBackground = true
        cancelMaintaining()
        if (!wantsConnection) return@withContext
        if (ready && activeTurns.isNotEmpty()) {
            val current = generation
            logger.log(GatewayLogLevel.INFO, "Background: waiting for ${activeTurns.size} running turn(s)")
            withTimeoutOrNull(graceMillis) { turnActivity.first { it == 0 } }
            if (!inBackground || current != generation) return@withContext
        }
        logger.log(GatewayLogLevel.INFO, "Background: closing the gateway socket")
        generation++
        closeConnection(HermesGatewayException.Transport("Gateway suspended in the background"))
        publish(GatewayConnectionState.Suspended)
    }

    /** Call when the app returns to the foreground. Reconnects at once and replays what was missed. */
    override suspend fun enterForeground(): Unit = withContext(confined) {
        if (!inBackground) return@withContext
        inBackground = false
        if (!wantsConnection || socket != null || maintainer != null || opening) return@withContext
        if (networkAvailable) startMaintaining(delayFirstAttempt = false)
        else publish(GatewayConnectionState.WaitingForNetwork)
    }

    /** [failure] is why the connection before ended, if it failed. */
    private fun startMaintaining(delayFirstAttempt: Boolean, failure: HermesGatewayException? = null) {
        cancelMaintaining()
        val run = ++maintainerRun
        maintainer = scope.launch(confined) { maintainConnection(delayFirstAttempt, failure, run) }
    }

    private fun cancelMaintaining() {
        maintainer?.cancel()
        maintainer = null
    }

    private suspend fun maintainConnection(delayFirstAttempt: Boolean, failure: HermesGatewayException?, run: Int) {
        var attempt = 0
        var previousFailure = failure
        try {
            while (wantsConnection) {
                if (inBackground) { publish(GatewayConnectionState.Suspended); break }
                // The network monitor restarts this loop when a path appears; nothing polls meanwhile.
                if (!networkAvailable) { publish(GatewayConnectionState.WaitingForNetwork); break }
                attempt++
                publish(if (hasConnected) GatewayConnectionState.Reconnecting(attempt) else GatewayConnectionState.Connecting)
                if (attempt > 1 || delayFirstAttempt) delay(configuration.reconnectDelayMillis(attempt))
                try {
                    open(previousFailure)
                    break
                } catch (error: HermesGatewayException) {
                    if (error.isTerminal) { fail(error); break }
                    previousFailure = error
                    logger.log(GatewayLogLevel.INFO, "Connection attempt $attempt failed: ${error.message}")
                }
            }
        } finally {
            if (run == maintainerRun) maintainer = null
        }
    }

    /** One connection attempt: address, credential, socket, capabilities, then session recovery. */
    private suspend fun open(previousFailure: HermesGatewayException?) {
        opening = true
        val current = ++generation
        try {
            val baseURI = try { configuration.address.resolve(previousFailure) }
            catch (error: CancellationException) { throw error }
            catch (error: HermesGatewayException) { throw error }
            catch (error: Exception) {
                throw HermesGatewayException.Transport("Cannot resolve the dashboard address: ${error.message}")
            }
            val connection = openSocket(baseURI)
            if (current != generation || !wantsConnection) {
                connection.close()
                throw CancellationException("Connection attempt superseded")
            }
            socket = connection
            inboundSinceTick = true
            authorityConnection = false
            if (hasConnected) trackedSessionIds().forEach { replayHold[it] = mutableListOf() }
            reader = scope.launch(confined) { readLoop(connection, current) }
            // Hermes forgets this per connection; without it every server request fails immediately.
            call("client.capabilities", ClientCapabilitiesParams(serverRequests = true),
                ClientCapabilitiesParams.serializer(), ClientCapabilitiesResult.serializer(),
                configuration.connectTimeoutMillis, waitsForConnection = false)
            if (socket !== connection) throw HermesGatewayException.Transport("Connection ended during handshake")
            describeRuntime(connection, current)
            if (hasConnected) recoverSessions(connection, current)
            if (socket !== connection) throw HermesGatewayException.Transport("Connection ended during session recovery")
            hasConnected = true
            ready = true
            logger.log(GatewayLogLevel.INFO, "Gateway connected")
            publish(GatewayConnectionState.Connected)
            heartbeat = scope.launch(confined) { heartbeatLoop(connection, current) }
            startDraining()
        } catch (error: Throwable) {
            if (current == generation) {
                generation++
                withContext(NonCancellable) { closeConnection(gatewayError(error)) }
            }
            // Credential providers may use their own HTTP client. Normalize
            // ordinary failures too, so a ticket endpoint dropping its response
            // cannot escape the background maintainer and crash its application.
            // Cancellation and fatal VM errors keep their original semantics.
            throw if (error is Exception && error !is CancellationException) gatewayError(error) else error
        } finally {
            opening = false
        }
    }

    /** Opens the WebSocket with a fresh credential. When Hermes rejects it, the credential may renew itself
     *  once and the socket is opened again with the new one. */
    private suspend fun openSocket(baseURI: URI): GatewayConnection {
        var renewed = false
        var currentBase = baseURI
        while (true) {
            try {
                val credential = configuration.auth.credential(currentBase, configuration.httpTransport)
                val (uri, headers, protocols) = socketRequest(currentBase, credential)
                return try { configuration.transport.connect(uri, headers, protocols) }
                catch (error: CancellationException) { throw error }
                catch (error: HermesGatewayException) { throw error }
                catch (error: Exception) { throw HermesGatewayException.Transport(error.message ?: "WebSocket connect failed") }
            } catch (error: HermesGatewayException.AuthenticationFailed) {
                if (renewed || !configuration.auth.renew(error)) throw error
                renewed = true
                // Renewal may select a different endpoint with its own saved
                // session. Both its ticket and socket must use the new address.
                currentBase = configuration.address.resolve(error)
                logger.log(GatewayLogLevel.INFO, "Hermes rejected the credential; retrying with the renewed one")
            }
        }
    }

    private fun fail(error: HermesGatewayException) {
        logger.log(GatewayLogLevel.ERROR, "Gateway failed: ${error.message}")
        wantsConnection = false
        cancelMaintaining()
        publish(GatewayConnectionState.Failed(error))
    }

    /** The socket died underneath a working connection. The first retry is jittered unless the cause is
     *  known to be fixed already, such as a new network path. */
    private suspend fun connectionLost(error: HermesGatewayException, current: Int, retryImmediately: Boolean = false) {
        if (current != generation) return
        val wasReady = ready
        generation++
        logger.log(GatewayLogLevel.INFO, "Connection lost: ${error.message}")
        withContext(NonCancellable) { closeConnection(error) }
        // During open() the attempt itself reports the failure.
        if (!wasReady || !wantsConnection) return
        if (error.isTerminal) fail(error)
        else if (!inBackground) startMaintaining(delayFirstAttempt = !retryImmediately, failure = error)
    }

    private suspend fun closeConnection(error: HermesGatewayException) {
        val previous = socket
        socket = null
        ready = false
        reader?.cancel()
        reader = null
        heartbeat?.cancel()
        heartbeat = null
        serverJobs.values.forEach { it.cancel() }
        serverJobs.clear()
        settledSnapshotRequests.clear()
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        replayHold.clear()
        previous?.let { runCatching { it.close() } }
    }

    private fun publish(state: GatewayConnectionState) {
        mutableStates.value = state
    }

    /** Waits until the gateway is connected, or throws what prevents it. */
    private suspend fun awaitConnection(timeoutMillis: Long?) {
        val wait: suspend () -> Unit = {
            mutableStates.first { state ->
                when (state) {
                    GatewayConnectionState.Connected -> withContext(confined) { ready }
                    is GatewayConnectionState.Failed -> throw state.error
                    GatewayConnectionState.Idle -> throw HermesGatewayException.Transport("Gateway is disconnected")
                    else -> false
                }
            }
        }
        if (timeoutMillis == null) wait()
        else try { withTimeout(timeoutMillis) { wait() } }
        catch (_: TimeoutCancellationException) { throw HermesGatewayException.Timeout("connection") }
    }

    // Network

    private fun startNetworkMonitor() {
        val source = configuration.networkMonitor ?: return
        if (monitor != null) return
        monitor = scope.launch(confined) { source.paths().collect { networkChanged(it) } }
    }

    private suspend fun networkChanged(path: GatewayNetworkPath) {
        val changed = !networkKnown || path.isAvailable != networkAvailable || path.network != network
        val networkSwitched = networkKnown && path.network != network
        networkKnown = true
        networkAvailable = path.isAvailable
        network = path.network
        if (!changed || !wantsConnection || inBackground) return
        if (!path.isAvailable) {
            logger.log(GatewayLogLevel.INFO, "Network unavailable")
            cancelMaintaining()
            if (socket != null) {
                generation++
                closeConnection(HermesGatewayException.Transport("Network unavailable"))
            }
            publish(GatewayConnectionState.WaitingForNetwork)
        } else if (socket == null) {
            // A path appeared or changed while disconnected: retry now instead of after backoff.
            if (opening && maintainer != null) return
            startMaintaining(delayFirstAttempt = false)
        } else if (networkSwitched && ready) {
            // Sockets stay bound to the network they opened on, which may no longer route.
            connectionLost(HermesGatewayException.Transport("Network changed"), generation, retryImmediately = true)
        }
    }

    // Calls

    /** Bounded by [HermesGatewayConfiguration.requestTimeoutMillis], or by the timeout of an enclosing
     *  [withHermesRequestTimeout]. */
    override suspend fun <Params : Any, Result : Any> call(
        method: String,
        params: Params,
        paramsSerializer: KSerializer<Params>,
        resultSerializer: KSerializer<Result>,
    ): Result = call(method, params, paramsSerializer, resultSerializer,
        currentCoroutineContext()[HermesRequestTimeout]?.millis ?: configuration.requestTimeoutMillis,
        waitsForConnection = true)

    /** App calls made while reconnecting wait for the connection, up to their timeout. When the connection
     *  drops under a call, the call is sent again on the next connection if Hermes never received it, or if it
     *  only reads state ([HermesGatewayContract.readOnlyMethods]), or if the session authority deduplicates it by
     *  the identity the client gave it ([AUTHORITY_IDEMPOTENT_METHODS]). Any other call whose response was lost
     *  fails with [HermesGatewayException.Transport]: Hermes may or may not have run it. */
    private suspend fun <Params : Any, Result : Any> call(
        method: String,
        params: Params,
        paramsSerializer: KSerializer<Params>,
        resultSerializer: KSerializer<Result>,
        timeoutMillis: Long,
        waitsForConnection: Boolean,
    ): Result {
        // Cadu runtime-close retirement: a refused, disconnected or suspended close must not rebind this runtime.
        if (method == "session.close") {
            val arguments = json.encodeToJsonElement(paramsSerializer, params) as? JsonObject
            val id = (arguments?.get("session_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id != null) withContext(confined + NonCancellable) { forgetSession(id) }
        }
        if (!waitsForConnection) {
            return try { attemptCall(method, params, paramsSerializer, resultSerializer, timeoutMillis, false, "") }
            catch (loss: ConnectionLoss) { throw loss.error }
        }
        // One identity per app call: an authority retry of it is recognised as the same intent.
        val intent = UUID.randomUUID().toString()
        return try {
            withTimeout(timeoutMillis) {
                var attempt = 1
                while (true) {
                    try {
                        return@withTimeout attemptCall(method, params, paramsSerializer, resultSerializer, timeoutMillis, true, intent)
                    } catch (loss: ConnectionLoss) {
                        val repeatable = !loss.delivered || method in HermesGatewayContract.readOnlyMethods
                        if (!repeatable || attempt >= 6) throw loss.error
                        attempt += 1
                        configuration.logger.log(GatewayLogLevel.INFO, "Repeating $method after a lost connection (attempt $attempt)")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (_: TimeoutCancellationException) {
            throw HermesGatewayException.Timeout(method)
        } finally {
            withContext(confined + NonCancellable) { authority.intentFinished(intent) }
        }
    }

    /** A call the connection dropped under; [delivered] is false when the frame never left, or when the session
     *  authority would recognise its repetition. */
    private class ConnectionLoss(val delivered: Boolean, val error: HermesGatewayException) : Exception(error.message)

    private suspend fun <Params : Any, Result : Any> attemptCall(
        method: String,
        params: Params,
        paramsSerializer: KSerializer<Params>,
        resultSerializer: KSerializer<Result>,
        timeoutMillis: Long,
        waitsForConnection: Boolean,
        intent: String,
    ): Result {
        if (waitsForConnection && !withContext(confined) { ready }) {
            if (!withContext(confined) { wantsConnection }) throw HermesGatewayException.Transport("Gateway is disconnected")
            awaitConnection(timeoutMillis)
        }
        val encodedParams = json.encodeToJsonElement(paramsSerializer, params)
        val (connection, current, onAuthority) = withContext(confined) {
            val active = socket ?: throw HermesGatewayException.Transport("Gateway is disconnected")
            Triple(active, generation, authorityConnection)
        }
        try {
            val result = if (onAuthority && encodedParams is JsonObject && method !in AUTHORITY_NATIVE_METHODS) {
                try {
                    withContext(confined) {
                        val answered = answerLiveRequest(method, encodedParams, connection, current)
                            ?: authority.call(method, encodedParams, wireOn(connection, current, timeoutMillis), intent)
                        // An answer from elsewhere in the app (a notification, say) ends the handler still asking.
                        if (method in PROMPT_ANSWER_METHODS) {
                            val id = encodedParams.string("request_id") ?: encodedParams.string("prompt_id") ?: encodedParams.string("id")
                            if (id != null && authority.isAnswered(id)) serverJobs.remove(id)?.cancel()
                        }
                        answered
                    }
                } catch (loss: ConnectionLoss) {
                    throw ConnectionLoss(loss.delivered && method !in AUTHORITY_IDEMPOTENT_METHODS, loss.error)
                }
            } else {
                exchange(method, encodedParams, timeoutMillis, connection, current)
            }
            validateContract(result)
            val decoded = try { json.decodeFromJsonElement(resultSerializer, result) }
            catch (error: Exception) {
                // The first line only: serialization messages go on to quote the payload, which logs must not carry.
                throw HermesGatewayException.Protocol("Cannot decode $method result: ${error.message?.lineSequence()?.first()}")
            }
            withContext(confined) {
                trackSession(method, encodedParams, result)
                if (onAuthority) {
                    // Hermes Desktop's adoptAttachedSnapshot: a snapshot's open prompts reach the handler at once.
                    if (method == "session.create" || method == "session.resume" || method == "session.activate") deliverSnapshotPrompts(result)
                } else if (configuration.automaticallyRestoreServerRequests && (method == "session.resume" || method == "session.activate")) {
                    deliverOpenRequests(result, connection, current)
                }
            }
            return decoded
        } catch (error: HermesGatewayException.RPC) {
            if (error.known == GatewayKnownError.BACKEND_RETIRING) {
                withContext(confined) { connectionLost(HermesGatewayException.Transport("Hermes backend is retiring"), current) }
            }
            throw error
        }
    }

    /** Sends one JSON-RPC call on [connection] and waits for its answer. Throws [ConnectionLoss] when that
     *  connection is gone before or while the call is out. */
    private suspend fun exchange(method: String, params: JsonElement, timeoutMillis: Long, connection: GatewayConnection, current: Int): JsonElement {
        val deferred = CompletableDeferred<JsonElement>()
        val id = withContext(confined) {
            if (socket !== connection || current != generation) {
                throw ConnectionLoss(delivered = false, error = HermesGatewayException.Transport("Gateway is disconnected"))
            }
            val assigned = nextID++
            pending[assigned] = deferred
            assigned
        }
        try {
            val frame = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            try { connection.send(frame.toString()) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // A socket that cannot send is dead even if its reader has not noticed yet.
                val lost = gatewayError(error)
                withContext(confined) { connectionLost(lost, current) }
                throw ConnectionLoss(delivered = false, error = lost)
            }
            return try { withTimeout(timeoutMillis) { deferred.await() } }
            catch (_: TimeoutCancellationException) { throw HermesGatewayException.Timeout(method) }
            catch (error: HermesGatewayException.Transport) {
                // The socket this call went out on is gone: the response was lost with it.
                if (withContext(confined) { generation } != current) throw ConnectionLoss(delivered = true, error = error)
                throw error
            }
        } finally {
            withContext(confined + NonCancellable) { pending.remove(id) }
        }
    }

    private fun wireOn(connection: GatewayConnection, current: Int, timeoutMillis: Long) =
        AuthorityWire { method, params -> exchange(method, params, timeoutMillis, connection, current) }

    private fun gatewayError(error: Throwable): HermesGatewayException = when (error) {
        is HermesGatewayException -> error
        is CancellationException -> HermesGatewayException.Transport("Cancelled")
        else -> HermesGatewayException.Transport(error.message ?: error.javaClass.simpleName)
    }

    // Socket reading and liveness

    private suspend fun readLoop(connection: GatewayConnection, current: Int) {
        try {
            while (true) {
                val text = connection.receive()
                if (current != generation) return
                inboundSinceTick = true
                handleFrame(text, connection, current)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // connectionLost cancels this reader; the reconnect must still be scheduled.
            withContext(NonCancellable) { connectionLost(gatewayError(error), current) }
        }
    }

    /** Any inbound frame proves the socket alive, so a streaming turn needs no pings. After one silent
     *  interval the gateway pings; once the silence reaches the deadline it reconnects. Silence is counted
     *  in ticks, not wall time, so it behaves the same under virtual time. */
    private suspend fun heartbeatLoop(connection: GatewayConnection, current: Int) {
        var silentTicks = 0
        val deadlineTicks = maxOf(1L, configuration.heartbeatDeadlineMillis / configuration.heartbeatIntervalMillis)
        while (current == generation) {
            delay(configuration.heartbeatIntervalMillis)
            if (current != generation) return
            if (inboundSinceTick) {
                inboundSinceTick = false
                silentTicks = 0
                continue
            }
            silentTicks++
            if (silentTicks >= deadlineTicks) {
                withContext(NonCancellable) {
                    connectionLost(HermesGatewayException.Transport("No frame from Hermes for $silentTicks heartbeat intervals"), current)
                }
                return
            }
            val frame = buildJsonObject {
                put("jsonrpc", "2.0")
                // String ids never match a pending call.
                put("id", "heartbeat-${++heartbeatSequence}")
                put("method", heartbeatMethod)
                put("params", JsonObject(emptyMap()))
            }
            try { connection.send(frame.toString()) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                withContext(NonCancellable) { connectionLost(gatewayError(error), current) }
                return
            }
        }
    }

    /** A malformed frame is logged and dropped: reconnecting would not change what Hermes sends. */
    private suspend fun handleFrame(text: String, connection: GatewayConnection, current: Int) {
        val frame = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (frame == null) {
            logger.log(GatewayLogLevel.ERROR, "Dropped a gateway frame that is not a JSON object")
            return
        }
        val method = (frame["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method != null) {
            val id = (frame["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (method == "event") handleEvent(frame["params"] as? JsonObject, false)
            else if (id != null) handleServerRequest(id, method, frame["params"] ?: JsonObject(emptyMap()), connection, current)
            return
        }
        val id = (frame["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return
        val deferred = pending.remove(id) ?: return
        val error = frame["error"] as? JsonObject
        if (error != null) {
            deferred.completeExceptionally(HermesGatewayException.RPC(
                (error["code"] as? JsonPrimitive)?.intOrNull ?: -32603,
                (error["message"] as? JsonPrimitive)?.content ?: "Unknown RPC error", error["data"],
            ))
        } else {
            val result = frame["result"]
            if (result == null) deferred.completeExceptionally(HermesGatewayException.Protocol("Response has no result or error"))
            else deferred.complete(result)
        }
    }

    private fun handleEvent(params: JsonObject?, replayed: Boolean) {
        val type = (params?.get("type") as? JsonPrimitive)?.content
        if (params == null || type == null) {
            logger.log(GatewayLogLevel.ERROR, "Dropped an event without a type")
            return
        }
        val sessionId = (params["session_id"] as? JsonPrimitive)?.content
        val seq = (params["seq"] as? JsonPrimitive)?.longOrNull
        if (!replayed && sessionId != null && seq != null) {
            replayHold[sessionId]?.let { it.add(params); return }
        }
        if (type == "gateway.ready") {
            authorityConnection = ((params["payload"] as? JsonObject)?.get("session_authority") as? JsonPrimitive)?.booleanOrNull == true
        }
        if (authorityConnection && sessionId != null) adoptEventEpoch(sessionId, params)
        if (sessionId != null && seq != null && seq <= (lastSequence[sessionId] ?: 0)) return
        var rawPayload = params["payload"] ?: JsonObject(emptyMap())
        val synthesized = mutableListOf<GatewayEvent>()
        if (authorityConnection && sessionId != null) {
            rawPayload = authorityEvent(type, sessionId, params, rawPayload, replayed, synthesized)
        }
        // A payload the generated model rejects must not tear down the socket: reconnect replay
        // would deliver the same frame again. Callers still receive the raw payload.
        val payload = try { GatewayEventPayload.decode(type, rawPayload, json) }
        catch (_: IllegalArgumentException) { GatewayEventPayload.Unknown(type, rawPayload) }
        when (payload) {
            is GatewayEventPayload.GatewayReady -> {
                // An authority session numbers its events per session epoch; this one is per process.
                if (!authorityConnection && replayEpoch != null && replayEpoch != payload.payload.replayEpoch) resetWatermarks()
                replayEpoch = payload.payload.replayEpoch
                // Older backends answer gateway.ping with an error, which still proves the socket alive.
                heartbeatMethod = if (payload.payload.heartbeat == true) "gateway.ping" else "ping"
            }
            is GatewayEventPayload.RequestCancel -> {
                settledSnapshotRequests.add(payload.payload.id)
                serverJobs.remove(payload.payload.id)?.cancel()
            }
            is GatewayEventPayload.MessageStart -> sessionId?.let { setTurn(it, true) }
            is GatewayEventPayload.MessageComplete, is GatewayEventPayload.Error -> sessionId?.let { setTurn(it, false) }
            is GatewayEventPayload.SessionReclaimed -> {
                val reclaimed = payload.payload
                if (reclaimed.sessionId in sessions || reclaimed.sessionId in lastSequence) {
                    forgetSession(reclaimed.sessionId)
                    recoveryBroadcast.emit(GatewaySessionRecovery.Reclaimed(
                        reclaimed.sessionId, reclaimed.storedSessionId, reclaimed.reason))
                }
            }
            else -> Unit
        }
        if (sessionId != null && seq != null) lastSequence[sessionId] = seq
        eventBroadcast.emit(GatewayEvent(type, sessionId, seq, payload, replayed))
        synthesized.forEach(eventBroadcast::emit)
    }

    // Session authority

    /** Asks an authority socket what it serves; a gateway that does not answer `runtime.describe` keeps the
     *  translation's built-in defaults. Legacy sockets are never asked, so their frames stay as they were. */
    private suspend fun describeRuntime(connection: GatewayConnection, current: Int) {
        val description = if (!authorityConnection) null else try {
            RuntimeDescription.parse(exchange("runtime.describe", JsonObject(emptyMap()), configuration.connectTimeoutMillis, connection, current))
        } catch (error: HermesGatewayException.RPC) {
            // A backend that announced an authority but does not know runtime.describe is treated as legacy; one
            // that would not describe itself keeps the translation's defaults.
            if (error.code == -32601) authorityConnection = false
            logger.log(GatewayLogLevel.INFO, "runtime.describe refused: ${error.message}")
            null
        } catch (loss: ConnectionLoss) {
            throw loss.error
        }
        authority.connectionOpened(description)
        runtime = if (!authorityConnection) GatewayRuntime.legacy else GatewayRuntime(
            sessionAuthority = true,
            instanceId = description?.instanceId,
            profileId = description?.profileId,
            authorityEpoch = description?.authorityEpoch,
            capabilities = description?.capabilities.orEmpty(),
            sessionCreateSources = description?.createSources.orEmpty(),
            sessionCreateParameters = description?.createParameters.orEmpty(),
        )
        logger.log(GatewayLogLevel.INFO, if (authorityConnection) "Hermes session authority" else "Hermes legacy gateway")
    }

    /** Watermarks are per (session, replay epoch): a new epoch numbers its events from 1 again. */
    private fun adoptEventEpoch(sessionId: String, params: JsonObject) {
        val epoch = (params["replay_epoch"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val known = sessionEpochs[sessionId]
        if (known == epoch) return
        if (known != null && sessionId in lastSequence) lastSequence[sessionId] = 0
        sessionEpochs[sessionId] = epoch
    }

    /** A snapshot the app asked for already contains every event up to its `last_sequence`. */
    private fun adoptSnapshotWatermark(snapshot: AuthoritySnapshot, explicit: Boolean) {
        val sessionId = snapshot.sessionId
        val epoch = snapshot.replayEpoch ?: return
        if (!explicit && sessionId !in sessions && sessionId !in lastSequence) return
        val latest = snapshot.lastSequence ?: 0
        if (sessionEpochs[sessionId] != epoch) {
            sessionEpochs[sessionId] = epoch
            lastSequence[sessionId] = latest
        } else {
            lastSequence[sessionId] = maxOf(lastSequence[sessionId] ?: 0, latest)
        }
    }

    /** Authority bookkeeping for one session event, and its payload in the legacy shape where one exists. */
    private fun authorityEvent(type: String, sessionId: String, params: JsonObject, rawPayload: JsonElement,
                               replayed: Boolean, synthesized: MutableList<GatewayEvent>): JsonElement {
        val fields = rawPayload as? JsonObject ?: return rawPayload
        val outcome = authority.event(type, sessionId, params.long("execution_generation"), fields)
        outcome.deliver?.let { deliverPrompt(it, snapshot = replayed) }
        outcome.settledId?.let { id ->
            settledSnapshotRequests.add(id)
            serverJobs.remove(id)?.let { job ->
                // Answered elsewhere, timed out or interrupted: the legacy withdrawal, for apps that listen to it.
                job.cancel()
                val payload = RequestCancelPayload(id, outcome.settled?.kind ?: type.removeSuffix(".settled"), "settled")
                synthesized += GatewayEvent("request.cancel", sessionId, null, GatewayEventPayload.RequestCancel(payload), replayed)
            }
        }
        return when (type) {
            "session.replay_gap" -> {
                if (!replayed) scheduleGapRecovery(sessionId)
                fields
            }
            "session.updated" -> {
                val title = fields.string("title")
                if (fields.string("operation") == "rename" && title != null) {
                    synthesized += GatewayEvent("session.title", sessionId, null,
                        GatewayEventPayload.SessionTitle(SessionTitlePayload(sessionId, title)), replayed)
                }
                fields
            }
            "session.usage" -> fields.with("usage" to LegacyShapes.usage(fields))
            "message.complete" -> fields.obj("usage")?.takeIf { it.containsKey("input_tokens") }
                ?.let { fields.with("usage" to LegacyShapes.usage(it)) } ?: fields
            else -> fields
        }
    }

    /** Puts an authority prompt to the server request handler as the legacy `approval` / `clarify` request,
     *  keyed by its prompt id. The answer goes back as `approval.respond` / `clarify.respond`. A handler that
     *  fails leaves the prompt unanswered: the next snapshot that still lists it offers it again. */
    private fun deliverPrompt(prompt: AuthorityPrompt, snapshot: Boolean) {
        val id = prompt.promptId
        if (snapshot && id in settledSnapshotRequests) return
        if (!snapshot) settledSnapshotRequests.remove(id)
        if (id in serverJobs || authority.isAnswered(id)) return
        val request = try { ServerRequest.decode(prompt.kind, prompt.legacyParams(), json) }
        catch (_: IllegalArgumentException) {
            logger.log(GatewayLogLevel.ERROR, "Dropped a ${prompt.kind} prompt the server request model rejects")
            return
        }
        val currentHandler = handler ?: run {
            logger.log(GatewayLogLevel.ERROR, "No server request handler for a ${prompt.kind} prompt")
            return
        }
        serverJobs[id] = scope.launch(ServerRequestContext(id, prompt.kind)) {
            val delivery = checkNotNull(coroutineContext[Job])
            var answered = false
            try {
                val result = currentHandler(request)
                if (!result.matches(request)) throw HermesGatewayException.Protocol("Server request result kind does not match ${prompt.kind}")
                answerPrompt(prompt, result)
                answered = true
            } catch (error: Throwable) {
                if (!coroutineContext.isActive) throw error
                logger.log(GatewayLogLevel.ERROR, "${prompt.kind} prompt was not answered: ${error.message}")
            } finally {
                withContext(confined + NonCancellable) {
                    if (serverJobs.remove(id, delivery) && answered) settledSnapshotRequests.add(id)
                }
            }
        }
    }

    /** Sends the handler's answer through the translated `approval.respond` / `clarify.respond`, which waits for
     *  a connection and attaches the session first when needed. */
    private suspend fun answerPrompt(prompt: AuthorityPrompt, result: ServerRequestResult) {
        val answering = withContext(confined) {
            if (authority.isAnswered(prompt.promptId)) return@withContext false
            // The answer is this handler's: a settled echo or another answer must not cancel it as withdrawn.
            serverJobs.remove(prompt.promptId)
            settledSnapshotRequests.add(prompt.promptId)
            true
        }
        if (!answering) return
        val answer = json.parseToJsonElement(result.encodedJSON(json)) as? JsonObject ?: JsonObject(emptyMap())
        val params = if (prompt.kind == "approval") {
            jsonObject("session_id" to JsonPrimitive(prompt.sessionId), "request_id" to JsonPrimitive(prompt.promptId),
                "choice" to (answer["choice"] ?: JsonPrimitive("deny")))
        } else {
            jsonObject("session_id" to JsonPrimitive(prompt.sessionId), "prompt_id" to JsonPrimitive(prompt.promptId),
                "answer" to JsonPrimitive(authority.clarifyAnswer(prompt.promptId, answer)))
        }
        try {
            call("${prompt.kind}.respond", params, JsonObject.serializer(), JsonElement.serializer())
        } catch (error: Throwable) {
            // Not answered: a later snapshot that still lists the prompt offers it again.
            withContext(confined + NonCancellable) { settledSnapshotRequests.remove(prompt.promptId) }
            throw error
        }
    }

    /** `request.answer` for a server request still open on this socket (`sudo`, `secret`): the authority takes
     *  only the JSON-RPC response to the request itself, so the client sends that in the handler's place. */
    private suspend fun answerLiveRequest(method: String, params: JsonObject, connection: GatewayConnection, current: Int): JsonElement? {
        if (method != "request.answer") return null
        val id = params.string("id") ?: return null
        if (authority.prompt(id) != null) return null
        val job = serverJobs.remove(id) ?: return null
        job.cancel()
        settledSnapshotRequests.add(id)
        try {
            connection.send(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                put("result", params["result"] ?: JsonObject(emptyMap()))
            }.toString())
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val lost = gatewayError(error)
            connectionLost(lost, current)
            throw ConnectionLoss(delivered = false, error = lost)
        }
        return jsonObject("status" to JsonPrimitive("ok"))
    }

    /** `session.replay_gap`: this socket's subscription was dropped after its backlog overflowed. Resume the
     *  session and replay from the watermark, holding live events until then; what cannot be replayed is
     *  reported as [GatewaySessionRecovery.ReplayTruncated]. */
    private fun scheduleGapRecovery(sessionId: String) {
        val connection = socket ?: return
        if (!ready || sessionId in replayHold) return
        val current = generation
        replayHold[sessionId] = mutableListOf()
        scope.launch(confined) {
            try {
                if (current != generation) return@launch
                recoverOnAuthority(sessionId, sessions[sessionId] ?: TrackedSession(), connection, current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.log(GatewayLogLevel.ERROR, "Replay gap recovery failed: ${error.message}")
                if (current == generation) recoveryBroadcast.emit(GatewaySessionRecovery.ReplayTruncated(sessionId))
            } finally {
                if (current == generation) releaseHeldEvents(sessionId)
            }
        }
    }

    private fun setTurn(sessionId: String, active: Boolean) {
        val changed = if (active) activeTurns.add(sessionId) else activeTurns.remove(sessionId)
        if (changed) turnActivity.value = activeTurns.size
    }

    private suspend fun handleServerRequest(id: String, method: String, params: JsonElement, connection: GatewayConnection, current: Int, snapshot: Boolean = false) {
        // A prompt of the session authority is answered by RPC, never by a response to this id.
        authority.prompt(id)?.takeIf { authorityConnection }?.let { return deliverPrompt(it, snapshot) }
        if (snapshot && id in settledSnapshotRequests) return
        if (!snapshot) settledSnapshotRequests.remove(id)
        // A reconnect can deliver one request both live and through open_requests.
        if (id in serverJobs) return
        val request = try { ServerRequest.decode(method, params, json) }
        catch (_: IllegalArgumentException) { answerWithError(id, -32602, "Invalid $method params", connection, current); return }
        if (request is ServerRequest.Unknown) { answerWithError(id, -32601, "Unknown server request", connection, current); return }
        val currentHandler = handler
        if (currentHandler == null) { answerWithError(id, -32601, "No server request handler", connection, current); return }
        // The app's handler runs off the confined dispatcher; it may wait for the user for minutes.
        serverJobs[id] = scope.launch(ServerRequestContext(id, method)) {
            val delivery = checkNotNull(coroutineContext[Job])
            try {
                val result = currentHandler(request)
                if (!result.matches(request)) throw HermesGatewayException.Protocol("Server request result kind does not match $method")
                withContext(confined) {
                    if (current != generation) return@withContext
                    try {
                        connection.send(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", id)
                            put("result", json.parseToJsonElement(result.encodedJSON(json)))
                        }.toString())
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { connectionLost(gatewayError(error), current) }
                }
            } catch (error: Throwable) {
                // Only a request Hermes withdrew (request.cancel cancels this job) goes unanswered. Any other
                // failure, including a handler throwing CancellationException itself, is answered so Hermes
                // does not wait out its deadline (an hour for clarify).
                if (!coroutineContext.isActive) throw error
                withContext(confined) { answerWithError(id, -32603, error.message ?: "Handler failed", connection, current) }
            } finally {
                withContext(confined + NonCancellable) {
                    if (serverJobs.remove(id, delivery)) settledSnapshotRequests.add(id)
                }
            }
        }
    }

    private suspend fun answerWithError(id: String, code: Int, message: String, connection: GatewayConnection, current: Int) {
        if (current != generation) return
        try {
            connection.send(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                put("error", buildJsonObject { put("code", code); put("message", message) })
            }.toString())
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { connectionLost(gatewayError(error), current) }
    }

    private fun validateContract(result: JsonElement) {
        val root = result as? JsonObject ?: return
        val info = root["info"] as? JsonObject
        val contract = ((root["desktop_contract"] ?: info?.get("desktop_contract")) as? JsonPrimitive)
            ?.content?.toIntOrNull() ?: return
        backendContract = contract
        if (contract < configuration.minimumContract) throw HermesGatewayException.IncompatibleServer(contract)
    }

    // Session tracking and recovery

    /** Sessions this client must rebind after a reconnect, whether or not they have emitted events yet. */
    private fun trackedSessionIds(): Set<String> = sessions.keys + lastSequence.keys - draining.keys

    /** A resumed or activated session carries the requests still waiting for an answer, for example after the
     *  app relaunched with an approval open. They reach the handler like live ones: Hermes settles an answer by
     *  its request id, whichever socket carries it. */
    private suspend fun deliverOpenRequests(result: JsonElement, connection: GatewayConnection, current: Int) {
        val entries = (result as? JsonObject)?.get("open_requests") as? JsonArray ?: return
        for (entry in entries) {
            val fields = entry as? JsonObject ?: continue
            val id = (fields["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val method = (fields["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            handleServerRequest(id, method, fields["params"] ?: JsonObject(emptyMap()), connection, current, snapshot = true)
        }
    }

    /** On the session authority a snapshot lists its open prompts as `prompts`, not `open_requests`; each is put to
     *  the handler like a live one (one already delivered or answered is skipped). A session keeps its id there,
     *  so an app can register it before resuming, whatever [HermesGatewayConfiguration.automaticallyRestoreServerRequests]. */
    private fun deliverSnapshotPrompts(result: JsonElement) {
        val listed = (result as? JsonObject)?.get("prompts") as? JsonArray ?: return
        for (entry in listed) {
            val id = ((entry as? JsonObject)?.get("prompt_id") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            authority.prompt(id)?.let { deliverPrompt(it, snapshot = true) }
        }
    }

    private fun trackSession(method: String, params: JsonElement, result: JsonElement) {
        val arguments = params as? JsonObject ?: JsonObject(emptyMap())
        fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (method) {
            "session.create", "session.resume", "session.activate", "session.branch" -> {
                val fields = result as? JsonObject ?: return
                val sessionId = fields.string("session_id") ?: return
                val previous = sessions[sessionId] ?: TrackedSession()
                sessions[sessionId] = previous.copy(
                    storedId = fields.string("stored_session_id") ?: fields.string("session_key") ?: previous.storedId
                        ?: if (method == "session.resume") arguments.string("session_id") else null,
                    profile = arguments.string("profile") ?: previous.profile,
                    source = arguments.string("source") ?: previous.source,
                    closeOnDisconnect = (arguments["close_on_disconnect"] as? JsonPrimitive)?.booleanOrNull
                        ?: previous.closeOnDisconnect,
                )
            }
            "session.close" -> arguments.string("session_id")?.let(::forgetSession)
        }
    }

    private fun forgetSession(sessionId: String) {
        sessions.remove(sessionId)
        lastSequence.remove(sessionId)
        sessionEpochs.remove(sessionId)
        setTurn(sessionId, false)
    }

    /** A new server process restarts every session's numbering. Keep the sessions so the rebind resumes
     *  the ones that did not survive. */
    private fun resetWatermarks() {
        lastSequence.keys.forEach { lastSequence[it] = 0 }
    }

    /** Throws when a session could not be brought up to date over this socket; the attempt then fails and the
     *  next one replays from the same watermark. Releasing live events past an unreplayed gap would skip it. */
    private suspend fun recoverSessions(connection: GatewayConnection, current: Int) {
        try {
            for (sessionId in trackedSessionIds().sorted()) {
                if (current != generation) return
                recover(sessionId, connection, current)
            }
        } catch (error: Throwable) {
            // Held events sit past the gap the next attempt replays.
            replayHold.clear()
            throw error
        }
        // Sessions created while recovering were never replayed, so their events need no hold.
        val remaining = replayHold.entries.sortedBy { it.key }.flatMap { it.value.toList() }
        replayHold.clear()
        remaining.forEach { handleEvent(it, false) }
    }

    private suspend fun recover(sessionId: String, connection: GatewayConnection, current: Int) {
        val session = sessions[sessionId] ?: TrackedSession()
        if (session.closeOnDisconnect) {
            forgetSession(sessionId)
            recoveryBroadcast.emit(GatewaySessionRecovery.Unavailable(sessionId, "Closed on disconnect"))
        } else if (authorityConnection) {
            recoverOnAuthority(sessionId, session, connection, current)
        } else {
            val outcome = rebind(sessionId)
            logger.log(GatewayLogLevel.DEBUG, "Rebind $sessionId: $outcome")
            when (outcome) {
                Rebind.Bound -> replay(sessionId, connection, current)
                is Rebind.Gone -> {
                    // Hermes dropped the session, but its turn may still be writing the reply to the replay
                    // buffer under this id: deliver what is there before moving to a new runtime id.
                    if (sessionId in activeTurns) replay(sessionId, connection, current)
                    resume(sessionId, session, outcome.reason, connection, current)
                }
                Rebind.Settling -> Unit
            }
        }
        releaseHeldEvents(sessionId)
    }

    /** On the session authority a session keeps its id: resuming it subscribes this socket again, then the
     *  events since the watermark are replayed from the session's own epoch. A new epoch (the replay buffer
     *  evicted it, or Hermes restarted) cannot be replayed and is reported as [GatewaySessionRecovery.ReplayTruncated]. */
    private suspend fun recoverOnAuthority(sessionId: String, session: TrackedSession, connection: GatewayConnection, current: Int) {
        val wire = wireOn(connection, current, RECOVERY_TIMEOUT_MILLIS)
        try {
            val snapshot = try {
                authority.attach(sessionId, session.profile, wire, adoptWatermark = false)
            } catch (error: HermesGatewayException.RPC) {
                logger.log(GatewayLogLevel.INFO, "Resume of $sessionId refused: ${error.message}")
                forgetSession(sessionId)
                authority.forget(sessionId)
                recoveryBroadcast.emit(GatewaySessionRecovery.Unavailable(sessionId, error.message ?: "unavailable"))
                return
            }
            val epoch = sessionEpochs[sessionId]
            val mark = lastSequence[sessionId] ?: 0
            val latest = snapshot.lastSequence ?: 0
            if (epoch == null || epoch == snapshot.replayEpoch) {
                snapshot.replayEpoch?.let { sessionEpochs[sessionId] = it }
                if (latest > mark) replayOnAuthority(sessionId, mark, snapshot, session.profile, wire)
            } else {
                snapshot.replayEpoch?.let { sessionEpochs[sessionId] = it }
                lastSequence[sessionId] = latest
                recoveryBroadcast.emit(GatewaySessionRecovery.ReplayTruncated(sessionId))
            }
            for (entry in snapshot.prompts) {
                val prompt = entry.string("prompt_id")?.let(authority::prompt) ?: continue
                deliverPrompt(prompt, snapshot = true)
            }
        } catch (loss: ConnectionLoss) {
            throw loss.error
        }
    }

    private suspend fun replayOnAuthority(sessionId: String, mark: Long, snapshot: AuthoritySnapshot, profile: String?, wire: AuthorityWire) {
        val result = try {
            wire.call("session.events.since", jsonObject("session_id" to JsonPrimitive(sessionId), "profile" to json(profile),
                "last_sequence" to JsonPrimitive(mark), "replay_epoch" to json(snapshot.replayEpoch))) as? JsonObject
                ?: JsonObject(emptyMap())
        } catch (error: HermesGatewayException.RPC) {
            logger.log(GatewayLogLevel.ERROR, "Replay refused: ${error.message}")
            lastSequence[sessionId] = snapshot.lastSequence ?: mark
            recoveryBroadcast.emit(GatewaySessionRecovery.ReplayTruncated(sessionId))
            return
        }
        logger.log(GatewayLogLevel.DEBUG, "Replay $sessionId after $mark: ${result.array("events")?.size ?: 0} events, " +
            "latest ${result.long("latest_seq")}, truncated ${result.bool("truncated")}, snapshot required ${result.bool("snapshot_required")}")
        if (result.bool("snapshot_required") == true || result.bool("truncated") == true) {
            result.string("replay_epoch")?.let { sessionEpochs[sessionId] = it }
            lastSequence[sessionId] = result.long("latest_seq") ?: snapshot.lastSequence ?: mark
            recoveryBroadcast.emit(GatewaySessionRecovery.ReplayTruncated(sessionId))
        } else {
            result.array("events")?.forEach { (it as? JsonObject)?.let { event -> handleEvent(event, true) } }
        }
    }

    /** Rebinding cancels Hermes' orphan reap and routes the session's live events to this socket. */
    private suspend fun rebind(sessionId: String): Rebind {
        for (attempt in 1..3) {
            try {
                call("session.activate", SessionActivateParams(sessionId, omitMessages = true),
                    SessionActivateParams.serializer(), JsonElement.serializer(), RECOVERY_TIMEOUT_MILLIS,
                    waitsForConnection = false)
                return Rebind.Bound
            } catch (error: HermesGatewayException.RPC) {
                // Not found, not live, or refused otherwise: this runtime id is no longer ours.
                if (error.known != GatewayKnownError.SESSION_SETTLING) return Rebind.Gone(error.message ?: "unavailable")
                if (attempt == 3) return Rebind.Settling
                // Hermes is still settling the disconnect interrupt.
                delay(500L * attempt)
            }
        }
        return Rebind.Settling
    }

    /** Hermes reclaims a session 20 s after its socket closes (and every session on restart), but keeps it
     *  in storage. Resuming by the stored id rebuilds it under a new runtime id. */
    private suspend fun resume(sessionId: String, session: TrackedSession, reason: String,
                               connection: GatewayConnection, current: Int) {
        val storedId = session.storedId
        if (!configuration.resumesReclaimedSessions || storedId == null) {
            retire(sessionId)
            recoveryBroadcast.emit(GatewaySessionRecovery.Unavailable(sessionId, reason))
            return
        }
        try {
            val result = call("session.resume",
                SessionResumeParams(storedId,
                    profile = session.profile?.let { Patch.Value(it) } ?: Patch.Absent,
                    source = session.source?.let { Patch.Value(it) } ?: Patch.Absent,
                    omitMessages = true),
                SessionResumeParams.serializer(), SessionResumeResult.serializer(),
                configuration.requestTimeoutMillis, waitsForConnection = false)
            // Cadu same-runtime resume: a runtime Hermes still runs comes back under its own id, bound to this
            // socket again. It stays tracked, so the next reconnect rebinds it too; what it sent is replayed.
            if (result.sessionId == sessionId) {
                logger.log(GatewayLogLevel.INFO, "Resumed a session Hermes still runs under its own runtime id")
                replay(sessionId, connection, current)
                return
            }
            retire(sessionId)
            logger.log(GatewayLogLevel.INFO, "Resumed a reclaimed session under a new runtime id")
            recoveryBroadcast.emit(GatewaySessionRecovery.Resumed(sessionId, result.sessionId, result.storedSessionId ?: result.sessionKey ?: storedId, result))
        } catch (error: HermesGatewayException.RPC) {
            retire(sessionId)
            recoveryBroadcast.emit(GatewaySessionRecovery.Unavailable(sessionId, error.message ?: "unavailable"))
        }
    }

    /** Stops rebinding a runtime id Hermes dropped. A turn still streaming under it keeps its events flowing:
     *  the client drains the replay buffer until the turn ends. */
    private fun retire(sessionId: String) {
        if (sessionId !in activeTurns) return forgetSession(sessionId)
        sessions.remove(sessionId)
        draining[sessionId] = System.nanoTime()
    }

    private fun startDraining() {
        if (draining.isEmpty() || drainJob != null) return
        drainJob = scope.launch(confined) {
            try {
                drainDetachedTurns()
            } finally {
                drainJob = null
            }
        }
    }

    /** Fetches the replay buffer of every drained turn while connected, until the turn completes, Hermes can
     *  no longer replay it, or [DRAIN_LIMIT_NANOS] passes. */
    private suspend fun drainDetachedTurns() {
        while (draining.isNotEmpty()) {
            delay(DRAIN_INTERVAL_MILLIS)
            if (!ready) continue
            for ((sessionId, started) in draining.toSortedMap()) {
                if (sessionId !in activeTurns || System.nanoTime() - started > DRAIN_LIMIT_NANOS) {
                    finishDraining(sessionId)
                    continue
                }
                try {
                    val result = call("session.events.since",
                        SessionEventsSinceParams(sessionId, lastSeen = Patch.Value(lastSequence[sessionId] ?: 0L)),
                        SessionEventsSinceParams.serializer(), SessionEventsSinceResult.serializer(),
                        RECOVERY_TIMEOUT_MILLIS, waitsForConnection = false)
                    val restarted = replayEpoch != null && replayEpoch != result.epoch
                    apply(result, sessionId)
                    if (restarted || result.truncated) finishDraining(sessionId)
                } catch (error: HermesGatewayException.RPC) {
                    finishDraining(sessionId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The connection dropped; draining continues once it is back.
                }
            }
        }
    }

    private fun finishDraining(sessionId: String) {
        if (draining.remove(sessionId) != null) forgetSession(sessionId)
    }

    private suspend fun replay(sessionId: String, connection: GatewayConnection, current: Int) {
        val result = try {
            call("session.events.since",
                SessionEventsSinceParams(sessionId, lastSeen = Patch.Value(lastSequence[sessionId] ?: 0L)),
                SessionEventsSinceParams.serializer(), SessionEventsSinceResult.serializer(),
                RECOVERY_TIMEOUT_MILLIS, waitsForConnection = false)
        } catch (error: HermesGatewayException.RPC) {
            // Hermes answered but cannot replay; the caller must reload what it shows.
            logger.log(GatewayLogLevel.ERROR, "Replay refused: ${error.message}")
            recoveryBroadcast.emit(GatewaySessionRecovery.ReplayTruncated(sessionId))
            return
        }
        apply(result, sessionId)
        result.openRequests.forEach {
            handleServerRequest(it.id, it.method, JsonObject(it.params), connection, current, snapshot = true)
        }
    }

    /** Delivers a page of replayed events, or reports the gap when Hermes no longer holds them. */
    private fun apply(result: SessionEventsSinceResult, sessionId: String) {
        logger.log(GatewayLogLevel.DEBUG, "Replay $sessionId after ${lastSequence[sessionId] ?: 0}: " +
            "${result.events.size} events, latest ${result.latestSeq}, truncated ${result.truncated}, " +
            "epoch ${if (result.epoch == replayEpoch) "same" else "changed"}")
        if (replayEpoch != null && replayEpoch != result.epoch) {
            resetWatermarks()
            replayEpoch = result.epoch
        } else if (result.truncated) {
            lastSequence[sessionId] = result.latestSeq
            recoveryBroadcast.emit(GatewaySessionRecovery.ReplayTruncated(sessionId))
        } else {
            result.events.forEach { handleEvent(JsonObject(it), true) }
        }
    }

    private fun releaseHeldEvents(sessionId: String) {
        replayHold.remove(sessionId)?.forEach { handleEvent(it, false) }
    }

    private fun socketRequest(baseURI: URI, credential: GatewayCredential): Triple<URI, Map<String, String>, List<String>> {
        val scheme = when (baseURI.scheme) {
            "http" -> "ws"; "https" -> "wss"
            else -> throw HermesGatewayException.Transport("Dashboard URL must use HTTP or HTTPS")
        }
        fun socketURI(rawQuery: String? = null) = dashboardURI(baseURI, "/api/ws", rawQuery, scheme)
            ?: throw HermesGatewayException.Transport("Invalid dashboard URL")
        return when (credential) {
            is GatewayCredential.Ticket -> {
                if (credential.value.isEmpty()) throw HermesGatewayException.Transport("Empty WebSocket ticket")
                // Hermes 0.20.6 and later read the ticket from the protocol header and echo only the public
                // protocol; earlier releases read only `?ticket=` and refuse the upgrade without it. The ticket
                // is single-use and lives 30 seconds, so it rides in both (a newer Hermes prefers the header).
                Triple(socketURI("ticket=" + URLEncoder.encode(credential.value, "UTF-8")), credential.headers, listOf("hermes-gateway-v1", "hermes-gateway-ticket.${credential.value}"))
            }
            is GatewayCredential.LocalToken -> {
                if (credential.value.isEmpty()) throw HermesGatewayException.Transport("Empty local token")
                Triple(socketURI("token=" + URLEncoder.encode(credential.value, "UTF-8")), credential.headers, emptyList())
            }
        }
    }

    private companion object {
        /** Recovery calls must not stall a reconnect behind a wedged backend. */
        const val RECOVERY_TIMEOUT_MILLIS = 10_000L
        /** How often a detached turn's replay buffer is fetched, and for how long at most. */
        const val DRAIN_INTERVAL_MILLIS = 1_000L
        const val DRAIN_LIMIT_NANOS = 900_000_000_000L

        val PROMPT_ANSWER_METHODS = setOf("approval.respond", "clarify.respond", "clarify.lock", "request.answer")

        /** Never translated on an authority socket. */
        val AUTHORITY_NATIVE_METHODS = setOf("client.capabilities", "runtime.describe")

        /** Translated calls the authority recognises when repeated (a retained request id, a submission id, a
         *  prompt answered once, or a plain read), so a reply lost with the connection is safe to resend. */
        val AUTHORITY_IDEMPOTENT_METHODS = setOf("session.create", "session.resume", "session.activate", "session.close",
            "session.title", "session.cwd.set", "session.workspace.move", "session.compress",
            "session.branch", "session.usage", "session.events.since", "session.interrupt", "session.list",
            "prompt.submit", "approval.respond", "clarify.respond", "clarify.lock", "commands.catalog")
    }
}
