package hermes.api

import java.net.URI
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import hermes.api.generated.gateway.ApprovalChoice
import hermes.api.generated.gateway.ApprovalResult
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.PingParams
import hermes.api.generated.gateway.RequestAnswerParams
import hermes.api.generated.gateway.ServerRequest
import hermes.api.generated.gateway.ServerRequestResult
import hermes.api.generated.gateway.SessionCloseParams
import hermes.api.generated.gateway.SessionCreateParams
import hermes.api.generated.gateway.SessionResumeParams
import hermes.api.generated.gateway.ValueResult
import hermes.api.runtime.GatewayConnectionState
import hermes.api.runtime.GatewayRuntime
import hermes.api.runtime.GatewaySessionRecovery
import hermes.api.runtime.HermesDashboardAddress
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.LocalTokenAuth
import hermes.api.runtime.Patch
import hermes.api.testing.GatewayFrames
import hermes.api.testing.ScriptedGatewaySocket
import hermes.api.testing.ScriptedGatewayTransport
import hermes.api.testing.SentAnswer
import hermes.api.testing.SentCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The gateway against a scripted session authority: detection, translated calls, prompts, replay and recovery. */
class SessionAuthorityGatewayTest {
    private fun TestScope.client(transport: ScriptedGatewayTransport, restoresRequests: Boolean = true) = HermesGateway(
        HermesGatewayConfiguration(HermesDashboardAddress(URI("http://localhost:3000")), LocalTokenAuth("secret"), transport,
            requestTimeoutMillis = 3_000, reconnectDelayMillis = { 0 }, heartbeatIntervalMillis = 600_000,
            heartbeatDeadlineMillis = 1_200_000, automaticallyRestoreServerRequests = restoresRequests),
        scope = backgroundScope)

    private suspend fun ScriptedGatewaySocket.next(): SentCall = SentCall(withTimeout(3_000) { sent.receive() })

    private val SentCall.fields: JsonObject get() = params.jsonObject

    private fun SentCall.text(name: String) = fields[name]?.jsonPrimitive?.content

    private fun authoritySocket() = ScriptedGatewaySocket(sessionAuthority = true)

    /** Creates session [id] through the typed method and answers it with a snapshot at [epoch]. */
    private suspend fun TestScope.create(gateway: HermesGateway, socket: ScriptedGatewaySocket, id: String, epoch: String = "epoch-1",
                                         generation: Long = 0) {
        val created = async { gateway.methods.session.create(SessionCreateParams(source = Patch.Value("cadu"), closeOnDisconnect = false)) }
        val frame = socket.next()
        assertEquals("session.create", frame.method)
        socket.inject(GatewayFrames.result(frame.id, GatewayFrames.authoritySnapshot(id, replayEpoch = epoch, generation = generation)))
        assertEquals(id, created.await().sessionId)
    }

    private suspend fun HermesGateway.awaitConnectedAfter(transport: ScriptedGatewayTransport, attempts: Int) = withTimeout(10_000) {
        while (transport.attempts < attempts || connectionStates.value != GatewayConnectionState.Connected) delay(10)
    }

    @Test
    fun anAuthoritySocketIsDetectedAndDescribed() = runTest {
        val socket = authoritySocket()
        val gateway = client(ScriptedGatewayTransport(socket))
        gateway.connect()
        val runtime = gateway.runtime
        assertEquals(true, runtime?.sessionAuthority)
        assertEquals("instance-1", runtime?.instanceId)
        assertTrue("event-replay-v1" in runtime?.capabilities.orEmpty())
        assertTrue("gui" in runtime?.sessionCreateSources.orEmpty())
        val created = async {
            gateway.methods.session.create(SessionCreateParams(profile = Patch.Value("default"), cols = Patch.Value(100),
                source = Patch.Value("cadu"), title = Patch.Value("T"), closeOnDisconnect = false, hidden = true))
        }
        val frame = socket.next()
        assertEquals(setOf("title", "hidden", "request_id", "source"), frame.fields.keys)
        assertEquals("gui", frame.text("source"))
        socket.inject(GatewayFrames.result(frame.id, GatewayFrames.authoritySnapshot("local-1")))
        assertEquals("local-1", created.await().storedSessionId)
        gateway.disconnect()
    }

