package hermes.api.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import hermes.api.generated.gateway.CanonicalSnapshot
import hermes.api.generated.gateway.RuntimeDescribeResult

// The session authority's results (`hermes-gateway-v1`, Hermes v0.22.1 and later) are decoded with the generated
// canonical types; the translation itself works on JSON objects, because it must give the raw results back in the
// legacy shapes the app's generated types decode.

/** Decodes canonical results: unknown keys are fine, a missing required one is a protocol error. */
internal val canonicalJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
internal fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
internal fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
internal fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.with(vararg entries: Pair<String, JsonElement?>): JsonObject {
    val copy = LinkedHashMap(this)
    for ((key, value) in entries) if (value == null) copy.remove(key) else copy[key] = value
    return JsonObject(copy)
}

internal fun JsonObject.without(vararg names: String): JsonObject = JsonObject(filterKeys { it !in names })
internal fun JsonObject.only(names: Set<String>): JsonObject = JsonObject(filterKeys { it in names })

internal fun json(value: String?): JsonElement? = value?.let(::JsonPrimitive)
internal fun json(value: Long?): JsonElement? = value?.let(::JsonPrimitive)
internal fun json(value: Boolean?): JsonElement? = value?.let(::JsonPrimitive)
internal fun jsonObject(vararg entries: Pair<String, JsonElement?>): JsonObject =
    JsonObject(entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(LinkedHashMap()))

/** `runtime.describe`: the owner's identity and what `session.create` accepts. */
internal data class RuntimeDescription(
    val instanceId: String?,
    val profileId: String?,
    val authorityEpoch: Long?,
    val capabilities: List<String>,
    val createSources: List<String>,
    val createParameters: List<String>,
) {
    companion object {
        /** `runtime.describe`'s `RuntimeDescribeResult`, or null when Hermes answered something else. */
        fun parse(value: JsonElement): RuntimeDescription? {
            val result = try { canonicalJson.decodeFromJsonElement(RuntimeDescribeResult.serializer(), value) }
            catch (_: IllegalArgumentException) { return null }
            return RuntimeDescription(result.instanceId, result.profileId, result.authorityEpoch, result.capabilities,
                result.sessionCreate.sources, result.sessionCreate.parameters)
        }
    }
}

/** `CanonicalPendingPrompt`, and the payload of an `approval.request` / `clarify.request` event. */
internal data class AuthorityPrompt(
    val promptId: String,
    val sessionId: String,
    val profile: String,
    val kind: String,
    val generation: Long?,
    val payload: JsonObject,
) {
    /** The legacy server request this prompt stands for: `approval` or `clarify`, keyed by the prompt id. */
    val method: String get() = kind

    /** Params in the shape of the legacy `approval` / `clarify` server request. A canonical clarify prompt is one
     *  question, so it is offered both as the single-question form and as a one-entry `questions` batch whose
     *  `qid` is the prompt id (what `clarify.lock` answers with). */
    fun legacyParams(): JsonObject {
        val choices = payload.array("choices") ?: JsonArray(emptyList())
        val names = choices.mapNotNull { (it as? JsonPrimitive)?.content }
        return if (kind == "approval") {
            jsonObject(
                "session_id" to JsonPrimitive(sessionId),
                "request_id" to JsonPrimitive(promptId),
                "command" to payload["command"],
                "description" to payload["description"],
                "choices" to choices,
                "allow_permanent" to JsonPrimitive("always" in names),
                "allow_session" to JsonPrimitive("session" in names),
                "edit" to payload["edit"]?.takeIf { it !is JsonNull },
                "timeout_s" to payload["timeout_s"]?.takeIf { it !is JsonNull },
            )
        } else {
            val question = payload["question"] ?: JsonPrimitive("")
            val multiSelect = payload["multi_select"]?.takeIf { it !is JsonNull }
            jsonObject(
                "session_id" to JsonPrimitive(sessionId),
                "question" to question,
                "choices" to choices,
                "multi_select" to multiSelect,
                "questions" to JsonArray(listOf(jsonObject(
                    "qid" to JsonPrimitive(promptId), "question" to question, "choices" to choices, "multi_select" to multiSelect,
                ))),
            )
        }
    }

    fun openRequest(): JsonObject = jsonObject("id" to JsonPrimitive(promptId), "method" to JsonPrimitive(kind), "params" to legacyParams())

    companion object {
        fun parse(payload: JsonObject, sessionId: String, profile: String, kind: String? = null, generation: Long? = null): AuthorityPrompt? {
            val id = payload.string("prompt_id") ?: return null
            val resolvedKind = kind ?: payload.string("kind") ?: return null
            if (resolvedKind != "approval" && resolvedKind != "clarify") return null
            return AuthorityPrompt(id, sessionId, profile, resolvedKind, payload.long("execution_generation") ?: generation, payload)
        }
    }
}

