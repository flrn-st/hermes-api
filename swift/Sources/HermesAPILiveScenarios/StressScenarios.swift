import Foundation
import HermesAPI

/// What the stress harness seeded (`harness/stress.py`), served by its control endpoint.
struct StressDataset: Decodable, Sendable {
    struct LongChat: Decodable, Sendable {
        let id: String
        let messages: Int
    }

    struct Kanban: Decodable, Sendable {
        let board: String
        let tasks: Int
    }

    let sessions: Int
    let longChat: LongChat
    let profiles: [String]
    let profileSessions: Int
    let kanban: Kanban
    let search: String
    let longReply: String
    let pacedReply: String

    enum CodingKeys: String, CodingKey {
        case sessions, profiles, kanban, search
        case longChat = "long_chat", profileSessions = "profile_sessions", longReply = "long_reply"
        case pacedReply = "paced_reply"
    }
}

/// Timings in milliseconds, by metric name, reported to the harness, which enforces a budget for each.
actor StressMetrics {
    private(set) var values: [String: Double] = [:]

    func record(_ name: String, _ milliseconds: Double) { values[name] = milliseconds }
}

/// Large data, long streams, concurrency and degraded networks, against a seeded tagged server.
enum StressScenarios {
    static func run(_ environment: LiveScenarioEnvironment) async throws {
        guard let control = environment.control, let token = environment.token else {
            throw LiveScenarioError("Stress scenarios need the harness control endpoint and the local token")
        }
        let faults = FaultControl(base: control)
        let dataset = try await faults.stressDataset()
        let metrics = StressMetrics()
        let trail = HTTPTrail()
        let rest = HermesREST(configuration: .init(baseURL: environment.url, auth: LocalTokenAuth(token: token),
                                                   transport: TracingHTTPTransport(trail: trail)))
        try await largeData(rest, dataset: dataset, metrics: metrics)
        try await parallelReads(rest, metrics: metrics)
        let gateway = HermesGateway(configuration: .init(baseURL: environment.url, auth: environment.auth))
        do {
            try await gateway.connect()
            try await gatewayScale(gateway, dataset: dataset, metrics: metrics)
            try await degradedNetworks(gateway, rest: rest, trail: trail, faults: faults, dataset: dataset,
                                       metrics: metrics)
            await gateway.disconnect()
        } catch {
            try? await faults.conditions("none")
            await gateway.disconnect()
            throw error
        }
        try await faults.metrics(await metrics.values)
    }

    static func measure<T: Sendable>(_ name: String, _ metrics: StressMetrics,
                                     _ body: () async throws -> T) async throws -> T {
        let started = ContinuousClock.now
        let result: T
        do {
            result = try await body()
        } catch let error as LiveScenarioError {
            throw error
        } catch {
            throw LiveScenarioError("\(name) failed: \(error)")
        }
        let elapsed = ContinuousClock.now - started
        await metrics.record(name, Double(elapsed.components.seconds) * 1000
                             + Double(elapsed.components.attoseconds) / 1e15)
        return result
    }

    // MARK: REST over large data

    private static func largeData(_ rest: HermesREST, dataset: StressDataset, metrics: StressMetrics) async throws {
        // Every session exactly once across pages: no gap and no duplicate while paging.
        let (ids, total) = try await measure("rest.sessions.page_through", metrics) {
            var ids: [String] = []
            var total = 0
            repeat {
                let page = try await rest.sessions.get(limit: 100, offset: ids.count)
                total = page.total
                ids += page.sessions.map(\.id)
                if page.sessions.isEmpty { break }
            } while ids.count < total
            return (ids, total)
        }
        guard ids.count == total, Set(ids).count == total, total >= dataset.sessions / 2 else {
            throw LiveScenarioError("Paging sessions returned \(ids.count) (\(Set(ids).count) unique) of \(total)")
        }
        let found = try await measure("rest.sessions.search", metrics) {
            try await rest.sessions.search(q: dataset.search, limit: 20)
        }
        guard !found.results.isEmpty else { throw LiveScenarioError("Search found no seeded session") }
        _ = try await measure("rest.sessions.sidebar", metrics) {
            try await rest.profiles.sessionsSidebar(recentsLimit: 500, cronLimit: 500, messagingLimit: 500)
        }
        _ = try await measure("rest.sessions.projects_tree", metrics) {
            try await rest.profiles.projectsTree(previewLimit: 50, sessionLimit: 2000)
        }
        // The long chat in pages, as a chat view loads it, then in one export.
        let long = dataset.longChat
        let paged = try await measure("rest.long_chat.page_through", metrics) {
            var count = 0
            var lastID: Int?
            while true {
                let page = try await rest.sessions.messages(sessionId: long.id, limit: 200, offset: count)
                if let first = page.messages.first?.id, let lastID, first <= lastID {
                    throw LiveScenarioError("Message pages overlap or go backwards at offset \(count)")
                }
                lastID = page.messages.last?.id ?? lastID
                count += page.messages.count
                if page.messages.count < 200 { return count }
            }
        }
        guard paged == long.messages else { throw LiveScenarioError("Paged \(paged) of \(long.messages) messages") }
        _ = try await measure("rest.long_chat.timeline", metrics) {
            try await rest.sessions.timeline(sessionId: long.id, limit: 500)
        }
        let exported = try await measure("rest.long_chat.export", metrics) {
            try await rest.sessions.export(sessionId: long.id)
        }
        guard exported.messages.count == long.messages else {
            throw LiveScenarioError("The export carries \(exported.messages.count) of \(long.messages) messages")
        }
        let profiles = try await measure("rest.profiles.list", metrics) { try await rest.profiles.get() }
        let names = Set(profiles.profiles.map(\.name))
        guard dataset.profiles.allSatisfy(names.contains) else {
            throw LiveScenarioError("Profile list misses seeded profiles")
        }
        let board = try await measure("rest.kanban.board", metrics) {
            try await rest.kanban.board(board: dataset.kanban.board)
        }
        let tasks = board.columns.reduce(0) { $0 + $1.tasks.count }
        guard tasks >= dataset.kanban.tasks else {
            throw LiveScenarioError("The board shows \(tasks) of \(dataset.kanban.tasks) tasks")
        }
    }

