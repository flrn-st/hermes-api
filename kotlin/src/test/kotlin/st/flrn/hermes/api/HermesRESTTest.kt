package st.flrn.hermes.api

import java.net.URI
import kotlinx.coroutines.test.runTest
import st.flrn.hermes.api.runtime.HermesREST
import st.flrn.hermes.api.runtime.HermesRESTConfiguration
import st.flrn.hermes.api.runtime.HermesRESTException
import st.flrn.hermes.api.runtime.RESTTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HermesRESTTest {
    @Test
    fun generatedAuthCallDecodesReviewedResponse() = runTest {
        val transport = object : RESTTransport {
            override suspend fun request(method: String, uri: URI, headers: Map<String, String>): Pair<Int, String> {
                assertEquals("POST", method)
                assertEquals("https://dashboard.example/api/auth/ws-ticket", uri.toString())
                assertEquals("Bearer test", headers["Authorization"])
                return 200 to """{"ticket":"fresh","ttl_seconds":30}"""
            }
        }
        val rest = HermesREST(HermesRESTConfiguration(URI("https://dashboard.example"),
            headers = { mapOf("Authorization" to "Bearer test") }, transport = transport))
        assertEquals("fresh", rest.methods.auth.wsTicket().ticket)
    }

    @Test
    fun unexpectedFieldsFailStrictDecode() = runTest {
        val transport = object : RESTTransport {
            override suspend fun request(method: String, uri: URI, headers: Map<String, String>): Pair<Int, String> =
                200 to """{"ticket":"fresh","ttl_seconds":30,"surprise":1}"""
        }
        val rest = HermesREST(HermesRESTConfiguration(URI("https://dashboard.example"), transport = transport))
        assertFailsWith<HermesRESTException.Decoding> { rest.methods.auth.wsTicket() }
    }

    @Test
    fun httpFailureIsTyped() = runTest {
        val transport = object : RESTTransport {
            override suspend fun request(method: String, uri: URI, headers: Map<String, String>): Pair<Int, String> =
                401 to """{"detail":"Unauthorized"}"""
        }
        val rest = HermesREST(HermesRESTConfiguration(URI("https://dashboard.example"), transport = transport))
        assertEquals(401, assertFailsWith<HermesRESTException.HTTP> { rest.methods.auth.wsTicket() }.status)
    }
}
