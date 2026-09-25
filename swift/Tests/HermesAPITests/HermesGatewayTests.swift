import Foundation
import HermesAPI
import Testing

// MARK: - Fakes

private struct TestAuth: HermesAuth {
    func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        .ticket("test-ticket", headers: ["Authorization": "Bearer test"])
    }
}

private struct TicketHTTPTransport: HTTPTransport {
    var status = 200

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        #expect(request.url?.path == "/api/auth/ws-ticket")
        #expect(request.httpMethod == "POST")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer test")
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil) else {
            throw HermesGatewayError.transport("Invalid test HTTP response")
        }
        return (Data(#"{"ticket":"fresh-ticket","ttl_seconds":30}"#.utf8), response)
    }
}

/// One fake Hermes socket. It answers `client.capabilities`, optionally answers heartbeats, and hands
/// every other frame the client sends to `sent`.
private actor TestSocket: GatewayConnection {
    nonisolated let sent: AsyncStream<Data>
    nonisolated let heartbeats: AsyncStream<String>
    private let sentContinuation: AsyncStream<Data>.Continuation
    private let heartbeatContinuation: AsyncStream<String>.Continuation
    private var incoming: [Data] = []
    private var waiters: [CheckedContinuation<Data, Error>] = []
    private var failure: HermesGatewayError?
    private let answersHeartbeats: Bool
    private(set) var isClosed = false

    init(answersHeartbeats: Bool = false) {
        (sent, sentContinuation) = AsyncStream.makeStream(of: Data.self)
        (heartbeats, heartbeatContinuation) = AsyncStream.makeStream(of: String.self)
        self.answersHeartbeats = answersHeartbeats
    }

    func send(_ frame: Data) throws {
        guard !isClosed else { throw HermesGatewayError.transport("closed") }
        guard let object = try JSONSerialization.jsonObject(with: frame) as? [String: Any] else {
            throw HermesGatewayError.decoding("Invalid test frame")
        }
        switch object["method"] as? String {
        case "client.capabilities":
            let id = object["id"] as? Int ?? 0
            inject(#"{"jsonrpc":"2.0","id":\#(id),"result":{"server_requests":["approval"]}}"#)
        case "gateway.ping":
            let id = object["id"] as? String ?? ""
            heartbeatContinuation.yield(id)
            if answersHeartbeats { inject(#"{"jsonrpc":"2.0","id":"\#(id)","result":{"ok":true}}"#) }
        default:
            sentContinuation.yield(frame)
        }
    }

    func receive() async throws -> Data {
        if let failure { throw failure }
        if !incoming.isEmpty { return incoming.removeFirst() }
        return try await withCheckedThrowingContinuation { waiters.append($0) }
    }

    func inject(_ json: String) {
        let frame = Data(json.utf8)
        if !waiters.isEmpty { waiters.removeFirst().resume(returning: frame) } else { incoming.append(frame) }
    }

    /// Hermes (or the network) ends the socket.
    func sever(_ error: HermesGatewayError = .transport("closed")) {
        failure = error
        for waiter in waiters { waiter.resume(throwing: error) }
        waiters.removeAll()
    }

    func close() {
        isClosed = true
        sever()
        sentContinuation.finish()
        heartbeatContinuation.finish()
    }
}

/// Hands out scripted sockets or failures in order; once exhausted, attempts wait until cancelled.
private actor SequenceTransport: GatewayTransport {
    enum Step {
        case socket(TestSocket)
        case failure(HermesGatewayError)
    }

    private var steps: [Step]
    private(set) var attempts = 0

    init(_ steps: [Step]) { self.steps = steps }

    init(_ sockets: [TestSocket]) { self.steps = sockets.map(Step.socket) }

    func connect(url: URL, headers: [String: String], subprotocols: [String]) async throws -> any GatewayConnection {
        #expect(url.path == "/api/ws")
        #expect(subprotocols == ["hermes-gateway-v1", "hermes-gateway-ticket.test-ticket"])
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

private final class TestNetworkMonitor: GatewayNetworkMonitor {
    private let stream: AsyncStream<GatewayNetworkPath>
    private let continuation: AsyncStream<GatewayNetworkPath>.Continuation

    init(_ initial: GatewayNetworkPath) {
        (stream, continuation) = AsyncStream.makeStream(of: GatewayNetworkPath.self)
        continuation.yield(initial)
    }

    func paths() -> AsyncStream<GatewayNetworkPath> { stream }

    func change(_ path: GatewayNetworkPath) { continuation.yield(path) }
}

private let wifi = GatewayNetworkPath(isAvailable: true, interface: "wifi:en0")
private let cellular = GatewayNetworkPath(isAvailable: true, interface: "cellular:pdp_ip0")
private let offline = GatewayNetworkPath(isAvailable: false, interface: nil)

private func client(
    _ transport: SequenceTransport, timeout: Duration = .seconds(1), monitor: TestNetworkMonitor? = nil,
    reconnectDelay: Duration = .zero, heartbeat: Duration = .seconds(60), deadline: Duration = .seconds(120)
) -> HermesGateway {
    HermesGateway(configuration: .init(
        baseURL: URL(string: "https://example.test")!, auth: TestAuth(), transport: transport,
        networkMonitor: monitor, requestTimeout: timeout, reconnectDelay: { _ in reconnectDelay },
        heartbeatInterval: heartbeat, heartbeatDeadline: deadline
    ))
}

private func client(socket: TestSocket, timeout: Duration = .seconds(1)) -> HermesGateway {
    client(SequenceTransport([socket]), timeout: timeout)
}

private func sentCall(_ frame: Data) throws -> (String, Int) {
    let object = try #require(JSONSerialization.jsonObject(with: frame) as? [String: Any])
    return (try #require(object["method"] as? String), try #require(object["id"] as? Int))
}

private func sentParams(_ frame: Data) throws -> [String: Any] {
    let object = try #require(JSONSerialization.jsonObject(with: frame) as? [String: Any])
    return try #require(object["params"] as? [String: Any])
}

private func event(_ type: String, session: String, seq: Int, payload: String = "{}") -> String {
    #"{"jsonrpc":"2.0","method":"event","params":{"type":"\#(type)","session_id":"\#(session)","seq":\#(seq),"payload":\#(payload)}}"#
}

private func result(_ id: Int, _ json: String) -> String { #"{"jsonrpc":"2.0","id":\#(id),"result":\#(json)}"# }

private func error(_ id: Int, code: Int, _ message: String) -> String {
    #"{"jsonrpc":"2.0","id":\#(id),"error":{"code":\#(code),"message":"\#(message)"}}"#
}

/// Waits (bounded) for the gateway to report a matching state.
private func state(
    of gateway: HermesGateway, within limit: Duration = .seconds(2),
    _ matches: @escaping @Sendable (GatewayConnectionState) -> Bool
) async throws {
    let states = gateway.connectionStates()
    try await withThrowingTaskGroup(of: Void.self) { group in
        group.addTask { for await state in states where matches(state) { return } }
        group.addTask {
            try await Task.sleep(for: limit)
            throw HermesGatewayError.timeout
        }
        defer { group.cancelAll() }
        try await group.next()
    }
}

private func isReconnecting(_ state: GatewayConnectionState) -> Bool {
    if case .reconnecting = state { true } else { false }
}

/// Creates a session through the client and answers it with a stored id.
private func createSession(
    _ gateway: HermesGateway, on socket: TestSocket, id: String, stored: String, closeOnDisconnect: Bool? = nil
) async throws {
    var sent = socket.sent.makeAsyncIterator()
    let created = Task {
        try await gateway.methods.session.create(.init(closeOnDisconnect: closeOnDisconnect))
    }
    let (method, createID) = try sentCall(try #require(await sent.next()))
    #expect(method == "session.create")
    await socket.inject(result(createID, #"{"session_id":"\#(id)","stored_session_id":"\#(stored)","message_count":0,"messages":[],"info":{}}"#))
    _ = try? await created.value
}

// MARK: - Calls

@Test func correlatesOutOfOrderResponses() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    try await gateway.connect()
    var sent = socket.sent.makeAsyncIterator()
    let first = Task { try await gateway.call("first", params: PingParams(), as: PingResult.self) }
    let second = Task { try await gateway.call("second", params: PingParams(), as: PingResult.self) }
    let callA = try sentCall(try #require(await sent.next()))
    let callB = try sentCall(try #require(await sent.next()))
    let ids = Dictionary(uniqueKeysWithValues: [callA, callB])
    await socket.inject(result(try #require(ids["second"]), #"{"pong":false}"#))
    await socket.inject(result(try #require(ids["first"]), #"{"pong":true}"#))
    #expect(try await first.value.pong)
    #expect(try await !second.value.pong)
    await gateway.disconnect()
}

@Test func rejectsIncompatibleDesktopContract() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    try await gateway.connect()
    var sent = socket.sent.makeAsyncIterator()
    let request = Task { try await gateway.call("contract", params: PingParams(), as: PingResult.self) }
    let (_, id) = try sentCall(try #require(await sent.next()))
    await socket.inject(result(id, #"{"info":{"desktop_contract":8}}"#))
    await #expect(throws: HermesGatewayError.incompatibleServer(8)) { try await request.value }
    await gateway.disconnect()
}

@Test func timesOutAndCancelsCalls() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket, timeout: .milliseconds(80))
    try await gateway.connect()
    var sent = socket.sent.makeAsyncIterator()
    let timed = Task { try await gateway.call("hang", params: PingParams(), as: PingResult.self) }
    _ = await sent.next()
    await #expect(throws: HermesGatewayError.timeout) { try await timed.value }
    let cancelled = Task { try await gateway.call("cancel", params: PingParams(), as: PingResult.self) }
    _ = await sent.next()
    cancelled.cancel()
    await #expect(throws: HermesGatewayError.cancelled) { try await cancelled.value }
    await gateway.disconnect()
}

@Test func callsDuringAReconnectWaitForTheNewSocket() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let gateway = client(SequenceTransport([first, second]), reconnectDelay: .milliseconds(150))
    try await gateway.connect()
    await first.sever()
    try await state(of: gateway, isReconnecting)
    let probe = Task { try await gateway.call("probe", params: PingParams(), as: PingResult.self) }
    var sent = second.sent.makeAsyncIterator()
    let (method, id) = try sentCall(try #require(await sent.next()))
    #expect(method == "probe")
    await second.inject(result(id, #"{"pong":true}"#))
    #expect(try await probe.value.pong)
    await gateway.disconnect()
}

@Test func retiringBackendTriggersAReconnect() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let transport = SequenceTransport([first, second])
    let gateway = client(transport)
    try await gateway.connect()
    var sent = first.sent.makeAsyncIterator()
    let call = Task { try await gateway.call("probe", params: PingParams(), as: PingResult.self) }
    let (_, id) = try sentCall(try #require(await sent.next()))
    await first.inject(error(id, code: 5035, "backend is retiring; reconnect to continue"))
    await #expect(throws: HermesGatewayError.self) { try await call.value }
    try await state(of: gateway) { $0 == .connected }
    #expect(await transport.attempts == 2)
    #expect(await first.isClosed)
    await gateway.disconnect()
}

// MARK: - Authentication

@Test func ticketAuthUsesAuthenticatedPost() async throws {
    let auth = DashboardTicketAuth { ["Authorization": "Bearer test"] }
    let base = try #require(URL(string: "https://example.test"))
    let credential = try await auth.credential(baseURL: base, http: TicketHTTPTransport())
    guard case .ticket(let ticket, let headers) = credential else {
        Issue.record("Expected ticket credential")
        return
    }
    #expect(ticket == "fresh-ticket")
    #expect(headers["Authorization"] == "Bearer test")
}

@Test func rejectedTicketIsAnAuthenticationFailure() async throws {
    let auth = DashboardTicketAuth { ["Authorization": "Bearer test"] }
    let base = try #require(URL(string: "https://example.test"))
    await #expect(throws: HermesGatewayError.authenticationFailed("WebSocket ticket request returned HTTP 401")) {
        try await auth.credential(baseURL: base, http: TicketHTTPTransport(status: 401))
    }
}

@Test func initialAuthenticationFailureIsTerminal() async throws {
    let rejected = HermesGatewayError.authenticationFailed("WebSocket upgrade returned HTTP 403")
    let transport = SequenceTransport([.failure(rejected)])
    let gateway = client(transport)
    await #expect(throws: rejected) { try await gateway.connect() }
    #expect(gateway.connectionState == .failed(rejected))
    try await Task.sleep(for: .milliseconds(100))
    #expect(await transport.attempts == 1)
}

@Test func authenticationFailureDuringReconnectStopsRetrying() async throws {
    let first = TestSocket()
    let rejected = HermesGatewayError.authenticationFailed("WebSocket upgrade returned HTTP 403")
    let transport = SequenceTransport([.socket(first), .failure(rejected)])
    let gateway = client(transport)
    try await gateway.connect()
    await first.sever()
    try await state(of: gateway) { $0 == .failed(rejected) }
    try await Task.sleep(for: .milliseconds(100))
    #expect(await transport.attempts == 2)
}

// MARK: - Events

@Test func deduplicatesSequencedEvents() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    try await gateway.connect()
    var events = gateway.events().makeAsyncIterator()
    await socket.inject(event("message.start", session: "s", seq: 1))
    await socket.inject(event("message.start", session: "s", seq: 1))
    await socket.inject(event("message.start", session: "s", seq: 2))
    #expect(await events.next()?.seq == 1)
    #expect(await events.next()?.seq == 2)
    await gateway.disconnect()
}

@Test func everySubscriberReceivesEveryEvent() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    try await gateway.connect()
    var first = gateway.events().makeAsyncIterator()
    var second = gateway.events().makeAsyncIterator()
    await socket.inject(event("message.start", session: "s", seq: 1))
    await socket.inject(event("message.start", session: "s", seq: 2))
    #expect(await first.next()?.seq == 1)
    #expect(await first.next()?.seq == 2)
    #expect(await second.next()?.seq == 1)
    #expect(await second.next()?.seq == 2)
    await gateway.disconnect()
}

@Test func undecodableEventPayloadKeepsTheConnection() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    try await gateway.connect()
    var events = gateway.events().makeAsyncIterator()
    await socket.inject(event("message.delta", session: "s", seq: 1, payload: #"{"text":42}"#))
    await socket.inject("not json")
    await socket.inject(event("message.start", session: "s", seq: 2))
    let malformed = try #require(await events.next())
    guard case .unknown(let type, let raw) = malformed.payload else {
        Issue.record("Expected the raw payload for an undecodable event")
        return
    }
    #expect(type == "message.delta" && raw == .object(["text": .integer(42)]))
    #expect(await events.next()?.seq == 2)
    #expect(gateway.connectionState == .connected)
    await gateway.disconnect()
}

// MARK: - Server requests

@Test func answersTypedServerRequest() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    await gateway.setServerRequestHandler { request in
        guard case .approval(let params) = request else { throw HermesGatewayError.decoding("Unexpected request") }
        #expect(params.requestId == "req")
        return .approval(ApprovalResult(choice: .once))
    }
    try await gateway.connect()
    var sent = socket.sent.makeAsyncIterator()
    await socket.inject(#"{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s","request_id":"req"}}"#)
    let replyFrame = try #require(await sent.next())
    let reply = try #require(JSONSerialization.jsonObject(with: replyFrame) as? [String: Any])
    #expect(reply["id"] as? String == "srq-1")
    #expect((reply["result"] as? [String: Any])?["choice"] as? String == "once")
    await gateway.disconnect()
}

@Test func rejectsServerRequestWithInvalidParams() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    await gateway.setServerRequestHandler { _ in .approval(ApprovalResult(choice: .deny)) }
    try await gateway.connect()
    var sent = socket.sent.makeAsyncIterator()
    await socket.inject(#"{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"command":7}}"#)
    let frame = try #require(await sent.next())
    let object = try #require(JSONSerialization.jsonObject(with: frame) as? [String: Any])
    #expect(object["id"] as? String == "srq-1")
    #expect((object["error"] as? [String: Any])?["code"] as? Int == -32602)
    await gateway.disconnect()
}

@Test func handlerThatGivesUpIsAnsweredWithAnError() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    await gateway.setServerRequestHandler { _ in throw CancellationError() }
    try await gateway.connect()
    var sent = socket.sent.makeAsyncIterator()
    await socket.inject(#"{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s","request_id":"req"}}"#)
    let frame = try #require(await sent.next())
    let object = try #require(JSONSerialization.jsonObject(with: frame) as? [String: Any])
    #expect(object["id"] as? String == "srq-1")
    #expect((object["error"] as? [String: Any])?["code"] as? Int == -32603)
    await gateway.disconnect()
}

@Test func withdrawnRequestIsNotAnswered() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    await gateway.setServerRequestHandler { _ in
        try await Task.sleep(for: .seconds(3600))
        return .approval(ApprovalResult(choice: .deny))
    }
    try await gateway.connect()
    var sent = socket.sent.makeAsyncIterator()
    await socket.inject(#"{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s","request_id":"req"}}"#)
    await socket.inject(#"{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","session_id":"s","payload":{"id":"srq-1","method":"approval","reason":"timeout"}}}"#)
    try await Task.sleep(for: .milliseconds(100))
    // The next frame is the caller's own call, not an answer to the withdrawn request.
    let ping = Task { try await gateway.call("ping", params: PingParams(), as: PingResult.self) }
    let (method, id) = try sentCall(try #require(await sent.next()))
    #expect(method == "ping")
    await socket.inject(result(id, #"{"pong":true}"#))
    #expect(try await ping.value.pong)
    await gateway.disconnect()
}

// MARK: - Reconnect and session recovery

@Test func reconnectReplaysGapBeforeLiveEvents() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let gateway = client(SequenceTransport([first, second]))
    try await gateway.connect()
    var events = gateway.events().makeAsyncIterator()
    await first.inject(event("message.start", session: "s", seq: 1))
    #expect(await events.next()?.seq == 1)
    var sent = second.sent.makeAsyncIterator()
    await first.sever()
    let (activate, activateID) = try sentCall(try #require(await sent.next()))
    #expect(activate == "session.activate")
    // A live frame racing the rebind is held until the gap replay lands.
    await second.inject(event("message.start", session: "s", seq: 3))
    await second.inject(result(activateID, #"{"session_id":"s"}"#))
    let (method, replayID) = try sentCall(try #require(await sent.next()))
    #expect(method == "session.events.since")
    await second.inject(result(replayID, #"{"events":[{"type":"message.start","session_id":"s","seq":2,"payload":{}}],"latest_seq":3,"truncated":false,"count":1,"epoch":"same","open_requests":[]}"#))
    let replayed = try #require(await events.next())
    let live = try #require(await events.next())
    #expect(replayed.seq == 2 && replayed.replayed)
    #expect(live.seq == 3 && !live.replayed)
    await gateway.disconnect()
}

@Test func reconnectRebindsCreatedSessionsWithoutEvents() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let gateway = client(SequenceTransport([first, second]))
    try await gateway.connect()
    try await createSession(gateway, on: first, id: "quiet", stored: "stored-quiet")
    var sent = second.sent.makeAsyncIterator()
    await first.sever()
    let frame = try #require(await sent.next())
    let params = try sentParams(frame)
    #expect(try sentCall(frame).0 == "session.activate")
    #expect(params["session_id"] as? String == "quiet")
    #expect(params["omit_messages"] as? Bool == true)
    await gateway.disconnect()
}

@Test func reconnectResumesReclaimedSessions() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let gateway = client(SequenceTransport([first, second]))
    try await gateway.connect()
    try await createSession(gateway, on: first, id: "runtime-1", stored: "stored-1")
    var recoveries = gateway.sessionRecoveries().makeAsyncIterator()
    var sent = second.sent.makeAsyncIterator()
    await first.sever()
    let (_, activateID) = try sentCall(try #require(await sent.next()))
    await second.inject(error(activateID, code: 4001, "session not found"))
    let resume = try #require(await sent.next())
    let (method, resumeID) = try sentCall(resume)
    #expect(method == "session.resume")
    #expect(try sentParams(resume)["session_id"] as? String == "stored-1")
    await second.inject(result(resumeID, #"{"session_id":"runtime-2","session_key":"stored-1","message_count":3,"messages":[],"info":{}}"#))
    #expect(await recoveries.next() == .resumed(previousSessionID: "runtime-1", sessionID: "runtime-2", storedSessionID: "stored-1"))
    try await state(of: gateway) { $0 == .connected }
    await gateway.disconnect()
}

@Test func reconnectReportsSessionsItCannotRecover() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let gateway = client(SequenceTransport([first, second]))
    try await gateway.connect()
    var events = gateway.events().makeAsyncIterator()
    await first.inject(event("message.start", session: "gone", seq: 4))
    #expect(await events.next()?.seq == 4)
    var recoveries = gateway.sessionRecoveries().makeAsyncIterator()
    var sent = second.sent.makeAsyncIterator()
    await first.sever()
    let (method, activateID) = try sentCall(try #require(await sent.next()))
    #expect(method == "session.activate")
    await second.inject(error(activateID, code: 4001, "session not found"))
    // No stored id is known for a session only seen through events, so it cannot be resumed.
    #expect(await recoveries.next() == .unavailable(sessionID: "gone", reason: "session not found"))
    let ping = Task { try await gateway.call("ping", params: PingParams(), as: PingResult.self) }
    let (next, pingID) = try sentCall(try #require(await sent.next()))
    #expect(next == "ping")
    await second.inject(result(pingID, #"{"pong":true}"#))
    #expect(try await ping.value.pong)
    await gateway.disconnect()
}

@Test func truncatedReplayIsReported() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let gateway = client(SequenceTransport([first, second]))
    try await gateway.connect()
    try await createSession(gateway, on: first, id: "long", stored: "stored-long")
    var recoveries = gateway.sessionRecoveries().makeAsyncIterator()
    var sent = second.sent.makeAsyncIterator()
    await first.sever()
    let (_, activateID) = try sentCall(try #require(await sent.next()))
    await second.inject(result(activateID, #"{"session_id":"long"}"#))
    let (_, replayID) = try sentCall(try #require(await sent.next()))
    await second.inject(result(replayID, #"{"events":[],"latest_seq":900,"truncated":true,"count":0,"epoch":"same","open_requests":[]}"#))
    #expect(await recoveries.next() == .replayTruncated(sessionID: "long"))
    await gateway.disconnect()
}

@Test func closedAndCloseOnDisconnectSessionsAreNotRebound() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let gateway = client(SequenceTransport([first, second]))
    try await gateway.connect()
    try await createSession(gateway, on: first, id: "done", stored: "stored-done")
    try await createSession(gateway, on: first, id: "ephemeral", stored: "stored-e", closeOnDisconnect: true)
    var firstSent = first.sent.makeAsyncIterator()
    let closed = Task {
        try await gateway.call("session.close", params: SessionCloseParams(sessionId: "done"), as: JSONValue.self)
    }
    let (_, closeID) = try sentCall(try #require(await firstSent.next()))
    await first.inject(result(closeID, #"{"closed":true}"#))
    _ = try await closed.value
    var recoveries = gateway.sessionRecoveries().makeAsyncIterator()
    var sent = second.sent.makeAsyncIterator()
    await first.sever()
    #expect(await recoveries.next() == .unavailable(sessionID: "ephemeral", reason: "Closed on disconnect"))
    try await state(of: gateway) { $0 == .connected }
    let ping = Task { try await gateway.call("ping", params: PingParams(), as: PingResult.self) }
    let (next, pingID) = try sentCall(try #require(await sent.next()))
    #expect(next == "ping")
    await second.inject(result(pingID, #"{"pong":true}"#))
    #expect(try await ping.value.pong)
    await gateway.disconnect()
}

@Test func reclaimedSessionIsReportedAndForgotten() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    try await gateway.connect()
    try await createSession(gateway, on: socket, id: "idle", stored: "stored-idle")
    var recoveries = gateway.sessionRecoveries().makeAsyncIterator()
    await socket.inject(#"{"jsonrpc":"2.0","method":"event","params":{"type":"session.reclaimed","session_id":"","payload":{"session_id":"idle","stored_session_id":"stored-idle","reason":"idle_timeout"}}}"#)
    #expect(await recoveries.next() == .reclaimed(sessionID: "idle", storedSessionID: "stored-idle", reason: "idle_timeout"))
    await gateway.disconnect()
}

// MARK: - Liveness

@Test func silentSocketIsReplacedAfterTheHeartbeatDeadline() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let transport = SequenceTransport([first, second])
    let gateway = client(transport, heartbeat: .milliseconds(40), deadline: .milliseconds(200))
    try await gateway.connect()
    var heartbeats = first.heartbeats.makeAsyncIterator()
    #expect(await heartbeats.next()?.hasPrefix("heartbeat-") == true)
    // The half-open first socket never answers; the gateway must move to the second one.
    try await state(of: gateway, isReconnecting)
    try await state(of: gateway) { $0 == .connected }
    #expect(await transport.attempts == 2)
    #expect(await first.isClosed)
    await gateway.disconnect()
}

@Test func answeredHeartbeatsKeepTheConnection() async throws {
    let socket = TestSocket(answersHeartbeats: true)
    let transport = SequenceTransport([socket])
    let gateway = client(transport, heartbeat: .milliseconds(40), deadline: .milliseconds(200))
    try await gateway.connect()
    var heartbeats = socket.heartbeats.makeAsyncIterator()
    for _ in 0..<8 { _ = await heartbeats.next() }
    #expect(gateway.connectionState == .connected)
    #expect(await transport.attempts == 1)
    await gateway.disconnect()
}

@Test func streamingTrafficNeedsNoHeartbeat() async throws {
    let socket = TestSocket()
    let gateway = client(SequenceTransport([socket]), heartbeat: .milliseconds(100), deadline: .milliseconds(400))
    try await gateway.connect()
    let stream = Task {
        for seq in 1...12 {
            await socket.inject(event("message.delta", session: "s", seq: seq, payload: #"{"text":"x"}"#))
            try await Task.sleep(for: .milliseconds(40))
        }
    }
    try await stream.value
    await socket.close()
    var heartbeats = socket.heartbeats.makeAsyncIterator()
    #expect(await heartbeats.next() == nil)
    await gateway.disconnect()
}

// MARK: - Network and app lifecycle

@Test func losingTheNetworkWaitsWithoutRetrying() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let transport = SequenceTransport([first, second])
    let monitor = TestNetworkMonitor(wifi)
    let gateway = client(transport, monitor: monitor)
    try await gateway.connect()
    monitor.change(offline)
    try await state(of: gateway) { $0 == .waitingForNetwork }
    #expect(await first.isClosed)
    try await Task.sleep(for: .milliseconds(150))
    #expect(await transport.attempts == 1)
    monitor.change(wifi)
    try await state(of: gateway) { $0 == .connected }
    #expect(await transport.attempts == 2)
    await gateway.disconnect()
}

@Test func switchingNetworksReconnectsAtOnce() async throws {
    let first = TestSocket(answersHeartbeats: true)
    let second = TestSocket()
    let transport = SequenceTransport([first, second])
    let monitor = TestNetworkMonitor(wifi)
    let gateway = client(transport, monitor: monitor, reconnectDelay: .seconds(30))
    try await gateway.connect()
    monitor.change(cellular)
    // The first retry after a path change is immediate, so a 30 s backoff never applies.
    try await state(of: gateway, isReconnecting)
    try await state(of: gateway) { $0 == .connected }
    #expect(await transport.attempts == 2)
    #expect(await first.isClosed)
    await gateway.disconnect()
}

@Test func backgroundClosesTheSocketAndForegroundReconnects() async throws {
    let first = TestSocket()
    let second = TestSocket()
    let transport = SequenceTransport([first, second])
    let gateway = client(transport)
    try await gateway.connect()
    await gateway.enterBackground()
    #expect(gateway.connectionState == .suspended)
    #expect(await first.isClosed)
    try await Task.sleep(for: .milliseconds(100))
    #expect(await transport.attempts == 1)
    await gateway.enterForeground()
    try await state(of: gateway) { $0 == .connected }
    #expect(await transport.attempts == 2)
    await gateway.disconnect()
}

@Test func backgroundLetsARunningTurnFinish() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    try await gateway.connect()
    var events = gateway.events().makeAsyncIterator()
    await socket.inject(event("message.start", session: "s", seq: 1))
    _ = await events.next()
    let backgrounded = Task { await gateway.enterBackground(grace: .seconds(5)) }
    try await Task.sleep(for: .milliseconds(100))
    #expect(await !socket.isClosed)
    await socket.inject(event("message.complete", session: "s", seq: 2, payload: #"{"text":"done"}"#))
    await backgrounded.value
    #expect(await socket.isClosed)
    #expect(gateway.connectionState == .suspended)
    await gateway.disconnect()
}

@Test func backgroundGraceBoundsAStuckTurn() async throws {
    let socket = TestSocket()
    let gateway = client(socket: socket)
    try await gateway.connect()
    var events = gateway.events().makeAsyncIterator()
    await socket.inject(event("message.start", session: "s", seq: 1))
    _ = await events.next()
    await gateway.enterBackground(grace: .milliseconds(100))
    #expect(await socket.isClosed)
    #expect(gateway.connectionState == .suspended)
    await gateway.disconnect()
}
