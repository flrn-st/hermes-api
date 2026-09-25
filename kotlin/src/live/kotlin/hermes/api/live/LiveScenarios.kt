package hermes.api.live

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import hermes.api.generated.gateway.ApprovalChoice
import hermes.api.generated.gateway.ApprovalResult
import hermes.api.generated.gateway.ClarifyResult
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.MessageCompletePayloadText
import hermes.api.generated.gateway.PingParams
import hermes.api.generated.gateway.PromptSubmitParams
import hermes.api.generated.gateway.ServerRequest
import hermes.api.generated.gateway.ServerRequestResult
import hermes.api.generated.gateway.SessionCloseParams
import hermes.api.generated.gateway.SessionCreateParams
import hermes.api.generated.gateway.SessionListParams
import hermes.api.generated.rest.ProfilesSetActiveRequest
import hermes.api.runtime.GatewayConnection
import hermes.api.runtime.GatewayConnectionState
import hermes.api.runtime.GatewaySessionRecovery
import hermes.api.runtime.GatewayCredential
import hermes.api.runtime.GatewayHTTPTransport
import hermes.api.runtime.GatewayLogger
import hermes.api.runtime.GatewayNetworkMonitor
import hermes.api.runtime.GatewayTransport
import hermes.api.runtime.HermesAuth
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.HermesREST
import hermes.api.runtime.HermesRESTConfiguration
import hermes.api.runtime.KtorGatewayTransport
import hermes.api.runtime.KtorRESTTransport
import hermes.api.runtime.LocalTokenAuth
import hermes.api.runtime.Patch

/** Live scenario inputs, read from `HERMES_LIVE_*` variables (or Android instrumentation arguments).
 *  Platforms pass their own network monitor and logger so the scenarios exercise them on the device. */
public class LiveScenarioEnvironment(
    values: Map<String, String>,
    public val networkMonitor: GatewayNetworkMonitor? = null,
    public val logger: GatewayLogger = GatewayLogger.None,
) {
    public val url: URI = URI(values["HERMES_LIVE_URL"] ?: throw LiveScenarioFailure("HERMES_LIVE_URL is required"))
    public val token: String? = values["HERMES_LIVE_TOKEN"]?.takeIf { it.isNotEmpty() }
    private val ticket: String? = values["HERMES_LIVE_TICKET"]?.takeIf { it.isNotEmpty() }
    public val lifecycle: Boolean = values["HERMES_LIVE_LIFECYCLE"] == "1"
    public val control: URI? = values["HERMES_LIVE_CONTROL"]?.takeIf { it.isNotEmpty() }?.let(::URI)

    public val auth: HermesAuth = when {
        ticket != null -> object : HermesAuth {
            override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential =
                GatewayCredential.Ticket(ticket, emptyMap())
        }
        token != null -> LocalTokenAuth(token)
        else -> throw LiveScenarioFailure("HERMES_LIVE_TICKET or HERMES_LIVE_TOKEN is required")
    }
}

public class LiveScenarioFailure(message: String) : Exception(message)

/** Fixture replies produced by the harness model stub (`harness/stub_llm.py`). */
private object Fixture {
    const val REPLY = "HermesAPI fixture reply."
    const val CLARIFY_PROMPT = "Ask which release channel to use for HermesAPI."
    const val CLARIFY_REPLY = "HermesAPI stable release selected."
    const val APPROVAL_PROMPT = "Try the fixture cleanup command and report whether it was approved."
    const val APPROVAL_COMMAND = "rm -rf /tmp/hermes-api-fixture-approval-target"
    const val APPROVAL_REPLY = "HermesAPI approval denied as expected."
    const val RECONNECT_PROMPT = "Stream the HermesAPI reconnect fixture slowly."
    val RECONNECT_REPLY = (1..16).joinToString(" ") { "part" + it.toString().padStart(2, '0') }
}