    /// Many requests at once, as a dashboard screen with several panels issues them.
    private static func parallelReads(_ rest: HermesREST, metrics: StressMetrics) async throws {
        try await measure("rest.parallel_reads", metrics) {
            try await withThrowingTaskGroup(of: Void.self) { group in
                for index in 0..<64 {
                    group.addTask {
                        switch index % 4 {
                        case 0: _ = try await rest.sessions.get(limit: 50, offset: index)
                        case 1: _ = try await rest.status.get()
                        case 2: _ = try await rest.profiles.get()
                        default: _ = try await rest.sessions.stats()
                        }
                    }
                }
                try await group.waitForAll()
            }
        }
    }

    // MARK: Gateway

    private static func gatewayScale(_ gateway: HermesGateway, dataset: StressDataset,
                                     metrics: StressMetrics) async throws {
        let listed = try await measure("gateway.session_list", metrics) {
            try await gateway.session.list(.init(limit: .value(500)))
        }
        guard listed.sessions.count >= 100 else { throw LiveScenarioError("session.list returned too few sessions") }
        let long = dataset.longChat
        let resumed = try await measure("gateway.long_chat.resume", metrics) {
            try await gateway.session.resume(.init(sessionId: long.id, closeOnDisconnect: true))
        }
        guard resumed.messageCount >= long.messages else {
            throw LiveScenarioError("Resume reports \(resumed.messageCount) of \(long.messages) messages")
        }
        let history = try await measure("gateway.long_chat.history", metrics) {
            try await gateway.session.history(.init(sessionId: resumed.sessionId))
        }
        guard history.messages.count >= long.messages else {
            throw LiveScenarioError("History holds \(history.messages.count) of \(long.messages) messages")
        }
        _ = try await gateway.session.close(.init(sessionId: resumed.sessionId))

        // A very long reply streamed as fast as Hermes produces it.
        let session = try await gateway.session.create(.init(closeOnDisconnect: true))
        let firstDelta = FirstDelta()
        let turn = try await measure("gateway.long_stream", metrics) {
            try await LiveScenarios.runTurn(gateway, sessionID: session.sessionId, prompt: Fixture.longPrompt, tool: nil,
                                            deadline: .seconds(240), onFirstDelta: { await firstDelta.mark() })
        }
        try turn.expect(reply: dataset.longReply)
        try turn.expectContiguousSequence()
        guard let first = await firstDelta.milliseconds else { throw LiveScenarioError("The long stream sent no delta") }
        await metrics.record("gateway.long_stream.first_delta", first)

        // Several sessions turning at once over the one socket.
        let sessions = try await withThrowingTaskGroup(of: String.self) { group in
            for _ in 0..<6 { group.addTask { try await gateway.session.create(.init(closeOnDisconnect: true)).sessionId } }
            return try await group.reduce(into: []) { $0.append($1) }
        }
        try await measure("gateway.parallel_turns", metrics) {
            try await withThrowingTaskGroup(of: Void.self) { group in
                for id in sessions {
                    group.addTask {
                        let turn = try await LiveScenarios.runTurn(gateway, sessionID: id, prompt: Fixture.greetingPrompt,
                                                                   tool: nil, deadline: .seconds(240))
                        try turn.expect(reply: Fixture.reply)
                    }
                }
                try await group.waitForAll()
            }
        }
        for id in sessions + [session.sessionId] { _ = try await gateway.session.close(.init(sessionId: id)) }
    }

