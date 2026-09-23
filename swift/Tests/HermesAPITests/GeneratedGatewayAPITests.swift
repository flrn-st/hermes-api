import Foundation
import HermesAPI
import Testing

private struct PingCaller: GatewayCalling {
    func call<Params: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, params: Params, as resultType: Result.Type
    ) async throws -> Result {
        #expect(method == "ping")
        #expect(try JSONSerialization.jsonObject(with: JSONEncoder().encode(params)) is [String: Any])
        return try JSONDecoder().decode(Result.self, from: Data(#"{"pong":true}"#.utf8))
    }
}

@Test func generatedMethodCallsItsWireName() async throws {
    let result = try await GatewayMethodCatalog(caller: PingCaller()).ping(.init())
    #expect(result.pong)
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
