import Foundation
import HermesAPI
import Testing

private struct FixtureRecord: Decodable {
    let kind: String
    let name: String
    let frame: JSONValue
}

@Test func recordedLivenessFramesDecodeInSwift() throws {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let ref = try String(contentsOf: root.appending(path: "spec/current-release.txt"), encoding: .utf8)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    let file = root.appending(path: "fixtures/\(ref)/liveness.jsonl")
    let lines = try String(contentsOf: file, encoding: .utf8).split(separator: "\n")
    #expect(lines.count == 4)
    let decoder = JSONDecoder()
    for line in lines {
        let record = try decoder.decode(FixtureRecord.self, from: Data(line.utf8))
        guard case .object(let frame) = record.frame else { throw HermesGatewayError.decoding("Invalid fixture frame") }
        switch record.name {
        case "gateway.ready":
            guard case .object(let params) = frame["params"] else { throw HermesGatewayError.decoding("Missing event params") }
            let payload = try GatewayEventPayload.decode(type: "gateway.ready", payload: params["payload"] ?? .object([:]))
            guard case .gatewayReady(let ready) = payload else { throw HermesGatewayError.decoding("Wrong event kind") }
            #expect(!ready.replayEpoch.isEmpty)
            #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(ready)) == params["payload"])
        case "client.capabilities":
            let result = try decoder.decode(ClientCapabilitiesResult.self, from: JSONEncoder().encode(frame["result"]))
            #expect(result.serverRequests.contains("approval"))
            #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["result"])
        case "ping":
            let result = try decoder.decode(PingResult.self, from: JSONEncoder().encode(frame["result"]))
            #expect(result.pong)
            #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["result"])
        case "gateway.capabilities":
            let result = try decoder.decode(GatewayCapabilitiesResult.self, from: JSONEncoder().encode(frame["result"]))
            #expect(result.perSessionExclusiveSubmit)
            #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["result"])
        default:
            Issue.record("Unexpected fixture: \(record.name)")
        }
    }
}
