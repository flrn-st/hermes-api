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

    /** The first frame of a socket. [sessionAuthority] marks the Hermes session authority (v0.22.1 and later). */
    public fun gatewayReady(sessionAuthority: Boolean = false, replayEpoch: String = "process-epoch"): String {
        val authority = if (sessionAuthority) ""","session_authority":true""" else ""
        return """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":""" +
            """{"skin":{},"change_events":true,"heartbeat":true,"replay_epoch":${quoted(replayEpoch)}$authority}}}"""
    }

    /** A session authority event: numbered by [seq] within the session's own [replayEpoch], and stamped with
     *  the turn's [generation] when it belongs to one. */
    public fun authorityEvent(type: String, session: String, seq: Long, replayEpoch: String, payload: String = "{}",
                              generation: Long? = null): String {
        val stamp = generation?.let { ""","execution_generation":$it""" } ?: ""
        return """{"jsonrpc":"2.0","method":"event","params":{"type":${quoted(type)},"session_id":${quoted(session)},""" +
            """"payload":$payload,"replay_epoch":${quoted(replayEpoch)},"seq":$seq$stamp}}"""
    }

    /** A `CanonicalSnapshot` as `session.create` and `session.resume` answer on the session authority. */
    public fun authoritySnapshot(session: String, revision: Long = 0, generation: Long = 0, replayEpoch: String = "epoch-1",
                                 lastSequence: Long = 0, subscription: String = "subscription-1", prompts: String = "[]",
                                 messages: String = "[]", running: Boolean = false): String =
        """{"session_id":${quoted(session)},"stored_session_id":${quoted(session)},"messages":$messages,""" +
            """"message_count":0,"running":$running,"authority_epoch":1,"replay_epoch":${quoted(replayEpoch)},""" +
            """"last_sequence":$lastSequence,"subscription_id":${quoted(subscription)},"revision":$revision,""" +
            """"execution_generation":$generation,"pending":[],"prompts":$prompts,""" +
            """"info":{"desktop_protocol":"hermes-gateway-v1","source":"gui","model":"test-model","profile_name":"default"}}"""

    /** `runtime.describe` of a Hermes v0.22.1 session authority. */
    public const val RUNTIME_DESCRIPTION: String = """{"instance_id":"instance-1","profile_id":"/home/hermes","authority_epoch":1,""" +
        """"capabilities":["durable-admission-v1","event-replay-v1"],"session_create":{"sources":["acp","cli","gui","oneshot","tool","tui"],""" +
        """"parameters":["accept_hooks","api_key","base_url","checkpoints","cwd","cwd_explicit","editor","follow_profile_config",""" +
        """"ignore_rules","ignore_user_config","max_turns","model","pass_session_id","provider","reasoning","request_id","row_cwd",""" +
        """"run_budget","safe_mode","service_tier","skills","source","title","toolsets","verbose","yolo"]},"skin":null}"""

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
