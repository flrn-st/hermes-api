import Foundation
import HermesAPI
import os

/// A stand-in for `HermesGateway` behind `HermesGatewayClient`, with no socket. Calls are answered by a
/// responder; tests push events, connection states and recoveries, and put server requests to the app's
/// handler with `request(_:)`.
public actor FakeGateway: HermesGatewayClient {
    public typealias Responder = ScriptedGatewayCaller.Responder

    private let respond: Responder
    private let eventFanout = Fanout<GatewayEvent>()
    private let stateFanout = Fanout<GatewayConnectionState>(latest: .idle)
    private let recoveryFanout = Fanout<GatewaySessionRecovery>()
    private var handler: (@Sendable (ServerRequest) async throws -> ServerRequestResult)?

    public private(set) var calls: [RecordedCall] = []
    public private(set) var backendContract: Int?
    /// Lifecycle calls in order: `connect`, `disconnect`, `enterBackground`, `enterForeground`.
    public private(set) var lifecycle: [String] = []
    /// What `connect()` throws, if anything; otherwise it moves to `.connected`.
    public var connectFailure: HermesGatewayError?

    public init(backendContract: Int? = HermesGatewayContract.desktopContract, _ respond: @escaping Responder = { method, _ in
        throw HermesGatewayError.rpc(code: -32601, message: "Method not found: \(method)", data: nil)
    }) {
        self.backendContract = backendContract
        self.respond = respond
    }

    public init(results: [String: JSONValue]) {
        self.init { method, _ in
            guard let result = results[method] else {
                throw HermesGatewayError.rpc(code: -32601, message: "Method not found: \(method)", data: nil)
            }
            return result
        }
    }

    // MARK: HermesGatewayClient

    public nonisolated func events() -> AsyncStream<GatewayEvent> { eventFanout.subscribe() }
    public nonisolated func connectionStates() -> AsyncStream<GatewayConnectionState> { stateFanout.subscribe() }
    public nonisolated var connectionState: GatewayConnectionState { stateFanout.latest ?? .idle }
    public nonisolated func sessionRecoveries() -> AsyncStream<GatewaySessionRecovery> { recoveryFanout.subscribe() }

    public func setServerRequestHandler(
        _ handler: @escaping @Sendable (ServerRequest) async throws -> ServerRequestResult
    ) {
        self.handler = handler
    }

    public func connect() async throws {
        lifecycle.append("connect")
        if let connectFailure {
            stateFanout.yield(.failed(connectFailure))
            throw connectFailure
        }
        stateFanout.yield(.connected)
    }

    public func disconnect() async {
        lifecycle.append("disconnect")
        stateFanout.yield(.idle)
    }

    public func enterBackground(grace: Duration) async {
        lifecycle.append("enterBackground")
        stateFanout.yield(.suspended)
    }

    public func enterForeground() async {
        lifecycle.append("enterForeground")
        stateFanout.yield(.connected)
    }

    public func call<Params: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, params: Params, as resultType: Result.Type
    ) async throws -> Result {
        let encoded = try JSONValue(jsonData: JSONEncoder().encode(params))
        calls.append(RecordedCall(method: method, params: encoded))
        let result = try await respond(method, encoded)
        do {
            return try JSONDecoder().decode(resultType, from: JSONEncoder().encode(result))
        } catch {
            throw HermesGatewayError.decoding(error.localizedDescription)
        }
    }

    // MARK: Driving the fake

    public func setBackendContract(_ contract: Int?) { backendContract = contract }

    /// Delivers an event to every `events()` subscriber.
    public func emit(_ event: GatewayEvent) { eventFanout.yield(event) }

    /// Decodes `payload` as the generated model for `type`, as the gateway does, and delivers it.
    public func emit(_ type: String, session: String? = nil, seq: Int? = nil, payload: JSONValue = .object([:]),
                     replayed: Bool = false) {
        let decoded = (try? GatewayEventPayload.decode(type: type, payload: payload)) ?? .unknown(type: type, raw: payload)
        eventFanout.yield(GatewayEvent(type: type, sessionID: session, seq: seq, payload: decoded, replayed: replayed))
    }

    public func setConnectionState(_ state: GatewayConnectionState) { stateFanout.yield(state) }

    public func recover(_ recovery: GatewaySessionRecovery) { recoveryFanout.yield(recovery) }

    /// Puts a server request to the app's handler and returns its answer, checked as the gateway checks it.
    public func request(_ request: ServerRequest) async throws -> ServerRequestResult {
        guard let handler else {
            throw HermesGatewayError.rpc(code: -32601, message: "No server request handler", data: nil)
        }
        let result = try await handler(request)
        guard result.matches(request) else {
            throw HermesGatewayError.decoding("Server request result kind does not match the request")
        }
        return result
    }

    /// Decodes a server request from its wire method and params, as the gateway does, and puts it to the handler.
    public func request(method: String, params: JSONValue) async throws -> ServerRequestResult {
        try await request(ServerRequest.decode(method: method, params: params))
    }
}

/// Fans values out to `AsyncStream` subscribers, each buffered independently; optionally replays the latest.
private final class Fanout<Element: Sendable>: Sendable {
    private struct State: Sendable {
        var nextID = 0
        var subscribers: [Int: AsyncStream<Element>.Continuation] = [:]
        var latest: Element?
    }

    private let state: OSAllocatedUnfairLock<State>
    private let replaysLatest: Bool

    init(latest: Element? = nil) {
        state = OSAllocatedUnfairLock(initialState: State(latest: latest))
        replaysLatest = latest != nil
    }

    var latest: Element? { state.withLock { $0.latest } }

    func subscribe() -> AsyncStream<Element> {
        let (stream, continuation) = AsyncStream.makeStream(of: Element.self)
        let id = state.withLock { state -> Int in
            let id = state.nextID
            state.nextID += 1
            state.subscribers[id] = continuation
            if replaysLatest, let latest = state.latest { continuation.yield(latest) }
            return id
        }
        continuation.onTermination = { [weak self] _ in
            _ = self?.state.withLock { $0.subscribers.removeValue(forKey: id) }
        }
        return stream
    }

    func yield(_ value: Element) {
        state.withLock { state in
            if replaysLatest { state.latest = value }
            for continuation in state.subscribers.values { continuation.yield(value) }
        }
    }
}
