import Foundation
import HermesAPI

/// Live scenario inputs, read from `HERMES_LIVE_*` variables that the harness sets.
public struct LiveScenarioEnvironment: Sendable {
    public let url: URL
    public let auth: any HermesAuth
    public let token: String?
    public let lifecycle: Bool
    /// `stress` runs the stress scenarios against the stress harness's seeded server instead.
    public let mode: String
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
        mode = values["HERMES_LIVE_MODE"] ?? ""
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
    static let acceptPrompt = "Run the fixture command that needs approval and report the result."
    static let acceptCommand = "rm -rf /tmp/hermes-api-fixture-accepted-target"
    static let acceptReply = "HermesAPI approval accepted."
    static let secretPrompt = "Load the HermesAPI fixture skill that needs a secret."
    static let secretEnvVar = "HERMES_API_FIXTURE_SECRET"
    static let secretReply = "HermesAPI secret request answered."
    static let withdrawnPrompt = "Ask a HermesAPI question that will be withdrawn."
    static let withdrawnQuestion = "Which HermesAPI question will be withdrawn?"
    static let greetingPrompt = "Reply with a short greeting."
    static let longPrompt = "Stream the HermesAPI long fixture."
    static let pacedPrompt = "Stream the HermesAPI paced fixture."
}

public enum LiveScenarios {
    /// Runs the smoke scenario and, when the harness exposes its control endpoint, the reconnect scenarios.
    public static func run(_ environment: LiveScenarioEnvironment) async throws {
        if environment.mode == "stress" {
            try await StressScenarios.run(environment)
            return
        }
        let observations = LiveObservations()
        try await smoke(environment, observations: observations)
        guard let control = environment.control else { return }
        let faults = FaultControl(base: control)
        if let token = environment.token {
            let scenario = try await faults.restScenario()
            try await RESTScenario.run(scenario.calls, baseURL: environment.url, auth: LocalTokenAuth(token: token),
                                       observations: observations)
            if let gated = scenario.gated {
                // Its own cookie storage: the gated calls sign in with a browser cookie session.
                let transport = URLSessionHTTPTransport(session: URLSession(configuration: .ephemeral))
                try await RESTScenario.run(gated.calls, baseURL: gated.url, auth: nil, transport: transport,
                                           observations: observations)
                if let username = gated.username, let password = gated.password {
                    try await ConnectionScenarios.passwordSession(
                        .init(url: gated.url, username: username, password: password))
                }
            }
            if let tls = scenario.tls {
                try await ConnectionScenarios.pinnedServer(tls, token: token)
            }
        }
        if environment.lifecycle {
            try await GatewayScenario.run(try await faults.gatewayScenario().calls, environment: environment,
                                          observations: observations)
            try await reconnect(environment, faults: faults, observations: observations)
        }
        // Only a fully passing run reports what it exercised.
        try await faults.report(observations.report())
    }

