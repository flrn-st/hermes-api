package hermes.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import hermes.api.generated.gateway.ApprovalRespondResult
import hermes.api.generated.gateway.ClarifyLockResult
import hermes.api.generated.gateway.ClarifyLockStatus
import hermes.api.generated.gateway.CommandDispatchResult
import hermes.api.generated.gateway.ConfigSetResult
import hermes.api.generated.gateway.GatewayErrorKind
import hermes.api.generated.gateway.GatewayKnownError
import hermes.api.generated.gateway.InterruptStatus
import hermes.api.generated.gateway.PromptSubmitResult
import hermes.api.generated.gateway.PromptSubmitStatus
import hermes.api.generated.gateway.SessionCloseResult
import hermes.api.generated.gateway.SessionCompressResult
import hermes.api.generated.gateway.SessionCreateResult
import hermes.api.generated.gateway.SessionCwdSetResult
import hermes.api.generated.gateway.SessionEventsSinceResult
import hermes.api.generated.gateway.SessionHistoryResult
import hermes.api.generated.gateway.SessionInterruptResult
import hermes.api.generated.gateway.SessionResumeResult
import hermes.api.generated.gateway.SessionTitleResult
import hermes.api.generated.gateway.SessionUndoResult
import hermes.api.generated.gateway.SessionUsageResult
import hermes.api.generated.gateway.SessionWorkspaceMoveResult
import hermes.api.runtime.AuthorityWire
import hermes.api.runtime.GatewayRefusal
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.RuntimeDescription
import hermes.api.runtime.SessionAuthority
import hermes.api.runtime.kind
import hermes.api.runtime.known
import hermes.api.runtime.refusal
import hermes.api.testing.GatewayFrames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The legacy-to-authority translation on its own, against a scripted wire. */
class SessionAuthorityTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    private data class Sent(val method: String, val params: JsonObject)

    /** Answers each call with the next scripted reply for its method, the last one again and again until another
     *  is scripted; records what was sent. */
    private class ScriptedWire : AuthorityWire {
        val sent = mutableListOf<Sent>()
        private class Reply(val answer: (JsonObject) -> JsonElement, var used: Boolean = false)
        private val replies = mutableMapOf<String, ArrayDeque<Reply>>()

        fun on(method: String, reply: (JsonObject) -> JsonElement) {
            replies.getOrPut(method) { ArrayDeque() }.addLast(Reply(reply))
        }

        fun on(method: String, result: String) = on(method) { Json.parseToJsonElement(result) }

        fun fail(method: String, error: Exception) = on(method) { throw error }

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            sent += Sent(method, params)
            val queue = replies[method] ?: error("unexpected $method $params")
            if (queue.first().used && queue.size > 1) queue.removeFirst()
            val reply = queue.first()
            reply.used = true
            if (queue.size > 1) queue.removeFirst()
            return reply.answer(params)
        }

        fun methods() = sent.map { it.method }
        fun last(method: String) = sent.last { it.method == method }.params
    }

    private var ids = 0
    private fun authority(description: RuntimeDescription? = describe()) =
        SessionAuthority(newId = { "id-${++ids}" }).also { it.connectionOpened(description) }

    private fun describe() = RuntimeDescription.parse(Json.parseToJsonElement(GatewayFrames.RUNTIME_DESCRIPTION))

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun <T> decode(serializer: KSerializer<T>, value: JsonElement): T = json.decodeFromJsonElement(serializer, value)

    private fun refusal(reason: String, message: String = reason) =
        HermesGatewayException.RPC(4001, message, obj("""{"reason":"$reason"}"""))

    private val JsonObject.payload get() = this["payload"]?.jsonObject
    private fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.content

    /** Attaches session `s` at the given state through an explicit resume. */
    private suspend fun SessionAuthority.attached(wire: ScriptedWire, session: String = "s", revision: Long = 3, generation: Long = 2,
                                                  prompts: String = "[]", messages: String = "[]") {
        wire.on("session.resume", GatewayFrames.authoritySnapshot(session, revision, generation, prompts = prompts, messages = messages))
        call("session.resume", obj("""{"session_id":"$session"}"""), wire, "attach")
    }

    // Lifecycle

    @Test
    fun createSpeaksTheCanonicalContract() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        wire.on("session.create", GatewayFrames.authoritySnapshot("local-1"))
        val result = authority.call("session.create", obj("""{"profile":"default","cols":100,"source":"cadu","cwd":"/w",
            "title":"T","hidden":true,"follow_profile_config":true,"close_on_disconnect":false,"reasoning_effort":"high",
            "fast":true,"model":"m","provider":"p","room_plumbing":true}"""), wire, "intent")
        val sent = wire.last("session.create")
        assertEquals(setOf("cwd", "title", "hidden", "follow_profile_config", "reasoning", "service_tier", "model", "provider",
            "request_id", "source"), sent.keys)
        assertEquals("gui", sent.text("source"))
        assertEquals("high", sent.text("reasoning"))
        assertEquals("priority", sent.text("service_tier"))
        val created = decode(SessionCreateResult.serializer(), result)
        assertEquals("local-1", created.sessionId)
        assertEquals("local-1", created.storedSessionId)
    }

    @Test
    fun createKeepsItsRequestIdAcrossRetriesUntilItSucceeds() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        val params = obj("""{"profile":"work","fast":false,"title":"T"}""")
        wire.fail("session.create", HermesGatewayException.Transport("lost"))
        assertFailsWith<HermesGatewayException.Transport> { authority.call("session.create", params, wire, "a") }
        wire.on("session.create", GatewayFrames.authoritySnapshot("local-1"))
        authority.call("session.create", params, wire, "b")
        val (first, second) = wire.sent.map { it.params }
        assertEquals(first.text("request_id"), second.text("request_id"))
        assertEquals("work", second.text("profile"))
        assertEquals("normal", second.text("service_tier"))
        authority.call("session.create", params, wire, "c")
        assertTrue(wire.sent[2].params.text("request_id") != first.text("request_id"))
    }

    @Test
    fun resumeDropsWhatTheAuthorityRefusesAndProjectsTheSnapshot() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s", running = true, prompts =
            """[{"kind":"approval","prompt_id":"p1","execution_generation":2,"choices":["once","session","deny"],"command":"rm -rf x"}]"""))
        val result = authority.call("session.resume", obj("""{"session_id":"s","lazy":true,"close_on_disconnect":false,
            "source":"cadu-android","eager_build":true,"cols":80}"""), wire, "i")
        val sent = wire.last("session.resume")
        assertEquals(setOf("session_id", "source", "cols", "omit_messages"), sent.keys)
        assertEquals("false", sent.text("omit_messages"))
        val resumed = decode(SessionResumeResult.serializer(), result)
        assertEquals("s", resumed.storedSessionId)
        assertEquals("s", resumed.sessionKey)
        assertEquals(true, resumed.running)
        assertEquals(true, resumed.info.running)
        val open = resumed.openRequests.orEmpty().single()
        assertEquals("p1", open.id)
        assertEquals("approval", open.method)
        assertEquals("p1", resumed.pendingApproval?.requestId)
        assertEquals(true, resumed.pendingApproval?.allowSession)
        assertEquals(false, resumed.pendingApproval?.allowPermanent)
    }

    @Test
    fun activateIsAResume() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s"))
        authority.call("session.activate", obj("""{"session_id":"s","omit_messages":true}"""), wire, "i")
        assertEquals(listOf("session.resume"), wire.methods())
        assertEquals("true", wire.last("session.resume").text("omit_messages"))
    }

    @Test
    fun closeDetachesTheSubscriptionOfThisSocketOnly() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        assertFalse(decode(SessionCloseResult.serializer(), authority.call("session.close", obj("""{"session_id":"s"}"""), wire, "i")).closed)
        assertTrue(wire.sent.isEmpty())
        authority.attached(wire)
        wire.on("session.detach", """{"session_id":"s","subscription_id":"subscription-1","detached":true}""")
        assertTrue(decode(SessionCloseResult.serializer(), authority.call("session.close", obj("""{"session_id":"s"}"""), wire, "i")).closed)
        assertEquals("subscription-1", wire.last("session.detach").text("subscription_id"))
        // A new connection has no subscription to detach.
        authority.attached(wire)
        authority.connectionOpened(describe())
        val before = wire.sent.size
        authority.call("session.close", obj("""{"session_id":"s"}"""), wire, "i")
        assertEquals(before, wire.sent.size)
    }

    @Test
    fun historyReadsTheSnapshotTranscript() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s", messages = """[{"role":"user","content":"hi","row_id":1}]"""))
        val history = decode(SessionHistoryResult.serializer(), authority.call("session.history", obj("""{"session_id":"s"}"""), wire, "i"))
        assertEquals(1, history.count)
        assertEquals("false", wire.last("session.resume").text("omit_messages"))
    }

    // Mutations

    @Test
    fun titleIsARenameFencedByRevisionOnly() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire)
        wire.on("session.mutate", """{"session_id":"s","operation":"rename","revision":4,"execution_generation":2,"title":"New"}""")
        val result = decode(SessionTitleResult.serializer(), authority.call("session.title", obj("""{"session_id":"s","title":"New"}"""), wire, "i"))
        assertEquals("New", result.title)
        val sent = wire.last("session.mutate")
        assertEquals("rename", sent.text("operation"))
        assertEquals(3, sent["expected_revision"]?.jsonPrimitive?.long)
        assertFalse(sent.containsKey("expected_generation"))
        assertEquals("New", sent.payload?.text("title"))
        assertEquals(setOf("session_id", "request_id", "expected_revision", "operation", "payload"), sent.keys)
    }

    @Test
    fun workspaceEditsAttachFirstAndCarryTheGeneration() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s", revision = 7, generation = 5))
        wire.on("session.mutate", """{"session_id":"s","operation":"workspace","revision":8}""")
        val moved = decode(SessionCwdSetResult.serializer(), authority.call("session.cwd.set", obj("""{"session_id":"s","cwd":"/new"}"""), wire, "i"))
        assertEquals("/new", moved.cwd)
        assertEquals(listOf("session.resume", "session.mutate"), wire.methods())
        val sent = wire.last("session.mutate")
        assertEquals(7, sent["expected_revision"]?.jsonPrimitive?.long)
        assertEquals(5, sent["expected_generation"]?.jsonPrimitive?.long)
        wire.on("session.mutate", """{"session_id":"s","operation":"workspace","revision":9}""")
        val workspace = decode(SessionWorkspaceMoveResult.serializer(),
            authority.call("session.workspace.move", obj("""{"session_key":"s","cwd":"/other"}"""), wire, "i"))
        assertEquals("/other", workspace.cwd)
        assertEquals(8, wire.last("session.mutate")["expected_revision"]?.jsonPrimitive?.long)
    }

    @Test
    fun aMutationRetriedAfterALostReplyKeepsItsRequest() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire)
        wire.fail("session.mutate", HermesGatewayException.Transport("lost"))
        val params = obj("""{"session_id":"s","title":"A"}""")
        assertFailsWith<HermesGatewayException.Transport> { authority.call("session.title", params, wire, "1") }
        wire.on("session.mutate", """{"session_id":"s","operation":"rename","revision":4}""")
        authority.call("session.title", params, wire, "2")
        val (first, retry) = wire.sent.filter { it.method == "session.mutate" }.map { it.params }
        assertEquals(first, retry)
        // Answered: the next equal edit is a new request at the new revision.
        authority.call("session.title", params, wire, "3")
        val next = wire.last("session.mutate")
        assertTrue(next.text("request_id") != first.text("request_id"))
        assertEquals(4, next["expected_revision"]?.jsonPrimitive?.long)
    }

    @Test
    fun anotherControlRetiresARetainedMutation() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire)
        wire.fail("session.mutate", HermesGatewayException.Transport("lost"))
        assertFailsWith<HermesGatewayException.Transport> { authority.call("session.title", obj("""{"session_id":"s","title":"A"}"""), wire, "1") }
        val lost = wire.last("session.mutate")
        assertFailsWith<HermesGatewayException.Transport> { authority.call("session.cwd.set", obj("""{"session_id":"s","cwd":"/x"}"""), wire, "2") }
        wire.on("session.mutate", """{"session_id":"s","operation":"rename","revision":4}""")
        authority.call("session.title", obj("""{"session_id":"s","title":"A"}"""), wire, "3")
        assertTrue(wire.last("session.mutate").text("request_id") != lost.text("request_id"))
    }

    @Test
    fun typedRefusalsDecideWhetherTheRequestIsRetained() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire)
        // A busy rename was not written but may be retried as the same request.
        wire.fail("session.mutate", refusal("session_busy"))
        assertFailsWith<HermesGatewayException.RPC> { authority.call("session.title", obj("""{"session_id":"s","title":"A"}"""), wire, "1") }
        val busy = wire.last("session.mutate")
        assertFailsWith<HermesGatewayException.RPC> { authority.call("session.title", obj("""{"session_id":"s","title":"A"}"""), wire, "2") }
        assertEquals(busy.text("request_id"), wire.last("session.mutate").text("request_id"))
        // A busy move is a new request next time.
        assertFailsWith<HermesGatewayException.RPC> { authority.call("session.cwd.set", obj("""{"session_id":"s","cwd":"/x"}"""), wire, "3") }
        val move = wire.last("session.mutate")
        assertFailsWith<HermesGatewayException.RPC> { authority.call("session.cwd.set", obj("""{"session_id":"s","cwd":"/x"}"""), wire, "4") }
        assertTrue(move.text("request_id") != wire.last("session.mutate").text("request_id"))
        // A revision conflict re-reads the session before the next attempt.
        wire.fail("session.mutate", refusal("revision_conflict"))
        assertFailsWith<HermesGatewayException.RPC> { authority.call("session.title", obj("""{"session_id":"s","title":"B"}"""), wire, "5") }
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s", revision = 9, generation = 2))
        wire.on("session.mutate", """{"session_id":"s","operation":"rename","revision":10}""")
        authority.call("session.title", obj("""{"session_id":"s","title":"B"}"""), wire, "6")
        assertEquals(listOf("session.resume", "session.mutate"), wire.methods().takeLast(2))
        assertEquals(9, wire.last("session.mutate")["expected_revision"]?.jsonPrimitive?.long)
    }

    @Test
    fun anotherViewersEditSettlesOlderRetainedValues() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire)
        wire.fail("session.mutate", HermesGatewayException.Transport("lost"))
        assertFailsWith<HermesGatewayException.Transport> { authority.call("session.title", obj("""{"session_id":"s","title":"A"}"""), wire, "1") }
        val lost = wire.last("session.mutate")
        authority.event("session.updated", "s", null, obj("""{"session_id":"s","revision":5,"operation":"archive"}"""))
        wire.on("session.mutate", """{"session_id":"s","operation":"rename","revision":6}""")
        authority.call("session.title", obj("""{"session_id":"s","title":"A"}"""), wire, "2")
        val next = wire.last("session.mutate")
        assertTrue(next.text("request_id") != lost.text("request_id"))
        assertEquals(5, next["expected_revision"]?.jsonPrimitive?.long)
    }

    @Test
    fun modelChangesAreMutationsWithTheConfirmationHandshake() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire)
        wire.on("session.mutate", """{"session_id":"s","operation":"model","status":"confirmation_required","confirm":"tok","confirm_message":"Costly"}""")
        val asked = decode(ConfigSetResult.serializer(), authority.call("config.set",
            obj("""{"session_id":"s","key":"model","value":"big --provider p --session"}"""), wire, "1"))
        assertEquals(true, asked.confirmRequired)
        assertEquals("Costly", asked.confirmMessage)
        val first = wire.last("session.mutate")
        assertEquals(obj("""{"model":"big","provider":"p"}"""), first.payload)
        assertEquals(2, first["expected_generation"]?.jsonPrimitive?.long)
        wire.on("session.mutate", """{"session_id":"s","operation":"model","revision":4,"model":"big"}""")
        val applied = decode(ConfigSetResult.serializer(), authority.call("config.set",
            obj("""{"session_id":"s","key":"model","value":"big --provider p --session","confirm_expensive_model":true}"""), wire, "2"))
        assertEquals("model", applied.key)
        assertEquals("tok", wire.last("session.mutate").payload?.text("confirm"))
        // Spent: a later pick of the same target asks again.
        authority.call("config.set", obj("""{"session_id":"s","key":"model","value":"big --provider p","confirm_expensive_model":true}"""), wire, "3")
        assertNull(wire.last("session.mutate").payload?.text("confirm"))
    }

    @Test
    fun compressResumesForTheTranscript() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire)
        wire.on("session.mutate", """{"session_id":"s","operation":"compress","revision":4,"message_count":2}""")
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s", 4, 2, messages = """[{"role":"user","content":"x","row_id":3}]"""))
        val result = decode(SessionCompressResult.serializer(), authority.call("session.compress",
            obj("""{"session_id":"s","focus_topic":"tests"}"""), wire, "i"))
        assertEquals(1, result.messages?.size)
        assertEquals(true, result.compressed)
        assertEquals("tests", wire.last("session.mutate").payload?.text("focus"))
    }

    @Test
    fun undoRewindsToTheLastUserMessage() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire, messages = """[{"role":"user","content":"a","row_id":1},{"role":"assistant","content":"b","row_id":2},
            {"role":"user","content":"c","row_id":3},{"role":"assistant","content":"d","row_id":4}]""")
        wire.on("session.mutate", """{"session_id":"s","operation":"rewind","revision":4}""")
        val undone = decode(SessionUndoResult.serializer(), authority.call("session.undo", obj("""{"session_id":"s"}"""), wire, "i"))
        assertEquals(2, undone.removed)
        assertEquals(3, wire.last("session.mutate").payload?.get("target_message_id")?.jsonPrimitive?.long)
    }

    @Test
    fun aSeededForkBranchesTheParentThroughTheSeed() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        val transcript = """[{"role":"user","content":"a","row_id":1},{"role":"assistant","content":"b","row_id":2},
            {"role":"user","content":"c","row_id":3},{"role":"assistant","content":"d","row_id":4}]"""
        wire.on("session.resume", GatewayFrames.authoritySnapshot("parent", 3, 2, messages = transcript))
        wire.on("session.resume", GatewayFrames.authoritySnapshot("child"))
        wire.on("session.mutate", """{"session_id":"parent","operation":"branch","revision":4,"branched_session_id":"child","copied_messages":2}""")
        val created = decode(SessionCreateResult.serializer(), authority.call("session.create", obj("""{"profile":"default","source":"cadu",
            "parent_session_id":"parent","messages":[{"role":"user","content":"a"},{"role":"assistant","content":"b"}],
            "close_on_disconnect":false}"""), wire, "i"))
        assertEquals("child", created.sessionId)
        val branch = wire.last("session.mutate")
        assertEquals("branch", branch.text("operation"))
        assertEquals(2, branch.payload?.get("through_message_id")?.jsonPrimitive?.long)
        assertEquals(listOf("session.resume", "session.mutate", "session.resume"), wire.methods())
    }

    @Test
    fun branchBoundaryFallsBackToTheUserTurn() {
        val rows = listOf(
            obj("""{"role":"user","content":"a","row_id":1}"""), obj("""{"role":"assistant","content":"tool talk","row_id":2}"""),
            obj("""{"role":"tool","content":"x","row_id":3}"""), obj("""{"role":"assistant","content":"done","row_id":4}"""),
            obj("""{"role":"user","content":"c","row_id":5}"""), obj("""{"role":"assistant","content":"d","row_id":6}"""),
        )
        // The app merged the first reply's segments; the cut stays at the end of the first turn.
        assertEquals(4, SessionAuthority.branchBoundary(listOf(obj("""{"role":"user","content":"a"}"""),
            obj("""{"role":"assistant","content":"tool talk done"}""")), rows))
        assertEquals(5, SessionAuthority.branchBoundary(listOf(obj("""{"role":"user","content":"a"}"""),
            obj("""{"role":"assistant","content":"?"}"""), obj("""{"role":"user","content":"c"}""")), rows))
        assertNull(SessionAuthority.branchBoundary(rows.map { it }, rows))
        assertFailsWith<HermesGatewayException.RPC> {
            SessionAuthority.branchBoundary(List(3) { obj("""{"role":"user","content":"x"}""") }, rows)
        }
    }

    // Turns

    @Test
    fun submitCarriesASubmissionIdAndAttachesFirst() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s"))
        wire.on("prompt.submit") { params ->
            obj("""{"admission_id":"a1","ref":{"profile_id":"/h","session_id":"${params.text("session_id")}"},"sequence":1,
                "status":"started","outcome":null,"authority_epoch":1,"execution_generation":3}""")
        }
        val result = decode(PromptSubmitResult.serializer(), authority.call("prompt.submit",
            obj("""{"session_id":"s","text":"hi","surface":"android"}"""), wire, "intent-1"))
        assertEquals(PromptSubmitStatus.Streaming, result.status)
        assertEquals(listOf("session.resume", "prompt.submit"), wire.methods())
        assertEquals("intent-1", wire.last("prompt.submit").text("submission_id"))
        // Attached now: the next submit goes straight out.
        authority.call("prompt.submit", obj("""{"session_id":"s","text":"again"}"""), wire, "intent-2")
        assertEquals("prompt.submit", wire.methods().last())
        assertEquals(3, wire.methods().size)
    }

    @Test
    fun anEditIsARewindThenASubmitAndRetriesWithoutCuttingTwice() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire)
        wire.on("session.mutate", """{"session_id":"s","operation":"rewind","revision":4}""")
        wire.fail("prompt.submit", HermesGatewayException.Transport("lost"))
        val edit = obj("""{"session_id":"s","text":"edited","truncate_before_row_id":7,"confirm_truncate":true,"rebind_survivor_row_ids":[1]}""")
        assertFailsWith<HermesGatewayException.Transport> { authority.call("prompt.submit", edit, wire, "edit") }
        wire.on("prompt.submit", """{"admission_id":"a","ref":{"profile_id":"/h","session_id":"s"},"sequence":1,"status":"queued",
            "outcome":null,"authority_epoch":1,"execution_generation":null}""")
        authority.call("prompt.submit", edit, wire, "edit")
        assertEquals(listOf("session.resume", "session.mutate", "prompt.submit", "prompt.submit"), wire.methods())
        assertEquals(7, wire.last("session.mutate").payload?.get("target_message_id")?.jsonPrimitive?.long)
        val submit = wire.last("prompt.submit")
        assertEquals(setOf("session_id", "text", "submission_id"), submit.keys)
        assertEquals("edit", submit.text("submission_id"))
        // Without a saved row there is nothing to cut.
        val unsaved = assertFailsWith<HermesGatewayException.RPC> {
            authority.call("prompt.submit", obj("""{"session_id":"s","text":"x","truncate_before_message_id":"m"}"""), wire, "x")
        }
        assertEquals(GatewayRefusal.UNSUPPORTED_ON_AUTHORITY, unsaved.refusal?.reason)
    }

    @Test
    fun turnControlsCarryTheTrackedGeneration() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire, generation = 4)
        authority.event("message.start", "s", 6, obj("{}"))
        wire.on("session.interrupt", """{"ref":{"profile_id":"/h","session_id":"s"},"instance_id":"i","authority_epoch":1,"revision":3,
            "execution_generation":6,"execution_state":"idle"}""")
        val interrupted = decode(SessionInterruptResult.serializer(), authority.call("session.interrupt",
            obj("""{"session_id":"s","profile":"default","expected_hosted_task_id":"t"}"""), wire, "i"))
        assertEquals(InterruptStatus.Interrupted, interrupted.status)
        assertEquals(obj("""{"session_id":"s","profile":"default","execution_generation":6}"""), wire.last("session.interrupt"))
        wire.on("session.redirect", """{"status":"redirected","text":"x","execution_generation":6,"authority_epoch":1}""")
        authority.call("session.redirect", obj("""{"session_id":"s","text":"x"}"""), wire, "i")
        assertEquals(6, wire.last("session.redirect")["execution_generation"]?.jsonPrimitive?.long)
        // An older generation never moves it back.
        authority.event("message.delta", "s", 5, obj("{}"))
        authority.call("session.redirect", obj("""{"session_id":"s","text":"y"}"""), wire, "i")
        assertEquals(6, wire.last("session.redirect")["execution_generation"]?.jsonPrimitive?.long)
    }

    @Test
    fun usageIsSessionStats() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        wire.on("session.stats", """{"input_tokens":10,"output_tokens":5,"cache_read_tokens":2,"cache_write_tokens":0,"prompt_tokens":12,
            "completion_tokens":5,"total_tokens":17,"reasoning_tokens":1,"api_calls":1,"last_prompt_tokens":12,"context_tokens":250,
            "context_length":1000,"compression_count":0,"tokens_per_s":9.5,"last_turn_tokens":5,"active_subagents":0,"model":"m","provider":"p"}""")
        val usage = decode(SessionUsageResult.serializer(), authority.call("session.usage", obj("""{"session_id":"s"}"""), wire, "i"))
        assertEquals(10, usage.input)
        assertEquals(17, usage.total)
        assertEquals(1, usage.calls)
        assertEquals(250, usage.contextUsed)
        assertEquals(1000, usage.contextMax)
        assertEquals(25, usage.contextPercent)
        assertEquals(9.5, usage.avgTps)
        assertEquals("m", usage.model)
    }

    @Test
    fun eventsSinceSendsTheSessionEpochAndDecodesAsLegacy() = runTest {
        val wire = ScriptedWire()
        val authority = SessionAuthority(epochOf = { if (it == "s") "epoch-7" else null }).also { it.connectionOpened(null) }
        wire.on("session.events.since", """{"events":[{"type":"message.delta","session_id":"s","payload":{"text":"x"},"replay_epoch":"epoch-7",
            "seq":4}],"latest_seq":4,"last_sequence":4,"epoch":"epoch-7","replay_epoch":"epoch-7","truncated":false,"snapshot_required":false,"count":1}""")
        val result = decode(SessionEventsSinceResult.serializer(), authority.call("session.events.since",
            obj("""{"session_id":"s","last_seen":3}"""), wire, "i"))
        assertEquals(1, result.events.size)
        assertEquals(obj("""{"session_id":"s","last_sequence":3,"replay_epoch":"epoch-7"}"""), wire.last("session.events.since"))
    }

    // Prompts

    @Test
    fun approvalsAndClarifyAnswersNameThePromptAndItsGeneration() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire, generation = 2)
        val asked = authority.event("approval.request", "s", 5,
            obj("""{"kind":"approval","prompt_id":"p1","execution_generation":5,"choices":["once","deny"],"command":"ls"}"""))
        assertEquals("p1", asked.deliver?.promptId)
        wire.on("approval.respond", """{"status":"resolved","prompt_id":"p1"}""")
        val approved = decode(ApprovalRespondResult.serializer(), authority.call("approval.respond",
            obj("""{"session_id":"s","profile":"default","choice":"once","request_id":"p1"}"""), wire, "i"))
        assertEquals(1, approved.resolved)
        assertEquals(obj("""{"session_id":"s","profile":"default","execution_generation":5,"prompt_id":"p1","choice":"once"}"""),
            wire.last("approval.respond"))
        authority.event("clarify.request", "s", 5, obj("""{"kind":"clarify","prompt_id":"q1","question":"Which?","choices":["a","b"]}"""))
        wire.on("clarify.respond", """{"status":"resolved","prompt_id":"q1"}""")
        val locked = decode(ClarifyLockResult.serializer(), authority.call("clarify.lock",
            obj("""{"request_id":"q1","question_id":"q1","answer":["a","b"]}"""), wire, "i"))
        assertEquals(ClarifyLockStatus.Ok, locked.status)
        assertEquals("a, b", wire.last("clarify.respond").text("answer"))
        assertTrue(authority.isAnswered("q1"))
        // Settled: no longer offered.
        assertEquals("q1", authority.event("clarify.settled", "s", 5, obj("""{"prompt_id":"q1"}""")).settledId)
        assertNull(authority.prompt("q1"))
    }

    @Test
    fun anApprovalFromANotificationAttachesToFindItsPrompt() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s", prompts =
            """[{"kind":"approval","prompt_id":"p9","execution_generation":3,"choices":["once","deny"]}]"""))
        wire.on("approval.respond", """{"status":"already_resolved","prompt_id":"p9"}""")
        val result = decode(ApprovalRespondResult.serializer(), authority.call("approval.respond",
            obj("""{"session_id":"s","choice":"deny","request_id":"p9"}"""), wire, "i"))
        assertEquals(0, result.resolved)
        assertEquals(3, wire.last("approval.respond")["execution_generation"]?.jsonPrimitive?.long)
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s"))
        val gone = assertFailsWith<HermesGatewayException.RPC> {
            authority.call("approval.respond", obj("""{"session_id":"s","choice":"deny","request_id":"nope"}"""), wire, "i")
        }
        assertEquals(GatewayKnownError.UNKNOWN_METHOD, gone.known)
    }

    // Configuration and commands

    @Test
    fun configurationAndCommandParamsFitTheClosedContracts() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        wire.on("config.set", """{"key":"x","value":"1"}""")
        authority.call("config.set", obj("""{"session_id":"s","key":"fast","value":"fast","scope":"session"}"""), wire, "i")
        assertFalse(wire.last("config.set").containsKey("scope"))
        authority.call("config.set", obj("""{"session_id":"s","key":"yolo","value":"1","scope":"session"}"""), wire, "i")
        assertFalse(wire.last("config.set").containsKey("scope"))
        authority.call("config.set", obj("""{"key":"yolo","value":"1","scope":"global"}"""), wire, "i")
        assertEquals("global", wire.last("config.set").text("scope"))
        authority.call("config.set", obj("""{"session_id":"s","key":"reasoning","value":"high","scope":"session"}"""), wire, "i")
        assertEquals("session", wire.last("config.set").text("scope"))
        wire.on("commands.catalog", """{"pairs":[]}""")
        authority.call("commands.catalog", obj("""{"session_id":"s","profile":"default"}"""), wire, "i")
        assertEquals(obj("""{"profile":"default"}"""), wire.last("commands.catalog"))
        wire.on("reload.mcp", """{"status":"reloaded"}""")
        authority.call("reload.mcp", obj("{}"), wire, "i")
        assertEquals(obj("""{"confirm":true}"""), wire.last("reload.mcp"))
        wire.on("session.list", """{"sessions":[],"scope":"stored"}""")
        authority.call("session.list", obj("""{"profile":"p","limit":2147483647,"include_hidden":true}"""), wire, "i")
        assertEquals(obj("""{"profile":"p","limit":200,"include_hidden":true,"scope":"stored"}"""), wire.last("session.list"))
        authority.call("session.list", obj("""{"title":"Bot Chat"}"""), wire, "i")
        assertEquals(obj("""{"title":"Bot Chat"}"""), wire.last("session.list"))
    }

    @Test
    fun typedModelBranchAndCompressCommandsAreMutations() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire)
        wire.on("session.mutate", """{"session_id":"s","operation":"model","revision":4}""")
        val dispatched = decode(CommandDispatchResult.serializer(), authority.call("command.dispatch",
            obj("""{"name":"/model","arg":"fast-model","session_id":"s"}"""), wire, "i"))
        assertEquals("model: fast-model", dispatched.output)
        assertEquals(obj("""{"model":"fast-model"}"""), wire.last("session.mutate").payload)
        wire.on("command.dispatch", """{"type":"exec","output":"ok"}""")
        authority.call("command.dispatch", obj("""{"name":"/model","arg":"x --global","session_id":"s"}"""), wire, "i")
        assertEquals("command.dispatch", wire.methods().last())
        assertEquals("branch" to obj("""{"title":"Side"}"""), SessionAuthority.slashMutation("/branch Side"))
        assertEquals("compress" to obj("{}"), SessionAuthority.slashMutation("/compress"))
        assertNull(SessionAuthority.slashMutation("/help"))
    }

    @Test
    fun verbsWithoutAnEquivalentAreRefusedByName() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        val refused = assertFailsWith<HermesGatewayException.RPC> {
            authority.call("pdf.attach", obj("""{"session_id":"s","path":"/x.pdf"}"""), wire, "i")
        }
        assertEquals(GatewayRefusal.UNSUPPORTED_ON_AUTHORITY, refused.refusal?.reason)
        assertEquals("pdf.attach", refused.refusal?.field)
        assertEquals(GatewayKnownError.UNKNOWN_METHOD, refused.known)
        assertEquals(GatewayErrorKind.UNSUPPORTED, refused.kind)
        assertTrue(wire.sent.isEmpty())
        // Everything else goes out unchanged.
        wire.on("rollback.list", """{"entries":[]}""")
        val params = obj("""{"session_id":"s","profile":"p"}""")
        authority.call("rollback.list", params, wire, "i")
        assertEquals(params, wire.last("rollback.list"))
    }

    @Test
    fun snapshotsAndEventsTrackRevisionAndPrompts() = runTest {
        val wire = ScriptedWire()
        val authority = authority()
        authority.attached(wire, prompts = """[{"kind":"approval","prompt_id":"old","execution_generation":2,"choices":["once"]}]""")
        assertTrue(authority.prompt("old") != null)
        // The pending fan-out carries the revision a turn reached.
        authority.event("session.info", "s", 3, obj("""{"revision":8,"pending":[]}"""))
        wire.on("session.mutate", """{"session_id":"s","operation":"rename","revision":9}""")
        authority.call("session.title", obj("""{"session_id":"s","title":"x"}"""), wire, "i")
        assertEquals(8, wire.last("session.mutate")["expected_revision"]?.jsonPrimitive?.long)
        // A later snapshot that no longer lists a prompt drops it.
        wire.on("session.resume", GatewayFrames.authoritySnapshot("s", 9, 3))
        authority.call("session.resume", obj("""{"session_id":"s"}"""), wire, "i")
        assertNull(authority.prompt("old"))
        // A replayed request already known is not delivered twice.
        val payload = obj("""{"kind":"clarify","prompt_id":"q","question":"?","choices":[]}""")
        assertTrue(authority.event("clarify.request", "s", 3, payload).deliver != null)
        assertNull(authority.event("clarify.request", "s", 3, payload).deliver)
        val question = authority.prompt("q")?.legacyParams()?.get("questions")?.jsonArray?.single()?.jsonObject
        assertEquals("q", question?.text("qid"))
    }
}