    // MARK: Degraded networks

    private static let reads: [@Sendable (HermesREST) async throws -> Void] = [
        { _ = try await $0.sessions.get(limit: 100) },
        { _ = try await $0.status.get() },
        { _ = try await $0.profiles.get() },
        { _ = try await $0.sessions.stats() },
        { _ = try await $0.config.get() },
        { _ = try await $0.skills.get() },
        { _ = try await $0.cron.jobs() },
        { _ = try await $0.tools.toolsets() },
    ]

    private static func degradedNetworks(_ gateway: HermesGateway, rest: HermesREST, trail: HTTPTrail,
                                         faults: FaultControl, dataset: StressDataset,
                                         metrics: StressMetrics) async throws {
        try await faults.conditions("3g")
        try await measure("rest.reads.3g", metrics) {
            for read in reads { try await read(rest) }
        }
        let slow = try await gateway.session.create(.init(closeOnDisconnect: true))
        let turn = try await measure("gateway.long_stream.3g", metrics) {
            try await LiveScenarios.runTurn(gateway, sessionID: slow.sessionId, prompt: Fixture.longPrompt, tool: nil,
                                            deadline: .seconds(300))
        }
        try turn.expect(reply: dataset.longReply)
        try turn.expectContiguousSequence()
        _ = try await gateway.session.close(.init(sessionId: slow.sessionId))

        // A link that resets every connection after a few seconds: reads retry, the stream resumes by replay.
        try await faults.conditions("flaky")
        do {
            try await measure("rest.reads.flaky", metrics) {
                for _ in 0..<4 { for read in reads { try await read(rest) } }
            }
        } catch {
            throw LiveScenarioError("\(error); last attempts: \(await trail.summary)")
        }
        let paced = try await step("gateway.session.create under flaky") {
            try await createSession(gateway, closeOnDisconnect: false)
        }
        let flaky = try await measure("gateway.paced_stream.flaky", metrics) {
            try await LiveScenarios.runTurn(gateway, sessionID: paced.sessionId, prompt: Fixture.pacedPrompt, tool: nil,
                                            deadline: .seconds(300))
        }
        try await faults.conditions("none")
        try flaky.expect(reply: dataset.pacedReply)
        try flaky.expectContiguousSequence()
        _ = try await gateway.session.close(.init(sessionId: paced.sessionId))
    }

    /// `session.create` may have run when the connection dropped under it; as an app would, create again.
    /// The first live session, if Hermes made it, closes with its unused socket.
    private static func createSession(_ gateway: HermesGateway, closeOnDisconnect: Bool) async throws -> SessionCreateResult {
        for attempt in 1...5 {
            do {
                return try await gateway.session.create(.init(closeOnDisconnect: closeOnDisconnect))
            } catch HermesGatewayError.transport where attempt < 5 {
                continue
            }
        }
        throw LiveScenarioError("session.create kept losing its connection")
    }

    private static func step<T: Sendable>(_ name: String, _ body: () async throws -> T) async throws -> T {
        do {
            return try await body()
        } catch {
            throw LiveScenarioError("\(name) failed: \(error)")
        }
    }
}

/// When the first streamed delta arrived, relative to creation.
private actor FirstDelta {
    private let started = ContinuousClock.now
    private var at: Duration?

    func mark() { if at == nil { at = ContinuousClock.now - started } }

    var milliseconds: Double? {
        guard let at else { return nil }
        return Double(at.components.seconds) * 1000 + Double(at.components.attoseconds) / 1e15
    }
}

/// The latest HTTP attempts of a stress run, for failure messages: the REST client logs only to the
/// unified log, which a CI run does not show.
actor HTTPTrail {
    private let started = ContinuousClock.now
    private var entries: [String] = []

    func record(_ entry: String) {
        let at = (ContinuousClock.now - started).components.seconds
        entries.append("+\(at)s \(entry)")
        if entries.count > 30 { entries.removeFirst(entries.count - 30) }
    }

    var summary: String { entries.joined(separator: " | ") }
}

struct TracingHTTPTransport: HTTPTransport {
    let trail: HTTPTrail
    let inner: any HTTPTransport = URLSessionHTTPTransport()

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let started = ContinuousClock.now
        let name = "\(request.httpMethod ?? "GET") \(request.url?.path ?? "?")"
        do {
            let (data, response) = try await inner.send(request)
            await trail.record("\(name) \(response.statusCode) \(data.count)B in \(ContinuousClock.now - started)")
            return (data, response)
        } catch {
            await trail.record("\(name) failed after \(ContinuousClock.now - started): \(error.localizedDescription)")
            throw error
        }
    }
}
