package hermes.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import hermes.api.generated.gateway.MessageStartPayload
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.GatewayErrorKind
import hermes.api.generated.gateway.GatewayRefusalReason
import hermes.api.generated.gateway.PingParams
import hermes.api.generated.gateway.RuntimeDescribeParams
import hermes.api.runtime.GatewayRefusal
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.kind
import hermes.api.runtime.refusal
import hermes.api.testing.RecordedCall
import hermes.api.testing.ScriptedGatewayCaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        assertEquals(GatewayEventPayload.MessageStart(MessageStartPayload()), known)
        val raw = JsonObject(mapOf("count" to JsonPrimitive(1)))
        val unknown = GatewayEventPayload.decode("future.event", raw, json)
        assertIs<GatewayEventPayload.Unknown>(unknown)
        assertEquals(raw, unknown.raw)
    }

    @Test
    fun canonicalMethodsCallTheirWireNamesWithStrictTypes() = runTest {
        val described = JsonObject(mapOf(
            "instance_id" to JsonPrimitive("i1"), "profile_id" to JsonPrimitive("/home/p"),
            "authority_epoch" to JsonPrimitive(3),
            "capabilities" to JsonArray(listOf(JsonPrimitive("durable-admission-v1"))),
            "session_create" to JsonObject(mapOf(
                "sources" to JsonArray(listOf(JsonPrimitive("gui"))),
                "parameters" to JsonArray(listOf(JsonPrimitive("cwd"))),
            )),
        ))
        val caller = ScriptedGatewayCaller(mapOf("runtime.describe" to described))
        val result = caller.methods.canonical.runtime.describe(RuntimeDescribeParams())
        assertEquals(listOf("durable-admission-v1"), result.capabilities)
        assertEquals(listOf("gui"), result.sessionCreate.sources)
        assertEquals(listOf(RecordedCall("runtime.describe", JsonObject(emptyMap()))), caller.calls)
    }

    @Test
    fun authorityRefusalsReadTheirReasonAndFields() {
        val missing = HermesGatewayException.RPC(4001, "not_found", JsonObject(mapOf("reason" to JsonPrimitive("not_found"))))
        assertEquals(GatewayRefusal("not_found"), missing.refusal)
        assertEquals(GatewayRefusalReason.NOT_FOUND, missing.refusal?.known)
        // The reviewed reason wins over the legacy reading of code 4001.
        assertEquals(GatewayErrorKind.NOT_FOUND, missing.kind)

        val params = HermesGatewayException.RPC(4001, "invalid_params", JsonObject(mapOf(
            "reason" to JsonPrimitive("invalid_params"),
            "fields" to JsonArray(listOf(JsonPrimitive("cols"), JsonPrimitive("lazy"))),
        )))
        assertEquals(listOf("cols", "lazy"), params.refusal?.fields)
        assertEquals(GatewayErrorKind.INVALID_REQUEST, params.kind)

        val later = HermesGatewayException.RPC(4001, "invalid_params", JsonObject(mapOf(
            "reason" to JsonPrimitive("invalid_params"), "field" to JsonPrimitive("hidden"),
            "detail" to JsonPrimitive("must be a boolean"),
        )))
        assertEquals("hidden", later.refusal?.field)
        assertEquals("must be a boolean", later.refusal?.detail)

        val unknown = HermesGatewayException.RPC(4001, "brand_new", JsonObject(mapOf("reason" to JsonPrimitive("brand_new"))))
        assertEquals("brand_new", unknown.refusal?.reason)
        assertNull(unknown.refusal?.known)
        assertNull(HermesGatewayException.RPC(4009, "session busy", null).refusal)
    }
}
