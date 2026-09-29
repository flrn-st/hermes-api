package hermes.api.runtime

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.takeFrom
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** A one-use display ticket pinned to the physical gateway endpoint that minted it. Never persist it. */
public class DisplayStreamRequest private constructor(internal val uri: URI, public val endpoint: URI) {
    private val consumed = AtomicBoolean(false)
    internal fun consume(): URI {
        check(consumed.compareAndSet(false, true)) { "Display ticket was already used. Observe again." }
        return uri
    }
    override fun toString(): String = "DisplayStreamRequest(<redacted>)"

    public companion object {
        /** [gatewayURI] is the actual connected /api/ws URL, not a resolver that can change endpoints. */
        public fun fromGatewayURI(gatewayURI: URI, ticket: String, path: String = "/api/display/ws"): DisplayStreamRequest {
            require(gatewayURI.scheme in setOf("ws", "wss") && gatewayURI.host != null &&
                gatewayURI.rawUserInfo == null && gatewayURI.rawFragment == null &&
                gatewayURI.rawPath.endsWith("/api/ws")) { "Invalid display gateway endpoint." }
            require(path == "/api/display/ws") { "Invalid display stream path." }
            require(ticket.isNotBlank() && ticket.length <= 8192 && ticket.none(Char::isISOControl)) { "Invalid display ticket." }
            val prefix = gatewayURI.rawPath.removeSuffix("/api/ws")
            val endpoint = URI("${gatewayURI.scheme}://${gatewayURI.rawAuthority}$prefix$path")
            val encoded = URLEncoder.encode(ticket, Charsets.UTF_8.name())
            return DisplayStreamRequest(URI("$endpoint?display_ticket=$encoded"), endpoint)
        }
    }
}

/** ViewerClosed returns control; Reconnecting preserves the current human exclusion on Hermes. */
public enum class DisplayCloseReason { ViewerClosed, Reconnecting }

/** Raw RFB bytes. Control/lease enforcement belongs to Hermes and the viewer's current ownership state. */
public interface DisplayConnection {
    public suspend fun send(bytes: ByteArray)
    public suspend fun receive(): ByteArray
    public suspend fun close(reason: DisplayCloseReason = DisplayCloseReason.ViewerClosed)
}

public interface DisplayTransport {
    /** Redeems [request] exactly once. Retrying requires a fresh display.observe ticket. */
    public suspend fun connect(request: DisplayStreamRequest, headers: Map<String, String> = emptyMap()): DisplayConnection
}

/**
 * Uses the caller's WebSocket-enabled client, including its proxy/TLS/tunnel policy. Does not own it.
 * The payload limit is checked after the engine receives a frame; configure engine limits separately
 * where supported. OkHttp does not support changing WebSocketSession.maxFrameSize.
 */
public class KtorDisplayTransport(
    private val client: HttpClient,
    private val maximumFrameBytes: Int = 16 * 1024 * 1024,
) : DisplayTransport {
    init { require(maximumFrameBytes in 1..(64 * 1024 * 1024)) }

    override suspend fun connect(request: DisplayStreamRequest, headers: Map<String, String>): DisplayConnection {
        val uri = request.consume()
        val session = try {
            client.webSocketSession {
                url { takeFrom(uri.toString()) }
                headers.forEach { (name, value) -> this.headers.append(name, value) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw DisplayStreamException("Could not connect to Bot Screen.") }
        return KtorDisplayConnection(session, maximumFrameBytes)
    }
}

/** Safe to show or log: never retains a URL, ticket, remote close reason or transport exception cause. */
public class DisplayStreamException(message: String, public val closeCode: Int? = null) : Exception(message)

private class KtorDisplayConnection(
    private val session: DefaultClientWebSocketSession,
    private val maximumFrameBytes: Int,
) : DisplayConnection {
    override suspend fun send(bytes: ByteArray) {
        require(bytes.size <= maximumFrameBytes) { "Display frame is too large." }
        try { session.send(Frame.Binary(true, bytes)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw DisplayStreamException("Bot Screen connection closed.") }
    }

    override suspend fun receive(): ByteArray {
        try {
            while (true) {
                when (val frame = session.incoming.receive()) {
                    is Frame.Binary -> {
                        if (frame.data.size > maximumFrameBytes) throw DisplayStreamException("Display frame is too large.")
                        return frame.data
                    }
                    is Frame.Text -> throw DisplayStreamException("Unexpected text from Bot Screen.")
                    is Frame.Close -> throw DisplayStreamException("Bot Screen connection closed.", frame.readReason()?.code?.toInt())
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DisplayStreamException) { throw failure }
        catch (_: Exception) {
            val code = try {
                if (session.closeReason.isCompleted) session.closeReason.await()?.code?.toInt() else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            throw DisplayStreamException("Bot Screen connection closed.", code)
        }
    }

    override suspend fun close(reason: DisplayCloseReason) {
        // Hermes treats 1000/1001 as explicit hand-back, but preserves the lease on dropped links.
        val code: Short = if (reason == DisplayCloseReason.ViewerClosed) 1000 else 1012
        try { session.close(CloseReason(code, "")) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw DisplayStreamException("Bot Screen connection closed.") }
    }
}
