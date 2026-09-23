import Foundation
import HermesAPI
import Testing

private struct TestAuth: HermesAuth {
    func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        .ticket("test-ticket", headers: ["Authorization": "Bearer test"])
    }
}

private struct TicketHTTPTransport: HTTPTransport {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        #expect(request.url?.path == "/api/auth/ws-ticket")
        #expect(request.httpMethod == "POST")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer test")
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil) else {
            throw HermesGatewayError.transport("Invalid test HTTP response")
        }
        return (Data(#"{"ticket":"fresh-ticket","ttl_seconds":30}"#.utf8), response)
    }
}

private struct TestTransport: GatewayTransport {
    let socket: TestSocket

    func connect(url: URL, headers: [String: String], subprotocols: [String]) async throws -> any GatewayConnection {
        #expect(url.path == "/api/ws")
        #expect(headers["Authorization"] == "Bearer test")
        #expect(subprotocols == ["hermes-gateway-v1", "hermes-gateway-ticket.test-ticket"])
        return socket
    }
}

private actor TestSocket: GatewayConnection {
    nonisolated let sent: AsyncStream<Data>
    private let sentContinuation: AsyncStream<Data>.Continuation
    private var incoming: [Data] = []
    private var waiters: [CheckedContinuation<Data, Error>] = []

    init() {
        let (stream, continuation) = AsyncStream.makeStream(of: Data.self)
        sent = stream
        sentContinuation = continuation
    }

    func send(_ frame: Data) throws {
        guard let object = try JSONSerialization.jsonObject(with: frame) as? [String: Any] else {
            throw HermesGatewayError.decoding("Invalid test frame")
        }
        if object["method"] as? String == "client.capabilities", let id = object["id"] as? Int {
            inject(#"{"jsonrpc":"2.0","id":\#(id),"result":{"server_requests":["approval"]}}"#)
        } else {
            sentContinuation.yield(frame)
        }
    }

    func receive() async throws -> Data {
        if !incoming.isEmpty { return incoming.removeFirst() }
        return try await withCheckedThrowingContinuation { waiters.append($0) }
    }

    func inject(_ json: String) {
        let frame = Data(json.utf8)
        if !waiters.isEmpty { waiters.removeFirst().resume(returning: frame) }
        else { incoming.append(frame) }
    }

    func ping() {}

    func close() {
        for waiter in waiters { waiter.resume(throwing: HermesGatewayError.transport("closed")) }
        waiters.removeAll()
        sentContinuation.finish()
    }
}

private func gateway(socket: TestSocket, timeout: Duration = .seconds(1)) -> HermesGateway {
    HermesGateway(configuration: .init(
        baseURL: URL(string: "https://example.test")!, auth: TestAuth(),
        transport: TestTransport(socket: socket), requestTimeout: timeout
    ))
}

private func sentCall(_ frame: Data) throws -> (String, Int) {
    let object = try #require(JSONSerialization.jsonObject(with: frame) as? [String: Any])
    return (try #require(object["method"] as? String), try #require(object["id"] as? Int))
}

@Test func correlatesOutOfOrderResponses() async throws {
    let socket = TestSocket()
    let client = gateway(socket: socket)
    try await client.connect()
    var sent = socket.sent.makeAsyncIterator()
    let first = Task { try await client.call("first", params: PingParams(), as: PingResult.self) }
    let second = Task { try await client.call("second", params: PingParams(), as: PingResult.self) }
    let callA = try sentCall(try #require(await sent.next()))
    let callB = try sentCall(try #require(await sent.next()))
    let ids = Dictionary(uniqueKeysWithValues: [callA, callB])
    let firstID = try #require(ids["first"])
    let secondID = try #require(ids["second"])
    await socket.inject(#"{"jsonrpc":"2.0","id":\#(secondID),"result":{"pong":false}}"#)
    await socket.inject(#"{"jsonrpc":"2.0","id":\#(firstID),"result":{"pong":true}}"#)
    #expect(try await first.value.pong)
    #expect(try await !second.value.pong)
    await client.disconnect()
}

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

@Test func deduplicatesSequencedEvents() async throws {
    let socket = TestSocket()
    let client = gateway(socket: socket)
    try await client.connect()
    var events = client.events.makeAsyncIterator()
    let first = #"{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"s","seq":1,"payload":{}}}"#
    await socket.inject(first)
    await socket.inject(first)
    await socket.inject(#"{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"s","seq":2,"payload":{}}}"#)
    #expect(await events.next()?.seq == 1)
    #expect(await events.next()?.seq == 2)
    await client.disconnect()
}

@Test func rejectsIncompatibleDesktopContract() async throws {
    let socket = TestSocket()
    let client = gateway(socket: socket)
    try await client.connect()
    var sent = socket.sent.makeAsyncIterator()
    let request = Task { try await client.call("contract", params: PingParams(), as: PingResult.self) }
    let (_, id) = try sentCall(try #require(await sent.next()))
    await socket.inject(#"{"jsonrpc":"2.0","id":\#(id),"result":{"info":{"desktop_contract":8}}}"#)
    do {
        _ = try await request.value
        Issue.record("Expected incompatible contract")
    } catch let error as HermesGatewayError {
        #expect(error == .incompatibleServer(8))
    }
    await client.disconnect()
}

@Test func timesOutAndCancelsCalls() async throws {
    let socket = TestSocket()
    let client = gateway(socket: socket, timeout: .milliseconds(80))
    try await client.connect()
    var sent = socket.sent.makeAsyncIterator()
    let timed = Task { try await client.call("hang", params: PingParams(), as: PingResult.self) }
    _ = await sent.next()
    do {
        _ = try await timed.value
        Issue.record("Expected timeout")
    } catch let error as HermesGatewayError {
        #expect(error == .timeout)
    }

    let cancelled = Task { try await client.call("cancel", params: PingParams(), as: PingResult.self) }
    _ = await sent.next()
    cancelled.cancel()
    do {
        _ = try await cancelled.value
        Issue.record("Expected cancellation")
    } catch let error as HermesGatewayError {
        #expect(error == .cancelled)
    }
    await client.disconnect()
}

@Test func answersTypedServerRequest() async throws {
    let socket = TestSocket()
    let client = gateway(socket: socket)
    await client.setServerRequestHandler { request in
        guard case .approval(let params) = request else {
            throw HermesGatewayError.decoding("Unexpected request")
        }
        #expect(params.requestId == "req")
        return .approval(ApprovalResult(choice: .once))
    }
    try await client.connect()
    var sent = socket.sent.makeAsyncIterator()
    await socket.inject(#"{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s","request_id":"req"}}"#)
    let replyFrame = try #require(await sent.next())
    let reply = try #require(JSONSerialization.jsonObject(with: replyFrame) as? [String: Any])
    #expect(reply["id"] as? String == "srq-1")
    let result = try #require(reply["result"] as? [String: Any])
    #expect(result["choice"] as? String == "once")
    await client.disconnect()
}

private actor SequenceTransport: GatewayTransport {
    private var sockets: [TestSocket]

    init(_ sockets: [TestSocket]) { self.sockets = sockets }

    func connect(url: URL, headers: [String: String], subprotocols: [String]) async throws -> any GatewayConnection {
        guard !sockets.isEmpty else { throw HermesGatewayError.transport("No test sockets remain") }
        return sockets.removeFirst()
    }
}

@Test func reconnectReplaysGapBeforeLiveEvents() async throws {
    let firstSocket = TestSocket()
    let secondSocket = TestSocket()
    let transport = SequenceTransport([firstSocket, secondSocket])
    let client = HermesGateway(configuration: .init(
        baseURL: URL(string: "https://example.test")!, auth: TestAuth(),
        transport: transport, requestTimeout: .seconds(1), reconnectDelay: { _ in .zero }
    ))
    try await client.connect()
    var events = client.events.makeAsyncIterator()
    await firstSocket.inject(#"{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"s","seq":1,"payload":{}}}"#)
    #expect(await events.next()?.seq == 1)
    var sent = secondSocket.sent.makeAsyncIterator()
    await firstSocket.close()
    let replayFrame = try #require(await sent.next())
    let (method, replayID) = try sentCall(replayFrame)
    #expect(method == "session.events.since")
    await secondSocket.inject(#"{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"s","seq":3,"payload":{}}}"#)
    await secondSocket.inject(#"{"jsonrpc":"2.0","id":\#(replayID),"result":{"events":[{"type":"message.start","session_id":"s","seq":2,"payload":{}}],"latest_seq":3,"truncated":false,"count":1,"epoch":"same","open_requests":[]}}"#)
    let replayed = try #require(await events.next())
    let live = try #require(await events.next())
    #expect(replayed.seq == 2 && replayed.replayed)
    #expect(live.seq == 3 && !live.replayed)
    await client.disconnect()
}
