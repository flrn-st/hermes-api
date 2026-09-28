import os

public enum GatewayLogLevel: Sendable, Hashable {
    case debug
    case info
    case error
}

/// Receives connection diagnostics from the gateway and REST clients. Messages never contain credentials or
/// payloads, so an app can forward them to its own diagnostics as they are.
public protocol GatewayLogger: Sendable {
    func log(_ level: GatewayLogLevel, _ message: String)
}

extension GatewayLogger {
    func debug(_ message: String) { log(.debug, message) }
    func info(_ message: String) { log(.info, message) }
    func error(_ message: String) { log(.error, message) }
}

/// Logs to the unified logging system. Messages are public: they carry no credentials or payloads.
public struct OSLogGatewayLogger: GatewayLogger {
    public let logger: Logger

    public init(subsystem: String = "hermes.api", category: String) {
        logger = Logger(subsystem: subsystem, category: category)
    }

    public init(_ logger: Logger) { self.logger = logger }

    public func log(_ level: GatewayLogLevel, _ message: String) {
        switch level {
        case .debug: logger.debug("\(message, privacy: .public)")
        case .info: logger.info("\(message, privacy: .public)")
        case .error: logger.error("\(message, privacy: .public)")
        }
    }
}

/// Discards every message.
public struct SilentGatewayLogger: GatewayLogger {
    public init() {}

    public func log(_ level: GatewayLogLevel, _ message: String) {}
}

extension HermesGatewayError {
    /// A description for logs: RPC error data and other payloads left out.
    var logDescription: String {
        switch self {
        case .transport(let message): "transport: \(message)"
        case .rpc(let code, let message, _): "RPC error \(code): \(message)"
        case .decoding: "decoding failed"
        case .timeout: "timeout"
        case .cancelled: "cancelled"
        case .incompatibleServer(let contract): "incompatible server (contract \(contract))"
        case .authenticationFailed(let message): "authentication failed: \(message)"
        }
    }
}

extension HermesRESTError {
    /// A description for logs: response bodies left out.
    var logDescription: String {
        switch self {
        case .transport(let message): "transport: \(message)"
        case .timeout: "timeout"
        case .http(let status, _): "HTTP \(status)"
        case .decoding: "decoding failed"
        }
    }
}
