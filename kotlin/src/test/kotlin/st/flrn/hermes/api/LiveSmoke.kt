package st.flrn.hermes.api

import java.net.URI
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import st.flrn.hermes.api.generated.gateway.PingParams
import st.flrn.hermes.api.generated.gateway.PromptSubmitParams
import st.flrn.hermes.api.generated.gateway.GatewayEventPayload
import st.flrn.hermes.api.generated.gateway.MessageCompletePayloadText
import st.flrn.hermes.api.generated.gateway.SessionCreateParams
import st.flrn.hermes.api.generated.gateway.SessionListParams
import st.flrn.hermes.api.generated.gateway.SessionCloseParams
import st.flrn.hermes.api.generated.gateway.ClarifyResult
import st.flrn.hermes.api.generated.gateway.ApprovalChoice
import st.flrn.hermes.api.generated.gateway.ApprovalResult
import st.flrn.hermes.api.generated.gateway.ServerRequest
import st.flrn.hermes.api.generated.gateway.ServerRequestResult
import st.flrn.hermes.api.generated.rest.ProfilesSetActiveRequest
import st.flrn.hermes.api.runtime.HermesGateway
import st.flrn.hermes.api.runtime.HermesGatewayConfiguration
import st.flrn.hermes.api.runtime.HermesAuth
import st.flrn.hermes.api.runtime.GatewayCredential
import st.flrn.hermes.api.runtime.GatewayHTTPTransport
import st.flrn.hermes.api.runtime.LocalTokenAuth
import st.flrn.hermes.api.runtime.KtorGatewayTransport
import st.flrn.hermes.api.runtime.KtorRESTTransport
import st.flrn.hermes.api.runtime.HermesREST
import st.flrn.hermes.api.runtime.HermesRESTConfiguration
import st.flrn.hermes.api.runtime.Patch

/** Invoked by the dedicated Gradle smoke task against a tagged Hermes server. */
suspend fun main() {
    val url = System.getenv("HERMES_LIVE_URL") ?: error("HERMES_LIVE_URL is required")
    val ticket = System.getenv("HERMES_LIVE_TICKET")
    val auth: HermesAuth = if (ticket != null) {
        object : HermesAuth {
            override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential =
                GatewayCredential.Ticket(ticket, emptyMap())
        }
    } else {
        LocalTokenAuth(System.getenv("HERMES_LIVE_TOKEN") ?: error("HERMES_LIVE_TOKEN is required"))
    }
    val transport = KtorGatewayTransport()
    val gateway = HermesGateway(HermesGatewayConfiguration(URI(url), auth, transport))
    gateway.setServerRequestHandler { request ->
        when (request) {
            is ServerRequest.Clarify -> {
                val questions = (request.params.questions as? Patch.Value)?.value
                    ?: error("Missing clarification questions")
                check(questions.size == 1 && questions[0].question == "Which release channel?") {
                    "Unexpected clarification question"
                }
                ServerRequestResult.Clarify(ClarifyResult(answers = mapOf(questions[0].qid to "Stable")))
            }
            is ServerRequest.Approval -> {
                check(request.params.command == "rm -rf /tmp/hermes-api-fixture-approval-target") {
                    "Unexpected approval command"
                }
                ServerRequestResult.Approval(ApprovalResult(choice = ApprovalChoice.Deny))
            }
            else -> error("Unexpected server request")
        }
    }
    try {
        gateway.connect()
        check(gateway.methods.ping(PingParams()).pong) { "Gateway ping returned false" }
        gateway.methods.gateway.capabilities(PingParams())
        if (System.getenv("HERMES_LIVE_LIFECYCLE") == "1") {
            val session = gateway.methods.session.create(SessionCreateParams(
                cwd = Patch.Value("/tmp"), title = Patch.Value("HermesAPI live session"),
                closeOnDisconnect = true,
            ))
            coroutineScope {
                var sawStart = false
                val streamed = StringBuilder()
                val completion = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(30_000) {
                        gateway.events.first { event ->
                            if (event.sessionId != session.sessionId) return@first false
                            when (val payload = event.payload) {
                                GatewayEventPayload.MessageStart -> sawStart = true
                                is GatewayEventPayload.MessageDelta -> streamed.append(payload.payload.text)
                                else -> Unit
                            }
                            event.type in setOf("message.complete", "error")
                        }
                    }
                }
                val submitted = gateway.methods.prompt.submit(PromptSubmitParams(
                    sessionId = session.sessionId,
                    text = JsonPrimitive("Reply with a short greeting."),
                ))
                check(submitted.status != null) { "Gateway rejected the prompt" }
                val event = completion.await()
                val payload = event.payload as? GatewayEventPayload.MessageComplete
                    ?: error("Gateway turn failed: ${event.type}")
                val reply = payload.payload.text as? MessageCompletePayloadText.StringValue
                check(reply?.value == "HermesAPI fixture reply.") { "Unexpected gateway reply" }
                check(sawStart && streamed.toString() == reply.value) { "Streamed gateway text differs" }
            }
            runToolTurn(gateway, session.sessionId,
                "Ask which release channel to use for HermesAPI.", "clarify",
                "HermesAPI stable release selected.")
            runToolTurn(gateway, session.sessionId,
                "Try the fixture cleanup command and report whether it was approved.", "terminal",
                "HermesAPI approval denied as expected.")
            gateway.methods.session.list(SessionListParams())
            check(gateway.methods.session.close(SessionCloseParams(session.sessionId)).closed) {
                "Gateway session did not close"
            }
            val token = System.getenv("HERMES_LIVE_TOKEN") ?: error("Local token is required for REST smoke")
            val restTransport = KtorRESTTransport()
            try {
                val rest = HermesREST(HermesRESTConfiguration(URI(url),
                    headers = { mapOf("X-Hermes-Session-Token" to token) }, transport = restTransport))
                val voice = rest.methods.audio.voiceLiveStatus(profile = "default")
                check(voice.ok && voice.mode == "chained" && voice.model.isNotEmpty() && voice.voice.isNotEmpty()) {
                    "Unexpected voice status"
                }
                val profile = rest.methods.profiles.active()
                check(profile.active == "default" && profile.current == "default") {
                    "Unexpected active profile"
                }
                val selected = rest.methods.profiles.setActive(ProfilesSetActiveRequest("default"))
                check(selected.ok && selected.active == "default") { "Could not select default profile" }
                check(rest.methods.sessions.emptyCount(profile = "default").count >= 0) {
                    "Invalid empty session count"
                }
            } finally {
                restTransport.close()
            }
        }
        System.out.write("Hermes ${HermesAPI.hermesRelease} gateway ping passed\n".toByteArray())
    } finally {
        gateway.disconnect()
        transport.close()
    }
}

