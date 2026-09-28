package hermes.api.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import hermes.api.generated.gateway.GatewayMethodCatalog
import hermes.api.generated.gateway.ServerRequest
import hermes.api.generated.gateway.OpenRequestEntry
import hermes.api.generated.gateway.ServerRequestResult

/**
 * Everything an app uses of a gateway: typed calls, the event, state and recovery streams, the server request
 * handler, and the lifecycle. [HermesGateway] is the implementation; depend on this interface where tests
 * substitute `FakeGateway` from the library's test fixtures.
 */
public interface HermesGatewayClient : GatewayCaller {
    /** Generated typed method namespaces over this client. */
    public val methods: GatewayMethodCatalog get() = GatewayMethodCatalog(this)

    /** Gateway notifications. Every collector receives every event emitted while it collects, buffered
     *  independently, so a slow collector delays only itself. Start collecting before [connect] to see the
     *  first events. */
    public val events: Flow<GatewayEvent>

    /** The current connection state. As a StateFlow it conflates quick transitions. */
    public val connectionStates: StateFlow<GatewayConnectionState>

    /** Session recoveries after reconnects, buffered independently per collector. See [GatewaySessionRecovery]. */
    public val sessionRecoveries: Flow<GatewaySessionRecovery>

    /** The desktop contract Hermes last reported, or `null` before the first session result. */
    public val backendContract: Int?

    public fun setServerRequestHandler(value: suspend (ServerRequest) -> ServerRequestResult)

    /** Re-delivers pending requests from a session snapshot after the app has registered its session
     *  ownership. Returns after scheduling the handlers, not after the person answers. Requires a live
     *  connection; active duplicates and requests answered or withdrawn on this connection are skipped. */
    public suspend fun restoreServerRequests(requests: List<OpenRequestEntry>)

    public suspend fun connect()

    public suspend fun disconnect()

    public suspend fun enterBackground(graceMillis: Long = 25_000)

    public suspend fun enterForeground()
}