    @Test
    fun aLegacySocketKeepsEveryFrameAsItWas() = runTest {
        for (ready in listOf(null, GatewayFrames.gatewayReady(sessionAuthority = false))) {
            val socket = ScriptedGatewaySocket()
            ready?.let(socket::inject)
            val gateway = client(ScriptedGatewayTransport(socket))
            gateway.connect()
            assertEquals(GatewayRuntime.legacy, gateway.runtime)
            val created = async {
                gateway.methods.session.create(SessionCreateParams(cols = Patch.Value(100), source = Patch.Value("cadu"),
                    reasoningEffort = Patch.Value("high"), closeOnDisconnect = false))
            }
            // No runtime.describe went out first.
            val frame = socket.next()
            assertEquals("session.create", frame.method)
            assertEquals(Json.parseToJsonElement("""{"cols":100,"source":"cadu","reasoning_effort":"high","close_on_disconnect":false}"""),
                frame.params)
            socket.inject(GatewayFrames.result(frame.id,
                """{"session_id":"rt","stored_session_id":"st","message_count":0,"messages":[],"info":{}}"""))
            created.await()
            gateway.disconnect()
        }
    }

    @Test
    fun anApprovalPromptReachesTheHandlerAndIsAnsweredByRPC() = runTest {
        val socket = authoritySocket()
        val gateway = client(ScriptedGatewayTransport(socket))
        val asked = CompletableDeferred<ServerRequest>()
        gateway.setServerRequestHandler { request ->
            asked.complete(request)
            ServerRequestResult.Approval(ApprovalResult(ApprovalChoice.Session))
        }
        gateway.connect()
        create(gateway, socket, "local-1")
        socket.inject(GatewayFrames.authorityEvent("approval.request", "local-1", 1, "epoch-1",
            """{"kind":"approval","prompt_id":"p1","execution_generation":1,"choices":["once","session","deny"],"command":"rm x"}""",
            generation = 1))
        val request = assertIs<ServerRequest.Approval>(withTimeout(3_000) { asked.await() })
        assertEquals("p1", request.params.requestId)
        assertEquals("rm x", request.params.command)
        val answer = socket.next()
        assertEquals("approval.respond", answer.method)
        assertEquals(Json.parseToJsonElement("""{"session_id":"local-1","execution_generation":1,"prompt_id":"p1","choice":"session"}"""),
            answer.params)
        socket.inject(GatewayFrames.result(answer.id, """{"status":"resolved","prompt_id":"p1"}"""))
        // The settled echo of the answer withdraws nothing.
        socket.inject(GatewayFrames.authorityEvent("approval.settled", "local-1", 2, "epoch-1", """{"prompt_id":"p1","execution_generation":1}"""))
        gateway.disconnect()
    }

    @Test
    fun aPromptSettledElsewhereCancelsItsHandler() = runTest {
        val socket = authoritySocket()
        val gateway = client(ScriptedGatewayTransport(socket))
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        gateway.setServerRequestHandler {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val withdrawn = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.first { it.type == "request.cancel" }
        }
        gateway.connect()
        create(gateway, socket, "local-1")
        socket.inject(GatewayFrames.authorityEvent("clarify.request", "local-1", 1, "epoch-1",
            """{"kind":"clarify","prompt_id":"q1","execution_generation":1,"question":"Which?","choices":["a"]}""", generation = 1))
        withTimeout(3_000) { started.await() }
        socket.inject(GatewayFrames.authorityEvent("clarify.settled", "local-1", 2, "epoch-1", """{"prompt_id":"q1","execution_generation":1}"""))
        withTimeout(3_000) { cancelled.await() }
        val cancel = assertIs<GatewayEventPayload.RequestCancel>(withTimeout(3_000) { withdrawn.await() }.payload)
        assertEquals("q1", cancel.payload.id)
        assertEquals("clarify", cancel.payload.method)
        gateway.disconnect()
    }

