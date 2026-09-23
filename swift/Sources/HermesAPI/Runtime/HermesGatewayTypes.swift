import Foundation

/// Errors surfaced by the gateway runtime.
public enum HermesGatewayError: Error, Sendable, Equatable {
    case transport(String)
    case rpc(code: Int, message: String, data: JSONValue?)
    case decoding(String)
    case timeout
    case cancelled
    case incompatibleServer(Int)
    case replayTruncated(String)
}

public enum HermesGatewayErrorCode {
    public static let contractValidation = 4000
    public static let incompatibleContract = 4006
    public static let sessionUnavailable = 4015
}

public enum GatewayConnectionState: Sendable, Equatable {
    case connecting
    case connected
    case reconnecting(attempt: Int)
    case disconnected(String?)
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
    public let requestTimeout: Duration

    public init(
        baseURL: URL,
        auth: any HermesAuth,
        transport: any GatewayTransport = URLSessionGatewayTransport(),
        httpTransport: any HTTPTransport = URLSessionHTTPTransport(),
        requestTimeout: Duration = .seconds(30)
    ) {
        self.baseURL = baseURL
        self.auth = auth
        self.transport = transport
        self.httpTransport = httpTransport
        self.requestTimeout = requestTimeout
    }
}
