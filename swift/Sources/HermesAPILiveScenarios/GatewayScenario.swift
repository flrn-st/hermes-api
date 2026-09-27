import Foundation
import HermesAPI

/// The harness's gateway scenario (`scenarios/gateway.yaml`) as the control endpoint serves it.
struct GatewayScenarioDocument: Decodable, Sendable {
    let calls: [GatewayScenarioCall]
}

/// One call of the gateway scenario.
struct GatewayScenarioCall: Decodable, Sendable {
    let method: String
    let params: JSONValue?
    /// Values to remember from the typed result, by name: a dotted path such as `project.id`.
    let capture: [String: String]?
    /// An event to wait for after the call, for methods whose work completes later.
    let wait: String?
}

/// Runs every call of the gateway scenario through the generated operation table, in order, so each
/// method's typed parameters, generated method and strict result decoding meet the tagged server.
enum GatewayScenario {
    static func run(_ calls: [GatewayScenarioCall], environment: LiveScenarioEnvironment,
                    observations: LiveObservations) async throws {
        let gateway = HermesGateway(configuration: .init(
            address: HermesDashboardAddress(environment.url), auth: environment.auth,
            transport: ObservingTransport(inner: URLSessionGatewayTransport(), observations: observations)))
        do {
            try await gateway.connect()
            try await run(calls, on: gateway)
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
    }

    private static func run(_ calls: [GatewayScenarioCall], on gateway: HermesGateway) async throws {
        // Names the scenario creates carry a per-run value, so every client can run it against one server.
        var captured: [String: JSONValue] = ["@unique": .string("swift\(Int(Date().timeIntervalSince1970))")]
        for (index, call) in calls.enumerated() {
            let label = "Gateway scenario call \(index + 1) \(call.method)"
            // Subscribe before calling, so the awaited event cannot precede the subscription.
            let events = call.wait.map { _ in gateway.events() }
            let result: JSONValue
            let params: JSONValue
            do {
                params = try RESTScenario.resolve(call.params ?? .object([:]), captured)
                result = try await GatewayOperations.call(call.method, on: gateway, with: params)
            } catch {
                throw LiveScenarioError("\(label) failed: \(error)")
            }
            for (name, path) in call.capture ?? [:] {
                guard let value = RESTScenario.lookup(result, path) else {
                    throw LiveScenarioError("\(label) has no \(path) to capture")
                }
                captured[name] = value
            }
            if let wait = call.wait, let events {
                // The call's own session: a subagent's events must not end the wait.
                var session: String?
                if case .object(let fields) = params, case .string(let id)? = fields["session_id"] { session = id }
                try await withDeadline(.seconds(60), "\(wait) after \(label)") { [session] in
                    for await event in events where event.type == wait && event.sessionID == session { return }
                    throw LiveScenarioError("Event stream ended before \(wait)")
                }
            }
        }
    }
}
