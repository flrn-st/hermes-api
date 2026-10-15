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

@Test func canonicalMethodsCallTheirWireNamesWithStrictTypes() async throws {
    let caller = ScriptedGatewayCaller(results: ["runtime.describe": .object([
        "instance_id": .string("i1"), "profile_id": .string("/home/p"), "authority_epoch": .integer(3),
        "capabilities": .array([.string("durable-admission-v1")]),
        "session_create": .object(["sources": .array([.string("gui")]), "parameters": .array([.string("cwd")])]),
    ])])
    let described = try await caller.methods.canonical.runtime.describe(RuntimeDescribeParams())
    #expect(described.capabilities == ["durable-admission-v1"])
    #expect(described.sessionCreate.sources == ["gui"])
    #expect(await caller.calls == [RecordedCall(method: "runtime.describe", params: .object([:]))])
}

@Test func authorityRefusalsReadTheirReasonAndFields() {
    let missing = HermesGatewayError.rpc(code: 4001, message: "not_found", data: .object(["reason": .string("not_found")]))
    #expect(missing.refusal == GatewayRefusal(reason: "not_found"))
    #expect(missing.refusal?.known == .notFound)
    // The reviewed reason wins over the legacy reading of code 4001.
    #expect(missing.kind == .notFound)

    let params = HermesGatewayError.rpc(code: 4001, message: "invalid_params", data: .object([
        "reason": .string("invalid_params"), "fields": .array([.string("cols"), .string("lazy")]),
    ]))
    #expect(params.refusal?.fields == ["cols", "lazy"])
    #expect(params.kind == .invalidRequest)

    let later = HermesGatewayError.rpc(code: 4001, message: "invalid_params", data: .object([
        "reason": .string("invalid_params"), "field": .string("hidden"), "detail": .string("must be a boolean"),
    ]))
    #expect(later.refusal?.field == "hidden")
    #expect(later.refusal?.detail == "must be a boolean")

    let unknown = HermesGatewayError.rpc(code: 4001, message: "brand_new", data: .object(["reason": .string("brand_new")]))
    #expect(unknown.refusal?.reason == "brand_new")
    #expect(unknown.refusal?.known == nil)
    #expect(HermesGatewayError.rpc(code: 4009, message: "session busy", data: nil).refusal == nil)
    #expect(HermesGatewayError.timeout.refusal == nil)
}