/** The fields of a `CanonicalSnapshot` the client keeps. */
internal data class AuthoritySnapshot(
    val sessionId: String,
    val storedSessionId: String?,
    val revision: Long?,
    val generation: Long?,
    val replayEpoch: String?,
    val lastSequence: Long?,
    val subscriptionId: String?,
    val running: Boolean?,
    val prompts: List<JsonObject>,
    val raw: JsonObject,
) {
    val messages: List<JsonObject> get() = raw.array("messages")?.mapNotNull { it as? JsonObject }.orEmpty()

    companion object {
        /** A `CanonicalSnapshot`, keeping its raw fields for the legacy projection; null when it is not one. */
        fun parse(value: JsonElement): AuthoritySnapshot? {
            val fields = value as? JsonObject ?: return null
            val snapshot = try { canonicalJson.decodeFromJsonElement(CanonicalSnapshot.serializer(), fields) }
            catch (_: IllegalArgumentException) { return null }
            return AuthoritySnapshot(snapshot.sessionId, snapshot.storedSessionId, snapshot.revision,
                snapshot.executionGeneration, snapshot.replayEpoch, snapshot.lastSequence, snapshot.subscriptionId,
                snapshot.running, fields.array("prompts")?.mapNotNull { it as? JsonObject }.orEmpty(), fields)
        }
    }
}

/** Projections of canonical results onto the legacy shapes the generated types decode. */
internal object LegacyShapes {
    /** A snapshot as `SessionResumeResult` / `SessionActivateResult` / `SessionCreateResult`: the stored identity
     *  under its legacy names, the turn state inside `info`, and open prompts as `open_requests`. */
    fun snapshot(snapshot: AuthoritySnapshot, prompts: List<AuthorityPrompt>): JsonObject {
        val raw = snapshot.raw
        val stored = snapshot.storedSessionId ?: snapshot.sessionId
        val info = (raw.obj("info") ?: JsonObject(emptyMap())).with(
            "running" to json(snapshot.running),
            "stored_session_id" to JsonPrimitive(stored),
        )
        val approval = prompts.firstOrNull { it.kind == "approval" }?.let { prompt ->
            prompt.legacyParams().without("session_id", "edit", "timeout_s")
        }
        return raw.with(
            "info" to info,
            "stored_session_id" to JsonPrimitive(stored),
            "session_key" to JsonPrimitive(stored),
            "resumed" to JsonPrimitive(stored),
            "messages" to (raw["messages"] ?: JsonArray(emptyList())),
            "message_count" to (raw["message_count"] ?: JsonPrimitive(snapshot.messages.size)),
            "messages_omitted" to JsonPrimitive(false),
            "open_requests" to JsonArray(prompts.map { it.openRequest() }),
            "pending_approval" to approval,
        )
    }

    /** `session.stats` (and the `session.usage` event) in the legacy usage shape. */
    fun usage(stats: JsonObject): JsonObject {
        val used = stats.long("context_tokens")
        val max = stats.long("context_length") ?: stats.long("config_context_length")
        val percent = if (used != null && max != null && max > 0) (used * 100 + max / 2) / max else null
        val cacheRead = stats.long("cache_read_tokens")
        val prompt = stats.long("prompt_tokens")
        val hit = if (cacheRead != null && prompt != null && prompt > 0) (cacheRead * 100 + prompt / 2) / prompt else null
        return stats.with(
            "model" to stats["model"],
            "input" to json(stats.long("input_tokens")),
            "output" to json(stats.long("output_tokens")),
            "reasoning" to json(stats.long("reasoning_tokens")),
            "prompt" to json(prompt),
            "completion" to json(stats.long("completion_tokens")),
            "total" to json(stats.long("total_tokens")),
            "calls" to json(stats.long("api_calls")),
            "compressions" to json(stats.long("compression_count")),
            "context_used" to json(used),
            "context_max" to json(max),
            "context_percent" to json(percent),
            "cache_read" to json(cacheRead),
            "cache_write" to json(stats.long("cache_write_tokens")),
            "cache_hit_pct" to json(hit),
            "avg_tps" to (stats["tokens_per_s"]?.takeIf { it !is JsonNull }),
            "active_subagents" to json(stats.long("active_subagents")),
        )
    }

    /** `AdmissionStatus` as `PromptSubmitResult`: `started` is the legacy `streaming`. */
    fun admission(value: JsonObject, sessionId: String, submissionId: String): JsonObject {
        val status = when (val raw = value.string("status")) { "started" -> "streaming"; null -> null; else -> raw }
        return value.with("status" to json(status), "session_id" to JsonPrimitive(sessionId),
            "submission_id" to JsonPrimitive(submissionId))
    }
}
