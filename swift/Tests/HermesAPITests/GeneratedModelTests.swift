import Foundation
import HermesAPI
import Testing

@Test func optionalNullableParamsPreserveAbsentNullAndValue() throws {
    let decoder = JSONDecoder()
    let absent = try decoder.decode(BrowserControllerResultParams.self, from: Data(#"{"session_id":"s","command_id":"c"}"#.utf8))
    #expect(absent.ok == .absent)

    let null = try decoder.decode(BrowserControllerResultParams.self, from: Data(#"{"session_id":"s","command_id":"c","ok":null}"#.utf8))
    #expect(null.ok == .null)
    let nullObject = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(null)) as? [String: Any])
    #expect(nullObject.keys.contains("ok"))
    #expect(nullObject["ok"] is NSNull)

    let value = try decoder.decode(BrowserControllerResultParams.self, from: Data(#"{"session_id":"s","command_id":"c","ok":true}"#.utf8))
    #expect(value.ok == .value(.boolean(true)))
}

@Test func requiredNullableFieldRequiresKey() throws {
    let decoder = JSONDecoder()
    let result = try decoder.decode(SessionMostRecentResult.self, from: Data(#"{"session_id":null}"#.utf8))
    #expect(result.sessionId == nil)
    let encoded = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(result)) as? [String: Any])
    #expect(encoded["session_id"] is NSNull)
    #expect(throws: DecodingError.self) {
        try decoder.decode(SessionMostRecentResult.self, from: Data(#"{}"#.utf8))
    }
}

@Test func openObjectKeepsAdditionalFields() throws {
    let decoded = try JSONDecoder().decode(ChangeSignalPayload.self, from: Data(#"{"future":{"nested":3}}"#.utf8))
    #expect(decoded.additionalProperties["future"] == .object(["nested": .integer(3)]))
    let encoded = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(decoded)) as? [String: Any])
    #expect(encoded.keys.contains("future"))
}

@Test func taggedUnionAndOpenEnumDecode() throws {
    let barrier = try JSONDecoder().decode(GoalSnapshotWaitBarrier.self, from: Data(#"{"type":"pid","target":123}"#.utf8))
    guard case .waitBarrierTarget(let target) = barrier else {
        Issue.record("Expected wait barrier target")
        return
    }
    #expect(target.target == .long(123))
    let choice = try JSONDecoder().decode(ApprovalChoice.self, from: Data(#""future""#.utf8))
    #expect(choice == .unknown("future"))
}

@Test func integerConstantsDecodeOnlyTheirValue() throws {
    func policy(version: Int) -> Data {
        Data(#"{"version":\#(version),"revision":"r","issued_at_ms":1,"mode":"allow","connectors":[],"tools":{}}"#.utf8)
    }
    #expect(try JSONDecoder().decode(ConnectorPolicyEffectiveAllow.self, from: policy(version: 1)).version == 1)
    #expect(throws: DecodingError.self) {
        try JSONDecoder().decode(ConnectorPolicyEffectiveAllow.self, from: policy(version: 2))
    }
}
