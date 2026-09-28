package hermes.api.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * A per-call timeout for the calls made in a scope; see [withHermesRequestTimeout]. It replaces
 * [HermesGatewayConfiguration.requestTimeoutMillis] for gateway calls and [HermesRESTConfiguration.timeoutMillis]
 * for each REST attempt.
 */
public class HermesRequestTimeout(public val millis: Long) : AbstractCoroutineContextElement(Key) {
    init {
        require(millis > 0) { "A request timeout must be positive" }
    }

    public companion object Key : CoroutineContext.Key<HermesRequestTimeout>

    override fun toString(): String = "HermesRequestTimeout($millis ms)"
}

/**
 * Runs [block] with [timeoutMillis] bounding every Hermes call it makes, instead of the configured timeout:
 * each gateway call (including waiting for a reconnect) and each REST attempt. A gateway call that takes
 * longer fails with [HermesGatewayException.Timeout], a REST attempt with [HermesRESTException.Timeout]
 * (after any retries the policy allows). The gateway's own handshake and recovery calls keep their bounds.
 */
public suspend fun <T> withHermesRequestTimeout(timeoutMillis: Long, block: suspend () -> T): T =
    withContext(HermesRequestTimeout(timeoutMillis)) { block() }