private suspend fun runToolTurn(gateway: HermesGateway, sessionID: String, prompt: String,
                                toolName: String, expected: String): Unit = coroutineScope {
    var sawToolStart = false
    var sawToolComplete = false
    val streamed = StringBuilder()
    val completion = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(30_000) {
            gateway.events.first { event ->
                if (event.sessionId != sessionID) return@first false
                when (val payload = event.payload) {
                    is GatewayEventPayload.ToolStart -> {
                        check(payload.payload.name == toolName) { "Unexpected tool started" }
                        sawToolStart = true
                    }
                    is GatewayEventPayload.ToolComplete -> {
                        check(payload.payload.name == toolName) { "Unexpected tool completed" }
                        if (toolName == "terminal") {
                            val result = payload.payload.result as? JsonObject
                            check(result?.get("status")?.jsonPrimitive?.content == "blocked" &&
                                result?.get("exit_code")?.jsonPrimitive?.content == "-1") {
                                "Denied terminal command was not blocked"
                            }
                        }
                        sawToolComplete = true
                    }
                    is GatewayEventPayload.MessageDelta -> streamed.append(payload.payload.text)
                    else -> Unit
                }
                event.type in setOf("message.complete", "error")
            }
        }
    }
    val submitted = gateway.methods.prompt.submit(PromptSubmitParams(
        sessionId = sessionID, text = JsonPrimitive(prompt)))
    check(submitted.status != null) { "Gateway rejected the $toolName prompt" }
    val event = completion.await()
    val payload = event.payload as? GatewayEventPayload.MessageComplete
        ?: error("Gateway $toolName turn failed: ${event.type}")
    val reply = payload.payload.text as? MessageCompletePayloadText.StringValue
    check(reply?.value == expected) { "Unexpected $toolName reply" }
    check(sawToolStart && sawToolComplete && streamed.toString().trim() == reply.value) {
        "$toolName flow was incomplete"
    }
}
