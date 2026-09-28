package hermes.api.live

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import hermes.api.generated.gateway.ApprovalChoice
import hermes.api.generated.gateway.ApprovalResult
import hermes.api.generated.gateway.ClarifyResult
import hermes.api.generated.gateway.GatewayErrorKind
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.gateway.GatewayKnownError
import hermes.api.generated.gateway.MessageCompletePayloadText
import hermes.api.generated.gateway.PingParams
import hermes.api.generated.gateway.ProfileNameParams
import hermes.api.generated.gateway.PromptSubmitParams
import hermes.api.generated.gateway.ServerRequest
import hermes.api.generated.gateway.ServerRequestResult
import hermes.api.generated.gateway.SessionActivateParams
import hermes.api.generated.gateway.SessionCloseParams
import hermes.api.generated.gateway.SessionCreateParams
import hermes.api.generated.gateway.SessionCwdSetParams
import hermes.api.generated.gateway.SessionInterruptParams
import hermes.api.generated.gateway.SessionListParams
import hermes.api.generated.gateway.SessionResumeParams
import hermes.api.generated.gateway.SpawnTreeLoadParams
import hermes.api.generated.gateway.ValueResult
import hermes.api.generated.rest.ProfileActiveUpdate
import hermes.api.generated.rest.VoiceLiveStatusResponseMode
import hermes.api.runtime.GatewayConnection
import hermes.api.runtime.GatewayConnectionState
import hermes.api.runtime.GatewayCredential
import hermes.api.runtime.GatewayHTTPTransport
import hermes.api.runtime.GatewayLogger
import hermes.api.runtime.GatewayNetworkMonitor
import hermes.api.runtime.GatewaySessionRecovery
import hermes.api.runtime.GatewayTransport
import hermes.api.runtime.HermesAuth
import hermes.api.runtime.HermesDashboardAddress
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.HermesREST
import hermes.api.runtime.HermesRESTConfiguration
import hermes.api.runtime.KtorGatewayTransport
import hermes.api.runtime.KtorRESTTransport
import hermes.api.runtime.LocalTokenAuth
import hermes.api.runtime.Patch
import hermes.api.runtime.kind
import hermes.api.runtime.known
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies

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
    /** `stress` runs the stress scenarios against the stress harness's seeded server instead. */
    public val mode: String = values["HERMES_LIVE_MODE"] ?: ""
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
internal object Fixture {
    const val REPLY = "HermesAPI fixture reply."
    const val CLARIFY_PROMPT = "Ask which release channel to use for HermesAPI."
    const val CLARIFY_REPLY = "HermesAPI stable release selected."
    const val APPROVAL_PROMPT = "Try the fixture cleanup command and report whether it was approved."
    const val APPROVAL_COMMAND = "rm -rf /tmp/hermes-api-fixture-approval-target"
    const val APPROVAL_REPLY = "HermesAPI approval denied as expected."
    const val RECONNECT_PROMPT = "Stream the HermesAPI reconnect fixture slowly."
    val RECONNECT_REPLY = (1..16).joinToString(" ") { "part" + it.toString().padStart(2, '0') }
    const val ACCEPT_PROMPT = "Run the fixture command that needs approval and report the result."
    const val ACCEPT_COMMAND = "rm -rf /tmp/hermes-api-fixture-accepted-target"
    const val ACCEPT_REPLY = "HermesAPI approval accepted."
    const val SECRET_PROMPT = "Load the HermesAPI fixture skill that needs a secret."
    const val SECRET_ENV_VAR = "HERMES_API_FIXTURE_SECRET"
    const val SECRET_REPLY = "HermesAPI secret request answered."
    const val WITHDRAWN_PROMPT = "Ask a HermesAPI question that will be withdrawn."
    const val WITHDRAWN_QUESTION = "Which HermesAPI question will be withdrawn?"
    const val GREETING_PROMPT = "Reply with a short greeting."
    const val LONG_PROMPT = "Stream the HermesAPI long fixture."
    const val PACED_PROMPT = "Stream the HermesAPI paced fixture."
}

