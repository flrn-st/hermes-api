import Foundation
import HermesAPI
import HermesAPILiveScenarios
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
    var restSeen = Set<String>()
    for line in lines {
        let record = try decoder.decode(FixtureRecord.self, from: Data(line.utf8))
        seen.insert(record.name)
        guard case .object(let frame) = record.frame else { throw HermesGatewayError.decoding("Invalid fixture frame") }
        if record.kind == "server_request" {
            switch record.name {
            case "clarify":
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
            case "approval":
                let request = try decoder.decode(ApprovalRequestParams.self,
                                                 from: JSONEncoder().encode(frame["params"]))
                #expect(request.command == "rm -rf /tmp/hermes-api-fixture-approval-target")
                #expect(request.choices?.contains(.deny) == true)
                let answer = try decoder.decode(ApprovalResult.self,
                                                from: JSONEncoder().encode(record.answer))
                #expect(answer.choice == .deny)
                #expect(try decoder.decode(JSONValue.self, from: JSONEncoder().encode(answer)) == record.answer)
            default:
                Issue.record("Unexpected server request: \(record.name)")
            }
            continue
        }
        if record.kind == "rest" {
            // Every recorded operation decodes through its generated model and re-encodes to the same JSON.
            guard case .integer(let status)? = frame["status"] else { throw HermesRESTError.decoding("No status") }
            var headers: [String: String] = [:]
            if case .string(let media)? = frame["media"] { headers["content-type"] = media }
            if case .string(let location)? = frame["location"] { headers["location"] = location }
            let body: Data
            if let json = frame["body"] {
                body = try JSONEncoder().encode(json)
            } else if case .string(let text)? = frame["text"] {
                body = Data(text.utf8)
            } else {
                body = Data()
            }
            #expect(RESTOperations.all.contains(record.name), "Unknown REST operation \(record.name)")
            let decoded = try RESTOperations.decode(record.name, RESTResponse(status: status, headers: headers, body: body))
            if let json = frame["body"] {
                let expected = collapsingOptionalNulls(json)
                let actual = collapsingOptionalNulls(decoded)
                #expect(actual == expected || actual == .object(["status": .integer(status), "value": expected]),
                        "\(record.name) does not round-trip")
            } else if case .string(let text)? = frame["text"] {
                #expect(decoded == .string(text) || decoded == .object(["status": .integer(status), "value": .string(text)]))
            }
            restSeen.insert(record.name)
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
                         .string("HermesAPI stable release selected."),
                         .string("HermesAPI approval denied as expected.")].contains(complete.text))
            }
            if case .messageDelta(let delta) = payload {
                #expect(["HermesAPI fixture reply.", "HermesAPI stable release selected.",
                         "HermesAPI approval denied as expected."].contains(
                    delta.text.trimmingCharacters(in: .whitespacesAndNewlines)))
            }
            if case .toolStart(let started) = payload {
                #expect(["clarify", "terminal"].contains(started.name))
            }
            if case .toolComplete(let completed) = payload {
                #expect(["clarify", "terminal"].contains(completed.name))
                if completed.name == "terminal" {
                    guard case .object(let result)? = completed.result else {
                        throw HermesGatewayError.decoding("Missing denied terminal result")
                    }
                    #expect(result["status"] == .string("blocked"))
                    #expect(result["exit_code"] == .integer(-1))
                }
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
    #expect(Set(["gateway.ready", "ping", "prompt.submit", "clarify", "approval", "tool.start",
                 "tool.complete", "message.delta", "message.complete",
                 "session.create", "session.list", "session.close"]).isSubset(of: seen))
    #expect(restSeen.count >= 4)
}
