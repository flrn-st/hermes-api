package st.flrn.hermes.api

import java.net.URI
import st.flrn.hermes.api.generated.gateway.PingParams
import st.flrn.hermes.api.runtime.HermesGateway
import st.flrn.hermes.api.runtime.HermesGatewayConfiguration
import st.flrn.hermes.api.runtime.HermesAuth
import st.flrn.hermes.api.runtime.GatewayCredential
import st.flrn.hermes.api.runtime.GatewayHTTPTransport
import st.flrn.hermes.api.runtime.LocalTokenAuth
import st.flrn.hermes.api.runtime.KtorGatewayTransport

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
        System.out.write("Hermes ${HermesAPI.hermesRelease} gateway ping passed\n".toByteArray())
    } finally {
        gateway.disconnect()
        transport.close()
    }
}