public object LiveScenarios {
    /** Runs the smoke scenario and, when the harness exposes its control endpoint, the reconnect scenarios. */
    public suspend fun run(environment: LiveScenarioEnvironment) {
        if (environment.mode == "stress") return StressScenarios.run(environment)
        val observations = LiveObservations()
        smoke(environment, observations)
        val control = environment.control ?: return
        val faults = FaultControl(control)
        environment.token?.let { token ->
            val scenario = faults.restScenario()
            KtorRESTTransport().use { transport ->
                RESTScenario.run(scenario.getValue("calls").jsonArray, environment.url, LocalTokenAuth(token), transport,
                    observations)
            }
            (scenario["gated"] as? JsonObject)?.let { gated ->
                // Its own cookie storage: the gated calls sign in with a browser cookie session.
                KtorRESTTransport(HttpClient(CIO) { followRedirects = false; install(HttpCookies) }).use { transport ->
                    RESTScenario.run(gated.getValue("calls").jsonArray, URI(gated.getValue("url").jsonPrimitive.content),
                        null, transport, observations)
                }
                val username = gated["username"]?.jsonPrimitive?.contentOrNull
                val password = gated["password"]?.jsonPrimitive?.contentOrNull
                if (username != null && password != null) {
                    ConnectionScenarios.passwordSession(URI(gated.getValue("url").jsonPrimitive.content), username, password)
                }
            }
            (scenario["tls"] as? JsonObject)?.let { tls ->
                ConnectionScenarios.pinnedServer(URI(tls.getValue("url").jsonPrimitive.content),
                    tls.getValue("certificate").jsonPrimitive.content, token)
            }
        }
        if (environment.lifecycle) {
            GatewayScenario.run(faults.gatewayScenario().getValue("calls").jsonArray, environment, observations)
            reconnect(environment, faults, observations)
        }
        // Only a fully passing run reports what it exercised.
        faults.report(observations.report())
    }

