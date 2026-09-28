import Foundation
import HermesAPI
import HermesAPITesting
import Testing

/// App code written against the protocol, as an app would write it.
private func connectAndAnswer(_ gateway: any HermesGatewayClient) async throws -> GatewayEvent? {
    await gateway.setServerRequestHandler { request in
        guard case .clarify(let params) = request else { throw HermesGatewayError.transport("Unexpected request") }
        return .clarify(ClarifyResult(answer: "Answer for \(params.sessionId)"))
    }
    let events = gateway.events()
    try await gateway.connect()
    _ = try await gateway.methods.ping(.init())
    for await event in events { return event }
    return nil
}

@Test func fakeGatewayStandsInForTheGateway() async throws {
    let fake = FakeGateway(results: ["ping": .object(["pong": .boolean(true)])])
    let app = Task { try await connectAndAnswer(fake) }
    let states = fake.connectionStates()
    for await state in states where state == .connected { break }
    await fake.emit("message.start", session: "s1", seq: 1)
    let event = try #require(try await app.value)
    #expect(event.sessionID == "s1")
    guard case .messageStart = event.payload else {
        Issue.record("Expected a typed message.start payload")
        return
    }
    #expect(await fake.calls.map(\.method) == ["ping"])
    #expect(await fake.lifecycle == ["connect"])

    let answer = try await fake.request(method: "clarify", params: .object(["session_id": .string("s1")]))
    #expect(answer == .clarify(ClarifyResult(answer: "Answer for s1")))
}

@Test func fakeGatewayRejectsAMismatchedAnswer() async throws {
    let fake = FakeGateway()
    await fake.setServerRequestHandler { _ in .approval(ApprovalResult(choice: .deny)) }
    await #expect(throws: HermesGatewayError.self) {
        _ = try await fake.request(.clarify(ClarifyRequestParams(sessionId: "s1")))
    }
    await #expect(throws: HermesGatewayError.self) { _ = try await fake.methods.ping(.init()) }
}

@Test func scriptedRESTCallerDecodesThroughGeneratedMethods() async throws {
    let rest = ScriptedRESTCaller(routes: ["GET /api/sessions/empty/count": (200, #"{"count":4}"#)])
    #expect(try await rest.methods.sessions.emptyCount(profile: "work").count == 4)
    let requests = await rest.requests
    #expect(requests.map(\.query) == [["profile": "work"]])
    await #expect(throws: HermesRESTError.self) { _ = try await rest.methods.sessions.getBySessionId(sessionId: "x") }
}

@Test func scriptedSocketDrivesARealGateway() async throws {
    let socket = ScriptedGatewaySocket()
    let transport = ScriptedGatewayTransport([socket])
    let gateway = HermesGateway(configuration: .init(
        address: HermesDashboardAddress(URL(string: "https://hermes.test")!), auth: StaticTicketAuth(),
        transport: transport, networkMonitor: ScriptedNetworkMonitor()
    ))
    try await gateway.connect()
    let pong = Task { try await gateway.methods.ping(.init()) }
    var sent = socket.sent.makeAsyncIterator()
    let call = try SentCall(try #require(await sent.next()))
    #expect(call.method == "ping")
    await socket.inject(GatewayFrames.result(call.id, #"{"pong":true}"#))
    #expect(try await pong.value.pong)
    #expect(await transport.subprotocols == [["hermes-gateway-v1", "hermes-gateway-ticket.test-ticket"]])
    await gateway.disconnect()
}
