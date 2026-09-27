import Foundation
import HermesAPI

/// Decoding cost of large payloads, built by inflating recorded frames (`fixtures/<release>/liveness.jsonl`):
/// what a chat view, a session list and a long streamed reply cost the app, in milliseconds.
public enum Benchmarks {
    /// Upper bounds for an optimized build on a CI runner, in milliseconds.
    public static let budgets: [String: Double] = [
        "rest.decode.session_page_100": 25,
        "rest.decode.messages_page_200": 25,
        "rest.decode.export_10000": 600,
        "gateway.decode.delta_events_100000": 3_000,
    ]

    public static func run(fixture: URL) throws -> [String: Double] {
        var records: [String: JSONValue] = [:]
        for line in try String(contentsOf: fixture, encoding: .utf8).split(separator: "\n") {
            guard case .object(let record) = try JSONDecoder().decode(JSONValue.self, from: Data(line.utf8)),
                  case .string(let name)? = record["name"], records[name] == nil, let frame = record["frame"] else { continue }
            records[name] = frame
        }
        func body(_ name: String) throws -> [String: JSONValue] {
            guard case .object(let frame)? = records[name], case .object(let body)? = frame["body"] else {
                throw LiveScenarioError("The fixture has no \(name) body")
            }
            return body
        }
        func inflate(_ body: [String: JSONValue], _ key: String, to count: Int) throws -> Data {
            guard case .array(let rows)? = body[key], let row = rows.first, case .object(var template) = row else {
                throw LiveScenarioError("The fixture's \(key) is empty")
            }
            var copies: [JSONValue] = []
            copies.reserveCapacity(count)
            for index in 0..<count {
                if case .string(let id)? = template["id"] { template["id"] = .string("\(id)-\(index)") }
                if case .integer? = template["id"] { template["id"] = .integer(index + 1) }
                copies.append(.object(template))
            }
            var inflated = body
            inflated[key] = .array(copies)
            return try JSONEncoder().encode(JSONValue.object(inflated))
        }
        var results: [String: Double] = [:]
        let page = try inflate(try body("GET /api/sessions"), "sessions", to: 100)
        results["rest.decode.session_page_100"] = try time(repeats: 20) {
            _ = try JSONDecoder().decode(SessionListResponse.self, from: page)
        }
        let messages = try inflate(try body("GET /api/sessions/{session_id}/messages"), "messages", to: 200)
        results["rest.decode.messages_page_200"] = try time(repeats: 20) {
            _ = try JSONDecoder().decode(SessionMessagesResponse.self, from: messages)
        }
        let export = try inflate(try body("GET /api/sessions/{session_id}/export"), "messages", to: 10_000)
        results["rest.decode.export_10000"] = try time(repeats: 3) {
            _ = try JSONDecoder().decode(SessionExportResponse.self, from: export)
        }
        // A long streamed reply: each frame parsed and its payload decoded, as the gateway's reader does.
        guard let delta = records["message.delta"] else { throw LiveScenarioError("The fixture has no message.delta") }
        let frames = (0..<100_000).map { _ in (try? JSONEncoder().encode(delta)) ?? Data() }
        results["gateway.decode.delta_events_100000"] = try time(repeats: 1) {
            for frame in frames {
                guard case .object(let fields) = try JSONValue(jsonData: frame),
                      case .object(let params)? = fields["params"], case .string(let type)? = params["type"] else {
                    throw LiveScenarioError("Unexpected delta frame")
                }
                _ = try GatewayEventPayload.decode(type: type, payload: params["payload"] ?? .object([:]))
            }
        }
        return results
    }

    private static func time(repeats: Int, _ body: () throws -> Void) throws -> Double {
        let clock = ContinuousClock()
        let elapsed = try clock.measure { for _ in 0..<repeats { try body() } }
        return (Double(elapsed.components.seconds) * 1000 + Double(elapsed.components.attoseconds) / 1e15) / Double(repeats)
    }
}
