import Foundation
import HermesAPI
import Testing

private struct FixtureRecord: Decodable {
    let kind: String
    let name: String
    let frame: JSONValue
    let answer: JSONValue?
}

private func collapsingOptionalNulls(_ value: JSONValue) -> JSONValue {
    switch value {
    case .object(let fields):
        return .object(fields.compactMapValues { field in
            if field == .null { return nil }
            return collapsingOptionalNulls(field)
        })
    case .array(let values):
        return .array(values.map(collapsingOptionalNulls))
    default:
        return value
    }
}

@Test func recordedLivenessFramesDecodeInSwift() throws {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let ref = try String(contentsOf: root.appending(path: "spec/current-release.txt"), encoding: .utf8)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    let file = root.appending(path: "fixtures/\(ref)/liveness.jsonl")
    let lines = try String(contentsOf: file, encoding: .utf8).split(separator: "\n")
    #expect(lines.count >= 8)
    let decoder = JSONDecoder()
    var seen = Set<String>()
    for line in lines {
        let record = try decoder.decode(FixtureRecord.self, from: Data(line.utf8))
        seen.insert(record.name)
        guard case .object(let frame) = record.frame else { throw HermesGatewayError.decoding("Invalid fixture frame") }
        if record.kind == "server_request" {
            #expect(record.name == "clarify")
            let request = try decoder.decode(ClarifyRequestParams.self,
                                             from: JSONEncoder().encode(frame["params"]))
            guard case .value(let questions) = request.questions else {
                throw HermesGatewayError.decoding("Missing recorded clarification")
            }
            #expect(questions.count == 1 && questions[0].question == "Which release channel?")
            let answer = try decoder.decode(ClarifyResult.self,
                                            from: JSONEncoder().encode(record.answer))
            #expect(answer.answers?["q0"] == "Stable")
            #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(answer)) == record.answer)
            continue
        }
        if record.kind == "rest" {
            #expect(frame["status"] == .integer(200))
            switch record.name {
            case "GET /api/audio/voice-live/status":
                let result = try decoder.decode(AudioVoiceLiveStatusResponse.self,
                                                from: JSONEncoder().encode(frame["body"]))
                #expect(result.ok && result.mode == "chained")
                #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["body"])
            case "GET /api/sessions/empty/count":
                let result = try decoder.decode(SessionsEmptyCountResponse.self,
                                                from: JSONEncoder().encode(frame["body"]))
                #expect(result.count >= 0)
                #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["body"])
            case "GET /api/profiles/active":
                let result = try decoder.decode(ProfilesActiveResponse.self,
                                                from: JSONEncoder().encode(frame["body"]))
                #expect(result.active == "default" && result.current == "default")
                #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["body"])
            case "POST /api/profiles/active":
                let result = try decoder.decode(ProfilesSetActiveResponse.self,
                                                from: JSONEncoder().encode(frame["body"]))
                #expect(result.ok && result.active == "default")
                #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["body"])
            default:
                Issue.record("Unexpected REST fixture: \(record.name)")
            }
            continue
        }
        if record.kind == "event" {
            guard case .object(let params) = frame["params"] else {
                throw HermesGatewayError.decoding("Missing event params")
            }
            let payload = try GatewayEventPayload.decode(type: record.name, payload: params["payload"] ?? .object([:]))
            if case .unknown = payload { Issue.record("Unmodelled event: \(record.name)") }
            if case .gatewayReady(let ready) = payload {
                #expect(!ready.replayEpoch.isEmpty)
                #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(ready)) == params["payload"])
            }
            if case .messageComplete(let complete) = payload {
                #expect([.string("HermesAPI fixture reply."),
                         .string("HermesAPI stable release selected.")].contains(complete.text))
            }
            if case .messageDelta(let delta) = payload {
                #expect(["HermesAPI fixture reply.", "HermesAPI stable release selected."].contains(
                    delta.text.trimmingCharacters(in: .whitespacesAndNewlines)))
            }
            if case .toolStart(let started) = payload {
                #expect(started.name == "clarify")
            }
            if case .toolComplete(let completed) = payload {
                #expect(completed.name == "clarify")
            }
            continue
        }
        switch record.name {
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
        case "session.create":
            let result = try decoder.decode(SessionCreateResult.self, from: JSONEncoder().encode(frame["result"]))
            #expect(!result.sessionId.isEmpty)
            let encoded = try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result))
            #expect(collapsingOptionalNulls(encoded) == collapsingOptionalNulls(frame["result"] ?? .null))
            #expect(try decoder.decode(SessionCreateResult.self, from: JSONEncoder().encode(result)) == result)
        case "session.list":
            let result = try decoder.decode(SessionListResult.self, from: JSONEncoder().encode(frame["result"]))
            #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["result"])
        case "session.close":
            let result = try decoder.decode(SessionCloseResult.self, from: JSONEncoder().encode(frame["result"]))
            #expect(result.closed)
            #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["result"])
        case "prompt.submit":
            let result = try decoder.decode(PromptSubmitResult.self, from: JSONEncoder().encode(frame["result"]))
            #expect(result.status != nil)
            #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(result)) == frame["result"])
        default:
            Issue.record("Unexpected fixture: \(record.name)")
        }
    }
    #expect(Set(["gateway.ready", "ping", "prompt.submit", "clarify", "tool.start",
                 "tool.complete", "message.delta", "message.complete",
                 "GET /api/audio/voice-live/status",
                 "GET /api/sessions/empty/count",
                 "GET /api/profiles/active",
                 "POST /api/profiles/active",
                 "session.create", "session.list", "session.close"]).isSubset(of: seen))
}
