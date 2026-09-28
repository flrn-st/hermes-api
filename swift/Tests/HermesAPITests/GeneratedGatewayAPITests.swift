import Foundation
import HermesAPI
import HermesAPITesting
import Testing

@Test func generatedMethodCallsItsWireName() async throws {
    let caller = ScriptedGatewayCaller(results: ["ping": .object(["pong": .boolean(true)])])
    let result = try await caller.methods.ping(.init())
    #expect(result.pong)
    #expect(await caller.calls == [RecordedCall(method: "ping", params: .object([:]))])
}

@Test func generatedEventRetainsUnknownPayload() throws {
    let known = try GatewayEventPayload.decode(type: "message.start", payload: .object([:]))
    guard case .messageStart = known else {
        Issue.record("Expected message.start")
        return
    }
    let unknown = try GatewayEventPayload.decode(type: "future.event", payload: .object(["count": .integer(1)]))
    guard case .unknown(let type, let raw) = unknown else {
        Issue.record("Expected unknown event")
        return
    }
    #expect(type == "future.event")
    #expect(raw == .object(["count": .integer(1)]))
}
