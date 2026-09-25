package st.flrn.hermes.api.runtime

import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import st.flrn.hermes.api.generated.gateway.GatewayEventPayload
import kotlin.random.Random

public sealed class HermesGatewayException(message: String) : Exception(message) {
    public class Transport(message: String) : HermesGatewayException(message)
    public class Protocol(message: String) : HermesGatewayException(message)
    public class Timeout(public val method: String) : HermesGatewayException("RPC $method timed out")
    public class RPC(public val code: Int, message: String, public val data: JsonElement?) : HermesGatewayException(message)
    public class IncompatibleServer(public val contract: Int) : HermesGatewayException("Unsupported desktop contract $contract")
    /** The dashboard rejected the credential: HTTP 401 or 403 from the ticket request or the WebSocket
     *  upgrade. Hermes also answers 403 for a disallowed origin or a disabled chat, so retrying cannot help. */
    public class AuthenticationFailed(message: String) : HermesGatewayException(message)

    /** Failures a reconnect cannot fix. The gateway stops retrying and reports [GatewayConnectionState.Failed]. */
    internal val isTerminal: Boolean get() = this is AuthenticationFailed || this is IncompatibleServer
}

public object HermesGatewayErrorCodes {
    public const val CONTRACT_VALIDATION: Int = 4000
    public const val SESSION_NOT_FOUND: Int = 4001
    public const val INCOMPATIBLE_CONTRACT: Int = 4006
    public const val SESSION_NOT_LIVE: Int = 4007
    public const val SESSION_SETTLING: Int = 4009
    public const val SESSION_UNAVAILABLE: Int = 4015
    /** The backend is shutting down and asks clients to reconnect. */
    public const val BACKEND_RETIRING: Int = 5035
}

public sealed interface GatewayConnectionState {
    /** Not started, or closed by `disconnect()`. */
    public data object Idle : GatewayConnectionState
    public data object Connecting : GatewayConnectionState
    public data object Connected : GatewayConnectionState
    /** The connection dropped; the gateway retries with backoff and replays missed events. */
    public data class Reconnecting(val attempt: Int) : GatewayConnectionState
    /** No usable network. The gateway reconnects as soon as one appears, without polling. */
    public data object WaitingForNetwork : GatewayConnectionState
    /** Closed by `enterBackground()`; `enterForeground()` reconnects. */
    public data object Suspended : GatewayConnectionState
    /** A failure retrying cannot fix. Call `connect()` again once it is resolved. */
    public data class Failed(val error: HermesGatewayException) : GatewayConnectionState
}

/** How the gateway recovered a session after a reconnect, or why it could not. */
public sealed interface GatewaySessionRecovery {
    /** Hermes had reclaimed the session while this client was away. The gateway resumed it from storage;
     *  it continues under a new runtime [sessionId], and its history is intact. */
    public data class Resumed(val previousSessionId: String, val sessionId: String, val storedSessionId: String) :
        GatewaySessionRecovery
    /** Hermes no longer buffers every event since the last one delivered. Reload the transcript. */
    public data class ReplayTruncated(val sessionId: String) : GatewaySessionRecovery
    /** The session could not be recovered and is no longer tracked. */
    public data class Unavailable(val sessionId: String, val reason: String) : GatewaySessionRecovery
    /** Hermes reclaimed a session while connected, for example after an idle timeout. Resume it by its
     *  stored id when it is needed again. */
    public data class Reclaimed(val sessionId: String, val storedSessionId: String, val reason: String) :
        GatewaySessionRecovery
}

public data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val seq: Long?,
    val payload: GatewayEventPayload,
    val replayed: Boolean,
)

/** The device's current route to the network. */
public data class GatewayNetworkPath(
    val isAvailable: Boolean,
    /** Identifies the network carrying traffic. A change while available (Wi-Fi to cellular, or a new
     *  Wi-Fi network) strands sockets bound to the old one, so the gateway reconnects at once. */
    val network: String?,
)

/** Reports network changes; the first value is the current path. Android supplies one in hermes-api-android. */
public fun interface GatewayNetworkMonitor {
    public fun paths(): Flow<GatewayNetworkPath>
}

public enum class GatewayLogLevel { DEBUG, INFO, ERROR }

/** Receives connection diagnostics. Messages never contain credentials or payloads. */
public fun interface GatewayLogger {
    public fun log(level: GatewayLogLevel, message: String)

    public companion object {
        public val None: GatewayLogger = GatewayLogger { _, _ -> }
    }
}

public data class HermesGatewayConfiguration(
    val baseURI: URI,
    val auth: HermesAuth,
    val transport: GatewayTransport = KtorGatewayTransport(),
    val httpTransport: GatewayHTTPTransport = transport as? GatewayHTTPTransport
        ?: KtorGatewayTransport(),
    /** Reacts to network changes. `null` disables it; the heartbeat still detects dead sockets. */
    val networkMonitor: GatewayNetworkMonitor? = null,
    val requestTimeoutMillis: Long = 120_000,
    /** Bounds the capability exchange of each connection attempt. */
    val connectTimeoutMillis: Long = 15_000,
    /** Full jitter: 0..250 ms on the first retry, growing to 0..30 s. */
    val reconnectDelayMillis: (Int) -> Long = { attempt ->
        Random.nextLong(0, minOf(30_000L, 250L * (1L shl minOf(attempt - 1, 7))) + 1)
    },
    /** After this long without an inbound frame, the gateway sends a `gateway.ping`. */
    val heartbeatIntervalMillis: Long = 15_000,
    /** After this long without any inbound frame, the socket counts as dead and the gateway reconnects.
     *  The defaults match Hermes' own clients (15 s and 45 s). */
    val heartbeatDeadlineMillis: Long = 45_000,
    /** Resume sessions Hermes reclaimed while this client was disconnected. See [GatewaySessionRecovery]. */
    val resumesReclaimedSessions: Boolean = true,
    val logger: GatewayLogger = GatewayLogger.None,
)
