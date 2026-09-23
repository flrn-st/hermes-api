package st.flrn.hermes.api

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import st.flrn.hermes.api.generated.gateway.ClientCapabilitiesResult
import st.flrn.hermes.api.generated.gateway.ClarifyRequestParams
import st.flrn.hermes.api.generated.gateway.ClarifyResult
import st.flrn.hermes.api.generated.gateway.ApprovalChoice
import st.flrn.hermes.api.generated.gateway.ApprovalRequestParams
import st.flrn.hermes.api.generated.gateway.ApprovalResult
import st.flrn.hermes.api.generated.gateway.GatewayCapabilitiesResult
import st.flrn.hermes.api.generated.gateway.GatewayEventPayload
import st.flrn.hermes.api.generated.gateway.GatewayReadyPayload
import st.flrn.hermes.api.generated.gateway.PingResult
import st.flrn.hermes.api.generated.gateway.PromptSubmitResult
import st.flrn.hermes.api.generated.gateway.SessionCreateResult
import st.flrn.hermes.api.generated.gateway.SessionListResult
import st.flrn.hermes.api.generated.gateway.SessionCloseResult
import st.flrn.hermes.api.runtime.Patch
import st.flrn.hermes.api.generated.rest.SessionsEmptyCountResponse
import st.flrn.hermes.api.generated.rest.AudioVoiceLiveStatusResponse
import st.flrn.hermes.api.generated.rest.ProfilesActiveResponse
import st.flrn.hermes.api.generated.rest.ProfilesSetActiveResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixtureDecodeTest {
    private fun collapsingOptionalNulls(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterValues { it != JsonNull }
            .mapValues { collapsingOptionalNulls(it.value) })
        is JsonArray -> JsonArray(value.map(::collapsingOptionalNulls))
        else -> value
    }

    @Test
    fun recordedLivenessFramesDecodeInKotlin() {
        val json = Json { ignoreUnknownKeys = false }
        val ref = Files.readString(Path.of("../spec/current-release.txt")).trim()
        val lines = Files.readAllLines(Path.of("../fixtures/$ref/liveness.jsonl"))
        assertTrue(lines.size >= 8)
        val seen = mutableSetOf<String>()
        for (line in lines) {
            val record = json.parseToJsonElement(line).jsonObject
            val name = record.getValue("name").jsonPrimitive.content
            seen += name
            val frame = record.getValue("frame").jsonObject
            if (record.getValue("kind").jsonPrimitive.content == "server_request") {
                val answerBody = record.getValue("answer")
                when (name) {
                    "clarify" -> {
                        val request = json.decodeFromJsonElement(ClarifyRequestParams.serializer(), frame.getValue("params"))
                        val questions = (request.questions as? Patch.Value)?.value
                            ?: error("Missing recorded clarification")
                        assertEquals("Which release channel?", questions.single().question)
                        val answer = json.decodeFromJsonElement(ClarifyResult.serializer(), answerBody)
                        assertEquals("Stable", answer.answers?.get("q0"))
                        assertEquals(answerBody, json.encodeToJsonElement(ClarifyResult.serializer(), answer))
                    }
                    "approval" -> {
                        val request = json.decodeFromJsonElement(ApprovalRequestParams.serializer(), frame.getValue("params"))
                        assertEquals("rm -rf /tmp/hermes-api-fixture-approval-target", request.command)
                        assertTrue(request.choices?.contains(ApprovalChoice.Deny) == true)
                        val answer = json.decodeFromJsonElement(ApprovalResult.serializer(), answerBody)
                        assertEquals(ApprovalChoice.Deny, answer.choice)
                        assertEquals(answerBody, json.encodeToJsonElement(ApprovalResult.serializer(), answer))
                    }
                    else -> error("Unexpected server request: $name")
                }
                continue
            }
            if (record.getValue("kind").jsonPrimitive.content == "rest") {
                assertEquals(200, frame.getValue("status").jsonPrimitive.content.toInt())
                val body = frame.getValue("body")
                when (name) {
                    "GET /api/audio/voice-live/status" -> {
                        val result = json.decodeFromJsonElement(AudioVoiceLiveStatusResponse.serializer(), body)
                        assertTrue(result.ok && result.mode == "chained")
                        assertEquals(body, json.encodeToJsonElement(AudioVoiceLiveStatusResponse.serializer(), result))
                    }
                    "GET /api/sessions/empty/count" -> {
                        val result = json.decodeFromJsonElement(SessionsEmptyCountResponse.serializer(), body)
                        assertTrue(result.count >= 0)
                        assertEquals(body, json.encodeToJsonElement(SessionsEmptyCountResponse.serializer(), result))
                    }
                    "GET /api/profiles/active" -> {
                        val result = json.decodeFromJsonElement(ProfilesActiveResponse.serializer(), body)
                        assertEquals("default", result.active)
                        assertEquals("default", result.current)
                        assertEquals(body, json.encodeToJsonElement(ProfilesActiveResponse.serializer(), result))
                    }
                    "POST /api/profiles/active" -> {
                        val result = json.decodeFromJsonElement(ProfilesSetActiveResponse.serializer(), body)
                        assertTrue(result.ok)
                        assertEquals("default", result.active)
                        assertEquals(body, json.encodeToJsonElement(ProfilesSetActiveResponse.serializer(), result))
                    }
                    else -> error("Unexpected REST fixture: $name")
                }
                continue
            }
            if (record.getValue("kind").jsonPrimitive.content == "event") {
                val params = frame.getValue("params").jsonObject
                val rawPayload = params["payload"] ?: JsonObject(emptyMap())
                val payload = GatewayEventPayload.decode(name, rawPayload, json)
                assertTrue(payload !is GatewayEventPayload.Unknown, "Unmodelled event: $name")
                if (payload is GatewayEventPayload.GatewayReady) {
                    assertTrue(payload.payload.replayEpoch.isNotEmpty())
                    assertEquals(params.getValue("payload"),
                        json.encodeToJsonElement(GatewayReadyPayload.serializer(), payload.payload))
                }
                if (payload is GatewayEventPayload.MessageComplete) {
                    val reply = assertIs<st.flrn.hermes.api.generated.gateway.MessageCompletePayloadText.StringValue>(
                        payload.payload.text)
                    assertTrue(reply.value in setOf("HermesAPI fixture reply.",
                        "HermesAPI stable release selected.", "HermesAPI approval denied as expected."))
                }
                if (payload is GatewayEventPayload.MessageDelta) {
                    assertTrue(payload.payload.text.trim() in setOf("HermesAPI fixture reply.",
                        "HermesAPI stable release selected.", "HermesAPI approval denied as expected."))
                }
                if (payload is GatewayEventPayload.ToolStart) assertTrue(payload.payload.name in setOf("clarify", "terminal"))
                if (payload is GatewayEventPayload.ToolComplete) {
                    assertTrue(payload.payload.name in setOf("clarify", "terminal"))
                    if (payload.payload.name == "terminal") {
                        val result = assertIs<JsonObject>(payload.payload.result)
                        assertEquals("blocked", result.getValue("status").jsonPrimitive.content)
                        assertEquals("-1", result.getValue("exit_code").jsonPrimitive.content)
                    }
                }
                continue
            }
            when (name) {
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
                "session.create" -> {
                    val result = json.decodeFromJsonElement(SessionCreateResult.serializer(), frame.getValue("result"))
                    assertTrue(result.sessionId.isNotEmpty())
                    val encoded = json.encodeToJsonElement(SessionCreateResult.serializer(), result)
                    assertEquals(collapsingOptionalNulls(frame.getValue("result")), collapsingOptionalNulls(encoded))
                    assertEquals(result, json.decodeFromJsonElement(SessionCreateResult.serializer(), encoded))
                }
                "session.list" -> {
                    val result = json.decodeFromJsonElement(SessionListResult.serializer(), frame.getValue("result"))
                    val encoded = json.encodeToJsonElement(SessionListResult.serializer(), result)
                    assertEquals(result, json.decodeFromJsonElement(SessionListResult.serializer(), encoded))
                }
                "session.close" -> {
                    val result = json.decodeFromJsonElement(SessionCloseResult.serializer(), frame.getValue("result"))
                    assertTrue(result.closed)
                    assertEquals(frame.getValue("result"), json.encodeToJsonElement(SessionCloseResult.serializer(), result))
                }
                "prompt.submit" -> {
                    val result = json.decodeFromJsonElement(PromptSubmitResult.serializer(), frame.getValue("result"))
                    assertTrue(result.status != null)
                    assertEquals(frame.getValue("result"), json.encodeToJsonElement(PromptSubmitResult.serializer(), result))
                }
                else -> error("Unexpected fixture: $name")
            }
        }
        assertTrue(seen.containsAll(setOf("gateway.ready", "ping", "prompt.submit", "clarify", "approval",
            "tool.start", "tool.complete", "message.delta",
            "GET /api/audio/voice-live/status",
            "GET /api/sessions/empty/count",
            "GET /api/profiles/active",
            "POST /api/profiles/active",
            "message.complete", "session.create", "session.list", "session.close")))
    }
}