    static func smoke(_ environment: LiveScenarioEnvironment, observations: LiveObservations) async throws {
        let gateway = HermesGateway(configuration: .init(
            address: HermesDashboardAddress(environment.url), auth: environment.auth,
            transport: ObservingTransport(inner: URLSessionGatewayTransport(), observations: observations)))
        let requests = RequestLog()
        await gateway.setServerRequestHandler { request in
            switch request {
            case .clarify(let params):
                guard case .value(let questions) = params.questions, questions.count == 1 else {
                    throw LiveScenarioError("Unexpected clarification request")
                }
                if questions[0].question == Fixture.withdrawnQuestion {
                    // Left open until Hermes withdraws it; the gateway then cancels this handler.
                    await requests.opened()
                    do {
                        try await Task.sleep(for: .seconds(300))
                    } catch {
                        await requests.withdrawn()
                        throw error
                    }
                    throw LiveScenarioError("The open question was never withdrawn")
                }
                guard questions[0].question == "Which release channel?" else {
                    throw LiveScenarioError("Unexpected clarification request")
                }
                return .clarify(ClarifyResult(answers: [questions[0].qid: "Stable"]))
            case .approval(let params):
                switch params.command {
                case Fixture.approvalCommand: return .approval(ApprovalResult(choice: .deny))
                case Fixture.acceptCommand: return .approval(ApprovalResult(choice: .once))
                default: throw LiveScenarioError("Unexpected approval command")
                }
            case .secret(let params):
                guard params.envVar == Fixture.secretEnvVar else { throw LiveScenarioError("Unexpected secret request") }
                await requests.secret()
                // An empty value skips the variable, so Hermes stores nothing and asks again next run.
                return .secret(ValueResult(value: ""))
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
                // Only against the tagged server: the ticket probe answers every method as unknown.
                try await refusals(gateway)
                try await lifecycle(gateway, environment: environment, requests: requests)
            }
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
    }

    /// Calls Hermes refuses, and the reviewed meaning each refusal must carry (`spec/gateway-errors.yaml`).
    private static func refusals(_ gateway: HermesGateway) async throws {
        let missing = "hermes-api-missing-session"
        try await refused(.unknownMethod, .unsupported) {
            _ = try await gateway.call("hermes.api.no_such_method", params: PingParams(), as: JSONValue.self)
        }
        try await refused(.invalidParams, .invalidRequest) {
            let params: JSONValue = .object(["session_id": .string(missing), "last_seen": .string("latest")])
            _ = try await gateway.call("session.events.since", params: params, as: JSONValue.self)
        }
        try await refused(.sessionNotFound, .notFound) {
            _ = try await gateway.session.activate(.init(sessionId: missing, omitMessages: true))
        }
        try await refused(.sessionNotFound, .notFound) {
            _ = try await gateway.session.resume(.init(sessionId: missing, omitMessages: true))
        }
        try await refused(.sessionNotFound, .notFound) {
            _ = try await gateway.prompt.submit(.init(sessionId: missing, text: .string("Refused")))
        }
        try await refused(.profileNotFound, .notFound) {
            _ = try await gateway.profiles.describe(.init(name: .value("hermes-api-missing-profile")))
        }
        try await refused(.paramsRejected, .invalidRequest) {
            _ = try await gateway.spawnTree.load(.init(path: ""))
        }
    }

    private static func refused(
        _ known: GatewayKnownError, _ kind: GatewayErrorKind, _ call: () async throws -> Void
    ) async throws {
        do {
            try await call()
        } catch let error as HermesGatewayError {
            guard error.known == known, error.kind == kind else {
                throw LiveScenarioError("Expected Hermes to refuse with \(known) (\(kind)), got \(error)")
            }
            return
        }
        throw LiveScenarioError("Expected Hermes to refuse with \(known), but the call succeeded")
    }

    private static func lifecycle(
        _ gateway: HermesGateway, environment: LiveScenarioEnvironment, requests: RequestLog
    ) async throws {
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
        let accepted = try await runTurn(gateway, sessionID: session.sessionId,
                                         prompt: Fixture.acceptPrompt, tool: "terminal")
        try accepted.expect(reply: Fixture.acceptReply)
        guard case .object(let ran)? = accepted.toolResult, ran["exit_code"] == .integer(0) else {
            throw LiveScenarioError("Approved terminal command did not run: \(String(describing: accepted.toolResult))")
        }
        let secret = try await runTurn(gateway, sessionID: session.sessionId,
                                       prompt: Fixture.secretPrompt, tool: "skill_view")
        try secret.expect(reply: Fixture.secretReply)
        guard await requests.secrets == 1 else { throw LiveScenarioError("Hermes did not ask for the skill's secret") }
        try await withdrawnQuestion(gateway, sessionID: session.sessionId, requests: requests)
        try await busySession(gateway, sessionID: session.sessionId)
        _ = try await gateway.session.list(.init())
        let closed = try await gateway.session.close(.init(sessionId: session.sessionId))
        guard closed.closed else { throw LiveScenarioError("Gateway session did not close") }
        guard let token = environment.token else { throw LiveScenarioError("REST smoke needs the local token") }
        let rest = HermesREST(configuration: .init(address: HermesDashboardAddress(environment.url), auth: LocalTokenAuth(token: token)))
        let voice = try await rest.audio.voiceLiveStatus(profile: "default")
        guard voice.ok, voice.mode == .chained, !voice.model.isEmpty, !voice.voice.isEmpty else {
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

    /// Leaves a question open and interrupts the turn: Hermes withdraws it with `request.cancel`, and the
    /// gateway cancels the handler still waiting on the user.
    private static func withdrawnQuestion(_ gateway: HermesGateway, sessionID: String, requests: RequestLog) async throws {
        let events = gateway.events()
        _ = try await gateway.prompt.submit(.init(sessionId: sessionID, text: .string(Fixture.withdrawnPrompt)))
        try await withDeadline(.seconds(60), "the question to reach the handler") {
            while await !requests.isOpen { try await Task.sleep(for: .milliseconds(100)) }
        }
        _ = try await gateway.session.interrupt(.init(sessionId: sessionID))
        try await withDeadline(.seconds(30), "Hermes to withdraw the question and end the turn") {
            var withdrawn = false
            for await event in events where event.sessionID == sessionID {
                switch event.payload {
                case .requestCancel(let cancel):
                    guard cancel.method == "clarify" else { throw LiveScenarioError("Unexpected withdrawn request") }
                    withdrawn = true
                case .messageComplete:
                    guard withdrawn else { throw LiveScenarioError("The turn ended without withdrawing the question") }
                    return
                default:
                    continue
                }
            }
            throw LiveScenarioError("Event stream ended before the question was withdrawn")
        }
        try await withDeadline(.seconds(10), "the gateway to cancel the waiting handler") {
            while await !requests.isWithdrawn { try await Task.sleep(for: .milliseconds(100)) }
        }
    }

    /// A call Hermes refuses while a turn runs, and the interrupt that ends the turn.
    private static func busySession(_ gateway: HermesGateway, sessionID: String) async throws {
        let events = gateway.events()
        _ = try await gateway.prompt.submit(.init(sessionId: sessionID, text: .string(Fixture.pacedPrompt)))
        try await withDeadline(.seconds(60), "the paced turn to start streaming") {
            for await event in events where event.sessionID == sessionID {
                if case .messageDelta = event.payload { return }
            }
            throw LiveScenarioError("Event stream ended before the paced turn streamed")
        }
        try await refused(.sessionBusy, .busy) {
            _ = try await gateway.session.cwdSet(.init(sessionId: sessionID, cwd: "/tmp"))
        }
        _ = try await gateway.session.interrupt(.init(sessionId: sessionID))
        try await withDeadline(.seconds(30), "the interrupted turn to end") {
            for await event in events where event.sessionID == sessionID {
                if case .messageComplete = event.payload { return }
            }
            throw LiveScenarioError("Event stream ended before the interrupted turn ended")
        }
    }

    /// Loses the socket mid-stream, silently, and with a question open, then resumes the session after a
    /// server restart. The heartbeat is shortened so a stalled socket is detected within seconds.
    static func reconnect(
        _ environment: LiveScenarioEnvironment, faults: FaultControl, observations: LiveObservations
    ) async throws {
        let gateway = HermesGateway(configuration: .init(
            address: HermesDashboardAddress(environment.url), auth: environment.auth,
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

            // An outage longer than Hermes' 20 s grace drops the session while its turn keeps writing the
            // reply to the replay buffer; the gateway resumes the session and still delivers the whole turn.
            let outageRecoveries = gateway.sessionRecoveries()
            let outage = try await runTurn(
                gateway, sessionID: resumedID, prompt: Fixture.reconnectPrompt, tool: nil, deadline: .seconds(120),
                onFirstDelta: { try await faults.drop(holdMilliseconds: 25_000) })
            try outage.expect(reply: Fixture.reconnectReply)
            try outage.expectContiguousSequence()
            let outageRecovery = try await withDeadline(.seconds(10), "the recovery after the long outage") {
                for await recovery in outageRecoveries { return Optional(recovery) }
                return nil
            }
            guard case .resumed(let dropped, _, _)? = outageRecovery, dropped == resumedID else {
                throw LiveScenarioError("Expected \(resumedID) to be resumed after the outage, got \(String(describing: outageRecovery))")
            }
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
    }

    static func runTurn(
        _ gateway: HermesGateway, sessionID: String, prompt: String, tool: String?,
        deadline: Duration = .seconds(120), onFirstDelta: (@Sendable () async throws -> Void)? = nil
    ) async throws -> Turn {
        // Subscribe before submitting so no event of the turn can precede the subscription.
        let events = gateway.events()
        let started = TurnStarted()
        let states = gateway.connectionStates()
        let recoveries = gateway.sessionRecoveries()
        let stateWatch = Task {
            for await state in states { await started.state(String(describing: state)) }
        }
        let recoveryWatch = Task {
            for await recovery in recoveries { await started.note("recovery \(recovery)") }
        }
        defer {
            stateWatch.cancel()
            recoveryWatch.cancel()
        }
        do {
            let submission = try await gateway.prompt.submit(.init(sessionId: sessionID, text: .string(prompt)))
            guard submission.status != nil else { throw LiveScenarioError("Gateway rejected the prompt") }
        } catch HermesGatewayError.transport {
            await started.note("submit lost its response")
            // The connection dropped with the submission unanswered: Hermes may have started the turn. As an
            // app should, watch the replayed events and submit again only if the turn never shows up.
            let resubmit = Task {
                try await Task.sleep(for: .seconds(15))
                guard await !started.value else { return }
                await started.note("resubmitted")
                _ = try await gateway.prompt.submit(.init(sessionId: sessionID, text: .string(prompt)))
            }
            await started.onFinish { resubmit.cancel() }
        }
        // Generous: Hermes builds the agent on the first turn, which is slow on a cold CI runner.
        do {
            return try await turnEvents(events, sessionID: sessionID, prompt: prompt, tool: tool, deadline: deadline,
                                        started: started, onFirstDelta: onFirstDelta)
        } catch let error as LiveScenarioError where error.description.hasPrefix("Timed out") {
            throw LiveScenarioError("\(error.description); \(await started.trace); \(await serverTail(gateway, sessionID, started))")
        }
    }

    /// What Hermes still holds after the last event the client saw, to tell a lost event from one never sent.
    private static func serverTail(_ gateway: HermesGateway, _ sessionID: String, _ started: TurnStarted) async -> String {
        let seen = await started.lastSeq ?? 0
        do {
            let tail = try await gateway.session.eventsSince(.init(sessionId: sessionID, lastSeen: .value(seen)))
            let types = tail.events.map { event in
                "\(event["type"].map { String(describing: $0) } ?? "?")#\(event["seq"].map { String(describing: $0) } ?? "?")"
            }
            return "server after seq \(seen): latest \(tail.latestSeq), truncated \(tail.truncated), events \(types)"
        } catch {
            return "server tail unavailable: \(error)"
        }
    }

    private static func turnEvents(
        _ events: AsyncStream<GatewayEvent>, sessionID: String, prompt: String, tool: String?, deadline: Duration,
        started: TurnStarted, onFirstDelta: (@Sendable () async throws -> Void)?
    ) async throws -> Turn {
        try await withDeadline(deadline, "the turn for \"\(prompt)\" to complete") {
            defer { Task { await started.finish() } }
            var turn = Turn(tool: tool)
            for await event in events {
                guard event.sessionID == sessionID else {
                    if turn.otherSessions.insert(event.sessionID ?? "none").inserted {
                        await started.note("\(event.type) for another session")
                    }
                    continue
                }
                turn.types[event.type, default: 0] += 1
                await started.event(event.type, seq: event.seq, replayed: event.replayed)
                if event.type == "message.start" || event.type == "message.delta" { await started.mark() }
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

/// Whether a submitted turn has shown up in the event stream, and what happened on the way, for failures.
private actor TurnStarted {
    private(set) var value = false
    private var finishers: [@Sendable () -> Void] = []
    private var log: [String] = []
    private var counts: [String: Int] = [:]
    private(set) var lastSeq: Int?
    private var replayedCount = 0

    func mark() { value = true }

    func state(_ description: String) { log.append("state \(description)") }

    func note(_ text: String) { log.append(text) }

    func event(_ type: String, seq: Int?, replayed: Bool) {
        counts[type, default: 0] += 1
        lastSeq = seq ?? lastSeq
        if replayed { replayedCount += 1 }
        if type != "message.delta" { log.append("\(type) seq \(seq.map(String.init) ?? "-")\(replayed ? " replayed" : "")") }
    }

    var trace: String {
        let tally = counts.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
        return "events [\(tally)], \(replayedCount) replayed, last seq \(lastSeq.map(String.init) ?? "-"); last: \(log.suffix(25).joined(separator: " | "))"
    }

    func onFinish(_ action: @escaping @Sendable () -> Void) { finishers.append(action) }

    func finish() {
        for action in finishers { action() }
        finishers.removeAll()
    }
}

struct Turn: Sendable {
    let tool: String?
    var sawStart = false
    var sawToolStart = false
    var sawToolComplete = false
    var toolResult: JSONValue?
    var streamed = ""
    var reply: String?
    var sequence: [Int] = []
    var replayedEvents = 0
    var types: [String: Int] = [:]
    var otherSessions: Set<String> = []

    init(tool: String?) { self.tool = tool }

    /// What arrived for the turn, for failure messages.
    var trace: String {
        let counts = types.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
        return "events [\(counts)], replayed \(replayedEvents), seq \(sequence.first.map(String.init) ?? "-")"
            + "...\(sequence.last.map(String.init) ?? "-") (\(sequence.count)), other sessions \(otherSessions.sorted())"
    }

    func expect(reply expected: String) throws {
        guard let reply, reply == expected else {
            throw LiveScenarioError("Expected reply \"\(expected)\", got \"\(reply ?? "nothing")\"")
        }
        guard sawStart else { throw LiveScenarioError("No message.start before \"\(expected)\"") }
        guard streamed.trimmingCharacters(in: .whitespacesAndNewlines) == reply else {
            throw LiveScenarioError("Streamed text \"\(streamed.prefix(200))\" differs from the final reply; \(trace)")
        }
        if let tool, !(sawToolStart && sawToolComplete) {
            throw LiveScenarioError("The \(tool) tool did not start and complete")
        }
    }

    /// Every sequenced event arrives exactly once and in order across a reconnect.
    func expectContiguousSequence() throws {
        guard let first = sequence.first, sequence == Array(first..<(first + sequence.count)) else {
            throw LiveScenarioError("Session events were lost, duplicated or reordered: \(sequence); \(trace)")
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

    /// Shapes the proxied connections with a named network profile (`harness/faults.py`).
    func conditions(_ profile: String) async throws {
        try await post("conditions", query: [URLQueryItem(name: "profile", value: profile)])
    }

    /// Sends a stress run's timings in milliseconds.
    func metrics(_ values: [String: Double]) async throws {
        try await post("metrics", query: [], body: JSONEncoder().encode(values))
    }

    /// The dataset the stress harness seeded.
    func stressDataset() async throws -> StressDataset {
        let (data, response) = try await URLSession.shared.data(
            for: URLRequest(url: base.appendingPathComponent("stress"), timeoutInterval: 30))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw LiveScenarioError("Harness control stress failed")
        }
        return try JSONDecoder().decode(StressDataset.self, from: data)
    }

    /// The gateway scenario the harness recorded fixtures from.
    func gatewayScenario() async throws -> GatewayScenarioDocument {
        let (data, response) = try await URLSession.shared.data(
            for: URLRequest(url: base.appendingPathComponent("gateway-scenario"), timeoutInterval: 30))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw LiveScenarioError("Harness control gateway-scenario failed")
        }
        return try JSONDecoder().decode(GatewayScenarioDocument.self, from: data)
    }

    /// The REST scenario the harness recorded fixtures from.
    func restScenario() async throws -> RESTScenarioDocument {
        let (data, response) = try await URLSession.shared.data(
            for: URLRequest(url: base.appendingPathComponent("rest-scenario"), timeoutInterval: 30))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw LiveScenarioError("Harness control rest-scenario failed")
        }
        return try JSONDecoder().decode(RESTScenarioDocument.self, from: data)
    }

    private func post(_ path: String, query: [URLQueryItem], body: Data? = nil) async throws {
        guard var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)
        else { throw LiveScenarioError("Invalid control URL") }
        components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw LiveScenarioError("Invalid control URL") }
        var request = URLRequest(url: url, timeoutInterval: 180)
        request.httpMethod = "POST"
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 204 else {
            let reason = String(decoding: data.prefix(2000), as: UTF8.self)
            throw LiveScenarioError("Harness control \(path) failed: \(reason)")
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

func withDeadline<T: Sendable>(
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

/// What the live handler saw of the requests the lifecycle provokes.
actor RequestLog {
    private(set) var isOpen = false
    private(set) var isWithdrawn = false
    private(set) var secrets = 0

    func opened() { isOpen = true }
    func withdrawn() { isWithdrawn = true }
    func secret() { secrets += 1 }
}
