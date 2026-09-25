import Foundation

/// One connected JSON-RPC WebSocket. Each frame is UTF-8 JSON.
public protocol GatewayConnection: Sendable {
    func send(_ frame: Data) async throws
    func receive() async throws -> Data
    func close() async
}

/// Creates WebSocket connections. Apps can inject a connection that uses their trust and tunnel policy.
public protocol GatewayTransport: Sendable {
    func connect(url: URL, headers: [String: String], subprotocols: [String]) async throws -> any GatewayConnection
}

/// Performs an HTTP request used by dashboard authentication and, later, REST calls.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionHTTPTransport: HTTPTransport {
    public let session: URLSession

    public init(session: URLSession = .shared) { self.session = session }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw HermesGatewayError.transport("HTTP response has no status")
        }
        return (data, response)
    }
}

public struct URLSessionGatewayTransport: GatewayTransport {
    public let session: URLSession
    /// URLSession's default of 1 MiB would fail every reconnect that replays or resumes a long session.
    public let maximumMessageSize: Int

    public init(session: URLSession = .shared, maximumMessageSize: Int = 64 << 20) {
        self.session = session
        self.maximumMessageSize = maximumMessageSize
    }

    public func connect(url: URL, headers: [String: String], subprotocols: [String]) async throws -> any GatewayConnection {
        var request = URLRequest(url: url)
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        if !subprotocols.isEmpty {
            request.setValue(subprotocols.joined(separator: ", "), forHTTPHeaderField: "Sec-WebSocket-Protocol")
        }
        let task = session.webSocketTask(with: request)
        task.maximumMessageSize = maximumMessageSize
        task.resume()
        return URLSessionGatewayConnection(task: task)
    }
}

private actor URLSessionGatewayConnection: GatewayConnection {
    private let task: URLSessionWebSocketTask

    init(task: URLSessionWebSocketTask) { self.task = task }

    func send(_ frame: Data) async throws {
        guard let text = String(data: frame, encoding: .utf8) else {
            throw HermesGatewayError.transport("Outgoing frame is not UTF-8")
        }
        do {
            try await task.send(.string(text))
        } catch {
            throw failure(error)
        }
    }

    func receive() async throws -> Data {
        let message: URLSessionWebSocketTask.Message
        do {
            message = try await task.receive()
        } catch {
            throw failure(error)
        }
        switch message {
        case .string(let text): return Data(text.utf8)
        case .data(let data): return data
        @unknown default: throw HermesGatewayError.transport("Unsupported WebSocket message")
        }
    }

    func close() {
        task.cancel(with: .normalClosure, reason: nil)
    }

    /// Hermes rejects a bad credential before the upgrade, which URLSession reports as the HTTP status.
    private func failure(_ error: any Error) -> HermesGatewayError {
        if let status = (task.response as? HTTPURLResponse)?.statusCode, status == 401 || status == 403 {
            return .authenticationFailed("WebSocket upgrade returned HTTP \(status)")
        }
        if task.closeCode != .invalid {
            return .transport("WebSocket closed with code \(task.closeCode.rawValue)")
        }
        return .transport(error.localizedDescription)
    }
}
