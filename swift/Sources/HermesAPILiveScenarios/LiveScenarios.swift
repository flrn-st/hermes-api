import Foundation
import HermesAPI

/// Live scenario inputs, read from `HERMES_LIVE_*` variables that the harness sets.
public struct LiveScenarioEnvironment: Sendable {
    public let url: URL
    public let auth: any HermesAuth
    public let token: String?
    public let lifecycle: Bool
    public let control: URL?

    public init(_ values: [String: String]) throws {
        guard let rawURL = values["HERMES_LIVE_URL"], let url = URL(string: rawURL) else {
            throw LiveScenarioError("HERMES_LIVE_URL is required")
        }
        self.url = url
        if let ticket = values["HERMES_LIVE_TICKET"], !ticket.isEmpty {
            auth = StaticTicketAuth(ticket: ticket)
            token = nil
        } else if let token = values["HERMES_LIVE_TOKEN"], !token.isEmpty {
            auth = LocalTokenAuth(token: token)
            self.token = token
        } else {
            throw LiveScenarioError("HERMES_LIVE_TICKET or HERMES_LIVE_TOKEN is required")
        }
        lifecycle = values["HERMES_LIVE_LIFECYCLE"] == "1"
        control = values["HERMES_LIVE_CONTROL"].flatMap(URL.init(string:))
    }
}

public struct LiveScenarioError: Error, CustomStringConvertible, LocalizedError {
    public let description: String

    public init(_ description: String) { self.description = description }

    public var errorDescription: String? { description }
}

/// Fixture replies produced by the harness model stub (`harness/stub_llm.py`).
enum Fixture {
    static let reply = "HermesAPI fixture reply."
    static let clarifyPrompt = "Ask which release channel to use for HermesAPI."
    static let clarifyReply = "HermesAPI stable release selected."
    static let approvalPrompt = "Try the fixture cleanup command and report whether it was approved."
    static let approvalCommand = "rm -rf /tmp/hermes-api-fixture-approval-target"
    static let approvalReply = "HermesAPI approval denied as expected."
    static let reconnectPrompt = "Stream the HermesAPI reconnect fixture slowly."
    static let reconnectReply = (1...16).map { String(format: "part%02d", $0) }.joined(separator: " ")
}

public enum LiveScenarios {
    /// Runs the smoke scenario and, when the harness exposes its control endpoint, the reconnect scenarios.
    public static func run(_ environment: LiveScenarioEnvironment) async throws {
        let observations = LiveObservations()
        try await smoke(environment, observations: observations)
        guard let control = environment.control else { return }
        let faults = FaultControl(base: control)
        if environment.lifecycle {
            try await reconnect(environment, faults: faults, observations: observations)
        }
        // Only a fully passing run reports what it exercised.
        try await faults.report(observations.report())
    }

