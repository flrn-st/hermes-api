package hermes.api.runtime

import java.net.URI
import java.net.URISyntaxException

/**
 * Where a Hermes dashboard is reached.
 *
 * [HermesGateway] resolves it before every connection attempt and [HermesREST] before every request attempt,
 * so an app can move between addresses (a LAN and a VPN address, say) without rebuilding its clients or
 * losing the gateway's sessions. `previousFailure` is why the attempt before could not reach the dashboard,
 * or `null` when there was none: answer quickly then (the address in use), and look for another address
 * only after a failure.
 */
public fun interface HermesDashboardAddress {
    public suspend fun resolve(previousFailure: Throwable?): URI
}

/** A dashboard that is always at [uri]. */
public fun HermesDashboardAddress(uri: URI): HermesDashboardAddress = HermesDashboardAddress { uri }

/**
 * The URI of a dashboard route. A dashboard can be served under a path, behind a reverse proxy
 * (`https://host/hermes/`) or a relay (`https://relay/agents/<id>`), so [rawPath] (percent-encoded, starting
 * with `/`) is appended to the base URI's path instead of replacing it. The base URI's query and fragment
 * are dropped; [rawQuery] is already encoded. Returns `null` for an unusable path or base.
 */
internal fun dashboardURI(base: URI, rawPath: String, rawQuery: String? = null, scheme: String = base.scheme): URI? {
    if (!rawPath.startsWith("/") || rawPath.startsWith("//") || base.rawAuthority == null) return null
    val prefix = base.rawPath.orEmpty().trimEnd('/')
    val query = rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
    return try { URI("$scheme://${base.rawAuthority}$prefix$rawPath$query") } catch (error: URISyntaxException) { null }
}
