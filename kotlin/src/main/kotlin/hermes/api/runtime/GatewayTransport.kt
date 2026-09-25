package hermes.api.runtime

import java.io.IOException
import java.net.URI
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.takeFrom
import io.ktor.websocket.close
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

/** Socket boundary, supplied by Ktor in production and fakes in tests. */
public interface GatewayConnection {
    public suspend fun send(text: String)
    public suspend fun receive(): String
    public suspend fun close()
}

public interface GatewayTransport {
    public suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection
}

public interface GatewayHTTPTransport {
    public suspend fun post(uri: URI, headers: Map<String, String>): Pair<Int, String>
}

/** Inject a configured client to control cookies, TLS trust and tunneled endpoints. */
public class KtorGatewayTransport(public val client: HttpClient = defaultClient()) : GatewayTransport, GatewayHTTPTransport, AutoCloseable {
    override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection {
        val session = try {
            client.webSocketSession {
                url { takeFrom(uri.toString()) }
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                if (protocols.isNotEmpty()) this.headers.append("Sec-WebSocket-Protocol", protocols.joinToString(", "))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw upgradeFailure(error)
        }
        return KtorConnection(session)
    }

    /** Hermes rejects a bad credential before the upgrade. OkHttp reports that as
     *  "Expected HTTP 101 response but was '403 Forbidden'" somewhere in the cause chain. */
    private fun upgradeFailure(error: Exception): HermesGatewayException {
        val status = generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { UPGRADE_STATUS.find(it.message.orEmpty())?.groupValues?.get(1) }
            .firstOrNull()
        return if (status == "401" || status == "403") {
            HermesGatewayException.AuthenticationFailed("WebSocket upgrade returned HTTP $status")
        } else {
            HermesGatewayException.Transport(error.message ?: "WebSocket connect failed")
        }
    }

    override suspend fun post(uri: URI, headers: Map<String, String>): Pair<Int, String> {
        val response = client.post(uri.toString()) {
            headers.forEach { (name, value) -> this.headers.append(name, value) }
        }
        return response.status.value to response.bodyAsText()
    }

    override fun close(): Unit = client.close()

    public companion object {
        private val UPGRADE_STATUS = Regex("""\b101\b.*?\b([1-5]\d\d)\b""")

        /** No transport-level pings: the gateway's own heartbeat decides liveness and stays quiet while
         *  events flow, which saves radio wake-ups on mobile. */
        public fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(WebSockets)
        }
    }
}

private class KtorConnection(private val session: DefaultClientWebSocketSession) : GatewayConnection {
    override suspend fun send(text: String) { session.send(Frame.Text(text)) }
    override suspend fun receive(): String {
        while (true) {
            when (val frame = session.incoming.receive()) {
                is Frame.Text -> return frame.readText()
                is Frame.Close -> throw IOException("WebSocket closed: ${frame.toString()}")
                else -> Unit
            }
        }
    }
    override suspend fun close() { session.close() }
}
