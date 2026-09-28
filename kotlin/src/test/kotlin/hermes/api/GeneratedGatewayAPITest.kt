package hermes.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.PingParams
import hermes.api.testing.RecordedCall
import hermes.api.testing.ScriptedGatewayCaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeneratedGatewayAPITest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    @Test
    fun generatedMethodCallsItsWireName() = runTest {
        val caller = ScriptedGatewayCaller(mapOf("ping" to JsonObject(mapOf("pong" to JsonPrimitive(true)))))
        val result = caller.methods.ping(PingParams())
        assertTrue(result.pong)
        assertEquals(listOf(RecordedCall("ping", JsonObject(emptyMap()))), caller.calls)
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
