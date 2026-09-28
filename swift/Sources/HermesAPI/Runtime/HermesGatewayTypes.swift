import Foundation
import os

/// Errors surfaced by the gateway runtime.
public enum HermesGatewayError: Error, Sendable, Equatable {
    case transport(String)
    case rpc(code: Int, message: String, data: JSONValue?)
    case decoding(String)
    case timeout
    case cancelled
    /// Hermes reported a desktop contract below `HermesGatewayConfiguration.minimumContract`.
    case incompatibleServer(Int)
    /// The dashboard rejected the credential: HTTP 401 or 403 from the ticket request or the WebSocket
    /// upgrade, and `HermesAuth.renew(after:)` could not replace it. Hermes also answers 403 for a
    /// disallowed origin or a disabled chat, so retrying the same credential cannot help.
    case authenticationFailed(String)

    /// Failures a reconnect cannot fix. The gateway stops retrying and reports `.failed`.
    var isTerminal: Bool {
        switch self {
        case .authenticationFailed, .incompatibleServer: true
        default: false
        }
    }
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

    public init(type: String, sessionID: String?, seq: Int?, payload: GatewayEventPayload, replayed: Bool = false) {
        self.type = type
        self.sessionID = sessionID
        self.seq = seq
        self.payload = payload
        self.replayed = replayed
    }
}

public struct HermesGatewayConfiguration: Sendable {
    /// Resolved before every connection attempt; see `HermesDashboardAddress`.
    public let address: HermesDashboardAddress
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
    /// The lowest desktop contract accepted from Hermes; a session result reporting less fails with
    /// `incompatibleServer`. Contracts only grow, so any higher one is accepted. Lower it to keep working
    /// with older backends, and branch on `HermesGateway.backendContract` for what they lack.
    public let minimumContract: Int
    public let logger: any GatewayLogger

    public init(
        address: HermesDashboardAddress,
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
        minimumContract: Int = HermesGatewayContract.desktopContract,
        logger: any GatewayLogger = OSLogGatewayLogger(category: "gateway")
    ) {
        self.address = address
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
        self.minimumContract = minimumContract
        self.logger = logger
    }
}
