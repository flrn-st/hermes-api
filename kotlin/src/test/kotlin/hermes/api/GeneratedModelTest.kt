package hermes.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import hermes.api.generated.gateway.ApprovalChoice
import hermes.api.generated.gateway.BrowserControllerResultParams
import hermes.api.generated.gateway.ChangeSignalPayload
import hermes.api.generated.gateway.ConnectorPolicyEffectiveAllow
import hermes.api.generated.gateway.GoalSnapshotWaitBarrier
import hermes.api.generated.gateway.SessionMostRecentResult
import hermes.api.generated.gateway.WaitBarrierTargetTarget
import hermes.api.runtime.Patch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeneratedModelTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    @Test
    fun optionalNullableParamsPreserveAbsentNullAndValue() {
        val absent = json.decodeFromString<BrowserControllerResultParams>(
            """{"session_id":"s","command_id":"c"}"""
        )
        assertEquals(Patch.Absent, absent.ok)

        val explicitNull = json.decodeFromString<BrowserControllerResultParams>(
            """{"session_id":"s","command_id":"c","ok":null}"""
        )
        assertEquals(Patch.Null, explicitNull.ok)
        assertEquals(JsonNull, json.parseToJsonElement(json.encodeToString(explicitNull)).jsonObject["ok"])

        val value = json.decodeFromString<BrowserControllerResultParams>(
            """{"session_id":"s","command_id":"c","ok":true}"""
        )
        assertIs<Patch.Value<*>>(value.ok)
        assertEquals("true", value.ok.value.toString())
    }

    @Test
    fun requiredNullableFieldRequiresKey() {
        val result = json.decodeFromString<SessionMostRecentResult>("""{"session_id":null}""")
        assertEquals(null, result.sessionId)
        assertTrue(json.parseToJsonElement(json.encodeToString(result)).jsonObject.containsKey("session_id"))
        assertFailsWith<SerializationException> {
            json.decodeFromString<SessionMostRecentResult>("{}")
        }
    }

    @Test
    fun openObjectKeepsAdditionalFields() {
        val decoded = json.decodeFromString<ChangeSignalPayload>("""{"future":{"nested":3}}""")
        assertEquals("3", decoded.additionalProperties.getValue("future").jsonObject.getValue("nested").jsonPrimitive.content)
        assertEquals(decoded.additionalProperties, json.parseToJsonElement(json.encodeToString(decoded)).jsonObject)
    }

    @Test
    fun taggedUnionAndOpenEnumDecode() {
        val barrier = json.decodeFromString<GoalSnapshotWaitBarrier>("""{"type":"pid","target":123}""")
        assertIs<GoalSnapshotWaitBarrier.WaitBarrierTargetValue>(barrier)
        assertIs<WaitBarrierTargetTarget.LongValue>(barrier.value.target)
        val choice = json.decodeFromString<ApprovalChoice>("\"future\"")
        assertEquals(ApprovalChoice.Unknown("future"), choice)
    }

    @Test
    fun integerConstantsDecodeOnlyTheirValue() {
        fun policy(version: Int) =
            """{"version":$version,"revision":"r","issued_at_ms":1,"mode":"allow","connectors":[],"tools":{}}"""
        assertEquals(1L, Json.decodeFromString(ConnectorPolicyEffectiveAllow.serializer(), policy(1)).version)
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(ConnectorPolicyEffectiveAllow.serializer(), policy(2))
        }
    }
}
