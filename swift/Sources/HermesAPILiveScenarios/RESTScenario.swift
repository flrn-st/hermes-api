import Foundation
import HermesAPI

/// The harness's REST scenario (`scenarios/rest.yaml`) as the control endpoint serves it: calls for the
/// main server, and calls for a second server behind the dashboard auth gate.
struct RESTScenarioDocument: Decodable, Sendable {
    struct Gated: Decodable, Sendable {
        let url: URL
        let calls: [RESTScenarioCall]
        let username: String?
        let password: String?
    }

    let calls: [RESTScenarioCall]
    let gated: Gated?
    /// The main server behind a self-signed certificate.
    let tls: ConnectionScenarios.PinnedServer?
}

/// One call of the REST scenario.
struct RESTScenarioCall: Decodable, Sendable {
    let operation: String
    let path: [String: JSONValue]?
    let query: [String: JSONValue]?
    let form: [String: JSONValue]?
    let body: JSONValue?
    /// Values to remember from the typed result, by name: a dotted path such as `jobs.0.id`.
    let capture: [String: String]?
    /// Repeat the call until the result at `path` equals `equals`, for at most `timeout` seconds.
    let until: Until?
    /// `native`: sign the call with the native session tokens captured as `tokens`.
    let auth: String?

    struct Until: Decodable, Sendable {
        let path: String
        let equals: JSONValue
        let timeout: Double
    }
}

/// Runs every call of the REST scenario through the generated operation table, in order, so each
/// operation's typed method, request encoding and strict response decoding meet the tagged server.
enum RESTScenario {
    /// Runs `calls` against `baseURL`. `auth` signs every call; a call marked `auth: native` is signed by
    /// a `NativeSessionAuth` holding the tokens the scenario captured as `tokens`.
    static func run(_ calls: [RESTScenarioCall], baseURL: URL, auth: (any HermesRESTAuth)?,
                    transport: any HTTPTransport = URLSessionHTTPTransport(),
                    observations: LiveObservations) async throws {
        let plain = HermesREST(configuration: .init(address: HermesDashboardAddress(baseURL), auth: auth, transport: transport))
        var native: HermesREST?
        var captured: [String: JSONValue] = [:]
        for (index, call) in calls.enumerated() {
            let rest: HermesREST
            if call.auth == "native" {
                if native == nil {
                    guard case .object(let issued)? = captured["tokens"],
                          case .string(let access)? = issued["access_token"],
                          case .string(let refresh)? = issued["refresh_token"] else {
                        throw LiveScenarioError("REST scenario call \(index + 1) needs captured native tokens")
                    }
                    var expiresAt: Int?
                    if case .integer(let expiry)? = issued["expires_at"] { expiresAt = expiry }
                    var provider = ""
                    if case .string(let name)? = issued["provider"] { provider = name }
                    let session = NativeSessionAuth(
                        address: HermesDashboardAddress(baseURL),
                        tokens: .init(accessToken: access, refreshToken: refresh, expiresAt: expiresAt, provider: provider),
                        transport: transport)
                    native = HermesREST(configuration: .init(address: HermesDashboardAddress(baseURL), auth: session, transport: transport))
                }
                rest = native ?? plain
            } else {
                rest = plain
            }
            let arguments = RESTArguments(
                path: try resolve(call.path ?? [:], captured), query: try resolve(call.query ?? [:], captured),
                form: try resolve(call.form ?? [:], captured), body: try call.body.map { try resolve($0, captured) })
            var result: JSONValue
            let deadline = ContinuousClock.now + .milliseconds(Int((call.until?.timeout ?? 0) * 1000))
            while true {
                do {
                    result = try await RESTOperations.call(call.operation, on: rest, with: arguments)
                } catch {
                    throw LiveScenarioError("REST scenario call \(index + 1) \(call.operation) failed: \(error)")
                }
                guard let until = call.until, lookup(result, until.path) != until.equals else { break }
                guard ContinuousClock.now < deadline else {
                    throw LiveScenarioError("REST scenario call \(index + 1) \(call.operation) never reached \(until.path)")
                }
                try await Task.sleep(for: .milliseconds(500))
            }
            for (name, path) in call.capture ?? [:] {
                guard let value = lookup(result, path) else {
                    throw LiveScenarioError("REST scenario call \(index + 1) \(call.operation) has no \(path) to capture")
                }
                captured[name] = value
            }
            await observations.rest(call.operation)
        }
    }

    private static func resolve(_ values: [String: JSONValue], _ captured: [String: JSONValue]) throws -> [String: JSONValue] {
        try values.mapValues { try resolve($0, captured) }
    }

    /// Replaces `${name}` with a captured value: the whole value when the string is only the
    /// placeholder, its text when the placeholder is part of a longer string.
    static func resolve(_ value: JSONValue, _ captured: [String: JSONValue]) throws -> JSONValue {
        switch value {
        case .string(let text):
            if text.hasPrefix("${"), text.hasSuffix("}"), !text.dropFirst(2).contains("${") {
                let name = String(text.dropFirst(2).dropLast())
                guard let value = captured[name] else { throw LiveScenarioError("REST scenario has not captured \(name)") }
                return value
            }
            var result = text
            for (name, value) in captured where result.contains("${\(name)}") {
                let replacement: String
                switch value {
                case .string(let string): replacement = string
                case .integer(let number): replacement = String(number)
                default: throw LiveScenarioError("Captured \(name) is not text")
                }
                result = result.replacingOccurrences(of: "${\(name)}", with: replacement)
            }
            return .string(result)
        case .array(let items): return .array(try items.map { try resolve($0, captured) })
        case .object(let fields): return .object(try fields.mapValues { try resolve($0, captured) })
        default: return value
        }
    }

    /// A dotted path into a JSON value; `$` is the whole value, and `path#param` the query parameter
    /// `param` of the URL at `path`.
    static func lookup(_ value: JSONValue, _ path: String) -> JSONValue? {
        if let hash = path.firstIndex(of: "#") {
            guard case .string(let url)? = lookup(value, String(path[..<hash])),
                  let item = URLComponents(string: url)?.queryItems?.first(where: { $0.name == path[path.index(after: hash)...] }),
                  let parameter = item.value else { return nil }
            return .string(parameter)
        }
        if path == "$" { return value }
        var current: JSONValue? = value
        for key in path.split(separator: ".").map(String.init) {
            switch current {
            case .object(let fields)?: current = fields[key]
            case .array(let items)?: current = Int(key).flatMap { items.indices.contains($0) ? items[$0] : nil }
            default: return nil
            }
        }
        return current
    }
}
