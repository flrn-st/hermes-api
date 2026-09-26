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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import hermes.api.generated.gateway.ClientCapabilitiesParams
import hermes.api.generated.gateway.ClientCapabilitiesResult
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.GatewayMethodCatalog
import hermes.api.generated.gateway.HermesGatewayContract
import hermes.api.generated.gateway.ServerRequest
import hermes.api.generated.gateway.ServerRequestResult
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
    data object RetryLater : Rebind
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
) : GatewayCaller {
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
    @Volatile private var handler: (suspend (ServerRequest) -> ServerRequestResult)? = null
    private var nextID = 1

    private val sessions = mutableMapOf<String, TrackedSession>()
    private val lastSequence = mutableMapOf<String, Long>()
    private val activeTurns = mutableSetOf<String>()
    private var replayEpoch: String? = null
    private var hasConnected = false
    private val replayHold = mutableMapOf<String, MutableList<JsonObject>>()

    // Events leave the reader through an unbounded queue, so a slow collector delays delivery but never
    // stalls the socket (and with it heartbeat replies).
    private val eventQueue = Channel<GatewayEvent>(Channel.UNLIMITED)
    private val mutableEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 1024)
    private val mutableStates = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
    private val mutableRecoveries = MutableSharedFlow<GatewaySessionRecovery>(extraBufferCapacity = 256)
    private val turnActivity = MutableStateFlow(0)

    public val methods: GatewayMethodCatalog = GatewayMethodCatalog(this)
    /** Gateway notifications for every collector. Collect before [connect] to see the first events. */
    public val events: SharedFlow<GatewayEvent> = mutableEvents
    /** The current connection state. As a StateFlow it conflates quick transitions. */
    public val connectionStates: StateFlow<GatewayConnectionState> = mutableStates
    /** Session recoveries after reconnects. See [GatewaySessionRecovery]. */
    public val sessionRecoveries: SharedFlow<GatewaySessionRecovery> = mutableRecoveries

    init {
        scope.launch { for (event in eventQueue) mutableEvents.emit(event) }
    }

    public fun setServerRequestHandler(value: suspend (ServerRequest) -> ServerRequestResult) {
        handler = value
    }

    // Connection lifecycle

    /** Connects and keeps the connection alive until [disconnect]. Returns once connected; while the
     *  network is down or the server unreachable it keeps retrying. Throws only a failure retrying cannot
     *  fix ([HermesGatewayException.AuthenticationFailed], [HermesGatewayException.IncompatibleServer]). */
    public suspend fun connect() {
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
    public suspend fun disconnect(): Unit = withContext(confined + NonCancellable) {
        wantsConnection = false
        cancelMaintaining()
        monitor?.cancel()
        monitor = null
        networkKnown = false
        generation++
        closeConnection(HermesGatewayException.Transport("Gateway disconnected"))
        publish(GatewayConnectionState.Idle)
    }

    /** Call when the app moves to the background (for example from `ProcessLifecycleOwner.onStop`). A
     *  streaming turn gets up to [graceMillis] to finish; then the socket closes and nothing runs until
     *  [enterForeground]. Hermes keeps running turns alive and replays what the client missed. */
    public suspend fun enterBackground(graceMillis: Long = 25_000): Unit = withContext(confined) {
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
    public suspend fun enterForeground(): Unit = withContext(confined) {
        if (!inBackground) return@withContext
        inBackground = false
        if (!wantsConnection || socket != null || maintainer != null || opening) return@withContext
        if (networkAvailable) startMaintaining(delayFirstAttempt = false)
        else publish(GatewayConnectionState.WaitingForNetwork)
    }

    private fun startMaintaining(delayFirstAttempt: Boolean) {
        cancelMaintaining()
        val run = ++maintainerRun
        maintainer = scope.launch(confined) { maintainConnection(delayFirstAttempt, run) }
    }

    private fun cancelMaintaining() {
        maintainer?.cancel()
        maintainer = null
    }

    private suspend fun maintainConnection(delayFirstAttempt: Boolean, run: Int) {
        var attempt = 0
        try {
            while (wantsConnection) {
                if (inBackground) { publish(GatewayConnectionState.Suspended); break }
                // The network monitor restarts this loop when a path appears; nothing polls meanwhile.
                if (!networkAvailable) { publish(GatewayConnectionState.WaitingForNetwork); break }
                attempt++
                publish(if (hasConnected) GatewayConnectionState.Reconnecting(attempt) else GatewayConnectionState.Connecting)
                if (attempt > 1 || delayFirstAttempt) delay(configuration.reconnectDelayMillis(attempt))
                try {
                    open()
                    break
                } catch (error: HermesGatewayException) {
                    if (error.isTerminal) { fail(error); break }
                    logger.log(GatewayLogLevel.INFO, "Connection attempt $attempt failed: ${error.message}")
                }
            }
        } finally {
            if (run == maintainerRun) maintainer = null
        }
    }

    /** One connection attempt: socket, capabilities, then session recovery. */
    private suspend fun open() {
        opening = true
        val current = ++generation
        try {
            val credential = configuration.auth.credential(configuration.baseURI, configuration.httpTransport)
            val (uri, headers, protocols) = socketRequest(credential)
            val connection = try { configuration.transport.connect(uri, headers, protocols) }
            catch (error: CancellationException) { throw error }
            catch (error: HermesGatewayException) { throw error }
            catch (error: Exception) { throw HermesGatewayException.Transport(error.message ?: "WebSocket connect failed") }
            if (current != generation || !wantsConnection) {
                connection.close()
                throw CancellationException("Connection attempt superseded")
            }
            socket = connection
            inboundSinceTick = true
            if (hasConnected) trackedSessionIds().forEach { replayHold[it] = mutableListOf() }
            reader = scope.launch(confined) { readLoop(connection, current) }
            // Hermes forgets this per connection; without it every server request fails immediately.
            call("client.capabilities", ClientCapabilitiesParams(serverRequests = true),
                ClientCapabilitiesParams.serializer(), ClientCapabilitiesResult.serializer(),
                configuration.connectTimeoutMillis, waitsForConnection = false)
            if (socket !== connection) throw HermesGatewayException.Transport("Connection ended during handshake")
            if (hasConnected && !recoverSessions(connection, current)) {
                throw HermesGatewayException.Transport("Session recovery failed; reconnecting to recover it")
            }
            if (socket !== connection) throw HermesGatewayException.Transport("Connection ended during session recovery")
            hasConnected = true
            ready = true
            logger.log(GatewayLogLevel.INFO, "Gateway connected")
            publish(GatewayConnectionState.Connected)
            heartbeat = scope.launch(confined) { heartbeatLoop(connection, current) }
        } catch (error: Throwable) {
            if (current == generation) {
                generation++
                withContext(NonCancellable) { closeConnection(gatewayError(error)) }
            }
            throw error
        } finally {
            opening = false
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
        else if (!inBackground) startMaintaining(delayFirstAttempt = !retryImmediately)
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

    override suspend fun <Params : Any, Result : Any> call(
        method: String,
        params: Params,
        paramsSerializer: KSerializer<Params>,
        resultSerializer: KSerializer<Result>,
    ): Result = call(method, params, paramsSerializer, resultSerializer, configuration.requestTimeoutMillis,
        waitsForConnection = true)

    /** App calls made while reconnecting wait for the connection, up to their timeout. Calls in flight
     *  when a socket dies fail with [HermesGatewayException.Transport]: Hermes may or may not have run them. */
    private suspend fun <Params : Any, Result : Any> call(
        method: String,
        params: Params,
        paramsSerializer: KSerializer<Params>,
        resultSerializer: KSerializer<Result>,
        timeoutMillis: Long,
        waitsForConnection: Boolean,
    ): Result {
        if (waitsForConnection && !withContext(confined) { ready }) {
            if (!withContext(confined) { wantsConnection }) throw HermesGatewayException.Transport("Gateway is disconnected")
            awaitConnection(timeoutMillis)
        }
        val encodedParams = json.encodeToJsonElement(paramsSerializer, params)
        val deferred = CompletableDeferred<JsonElement>()
        val (id, connection, current) = withContext(confined) {
            val active = socket ?: throw HermesGatewayException.Transport("Gateway is disconnected")
            val assigned = nextID++
            pending[assigned] = deferred
            Triple(assigned, active, generation)
        }
        try {
            val frame = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", encodedParams)
            }
            try { connection.send(frame.toString()) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { throw gatewayError(error) }
            val result = try { withTimeout(timeoutMillis) { deferred.await() } }
            catch (_: TimeoutCancellationException) { throw HermesGatewayException.Timeout(method) }
            validateContract(result)
            val decoded = try { json.decodeFromJsonElement(resultSerializer, result) }
            catch (error: Exception) { throw HermesGatewayException.Protocol("Cannot decode $method result: ${error.message}") }
            withContext(confined) { trackSession(method, encodedParams, result) }
            return decoded
        } catch (error: HermesGatewayException.RPC) {
            if (error.code == HermesGatewayErrorCodes.BACKEND_RETIRING) {
                withContext(confined) { connectionLost(HermesGatewayException.Transport("Hermes backend is retiring"), current) }
            }
            throw error
        } finally {
            withContext(confined + NonCancellable) { pending.remove(id) }
        }
    }

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
        if (sessionId != null && seq != null && seq <= (lastSequence[sessionId] ?: 0)) return
        val rawPayload = params["payload"] ?: JsonObject(emptyMap())
        // A payload the generated model rejects must not tear down the socket: reconnect replay
        // would deliver the same frame again. Callers still receive the raw payload.
        val payload = try { GatewayEventPayload.decode(type, rawPayload, json) }
        catch (_: IllegalArgumentException) { GatewayEventPayload.Unknown(type, rawPayload) }
        when (payload) {
            is GatewayEventPayload.GatewayReady -> {
                if (replayEpoch != null && replayEpoch != payload.payload.replayEpoch) resetWatermarks()
                replayEpoch = payload.payload.replayEpoch
                // Older backends answer gateway.ping with an error, which still proves the socket alive.
                heartbeatMethod = if (payload.payload.heartbeat == true) "gateway.ping" else "ping"
            }
            is GatewayEventPayload.RequestCancel -> serverJobs.remove(payload.payload.id)?.cancel()
            GatewayEventPayload.MessageStart -> sessionId?.let { setTurn(it, true) }
            is GatewayEventPayload.MessageComplete, is GatewayEventPayload.Error -> sessionId?.let { setTurn(it, false) }
            is GatewayEventPayload.SessionReclaimed -> {
                val reclaimed = payload.payload
                if (reclaimed.sessionId in sessions || reclaimed.sessionId in lastSequence) {
                    forgetSession(reclaimed.sessionId)
                    mutableRecoveries.tryEmit(GatewaySessionRecovery.Reclaimed(
                        reclaimed.sessionId, reclaimed.storedSessionId, reclaimed.reason))
                }
            }
            else -> Unit
        }
        if (sessionId != null && seq != null) lastSequence[sessionId] = seq
        eventQueue.trySend(GatewayEvent(type, sessionId, seq, payload, replayed))
    }

    private fun setTurn(sessionId: String, active: Boolean) {
        val changed = if (active) activeTurns.add(sessionId) else activeTurns.remove(sessionId)
        if (changed) turnActivity.value = activeTurns.size
    }

    private suspend fun handleServerRequest(id: String, method: String, params: JsonElement, connection: GatewayConnection, current: Int) {
        // A reconnect can deliver one request both live and through open_requests.
        if (id in serverJobs) return
        val request = try { ServerRequest.decode(method, params, json) }
        catch (_: IllegalArgumentException) { answerWithError(id, -32602, "Invalid $method params", connection, current); return }
        if (request is ServerRequest.Unknown) { answerWithError(id, -32601, "Unknown server request", connection, current); return }
        val currentHandler = handler
        if (currentHandler == null) { answerWithError(id, -32601, "No server request handler", connection, current); return }
        // The app's handler runs off the confined dispatcher; it may wait for the user for minutes.
        serverJobs[id] = scope.launch {
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
                withContext(confined + NonCancellable) { serverJobs.remove(id) }
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
        if (contract !in HermesGatewayContract.supportedContractRange) throw HermesGatewayException.IncompatibleServer(contract)
    }

    // Session tracking and recovery

    /** Sessions this client must rebind after a reconnect, whether or not they have emitted events yet. */
    private fun trackedSessionIds(): Set<String> = sessions.keys + lastSequence.keys

    private fun trackSession(method: String, params: JsonElement, result: JsonElement) {
        val arguments = params as? JsonObject ?: JsonObject(emptyMap())
        fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (method) {
            "session.create", "session.resume", "session.activate", "session.branch" -> {
                val fields = result as? JsonObject ?: return
                val sessionId = fields.string("session_id") ?: return
                val previous = sessions[sessionId] ?: TrackedSession()
                sessions[sessionId] = previous.copy(
                    storedId = fields.string("stored_session_id") ?: fields.string("session_key") ?: previous.storedId,
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
        setTurn(sessionId, false)
    }

    /** A new server process restarts every session's numbering. Keep the sessions so the rebind resumes
     *  the ones that did not survive. */
    private fun resetWatermarks() {
        lastSequence.keys.forEach { lastSequence[it] = 0 }
    }

    /** False when a session could not be rebound or its gap replayed: the connection must be retried, because
     *  delivering the live events held meanwhile would move that session's watermark past the gap for good. */
    private suspend fun recoverSessions(connection: GatewayConnection, current: Int): Boolean {
        var recovered = true
        try {
            for (sessionId in trackedSessionIds().sorted()) {
                if (current != generation) return true
                val session = sessions[sessionId] ?: TrackedSession()
                if (session.closeOnDisconnect) {
                    forgetSession(sessionId)
                    mutableRecoveries.tryEmit(GatewaySessionRecovery.Unavailable(sessionId, "Closed on disconnect"))
                } else {
                    when (val outcome = rebind(sessionId)) {
                        Rebind.Bound -> if (!replay(sessionId, connection, current)) {
                            recovered = false
                            return false
                        }
                        is Rebind.Gone -> resume(sessionId, session, outcome.reason)
                        Rebind.RetryLater -> {
                            recovered = false
                            return false
                        }
                    }
                }
                releaseHeldEvents(sessionId)
            }
        } finally {
            val remaining = replayHold.entries.sortedBy { it.key }.flatMap { it.value.toList() }
            replayHold.clear()
            if (recovered) remaining.forEach { handleEvent(it, false) }
        }
        return true
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
                when (error.code) {
                    // Hermes is still settling the disconnect interrupt.
                    HermesGatewayErrorCodes.SESSION_SETTLING -> if (attempt < 3) delay(500L * attempt) else return Rebind.RetryLater
                    HermesGatewayErrorCodes.SESSION_NOT_FOUND, HermesGatewayErrorCodes.SESSION_NOT_LIVE,
                    HermesGatewayErrorCodes.SESSION_UNAVAILABLE -> return Rebind.Gone(error.message ?: "unavailable")
                    else -> return Rebind.RetryLater
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return Rebind.RetryLater
            }
        }
        return Rebind.RetryLater
    }

    /** Hermes reclaims a session 20 s after its socket closes (and every session on restart), but keeps it
     *  in storage. Resuming by the stored id rebuilds it under a new runtime id. */
    private suspend fun resume(sessionId: String, session: TrackedSession, reason: String) {
        val storedId = session.storedId
        if (!configuration.resumesReclaimedSessions || storedId == null) {
            forgetSession(sessionId)
            mutableRecoveries.tryEmit(GatewaySessionRecovery.Unavailable(sessionId, reason))
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
            forgetSession(sessionId)
            logger.log(GatewayLogLevel.INFO, "Resumed a reclaimed session under a new runtime id")
            mutableRecoveries.tryEmit(GatewaySessionRecovery.Resumed(sessionId, result.sessionId, storedId))
        } catch (error: HermesGatewayException.RPC) {
            forgetSession(sessionId)
            mutableRecoveries.tryEmit(GatewaySessionRecovery.Unavailable(sessionId, error.message ?: "unavailable"))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the session; the next reconnect tries again.
        }
    }

    private suspend fun replay(sessionId: String, connection: GatewayConnection, current: Int): Boolean {
        try {
            val result = call("session.events.since",
                SessionEventsSinceParams(sessionId, lastSeen = Patch.Value(lastSequence[sessionId] ?: 0L)),
                SessionEventsSinceParams.serializer(), SessionEventsSinceResult.serializer(),
                RECOVERY_TIMEOUT_MILLIS, waitsForConnection = false)
            if (replayEpoch != null && replayEpoch != result.epoch) {
                resetWatermarks()
                replayEpoch = result.epoch
            } else if (result.truncated) {
                lastSequence[sessionId] = result.latestSeq
                mutableRecoveries.tryEmit(GatewaySessionRecovery.ReplayTruncated(sessionId))
            } else {
                result.events.forEach { handleEvent(JsonObject(it), true) }
            }
            result.openRequests.forEach {
                handleServerRequest(it.id, it.method, JsonObject(it.params), connection, current)
            }
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the watermark; the retried connection replays the gap.
            return false
        }
    }

    private fun releaseHeldEvents(sessionId: String) {
        replayHold.remove(sessionId)?.forEach { handleEvent(it, false) }
    }

    private fun socketRequest(credential: GatewayCredential): Triple<URI, Map<String, String>, List<String>> {
        val scheme = when (configuration.baseURI.scheme) {
            "http" -> "ws"; "https" -> "wss"
            else -> throw HermesGatewayException.Transport("Dashboard URL must use HTTP or HTTPS")
        }
        val base = URI(scheme, configuration.baseURI.userInfo, configuration.baseURI.host,
            configuration.baseURI.port, "/api/ws", null, null)
        return when (credential) {
            is GatewayCredential.Ticket -> {
                if (credential.value.isEmpty()) throw HermesGatewayException.Transport("Empty WebSocket ticket")
                Triple(base, credential.headers, listOf("hermes-gateway-v1", "hermes-gateway-ticket.${credential.value}"))
            }
            is GatewayCredential.LocalToken -> {
                if (credential.value.isEmpty()) throw HermesGatewayException.Transport("Empty local token")
                Triple(URI(base.toString() + "?token=" + URLEncoder.encode(credential.value, "UTF-8")),
                    credential.headers, emptyList())
            }
        }
    }

    private companion object {
        /** Recovery calls must not stall a reconnect behind a wedged backend. */
        const val RECOVERY_TIMEOUT_MILLIS = 10_000L
    }
}
