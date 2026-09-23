package st.flrn.hermes.api

import java.net.URI
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import st.flrn.hermes.api.generated.gateway.PingParams
import st.flrn.hermes.api.runtime.GatewayConnection
import st.flrn.hermes.api.runtime.GatewayTransport
import st.flrn.hermes.api.runtime.HermesGateway
import st.flrn.hermes.api.runtime.HermesGatewayConfiguration
import st.flrn.hermes.api.runtime.LocalTokenAuth
import st.flrn.hermes.api.runtime.DashboardTicketAuth
import st.flrn.hermes.api.runtime.GatewayHTTPTransport
import st.flrn.hermes.api.runtime.GatewayCredential
import st.flrn.hermes.api.runtime.HermesGatewayException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class HermesGatewayTest {
    private class FakeSocket : GatewayConnection {
        val inbound = Channel<String>(Channel.UNLIMITED)
        val outbound = Channel<String>(Channel.UNLIMITED)
        override suspend fun send(text: String) { outbound.send(text) }
        override suspend fun receive(): String = inbound.receive()
        override suspend fun close() { inbound.close() }
        suspend fun sent() = Json.parseToJsonElement(withTimeout(3_000) { outbound.receive() }).jsonObject
    }

    @Test
    fun authenticatedTicketUsesPost() = runTest {
        var requested: URI? = null
        val http = object : GatewayHTTPTransport {
            override suspend fun post(uri: URI, headers: Map<String, String>): Pair<Int, String> {
                requested = uri
                assertEquals("Bearer example", headers["Authorization"])
                return 200 to """{"ticket":"single-use","ttl_seconds":30}"""
            }
        }
        val credential = DashboardTicketAuth { mapOf("Authorization" to "Bearer example") }
            .credential(URI("https://dashboard.example"), http)
        assertEquals("https://dashboard.example/api/auth/ws-ticket", requested.toString())
        assertEquals("single-use", assertIs<GatewayCredential.Ticket>(credential).value)
    }

    @Test
    fun rpcErrorsAreTyped() = runTest {
        val fake = FakeSocket()
        val transport = object : GatewayTransport {
            override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection = fake
        }
        val client = HermesGateway(HermesGatewayConfiguration(
            URI("http://localhost:3000"), LocalTokenAuth("secret"), transport, requestTimeoutMillis = 3_000,
        ), scope = backgroundScope)
        val opening = async { client.connect() }
        val capability = fake.sent()
        fake.inbound.send("""{"jsonrpc":"2.0","id":${capability["id"]},"result":{"server_requests":[]}}""")
        opening.await()
        val request = async { runCatching { client.methods.ping(PingParams()) } }
        val frame = fake.sent()
        fake.inbound.send("""{"jsonrpc":"2.0","id":${frame["id"]},"error":{"code":4015,"message":"missing session"}}""")
        val failure = assertFailsWith<HermesGatewayException.RPC> { request.await().getOrThrow() }
        assertEquals(4015, failure.code)
        client.disconnect()
    }

    @Test
    fun connectsAndCorrelatesOutOfOrderCalls() = runTest {
        val fake = FakeSocket()
        var observedURI: URI? = null
        val transport = object : GatewayTransport {
            override suspend fun connect(uri: URI, headers: Map<String, String>, protocols: List<String>): GatewayConnection {
                observedURI = uri
                return fake
            }
        }
        val client = HermesGateway(HermesGatewayConfiguration(
            URI("http://localhost:3000"), LocalTokenAuth("secret"), transport, requestTimeoutMillis = 3_000,
        ), scope = backgroundScope)
        val opening = async { client.connect() }
        val capability = fake.sent()
        assertEquals("client.capabilities", capability["method"]?.jsonPrimitive?.content)
        fake.inbound.send("""{"jsonrpc":"2.0","id":${capability["id"]},"result":{"server_requests":[]}}""")
        opening.await()
        assertEquals("ws://localhost:3000/api/ws?token=secret", observedURI.toString())

        val first = async { client.methods.ping(PingParams()) }
        val second = async { client.methods.ping(PingParams()) }
        val frame1 = fake.sent()
        val frame2 = fake.sent()
        val id1 = frame1["id"]
        val id2 = frame2["id"]
        assertTrue(id1 != id2)
        fake.inbound.send("""{"jsonrpc":"2.0","id":$id2,"result":{"pong":true}}""")
        fake.inbound.send("""{"jsonrpc":"2.0","id":$id1,"result":{"pong":true}}""")
        assertTrue(first.await().pong)
        assertTrue(second.await().pong)
        client.disconnect()
    }
}
