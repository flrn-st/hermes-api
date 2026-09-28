package hermes.api.testing

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.HermesGatewayContract
import hermes.api.generated.gateway.ServerRequest
import hermes.api.generated.gateway.OpenRequestEntry
import hermes.api.generated.gateway.ServerRequestResult
import hermes.api.runtime.GatewayConnectionState
import hermes.api.runtime.GatewayEvent
import hermes.api.runtime.GatewaySessionRecovery
import hermes.api.runtime.HermesGatewayClient
import hermes.api.runtime.HermesGatewayException

/**
 * A stand-in for `HermesGateway` behind [HermesGatewayClient], with no socket. Calls are answered by a
 * responder; tests push events, connection states and recoveries, and put server requests to the app's
 * handler with [request].
 */
public class FakeGateway(
    backendContract: Int? = HermesGatewayContract.desktopContract,
    respond: GatewayResponder = { method, _ -> throw methodNotFound(method) },
) : HermesGatewayClient {
    /** Answers each method with a fixed result; any other method fails with Hermes' "method not found". */
    public constructor(results: Map<String, JsonElement>) : this(respond = responderFor(results))

    private val scripted = ScriptedCalls(respond, gatewayJson)
    private val eventFanout = Fanout<GatewayEvent>()
    private val recoveryFanout = Fanout<GatewaySessionRecovery>()
    private val states = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
    private val recordedLifecycle = mutableListOf<String>()
    private val snapshots = mutableListOf<OpenRequestEntry>()

    /** Snapshot requests the app asked to restore. Drive the handler explicitly with [request], as with
     *  live requests, so tests decide when a pending request is presented and answered. */
    public val restoredRequests: List<OpenRequestEntry> get() = synchronized(snapshots) { snapshots.toList() }
    @Volatile private var handler: (suspend (ServerRequest) -> ServerRequestResult)? = null

    /** Calls in the order they were made, with their encoded params. */
    public val calls: List<RecordedCall> get() = scripted.calls

    /** Lifecycle calls in order: `connect`, `disconnect`, `enterBackground`, `enterForeground`. */
    public val lifecycle: List<String> get() = synchronized(recordedLifecycle) { recordedLifecycle.toList() }

    /** What [connect] throws, if anything; otherwise it moves to [GatewayConnectionState.Connected]. */
    @Volatile public var connectFailure: HermesGatewayException? = null

    @Volatile override var backendContract: Int? = backendContract

    // HermesGatewayClient

    override val events: Flow<GatewayEvent> = eventFanout.flow
    override val connectionStates: StateFlow<GatewayConnectionState> = states
    override val sessionRecoveries: Flow<GatewaySessionRecovery> = recoveryFanout.flow

    override fun setServerRequestHandler(value: suspend (ServerRequest) -> ServerRequestResult) {
        handler = value
    }

    override suspend fun restoreServerRequests(requests: List<OpenRequestEntry>) {
        synchronized(snapshots) { snapshots.addAll(requests) }
    }

    override suspend fun connect() {
        record("connect")
        connectFailure?.let { failure ->
            states.value = GatewayConnectionState.Failed(failure)
            throw failure
        }
        states.value = GatewayConnectionState.Connected
    }

    override suspend fun disconnect() {
        record("disconnect")
        states.value = GatewayConnectionState.Idle
    }

    override suspend fun enterBackground(graceMillis: Long) {
        record("enterBackground")
        states.value = GatewayConnectionState.Suspended
    }

    override suspend fun enterForeground() {
        record("enterForeground")
        states.value = GatewayConnectionState.Connected
    }

    override suspend fun <Params : Any, Result : Any> call(
        method: String, params: Params, paramsSerializer: KSerializer<Params>, resultSerializer: KSerializer<Result>,
    ): Result = scripted.call(method, params, paramsSerializer, resultSerializer)

    // Driving the fake

    /** Delivers an event to every [events] collector. */
    public fun emit(event: GatewayEvent) {
        eventFanout.emit(event)
    }

    /** Decodes [payload] as the generated model for [type], as the gateway does (falling back to
     *  [GatewayEventPayload.Unknown]), and delivers it. */
    public fun emit(
        type: String, sessionId: String? = null, seq: Long? = null,
        payload: JsonElement = JsonObject(emptyMap()), replayed: Boolean = false,
    ) {
        val decoded = try { GatewayEventPayload.decode(type, payload, gatewayJson) }
        catch (_: IllegalArgumentException) { GatewayEventPayload.Unknown(type, payload) }
        eventFanout.emit(GatewayEvent(type, sessionId, seq, decoded, replayed))
    }

    public fun setConnectionState(state: GatewayConnectionState) {
        states.value = state
    }

    /** Delivers a recovery to every [sessionRecoveries] collector. */
    public fun recover(recovery: GatewaySessionRecovery) {
        recoveryFanout.emit(recovery)
    }

    /** Puts a server request to the app's handler and returns its answer, checked as the gateway checks it. */
    public suspend fun request(request: ServerRequest): ServerRequestResult {
        val current = handler ?: throw HermesGatewayException.RPC(-32601, "No server request handler", null)
        val result = current(request)
        if (!result.matches(request)) {
            throw HermesGatewayException.Protocol("Server request result kind does not match the request")
        }
        return result
    }

    /** Decodes a server request from its wire method and params, as the gateway does, and puts it to the
     *  handler. Params the generated model rejects fail with -32602, an unknown method with -32601, as the
     *  gateway answers Hermes. */
    public suspend fun request(method: String, params: JsonElement): ServerRequestResult {
        val decoded = try { ServerRequest.decode(method, params, gatewayJson) }
        catch (_: IllegalArgumentException) { throw HermesGatewayException.RPC(-32602, "Invalid $method params", null) }
        if (decoded is ServerRequest.Unknown) throw HermesGatewayException.RPC(-32601, "Unknown server request", null)
        return request(decoded)
    }

    private fun record(step: String) {
        synchronized(recordedLifecycle) { recordedLifecycle += step }
    }
}

/** Fans values out to collectors, each buffered independently from when its collection starts. */
private class Fanout<T> {
    private val lock = Any()
    private val subscribers = LinkedHashSet<Channel<T>>()

    val flow: Flow<T> = flow {
        val channel = Channel<T>(Channel.UNLIMITED)
        synchronized(lock) { subscribers += channel }
        try {
            for (value in channel) emit(value)
        } finally {
            synchronized(lock) { subscribers -= channel }
            channel.cancel()
        }
    }

    fun emit(value: T) {
        synchronized(lock) { subscribers.forEach { it.trySend(value) } }
    }
}
