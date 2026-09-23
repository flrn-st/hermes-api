package st.flrn.hermes.api.runtime

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import st.flrn.hermes.api.generated.gateway.ClientCapabilitiesParams
import st.flrn.hermes.api.generated.gateway.ClientCapabilitiesResult
import st.flrn.hermes.api.generated.gateway.GatewayEventPayload
import st.flrn.hermes.api.generated.gateway.GatewayMethodCatalog
import st.flrn.hermes.api.generated.gateway.HermesGatewayContract
import st.flrn.hermes.api.generated.gateway.ServerRequest
import st.flrn.hermes.api.generated.gateway.ServerRequestResult

public sealed class HermesGatewayException(message: String) : Exception(message) {
    public class Transport(message: String) : HermesGatewayException(message)
    public class Protocol(message: String) : HermesGatewayException(message)
    public class RPC(public val code: Int, message: String, public val data: JsonElement?) : HermesGatewayException(message)
    public class IncompatibleServer(public val contract: Int) : HermesGatewayException("Unsupported desktop contract $contract")
}

public sealed interface GatewayConnectionState {
    public data object Connecting : GatewayConnectionState
    public data object Connected : GatewayConnectionState
    public data class Disconnected(val reason: String?) : GatewayConnectionState
}

public data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val seq: Long?,
    val payload: GatewayEventPayload,
    val replayed: Boolean,
)

public data class HermesGatewayConfiguration(
    val baseURI: URI,
    val auth: HermesAuth,
    val transport: GatewayTransport = KtorGatewayTransport(),
    val httpTransport: GatewayHTTPTransport = transport as? GatewayHTTPTransport
        ?: KtorGatewayTransport(),
    val requestTimeoutMillis: Long = 30_000,
)

