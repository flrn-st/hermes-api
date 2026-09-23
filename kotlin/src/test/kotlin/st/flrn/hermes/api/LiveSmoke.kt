package st.flrn.hermes.api

import java.net.URI
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import st.flrn.hermes.api.generated.gateway.PingParams
import st.flrn.hermes.api.generated.gateway.PromptSubmitParams
import st.flrn.hermes.api.generated.gateway.GatewayEventPayload
import st.flrn.hermes.api.generated.gateway.MessageCompletePayloadText
import st.flrn.hermes.api.generated.gateway.SessionCreateParams
import st.flrn.hermes.api.generated.gateway.SessionListParams
import st.flrn.hermes.api.generated.gateway.SessionCloseParams
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
                val completion = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(30_000) {
                        gateway.events.first { event ->
                            event.sessionId == session.sessionId &&
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
            }
            gateway.methods.session.list(SessionListParams())
            check(gateway.methods.session.close(SessionCloseParams(session.sessionId)).closed) {
                "Gateway session did not close"
            }
            val token = System.getenv("HERMES_LIVE_TOKEN") ?: error("Local token is required for REST smoke")
            val restTransport = KtorRESTTransport()
            try {
                val rest = HermesREST(HermesRESTConfiguration(URI(url),
                    headers = { mapOf("X-Hermes-Session-Token" to token) }, transport = restTransport))
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
