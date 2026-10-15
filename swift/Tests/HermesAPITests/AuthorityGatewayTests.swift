import Foundation
import HermesAPI
import HermesAPITesting
import Testing

// The gateway against a scripted Hermes session authority: detection, the translated frames it sends for the
// generated legacy calls, the legacy shapes it answers with, prompts as events, and per-session replay.

private func gatewayClient(_ transport: ScriptedGatewayTransport, timeout: Duration = .seconds(2)) -> HermesGateway {
    HermesGateway(configuration: .init(
        address: HermesDashboardAddress(URL(string: "https://example.test")!), auth: StaticTicketAuth(),
        transport: transport, httpTransport: URLSessionHTTPTransport(), networkMonitor: nil, requestTimeout: timeout,
        reconnectDelay: { _ in .zero }, heartbeatInterval: .seconds(60), heartbeatDeadline: .seconds(120)
    ))
}

private func ready(authority: Bool) -> String {
    let flag = authority ? #","session_authority":true"# : ""
    return GatewayFrames.event(
        "gateway.ready", payload: #"{"skin":{},"change_events":true,"replay_epoch":"process-1","heartbeat":true\#(flag)}"#)
}

private let describeResult = """
{"instance_id":"inst","profile_id":"/home/h","authority_epoch":1,"capabilities":["durable-admission-v1"],
 "session_create":{"sources":["acp","cli","gui","oneshot","tool","tui"],
 "parameters":["cwd","cwd_explicit","follow_profile_config","model","provider","reasoning","request_id","service_tier","source","title","toolsets"]}}
"""

private func snapshot(
    _ id: String, revision: Int = 3, generation: Int = 7, epoch: String = "e1", last: Int = 0,
    messages: String = "[]", prompts: String = "[]", running: Bool = false
) -> String {
    """
    {"session_id":"\(id)","stored_session_id":"\(id)","messages":\(messages),"message_count":0,"running":\(running),
     "authority_epoch":1,"replay_epoch":"\(epoch)","last_sequence":\(last),"subscription_id":"sub-\(id)",
     "revision":\(revision),"execution_generation":\(generation),"pending":[],"prompts":\(prompts),
     "info":{"desktop_protocol":"hermes-gateway-v1","model":"m","cwd":"/w"}}
    """
}

private func authorityEvent(
    _ type: String, session: String, seq: Int, epoch: String = "e1", generation: Int? = nil, payload: String = "{}"
) -> String {
    let stamp = generation.map { #","execution_generation":\#($0)"# } ?? ""
    return #"{"jsonrpc":"2.0","method":"event","params":{"type":"\#(type)","session_id":"\#(session)","seq":\#(seq),"replay_epoch":"\#(epoch)"\#(stamp),"payload":\#(payload)}}"#
}

private func refusal(_ id: Int, _ reason: String) -> String {
    GatewayFrames.error(id, code: 4001, reason, data: ["reason": .string(reason)])
}

/// The frames a scripted socket received, read in order.
private struct Sent {
    var iterator: AsyncStream<Data>.AsyncIterator

    init(_ socket: ScriptedGatewaySocket) { iterator = socket.sent.makeAsyncIterator() }

    mutating func next() async throws -> SentCall {
        try SentCall(try #require(await iterator.next()))
    }

    mutating func next(_ method: String) async throws -> SentCall {
        let call = try await next()
        #expect(call.method == method)
        return call
    }
}

/// Connects to a socket that announces a session authority and answers `runtime.describe`.
private func connectAuthority(
    _ socket: ScriptedGatewaySocket, gateway: HermesGateway, sent: inout Sent
) async throws {
    await socket.inject(ready(authority: true))
    let connecting = Task { try await gateway.connect() }
    let describe = try await sent.next("runtime.describe")
    await socket.inject(GatewayFrames.result(describe.id, describeResult))
    try await connecting.value
}

/// An authority gateway with one session `local-a` created and attached.
private func authorityWithSession(
    timeout: Duration = .seconds(2)
) async throws -> (HermesGateway, ScriptedGatewaySocket, Sent) {
    let socket = ScriptedGatewaySocket()
    let gateway = gatewayClient(ScriptedGatewayTransport([socket]), timeout: timeout)
    var sent = Sent(socket)
    try await connectAuthority(socket, gateway: gateway, sent: &sent)
    let created = Task { try await gateway.methods.session.create(.init(profile: .value("default"))) }
    let create = try await sent.next("session.create")
    await socket.inject(GatewayFrames.result(create.id, snapshot("local-a")))
    _ = try await created.value
    return (gateway, socket, sent)
}

private let iosCreate = SessionCreateParams(
    profile: .value("default"), cols: .value(100), source: .value("ios"), cwd: .value("/w"), title: .value("T"),
    model: .value("m"), provider: .value("p"), reasoningEffort: .value("high"), fast: .value(true),
    closeOnDisconnect: false, hidden: true
)

// MARK: - Detection

@Test func aLegacyGatewayGetsTheFramesItAlwaysDid() async throws {
    let socket = ScriptedGatewaySocket()
    let gateway = gatewayClient(ScriptedGatewayTransport([socket]))
    var sent = Sent(socket)
    await socket.inject(ready(authority: false))
    try await gateway.connect()
    #expect(await gateway.sessionAuthority == false)
    let created = Task { try await gateway.methods.session.create(iosCreate) }
    let create = try await sent.next("session.create")
    #expect(create.params == (try JSONValue(jsonData: JSONEncoder().encode(iosCreate))))
    await socket.inject(GatewayFrames.result(create.id, #"{"session_id":"rt","stored_session_id":"st","message_count":0,"messages":[],"info":{}}"#))
    _ = try await created.value
    let submitted = Task { try await gateway.methods.prompt.submit(.init(sessionId: "rt", text: .string("hi"))) }
    let submit = try await sent.next("prompt.submit")
    #expect(submit.params == ["session_id": "rt", "text": "hi"])
    await socket.inject(GatewayFrames.result(submit.id, #"{"status":"streaming"}"#))
    _ = try await submitted.value
    await gateway.disconnect()
}

@Test func anAnnouncedAuthorityThatCannotDescribeItselfStaysLegacy() async throws {
    let socket = ScriptedGatewaySocket()
    let gateway = gatewayClient(ScriptedGatewayTransport([socket]))
    var sent = Sent(socket)
    await socket.inject(ready(authority: true))
    let connecting = Task { try await gateway.connect() }
    let describe = try await sent.next("runtime.describe")
    await socket.inject(GatewayFrames.error(describe.id, code: -32601, "unknown method: runtime.describe"))
    try await connecting.value
    #expect(await gateway.sessionAuthority == false)
    let created = Task { try await gateway.methods.session.create(iosCreate) }
    let create = try await sent.next("session.create")
    #expect(create.params["source"] == "ios")
    #expect(create.params["cols"] == 100)
    await socket.inject(GatewayFrames.result(create.id, #"{"session_id":"rt","stored_session_id":"st","message_count":0,"messages":[],"info":{}}"#))
    _ = try await created.value
    await gateway.disconnect()
}

// MARK: - Sessions

@Test func aCreateIsTranslatedAndAnsweredInTheLegacyShape() async throws {
    let socket = ScriptedGatewaySocket()
    let gateway = gatewayClient(ScriptedGatewayTransport([socket]))
    var sent = Sent(socket)
    try await connectAuthority(socket, gateway: gateway, sent: &sent)
    #expect(await gateway.sessionAuthority)
    let created = Task { try await gateway.methods.session.create(iosCreate) }
    let create = try await sent.next("session.create")
    let requestID = try #require(create.params["request_id"]?.stringValue)
    #expect(create.params == [
        "profile": "default", "source": "gui", "cwd": "/w", "title": "T", "model": "m", "provider": "p",
        "reasoning": "high", "service_tier": "priority", "hidden": true, "request_id": .string(requestID),
    ])
    await socket.inject(refusal(create.id, "storage_unavailable"))
    await #expect(throws: HermesGatewayError.self) { try await created.value }

    // The same create again is the same request until one is answered.
    let retried = Task { try await gateway.methods.session.create(iosCreate) }
    let retry = try await sent.next("session.create")
    #expect(retry.params["request_id"] == .string(requestID))
    await socket.inject(GatewayFrames.result(retry.id, snapshot("local-a", running: true)))
    let result = try await retried.value
    #expect(result.sessionId == "local-a")
    #expect(result.storedSessionId == "local-a")
    #expect(result.info.running == true)
    #expect(result.info.cwd == "/w")

    let again = Task { try await gateway.methods.session.create(iosCreate) }
    let next = try await sent.next("session.create")
    #expect(next.params["request_id"] != .string(requestID))
    await socket.inject(GatewayFrames.result(next.id, snapshot("local-b")))
    _ = try await again.value
    await gateway.disconnect()
}

@Test func aForkBranchesTheParentThroughTheSeedsLastRow() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    var params = SessionCreateParams(profile: .value("default"), source: .value("ios"))
    params.messages = .value([SeedMessage(role: "user", content: .value("hi")), SeedMessage(role: "assistant", content: .value("hello"))])
    params.parentSessionId = .value("local-a")
    let forked = Task { try await gateway.methods.session.create(params) }
    let resume = try await sent.next("session.resume")
    #expect(resume.params == ["session_id": "local-a", "profile": "default"])
    let rows = #"[{"role":"user","text":"hi","row_id":11},{"role":"assistant","text":"hello","row_id":12},{"role":"user","text":"more","row_id":13}]"#
    await socket.inject(GatewayFrames.result(resume.id, snapshot("local-a", revision: 5, generation: 9, messages: rows)))
    let mutate = try await sent.next("session.mutate")
    #expect(mutate.params["operation"] == "branch")
    #expect(mutate.params["payload"] == ["through_message_id": 12])
    #expect(mutate.params["expected_revision"] == 5)
    #expect(mutate.params["expected_generation"] == 9)
    #expect(mutate.params["request_id"]?.stringValue != nil)
    await socket.inject(GatewayFrames.result(mutate.id,
        #"{"session_id":"local-a","operation":"branch","revision":6,"branched_session_id":"local-child","copied_messages":2}"#))
    let child = try await sent.next("session.resume")
    #expect(child.params == ["session_id": "local-child", "profile": "default"])
    await socket.inject(GatewayFrames.result(child.id, snapshot("local-child")))
    #expect(try await forked.value.sessionId == "local-child")
    await gateway.disconnect()
}

@Test func aCloseDetachesTheSubscription() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    let closed = Task { try await gateway.methods.session.close(.init(sessionId: "local-a")) }
    let detach = try await sent.next("session.detach")
    #expect(detach.params == ["session_id": "local-a", "subscription_id": "sub-local-a", "profile": "default"])
    await socket.inject(GatewayFrames.result(detach.id, #"{"session_id":"local-a","subscription_id":"sub-local-a","detached":true}"#))
    #expect(try await closed.value.closed)
    await gateway.disconnect()
}

@Test func aResumeDropsWhatTheAuthorityRefuses() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    let resumed = Task {
        try await gateway.methods.session.resume(.init(
            sessionId: "local-b", profile: .value("default"), source: .value("ios"), lazy: true, omitMessages: true,
            closeOnDisconnect: false))
    }
    let resume = try await sent.next("session.resume")
    #expect(resume.params == ["session_id": "local-b", "profile": "default", "source": "ios", "omit_messages": true])
    let approval = #"[{"kind":"approval","prompt_id":"a1","execution_generation":7,"choices":["once","deny"],"command":"rm x"}]"#
    await socket.inject(GatewayFrames.result(resume.id, snapshot("local-b", prompts: approval)))
    let result = try await resumed.value
    #expect(result.pendingApproval?.requestId == "a1")
    #expect(result.pendingApproval?.command == "rm x")
    #expect(result.resumed == nil)
    #expect(result.openRequests == nil)
    await gateway.disconnect()
}

// MARK: - Mutations

@Test func aRenameWhoseAnswerIsLostIsRetriedAsTheSameRequest() async throws {
    let (gateway, socket, initial) = try await authorityWithSession(timeout: .milliseconds(300))
    var sent = initial
    let first = Task { try await gateway.methods.session.title(.init(sessionId: "local-a", title: .value("Plans"))) }
    let lost = try await sent.next("session.mutate")
    #expect(lost.params["operation"] == "rename")
    #expect(lost.params["payload"] == ["title": "Plans"])
    #expect(lost.params["expected_revision"] == 3)
    #expect(lost.params["expected_generation"] == nil)
    await #expect(throws: HermesGatewayError.timeout) { try await first.value }

    let second = Task { try await gateway.methods.session.title(.init(sessionId: "local-a", title: .value("Plans"))) }
    let retry = try await sent.next("session.mutate")
    #expect(retry.params == lost.params)
    await socket.inject(GatewayFrames.result(retry.id, #"{"session_id":"local-a","operation":"rename","revision":4,"title":"Plans"}"#))
    #expect(try await second.value.title == "Plans")

    let third = Task { try await gateway.methods.session.title(.init(sessionId: "local-a", title: .value("Plans"))) }
    let next = try await sent.next("session.mutate")
    #expect(next.params["request_id"] != lost.params["request_id"])
    #expect(next.params["expected_revision"] == 4)
    await socket.inject(refusal(next.id, "revision_conflict"))
    let error = await #expect(throws: HermesGatewayError.self) { try await third.value }
    #expect(error?.refusal?.reason == "revision_conflict")
    #expect(error?.kind == .conflict)

    // The conflict made the revision unknown: the next edit re-reads it first.
    let fourth = Task { try await gateway.methods.session.title(.init(sessionId: "local-a", title: .value("Plans"))) }
    let reread = try await sent.next("session.resume")
    await socket.inject(GatewayFrames.result(reread.id, snapshot("local-a", revision: 8)))
    let fresh = try await sent.next("session.mutate")
    #expect(fresh.params["expected_revision"] == 8)
    await socket.inject(GatewayFrames.result(fresh.id, #"{"session_id":"local-a","operation":"rename","revision":9}"#))
    _ = try await fourth.value
    await gateway.disconnect()
}

@Test func aPickedModelIsAMutationWithItsConfirmation() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    let asked = Task {
        try await gateway.methods.config.set(.init(key: "model", value: "big --provider p --session", sessionId: .value("local-a")))
    }
    let ask = try await sent.next("session.mutate")
    #expect(ask.params["operation"] == "model")
    #expect(ask.params["payload"] == ["model": "big", "provider": "p"])
    #expect(ask.params["expected_generation"] == 7)
    await socket.inject(GatewayFrames.result(ask.id,
        #"{"session_id":"local-a","operation":"model","status":"confirmation_required","confirm":"tok","confirm_message":"Costly"}"#))
    let answer = try await asked.value
    #expect(answer.confirmRequired == true)
    #expect(answer.confirmMessage == "Costly")

    let confirmed = Task {
        try await gateway.methods.config.set(.init(
            key: "model", value: "big --provider p --session", sessionId: .value("local-a"), confirmExpensiveModel: true))
    }
    let resend = try await sent.next("session.mutate")
    #expect(resend.params["payload"] == ["model": "big", "provider": "p", "confirm": "tok"])
    await socket.inject(GatewayFrames.result(resend.id, #"{"session_id":"local-a","operation":"model","revision":4,"model":"big"}"#))
    #expect(try await confirmed.value.confirmRequired == nil)
    await gateway.disconnect()
}

@Test func anUndoRewindsTheLastUserRow() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    let undone = Task { try await gateway.methods.session.undo(.init(sessionId: "local-a")) }
    let resume = try await sent.next("session.resume")
    let rows = #"[{"role":"user","text":"a","row_id":1},{"role":"assistant","text":"b","row_id":2},{"role":"user","text":"c","row_id":3},{"role":"assistant","text":"d","row_id":4}]"#
    await socket.inject(GatewayFrames.result(resume.id, snapshot("local-a", messages: rows)))
    let mutate = try await sent.next("session.mutate")
    #expect(mutate.params["operation"] == "rewind")
    #expect(mutate.params["payload"] == ["target_message_id": 3])
    await socket.inject(GatewayFrames.result(mutate.id, #"{"session_id":"local-a","operation":"rewind","revision":4,"rewound_count":2}"#))
    #expect(try await undone.value.removed == 2)
    await gateway.disconnect()
}

// MARK: - Turns

@Test func aSubmitAttachesItsSessionAndCarriesAnIdentity() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    let submitted = Task {
        try await gateway.methods.prompt.submit(.init(sessionId: "local-n", profile: .value("default"), text: .string("hi"), queued: .value(true)))
    }
    let attach = try await sent.next("session.resume")
    #expect(attach.params["session_id"] == "local-n")
    await socket.inject(GatewayFrames.result(attach.id, snapshot("local-n")))
    let submit = try await sent.next("prompt.submit")
    let submission = try #require(submit.params["submission_id"]?.stringValue)
    #expect(submit.params == ["session_id": "local-n", "profile": "default", "text": "hi", "queued": true,
                              "submission_id": .string(submission)])
    await socket.inject(GatewayFrames.result(submit.id,
        #"{"admission_id":"ad1","ref":{"profile_id":"/home/h","session_id":"local-n"},"sequence":1,"status":"started","outcome":null,"authority_epoch":1,"execution_generation":8}"#))
    #expect(try await submitted.value.status == .streaming)
    await gateway.disconnect()
}

@Test func anEditRewindsThenSubmits() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    let edited = Task {
        try await gateway.methods.prompt.submit(.init(
            sessionId: "local-a", text: .string("again"), truncateBeforeRowId: .value(12), confirmTruncate: .value(true)))
    }
    let rewind = try await sent.next("session.mutate")
    #expect(rewind.params["operation"] == "rewind")
    #expect(rewind.params["payload"] == ["target_message_id": 12])
    await socket.inject(GatewayFrames.result(rewind.id, #"{"session_id":"local-a","operation":"rewind","revision":4,"execution_generation":8}"#))
    let submit = try await sent.next("prompt.submit")
    #expect(submit.params["truncate_before_row_id"] == nil)
    #expect(submit.params["confirm_truncate"] == nil)
    await socket.inject(GatewayFrames.result(submit.id,
        #"{"admission_id":"ad1","ref":{"profile_id":"/home/h","session_id":"local-a"},"sequence":1,"status":"queued","outcome":null,"authority_epoch":1,"execution_generation":null}"#))
    #expect(try await edited.value.status == .queued)
    await gateway.disconnect()
}

@Test func anInterruptPresentsTheCurrentGeneration() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    await socket.inject(authorityEvent("message.start", session: "local-a", seq: 1, generation: 9))
    let interrupted = Task { try await gateway.methods.session.interrupt(.init(sessionId: "local-a")) }
    let interrupt = try await sent.next("session.interrupt")
    #expect(interrupt.params == ["session_id": "local-a", "execution_generation": 9])
    await socket.inject(GatewayFrames.result(interrupt.id,
        #"{"ref":{"profile_id":"/h","session_id":"local-a"},"instance_id":"i","authority_epoch":1,"revision":3,"execution_generation":9,"execution_state":"idle"}"#))
    #expect(try await interrupted.value.status == .interrupted)
    await gateway.disconnect()
}

@Test func aBusyRefusalReadsAsTheLegacyBusyError() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    let moved = Task { try await gateway.methods.session.cwdSet(.init(sessionId: "local-a", cwd: "/x")) }
    let mutate = try await sent.next("session.mutate")
    #expect(mutate.params["operation"] == "workspace")
    await socket.inject(refusal(mutate.id, "session_busy"))
    let error = await #expect(throws: HermesGatewayError.self) { try await moved.value }
    #expect(error?.known == .sessionBusy)
    #expect(error?.kind == .busy)
    await gateway.disconnect()
}

// MARK: - Prompts

@Test func anApprovalEventIsAnsweredWithApprovalRespond() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    await gateway.setServerRequestHandler { request in
        guard case .approval(let params) = request else { throw HermesGatewayError.decoding("Unexpected request") }
        #expect(params.requestId == "a1")
        #expect(params.command == "rm -rf build")
        #expect(ServerRequestContext.current?.id == "a1")
        return .approval(ApprovalResult(choice: .once))
    }
    await socket.inject(authorityEvent("approval.request", session: "local-a", seq: 1, generation: 7, payload:
        #"{"kind":"approval","prompt_id":"a1","execution_generation":7,"command":"rm -rf build","description":"d","choices":["once","deny"]}"#))
    let respond = try await sent.next("approval.respond")
    #expect(respond.params == ["session_id": "local-a", "execution_generation": 7, "prompt_id": "a1", "choice": "once",
                               "profile": "default"])
    await socket.inject(GatewayFrames.result(respond.id, #"{"status":"resolved","prompt_id":"a1"}"#))
    await gateway.disconnect()
}

@Test func aClarifyEventIsOneQuestionAnsweredWithClarifyRespond() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    await gateway.setServerRequestHandler { request in
        guard case .clarify(let params) = request else { throw HermesGatewayError.decoding("Unexpected request") }
        #expect(params.questions.map(\.qid) == ["q1"])
        #expect(params.questions.first?.question == "Which?")
        return .clarify(ClarifyResult(answers: ["q1": "b"]))
    }
    await socket.inject(authorityEvent("clarify.request", session: "local-a", seq: 1, payload:
        #"{"kind":"clarify","prompt_id":"q1","execution_generation":7,"question":"Which?","choices":["a","b"]}"#))
    let respond = try await sent.next("clarify.respond")
    #expect(respond.params == ["session_id": "local-a", "execution_generation": 7, "prompt_id": "q1", "answer": "b",
                               "profile": "default"])
    await socket.inject(GatewayFrames.result(respond.id, #"{"status":"resolved","prompt_id":"q1"}"#))
    await gateway.disconnect()
}

@Test func aSettledPromptStopsItsHandler() async throws {
    let (gateway, socket, _) = try await authorityWithSession()
    let (cancelled, signal) = AsyncStream.makeStream(of: String.self)
    await gateway.setServerRequestHandler { _ in
        await withTaskCancellationHandler {
            try? await Task.sleep(for: .seconds(30))
        } onCancel: {
            signal.yield("cancelled")
        }
        throw CancellationError()
    }
    await socket.inject(authorityEvent("approval.request", session: "local-a", seq: 1, payload:
        #"{"kind":"approval","prompt_id":"a1","execution_generation":7,"choices":["once","deny"]}"#))
    await socket.inject(authorityEvent("approval.settled", session: "local-a", seq: 2, payload:
        #"{"prompt_id":"a1","execution_generation":7}"#))
    var iterator = cancelled.makeAsyncIterator()
    #expect(await iterator.next() == "cancelled")
    await gateway.disconnect()
}

// MARK: - Events and replay

@Test func eachSessionReplayEpochHasItsOwnWatermark() async throws {
    let (gateway, socket, _) = try await authorityWithSession()
    var events = gateway.events().makeAsyncIterator()
    await socket.inject(authorityEvent("message.delta", session: "local-a", seq: 1, payload: #"{"text":"a"}"#))
    await socket.inject(authorityEvent("message.delta", session: "local-a", seq: 2, payload: #"{"text":"b"}"#))
    await socket.inject(authorityEvent("message.delta", session: "local-a", seq: 2, payload: #"{"text":"dup"}"#))
    // The authority re-minted the epoch: numbering restarts and nothing is dropped.
    await socket.inject(authorityEvent("message.delta", session: "local-a", seq: 1, epoch: "e2", payload: #"{"text":"c"}"#))
    var seen: [Int?] = []
    while seen.count < 3, let event = await events.next() {
        if event.type == "message.delta" { seen.append(event.seq) }
    }
    #expect(seen == [1, 2, 1])
    await gateway.disconnect()
}

@Test func aRenameElsewhereAlsoArrivesAsTheLegacyTitleEvent() async throws {
    let (gateway, socket, _) = try await authorityWithSession()
    var events = gateway.events().makeAsyncIterator()
    await socket.inject(authorityEvent("session.updated", session: "local-a", seq: 1, payload:
        #"{"session_id":"local-a","operation":"rename","revision":4,"title":"New"}"#))
    #expect(await events.next()?.type == "session.updated")
    let title = await events.next()
    #expect(title?.type == "session.title")
    guard case .sessionTitle(let payload)? = title?.payload else {
        Issue.record("expected a session.title payload")
        return
    }
    #expect(payload.title == "New")
    await gateway.disconnect()
}

@Test func aReconnectResumesThenReplaysInTheSessionsEpoch() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = gatewayClient(ScriptedGatewayTransport([first, second]))
    var sent = Sent(first)
    try await connectAuthority(first, gateway: gateway, sent: &sent)
    let created = Task { try await gateway.methods.session.create(.init()) }
    let create = try await sent.next("session.create")
    await first.inject(GatewayFrames.result(create.id, snapshot("local-a", epoch: "e1", last: 0)))
    _ = try await created.value
    var events = gateway.events().makeAsyncIterator()
    await first.inject(authorityEvent("message.delta", session: "local-a", seq: 1, payload: #"{"text":"a"}"#))
    #expect(await events.next()?.seq == 1)

    var resent = Sent(second)
    await second.inject(ready(authority: true))
    await first.sever()
    let describe = try await resent.next("runtime.describe")
    await second.inject(GatewayFrames.result(describe.id, describeResult))
    let rebind = try await resent.next("session.resume")
    #expect(rebind.params["session_id"] == "local-a")
    await second.inject(GatewayFrames.result(rebind.id, snapshot("local-a", epoch: "e1", last: 3)))
    let replay = try await resent.next("session.events.since")
    #expect(replay.params == ["session_id": "local-a", "last_sequence": 1, "replay_epoch": "e1"])
    let missed = [2, 3].map { #"{"type":"message.delta","session_id":"local-a","payload":{"text":"x"},"replay_epoch":"e1","seq":\#($0)}"# }
    await second.inject(GatewayFrames.result(replay.id,
        #"{"events":[\#(missed.joined(separator: ","))],"latest_seq":3,"last_sequence":3,"epoch":"e1","replay_epoch":"e1","truncated":false,"snapshot_required":false,"count":2}"#))
    var replayed: [GatewayEvent?] = []
    while replayed.count < 2, let event = await events.next() {
        if event.type == "message.delta" { replayed.append(event) }
    }
    #expect(replayed.map { $0?.seq } == [2, 3])
    #expect(replayed.allSatisfy { $0?.replayed == true })
    await gateway.disconnect()
}

@Test func aReconnectIntoANewEpochReportsTheGap() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = gatewayClient(ScriptedGatewayTransport([first, second]))
    var sent = Sent(first)
    try await connectAuthority(first, gateway: gateway, sent: &sent)
    let created = Task { try await gateway.methods.session.create(.init()) }
    let create = try await sent.next("session.create")
    await first.inject(GatewayFrames.result(create.id, snapshot("local-a", epoch: "e1")))
    _ = try await created.value
    var recoveries = gateway.sessionRecoveries().makeAsyncIterator()

    var resent = Sent(second)
    await second.inject(ready(authority: true))
    await first.sever()
    let describe = try await resent.next("runtime.describe")
    await second.inject(GatewayFrames.result(describe.id, describeResult))
    let rebind = try await resent.next("session.resume")
    await second.inject(GatewayFrames.result(rebind.id, snapshot("local-a", epoch: "e2", last: 5)))
    #expect(await recoveries.next() == .replayTruncated(sessionID: "local-a"))
    await gateway.disconnect()
}

@Test func aSessionTheAuthorityNoLongerFindsIsUnavailable() async throws {
    let first = ScriptedGatewaySocket()
    let second = ScriptedGatewaySocket()
    let gateway = gatewayClient(ScriptedGatewayTransport([first, second]))
    var sent = Sent(first)
    try await connectAuthority(first, gateway: gateway, sent: &sent)
    let created = Task { try await gateway.methods.session.create(.init()) }
    let create = try await sent.next("session.create")
    await first.inject(GatewayFrames.result(create.id, snapshot("local-a")))
    _ = try await created.value
    var recoveries = gateway.sessionRecoveries().makeAsyncIterator()

    var resent = Sent(second)
    await second.inject(ready(authority: true))
    await first.sever()
    let describe = try await resent.next("runtime.describe")
    await second.inject(GatewayFrames.result(describe.id, describeResult))
    let rebind = try await resent.next("session.resume")
    await second.inject(refusal(rebind.id, "not_found"))
    #expect(await recoveries.next() == .unavailable(sessionID: "local-a", reason: "not_found"))
    await gateway.disconnect()
}

@Test func aReplayGapResumesAndReplaysFromTheWatermark() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial
    var events = gateway.events().makeAsyncIterator()
    await socket.inject(authorityEvent("message.delta", session: "local-a", seq: 1, payload: #"{"text":"a"}"#))
    #expect(await events.next()?.seq == 1)
    await socket.inject(authorityEvent("session.replay_gap", session: "local-a", seq: 2, payload: #"{"replay_epoch":"e1","latest_seq":9}"#))
    let resume = try await sent.next("session.resume")
    await socket.inject(GatewayFrames.result(resume.id, snapshot("local-a", last: 9)))
    let replay = try await sent.next("session.events.since")
    #expect(replay.params["last_sequence"] == 2)
    #expect(replay.params["replay_epoch"] == "e1")
    await socket.inject(GatewayFrames.result(replay.id,
        #"{"events":[],"latest_seq":9,"last_sequence":9,"epoch":"e1","replay_epoch":"e1","truncated":true,"snapshot_required":true,"count":0}"#))
    var recoveries = gateway.sessionRecoveries().makeAsyncIterator()
    #expect(await recoveries.next() == .replayTruncated(sessionID: "local-a"))
    await gateway.disconnect()
}

// MARK: - Small translations

@Test func smallerCallsAreFittedToTheAuthoritysContracts() async throws {
    let (gateway, socket, initial) = try await authorityWithSession()
    var sent = initial

    let catalog = Task { try await gateway.methods.commands.catalog(.init(sessionId: .value("local-a"), profile: .value("default"))) }
    let catalogCall = try await sent.next("commands.catalog")
    #expect(catalogCall.params == ["profile": "default"])
    await socket.inject(GatewayFrames.result(catalogCall.id, #"{"pairs":[],"sub":{},"canon":{},"categories":[]}"#))
    _ = try? await catalog.value

    let reload = Task { try await gateway.methods.reload.mcp(.init()) }
    let reloadCall = try await sent.next("reload.mcp")
    #expect(reloadCall.params == ["confirm": true])
    await socket.inject(GatewayFrames.result(reloadCall.id, #"{"status":"reloaded"}"#))
    _ = try await reload.value

    let list = Task { try await gateway.methods.session.list(.init(limit: .value(Int(Int32.max)))) }
    let listCall = try await sent.next("session.list")
    #expect(listCall.params == ["limit": 200])
    await socket.inject(GatewayFrames.result(listCall.id, #"{"sessions":[],"scope":"live"}"#))
    _ = try await list.value

    let fast = Task { try await gateway.methods.config.set(.init(key: "fast", value: "fast", sessionId: .value("local-a"), scope: .value("session"))) }
    let fastCall = try await sent.next("config.set")
    #expect(fastCall.params == ["key": "fast", "value": "fast", "session_id": "local-a"])
    await socket.inject(GatewayFrames.result(fastCall.id, #"{"key":"fast","value":"fast"}"#))
    _ = try await fast.value

    let usage = Task { try await gateway.methods.session.usage(.init(sessionId: "local-a")) }
    let statsCall = try await sent.next("session.stats")
    #expect(statsCall.params == ["session_id": "local-a"])
    await socket.inject(GatewayFrames.result(statsCall.id,
        #"{"input_tokens":10,"output_tokens":5,"total_tokens":15,"api_calls":2,"context_tokens":50,"context_length":200,"model":"m","provider":"p"}"#))
    let stats = try await usage.value
    #expect(stats.total == 15)
    #expect(stats.calls == 2)
    #expect(stats.contextPercent == 25)

    let pdf = await #expect(throws: HermesGatewayError.self) {
        try await gateway.methods.pdf.attach(.init(sessionId: "local-a", path: .value("/x.pdf")))
    }
    #expect(pdf?.refusal?.reason == GatewayRefusal.unsupportedOnAuthority)
    #expect(pdf?.known == .unknownMethod)
    await gateway.disconnect()
}
