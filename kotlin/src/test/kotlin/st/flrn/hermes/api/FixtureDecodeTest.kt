package st.flrn.hermes.api

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import st.flrn.hermes.api.generated.gateway.ClientCapabilitiesResult
import st.flrn.hermes.api.generated.gateway.GatewayCapabilitiesResult
import st.flrn.hermes.api.generated.gateway.GatewayEventPayload
import st.flrn.hermes.api.generated.gateway.GatewayReadyPayload
import st.flrn.hermes.api.generated.gateway.PingResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixtureDecodeTest {
    @Test
    fun recordedLivenessFramesDecodeInKotlin() {
        val json = Json { ignoreUnknownKeys = false }
        val ref = Files.readString(Path.of("../spec/current-release.txt")).trim()
        val lines = Files.readAllLines(Path.of("../fixtures/$ref/liveness.jsonl"))
        assertEquals(4, lines.size)
        for (line in lines) {
            val record = json.parseToJsonElement(line).jsonObject
            val name = record.getValue("name").jsonPrimitive.content
            val frame = record.getValue("frame").jsonObject
            when (name) {
                "gateway.ready" -> {
                    val params = frame.getValue("params").jsonObject
                    val payload = GatewayEventPayload.decode("gateway.ready", params.getValue("payload"), json)
                    val ready = assertIs<GatewayEventPayload.GatewayReady>(payload).payload
                    assertTrue(ready.replayEpoch.isNotEmpty())
                    assertEquals(params.getValue("payload"), json.encodeToJsonElement(GatewayReadyPayload.serializer(), ready))
                }
                "client.capabilities" -> {
                    val result = json.decodeFromJsonElement(ClientCapabilitiesResult.serializer(), frame.getValue("result"))
                    assertTrue(result.serverRequests.contains("approval"))
                    assertEquals(frame.getValue("result"), json.encodeToJsonElement(ClientCapabilitiesResult.serializer(), result))
                }
                "ping" -> {
                    val result = json.decodeFromJsonElement(PingResult.serializer(), frame.getValue("result"))
                    assertTrue(result.pong)
                    assertEquals(frame.getValue("result"), json.encodeToJsonElement(PingResult.serializer(), result))
                }
                "gateway.capabilities" -> {
                    val result = json.decodeFromJsonElement(GatewayCapabilitiesResult.serializer(), frame.getValue("result"))
                    assertTrue(result.perSessionExclusiveSubmit)
                    assertEquals(frame.getValue("result"), json.encodeToJsonElement(GatewayCapabilitiesResult.serializer(), result))
                }
                else -> error("Unexpected fixture: $name")
            }
        }
    }
}
