package st.flrn.hermes.api

import java.net.URI
import st.flrn.hermes.api.generated.gateway.PingParams
import st.flrn.hermes.api.runtime.HermesGateway
import st.flrn.hermes.api.runtime.HermesGatewayConfiguration
import st.flrn.hermes.api.runtime.LocalTokenAuth

/** Invoked by the dedicated Gradle smoke task against a tagged Hermes server. */
suspend fun main() {
    val url = System.getenv("HERMES_LIVE_URL") ?: error("HERMES_LIVE_URL is required")
    val token = System.getenv("HERMES_LIVE_TOKEN") ?: error("HERMES_LIVE_TOKEN is required")
    val gateway = HermesGateway(HermesGatewayConfiguration(URI(url), LocalTokenAuth(token)))
    try {
        gateway.connect()
        check(gateway.methods.ping(PingParams()).pong) { "Gateway ping returned false" }
        System.out.write("Hermes ${HermesAPI.hermesRelease} gateway ping passed\n".toByteArray())
    } finally {
        gateway.disconnect()
    }
}
