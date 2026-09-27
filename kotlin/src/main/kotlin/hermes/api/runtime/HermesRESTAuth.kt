package hermes.api.runtime

import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import hermes.api.generated.rest._NativeRefreshBody

/**
 * Credentials for dashboard REST requests.
 *
 * Hermes accepts three: the loopback dashboard's session token (`X-Hermes-Session-Token`), a native
 * app's OAuth access token (`Authorization: Bearer`), and browser session cookies (left to the
 * transport's cookie storage). [HermesREST] asks for headers before every attempt and, after a 401,
 * lets the credential renew itself once before it retries.
 */
public interface HermesRESTAuth {
    /** Headers that authenticate the next request. */
    public suspend fun authorizationHeaders(): Map<String, String>

    /** Called once after a request answered 401. [rejected] holds the headers that request carried.
     *  Return `true` when the credential has changed since, and the request should be sent again. */
    public suspend fun renew(rejected: Map<String, String>, response: RESTResponse): Boolean = false
}

/** Fixed headers, for example a reverse proxy's credential on top of cookie sessions. */
public class StaticHeadersAuth(private val values: Map<String, String>) : HermesRESTAuth, HermesAuth {
    override suspend fun authorizationHeaders(): Map<String, String> = values

    override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential =
        DashboardTicketAuth { values }.credential(baseURI, http)
}

/**
 * A native app's dashboard session: the Bearer tokens `POST /auth/native/token` issued.
 *
 * The access token is refreshed through `POST /auth/native/refresh` when it is about to expire or a
 * request answers 401. Concurrent callers share one refresh, and [onRotate] receives every new token
 * pair so the app can persist it (Hermes rotates the refresh token on each use). It also authorizes
 * gateway connections, so one credential serves both surfaces.
 */
public class NativeSessionAuth(
    address: HermesDashboardAddress,
    tokens: Tokens,
    transport: RESTTransport = KtorRESTTransport(),
    /** Refresh this long before [Tokens.expiresAt], so a request does not race the expiry. */
    private val leewaySeconds: Long = 30,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val onRotate: suspend (Tokens) -> Unit = {},
) : HermesRESTAuth, HermesAuth {
    public data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        /** Unix seconds; `null` when unknown. */
        val expiresAt: Long? = null,
        val provider: String = "",
    )

    // The refresh endpoint is public; the refresher itself carries no credential.
    private val refresher = HermesREST(HermesRESTConfiguration(address, transport = transport))
    private val lock = Mutex()
    private var tokens = tokens
    private var refreshing: CompletableDeferred<Tokens>? = null

    public val current: Tokens get() = tokens

    override suspend fun authorizationHeaders(): Map<String, String> {
        val expiresAt = tokens.expiresAt
        if (expiresAt != null && clock() + leewaySeconds >= expiresAt) refresh(tokens.accessToken)
        return mapOf("Authorization" to "Bearer ${tokens.accessToken}")
    }

    override suspend fun renew(rejected: Map<String, String>, response: RESTResponse): Boolean {
        val token = rejected["Authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: return false
        return refresh(token).accessToken != token
    }

    override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential =
        DashboardTicketAuth { authorizationHeaders() }.credential(baseURI, http)

    override suspend fun renew(failure: HermesGatewayException.AuthenticationFailed): Boolean {
        val rejected = tokens.accessToken
        return refresh(rejected).accessToken != rejected
    }

    /** Refreshes unless another caller already replaced [rejected]; every caller waits for the same refresh. */
    private suspend fun refresh(rejected: String): Tokens {
        val (pending, owner) = lock.withLock {
            if (tokens.accessToken != rejected) return tokens
            refreshing?.let { return@withLock it to false }
            CompletableDeferred<Tokens>().also { refreshing = it } to true
        }
        if (!owner) return pending.await()
        try {
            val current = tokens
            val issued = refresher.methods.web.authNativeRefresh(
                _NativeRefreshBody(provider = current.provider.ifEmpty { null }, refreshToken = current.refreshToken))
            val fresh = Tokens(issued.accessToken, issued.refreshToken, issued.expiresAt, issued.provider)
            lock.withLock {
                tokens = fresh
                refreshing = null
            }
            onRotate(fresh)
            pending.complete(fresh)
            return fresh
        } catch (error: Throwable) {
            lock.withLock { refreshing = null }
            pending.completeExceptionally(error)
            throw error
        }
    }
}