    @Test
    fun watermarksArePerSessionEpoch() = runTest {
        val socket = authoritySocket()
        val gateway = client(ScriptedGatewayTransport(socket))
        val deltas = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filter { it.type == "message.delta" }.take(3).toList()
        }
        gateway.connect()
        create(gateway, socket, "local-1")
        socket.inject(GatewayFrames.authorityEvent("message.delta", "local-1", 1, "epoch-1", """{"text":"a"}"""))
        socket.inject(GatewayFrames.authorityEvent("message.delta", "local-1", 2, "epoch-1", """{"text":"b"}"""))
        socket.inject(GatewayFrames.authorityEvent("message.delta", "local-1", 2, "epoch-1", """{"text":"duplicate"}"""))
        // The replay cache evicted the session: a new epoch numbers from 1 again.
        socket.inject(GatewayFrames.authorityEvent("message.delta", "local-1", 1, "epoch-2", """{"text":"c"}"""))
        val texts = withTimeout(3_000) { deltas.await() }.map { (it.payload as GatewayEventPayload.MessageDelta).payload.text }
        assertEquals(listOf("a", "b", "c"), texts)
        gateway.disconnect()
    }

    @Test
    fun aReconnectResumesAndReplaysFromTheSessionEpoch() = runTest {
        val first = authoritySocket()
        val second = authoritySocket()
        val transport = ScriptedGatewayTransport(first, second)
        val gateway = client(transport)
        val replayed = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.filter { it.type == "message.delta" && it.replayed }.take(2).toList()
        }
        gateway.connect()
        create(gateway, first, "local-1")
        first.inject(GatewayFrames.authorityEvent("message.delta", "local-1", 1, "epoch-1", """{"text":"a"}"""))
        first.inject(GatewayFrames.authorityEvent("message.delta", "local-1", 2, "epoch-1", """{"text":"b"}"""))
        first.sever()
        val resume = second.next()
        assertEquals("session.resume", resume.method)
        assertEquals("local-1", resume.text("session_id"))
        second.inject(GatewayFrames.result(resume.id, GatewayFrames.authoritySnapshot("local-1", lastSequence = 4, subscription = "sub-2")))
        val since = second.next()
        assertEquals("session.events.since", since.method)
        assertEquals(Json.parseToJsonElement("""{"session_id":"local-1","last_sequence":2,"replay_epoch":"epoch-1"}"""), since.params)
        second.inject(GatewayFrames.result(since.id, """{"events":[
            {"type":"message.delta","session_id":"local-1","payload":{"text":"c"},"replay_epoch":"epoch-1","seq":3},
            {"type":"message.delta","session_id":"local-1","payload":{"text":"d"},"replay_epoch":"epoch-1","seq":4}],
            "latest_seq":4,"last_sequence":4,"epoch":"epoch-1","replay_epoch":"epoch-1","truncated":false,"snapshot_required":false,"count":2}"""))
        gateway.awaitConnectedAfter(transport, 2)
        assertEquals(listOf(3L, 4L), withTimeout(3_000) { replayed.await() }.map { it.seq })
        // Closing detaches the new subscription.
        val closing = async { gateway.methods.session.close(SessionCloseParams("local-1")) }
        val detach = second.next()
        assertEquals("session.detach", detach.method)
        assertEquals("sub-2", detach.text("subscription_id"))
        second.inject(GatewayFrames.result(detach.id, """{"session_id":"local-1","subscription_id":"sub-2","detached":true}"""))
        assertTrue(closing.await().closed)
        gateway.disconnect()
    }

    @Test
    fun aReconnectIntoANewEpochReportsTheGap() = runTest {
        val first = authoritySocket()
        val second = authoritySocket()
        val transport = ScriptedGatewayTransport(first, second)
        val gateway = client(transport)
        val recovery = async(start = CoroutineStart.UNDISPATCHED) { gateway.sessionRecoveries.first() }
        gateway.connect()
        create(gateway, first, "local-1")
        first.inject(GatewayFrames.authorityEvent("message.delta", "local-1", 1, "epoch-1", """{"text":"a"}"""))
        first.sever()
        val resume = second.next()
        second.inject(GatewayFrames.result(resume.id, GatewayFrames.authoritySnapshot("local-1", replayEpoch = "epoch-9", lastSequence = 7)))
        gateway.awaitConnectedAfter(transport, 2)
        assertEquals(GatewaySessionRecovery.ReplayTruncated("local-1"), withTimeout(3_000) { recovery.await() })
        // Nothing was replayed: the next frame is the app's.
        val ping = async { gateway.methods.ping(PingParams()) }
        val frame = second.next()
        assertEquals("ping", frame.method)
        second.inject(GatewayFrames.result(frame.id, """{"pong":true}"""))
        ping.await()
        // Events of the new epoch continue from its snapshot.
        val delta = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.first { it.type == "message.delta" } }
        second.inject(GatewayFrames.authorityEvent("message.delta", "local-1", 7, "epoch-9", """{"text":"old"}"""))
        second.inject(GatewayFrames.authorityEvent("message.delta", "local-1", 8, "epoch-9", """{"text":"new"}"""))
        assertEquals(8L, withTimeout(3_000) { delta.await() }.seq)
        gateway.disconnect()
    }

    @Test
    fun aReplayGapResumesTheSession() = runTest {
        val socket = authoritySocket()
        val gateway = client(ScriptedGatewayTransport(socket))
        val recovery = async(start = CoroutineStart.UNDISPATCHED) { gateway.sessionRecoveries.first() }
        gateway.connect()
        create(gateway, socket, "local-1")
        socket.inject(GatewayFrames.event("session.replay_gap", "local-1", payload = """{"replay_epoch":"epoch-1","latest_seq":40}"""))
        val resume = socket.next()
        assertEquals("session.resume", resume.method)
        socket.inject(GatewayFrames.result(resume.id, GatewayFrames.authoritySnapshot("local-1", lastSequence = 40)))
        // It replays from the watermark first; only what the authority no longer holds is a gap.
        val since = socket.next()
        assertEquals("session.events.since", since.method)
        assertEquals(0L, since.fields["last_sequence"]?.jsonPrimitive?.long)
        socket.inject(GatewayFrames.result(since.id, """{"events":[],"latest_seq":40,"last_sequence":40,"epoch":"epoch-1",
            "replay_epoch":"epoch-1","truncated":true,"snapshot_required":true,"count":0}"""))
        assertEquals(GatewaySessionRecovery.ReplayTruncated("local-1"), withTimeout(3_000) { recovery.await() })
        gateway.disconnect()
    }

    @Test
    fun aRenameAlsoReachesLegacyTitleListeners() = runTest {
        val socket = authoritySocket()
        val gateway = client(ScriptedGatewayTransport(socket))
        val title = async(start = CoroutineStart.UNDISPATCHED) { gateway.events.first { it.type == "session.title" } }
        gateway.connect()
        create(gateway, socket, "local-1")
        socket.inject(GatewayFrames.authorityEvent("session.updated", "local-1", 1, "epoch-1",
            """{"session_id":"local-1","revision":1,"execution_generation":0,"operation":"rename","title":"Renamed"}"""))
        val payload = assertIs<GatewayEventPayload.SessionTitle>(withTimeout(3_000) { title.await() }.payload)
        assertEquals("Renamed", payload.payload.title)
        gateway.disconnect()
    }

    @Test
    fun restoredSnapshotPromptsAreAnsweredByRPC() = runTest {
        val socket = authoritySocket()
        val gateway = client(ScriptedGatewayTransport(socket), restoresRequests = false)
        gateway.setServerRequestHandler { ServerRequestResult.Approval(ApprovalResult(ApprovalChoice.Deny)) }
        gateway.connect()
        val resumed = async { gateway.methods.session.resume(SessionResumeParams("local-1", lazy = true)) }
        val frame = socket.next()
        assertFalse("lazy" in frame.fields)
        socket.inject(GatewayFrames.result(frame.id, GatewayFrames.authoritySnapshot("local-1", generation = 3, prompts =
            """[{"kind":"approval","prompt_id":"p7","execution_generation":3,"choices":["once","deny"],"command":"ls"}]""")))
        val snapshot = resumed.await()
        // Nothing is put to the handler until the app asks.
        val open = snapshot.openRequests.orEmpty()
        assertEquals(listOf("p7"), open.map { it.id })
        gateway.restoreServerRequests(open)
        val answer = socket.next()
        assertEquals("approval.respond", answer.method)
        assertEquals("p7", answer.text("prompt_id"))
        assertEquals(3, answer.fields["execution_generation"]?.jsonPrimitive?.long)
        assertEquals("deny", answer.text("choice"))
        gateway.disconnect()
    }

    @Test
    fun aSudoRequestIsAnsweredOnTheSocketForRequestAnswer() = runTest {
        val socket = authoritySocket()
        val gateway = client(ScriptedGatewayTransport(socket))
        val waiting = CompletableDeferred<Unit>()
        gateway.setServerRequestHandler {
            waiting.complete(Unit)
            awaitCancellation()
        }
        gateway.connect()
        socket.inject(GatewayFrames.serverRequest("srq-1", "sudo", """{"session_id":"local-1","command":"apt update"}"""))
        withTimeout(3_000) { waiting.await() }
        val answered = gateway.methods.request.answer(RequestAnswerParams("srq-1",
            mapOf("value" to Json.parseToJsonElement("\"pw\""))))
        assertEquals("ok", answered.status.rawValue)
        val frame = SentAnswer(withTimeout(3_000) { socket.sent.receive() })
        assertEquals("srq-1", frame.id)
        assertEquals(Json.encodeToJsonElement(ValueResult.serializer(), ValueResult("pw")), frame.result)
        gateway.disconnect()
    }

    @Test
    fun aClosedRuntimeIsNotReboundWhateverTheCloseAnswered() = runTest {
        val first = ScriptedGatewaySocket()
        val second = ScriptedGatewaySocket()
        val transport = ScriptedGatewayTransport(first, second)
        val gateway = client(transport)
        gateway.connect()
        val created = async { gateway.methods.session.create(SessionCreateParams()) }
        val frame = first.next()
        first.inject(GatewayFrames.result(frame.id, """{"session_id":"rt","stored_session_id":"st","message_count":0,"messages":[],"info":{}}"""))
        created.await()
        val closing = async { runCatching { gateway.methods.session.close(SessionCloseParams("rt")) } }
        val close = first.next()
        first.inject(GatewayFrames.error(close.id, 4001, "session not found"))
        assertIs<HermesGatewayException.RPC>(closing.await().exceptionOrNull())
        first.sever()
        gateway.awaitConnectedAfter(transport, 2)
        val ping = async { gateway.methods.ping(PingParams()) }
        val next = second.next()
        assertEquals("ping", next.method)
        second.inject(GatewayFrames.result(next.id, """{"pong":true}"""))
        ping.await()
        assertNull(gateway.runtime?.instanceId)
        gateway.disconnect()
    }

    @Test
    fun anAuthorityThatCannotDescribeItselfIsTreatedAsLegacy() = runTest {
        val socket = ScriptedGatewaySocket()
        socket.inject(GatewayFrames.gatewayReady(sessionAuthority = true))
        val gateway = client(ScriptedGatewayTransport(socket))
        val connecting = async { gateway.connect() }
        val describe = socket.next()
        assertEquals("runtime.describe", describe.method)
        socket.inject(GatewayFrames.error(describe.id, -32601, "unknown method: runtime.describe"))
        connecting.await()
        assertFalse(gateway.sessionAuthority)
        gateway.disconnect()
    }

    @Test
    fun anAnswerFromElsewhereEndsTheHandlerStillAsking() = runTest {
        val socket = authoritySocket()
        val gateway = client(ScriptedGatewayTransport(socket))
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        gateway.setServerRequestHandler {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        gateway.connect()
        assertTrue(gateway.sessionAuthority)
        create(gateway, socket, "local-1")
        socket.inject(GatewayFrames.authorityEvent("approval.request", "local-1", 1, "epoch-1",
            """{"kind":"approval","prompt_id":"p1","execution_generation":1,"choices":["once","deny"]}""", generation = 1))
        withTimeout(3_000) { started.await() }
        // Answered from a notification, through the legacy method.
        val responding = async {
            gateway.methods.approval.respond(hermes.api.generated.gateway.ApprovalRespondParams("local-1",
                choice = Patch.Value("once"), requestId = Patch.Value("p1")))
        }
        val frame = socket.next()
        assertEquals("approval.respond", frame.method)
        socket.inject(GatewayFrames.result(frame.id, """{"status":"resolved","prompt_id":"p1"}"""))
        assertEquals(1L, responding.await().resolved)
        withTimeout(3_000) { cancelled.await() }
        gateway.disconnect()
    }
}