    static func smoke(_ environment: LiveScenarioEnvironment, observations: LiveObservations) async throws {
        let gateway = HermesGateway(configuration: .init(
            baseURL: environment.url, auth: environment.auth,
            transport: ObservingTransport(inner: URLSessionGatewayTransport(), observations: observations)))
        await gateway.setServerRequestHandler { request in
            switch request {
            case .clarify(let params):
                guard case .value(let questions) = params.questions,
                      questions.count == 1,
                      questions[0].question == "Which release channel?" else {
                    throw LiveScenarioError("Unexpected clarification request")
                }
                return .clarify(ClarifyResult(answers: [questions[0].qid: "Stable"]))
            case .approval(let params):
                guard params.command == Fixture.approvalCommand else {
                    throw LiveScenarioError("Unexpected approval command")
                }
                return .approval(ApprovalResult(choice: .deny))
            default:
                throw LiveScenarioError("Unexpected server request")
            }
        }
        do {
            try await gateway.connect()
            let result = try await gateway.ping(PingParams())
            guard result.pong else { throw LiveScenarioError("Gateway ping returned false") }
            _ = try await gateway.gateway.capabilities(PingParams())
            if environment.lifecycle {
                try await lifecycle(gateway, environment: environment)
            }
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
    }

    private static func lifecycle(_ gateway: HermesGateway, environment: LiveScenarioEnvironment) async throws {
        // Hermes broadcasts sessions.changed (at most every 2 s) once a turn writes the session store.
        let listChanges = gateway.events()
        let session = try await gateway.session.create(.init(
            cwd: .value("/tmp"), title: .value("HermesAPI live session"), closeOnDisconnect: true))
        let greeting = try await runTurn(gateway, sessionID: session.sessionId,
                                         prompt: "Reply with a short greeting.", tool: nil)
        try greeting.expect(reply: Fixture.reply)
        try await withDeadline(.seconds(15), "a sessions.changed broadcast") {
            for await event in listChanges where event.type == "sessions.changed" {
                guard case .sessionsChanged = event.payload else { throw LiveScenarioError("Untyped sessions.changed") }
                return
            }
            throw LiveScenarioError("Event stream ended before sessions.changed")
        }
        let clarified = try await runTurn(gateway, sessionID: session.sessionId,
                                          prompt: Fixture.clarifyPrompt, tool: "clarify")
        try clarified.expect(reply: Fixture.clarifyReply)
        let denied = try await runTurn(gateway, sessionID: session.sessionId,
                                       prompt: Fixture.approvalPrompt, tool: "terminal")
        try denied.expect(reply: Fixture.approvalReply)
        guard case .object(let result)? = denied.toolResult,
              result["status"] == .string("blocked"),
              result["exit_code"] == .integer(-1) else {
            throw LiveScenarioError("Denied terminal command was not blocked")
        }
        _ = try await gateway.session.list(.init())
        let closed = try await gateway.session.close(.init(sessionId: session.sessionId))
        guard closed.closed else { throw LiveScenarioError("Gateway session did not close") }
        guard let token = environment.token else { throw LiveScenarioError("REST smoke needs the local token") }
        let rest = HermesREST(configuration: .init(
            baseURL: environment.url, headers: { ["X-Hermes-Session-Token": token] }))
        let voice = try await rest.audio.voiceLiveStatus(profile: "default")
        guard voice.ok, voice.mode == "chained", !voice.model.isEmpty, !voice.voice.isEmpty else {
            throw LiveScenarioError("Unexpected voice status")
        }
        let profile = try await rest.profiles.active()
        guard profile.active == "default", profile.current == "default" else {
            throw LiveScenarioError("Unexpected active profile")
        }
        let selected = try await rest.profiles.setActive(body: .init(name: "default"))
        guard selected.ok, selected.active == "default" else {
            throw LiveScenarioError("Could not select the default profile")
        }
        let count = try await rest.sessions.emptyCount(profile: "default")
        guard count.count >= 0 else { throw LiveScenarioError("Invalid empty session count") }
    }

    /// Loses the socket mid-stream, silently, and with a question open, then resumes the session after a
    /// server restart. The heartbeat is shortened so a stalled socket is detected within seconds.
    static func reconnect(
        _ environment: LiveScenarioEnvironment, faults: FaultControl, observations: LiveObservations
    ) async throws {
        let gateway = HermesGateway(configuration: .init(
            baseURL: environment.url, auth: environment.auth,
            transport: ObservingTransport(inner: URLSessionGatewayTransport(), observations: observations),
            reconnectDelay: { _ in .milliseconds(250) },
            heartbeatInterval: .seconds(1), heartbeatDeadline: .seconds(4)))
        let clarifications = Counter()
        await gateway.setServerRequestHandler { request in
            guard case .clarify(let params) = request,
                  case .value(let questions) = params.questions, questions.count == 1 else {
                throw LiveScenarioError("Unexpected server request during reconnect")
            }
            if await clarifications.increment() == 1 {
                // Drop the socket while the question is open; the rebind must re-deliver it.
                try await faults.drop(holdMilliseconds: 1_000)
                try await Task.sleep(for: .seconds(60))
            }
            return .clarify(ClarifyResult(answers: [questions[0].qid: "Stable"]))
        }
        let states = StateLog()
        let stateStream = gateway.connectionStates()
        let stateTask = Task {
            for await state in stateStream { await states.append(state) }
        }
        defer { stateTask.cancel() }
        do {
            try await gateway.connect()
            let session = try await gateway.session.create(.init(
                cwd: .value("/tmp"), title: .value("HermesAPI reconnect session"), closeOnDisconnect: false))

            let streamed = try await runTurn(
                gateway, sessionID: session.sessionId, prompt: Fixture.reconnectPrompt, tool: nil,
                onFirstDelta: { try await faults.drop(holdMilliseconds: 1_500) })
            try streamed.expect(reply: Fixture.reconnectReply)
            guard streamed.replayedEvents > 0 else {
                throw LiveScenarioError("No event reached the client through reconnect replay")
            }
            try streamed.expectContiguousSequence()
            guard await states.reconnects() >= 1 else { throw LiveScenarioError("Gateway never reconnected") }

            // A stalled socket reports nothing; the default heartbeat must replace it before the
            // server reaps the session, which the next turn on the same session then proves.
            let reconnectsBeforeStall = await states.reconnects()
            try await faults.blackhole()
            try await withDeadline(.seconds(20), "the heartbeat to replace a silently stalled socket") {
                while await states.reconnects() <= reconnectsBeforeStall {
                    try await Task.sleep(for: .milliseconds(100))
                }
            }

            let clarified = try await runTurn(gateway, sessionID: session.sessionId,
                                              prompt: Fixture.clarifyPrompt, tool: "clarify")
            try clarified.expect(reply: Fixture.clarifyReply)
            try clarified.expectContiguousSequence()
            guard await clarifications.value == 2 else {
                throw LiveScenarioError("The open clarification was not re-delivered after reconnect")
            }

            // A restart loses every live session; the gateway resumes this one from Hermes' storage.
            let reconnectsBeforeRestart = await states.reconnects()
            let recoveries = gateway.sessionRecoveries()
            try await faults.restart()
            let recovery = try await withDeadline(.seconds(60), "the restarted server's session recovery") {
                for await recovery in recoveries { return Optional(recovery) }
                return nil
            }
            guard case .resumed(let previous, let resumedID, let storedID)? = recovery,
                  previous == session.sessionId, storedID == session.storedSessionId else {
                throw LiveScenarioError("Expected \(session.sessionId) to be resumed, got \(String(describing: recovery))")
            }
            try await withDeadline(.seconds(60), "the gateway to reconnect after the restart") {
                while await !states.isConnected(afterReconnects: reconnectsBeforeRestart) {
                    try await Task.sleep(for: .milliseconds(100))
                }
            }
            let resumed = try await runTurn(gateway, sessionID: resumedID,
                                            prompt: "Reply with a short greeting.", tool: nil)
            try resumed.expect(reply: Fixture.reply)
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
    }

    private static func runTurn(
        _ gateway: HermesGateway, sessionID: String, prompt: String, tool: String?,
        onFirstDelta: (@Sendable () async throws -> Void)? = nil
    ) async throws -> Turn {
        // Subscribe before submitting so no event of the turn can precede the subscription.
        let events = gateway.events()
        let submission = try await gateway.prompt.submit(.init(sessionId: sessionID, text: .string(prompt)))
        guard submission.status != nil else { throw LiveScenarioError("Gateway rejected the prompt") }
        return try await withDeadline(.seconds(45), "the turn for \"\(prompt)\" to complete") {
            var turn = Turn(tool: tool)
            for await event in events where event.sessionID == sessionID {
                if let seq = event.seq { turn.sequence.append(seq) }
                if event.replayed { turn.replayedEvents += 1 }
                switch event.payload {
                case .messageStart:
                    turn.sawStart = true
                case .toolStart(let payload) where payload.name == tool:
                    turn.sawToolStart = true
                case .toolComplete(let payload) where payload.name == tool:
                    turn.sawToolComplete = true
                    turn.toolResult = payload.result
                case .messageDelta(let payload):
                    let first = turn.streamed.isEmpty
                    turn.streamed += payload.text
                    if first, let onFirstDelta { try await onFirstDelta() }
                case .messageComplete(let payload):
                    if case .string(let text)? = payload.text { turn.reply = text }
                    return turn
                case .error:
                    throw LiveScenarioError("Gateway reported an error during \"\(prompt)\"")
                default:
                    continue
                }
            }
            throw LiveScenarioError("Event stream ended during \"\(prompt)\"")
        }
    }
}

private struct Turn: Sendable {
    let tool: String?
    var sawStart = false
    var sawToolStart = false
    var sawToolComplete = false
    var toolResult: JSONValue?
    var streamed = ""
    var reply: String?
    var sequence: [Int] = []
    var replayedEvents = 0

    init(tool: String?) { self.tool = tool }

    func expect(reply expected: String) throws {
        guard let reply, reply == expected else {
            throw LiveScenarioError("Expected reply \"\(expected)\", got \"\(reply ?? "nothing")\"")
        }
        guard sawStart else { throw LiveScenarioError("No message.start before \"\(expected)\"") }
        guard streamed.trimmingCharacters(in: .whitespacesAndNewlines) == reply else {
            throw LiveScenarioError("Streamed text \"\(streamed)\" differs from the final reply")
        }
        if let tool, !(sawToolStart && sawToolComplete) {
            throw LiveScenarioError("The \(tool) tool did not start and complete")
        }
    }

    /// Every sequenced event arrives exactly once and in order across a reconnect.
    func expectContiguousSequence() throws {
        guard let first = sequence.first, sequence == Array(first..<(first + sequence.count)) else {
            throw LiveScenarioError("Session events were lost, duplicated or reordered: \(sequence)")
        }
    }
}

/// Calls the harness control endpoint that severs sockets or restarts the tagged server.
struct FaultControl: Sendable {
    let base: URL

    func drop(holdMilliseconds: Int) async throws {
        try await post("drop", query: [URLQueryItem(name: "hold_ms", value: String(holdMilliseconds))])
    }

    func blackhole() async throws {
        try await post("blackhole", query: [])
    }

    func restart() async throws {
        try await post("restart", query: [])
    }

    /// Sends the run's measured coverage evidence.
    func report(_ observations: [String: [String]]) async throws {
        try await post("report", query: [], body: JSONEncoder().encode(observations))
    }

    private func post(_ path: String, query: [URLQueryItem], body: Data? = nil) async throws {
        guard var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)
        else { throw LiveScenarioError("Invalid control URL") }
        components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw LiveScenarioError("Invalid control URL") }
        var request = URLRequest(url: url, timeoutInterval: 120)
        request.httpMethod = "POST"
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (_, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 204 else {
            throw LiveScenarioError("Harness control \(path) failed")
        }
    }
}

private actor Counter {
    private(set) var value = 0

    func increment() -> Int {
        value += 1
        return value
    }
}

private actor StateLog {
    private var states: [GatewayConnectionState] = []

    func isConnected(afterReconnects previous: Int) -> Bool {
        states.last == .connected && reconnects() > previous
    }

    func append(_ state: GatewayConnectionState) { states.append(state) }

    /// Completed reconnects: a reconnecting run followed by a connected state.
    func reconnects() -> Int {
        var count = 0
        var reconnecting = false
        for state in states {
            if case .reconnecting = state { reconnecting = true }
            if reconnecting && state == .connected {
                count += 1
                reconnecting = false
            }
        }
        return count
    }
}

private struct StaticTicketAuth: HermesAuth {
    let ticket: String

    func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        .ticket(ticket, headers: [:])
    }
}

private func withDeadline<T: Sendable>(
    _ limit: Duration, _ waitingFor: String, _ operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(for: limit)
            throw LiveScenarioError("Timed out waiting for \(waitingFor)")
        }
        defer { group.cancelAll() }
        guard let first = try await group.next() else { throw LiveScenarioError("No result for \(waitingFor)") }
        return first
    }
}
