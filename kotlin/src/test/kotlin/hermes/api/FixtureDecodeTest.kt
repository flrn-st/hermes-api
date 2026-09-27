package hermes.api

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import hermes.api.generated.gateway.ClientCapabilitiesResult
import hermes.api.generated.gateway.ClarifyRequestParams
import hermes.api.generated.gateway.ClarifyResult
import hermes.api.generated.gateway.ApprovalChoice
import hermes.api.generated.gateway.ApprovalRequestParams
import hermes.api.generated.gateway.ApprovalResult
import hermes.api.generated.gateway.GatewayCapabilitiesResult
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.GatewayReadyPayload
import hermes.api.generated.gateway.PingResult
import hermes.api.generated.gateway.PromptSubmitResult
import hermes.api.generated.gateway.SessionCreateResult
import hermes.api.generated.gateway.SessionListResult
import hermes.api.generated.gateway.SessionCloseResult
import hermes.api.runtime.Patch
import hermes.api.live.generated.GatewayOperations
import hermes.api.live.generated.RESTOperations
import hermes.api.runtime.RESTResponse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixtureDecodeTest {
    /** Optional nulls collapsed and numbers compared by value, so `1700000000` equals a re-encoded `1.7E9`. */
    private fun normalized(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterValues { it != JsonNull }.mapValues { normalized(it.value) })
        is JsonArray -> JsonArray(value.map(::normalized))
        is JsonPrimitive -> if (value.isString) value else value.content.toBigDecimalOrNull()
            ?.let { JsonPrimitive(it.stripTrailingZeros().toPlainString()) } ?: value
    }

    private fun collapsingOptionalNulls(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterValues { it != JsonNull }
            .mapValues { collapsingOptionalNulls(it.value) })
        is JsonArray -> JsonArray(value.map(::collapsingOptionalNulls))
        else -> value
    }

    private companion object {
        /** Every reply and tool the stub model answers the recorded scenarios with (`harness/stub_llm.py`). */
        val FIXTURE_REPLIES = setOf("HermesAPI fixture reply.", "HermesAPI stable release selected.",
            "HermesAPI approval denied as expected.", "HermesAPI reasoning complete.", "HermesAPI subagent finished.")
        val FIXTURE_TOOLS = setOf("clarify", "terminal", "delegate_task")
    }

    @Test
    fun recordedLivenessFramesDecodeInKotlin() {
        val json = Json { ignoreUnknownKeys = false }
        // The gateway runtime tolerates keys a model omits (Hermes adds fields between releases, and some
        // are always null); the round trip below still fails when a value is dropped.
        val gatewayJson = Json { ignoreUnknownKeys = true; explicitNulls = true }
        val ref = Files.readString(Path.of("../spec/current-release.txt")).trim()
        val lines = Files.readAllLines(Path.of("../fixtures/$ref/liveness.jsonl"))
        assertTrue(lines.size >= 8)
        val seen = mutableSetOf<String>()
        val restSeen = mutableSetOf<String>()
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
                // Every recorded operation decodes through its generated model and re-encodes to the same JSON.
                val status = frame.getValue("status").jsonPrimitive.int
                val headers = buildMap {
                    frame["media"]?.jsonPrimitive?.contentOrNull?.let { put("content-type", it) }
                    frame["location"]?.jsonPrimitive?.contentOrNull?.let { put("location", it) }
                }
                val jsonBody = frame["body"]
                val text = frame["text"]?.jsonPrimitive?.contentOrNull
                val bytes = jsonBody?.toString()?.encodeToByteArray() ?: text?.encodeToByteArray() ?: ByteArray(0)
                assertTrue(name in RESTOperations.all, "Unknown REST operation $name")
                val decoded = RESTOperations.decode(name, RESTResponse(status, headers, bytes), json)
                if (jsonBody != null) {
                    val expected = normalized(jsonBody)
                    val actual = normalized(decoded)
                    assertTrue(actual == expected ||
                        actual == JsonObject(mapOf("status" to JsonPrimitive(status), "value" to expected)),
                        "$name does not round-trip")
                } else if (text != null) {
                    assertTrue(decoded == JsonPrimitive(text) ||
                        decoded == JsonObject(mapOf("status" to JsonPrimitive(status), "value" to JsonPrimitive(text))))
                }
                restSeen += name
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
                    val reply = assertIs<hermes.api.generated.gateway.MessageCompletePayloadText.StringValue>(
                        payload.payload.text)
                    assertTrue(reply.value in FIXTURE_REPLIES)
                }
                if (payload is GatewayEventPayload.MessageDelta) {
                    assertTrue(payload.payload.text.trim() in FIXTURE_REPLIES)
                }
                if (payload is GatewayEventPayload.ToolStart) assertTrue(payload.payload.name in FIXTURE_TOOLS)
                if (payload is GatewayEventPayload.ToolComplete) {
                    assertTrue(payload.payload.name in FIXTURE_TOOLS)
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
                else -> {
                    // Every other recorded method decodes through its generated result type, with the gateway
                    // runtime's settings, and re-encodes to the same JSON: nothing Hermes sent is lost.
                    assertTrue(name in GatewayOperations.all, "Unknown gateway method $name")
                    val result = frame.getValue("result")
                    assertEquals(normalized(result), normalized(GatewayOperations.decodeResult(name, result, gatewayJson)),
                        "$name does not round-trip")
                }
            }
        }
        assertTrue(seen.containsAll(setOf("gateway.ready", "ping", "prompt.submit", "clarify", "approval",
            "tool.start", "tool.complete", "message.delta",
            "message.complete", "session.create", "session.list", "session.close")))
        assertTrue(restSeen.size >= 4)
    }
}
