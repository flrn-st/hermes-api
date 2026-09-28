import Foundation
import HermesAPI
import HermesAPITesting
import Testing

// MARK: - Fakes

private struct TestAuth: HermesAuth {
    func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        .ticket("test-ticket", headers: ["Authorization": "Bearer test"])
    }
}

private struct TicketHTTPTransport: HTTPTransport {
    var status = 200
    var path = "/api/auth/ws-ticket"

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        #expect(request.url?.path == path)
        #expect(request.httpMethod == "POST")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer test")
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil) else {
            throw HermesGatewayError.transport("Invalid test HTTP response")
        }
        return (Data(#"{"ticket":"fresh-ticket","ttl_seconds":30}"#.utf8), response)
    }
}

private let wifi = ScriptedNetworkMonitor.wifi
private let cellular = ScriptedNetworkMonitor.cellular
private let offline = ScriptedNetworkMonitor.offline

private func client(
    _ transport: ScriptedGatewayTransport, timeout: Duration = .seconds(1), monitor: ScriptedNetworkMonitor? = nil,
    reconnectDelay: Duration = .zero, heartbeat: Duration = .seconds(60), deadline: Duration = .seconds(120),
    minimumContract: Int = HermesGatewayContract.desktopContract,
    baseURL: URL = URL(string: "https://example.test")!, auth: any HermesAuth = TestAuth(),
    httpTransport: any HTTPTransport = TicketHTTPTransport()
) -> HermesGateway {
    HermesGateway(configuration: .init(
        address: HermesDashboardAddress(baseURL), auth: auth, transport: transport, httpTransport: httpTransport,
        networkMonitor: monitor, requestTimeout: timeout, reconnectDelay: { _ in reconnectDelay },
        heartbeatInterval: heartbeat, heartbeatDeadline: deadline, minimumContract: minimumContract
    ))
}

private func client(
    socket: ScriptedGatewaySocket, timeout: Duration = .seconds(1), minimumContract: Int = HermesGatewayContract.desktopContract
) -> HermesGateway {
    client(ScriptedGatewayTransport([socket]), timeout: timeout, minimumContract: minimumContract)
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
    GatewayFrames.event(type, session: session, seq: seq, payload: payload)
}

private func result(_ id: Int, _ json: String) -> String { GatewayFrames.result(id, json) }

private func error(_ id: Int, code: Int, _ message: String) -> String { GatewayFrames.error(id, code: code, message) }

