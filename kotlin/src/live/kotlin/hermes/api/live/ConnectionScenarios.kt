package hermes.api.live

import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import hermes.api.generated.gateway.PingParams
import hermes.api.runtime.HermesDashboardAddress
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.HermesREST
import hermes.api.runtime.HermesRESTConfiguration
import hermes.api.runtime.HermesRESTException
import hermes.api.runtime.KtorGatewayTransport
import hermes.api.runtime.KtorRESTTransport
import hermes.api.runtime.LocalTokenAuth
import hermes.api.runtime.PasswordSessionAuth
import hermes.api.runtime.RESTRetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets

/** Connection setups apps use beyond the loopback token: a password session's cookie authorizing the
 *  gateway, and a self-hosted dashboard behind a self-signed certificate the app pins. */
internal object ConnectionScenarios {
    /** Signs in with a password, then uses the cookie session for REST and for the gateway's ticket. */
    suspend fun passwordSession(url: URI, username: String, password: String) {
        val address = HermesDashboardAddress(url)
        // One cookie-carrying client for REST and ticket requests; the socket carries the ticket instead.
        KtorRESTTransport(HttpClient(CIO) { followRedirects = false; install(HttpCookies) }).use { transport ->
            val auth = PasswordSessionAuth(address, transport) { PasswordSessionAuth.Credentials(username, password) }
            val rest = HermesREST(HermesRESTConfiguration(address, auth, transport = transport))
            // No sign-in yet: the first request is rejected, and the session signs in and repeats it.
            rest.methods.sessions.emptyCount()
            KtorGatewayTransport().use { socket ->
                val gateway = HermesGateway(HermesGatewayConfiguration(address, auth, socket))
                try {
                    gateway.connect()
                    if (!gateway.methods.ping(PingParams()).pong) {
                        throw LiveScenarioFailure("Gateway ping over a password session returned false")
                    }
                } finally {
                    gateway.disconnect()
                }
            }
            auth.signOut()
            try {
                HermesREST(HermesRESTConfiguration(address, transport = transport)).methods.sessions.emptyCount()
            } catch (error: HermesRESTException.HTTP) {
                if (error.status == 401) return
                throw error
            }
            throw LiveScenarioFailure("The signed-out session still reached a gated route")
        }
    }

    /** Reaches the main server over HTTPS and WSS with clients that trust only the harness's certificate, and
     *  checks that a client without that trust is refused. */
    suspend fun pinnedServer(url: URI, certificate: String, token: String) {
        val trust = pinnedTrust(Base64.getDecoder().decode(certificate))
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val address = HermesDashboardAddress(url)
        val auth = LocalTokenAuth(token)
        val pinnedREST = HttpClient(OkHttp) {
            followRedirects = false
            engine { config { sslSocketFactory(tls.socketFactory, trust) } }
        }
        KtorRESTTransport(pinnedREST).use { transport ->
            HermesREST(HermesRESTConfiguration(address, auth, transport = transport)).methods.sessions.emptyCount()
        }
        val pinnedSocket = HttpClient(OkHttp) {
            install(WebSockets)
            engine { config { sslSocketFactory(tls.socketFactory, trust) } }
        }
        KtorGatewayTransport(pinnedSocket).use { transport ->
            val gateway = HermesGateway(HermesGatewayConfiguration(address, auth, transport))
            try {
                gateway.connect()
                if (!gateway.methods.ping(PingParams()).pong) throw LiveScenarioFailure("Gateway ping over TLS returned false")
            } finally {
                gateway.disconnect()
            }
        }
        KtorRESTTransport().use { untrusting ->
            try {
                HermesREST(HermesRESTConfiguration(address, auth, transport = untrusting, retry = RESTRetryPolicy.None))
                    .methods.sessions.emptyCount()
            } catch (_: HermesRESTException.Transport) {
                return
            }
            throw LiveScenarioFailure("A client without the pinned certificate reached Hermes")
        }
    }

    /** Trusts exactly one self-signed server certificate, as an app pinning its self-hosted dashboard does. */
    private fun pinnedTrust(der: ByteArray): X509TrustManager {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("hermes", certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw LiveScenarioFailure("No X.509 trust manager for the pinned certificate")
    }
}