/** A JSON-RPC gateway client. Each instance manages one dashboard WebSocket. */
public class HermesGateway(
    private val configuration: HermesGatewayConfiguration,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : GatewayCaller {
    private val lock: Any = Any()
    private val pending: MutableMap<Int, CompletableDeferred<JsonElement>> = mutableMapOf()
    private val serverJobs: MutableMap<String, Job> = mutableMapOf()
    private val lastSequence: MutableMap<String, Long> = mutableMapOf()
    private var nextID: Int = 1
    private var generation: Int = 0
    private var socket: GatewayConnection? = null
    private var reader: Job? = null
    private var connecting: Boolean = false
    private var replayEpoch: String? = null
    private var handler: (suspend (ServerRequest) -> ServerRequestResult)? = null
    private val mutableEvents: MutableSharedFlow<GatewayEvent> = MutableSharedFlow(extraBufferCapacity = 256)
    private val mutableStates: MutableStateFlow<GatewayConnectionState> = MutableStateFlow(GatewayConnectionState.Disconnected(null))

    public val methods: GatewayMethodCatalog = GatewayMethodCatalog(this)
    public val events: SharedFlow<GatewayEvent> = mutableEvents
    public val connectionStates: StateFlow<GatewayConnectionState> = mutableStates

    public fun setServerRequestHandler(value: suspend (ServerRequest) -> ServerRequestResult) {
        synchronized(lock) { handler = value }
    }

    public suspend fun connect() {
        val current = synchronized(lock) {
            if (connecting) throw HermesGatewayException.Transport("Connection already in progress")
            if (socket != null) return
            connecting = true
            ++generation
        }
        mutableStates.value = GatewayConnectionState.Connecting
        try {
            val credential = configuration.auth.credential(configuration.baseURI, configuration.httpTransport)
            val (uri, headers, protocols) = socketRequest(credential)
            val connection = configuration.transport.connect(uri, headers, protocols)
            val accepted = synchronized(lock) {
                if (current != generation) false else { socket = connection; true }
            }
            if (!accepted) { connection.close(); throw CancellationException("Connect superseded") }
            reader = scope.launch { readLoop(connection, current) }
            call("client.capabilities", ClientCapabilitiesParams(serverRequests = true),
                ClientCapabilitiesParams.serializer(), ClientCapabilitiesResult.serializer())
            if (synchronized(lock) { socket !== connection }) throw HermesGatewayException.Transport("Connection ended during handshake")
            mutableStates.value = GatewayConnectionState.Connected
        } catch (error: Throwable) {
            closeConnection(error.message)
            throw error
        } finally {
            synchronized(lock) { connecting = false }
        }
    }

    public suspend fun disconnect() { closeConnection(null) }

    override suspend fun <Params : Any, Result : Any> call(
        method: String,
        params: Params,
        paramsSerializer: KSerializer<Params>,
        resultSerializer: KSerializer<Result>,
    ): Result {
        val deferred = CompletableDeferred<JsonElement>()
        val (id, connection) = synchronized(lock) {
            val active = socket ?: throw HermesGatewayException.Transport("Gateway is disconnected")
            val assigned = nextID++
            pending[assigned] = deferred
            assigned to active
        }
        try {
            val frame = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", json.encodeToJsonElement(paramsSerializer, params))
            }
            connection.send(frame.toString())
            val result = try {
                withTimeout(configuration.requestTimeoutMillis) { deferred.await() }
            } catch (error: TimeoutCancellationException) {
                throw HermesGatewayException.Transport("RPC $method timed out")
            }
            validateContract(result)
            return try { json.decodeFromJsonElement(resultSerializer, result) }
            catch (error: Exception) { throw HermesGatewayException.Protocol("Cannot decode $method result: ${error.message}") }
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    private suspend fun readLoop(connection: GatewayConnection, current: Int) {
        try {
            while (true) {
                val text = connection.receive()
                if (synchronized(lock) { current != generation }) return
                handleFrame(json.parseToJsonElement(text).jsonObject, connection, current)
            }
        } catch (error: Throwable) {
            if (error !is CancellationException && synchronized(lock) { current == generation }) closeConnection(error.message)
        }
    }

    private suspend fun handleFrame(frame: JsonObject, connection: GatewayConnection, current: Int) {
        val method = frame["method"]?.let { (it as? JsonPrimitive)?.content }
        if (method != null) {
            val id = (frame["id"] as? JsonPrimitive)?.content
            if (method == "event") handleEvent(frame["params"]?.jsonObject ?: throw HermesGatewayException.Protocol("Invalid event"))
            else if (id != null) handleServerRequest(id, method, frame["params"] ?: JsonObject(emptyMap()), connection, current)
            return
        }
        val id = (frame["id"] as? JsonPrimitive)?.intOrNull ?: return
        val deferred = synchronized(lock) { pending.remove(id) } ?: return
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

    private suspend fun handleEvent(params: JsonObject) {
        val type = (params["type"] as? JsonPrimitive)?.content ?: throw HermesGatewayException.Protocol("Event has no type")
        val sessionID = (params["session_id"] as? JsonPrimitive)?.content
        val seq = (params["seq"] as? JsonPrimitive)?.longOrNull
        if (sessionID != null && seq != null && synchronized(lock) { seq <= (lastSequence[sessionID] ?: 0) }) return
        val payload = GatewayEventPayload.decode(type, params["payload"] ?: JsonObject(emptyMap()), json)
        synchronized(lock) {
            if (payload is GatewayEventPayload.GatewayReady) {
                if (replayEpoch != null && replayEpoch != payload.payload.replayEpoch) lastSequence.clear()
                replayEpoch = payload.payload.replayEpoch
            }
            if (payload is GatewayEventPayload.RequestCancel) serverJobs.remove(payload.payload.id)?.cancel()
            if (sessionID != null && seq != null) lastSequence[sessionID] = seq
        }
        mutableEvents.emit(GatewayEvent(type, sessionID, seq, payload, false))
    }

    private suspend fun handleServerRequest(id: String, method: String, params: JsonElement, connection: GatewayConnection, current: Int) {
        val request = ServerRequest.decode(method, params, json)
        val currentHandler = synchronized(lock) { handler }
        if (request is ServerRequest.Unknown || currentHandler == null) {
            sendError(id, -32601, "No server request handler", connection)
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = currentHandler(request)
                if (!result.matches(request)) throw HermesGatewayException.Protocol("Server request result kind does not match $method")
                if (synchronized(lock) { current == generation }) {
                    connection.send(buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", id)
                        put("result", json.parseToJsonElement(result.encodedJSON(json)))
                    }.toString())
                }
            } catch (_: CancellationException) {
                // Server withdrew the request.
            } catch (error: Throwable) {
                if (synchronized(lock) { current == generation }) sendError(id, -32603, error.message ?: "Handler failed", connection)
            } finally {
                synchronized(lock) { serverJobs.remove(id) }
            }
        }
        synchronized(lock) { serverJobs[id] = job }
        job.start()
    }

    private suspend fun sendError(id: String, code: Int, message: String, connection: GatewayConnection) {
        connection.send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }.toString())
    }

    private fun validateContract(result: JsonElement) {
        val root = result as? JsonObject ?: return
        val info = root["info"] as? JsonObject
        val contract = ((root["desktop_contract"] ?: info?.get("desktop_contract")) as? JsonPrimitive)
            ?.content?.toIntOrNull() ?: return
        if (contract !in HermesGatewayContract.supportedContractRange) throw HermesGatewayException.IncompatibleServer(contract)
    }

    private suspend fun closeConnection(reason: String?) {
        val previous = synchronized(lock) {
            generation++
            val active = socket
            socket = null
            reader?.cancel()
            reader = null
            serverJobs.values.forEach { it.cancel() }
            serverJobs.clear()
            pending.values.forEach { it.completeExceptionally(HermesGatewayException.Transport(reason ?: "Gateway disconnected")) }
            pending.clear()
            active
        }
        previous?.close()
        mutableStates.value = GatewayConnectionState.Disconnected(reason)
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
                Triple(URI(base.toString() + "?token=" + URLEncoder.encode(credential.value, StandardCharsets.UTF_8)),
                    credential.headers, emptyList())
            }
        }
    }
}
