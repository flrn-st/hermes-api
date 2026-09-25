package st.flrn.hermes.api.live

import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import st.flrn.hermes.api.generated.gateway.GatewayEventPayload
import st.flrn.hermes.api.generated.gateway.ServerRequest
import st.flrn.hermes.api.runtime.GatewayConnection
import st.flrn.hermes.api.runtime.GatewayTransport

/** What a live run exercised, measured on the wire and reported to the harness as coverage evidence:
 *  methods that returned a result, events that decoded to their typed payload, and server requests
 *  that decoded and were answered with a result. */
internal class LiveObservations {
    private val methods = sortedSetOf<String>()
    private val events = sortedSetOf<String>()
    private val serverRequests = sortedSetOf<String>()

    fun method(name: String) = synchronized(this) { methods.add(name); Unit }
    fun event(name: String) = synchronized(this) { events.add(name); Unit }
    fun serverRequest(name: String) = synchronized(this) { serverRequests.add(name); Unit }

    fun report(): String = synchronized(this) {
        fun list(values: Set<String>) = JsonArray(values.map(::JsonPrimitive))
        JsonObject(mapOf("methods" to list(methods), "events" to list(events),
            "server_requests" to list(serverRequests))).toString()
    }
}

/** Wraps a transport so every socket it opens reports to [observations], and counts opened sockets.
 *  `connectionStates` is a StateFlow, so a collector can miss the brief Reconnecting state and cannot
 *  count reconnects reliably; the socket count can. */
internal class ObservingTransport(
    private val delegate: GatewayTransport,
    private val observations: LiveObservations,
) : GatewayTransport {
    private val opened = AtomicInteger()

    val reconnects: Int get() = maxOf(0, opened.get() - 1)

    override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection {
        val connection = delegate.connect(uri, headers, protocols)
        opened.incrementAndGet()
        return ObservedConnection(connection, observations)
    }
}

private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

private class ObservedConnection(
    private val delegate: GatewayConnection,
    private val observations: LiveObservations,
) : GatewayConnection {
    /** Request ids are scoped to one socket: calls this client sent and server requests it received. */
    private val calls = mutableMapOf<Int, String>()
    private val requests = mutableMapOf<String, String>()

    override suspend fun send(text: String) {
        delegate.send(text)
        val fields = parse(text) ?: return
        val method = (fields["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = fields["id"] as? JsonPrimitive ?: return
        if (method != null && !id.isString) {
            id.intOrNull?.let { synchronized(calls) { calls[it] = method } }
        } else if (method == null && id.isString && fields["result"] != null) {
            synchronized(requests) { requests.remove(id.content) }?.let(observations::serverRequest)
        }
    }

    override suspend fun receive(): String {
        val text = delegate.receive()
        val fields = parse(text) ?: return text
        val method = (fields["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = fields["id"] as? JsonPrimitive
        when {
            method == "event" -> {
                val params = fields["params"] as? JsonObject ?: return text
                val type = (params["type"] as? JsonPrimitive)?.content ?: return text
                val payload = runCatching { GatewayEventPayload.decode(type, params["payload"] ?: JsonObject(emptyMap()), json) }
                    .getOrNull()
                if (payload != null && payload !is GatewayEventPayload.Unknown) observations.event(type)
            }
            method != null && id != null && id.isString -> {
                val request = runCatching { ServerRequest.decode(method, fields["params"] ?: JsonObject(emptyMap()), json) }
                    .getOrNull()
                if (request != null && request !is ServerRequest.Unknown) synchronized(requests) { requests[id.content] = method }
            }
            method == null && id != null && fields["result"] != null ->
                id.intOrNull?.let { synchronized(calls) { calls.remove(it) } }?.let(observations::method)
        }
        return text
    }

    override suspend fun close() = delegate.close()

    private fun parse(text: String): Map<String, JsonElement>? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