public object LiveScenarios {
    /** Runs the smoke scenario and, when the harness exposes its control endpoint, the reconnect scenarios. */
    public suspend fun run(environment: LiveScenarioEnvironment) {
        val observations = LiveObservations()
        smoke(environment, observations)
        val control = environment.control ?: return
        val faults = FaultControl(control)
        if (environment.lifecycle) reconnect(environment, faults, observations)
        // Only a fully passing run reports what it exercised.
        faults.report(observations.report())
    }

    private suspend fun smoke(environment: LiveScenarioEnvironment, observations: LiveObservations) {
        val ktor = KtorGatewayTransport()
        val gateway = HermesGateway(HermesGatewayConfiguration(
            environment.url, environment.auth, ObservingTransport(ktor, observations), httpTransport = ktor,
            networkMonitor = environment.networkMonitor, logger = environment.logger,
        ))
        gateway.setServerRequestHandler { request ->
            when (request) {
                is ServerRequest.Clarify -> {
                    val questions = (request.params.questions as? Patch.Value)?.value
                        ?: throw LiveScenarioFailure("Missing clarification questions")
                    check(questions.size == 1 && questions[0].question == "Which release channel?") {
                        "Unexpected clarification question"
                    }
                    ServerRequestResult.Clarify(ClarifyResult(answers = mapOf(questions[0].qid to "Stable")))
                }
                is ServerRequest.Approval -> {
                    check(request.params.command == Fixture.APPROVAL_COMMAND) { "Unexpected approval command" }
                    ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Deny))
                }
                else -> throw LiveScenarioFailure("Unexpected server request")
            }
        }
        try {
            gateway.connect()
            check(gateway.methods.ping(PingParams()).pong) { "Gateway ping returned false" }
            gateway.methods.gateway.capabilities(PingParams())
            if (environment.lifecycle) lifecycle(gateway, environment)
        } finally {
            gateway.disconnect()
            ktor.close()
        }
    }

    private suspend fun lifecycle(gateway: HermesGateway, environment: LiveScenarioEnvironment): Unit = coroutineScope {
        // Hermes broadcasts sessions.changed (at most every 2 s) once a turn writes the session store.
        val listChanged = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.first { it.payload is GatewayEventPayload.SessionsChanged }
        }
        val session = gateway.methods.session.create(SessionCreateParams(
            cwd = Patch.Value("/tmp"), title = Patch.Value("HermesAPI live session"), closeOnDisconnect = true,
        ))
        runTurn(gateway, session.sessionId, "Reply with a short greeting.", null).expectReply(Fixture.REPLY)
        deadline(15_000, "a sessions.changed broadcast") { listChanged.await() }
        runTurn(gateway, session.sessionId, Fixture.CLARIFY_PROMPT, "clarify").expectReply(Fixture.CLARIFY_REPLY)
        val denied = runTurn(gateway, session.sessionId, Fixture.APPROVAL_PROMPT, "terminal")
        denied.expectReply(Fixture.APPROVAL_REPLY)
        val result = denied.toolResult as? JsonObject
        check(result?.get("status")?.jsonPrimitive?.content == "blocked" &&
            result["exit_code"]?.jsonPrimitive?.content == "-1") { "Denied terminal command was not blocked" }
        gateway.methods.session.list(SessionListParams())
        check(gateway.methods.session.close(SessionCloseParams(session.sessionId)).closed) {
            "Gateway session did not close"
        }
        val token = environment.token ?: throw LiveScenarioFailure("REST smoke needs the local token")
        val restTransport = KtorRESTTransport()
        try {
            val rest = HermesREST(HermesRESTConfiguration(environment.url,
                headers = { mapOf("X-Hermes-Session-Token" to token) }, transport = restTransport))
            val voice = rest.methods.audio.voiceLiveStatus(profile = "default")
            check(voice.ok && voice.mode == "chained" && voice.model.isNotEmpty() && voice.voice.isNotEmpty()) {
                "Unexpected voice status"
            }
            val profile = rest.methods.profiles.active()
            check(profile.active == "default" && profile.current == "default") { "Unexpected active profile" }
            val selected = rest.methods.profiles.setActive(ProfilesSetActiveRequest("default"))
            check(selected.ok && selected.active == "default") { "Could not select the default profile" }
            check(rest.methods.sessions.emptyCount(profile = "default").count >= 0) { "Invalid empty session count" }
        } finally {
            restTransport.close()
        }
    }

    /** Loses the socket mid-stream, silently, and with a question open, then resumes the session after a
     *  server restart. The heartbeat is shortened so a stalled socket is detected within seconds. */
    private suspend fun reconnect(
        environment: LiveScenarioEnvironment, faults: FaultControl, observations: LiveObservations,
    ): Unit = coroutineScope {
        val ktor = KtorGatewayTransport()
        val transport = ObservingTransport(ktor, observations)
        val gateway = HermesGateway(HermesGatewayConfiguration(
            environment.url, environment.auth, transport, httpTransport = ktor, reconnectDelayMillis = { 250 },
            heartbeatIntervalMillis = 1_000, heartbeatDeadlineMillis = 4_000,
            networkMonitor = environment.networkMonitor, logger = environment.logger,
        ))
        suspend fun awaitReconnect(after: Int) {
            while (transport.reconnects <= after || gateway.connectionStates.value != GatewayConnectionState.Connected) {
                delay(100)
            }
        }
        val clarifications = AtomicInteger()
        gateway.setServerRequestHandler { request ->
            val questions = ((request as? ServerRequest.Clarify)?.params?.questions as? Patch.Value)?.value
                ?: throw LiveScenarioFailure("Unexpected server request during reconnect")
            if (clarifications.incrementAndGet() == 1) {
                // Drop the socket while the question is open; the rebind must re-deliver it.
                faults.drop(holdMillis = 1_000)
                delay(60_000)
            }
            ServerRequestResult.Clarify(ClarifyResult(answers = mapOf(questions[0].qid to "Stable")))
        }
        try {
            gateway.connect()
            val session = gateway.methods.session.create(SessionCreateParams(
                cwd = Patch.Value("/tmp"), title = Patch.Value("HermesAPI reconnect session"), closeOnDisconnect = false,
            ))

            val streamed = runTurn(gateway, session.sessionId, Fixture.RECONNECT_PROMPT, null) {
                faults.drop(holdMillis = 1_500)
            }
            streamed.expectReply(Fixture.RECONNECT_REPLY)
            if (streamed.replayedEvents == 0) throw LiveScenarioFailure("No event reached the client through reconnect replay")
            streamed.expectContiguousSequence()
            if (transport.reconnects < 1) throw LiveScenarioFailure("Gateway never reconnected")

            // A stalled socket reports nothing; the default heartbeat must replace it before the
            // server reaps the session, which the next turn on the same session then proves.
            val reconnectsBeforeStall = transport.reconnects
            faults.blackhole()
            deadline(20_000, "the heartbeat to replace a silently stalled socket") { awaitReconnect(reconnectsBeforeStall) }

            val clarified = runTurn(gateway, session.sessionId, Fixture.CLARIFY_PROMPT, "clarify")
            clarified.expectReply(Fixture.CLARIFY_REPLY)
            clarified.expectContiguousSequence()
            if (clarifications.get() != 2) {
                throw LiveScenarioFailure("The open clarification was not re-delivered after reconnect")
            }

            // A restart loses every live session; the gateway resumes this one from Hermes' storage.
            val reconnectsBeforeRestart = transport.reconnects
            val recovery = async(start = CoroutineStart.UNDISPATCHED) { gateway.sessionRecoveries.first() }
            faults.restart()
            val resumed = deadline(60_000, "the restarted server's session recovery") { recovery.await() }
            if (resumed !is GatewaySessionRecovery.Resumed || resumed.previousSessionId != session.sessionId ||
                resumed.storedSessionId != session.storedSessionId) {
                throw LiveScenarioFailure("Expected ${session.sessionId} to be resumed, got $resumed")
            }
            deadline(60_000, "the gateway to reconnect after the restart") { awaitReconnect(reconnectsBeforeRestart) }
            runTurn(gateway, resumed.sessionId, "Reply with a short greeting.", null).expectReply(Fixture.REPLY)
        } finally {
            gateway.disconnect()
            ktor.close()
        }
    }

    private suspend fun runTurn(
        gateway: HermesGateway, sessionId: String, prompt: String, tool: String?,
        onFirstDelta: (suspend () -> Unit)? = null,
    ): Turn = coroutineScope {
        val turn = Turn(tool)
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            // Generous: Hermes builds the agent on the first turn, which is slow on a cold CI runner.
            deadline(120_000, "the turn for \"$prompt\" to complete") {
                gateway.events.first { event ->
                    if (event.sessionId != sessionId) return@first false
                    event.seq?.let(turn.sequence::add)
                    if (event.replayed) turn.replayedEvents++
                    when (val payload = event.payload) {
                        GatewayEventPayload.MessageStart -> turn.sawStart = true
                        is GatewayEventPayload.ToolStart -> if (payload.payload.name == tool) turn.sawToolStart = true
                        is GatewayEventPayload.ToolComplete -> if (payload.payload.name == tool) {
                            turn.sawToolComplete = true
                            turn.toolResult = payload.payload.result
                        }
                        is GatewayEventPayload.MessageDelta -> {
                            val first = turn.streamed.isEmpty()
                            turn.streamed.append(payload.payload.text)
                            if (first) onFirstDelta?.invoke()
                        }
                        is GatewayEventPayload.MessageComplete ->
                            turn.reply = (payload.payload.text as? MessageCompletePayloadText.StringValue)?.value
                        is GatewayEventPayload.Error -> throw LiveScenarioFailure("Gateway reported an error during \"$prompt\"")
                        else -> Unit
                    }
                    event.type == "message.complete"
                }
            }
        }
        val submitted = gateway.methods.prompt.submit(PromptSubmitParams(sessionId = sessionId, text = JsonPrimitive(prompt)))
        if (submitted.status == null) throw LiveScenarioFailure("Gateway rejected the prompt")
        completion.await()
        turn
    }
}

