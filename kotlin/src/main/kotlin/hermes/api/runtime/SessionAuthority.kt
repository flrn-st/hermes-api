package hermes.api.runtime

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import hermes.api.generated.gateway.AdmissionStatus
import hermes.api.generated.gateway.SessionMutateParams

/** One JSON-RPC exchange on the current socket, as the session authority receives it. */
internal fun interface AuthorityWire {
    suspend fun call(method: String, params: JsonObject): JsonElement
}

/**
 * Speaks the Hermes session authority (`hermes-gateway-v1`) for apps written against the legacy gateway methods.
 *
 * It mirrors Hermes Desktop's adapter (`apps/desktop/src/api/canonical-protocol.ts` and `client.ts` in Hermes
 * v0.22.1): legacy calls are translated into the authority's closed contracts, results are projected back onto the
 * shapes the generated types decode, and the per-session state the authority fences on (revision, execution
 * generation, subscription, open prompts) is tracked from snapshots, results and events.
 *
 * Not thread-safe: the gateway calls it on its confined dispatcher only. Its state outlives a connection, so a
 * mutation retried after a lost reply keeps its request id; [connectionOpened] drops only what is per socket.
 */
internal class SessionAuthority(
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** The replay epoch the client holds for a session, sent with `session.events.since`. */
    private val epochOf: (String) -> String? = { null },
    /** The last sequence the client delivered for a session, the default `last_sequence` of a replay. */
    private val sequenceOf: (String) -> Long? = { null },
    /** Called with every snapshot adopted, unless the caller replays from its own watermark; `true` when the app
     *  asked for the snapshot (create, resume), `false` when the client attached the session for a call. */
    private val onSnapshot: (AuthoritySnapshot, Boolean) -> Unit = { _, _ -> },
) {
    internal data class Owner(val profile: String, val sessionId: String)

    private class SessionState {
        var revision: Long? = null
        var generation: Long? = null
        var subscriptionId: String? = null
        var attachedOn: Int = -1
    }

    private class Retained(val owner: Owner, val operation: String, val request: JsonObject)
    private class Confirmation(val token: String, var sent: Boolean)

    /** `runtime.describe` of the current connection, when Hermes answered it. */
    var description: RuntimeDescription? = null
        private set
    private var connection = 0
    private val states = HashMap<Owner, SessionState>()
    /** The profile a session was last reached with, as the app named it (null: the socket's own). */
    private val routes = HashMap<String, String?>()
    private val mutations = LinkedHashMap<String, Retained>()
    private val creates = HashMap<String, String>()
    private val prompts = LinkedHashMap<String, AuthorityPrompt>()
    private val answered = HashSet<String>()
    private val confirmations = HashMap<String, Confirmation>()
    /** Intents whose rewind already committed, so a retried edit submits without cutting twice. */
    private val rewound = HashSet<String>()

    fun connectionOpened(description: RuntimeDescription?) {
        connection++
        this.description = description
        for (state in states.values) {
            state.subscriptionId = null
            state.attachedOn = -1
        }
    }

    fun intentFinished(intent: String) {
        rewound.remove(intent)
    }

    fun prompt(id: String): AuthorityPrompt? = prompts[id]

    fun isAnswered(id: String): Boolean = id in answered

    fun forget(sessionId: String) {
        states.keys.removeAll { it.sessionId == sessionId }
        prompts.values.removeAll { it.sessionId == sessionId }
    }

    private fun canonicalProfile(profile: String?): String = profile?.takeIf { it.isNotEmpty() } ?: "default"

    private fun owner(sessionId: String, profile: String? = routes[sessionId]): Owner = Owner(canonicalProfile(profile), sessionId)

    private fun state(owner: Owner): SessionState = states.getOrPut(owner) { SessionState() }

    private fun route(sessionId: String, profile: String?) {
        if (profile != null || sessionId !in routes) routes[sessionId] = profile
    }

    private fun adoptGeneration(owner: Owner, generation: Long?): Boolean {
        if (generation == null) return true
        val state = state(owner)
        if (generation < (state.generation ?: -1)) return false
        state.generation = generation
        return true
    }

    // Snapshots and events

    /** Adopts a `CanonicalSnapshot`: subscription on this socket, revision, generation and the open prompts. */
    fun adoptSnapshot(snapshot: AuthoritySnapshot, profile: String?): List<AuthorityPrompt> {
        route(snapshot.sessionId, profile)
        val owner = owner(snapshot.sessionId)
        val state = state(owner)
        snapshot.revision?.let { state.revision = it }
        adoptGeneration(owner, snapshot.generation)
        state.subscriptionId = snapshot.subscriptionId
        state.attachedOn = connection
        val open = snapshot.prompts.mapNotNull { AuthorityPrompt.parse(it, snapshot.sessionId, owner.profile, generation = snapshot.generation) }
        val ids = open.map { it.promptId }.toSet()
        prompts.values.removeAll { it.sessionId == snapshot.sessionId && it.promptId !in ids }
        open.forEach { prompts[it.promptId] = it }
        return open
    }

    data class EventOutcome(val deliver: AuthorityPrompt? = null, val settled: AuthorityPrompt? = null, val settledId: String? = null)

    /** Bookkeeping for one session event: generation, revision and prompts. A prompt to put to the app's
     *  server request handler, or one that settled, is returned. */
    fun event(type: String, sessionId: String, frameGeneration: Long?, payload: JsonObject): EventOutcome {
        val owner = owner(sessionId)
        val generation = frameGeneration ?: payload.long("execution_generation")
        if (!adoptGeneration(owner, generation)) return EventOutcome()
        payload.long("revision")?.let { revision ->
            val state = state(owner)
            if (type == "session.info") state.revision = revision
            if (type == "session.updated" && (payload.string("session_id") ?: sessionId) == sessionId &&
                revision > (state.revision ?: -1)) {
                state.revision = revision
                retireSuperseded(owner, revision)
            }
        }
        val promptId = payload.string("prompt_id") ?: return EventOutcome()
        if (type.endsWith(".settled")) {
            answered.remove(promptId)
            return EventOutcome(settled = prompts.remove(promptId), settledId = promptId)
        }
        val kind = when (type) { "approval.request" -> "approval"; "clarify.request" -> "clarify"; else -> return EventOutcome() }
        val prompt = AuthorityPrompt.parse(payload, sessionId, owner.profile, kind, generation) ?: return EventOutcome()
        val known = prompts.containsKey(promptId)
        prompts[promptId] = prompt
        return if (known) EventOutcome() else EventOutcome(deliver = prompt)
    }

    // Calls

    /** Translates one legacy call. [intent] identifies the app's call across the gateway's retries. */
    suspend fun call(method: String, params: JsonObject, wire: AuthorityWire, intent: String): JsonElement {
        val profile = params.string("profile")
        params.string("session_id")?.let { route(it, profile) }
        return when (method) {
            "session.create" ->
                if (params.containsKey("parent_session_id") || params.containsKey("messages")) fork(params, wire)
                else create(params, wire)
            "session.resume" -> resume(params, wire)
            "session.activate" -> resume(params.only(setOf("session_id", "profile", "cols", "omit_messages"))
                .let { if (it.containsKey("profile") || routes[it.string("session_id")] == null) it else it.with("profile" to json(routes[it.string("session_id")])) }, wire)
            "session.close" -> close(params, wire)
            "session.title" -> {
                val sessionId = required(params, "session_id")
                val title = params["title"] ?: JsonPrimitive("")
                val receipt = mutate(sessionId, profile, "rename", jsonObject("title" to title), false, wire)
                jsonObject("title" to (receipt["title"] ?: title), "session_key" to JsonPrimitive(sessionId))
            }
            "session.cwd.set" -> {
                val cwd = params["cwd"] ?: JsonPrimitive("")
                val receipt = mutate(required(params, "session_id"), profile, "workspace", jsonObject("cwd" to cwd), true, wire)
                receipt.without("session_id", "operation", "revision", "execution_generation").with("cwd" to (receipt["cwd"] ?: cwd))
            }
            "session.workspace.move" -> {
                val cwd = params["cwd"] ?: JsonPrimitive("")
                val receipt = mutate(required(params, "session_key"), profile, "workspace", jsonObject("cwd" to cwd), true, wire)
                jsonObject("cwd" to (receipt["cwd"] ?: cwd), "branch" to receipt["branch"])
            }
            "session.compress" -> compress(params, wire)
            "session.undo" -> undo(params, wire)
            "session.branch" -> branch(params, wire)
            "session.usage" -> usage(params, wire)
            "session.interrupt", "session.redirect", "session.steer" -> turnControl(method, params, wire)
            "session.events.since" -> eventsSince(params, wire)
            "session.list" -> {
                var prepared = params.only(setOf("profile", "scope", "limit", "title", "include_hidden"))
                // The legacy list is the stored history; the authority's default scope lists only live sessions.
                if (!params.containsKey("title") && !params.containsKey("scope")) prepared = prepared.with("scope" to JsonPrimitive("stored"))
                params.long("limit")?.let { prepared = prepared.with("limit" to JsonPrimitive(it.coerceIn(1, 200))) }
                wire.call(method, prepared)
            }
            "prompt.submit" -> submit(params, wire, intent)
            "approval.respond" -> {
                val id = params.string("request_id") ?: params.string("prompt_id") ?: return wire.call(method, params)
                val prompt = findPrompt(id, params.string("session_id"), profile, wire)
                val result = respond(prompt, "choice", params["choice"] ?: JsonPrimitive("deny"), wire)
                result.with("resolved" to JsonPrimitive(if (result.string("status") == "resolved") 1 else 0))
            }
            "clarify.respond" -> {
                val id = params.string("prompt_id") ?: params.string("request_id") ?: return wire.call(method, params)
                respond(findPrompt(id, params.string("session_id"), profile, wire), "answer", params["answer"] ?: JsonPrimitive(""), wire)
            }
            "clarify.lock" -> {
                val id = params.string("request_id")
                val prompt = id?.let { prompts[it] } ?: params.string("question_id")?.let { prompts[it] }
                if (prompt == null || prompt.kind != "clarify") return wire.call(method, params)
                val result = respond(prompt, "answer", JsonPrimitive(answerText(params["answer"])), wire)
                jsonObject("status" to JsonPrimitive(if (result.string("status") == "already_resolved") "expired" else "ok"),
                    "remaining" to JsonArray(emptyList()))
            }
            "request.answer" -> {
                val prompt = params.string("id")?.let { prompts[it] } ?: return wire.call(method, params)
                val result = params.obj("result") ?: JsonObject(emptyMap())
                val answer = if (prompt.kind == "approval") respond(prompt, "choice", result["choice"] ?: JsonPrimitive("deny"), wire)
                    else respond(prompt, "answer", JsonPrimitive(clarifyAnswer(prompt.promptId, result)), wire)
                jsonObject("status" to JsonPrimitive(if (answer.string("status") == "already_resolved") "expired" else "ok"))
            }
            "config.set" -> configSet(params, wire)
            "commands.catalog" -> wire.call(method, params.without("session_id"))
            "reload.mcp" -> wire.call(method, if (params.containsKey("confirm")) params else params.with("confirm" to JsonPrimitive(true)))
            "command.dispatch" -> {
                val name = params.string("name").orEmpty()
                val argument = params.string("arg").orEmpty()
                slash(method, "$name $argument", params, wire) ?: wire.call(method, params)
            }
            "slash.exec" -> slash(method, params.string("command").orEmpty(), params, wire) ?: wire.call(method, params)
            "pdf.attach", "image.detach" ->
                throw unsupportedOnAuthority(method, "the session authority does not serve it; send files with file.attach")
            "image.attach_bytes" -> {
                params.string("session_id")?.let { ensureState(it, profile, wire, subscription = true) }
                wire.call(method, params)
            }
            else -> wire.call(method, params)
        }
    }

    private fun required(params: JsonObject, name: String): String =
        params.string(name) ?: throw HermesGatewayException.Protocol("Missing $name")

    // Attachment

    /** Resumes the session on this socket: its subscription, revision, generation and open prompts. */
    suspend fun attach(sessionId: String, profile: String?, wire: AuthorityWire, adoptWatermark: Boolean = true): AuthoritySnapshot =
        snapshot(sessionId, profile, wire, messages = false, adoptWatermark = adoptWatermark)

    private suspend fun snapshot(sessionId: String, profile: String?, wire: AuthorityWire, messages: Boolean,
                                 adoptWatermark: Boolean = true): AuthoritySnapshot {
        val params = jsonObject("session_id" to JsonPrimitive(sessionId), "profile" to json(profile),
            "omit_messages" to JsonPrimitive(!messages), "defer_history" to JsonPrimitive(!messages))
        val result = wire.call("session.resume", params)
        val snapshot = AuthoritySnapshot.parse(result) ?: throw HermesGatewayException.Protocol("session.resume answered without a snapshot")
        adoptSnapshot(snapshot, profile)
        if (adoptWatermark) onSnapshot(snapshot, false)
        return snapshot
    }

    private suspend fun ensureState(sessionId: String, profile: String?, wire: AuthorityWire, subscription: Boolean = false,
                                    revision: Boolean = false, generation: Boolean = false): SessionState {
        fun missing(state: SessionState?) = state == null || (subscription && state.attachedOn != connection) ||
            (revision && state.revision == null) || (generation && state.generation == null)
        if (missing(states[owner(sessionId, profile ?: routes[sessionId])])) attach(sessionId, profile, wire)
        val state = states[owner(sessionId, profile ?: routes[sessionId])]
        if (state == null || (revision && state.revision == null) || (generation && state.generation == null)) {
            throw unsupportedOnAuthority("session.mutate",
                "Hermes reported no ${if (state?.revision == null) "revision" else "execution generation"} for the session")
        }
        return state
    }

    // Lifecycle

    private fun prepareCreate(params: JsonObject): JsonObject {
        val effort = params.string("reasoning_effort")?.takeIf { it.isNotEmpty() }
        val tier = params.string("service_tier")?.takeIf { it.isNotEmpty() }
            ?: when (params.bool("fast")) { true -> "priority"; false -> "normal"; null -> null }
        var body = params.without("reasoning_effort", "fast", "service_tier", "cols", "close_on_disconnect", "source",
            "request_id", "messages", "parent_session_id", "profile")
        if (effort != null) body = body.with("reasoning" to JsonPrimitive(effort))
        if (tier != null) body = body.with("service_tier" to JsonPrimitive(tier))
        if (params.bool("cwd_explicit") == null) body = body.without("cwd_explicit")
        // `runtime.describe` lists the launch parameters but not every contract key (v0.22.1 omits `hidden`),
        // so Hermes Desktop's own list stays allowed beside it.
        val allowed = STATIC_CREATE_PARAMETERS + description?.createParameters.orEmpty()
        body = JsonObject(body.filterKeys { it in allowed && it != "source" && it != "request_id" })
        val sibling = params.string("profile")?.takeIf { it.isNotEmpty() && it != "default" }
        if (sibling != null) body = body.with("profile" to JsonPrimitive(sibling))
        val key = body.toString()
        val requestId = params.string("request_id") ?: creates[key] ?: newId()
        creates[key] = requestId
        return body.with("request_id" to JsonPrimitive(requestId), "source" to JsonPrimitive("gui"))
    }

    private suspend fun create(params: JsonObject, wire: AuthorityWire): JsonObject {
        val prepared = prepareCreate(params)
        val result = wire.call("session.create", prepared)
        val requestId = prepared.string("request_id")
        creates.values.removeAll { it == requestId }
        return adopted(result, params.string("profile"))
    }

    private suspend fun resume(params: JsonObject, wire: AuthorityWire): JsonObject {
        var prepared = params.only(RESUME_PARAMETERS)
        // Hermes Desktop: a lazy attach skipped the transcript prefetch, so it takes the transcript.
        if (params.containsKey("lazy")) prepared = prepared.with("omit_messages" to JsonPrimitive(false))
        return adopted(wire.call("session.resume", prepared), params.string("profile"))
    }

    private fun adopted(result: JsonElement, profile: String?): JsonObject {
        val snapshot = AuthoritySnapshot.parse(result) ?: throw HermesGatewayException.Protocol("Expected a session snapshot")
        val open = adoptSnapshot(snapshot, profile)
        onSnapshot(snapshot, true)
        return LegacyShapes.snapshot(snapshot, open)
    }

    private suspend fun close(params: JsonObject, wire: AuthorityWire): JsonObject {
        val sessionId = required(params, "session_id")
        val profile = params.string("profile") ?: routes[sessionId]
        val state = states[owner(sessionId, profile)]
        val subscription = state?.subscriptionId?.takeIf { state.attachedOn == connection }
        forget(sessionId)
        if (subscription == null) return jsonObject("closed" to JsonPrimitive(false))
        val result = wire.call("session.detach", jsonObject("session_id" to JsonPrimitive(sessionId),
            "subscription_id" to JsonPrimitive(subscription), "profile" to json(profile))) as? JsonObject
        return jsonObject("closed" to JsonPrimitive(result?.bool("detached") ?: false))
    }

    /** A fork seeded with the parent's first messages is the authority's `branch` mutation on the parent, kept
     *  through the parent's row that ends the seed. The child is then attached like a created session. */
    private suspend fun fork(params: JsonObject, wire: AuthorityWire): JsonObject {
        val seeds = params.array("messages")?.mapNotNull { it as? JsonObject }.orEmpty()
        val parent = params.string("parent_session_id") ?: throw unsupportedOnAuthority("session.create",
            "a seeded session needs parent_session_id: the authority only branches a stored session")
        val profile = params.string("profile")
        val parentSnapshot = snapshot(parent, profile, wire, messages = true)
        val through = if (seeds.isEmpty()) null else branchBoundary(seeds, parentSnapshot.messages)
        val payload = jsonObject("title" to params.string("title")?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive),
            "through_message_id" to json(through))
        val receipt = mutate(parentSnapshot.sessionId, profile, "branch", payload, true, wire)
        val child = receipt.string("branched_session_id") ?: throw HermesGatewayException.Protocol("Branch receipt has no branched_session_id")
        return resume(jsonObject("session_id" to JsonPrimitive(child), "profile" to json(profile)), wire)
    }

    private suspend fun branch(params: JsonObject, wire: AuthorityWire): JsonObject {
        if (params.containsKey("count")) throw unsupportedOnAuthority("session.branch",
            "the authority branches through a stored row, not a message count")
        val sessionId = required(params, "session_id")
        val profile = params.string("profile")
        val title = params.string("name")?.takeIf { it.isNotEmpty() }
        val receipt = mutate(sessionId, profile, "branch", jsonObject("title" to json(title)), true, wire)
        val child = receipt.string("branched_session_id") ?: throw HermesGatewayException.Protocol("Branch receipt has no branched_session_id")
        val snapshot = resume(jsonObject("session_id" to JsonPrimitive(child), "profile" to json(profile)), wire)
        return snapshot.with("parent" to JsonPrimitive(sessionId),
            "title" to JsonPrimitive(snapshot.obj("info")?.string("title") ?: title ?: ""))
    }

    // Turns

    private suspend fun submit(params: JsonObject, wire: AuthorityWire, intent: String): JsonElement {
        val sessionId = params.string("session_id") ?: return wire.call("prompt.submit", params)
        val profile = params.string("profile")
        if (TRUNCATE_PARAMETERS.any { it in params }) {
            val row = params.long("truncate_before_row_id")?.takeIf { it > 0 }
            if (row != null) {
                if (intent !in rewound) {
                    mutate(sessionId, profile, "rewind", jsonObject("target_message_id" to JsonPrimitive(row)), true, wire)
                    rewound += intent
                }
            } else if (TRUNCATE_TARGETS.any { it in params }) {
                throw unsupportedOnAuthority("prompt.submit", "this message is not saved yet; wait for it to settle, then edit it again")
            }
        }
        // The authority knows two surfaces and reads a voice context only for a live-voice turn; anything else
        // is refused, so it is left out as a legacy gateway ignored it.
        val surface = params.string("surface")
        val submit = JsonObject(params.filterKeys { key ->
            key in SUBMIT_PARAMETERS && when (key) {
                "surface" -> surface in SUBMIT_SURFACES
                "voice_context" -> surface == "voice-live"
                else -> true
            }
        })
        ensureState(sessionId, profile, wire, subscription = true)
        retireMutations(owner(sessionId, profile ?: routes[sessionId]), null)
        val submissionId = submit.string("submission_id") ?: submit.string("input_id") ?: intent
        val prepared = if (submit.containsKey("submission_id") || submit.containsKey("input_id")) submit
            else submit.with("submission_id" to JsonPrimitive(submissionId))
        val result = wire.call("prompt.submit", prepared) as? JsonObject
            ?: throw HermesGatewayException.Protocol("prompt.submit answered without an admission")
        val admission = try { canonicalJson.decodeFromJsonElement(AdmissionStatus.serializer(), result) }
            catch (_: IllegalArgumentException) { throw HermesGatewayException.Protocol("prompt.submit answered without an admission") }
        if (admission.ref.sessionId != sessionId) throw HermesGatewayException.Protocol("The admission receipt names another session")
        adoptGeneration(owner(sessionId), admission.executionGeneration)
        return LegacyShapes.admission(result, sessionId, submissionId)
    }

    /** Interrupt, redirect and steer present the execution generation they act on. */
    private suspend fun turnControl(method: String, params: JsonObject, wire: AuthorityWire): JsonElement {
        val sessionId = required(params, "session_id")
        val profile = params.string("profile")
        var prepared = params.without("expected_hosted_task_id")
        if (!prepared.containsKey("execution_generation")) {
            val generation = try {
                ensureState(sessionId, profile, wire, subscription = true, generation = true).generation
            } catch (_: HermesGatewayException.RPC) { null }
                ?: throw unsupportedOnAuthority(method, "Hermes reported no execution generation")
            prepared = prepared.with("execution_generation" to JsonPrimitive(generation))
        }
        val result = try {
            wire.call(method, prepared)
        } catch (error: HermesGatewayException.RPC) {
            if (error.refusal?.reason == "stale_generation") states[owner(sessionId)]?.generation = null
            throw error
        }
        adoptGeneration(owner(sessionId), (result as? JsonObject)?.long("execution_generation"))
        return if (method == "session.interrupt") jsonObject("status" to JsonPrimitive("interrupted"), "interrupted" to JsonPrimitive(true))
            else result
    }

    private suspend fun compress(params: JsonObject, wire: AuthorityWire): JsonObject {
        val sessionId = required(params, "session_id")
        val profile = params.string("profile")
        val focus = params.string("focus_topic")?.takeIf { it.isNotEmpty() }
        val receipt = mutate(sessionId, profile, "compress", jsonObject("focus" to json(focus)), true, wire)
        if (receipt.string("status") == "preview") {
            val lines = receipt.array("lines")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            return jsonObject("status" to JsonPrimitive("preview"), "message" to JsonPrimitive(lines.joinToString("\n")))
        }
        // The receipt carries counts, not the compacted transcript: read it back, as Hermes Desktop does.
        val snapshot = snapshot(sessionId, profile, wire, messages = true)
        return jsonObject("status" to JsonPrimitive("compressed"), "compressed" to JsonPrimitive(true),
            "messages" to JsonArray(snapshot.messages), "info" to LegacyShapes.snapshot(snapshot, emptyList())["info"],
            "after_messages" to json(receipt.long("message_count")))
    }

    /** There is no undo verb: the authority's `rewind` to the last user message removes the same exchange. */
    private suspend fun undo(params: JsonObject, wire: AuthorityWire): JsonObject {
        val sessionId = required(params, "session_id")
        val profile = params.string("profile")
        val messages = snapshot(sessionId, profile, wire, messages = true).messages
        val index = messages.indexOfLast { it.string("role") == "user" && (it.long("row_id") ?: 0) > 0 }
        if (index < 0) throw unsupportedOnAuthority("session.undo", "the session has no stored user turn to undo")
        val row = messages[index].long("row_id")
        val receipt = mutate(sessionId, profile, "rewind", jsonObject("target_message_id" to json(row)), true, wire)
        return jsonObject("removed" to JsonPrimitive(receipt.long("rewound_count") ?: (messages.size - index).toLong()))
    }

    private suspend fun usage(params: JsonObject, wire: AuthorityWire): JsonObject {
        val sessionId = required(params, "session_id")
        val request = jsonObject("session_id" to JsonPrimitive(sessionId), "profile" to json(params.string("profile")))
        val stats = try {
            wire.call("session.stats", request)
        } catch (error: HermesGatewayException.RPC) {
            // A session this socket never attached is not live in the authority yet.
            if (error.refusal?.reason != "not_found") throw error
            attach(sessionId, params.string("profile"), wire)
            wire.call("session.stats", request)
        }
        return LegacyShapes.usage(stats as? JsonObject ?: JsonObject(emptyMap()))
    }

    private suspend fun eventsSince(params: JsonObject, wire: AuthorityWire): JsonObject {
        val sessionId = required(params, "session_id")
        val prepared = jsonObject(
            "session_id" to JsonPrimitive(sessionId),
            "profile" to json(params.string("profile")),
            "last_sequence" to JsonPrimitive(params.long("last_sequence") ?: params.long("last_seen") ?: sequenceOf(sessionId) ?: 0L),
            "replay_epoch" to json(params.string("replay_epoch") ?: epochOf(sessionId)),
        )
        val result = wire.call("session.events.since", prepared) as? JsonObject ?: JsonObject(emptyMap())
        result.array("events")?.forEach { event ->
            val frame = event as? JsonObject ?: return@forEach
            val type = frame.string("type") ?: return@forEach
            event(type, frame.string("session_id") ?: sessionId, frame.long("execution_generation"), frame.obj("payload") ?: JsonObject(emptyMap()))
        }
        return result.with("open_requests" to (result["open_requests"] ?: JsonArray(emptyList())),
            "epoch" to (result["epoch"] ?: result["replay_epoch"] ?: JsonPrimitive("")),
            "truncated" to if (result.bool("snapshot_required") == true) JsonPrimitive(true) else result["truncated"])
    }

    // Prompts

    private suspend fun findPrompt(id: String, sessionId: String?, profile: String?, wire: AuthorityWire): AuthorityPrompt {
        prompts[id]?.let { return it }
        // Answered from a notification on a fresh socket: the snapshot lists what is still open.
        if (sessionId != null) attach(sessionId, profile, wire)
        return prompts[id] ?: throw unsupportedOnAuthority("approval.respond", "the approval is not open on this gateway")
    }

    /** `approval.respond` / `clarify.respond` for one prompt, fenced by the generation that asked it. */
    suspend fun respond(prompt: AuthorityPrompt, field: String, value: JsonElement, wire: AuthorityWire): JsonObject {
        val profile = routes[prompt.sessionId]
        ensureState(prompt.sessionId, profile, wire, subscription = true)
        val generation = prompt.generation ?: states[owner(prompt.sessionId)]?.generation
        val method = if (prompt.kind == "approval") "approval.respond" else "clarify.respond"
        val result = wire.call(method, jsonObject("session_id" to JsonPrimitive(prompt.sessionId), "profile" to json(profile),
            "execution_generation" to json(generation), "prompt_id" to JsonPrimitive(prompt.promptId), field to value))
        answered += prompt.promptId
        return result as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun answerText(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", ")
        is JsonObject -> value.toString()
    }

    fun clarifyAnswer(promptId: String, result: JsonObject): String =
        result.string("answer") ?: result.obj("answers")?.let { answers ->
            answers.string(promptId) ?: answers.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content }
        } ?: ""

    // Configuration and commands

    private suspend fun configSet(params: JsonObject, wire: AuthorityWire): JsonElement {
        val key = params.string("key")
        val sessionId = params.string("session_id")
        if (key == "model" && sessionId != null) {
            val pick = pickerModel((params["value"] as? JsonPrimitive)?.content) ?: return wire.call("config.set", params)
            val owner = owner(sessionId, params.string("profile") ?: routes[sessionId])
            val payload = confirmedModel(owner, pick, params.bool("confirm_expensive_model") == true)
            val receipt = mutate(sessionId, params.string("profile"), "model", payload, true, wire)
            val model = pick.string("model")
            return if (receipt.string("status") == "confirmation_required") {
                jsonObject("key" to JsonPrimitive("model"), "value" to json(model), "confirm_required" to JsonPrimitive(true),
                    "confirm_message" to receipt["confirm_message"])
            } else {
                jsonObject("key" to JsonPrimitive("model"), "value" to (receipt["model"] ?: json(model)))
            }
        }
        // Only reasoning and global yolo take a scope on the authority.
        val scope = params.string("scope")
        if (scope != null && key != "reasoning" && !(key == "yolo" && scope == "global")) {
            return wire.call("config.set", params.without("scope"))
        }
        return wire.call("config.set", params)
    }

    /** The typed `/model <name>`, `/branch [title]` and `/compress [focus]` directives are mutations. */
    private suspend fun slash(method: String, command: String, params: JsonObject, wire: AuthorityWire): JsonObject? {
        val sessionId = params.string("session_id") ?: return null
        val directive = slashMutation(command) ?: return null
        val profile = params.string("profile")
        val operation = directive.first
        val payload = if (operation == "model") confirmedModel(owner(sessionId, profile ?: routes[sessionId]), directive.second, false)
            else directive.second
        val receipt = mutate(sessionId, profile, operation, payload, true, wire)
        if (operation == "model" && receipt.string("status") == "confirmation_required") {
            return jsonObject("type" to JsonPrimitive("exec"), "output" to (receipt["confirm_message"] ?: JsonPrimitive("")),
                "status" to JsonPrimitive("confirmation_required"))
        }
        val output = when (operation) {
            "model" -> "model: ${receipt.string("model") ?: payload.string("model").orEmpty()}"
            "branch" -> "branch: ${receipt.string("branched_session_id").orEmpty()}"
            else -> if (receipt.string("status") == "preview") {
                receipt.array("lines")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().joinToString("\n")
            } else "compress: ${receipt.string("target_session_id") ?: sessionId}"
        }
        return jsonObject("type" to JsonPrimitive("exec"), "output" to JsonPrimitive(output))
    }

    private fun confirmationKey(owner: Owner, payload: JsonObject): String =
        JsonArray(listOf(JsonPrimitive(owner.profile), JsonPrimitive(owner.sessionId), payload.without("confirm"))).toString()

    private fun confirmedModel(owner: Owner, payload: JsonObject, confirmed: Boolean): JsonObject {
        val confirmation = confirmations[confirmationKey(owner, payload)] ?: return payload
        if (!(confirmed || confirmation.sent)) return payload
        confirmation.sent = true
        return payload.with("confirm" to JsonPrimitive(confirmation.token))
    }

    private fun settleConfirmation(owner: Owner, request: JsonObject) {
        val payload = request.obj("payload") ?: return
        val token = payload.string("confirm") ?: return
        if (request.string("operation") != "model") return
        val key = confirmationKey(owner, payload)
        if (confirmations[key]?.token == token) confirmations.remove(key)
    }

    // Mutations

    private suspend fun mutate(sessionId: String, profile: String?, operation: String, payload: JsonObject,
                               withGeneration: Boolean, wire: AuthorityWire): JsonObject {
        ensureState(sessionId, profile, wire, revision = true, generation = withGeneration)
        val owner = owner(sessionId, profile ?: routes[sessionId])
        val request = retainedMutation(owner, operation, payload, withGeneration)
        val result = try {
            wire.call("session.mutate", request.with("profile" to json(profile)))
        } catch (error: HermesGatewayException.RPC) {
            failure(owner, request, error)
            throw error
        }
        val receipt = result as? JsonObject ?: throw HermesGatewayException.Protocol("session.mutate answered without a receipt")
        if (receipt.string("session_id") != sessionId) throw HermesGatewayException.Protocol("Mutation receipt destination mismatch")
        settle(owner, request, receipt)
        return receipt
    }

    /** One request id per (session, operation, payload) until the authority answers: a retry after a lost reply
     *  replays the same request, which the authority resolves as an exact retry. Any other control of the session
     *  retires it, as only a session's very next control may retry an ambiguous one. */
    private fun retainedMutation(owner: Owner, operation: String, payload: JsonObject, withGeneration: Boolean): JsonObject {
        val key = JsonArray(listOf(JsonPrimitive(owner.profile), JsonPrimitive(owner.sessionId), JsonPrimitive(operation), payload)).toString()
        retireMutations(owner, key)
        mutations[key]?.let { return it.request }
        val state = state(owner)
        val revision = state.revision ?: throw unsupportedOnAuthority("session.mutate", "Hermes reported no revision for the session")
        val generation = state.generation
        if (withGeneration && generation == null) {
            throw unsupportedOnAuthority("session.mutate", "Hermes reported no execution generation for the session")
        }
        val request = canonicalJson.encodeToJsonElement(SessionMutateParams.serializer(), SessionMutateParams(
            sessionId = owner.sessionId, requestId = newId(), expectedRevision = revision, operation = operation,
            payload = payload, expectedGeneration = if (withGeneration && generation != null) Patch.Value(generation) else Patch.Absent,
        )) as JsonObject
        mutations[key] = Retained(owner, operation, request)
        return request
    }

    private fun retireMutations(owner: Owner, keep: String?) {
        mutations.entries.removeAll { (key, retained) -> key != keep && retained.owner == owner }
    }

    private fun forgetRequest(request: JsonObject) {
        val id = request.string("request_id")
        mutations.values.removeAll { it.request.string("request_id") == id }
    }

    private fun settle(owner: Owner, request: JsonObject, receipt: JsonObject) {
        val revision = receipt.long("revision")
        // An exact retry replays its original receipt: never move the revision backwards.
        if (revision != null) {
            val state = state(owner)
            if (revision >= (state.revision ?: -1)) state.revision = revision
        }
        adoptGeneration(owner, receipt.long("execution_generation"))
        forgetRequest(request)
        settleConfirmation(owner, request)
        retireSuperseded(owner, revision)
        if (request.string("operation") == "model" && receipt.string("status") == "confirmation_required") {
            val token = receipt.string("confirm")
            val payload = request.obj("payload")
            if (token != null && payload != null) confirmations[confirmationKey(owner, payload)] = Confirmation(token, false)
        }
    }

    /** A typed refusal wrote nothing. As in Hermes Desktop's `failure`, only `revision_conflict` (and `session_busy`
     *  on a rewind or a move) retires the request; a `stale_generation` refusal keeps it, so the same edit is
     *  retried as the same request until another control of the session retires it. A transport failure keeps the
     *  request, so its retry cannot overwrite an edit made meanwhile. A refusal that proves the presented revision
     *  or generation stale forgets that value, so a new edit re-reads it. */
    private fun failure(owner: Owner, request: JsonObject, error: HermesGatewayException.RPC) {
        val reason = error.refusal?.reason ?: return
        settleConfirmation(owner, request)
        val operation = request.string("operation")
        when (reason) {
            "revision_conflict" -> states[owner]?.revision = null
            "stale_generation" -> states[owner]?.generation = null
        }
        if (reason == "revision_conflict" || (reason == "session_busy" && operation in setOf("rewind", "workspace"))) {
            forgetRequest(request)
        }
    }

    /** An acknowledged mutation at [revision] settles every older retained value-setting edit of the session:
     *  it either applied before or can only be refused. Branch and compress stay, since a new request id there
     *  would run them a second time. */
    private fun retireSuperseded(owner: Owner, revision: Long?) {
        if (revision == null) return
        val retired = mutations.entries.filter { (_, retained) ->
            retained.owner == owner && retained.operation in VALUE_OPERATIONS &&
                (retained.request.long("expected_revision") ?: Long.MAX_VALUE) < revision
        }
        for ((key, retained) in retired) {
            mutations.remove(key)
            settleConfirmation(owner, retained.request)
        }
    }

    internal companion object {
        /** `session.create` keys when Hermes does not describe them (Hermes Desktop's list). */
        val STATIC_CREATE_PARAMETERS = setOf("cwd", "model", "provider", "toolsets", "reasoning", "title", "hidden",
            "follow_profile_config", "service_tier", "cwd_explicit")
        val RESUME_PARAMETERS = setOf("session_id", "profile", "title", "latest", "workspace", "source", "editor", "api_key",
            "defer_history", "omit_messages", "cols", "display_projection")
        val TRUNCATE_PARAMETERS = listOf("confirm_truncate", "confirm_empty_truncate", "confirm_deep_truncate",
            "truncate_before_row_id", "truncate_before_message_id", "truncate_before_user_ordinal", "rebind_survivor_row_ids")
        /** `CanonicalSubmitParams`. */
        val SUBMIT_PARAMETERS = setOf("session_id", "profile", "text", "submission_id", "input_id", "queued", "attachments",
            "finite", "unattended", "surface", "voice_context", "interrupted", "voice_turn", "display_kind", "title_preview",
            "turn_author", "api_text", "model_override", "clarify_batch")
        /** `gateway/session_surface.py` `SURFACES`. */
        val SUBMIT_SURFACES = setOf("hud", "voice-live")
        val TRUNCATE_TARGETS = listOf("truncate_before_row_id", "truncate_before_message_id", "truncate_before_user_ordinal")
        val VALUE_OPERATIONS = setOf("rename", "archive", "model", "reasoning", "workspace")

        /** `config.set key=model`: `<model> --provider <id> [--session]`; any other shape stays `config.set`. */
        fun pickerModel(value: String?): JsonObject? {
            val parts = value?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
            val model = parts.firstOrNull()?.takeIf { !it.startsWith("-") } ?: return null
            val flags = parts.drop(1).let { if (it.lastOrNull() == "--session") it.dropLast(1) else it }
            return if (flags.size == 2 && flags[0] == "--provider" && !flags[1].startsWith("-")) {
                jsonObject("model" to JsonPrimitive(model), "provider" to JsonPrimitive(flags[1]))
            } else null
        }

        /** Hermes' `slash_mutation`: the typed directives that are mutations rather than commands. */
        fun slashMutation(command: String): Pair<String, JsonObject>? {
            val parts = command.trim().trimStart('/').split(Regex("\\s+")).filter { it.isNotEmpty() }
            val name = parts.firstOrNull() ?: return null
            val argument = parts.drop(1).joinToString(" ").trim()
            if (name == "model") {
                if (argument.isEmpty() || argument.startsWith("-") || Regex("(^|\\s)--").containsMatchIn(argument)) return null
                val words = argument.split(Regex("\\s+"))
                return if (words.size == 1) "model" to jsonObject("model" to JsonPrimitive(words[0])) else null
            }
            val field = when (name) { "branch" -> "title"; "compress" -> "focus"; else -> return null }
            return name to if (argument.isEmpty()) JsonObject(emptyMap()) else jsonObject(field to JsonPrimitive(argument))
        }

        private fun text(message: JsonObject): String =
            (message.string("content") ?: message.string("text") ?: "").trim().replace(Regex("\\s+"), " ")

        /** The parent's row that ends a fork's seed, or null for the whole transcript. The seed is matched in order
         *  against the parent's user and assistant rows; failing that, it is cut at the same user turn. */
        fun branchBoundary(seeds: List<JsonObject>, messages: List<JsonObject>): Long? {
            val rows = messages.filter { (it.long("row_id") ?: 0) > 0 }
            var index = 0
            var last: JsonObject? = null
            for (message in rows) {
                if (index == seeds.size) break
                val seed = seeds[index]
                if (message.string("role") == seed.string("role") && text(message) == text(seed)) {
                    index++
                    last = message
                }
            }
            if (index == seeds.size && last != null) {
                return last.long("row_id")?.takeIf { last !== rows.lastOrNull() }
            }
            val turns = seeds.count { it.string("role") == "user" }
            val users = rows.withIndex().filter { it.value.string("role") == "user" }
            if (turns == 0 || turns > users.size) throw unsupportedOnAuthority("session.create",
                "the fork's messages do not match the parent's stored transcript")
            if (seeds.last().string("role") == "user") return users[turns - 1].value.long("row_id")
            val end = if (turns < users.size) users[turns].index - 1 else rows.size - 1
            return rows[end].long("row_id")?.takeIf { end != rows.size - 1 }
        }
    }
}