    private suspend fun smoke(environment: LiveScenarioEnvironment, observations: LiveObservations) {
        val ktor = KtorGatewayTransport()
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(environment.url), environment.auth, ObservingTransport(ktor, observations), httpTransport = ktor,
            networkMonitor = environment.networkMonitor, logger = environment.logger,
        ))
        val requests = RequestLog()
        gateway.setServerRequestHandler { request ->
            when (request) {
                is ServerRequest.Clarify -> {
                    val questions = (request.params.questions as? Patch.Value)?.value
                        ?: throw LiveScenarioFailure("Missing clarification questions")
                    check(questions.size == 1) { "Unexpected clarification request" }
                    if (questions[0].question == Fixture.WITHDRAWN_QUESTION) {
                        // Left open until Hermes withdraws it; the gateway then cancels this handler.
                        requests.isOpen = true
                        try {
                            delay(300_000)
                        } catch (error: CancellationException) {
                            requests.isWithdrawn = true
                            throw error
                        }
                        throw LiveScenarioFailure("The open question was never withdrawn")
                    }
                    check(questions[0].question == "Which release channel?") { "Unexpected clarification question" }
                    ServerRequestResult.Clarify(ClarifyResult(answers = mapOf(questions[0].qid to "Stable")))
                }
                is ServerRequest.Approval -> when (request.params.command) {
                    Fixture.APPROVAL_COMMAND -> ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Deny))
                    Fixture.ACCEPT_COMMAND -> ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Once))
                    else -> throw LiveScenarioFailure("Unexpected approval command")
                }
                is ServerRequest.Secret -> {
                    check(request.params.envVar == Fixture.SECRET_ENV_VAR) { "Unexpected secret request" }
                    requests.secrets.incrementAndGet()
                    // An empty value skips the variable, so Hermes stores nothing and asks again next run.
                    ServerRequestResult.Secret(ValueResult(value = ""))
                }
                else -> throw LiveScenarioFailure("Unexpected server request")
            }
        }
        try {
            gateway.connect()
            check(gateway.methods.ping(PingParams()).pong) { "Gateway ping returned false" }
            gateway.methods.gateway.capabilities(PingParams())
            if (environment.lifecycle) {
                // Only against the tagged server: the ticket probe answers every method as unknown.
                refusals(gateway)
                lifecycle(gateway, environment, requests)
            }
        } finally {
            gateway.disconnect()
            ktor.close()
        }
    }

    /** Calls Hermes refuses, and the reviewed meaning each refusal must carry (`spec/gateway-errors.yaml`). */
    private suspend fun refusals(gateway: HermesGateway) {
        val missing = "hermes-api-missing-session"
        refused(GatewayKnownError.UNKNOWN_METHOD, GatewayErrorKind.UNSUPPORTED) {
            gateway.call("hermes.api.no_such_method", PingParams(), PingParams.serializer(), JsonElement.serializer())
        }
        refused(GatewayKnownError.INVALID_PARAMS, GatewayErrorKind.INVALID_REQUEST) {
            val params = JsonObject(mapOf("session_id" to JsonPrimitive(missing), "last_seen" to JsonPrimitive("latest")))
            gateway.call("session.events.since", params, JsonObject.serializer(), JsonElement.serializer())
        }
        refused(GatewayKnownError.SESSION_NOT_FOUND, GatewayErrorKind.NOT_FOUND) {
            gateway.methods.session.activate(SessionActivateParams(missing, omitMessages = true))
        }
        refused(GatewayKnownError.SESSION_NOT_FOUND, GatewayErrorKind.NOT_FOUND) {
            gateway.methods.session.resume(SessionResumeParams(missing, omitMessages = true))
        }
        refused(GatewayKnownError.SESSION_NOT_FOUND, GatewayErrorKind.NOT_FOUND) {
            gateway.methods.prompt.submit(PromptSubmitParams(sessionId = missing, text = JsonPrimitive("Refused")))
        }
        refused(GatewayKnownError.PROFILE_NOT_FOUND, GatewayErrorKind.NOT_FOUND) {
            gateway.methods.profiles.describe(ProfileNameParams(name = Patch.Value("hermes-api-missing-profile")))
        }
        refused(GatewayKnownError.PARAMS_REJECTED, GatewayErrorKind.INVALID_REQUEST) {
            gateway.methods.spawnTree.load(SpawnTreeLoadParams(path = ""))
        }
    }

    private suspend fun refused(known: GatewayKnownError, kind: GatewayErrorKind, call: suspend () -> Unit) {
        try {
            call()
        } catch (error: HermesGatewayException.RPC) {
            if (error.known != known || error.kind != kind) {
                throw LiveScenarioFailure("Expected Hermes to refuse with $known ($kind), got ${error.code} ${error.message}")
            }
            return
        }
        throw LiveScenarioFailure("Expected Hermes to refuse with $known, but the call succeeded")
    }

    private suspend fun lifecycle(
        gateway: HermesGateway, environment: LiveScenarioEnvironment, requests: RequestLog,
    ): Unit = coroutineScope {
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
        val accepted = runTurn(gateway, session.sessionId, Fixture.ACCEPT_PROMPT, "terminal")
        accepted.expectReply(Fixture.ACCEPT_REPLY)
        check((accepted.toolResult as? JsonObject)?.get("exit_code")?.jsonPrimitive?.content == "0") {
            "Approved terminal command did not run: ${accepted.toolResult}"
        }
        runTurn(gateway, session.sessionId, Fixture.SECRET_PROMPT, "skill_view").expectReply(Fixture.SECRET_REPLY)
        check(requests.secrets.get() == 1) { "Hermes did not ask for the skill's secret" }
        withdrawnQuestion(gateway, session.sessionId, requests)
        busySession(gateway, session.sessionId)
        gateway.methods.session.list(SessionListParams())
        check(gateway.methods.session.close(SessionCloseParams(session.sessionId)).closed) {
            "Gateway session did not close"
        }
        val token = environment.token ?: throw LiveScenarioFailure("REST smoke needs the local token")
        val restTransport = KtorRESTTransport()
        try {
            val rest = HermesREST(HermesRESTConfiguration(HermesDashboardAddress(environment.url), LocalTokenAuth(token), transport = restTransport))
            val voice = rest.methods.audio.voiceLiveStatus(profile = "default")
            check(voice.ok && voice.mode == VoiceLiveStatusResponseMode.Chained && voice.model.isNotEmpty() &&
                voice.voice.isNotEmpty()) {
                "Unexpected voice status"
            }
            val profile = rest.methods.profiles.active()
            check(profile.active == "default" && profile.current == "default") { "Unexpected active profile" }
            val selected = rest.methods.profiles.setActive(ProfileActiveUpdate("default"))
            check(selected.ok && selected.active == "default") { "Could not select the default profile" }
            check(rest.methods.sessions.emptyCount(profile = "default").count >= 0) { "Invalid empty session count" }
        } finally {
            restTransport.close()
        }
    }

    /** Leaves a question open and interrupts the turn: Hermes withdraws it with `request.cancel`, and the
     *  gateway cancels the handler still waiting on the user. */
    private suspend fun withdrawnQuestion(gateway: HermesGateway, sessionId: String, requests: RequestLog): Unit = coroutineScope {
        val ended = async(start = CoroutineStart.UNDISPATCHED) {
            var withdrawn = false
            gateway.events.first { event ->
                if (event.sessionId != sessionId) return@first false
                when (val payload = event.payload) {
                    is GatewayEventPayload.RequestCancel -> {
                        if (payload.payload.method != "clarify") throw LiveScenarioFailure("Unexpected withdrawn request")
                        withdrawn = true
                        false
                    }
                    is GatewayEventPayload.MessageComplete -> {
                        if (!withdrawn) throw LiveScenarioFailure("The turn ended without withdrawing the question")
                        true
                    }
                    else -> false
                }
            }
        }
        gateway.methods.prompt.submit(PromptSubmitParams(sessionId = sessionId, text = JsonPrimitive(Fixture.WITHDRAWN_PROMPT)))
        deadline(60_000, "the question to reach the handler") { while (!requests.isOpen) delay(100) }
        gateway.methods.session.interrupt(SessionInterruptParams(sessionId))
        deadline(30_000, "Hermes to withdraw the question and end the turn") { ended.await() }
        deadline(10_000, "the gateway to cancel the waiting handler") { while (!requests.isWithdrawn) delay(100) }
    }

    /** A call Hermes refuses while a turn runs, and the interrupt that ends the turn. */
    private suspend fun busySession(gateway: HermesGateway, sessionId: String): Unit = coroutineScope {
        val streaming = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.first { it.sessionId == sessionId && it.payload is GatewayEventPayload.MessageDelta }
        }
        val ended = async(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.first { it.sessionId == sessionId && it.payload is GatewayEventPayload.MessageComplete }
        }
        gateway.methods.prompt.submit(PromptSubmitParams(sessionId = sessionId, text = JsonPrimitive(Fixture.PACED_PROMPT)))
        deadline(60_000, "the paced turn to start streaming") { streaming.await() }
        refused(GatewayKnownError.SESSION_BUSY, GatewayErrorKind.BUSY) {
            gateway.methods.session.cwdSet(SessionCwdSetParams(sessionId = sessionId, cwd = "/tmp"))
        }
        gateway.methods.session.interrupt(SessionInterruptParams(sessionId))
        deadline(30_000, "the interrupted turn to end") { ended.await() }
    }

    /** Loses the socket mid-stream, silently, and with a question open, then resumes the session after a
     *  server restart. The heartbeat is shortened so a stalled socket is detected within seconds. */
    private suspend fun reconnect(
        environment: LiveScenarioEnvironment, faults: FaultControl, observations: LiveObservations,
    ): Unit = coroutineScope {
        val ktor = KtorGatewayTransport()
        val transport = ObservingTransport(ktor, observations)
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(environment.url), environment.auth, transport, httpTransport = ktor, reconnectDelayMillis = { 250 },
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

            // An outage longer than Hermes' 20 s grace drops the session while its turn keeps writing the reply
            // to the replay buffer; the gateway resumes the session and still delivers the whole turn.
            val outageRecovery = async(start = CoroutineStart.UNDISPATCHED) { gateway.sessionRecoveries.first() }
            val outage = runTurn(gateway, resumed.sessionId, Fixture.RECONNECT_PROMPT, null,
                onFirstDelta = { faults.drop(25_000) })
            outage.expectReply(Fixture.RECONNECT_REPLY)
            outage.expectContiguousSequence()
            val dropped = deadline(10_000, "the recovery after the long outage") { outageRecovery.await() }
            if (dropped !is GatewaySessionRecovery.Resumed || dropped.previousSessionId != resumed.sessionId) {
                throw LiveScenarioFailure("Expected ${resumed.sessionId} to be resumed after the outage, got $dropped")
            }
        } finally {
            gateway.disconnect()
            ktor.close()
        }
    }

    internal suspend fun runTurn(
        gateway: HermesGateway, sessionId: String, prompt: String, tool: String?,
        deadlineMillis: Long = 120_000, onFirstDelta: (suspend () -> Unit)? = null,
    ): Turn = coroutineScope {
        val turn = Turn(tool)
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            // Generous: Hermes builds the agent on the first turn, which is slow on a cold CI runner.
            deadline(deadlineMillis, "the turn for \"$prompt\" to complete") {
                gateway.events.first { event ->
                    if (event.sessionId != sessionId) {
                        turn.otherSessions += event.sessionId ?: "none"
                        return@first false
                    }
                    turn.types[event.type] = (turn.types[event.type] ?: 0) + 1
                    if (event.type == "message.start" || event.type == "message.delta") turn.started = true
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
        try {
            val submitted = gateway.methods.prompt.submit(PromptSubmitParams(sessionId = sessionId, text = JsonPrimitive(prompt)))
            if (submitted.status == null) throw LiveScenarioFailure("Gateway rejected the prompt")
        } catch (error: HermesGatewayException.Transport) {
            turn.notes += "submit lost its response"
            // The connection dropped with the submission unanswered: Hermes may have started the turn. As an
            // app should, watch the replayed events and submit again only if the turn never shows up.
            val resubmit = launch {
                delay(15_000)
                if (!turn.started) turn.notes += "resubmitted"
                if (!turn.started) gateway.methods.prompt.submit(PromptSubmitParams(sessionId = sessionId, text = JsonPrimitive(prompt)))
            }
            completion.invokeOnCompletion { resubmit.cancel() }
        }
        val states = launch { gateway.connectionStates.collect { turn.notes += "state $it" } }
        val recoveries = launch { gateway.sessionRecoveries.collect { turn.notes += "recovery $it" } }
        try {
            completion.await()
        } catch (error: LiveScenarioFailure) {
            if (error.message?.startsWith("Timed out") == true) throw LiveScenarioFailure("${error.message}; ${turn.trace}")
            throw error
        } finally {
            states.cancel()
            recoveries.cancel()
        }
        turn
    }
}

internal class Turn(val tool: String?) {
    @Volatile var started = false
    var sawStart = false
    var sawToolStart = false
    var sawToolComplete = false
    var toolResult: JsonElement? = null
    val streamed = StringBuilder()
    var reply: String? = null
    val sequence = mutableListOf<Long>()
    var replayedEvents = 0
    val types = sortedMapOf<String, Int>()
    val otherSessions = sortedSetOf<String>()

    /** What arrived for the turn, for failure messages. */
    val notes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val trace: String get() = "events $types, replayed $replayedEvents, seq ${sequence.firstOrNull()}..." +
        "${sequence.lastOrNull()} (${sequence.size}), other sessions $otherSessions; last: " +
        synchronized(notes) { notes.takeLast(25).joinToString(" | ") }

    fun expectReply(expected: String) {
        if (reply != expected) throw LiveScenarioFailure("Expected reply \"$expected\", got \"${reply ?: "nothing"}\"")
        if (!sawStart) throw LiveScenarioFailure("No message.start before \"$expected\"")
        if (streamed.toString().trim() != reply) {
            throw LiveScenarioFailure("Streamed text \"${streamed.take(200)}\" differs from the final reply; $trace")
        }
        if (tool != null && !(sawToolStart && sawToolComplete)) {
            throw LiveScenarioFailure("The $tool tool did not start and complete")
        }
    }

    /** Every sequenced event arrives exactly once and in order across a reconnect. */
    fun expectContiguousSequence() {
        val first = sequence.firstOrNull()
        if (first == null || sequence != (first until first + sequence.size).toList()) {
            throw LiveScenarioFailure("Session events were lost, duplicated or reordered: $sequence; $trace")
        }
    }
}

/** Calls the harness control endpoint that severs sockets or restarts the tagged server. */
internal class FaultControl(private val base: URI) {
    suspend fun drop(holdMillis: Int) = post("drop?hold_ms=$holdMillis")

    suspend fun blackhole() = post("blackhole")

    suspend fun restart() = post("restart")

    /** Sends the run's measured coverage evidence. */
    suspend fun report(json: String) = post("report", json)

    /** Shapes the proxied connections with a named network profile (`harness/faults.py`). */
    suspend fun conditions(profile: String) = post("conditions?profile=$profile")

    /** Sends a stress run's timings in milliseconds. */
    suspend fun metrics(json: String) = post("metrics", json)

    /** The dataset the stress harness seeded. */
    suspend fun stressDataset(): JsonObject = get("stress")

    /** The REST scenario the harness recorded fixtures from. */
    suspend fun restScenario(): JsonObject = get("rest-scenario")

    /** The gateway scenario the harness recorded fixtures from. */
    suspend fun gatewayScenario(): JsonObject = get("gateway-scenario")

    private suspend fun get(path: String): JsonObject = withContext(Dispatchers.IO) {
        val connection = URL(base.resolve("/$path").toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            if (connection.responseCode != 200) throw LiveScenarioFailure("Harness control $path failed")
            Json.parseToJsonElement(connection.inputStream.use { it.readBytes().decodeToString() }).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun post(path: String, json: String? = null): Unit = withContext(Dispatchers.IO) {
        val connection = URL(base.resolve("/$path").toString()).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 180_000
            if (json != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(json.toByteArray()) }
            }
            if (connection.responseCode != 204) {
                val reason = connection.errorStream?.use { it.readBytes().decodeToString().take(2000) }.orEmpty()
                throw LiveScenarioFailure("Harness control $path failed: $reason")
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal suspend fun <T> deadline(millis: Long, waitingFor: String, block: suspend () -> T): T = try {
    withTimeout(millis) { block() }
} catch (_: kotlinx.coroutines.TimeoutCancellationException) {
    throw LiveScenarioFailure("Timed out waiting for $waitingFor")
}

/** What the live handler saw of the requests the lifecycle provokes. */
internal class RequestLog {
    @Volatile var isOpen = false
    @Volatile var isWithdrawn = false
    val secrets = AtomicInteger()
}
