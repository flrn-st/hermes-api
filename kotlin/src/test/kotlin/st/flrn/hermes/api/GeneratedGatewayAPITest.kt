package st.flrn.hermes.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import st.flrn.hermes.api.generated.gateway.GatewayEventPayload
import st.flrn.hermes.api.generated.gateway.GatewayMethodCatalog
import st.flrn.hermes.api.generated.gateway.PingParams
import st.flrn.hermes.api.runtime.GatewayCaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeneratedGatewayAPITest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    @Test
    fun generatedMethodCallsItsWireName() = runTest {
        val caller = object : GatewayCaller {
            override suspend fun <Params : Any, Result : Any> call(
                method: String,
                params: Params,
                paramsSerializer: KSerializer<Params>,
                resultSerializer: KSerializer<Result>,
            ): Result {
                assertEquals("ping", method)
                assertEquals("{}", json.encodeToString(paramsSerializer, params))
                return json.decodeFromString(resultSerializer, """{"pong":true}""")
            }
        }
        val result = GatewayMethodCatalog(caller).ping(PingParams())
        assertTrue(result.pong)
    }

    @Test
    fun generatedEventRetainsUnknownPayload() {
        val known = GatewayEventPayload.decode("message.start", JsonObject(emptyMap()), json)
        assertEquals(GatewayEventPayload.MessageStart, known)
        val raw = JsonObject(mapOf("count" to JsonPrimitive(1)))
        val unknown = GatewayEventPayload.decode("future.event", raw, json)
        assertIs<GatewayEventPayload.Unknown>(unknown)
        assertEquals(raw, unknown.raw)
    }
}
