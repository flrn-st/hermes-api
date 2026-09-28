package hermes.api.runtime

import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import hermes.api.generated.rest._PasswordLoginBody

/**
 * A dashboard session from a password provider (`POST /auth/password-login`, such as Hermes' bundled `basic`
 * provider). Hermes answers with a session cookie, which [transport] must store and send on every request:
 * give it a client with cookie storage, and use the same transport for [HermesRESTConfiguration.transport].
 *
 * ```
 * val transport = KtorRESTTransport(HttpClient(CIO) { followRedirects = false; install(HttpCookies) })
 * val auth = PasswordSessionAuth(address, transport) { PasswordSessionAuth.Credentials("hermes", password) }
 * val rest = HermesREST(HermesRESTConfiguration(address, auth, transport = transport))
 * val gateway = HermesGateway(HermesGatewayConfiguration(address, auth))
 * ```
 *
 * The session is signed in again when Hermes rejects it (a REST 401, or a rejected gateway ticket or upgrade);
 * concurrent rejections share one sign-in. Gateway tickets are always requested through [transport], whichever
 * HTTP transport the gateway was configured with, because only it holds the cookie. The WebSocket itself
 * carries the ticket, so the gateway's socket transport needs no cookies.
 */
public class PasswordSessionAuth(
    address: HermesDashboardAddress,
    private val transport: RESTTransport,
    /** Read for every sign-in, so an app can keep them in its credential store. */
    private val credentials: suspend () -> Credentials,
) : HermesRESTAuth, HermesAuth {
    public data class Credentials(
        val username: String,
        val password: String,
        val provider: String = "basic",
    ) {
        override fun toString(): String = "Credentials(username=$username, provider=$provider)"
    }

    // The sign-in routes are public: the session itself carries no credential.
    private val session = HermesREST(HermesRESTConfiguration(address, transport = transport))
    private val lock = Mutex()
    private var signingIn: CompletableDeferred<Unit>? = null

    /** Posts ticket requests through [transport], so they carry the session cookie. */
    private val ticketTransport = object : GatewayHTTPTransport {
        override suspend fun post(uri: URI, headers: Map<String, String>): Pair<Int, String> {
            val response = transport.request("POST", uri, headers, null, null)
            return response.status to response.body.decodeToString()
        }
    }

    /** Signs in now, for example to check credentials the user entered. Throws [HermesRESTException.HTTP]
     *  with status 401 when Hermes rejects them. Concurrent callers share one sign-in. */
    public suspend fun signIn() {
        val (pending, owner) = lock.withLock {
            signingIn?.let { return@withLock it to false }
            CompletableDeferred<Unit>().also { signingIn = it } to true
        }
        if (!owner) return pending.await()
        try {
            val current = credentials()
            val result = session.methods.web.authPasswordLogin(_PasswordLoginBody(
                password = current.password, provider = current.provider, username = current.username))
            if (!result.ok) throw HermesRESTException.HTTP(401, "")
            pending.complete(Unit)
        } catch (error: Throwable) {
            pending.completeExceptionally(error)
            throw error
        } finally {
            withContext(NonCancellable) { lock.withLock { if (signingIn === pending) signingIn = null } }
        }
    }

    /** Ends the session on Hermes; the cookie storage drops the cookie Hermes clears. */
    public suspend fun signOut() {
        session.methods.web.authLogout()
    }

    override suspend fun authorizationHeaders(): Map<String, String> = emptyMap()

    override suspend fun renew(rejected: Map<String, String>, response: RESTResponse): Boolean = signInAgain()

    override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential =
        DashboardTicketAuth { emptyMap() }.credential(baseURI, ticketTransport)

    override suspend fun renew(failure: HermesGatewayException.AuthenticationFailed): Boolean = signInAgain()

    /** Rejected credentials make the rejection final instead of an error to retry. */
    private suspend fun signInAgain(): Boolean = try {
        signIn()
        true
    } catch (error: HermesRESTException.HTTP) {
        if (!error.isAuthenticationFailure) throw error
        false
    }
}