private class Turn(val tool: String?) {
    var sawStart = false
    var sawToolStart = false
    var sawToolComplete = false
    var toolResult: JsonElement? = null
    val streamed = StringBuilder()
    var reply: String? = null
    val sequence = mutableListOf<Long>()
    var replayedEvents = 0

    fun expectReply(expected: String) {
        if (reply != expected) throw LiveScenarioFailure("Expected reply \"$expected\", got \"${reply ?: "nothing"}\"")
        if (!sawStart) throw LiveScenarioFailure("No message.start before \"$expected\"")
        if (streamed.toString().trim() != reply) {
            throw LiveScenarioFailure("Streamed text \"$streamed\" differs from the final reply")
        }
        if (tool != null && !(sawToolStart && sawToolComplete)) {
            throw LiveScenarioFailure("The $tool tool did not start and complete")
        }
    }

    /** Every sequenced event arrives exactly once and in order across a reconnect. */
    fun expectContiguousSequence() {
        val first = sequence.firstOrNull()
        if (first == null || sequence != (first until first + sequence.size).toList()) {
            throw LiveScenarioFailure("Session events were lost, duplicated or reordered: $sequence")
        }
    }
}

/** Calls the harness control endpoint that severs sockets or restarts the tagged server. */
private class FaultControl(private val base: URI) {
    suspend fun drop(holdMillis: Int) = post("drop?hold_ms=$holdMillis")

    suspend fun blackhole() = post("blackhole")

    suspend fun restart() = post("restart")

    /** Sends the run's measured coverage evidence. */
    suspend fun report(json: String) = post("report", json)

    private suspend fun post(path: String, json: String? = null): Unit = withContext(Dispatchers.IO) {
        val connection = URL(base.resolve("/$path").toString()).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 120_000
            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(json.toByteArray()) }
            }
            if (connection.responseCode != 204) throw LiveScenarioFailure("Harness control $path failed")
        } finally {
            connection.disconnect()
        }
    }
}

private suspend fun <T> deadline(millis: Long, waitingFor: String, block: suspend () -> T): T = try {
    withTimeout(millis) { block() }
} catch (_: kotlinx.coroutines.TimeoutCancellationException) {
    throw LiveScenarioFailure("Timed out waiting for $waitingFor")
}
