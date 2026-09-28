import Foundation
import HermesAPI

/// A fake Hermes WebSocket for driving a real `HermesGateway` in tests. It answers `client.capabilities`,
/// optionally answers heartbeats, and hands every other frame the client sends to `sent`. Push what Hermes
/// would send with `inject(_:)` and the builders in `GatewayFrames`.
public actor ScriptedGatewaySocket: GatewayConnection {
    /// Frames the client sent, except the capability handshake and heartbeats.
    public nonisolated let sent: AsyncStream<Data>
    /// The ids of heartbeat pings the client sent.
    public nonisolated let heartbeats: AsyncStream<String>
    private let sentContinuation: AsyncStream<Data>.Continuation
    private let heartbeatContinuation: AsyncStream<String>.Continuation
    private var incoming: [Data] = []
    private var waiters: [CheckedContinuation<Data, Error>] = []
    private var failure: HermesGatewayError?
    private let answersHeartbeats: Bool
    private let serverRequests: [String]
    public private(set) var isClosed = false
    private var sendsFail = false

    /// - Parameters:
    ///   - answersHeartbeats: Reply to `gateway.ping` so an idle connection stays alive.
    ///   - serverRequests: The server request kinds the capability handshake reports.
    public init(answersHeartbeats: Bool = false, serverRequests: [String] = ["approval"]) {
        (sent, sentContinuation) = AsyncStream.makeStream(of: Data.self)
        (heartbeats, heartbeatContinuation) = AsyncStream.makeStream(of: String.self)
        self.answersHeartbeats = answersHeartbeats
        self.serverRequests = serverRequests
    }

    /// The socket died, but its reader has not noticed: sends fail, reads still wait.
    public func failSends() { sendsFail = true }

    public func send(_ frame: Data) throws {
        guard !isClosed, !sendsFail else { throw HermesGatewayError.transport("closed") }
        guard case .object(let object) = try JSONValue(jsonData: frame) else {
            throw HermesGatewayError.decoding("Outgoing frame is not a JSON object")
        }
        switch object["method"]?.stringValue {
        case "client.capabilities":
            let requests = serverRequests.map(JSONValue.string)
            inject(GatewayFrames.result(object["id"]?.integerValue ?? 0, .object(["server_requests": .array(requests)])))
        case "gateway.ping":
            let id = object["id"]?.stringValue ?? ""
            heartbeatContinuation.yield(id)
            if answersHeartbeats { inject(#"{"jsonrpc":"2.0","id":"\#(id)","result":{"ok":true}}"#) }
        default:
            sentContinuation.yield(frame)
        }
    }

    public func receive() async throws -> Data {
        if let failure { throw failure }
        if !incoming.isEmpty { return incoming.removeFirst() }
        return try await withCheckedThrowingContinuation { waiters.append($0) }
    }

    /// Delivers one frame from Hermes to the client.
    public func inject(_ json: String) {
        let frame = Data(json.utf8)
        if !waiters.isEmpty { waiters.removeFirst().resume(returning: frame) } else { incoming.append(frame) }
    }

    /// Hermes (or the network) ends the socket.
    public func sever(_ error: HermesGatewayError = .transport("closed")) {
        failure = error
        for waiter in waiters { waiter.resume(throwing: error) }
        waiters.removeAll()
    }

    public func close() {
        isClosed = true
        sever()
        sentContinuation.finish()
        heartbeatContinuation.finish()
    }
}

/// Hands out scripted sockets or failures in order, recording each attempt. Once the script is exhausted,
/// further attempts wait until cancelled, like a server that never answers.
public actor ScriptedGatewayTransport: GatewayTransport {
    public enum Step: Sendable {
        case socket(ScriptedGatewaySocket)
        case failure(HermesGatewayError)
    }

    private var steps: [Step]
    public private(set) var attempts = 0
    public private(set) var urls: [URL] = []
    public private(set) var headers: [[String: String]] = []
    public private(set) var subprotocols: [[String]] = []

    public init(_ steps: [Step]) { self.steps = steps }

    public init(_ sockets: [ScriptedGatewaySocket]) { self.steps = sockets.map(Step.socket) }

    public func connect(url: URL, headers: [String: String], subprotocols: [String]) async throws -> any GatewayConnection {
        urls.append(url)
        self.headers.append(headers)
        self.subprotocols.append(subprotocols)
        attempts += 1
        guard !steps.isEmpty else {
            try await Task.sleep(for: .seconds(3600))
            throw HermesGatewayError.cancelled
        }
        switch steps.removeFirst() {
        case .socket(let socket): return socket
        case .failure(let error): throw error
        }
    }
}

/// A network monitor tests move between paths with `change(_:)`. `paths()` serves one subscriber.
public final class ScriptedNetworkMonitor: GatewayNetworkMonitor {
    public static let wifi = GatewayNetworkPath(isAvailable: true, interface: "wifi:en0")
    public static let cellular = GatewayNetworkPath(isAvailable: true, interface: "cellular:pdp_ip0")
    public static let offline = GatewayNetworkPath(isAvailable: false, interface: nil)

    private let stream: AsyncStream<GatewayNetworkPath>
    private let continuation: AsyncStream<GatewayNetworkPath>.Continuation

    public init(_ initial: GatewayNetworkPath = ScriptedNetworkMonitor.wifi) {
        (stream, continuation) = AsyncStream.makeStream(of: GatewayNetworkPath.self)
        continuation.yield(initial)
    }

    public func paths() -> AsyncStream<GatewayNetworkPath> { stream }

    public func change(_ path: GatewayNetworkPath) { continuation.yield(path) }
}

/// A gateway credential that needs no HTTP: a fixed ticket and headers.
public struct StaticTicketAuth: HermesAuth {
    public let ticket: String
    public let headers: [String: String]

    public init(ticket: String = "test-ticket", headers: [String: String] = [:]) {
        self.ticket = ticket
        self.headers = headers
    }

    public func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        .ticket(ticket, headers: headers)
    }
}
