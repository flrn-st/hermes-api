package hermes.api.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import hermes.api.runtime.HermesGatewayException

/** JSON-RPC frames as Hermes sends them, for [ScriptedGatewaySocket.inject]. */
public object GatewayFrames {
    /** A notification. Omit [session] and [seq] for app-level events. [payload] is JSON. */
    public fun event(type: String, session: String? = null, seq: Long? = null, payload: String = "{}"): String {
        val params = buildList {
            add(""""type":${quoted(type)}""")
            if (session != null) add(""""session_id":${quoted(session)}""")
            if (seq != null) add(""""seq":$seq""")
            add(""""payload":$payload""")
        }
        return """{"jsonrpc":"2.0","method":"event","params":{${params.joinToString(",")}}}"""
    }

    /** The response to call [id]; [json] is the result. */
    public fun result(id: Int, json: String): String = """{"jsonrpc":"2.0","id":$id,"result":$json}"""

    public fun result(id: Int, value: JsonElement): String = result(id, value.toString())

    /** An error response to call [id], for example `-32000` with a named Hermes error message. */
    public fun error(id: Int, code: Int, message: String, data: JsonElement? = null): String {
        val extra = data?.let { ""","data":$it""" } ?: ""
        return """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":${quoted(message)}$extra}}"""
    }

    /** A request from Hermes to the client, such as `approval` or `clarify`; [params] is JSON. */
    public fun serverRequest(id: String, method: String, params: String): String =
        """{"jsonrpc":"2.0","id":${quoted(id)},"method":${quoted(method)},"params":$params}"""

    private fun quoted(value: String): String = JsonPrimitive(value).toString()
}

private fun parseObject(frame: String, what: String): JsonObject =
    try { Json.parseToJsonElement(frame) as? JsonObject } catch (_: IllegalArgumentException) { null }
        ?: throw HermesGatewayException.Protocol("Frame is not $what")

/** A call the client sent, parsed from a frame on [ScriptedGatewaySocket.sent] with `SentCall(frame)`. */
public data class SentCall(val id: Int, val method: String, val params: JsonElement) {
    public companion object {
        public operator fun invoke(frame: String): SentCall {
            val fields = parseObject(frame, "a JSON-RPC call")
            val id = (fields["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            val method = (fields["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id == null || method == null) throw HermesGatewayException.Protocol("Frame is not a JSON-RPC call")
            return SentCall(id, method, fields["params"] ?: JsonObject(emptyMap()))
        }
    }
}

/** A server request answer the client sent, parsed from a frame on [ScriptedGatewaySocket.sent] with
 *  `SentAnswer(frame)`. */
public data class SentAnswer(val id: String, val result: JsonElement?, val error: JsonElement?) {
    public companion object {
        public operator fun invoke(frame: String): SentAnswer {
            val fields = parseObject(frame, "a server request answer")
            val id = (fields["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw HermesGatewayException.Protocol("Frame is not a server request answer")
            return SentAnswer(id, fields["result"], fields["error"])
        }
    }
}
