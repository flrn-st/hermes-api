package hermes.api

import hermes.api.runtime.DisplayStreamRequest
import hermes.api.runtime.KtorDisplayTransport
import hermes.api.runtime.DisplayStreamException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.net.URI
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class DisplayTransportTest {
    @Test fun pinsActualGatewayOriginAndPrefixWithoutReusingGatewayCredentials() {
        val request = DisplayStreamRequest.fromGatewayURI(
            URI("wss://example.test:8443/agents/a%20b/api/ws?ticket=GATEWAY_SECRET"), "DISPLAY+SECRET&/")
        assertEquals("wss://example.test:8443/agents/a%20b/api/display/ws", request.endpoint.toString())
        assertEquals("display_ticket=DISPLAY%2BSECRET%26%2F", request.uri.rawQuery)
        assertFalse(request.uri.toString().contains("GATEWAY_SECRET"))
        assertFalse(request.toString().contains("SECRET"))
        assertFalse(request.toString().contains("example.test"))
    }

    @Test fun rejectsRetargetingAndInvalidCapabilities() {
        val good = URI("ws://127.0.0.1:1234/api/ws")
        for (path in listOf("https://other.test/api/display/ws", "//other.test/api/display/ws", "/api/ws", "/api/display/ws?extra=1")) {
            assertFailsWith<IllegalArgumentException> { DisplayStreamRequest.fromGatewayURI(good, "ticket", path) }
        }
        for (url in listOf("https://example.test/api/ws", "wss://user:secret@example.test/api/ws", "wss://example.test/api/ws#fragment", "wss://example.test/other")) {
            assertFailsWith<IllegalArgumentException> { DisplayStreamRequest.fromGatewayURI(URI(url), "ticket") }
        }
        for (ticket in listOf("", " ", "a\nb", "a".repeat(8193))) {
            assertFailsWith<IllegalArgumentException> { DisplayStreamRequest.fromGatewayURI(good, ticket) }
        }
    }

    @Test fun aTicketCannotBeRedeemedTwiceEvenAfterAFailedConnection() {
        val request = DisplayStreamRequest.fromGatewayURI(URI("ws://[::1]:1234/api/ws"), "ticket")
        assertEquals("ws://[::1]:1234/api/display/ws?display_ticket=ticket", request.consume().toString())
        val failure = assertFailsWith<IllegalStateException> { request.consume() }
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains("display_ticket="))
    }

    @Test fun transportsBinaryRfbWithProxyHeadersAndDoesNotExposeRemoteCloseReason(): Unit = runBlocking {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit<Pair<String, ByteArray>> {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    check(byte >= 0 && header.length < 16_384)
                    header.append(byte.toChar())
                }
                val key = header.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                output.write(byteArrayOf(0x82.toByte(), 4, 0, 1, 0x80.toByte(), 0xff.toByte()))
                output.flush()
                check(input.read() == 0x82)
                val length = input.read()
                check(length == 0x84) // Client frames must be masked.
                val mask = input.readNBytes(4)
                val payload = input.readNBytes(4)
                for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                val reason = byteArrayOf(0x11, 0x31) + "PRIVATE_CLOSE_REASON".toByteArray() // 4401: expired ticket.
                output.write(byteArrayOf(0x88.toByte(), reason.size.toByte()) + reason)
                output.flush()
                input.read() // Allow the client's close response to complete.
                header.toString() to payload
            }
        }
        val client = HttpClient(OkHttp) { followRedirects = false; install(WebSockets) }
        try {
            withTimeout(8_000) {
                val address = URI("ws://localhost:${server.localPort}/prefix/api/ws?ticket=unrelated")
                val request = DisplayStreamRequest.fromGatewayURI(address, "fixture-ticket")
                val transport = KtorDisplayTransport(client)
                val connection = transport.connect(request, mapOf("X-Proxy" to "fixture-proxy"))
                try {
                    assertContentEquals(byteArrayOf(0, 1, 0x80.toByte(), 0xff.toByte()), connection.receive())
                    val sent = byteArrayOf(5, 6, 7, 8)
                    connection.send(sent)
                    val failure = assertFailsWith<DisplayStreamException> { connection.receive() }
                    assertFalse(failure.message.orEmpty().contains("PRIVATE_CLOSE_REASON"))
                    assertNull(failure.cause)
                    val (headers, received) = served.get(5, TimeUnit.SECONDS)
                    assertTrue(headers.startsWith("GET /prefix/api/display/ws?display_ticket=fixture-ticket HTTP/1.1\r\n"))
                    assertTrue(headers.contains("X-Proxy: fixture-proxy", true))
                    assertFalse(headers.contains("unrelated"))
                    assertContentEquals(sent, received)
                    assertFailsWith<IllegalStateException> { transport.connect(request) }
                } finally { connection.close() }
            }
        } finally { client.close(); server.close(); executor.shutdownNow() }
    }
}
