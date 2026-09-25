package st.flrn.hermes.api

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
import st.flrn.hermes.api.runtime.HermesGatewayException
import st.flrn.hermes.api.runtime.KtorGatewayTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KtorGatewayTransportTest {
    @Test
    fun ticketPostSendsAppAuthenticationHeader() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization: String? = null
        server.createContext("/api/auth/ws-ticket") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"ticket":"single-use","ttl_seconds":30}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val transport = KtorGatewayTransport()
        try {
            val (status, body) = transport.post(
                URI("http://127.0.0.1:${server.address.port}/api/auth/ws-ticket"),
                mapOf("Authorization" to "Bearer app-token"),
            )
            assertEquals(200, status)
            assertEquals("Bearer app-token", authorization)
            assertEquals("""{"ticket":"single-use","ttl_seconds":30}""", body)
        } finally {
            transport.close()
            server.stop(0)
        }
    }

    @Test
    fun rejectedUpgradeIsAnAuthenticationFailure() = runBlocking<Unit> {
        // Hermes closes an unauthenticated socket before accepting it, which uvicorn sends as HTTP 403.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/ws") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.start()
        val transport = KtorGatewayTransport()
        try {
            val error = assertFailsWith<HermesGatewayException> {
                transport.connect(URI("ws://127.0.0.1:${server.address.port}/api/ws?token=wrong"), emptyMap(), emptyList())
            }
            assertIs<HermesGatewayException.AuthenticationFailed>(error, error.message)
        } finally {
            transport.close()
            server.stop(0)
        }
    }

    @Test
    fun unreachableServerIsATransportFailure() = runBlocking<Unit> {
        val transport = KtorGatewayTransport()
        try {
            val error = assertFailsWith<HermesGatewayException> {
                transport.connect(URI("ws://127.0.0.1:9/api/ws"), emptyMap(), emptyList())
            }
            assertIs<HermesGatewayException.Transport>(error, error.message)
        } finally {
            transport.close()
        }
    }
}
