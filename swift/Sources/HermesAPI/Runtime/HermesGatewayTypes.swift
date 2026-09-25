import Foundation
import os

/// Errors surfaced by the gateway runtime.
public enum HermesGatewayError: Error, Sendable, Equatable {
    case transport(String)
    case rpc(code: Int, message: String, data: JSONValue?)
    case decoding(String)
    case timeout
    case cancelled
    case incompatibleServer(Int)
    /// The dashboard rejected the credential: HTTP 401 or 403 from the ticket request or the WebSocket
    /// upgrade. Hermes also answers 403 for a disallowed origin or a disabled chat, so retrying cannot help.
    case authenticationFailed(String)

    /// Failures a reconnect cannot fix. The gateway stops retrying and reports `.failed`.
    var isTerminal: Bool {
        switch self {
        case .authenticationFailed, .incompatibleServer: true
        default: false
        }
    }
}

public enum HermesGatewayErrorCode {
    public static let contractValidation = 4000
    public static let sessionNotFound = 4001
    public static let incompatibleContract = 4006
    public static let sessionNotLive = 4007
    public static let sessionSettling = 4009
    public static let sessionUnavailable = 4015
    /// The backend is shutting down and asks clients to reconnect.
    public static let backendRetiring = 5035
}

public enum GatewayConnectionState: Sendable, Equatable {
    /// Not started, or closed by `disconnect()`.
    case idle
    case connecting
    case connected
    /// The connection dropped; the gateway retries with backoff and replays missed events.
    case reconnecting(attempt: Int)
    /// No usable network path. The gateway reconnects as soon as one appears, without polling.
    case waitingForNetwork
    /// Closed by `enterBackground()`; `enterForeground()` reconnects.
    case suspended
    /// A failure retrying cannot fix. Call `connect()` again once it is resolved.
    case failed(HermesGatewayError)
}

/// How the gateway recovered a session after a reconnect, or why it could not.
public enum GatewaySessionRecovery: Sendable, Equatable {
    /// Hermes had reclaimed the session while this client was away. The gateway resumed it from storage;
    /// it continues under a new runtime `sessionID`, and its history is intact.
    case resumed(previousSessionID: String, sessionID: String, storedSessionID: String)
    /// Hermes no longer buffers every event since the last one delivered. Reload the transcript.
    case replayTruncated(sessionID: String)
    /// The session could not be recovered and is no longer tracked.
    case unavailable(sessionID: String, reason: String)
    /// Hermes reclaimed a session while connected, for example after an idle timeout. Resume it by its
    /// stored id when it is needed again.
    case reclaimed(sessionID: String, storedSessionID: String, reason: String)
}

/// A decoded gateway notification. `seq` is monotonic within its session.
public struct GatewayEvent: Sendable, Hashable {
    public let type: String
    public let sessionID: String?
    public let seq: Int?
    public let payload: GatewayEventPayload
    public let replayed: Bool
}

public struct HermesGatewayConfiguration: Sendable {
    public let baseURL: URL
    public let auth: any HermesAuth
    public let transport: any GatewayTransport
    public let httpTransport: any HTTPTransport
    /// Reacts to network changes. `nil` disables it; the heartbeat still detects dead sockets.
    public let networkMonitor: (any GatewayNetworkMonitor)?
    public let requestTimeout: Duration
    /// Bounds the WebSocket handshake and capability exchange of each connection attempt.
    public let connectTimeout: Duration
    public let reconnectDelay: @Sendable (Int) -> Duration
    /// After this long without an inbound frame, the gateway sends a `gateway.ping`.
    public let heartbeatInterval: Duration
    /// After this long without any inbound frame, the socket counts as dead and the gateway reconnects.
    /// The defaults match Hermes' own clients (15 s and 45 s).
    public let heartbeatDeadline: Duration
    /// Resume sessions Hermes reclaimed while this client was disconnected. See `GatewaySessionRecovery`.
    public let resumesReclaimedSessions: Bool
    public let logger: Logger

    public init(
        baseURL: URL,
        auth: any HermesAuth,
        transport: any GatewayTransport = URLSessionGatewayTransport(),
        httpTransport: any HTTPTransport = URLSessionHTTPTransport(),
        networkMonitor: (any GatewayNetworkMonitor)? = SystemNetworkMonitor(),
        requestTimeout: Duration = .seconds(120),
        connectTimeout: Duration = .seconds(15),
        reconnectDelay: @escaping @Sendable (Int) -> Duration = { attempt in
            // Full jitter: 0...250 ms on the first retry, growing to 0...30 s.
            let cap = min(30_000, 250 * (1 << min(attempt - 1, 7)))
            return .milliseconds(Int.random(in: 0...cap))
        },
        heartbeatInterval: Duration = .seconds(15),
        heartbeatDeadline: Duration = .seconds(45),
        resumesReclaimedSessions: Bool = true,
        logger: Logger = Logger(subsystem: "hermes.api", category: "gateway")
    ) {
        self.baseURL = baseURL
        self.auth = auth
        self.transport = transport
        self.httpTransport = httpTransport
        self.networkMonitor = networkMonitor
        self.requestTimeout = requestTimeout
        self.connectTimeout = connectTimeout
        self.reconnectDelay = reconnectDelay
        self.heartbeatInterval = heartbeatInterval
        self.heartbeatDeadline = heartbeatDeadline
        self.resumesReclaimedSessions = resumesReclaimedSessions
        self.logger = logger
    }
}
