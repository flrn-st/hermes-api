package hermes.api.testing

import java.net.URI
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import hermes.api.runtime.GatewayConnection
import hermes.api.runtime.GatewayCredential
import hermes.api.runtime.GatewayHTTPTransport
import hermes.api.runtime.GatewayNetworkMonitor
import hermes.api.runtime.GatewayNetworkPath
import hermes.api.runtime.GatewayTransport
import hermes.api.runtime.HermesAuth
import hermes.api.runtime.HermesGatewayException

/**
 * A fake Hermes WebSocket for driving a real `HermesGateway` in tests. It answers `client.capabilities`,
 * optionally answers heartbeats, and hands every other frame the client sends to [sent]. Push what Hermes
 * would send with [inject] and the builders in [GatewayFrames].
 *
 * @param answersHeartbeats Reply to `gateway.ping` so an idle connection stays alive.
 * @param serverRequests The server request kinds the capability handshake reports.
 */
public class ScriptedGatewaySocket(
    private val answersHeartbeats: Boolean = false,
    private val serverRequests: List<String> = listOf("approval"),
) : GatewayConnection {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val sentFrames = Channel<String>(Channel.UNLIMITED)
    private val heartbeatIds = Channel<String>(Channel.UNLIMITED)
    @Volatile private var sendsFail = false

    /** Frames the client sent, except the capability handshake and heartbeats. Parse them with [SentCall]
     *  and [SentAnswer]. */
    public val sent: ReceiveChannel<String> get() = sentFrames

    /** The ids of heartbeat pings the client sent. */
    public val heartbeats: ReceiveChannel<String> get() = heartbeatIds

    @Volatile public var isClosed: Boolean = false
        private set

    /** The socket died, but its reader has not noticed: sends fail, reads still wait. */
    public fun failSends() {
        sendsFail = true
    }

    override suspend fun send(text: String) {
        if (isClosed || sendsFail) throw HermesGatewayException.Transport("closed")
        val frame = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: IllegalArgumentException) { null }
            ?: throw HermesGatewayException.Protocol("Outgoing frame is not a JSON object")
        val method = (frame["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (method) {
            "client.capabilities" -> {
                val id = (frame["id"] as? JsonPrimitive)?.intOrNull ?: 0
                inject(GatewayFrames.result(id, JsonObject(mapOf(
                    "server_requests" to JsonArray(serverRequests.map(::JsonPrimitive)),
                ))))
            }
            "gateway.ping" -> {
                val id = (frame["id"] as? JsonPrimitive)?.content.orEmpty()
                heartbeatIds.trySend(id)
                if (answersHeartbeats) {
                    inject("""{"jsonrpc":"2.0","id":${JsonPrimitive(id)},"result":{"ok":true}}""")
                }
            }
            else -> sentFrames.trySend(text)
        }
    }

    override suspend fun receive(): String = incoming.receive()

    /** Delivers one frame from Hermes to the client. */
    public fun inject(json: String) {
        incoming.trySend(json)
    }

    /** Hermes (or the network) ends the socket: frames already injected are read first, then [error]. */
    public fun sever(error: HermesGatewayException = HermesGatewayException.Transport("closed")) {
        incoming.close(error)
    }

    override suspend fun close() {
        isClosed = true
        sever()
        sentFrames.close()
        heartbeatIds.close()
    }
}

/**
 * Hands out scripted sockets or failures in order, recording each attempt. Once the script is exhausted,
 * further attempts wait until cancelled, like a server that never answers.
 */
public class ScriptedGatewayTransport(steps: List<Step>) : GatewayTransport {
    public constructor(vararg sockets: ScriptedGatewaySocket) : this(sockets.map { Step.Socket(it) })

    public sealed interface Step {
        public data class Socket(val socket: ScriptedGatewaySocket) : Step
        public data class Failure(val error: HermesGatewayException) : Step
    }

    private val lock = Any()
    private val remaining = steps.toMutableList()
    private val recordedURIs = mutableListOf<URI>()
    private val recordedHeaders = mutableListOf<Map<String, String>>()
    private val recordedProtocols = mutableListOf<List<String>>()

    public val attempts: Int get() = synchronized(lock) { recordedURIs.size }
    public val uris: List<URI> get() = synchronized(lock) { recordedURIs.toList() }
    public val headers: List<Map<String, String>> get() = synchronized(lock) { recordedHeaders.toList() }
    public val protocols: List<List<String>> get() = synchronized(lock) { recordedProtocols.toList() }

    override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection {
        val step = synchronized(lock) {
            recordedURIs += uri
            recordedHeaders += headers
            recordedProtocols += protocols
            remaining.removeFirstOrNull()
        } ?: awaitCancellation()
        return when (step) {
            is Step.Socket -> step.socket
            is Step.Failure -> throw step.error
        }
    }
}

/** A network monitor tests move between paths with [change]. New collectors start from the latest path. */
public class ScriptedNetworkMonitor(initial: GatewayNetworkPath = wifi) : GatewayNetworkMonitor {
    private val flow = MutableSharedFlow<GatewayNetworkPath>(replay = 1, extraBufferCapacity = 1024)
        .also { it.tryEmit(initial) }

    override fun paths(): Flow<GatewayNetworkPath> = flow

    public fun change(path: GatewayNetworkPath) {
        flow.tryEmit(path)
    }

    public companion object {
        public val wifi: GatewayNetworkPath = GatewayNetworkPath(isAvailable = true, network = "wifi:en0")
        public val cellular: GatewayNetworkPath = GatewayNetworkPath(isAvailable = true, network = "cellular:pdp_ip0")
        public val offline: GatewayNetworkPath = GatewayNetworkPath(isAvailable = false, network = null)
    }
}

/** A gateway credential that needs no HTTP: a fixed ticket and headers. */
public class StaticTicketAuth(
    public val ticket: String = "test-ticket",
    public val headers: Map<String, String> = emptyMap(),
) : HermesAuth {
    override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential =
        GatewayCredential.Ticket(ticket, headers)
}