/// Waits (bounded) for the gateway to report a matching state.
private func state(
    of gateway: HermesGateway, within limit: Duration = .seconds(5),
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
    _ gateway: HermesGateway, on socket: ScriptedGatewaySocket, id: String, stored: String, closeOnDisconnect: Bool? = nil
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
    let socket = ScriptedGatewaySocket()
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

/// Answers one call with a session result that reports `contract`.
private func callReporting(contract: Int, through gateway: HermesGateway, on socket: ScriptedGatewaySocket) async throws -> PingResult {
    var sent = socket.sent.makeAsyncIterator()
    let request = Task { try await gateway.call("contract", params: PingParams(), as: PingResult.self) }
    let (_, id) = try sentCall(try #require(await sent.next()))
    await socket.inject(result(id, #"{"pong":true,"info":{"desktop_contract":\#(contract)}}"#))
    return try await request.value
}

@Test func rejectsABackendBelowTheMinimumContract() async throws {
    let socket = ScriptedGatewaySocket()
    let gateway = client(socket: socket)
    try await gateway.connect()
    let older = HermesGatewayContract.desktopContract - 1
    await #expect(throws: HermesGatewayError.incompatibleServer(older)) {
        try await callReporting(contract: older, through: gateway, on: socket)
    }
    #expect(await gateway.backendContract == older)
    await gateway.disconnect()
}

@Test func acceptsNewerContractsAndOlderOnesTheAppAllows() async throws {
    let socket = ScriptedGatewaySocket()
    let older = HermesGatewayContract.desktopContract - 1
    let gateway = client(socket: socket, minimumContract: older)
    try await gateway.connect()
    #expect(await gateway.backendContract == nil)
    #expect(try await callReporting(contract: HermesGatewayContract.desktopContract + 1, through: gateway, on: socket).pong)
    #expect(await gateway.backendContract == HermesGatewayContract.desktopContract + 1)
    #expect(try await callReporting(contract: older, through: gateway, on: socket).pong)
    #expect(await gateway.backendContract == older)
    await gateway.disconnect()
}

@Test func timesOutAndCancelsCalls() async throws {
    let socket = ScriptedGatewaySocket()
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
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]), reconnectDelay: .milliseconds(150))
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

@Test func readOnlyCallsAreRepeatedAfterALostConnection() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]), timeout: .seconds(5))
    try await gateway.connect()
    var sentFirst = first.sent.makeAsyncIterator()
    let call = Task { try await gateway.call("ping", params: PingParams(), as: PingResult.self) }
    _ = try #require(await sentFirst.next())
    await first.sever()
    var sentSecond = second.sent.makeAsyncIterator()
    let (method, id) = try sentCall(try #require(await sentSecond.next()))
    #expect(method == "ping")
    await second.inject(result(id, #"{"pong":true}"#))
    #expect(try await call.value.pong)
    await gateway.disconnect()
}

@Test func callsThatMayHaveRunAreNotRepeated() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]), timeout: .seconds(5))
    try await gateway.connect()
    var sentFirst = first.sent.makeAsyncIterator()
    let call = Task { try await gateway.call("probe", params: PingParams(), as: PingResult.self) }
    _ = try #require(await sentFirst.next())
    await first.sever()
    await #expect(throws: HermesGatewayError.transport("closed")) { try await call.value }
    // The next frame on the new socket is the next call, not a repeat of the one that may have run.
    try await state(of: gateway) { $0 == .connected }
    let next = Task { try await gateway.call("ping", params: PingParams(), as: PingResult.self) }
    var sentSecond = second.sent.makeAsyncIterator()
    let (method, id) = try sentCall(try #require(await sentSecond.next()))
    #expect(method == "ping")
    await second.inject(result(id, #"{"pong":true}"#))
    _ = try await next.value
    await gateway.disconnect()
}

@Test func undeliveredCallsAreSentOnTheNextConnection() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]), timeout: .seconds(5))
    try await gateway.connect()
    await first.failSends()
    let call = Task { try await gateway.call("probe", params: PingParams(), as: PingResult.self) }
    var sentSecond = second.sent.makeAsyncIterator()
    let (method, id) = try sentCall(try #require(await sentSecond.next()))
    #expect(method == "probe")
    await second.inject(result(id, #"{"pong":true}"#))
    #expect(try await call.value.pong)
    #expect(await first.isClosed)
    await gateway.disconnect()
}

@Test func retiringBackendTriggersAReconnect() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([first, second])
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

@Test func genericFailureWithTheRetiringCodeKeepsTheConnection() async throws {
    let socket = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([socket])
    let gateway = client(transport)
    try await gateway.connect()
    var sent = socket.sent.makeAsyncIterator()
    let call = Task { try await gateway.call("tools.configure", params: PingParams(), as: PingResult.self) }
    let (_, id) = try sentCall(try #require(await sent.next()))
    // tools.configure answers 5035 for its own failures too; only the retiring message means Hermes is leaving.
    await socket.inject(error(id, code: 5035, "could not write the toolset config"))
    await #expect(throws: HermesGatewayError.self) { try await call.value }
    let ping = Task { try await gateway.call("ping", params: PingParams(), as: PingResult.self) }
    let (_, pingID) = try sentCall(try #require(await sent.next()))
    await socket.inject(result(pingID, #"{"pong":true}"#))
    #expect(try await ping.value.pong)
    #expect(await transport.attempts == 1)
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

@Test func connectionsKeepTheBaseURLPath() async throws {
    let socket = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([socket])
    let gateway = client(
        transport, baseURL: try #require(URL(string: "https://relay.example/agents/box/")),
        auth: DashboardTicketAuth { ["Authorization": "Bearer test"] },
        httpTransport: TicketHTTPTransport(path: "/agents/box/api/auth/ws-ticket")
    )
    try await gateway.connect()
    #expect(await transport.urls.map(\.absoluteString) == ["wss://relay.example/agents/box/api/ws"])
    #expect(await transport.subprotocols == [["hermes-gateway-v1", "hermes-gateway-ticket.fresh-ticket"]])
    await gateway.disconnect()
}

/// Hands out dashboard addresses in order, keeping the last, and records the failure each resolve was given.
private actor AddressBook {
    private var urls: [URL]
    private(set) var failures: [HermesGatewayError?] = []

    init(_ urls: [String]) { self.urls = urls.compactMap(URL.init(string:)) }

    func next(after failure: (any Error)?) -> URL {
        failures.append(failure.map { $0 as? HermesGatewayError ?? .transport("unexpected \($0)") })
        return urls.count > 1 ? urls.removeFirst() : urls[0]
    }
}

@Test func everyConnectionAttemptResolvesTheAddressAfterThePreviousFailure() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([.failure(.transport("primary unreachable")), .socket(first), .socket(second)])
    let book = AddressBook(["https://primary.example", "https://fallback.example", "https://primary.example"])
    let gateway = HermesGateway(configuration: .init(
        address: HermesDashboardAddress { await book.next(after: $0) }, auth: TestAuth(), transport: transport,
        networkMonitor: nil, requestTimeout: .seconds(1), reconnectDelay: { _ in .zero }
    ))
    try await gateway.connect()
    let states = gateway.connectionStates()
    await first.sever(.transport("connection reset"))
    var reconnected = false
    for await state in states {
        if isReconnecting(state) { reconnected = true }
        if reconnected && state == .connected { break }
    }
    #expect(await transport.urls.map(\.absoluteString)
            == ["wss://primary.example/api/ws", "wss://fallback.example/api/ws", "wss://primary.example/api/ws"])
    #expect(await book.failures == [nil, .transport("primary unreachable"), .transport("connection reset")])
    await gateway.disconnect()
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
    let transport = ScriptedGatewayTransport([.failure(rejected)])
    let gateway = client(transport)
    await #expect(throws: rejected) { try await gateway.connect() }
    #expect(gateway.connectionState == .failed(rejected))
    try await Task.sleep(for: .milliseconds(100))
    #expect(await transport.attempts == 1)
}

/// A credential that renews on every rejection, counting the renewals.
private actor RenewingAuth: HermesAuth {
    private(set) var renewals = 0

    nonisolated func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        .ticket("test-ticket", headers: [:])
    }

    func renew(after failure: HermesGatewayError) -> Bool {
        renewals += 1
        return true
    }
}

@Test func aRenewedCredentialRetriesTheRejectedAttempt() async throws {
    let rejected = HermesGatewayError.authenticationFailed("WebSocket upgrade returned HTTP 401")
    let transport = ScriptedGatewayTransport([.failure(rejected), .socket(ScriptedGatewaySocket())])
    let auth = RenewingAuth()
    let gateway = client(transport, reconnectDelay: .seconds(60), auth: auth)
    try await gateway.connect()
    #expect(await transport.attempts == 2)
    #expect(await auth.renewals == 1)
    await gateway.disconnect()
}

@Test func aCredentialRenewsOncePerAttempt() async throws {
    let rejected = HermesGatewayError.authenticationFailed("WebSocket upgrade returned HTTP 403")
    let transport = ScriptedGatewayTransport([.failure(rejected), .failure(rejected)])
    let auth = RenewingAuth()
    let gateway = client(transport, auth: auth)
    await #expect(throws: rejected) { try await gateway.connect() }
    #expect(await transport.attempts == 2)
    #expect(await auth.renewals == 1)
}

@Test func authenticationFailureDuringReconnectStopsRetrying() async throws {
    let first = ScriptedGatewaySocket()
    let rejected = HermesGatewayError.authenticationFailed("WebSocket upgrade returned HTTP 403")
    let transport = ScriptedGatewayTransport([.socket(first), .failure(rejected)])
    let gateway = client(transport)
    try await gateway.connect()
    await first.sever()
    try await state(of: gateway) { $0 == .failed(rejected) }
    try await Task.sleep(for: .milliseconds(100))
    #expect(await transport.attempts == 2)
}

// MARK: - Events

@Test func deduplicatesSequencedEvents() async throws {
    let socket = ScriptedGatewaySocket()
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
    let socket = ScriptedGatewaySocket()
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
    let socket = ScriptedGatewaySocket()
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
    let socket = ScriptedGatewaySocket()
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
    let socket = ScriptedGatewaySocket()
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
    let socket = ScriptedGatewaySocket()
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
    let socket = ScriptedGatewaySocket()
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
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]))
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

@Test func failedReplayRetriesTheGapInsteadOfSkippingIt() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let third = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second, third]))
    try await gateway.connect()
    var events = gateway.events().makeAsyncIterator()
    await first.inject(event("message.start", session: "s", seq: 1))
    #expect(await events.next()?.seq == 1)
    var secondSent = second.sent.makeAsyncIterator()
    var thirdSent = third.sent.makeAsyncIterator()
    await first.sever()
    let (_, activateID) = try sentCall(try #require(await secondSent.next()))
    await second.inject(event("message.delta", session: "s", seq: 3, payload: #"{"text":"b"}"#))
    await second.inject(result(activateID, #"{"session_id":"s"}"#))
    let (_, replayID) = try sentCall(try #require(await secondSent.next()))
    // The socket stays up but the replay is unusable: releasing seq 3 now would skip seq 2 for good.
    await second.inject(result(replayID, #"{"unexpected":true}"#))
    let (activate, retryActivateID) = try sentCall(try #require(await thirdSent.next()))
    #expect(activate == "session.activate")
    await third.inject(result(retryActivateID, #"{"session_id":"s"}"#))
    let replay = try #require(await thirdSent.next())
    let (_, retryReplayID) = try sentCall(replay)
    #expect(try sentParams(replay)["last_seen"] as? Int == 1)
    await third.inject(result(retryReplayID, #"{"events":[{"type":"message.delta","session_id":"s","seq":2,"payload":{"text":"a"}},{"type":"message.delta","session_id":"s","seq":3,"payload":{"text":"b"}}],"latest_seq":3,"truncated":false,"count":2,"epoch":"same","open_requests":[]}"#))
    #expect(await events.next()?.seq == 2)
    #expect(await events.next()?.seq == 3)
    try await state(of: gateway) { $0 == .connected }
    await gateway.disconnect()
}

@Test func reconnectRebindsCreatedSessionsWithoutEvents() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]))
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
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]))
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

@Test func turnOfADroppedSessionKeepsStreamingFromTheReplayBuffer() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]))
    try await gateway.connect()
    try await createSession(gateway, on: first, id: "runtime-1", stored: "stored-1")
    var events = gateway.events().makeAsyncIterator()
    var recoveries = gateway.sessionRecoveries().makeAsyncIterator()
    await first.inject(event("message.start", session: "runtime-1", seq: 1))
    #expect(await events.next()?.seq == 1)
    var sent = second.sent.makeAsyncIterator()
    await first.sever()
    // Hermes dropped the session while its turn kept writing to the replay buffer.
    let (_, activateID) = try sentCall(try #require(await sent.next()))
    await second.inject(error(activateID, code: 4001, "session not found"))
    let replay = try #require(await sent.next())
    let (method, replayID) = try sentCall(replay)
    #expect(method == "session.events.since")
    #expect(try sentParams(replay)["session_id"] as? String == "runtime-1")
    await second.inject(result(replayID, #"{"events":[{"type":"message.delta","session_id":"runtime-1","seq":2,"payload":{"text":"a"}}],"latest_seq":2,"truncated":false,"count":1,"epoch":"same","open_requests":[]}"#))
    let (_, resumeID) = try sentCall(try #require(await sent.next()))
    await second.inject(result(resumeID, #"{"session_id":"runtime-2","session_key":"stored-1","message_count":1,"messages":[],"info":{}}"#))
    #expect(await recoveries.next() == .resumed(previousSessionID: "runtime-1", sessionID: "runtime-2", storedSessionID: "stored-1"))
    #expect(await events.next()?.seq == 2)
    // Connected again, the client keeps fetching the old id's buffer until the turn completes.
    let drain = try #require(await sent.next())
    let (drainMethod, drainID) = try sentCall(drain)
    #expect(drainMethod == "session.events.since")
    #expect(try sentParams(drain)["last_seen"] as? Int == 2)
    await second.inject(result(drainID, #"{"events":[{"type":"message.complete","session_id":"runtime-1","seq":3,"payload":{"text":"ab"}}],"latest_seq":3,"truncated":false,"count":1,"epoch":"same","open_requests":[]}"#))
    let complete = try #require(await events.next())
    #expect(complete.type == "message.complete" && complete.sessionID == "runtime-1" && complete.replayed)
    // The turn is over: the next frame is the caller's own call, not another fetch.
    let ping = Task { try await gateway.call("ping", params: PingParams(), as: PingResult.self) }
    let (next, pingID) = try sentCall(try #require(await sent.next()))
    #expect(next == "ping")
    await second.inject(result(pingID, #"{"pong":true}"#))
    #expect(try await ping.value.pong)
    await gateway.disconnect()
}

@Test func reconnectReportsSessionsItCannotRecover() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]))
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
    // Its turn was still running, so the client first fetches what Hermes buffered for it.
    let (replay, replayID) = try sentCall(try #require(await sent.next()))
    #expect(replay == "session.events.since")
    await second.inject(result(replayID, #"{"events":[],"latest_seq":4,"truncated":false,"count":0,"epoch":"same","open_requests":[]}"#))
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
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]))
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
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = client(ScriptedGatewayTransport([first, second]))
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
    let socket = ScriptedGatewaySocket()
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
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([first, second])
    let gateway = client(transport, heartbeat: .milliseconds(100), deadline: .milliseconds(500))
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

@Test func silenceAloneNeverDropsASocketWithoutAPing() async throws {
    // A deadline shorter than the interval makes the first check see the silence of a long pause
    // (a suspended app, a stalled runner). The gateway must still ping before it gives up.
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([first, second])
    let gateway = client(transport, heartbeat: .milliseconds(100), deadline: .milliseconds(50))
    try await gateway.connect()
    var heartbeats = first.heartbeats.makeAsyncIterator()
    #expect(await heartbeats.next()?.hasPrefix("heartbeat-") == true)
    try await state(of: gateway, isReconnecting)
    try await state(of: gateway) { $0 == .connected }
    #expect(await transport.attempts == 2)
    await gateway.disconnect()
}

@Test func answeredHeartbeatsKeepTheConnection() async throws {
    let socket = ScriptedGatewaySocket(answersHeartbeats: true)
    let transport = ScriptedGatewayTransport([socket])
    let gateway = client(transport, heartbeat: .milliseconds(100), deadline: .seconds(1))
    try await gateway.connect()
    var heartbeats = socket.heartbeats.makeAsyncIterator()
    for _ in 0..<8 { _ = await heartbeats.next() }
    #expect(gateway.connectionState == .connected)
    #expect(await transport.attempts == 1)
    await gateway.disconnect()
}

@Test func streamingTrafficNeedsNoHeartbeat() async throws {
    let socket = ScriptedGatewaySocket()
    // Events arrive forty times faster than the heartbeat interval and keep arriving for twice as long, so a
    // loaded simulator that stalls the test for a second still sees no silent interval.
    let gateway = client(ScriptedGatewayTransport([socket]), heartbeat: .seconds(2), deadline: .seconds(6))
    try await gateway.connect()
    let stream = Task {
        for seq in 1...80 {
            await socket.inject(event("message.delta", session: "s", seq: seq, payload: #"{"text":"x"}"#))
            try await Task.sleep(for: .milliseconds(50))
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
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([first, second])
    let monitor = ScriptedNetworkMonitor(wifi)
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
    let first = ScriptedGatewaySocket(answersHeartbeats: true)
    let second = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([first, second])
    let monitor = ScriptedNetworkMonitor(wifi)
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
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([first, second])
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
    let socket = ScriptedGatewaySocket()
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
    let socket = ScriptedGatewaySocket()
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
