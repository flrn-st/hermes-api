import Foundation
@testable import HermesAPI
import os
import Testing

// The session authority's translation state, without a socket: Desktop's retry rules for mutations, the
// create mapping, and what events and snapshots teach it.

private func counter() -> @Sendable () -> String {
    let value = OSAllocatedUnfairLock(initialState: 0)
    return { "id-\(value.withLock { count in count += 1; return count })" }
}

/// A snapshot at `revision` / `generation`, subscribed.
private func snapshot(_ id: String, revision: Int = 3, generation: Int = 7, epoch: String = "e1") -> CanonicalSnapshot {
    CanonicalSnapshot(
        sessionId: id, storedSessionId: id, messages: [], messageCount: 0, running: false, authorityEpoch: 1,
        replayEpoch: epoch, lastSequence: 0, subscriptionId: "sub-\(id)", revision: revision,
        executionGeneration: generation, pending: [], prompts: [], info: CanonicalSnapshotInfo(desktopProtocol: .hermesGatewayV1)
    )
}

private func attached(_ owner: AuthorityOwner, revision: Int = 3, generation: Int = 7) -> AuthorityProtocol {
    var state = AuthorityProtocol(newID: counter())
    _ = state.adoptSnapshot(snapshot(owner.sessionID, revision: revision, generation: generation), prompts: [],
                            owner: owner, route: owner.profile == "default" ? nil : owner.profile)
    return state
}

private func receipt(_ owner: AuthorityOwner, revision: Int?, status: String? = nil, confirm: String? = nil) -> SessionMutateResult {
    var extra: [String: JSONValue] = [:]
    if let status { extra["status"] = .string(status) }
    if let confirm { extra["confirm"] = .string(confirm) }
    return SessionMutateResult(sessionId: owner.sessionID, operation: "model", revision: revision, additionalProperties: extra)
}

private let owner = AuthorityOwner(sessionID: "local-a", profile: nil)

@Test func aMutationWhoseAnswerWasLostIsRetriedAsTheSameRequest() throws {
    var state = attached(owner)
    let first = try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    #expect(first.expectedRevision == 3)
    #expect(first.expectedGeneration == nil, "rename is not fenced by the generation")
    state.mutationRefused(first, reason: nil)
    let retry = try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    #expect(retry == first)
}

@Test func anotherEditOfTheSessionRetiresTheRetainedOne() throws {
    var state = attached(owner)
    let rename = try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    _ = try state.mutation(owner, operation: "workspace", payload: ["cwd": "/w"], fenced: true)
    let again = try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    #expect(again.requestID != rename.requestID)
}

@Test func anEditOfAnotherSessionKeepsTheRetainedOne() throws {
    let other = AuthorityOwner(sessionID: "local-b", profile: nil)
    var state = attached(owner)
    _ = state.adoptSnapshot(snapshot("local-b"), prompts: [], owner: other, route: nil)
    let rename = try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    _ = try state.mutation(other, operation: "rename", payload: ["title": "B"], fenced: false)
    #expect(try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false) == rename)
}

@Test func anAcknowledgedRevisionRetiresOlderValueEditsButNotABranch() throws {
    var state = attached(owner)
    let branch = try state.mutation(owner, operation: "branch", payload: [:], fenced: true)
    state.mutationRefused(branch, reason: nil)
    // Another viewer's rename lands at revision 4 (a session.updated event).
    let effect = state.event(type: "session.updated", sessionID: owner.sessionID,
                             frame: ["payload": ["revision": 4, "operation": "rename", "title": "X"]])
    #expect(effect == .none)
    #expect(state.session(owner)?.revision == 4)
    #expect(try state.mutation(owner, operation: "branch", payload: [:], fenced: true) == branch,
            "a branch retried with a new id would run twice")

    var values = attached(owner)
    let rename = try values.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    values.mutationRefused(rename, reason: nil)
    _ = values.event(type: "session.updated", sessionID: owner.sessionID, frame: ["payload": ["revision": 4]])
    let fresh = try values.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    #expect(fresh.requestID != rename.requestID)
    #expect(fresh.expectedRevision == 4)
}

@Test func aReceiptNeverMovesTheRevisionBack() throws {
    var state = attached(owner, revision: 9)
    let rename = try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    state.mutationAnswered(rename, receipt: receipt(owner, revision: 5))
    #expect(state.session(owner)?.revision == 9, "an exact retry's receipt replays its original revision")
    #expect(state.mutations.isEmpty)
}

@Test func aRevisionConflictRetiresTheEditAndForgetsTheRevision() throws {
    var state = attached(owner)
    let rename = try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    state.mutationRefused(rename, reason: "revision_conflict")
    #expect(state.mutations.isEmpty)
    #expect(throws: AuthorityPrerequisite.revision) {
        try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    }
}

@Test func aBusyRefusalRetiresOnlyARewindOrAMove() throws {
    var state = attached(owner)
    let rewind = try state.mutation(owner, operation: "rewind", payload: ["target_message_id": 4], fenced: true)
    state.mutationRefused(rewind, reason: "session_busy")
    #expect(state.mutations.isEmpty)
    let compress = try state.mutation(owner, operation: "compress", payload: [:], fenced: true)
    state.mutationRefused(compress, reason: "session_busy")
    #expect(try state.mutation(owner, operation: "compress", payload: [:], fenced: true) == compress)
}

@Test func aStaleGenerationRetiresTheEditAndForgetsTheGeneration() throws {
    var state = attached(owner)
    let move = try state.mutation(owner, operation: "workspace", payload: ["cwd": "/w"], fenced: true)
    #expect(move.expectedGeneration == 7)
    state.mutationRefused(move, reason: "stale_generation")
    #expect(throws: AuthorityPrerequisite.generation) {
        try state.mutation(owner, operation: "workspace", payload: ["cwd": "/w"], fenced: true)
    }
}

@Test func aMutationNeedsAKnownRevision() {
    var state = AuthorityProtocol(newID: counter())
    #expect(throws: AuthorityPrerequisite.revision) {
        try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    }
}

@Test func aSubmitRetiresTheSessionsRetainedEdits() throws {
    var state = attached(owner)
    let rename = try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false)
    state.mutationRefused(rename, reason: nil)
    state.retireMutations(of: owner)
    #expect(try state.mutation(owner, operation: "rename", payload: ["title": "A"], fenced: false).requestID != rename.requestID)
}

@Test func aGuardedModelIsConfirmedWithTheAuthoritysToken() throws {
    var state = attached(owner)
    let target: [String: JSONValue] = ["model": "big", "provider": "p"]
    #expect(state.modelPayload(owner, target: target, confirmed: true) == target, "no token before the authority asks")
    let ask = try state.mutation(owner, operation: "model", payload: target, fenced: true)
    state.mutationAnswered(ask, receipt: receipt(owner, revision: nil, status: "confirmation_required", confirm: "tok"))
    #expect(state.modelPayload(owner, target: target, confirmed: false) == target, "never confirmed unasked")
    let confirmed = state.modelPayload(owner, target: target, confirmed: true)
    #expect(confirmed["confirm"] == "tok")
    let resend = try state.mutation(owner, operation: "model", payload: confirmed, fenced: true)
    #expect(resend.requestID != ask.requestID)
    // The confirmed request's answer is lost: a retry of the same target re-sends the same token and id.
    state.mutationRefused(resend, reason: nil)
    let retried = state.modelPayload(owner, target: target, confirmed: false)
    #expect(retried == confirmed)
    #expect(try state.mutation(owner, operation: "model", payload: retried, fenced: true) == resend)
    // Answered: the token is spent.
    state.mutationAnswered(resend, receipt: receipt(owner, revision: 4))
    #expect(state.modelPayload(owner, target: target, confirmed: true) == target)
}

@Test func theGenerationNeverMovesBackwards() {
    var state = attached(owner, generation: 7)
    _ = state.event(type: "message.start", sessionID: owner.sessionID, frame: ["execution_generation": 8])
    #expect(state.session(owner)?.generation == 8)
    let stale = state.event(type: "approval.request", sessionID: owner.sessionID, frame: [
        "execution_generation": 6, "payload": ["prompt_id": "p1", "execution_generation": 6, "choices": ["once"]],
    ])
    #expect(stale == .none, "a frame of an older execution changes nothing")
    #expect(state.session(owner)?.generation == 8)
    #expect(state.prompts.isEmpty)
}

@Test func promptsAreKeptUntilTheySettle() {
    var state = attached(owner)
    let effect = state.event(type: "clarify.request", sessionID: owner.sessionID, frame: [
        "payload": ["kind": "clarify", "prompt_id": "q1", "execution_generation": 7, "question": "Which?", "choices": ["a", "b"]],
    ])
    guard case .prompt(let prompt) = effect else {
        Issue.record("expected a prompt, got \(effect)")
        return
    }
    #expect(prompt.kind == .clarify)
    #expect(prompt.serverRequestParams == [
        "session_id": "local-a", "questions": [["qid": "q1", "question": "Which?", "choices": ["a", "b"]]],
    ])
    #expect(state.event(type: "clarify.request", sessionID: owner.sessionID, frame: [
        "payload": ["prompt_id": "q1", "execution_generation": 7],
    ]) == .none, "delivered once")
    #expect(state.event(type: "clarify.settled", sessionID: owner.sessionID, frame: [
        "payload": ["prompt_id": "q1", "execution_generation": 7],
    ]) == .settled("q1"))
    #expect(state.prompts.isEmpty)
}

@Test func anApprovalPromptBecomesTheLegacyRequest() {
    let payload: [String: JSONValue] = [
        "kind": "approval", "prompt_id": "a1", "execution_generation": 2, "command": "rm -rf build",
        "description": "Delete build", "choices": ["once", "session", "deny"],
    ]
    let prompt = AuthorityPrompt(kind: "approval", payload: payload, owner: owner, frameGeneration: nil)
    #expect(prompt?.serverRequestParams == [
        "session_id": "local-a", "request_id": "a1", "command": "rm -rf build", "description": "Delete build",
        "choices": ["once", "session", "deny"], "allow_permanent": false, "allow_session": true,
    ])
}

@Test func aSnapshotDropsPromptsItNoLongerLists() {
    var state = attached(owner)
    _ = state.event(type: "approval.request", sessionID: owner.sessionID, frame: [
        "payload": ["prompt_id": "a1", "execution_generation": 7, "choices": ["once"]],
    ])
    let open = state.adoptSnapshot(snapshot(owner.sessionID), prompts: [
        ["kind": "approval", "prompt_id": "a2", "execution_generation": 7, "choices": ["once"]],
    ], owner: owner, route: nil)
    #expect(open.map(\.promptID) == ["a2"])
    #expect(Set(state.prompts.keys) == ["a2"])
}

@Test func aLegacyCreateBecomesTheCanonicalOne() {
    var state = AuthorityProtocol(newID: counter())
    let allowed = AuthorityProtocol.defaultCreateParameters
    let params: [String: JSONValue] = [
        "profile": "work", "cols": 100, "source": "ios", "provider": "p", "model": "m", "reasoning_effort": "high",
        "fast": true, "cwd": "/w", "title": "T", "hidden": false, "close_on_disconnect": false,
    ]
    let prepared = state.prepareCreate(params, allowed: allowed)
    #expect(prepared == [
        "profile": "work", "source": "gui", "provider": "p", "model": "m", "reasoning": "high",
        "service_tier": "priority", "cwd": "/w", "title": "T", "hidden": false, "request_id": "id-1",
    ])
    #expect(state.prepareCreate(params, allowed: allowed)["request_id"] == "id-1", "a retry is the same create")
    #expect(state.prepareCreate(params.merging(["fast": false]) { $1 }, allowed: allowed)["service_tier"] == "normal")
    state.createAnswered("id-1")
    #expect(state.prepareCreate(params, allowed: allowed)["request_id"] != "id-1")
}

@Test func aForkSeedIsMatchedToTheParentsStoredRows() {
    let rows: [JSONValue] = [
        ["role": "user", "text": "hi", "row_id": 11],
        ["role": "tool", "text": "ls", "row_id": 12],
        ["role": "assistant", "content": "hello", "row_id": 13],
        ["role": "user", "content": "more", "row_id": 14],
    ]
    let seed: [JSONValue] = [["role": "user", "content": "hi"], ["role": "assistant", "content": "hello "]]
    #expect(HermesGateway.lastStoredRow(of: seed, in: rows) == 13)
    #expect(HermesGateway.lastStoredRow(of: [["role": "user", "content": "other"]], in: rows) == nil)
}

@Test func pickerAndSlashDirectivesAreRecognised() {
    #expect(HermesGateway.pickerModel("gpt-5 --provider openai --session")! == ("gpt-5", "openai"))
    #expect(HermesGateway.pickerModel("gpt-5 --global") == nil)
    #expect(HermesGateway.slashMutation("/model gpt-5")?.operation == "model")
    #expect(HermesGateway.slashMutation("/model gpt-5 --global") == nil)
    #expect(HermesGateway.slashMutation("/branch Side quest")?.payload == ["title": "Side quest"])
    #expect(HermesGateway.slashMutation("/compress")?.payload == [:])
    #expect(HermesGateway.slashMutation("/help") == nil)
}

@Test func refusalsAreNamedByTheirReason() {
    func refusal(_ reason: String, code: Int = 4001) -> HermesGatewayError {
        .rpc(code: code, message: reason, data: ["reason": .string(reason)])
    }
    #expect(refusal("session_busy").known == .sessionBusy)
    #expect(refusal("session_busy").kind == .busy)
    #expect(refusal("not_found").known == .sessionNotFound)
    #expect(refusal("invalid_params").known == .invalidParams)
    #expect(refusal("stale_generation").known == nil)
    #expect(refusal("stale_generation").kind == .conflict)
    #expect(refusal("revision_conflict").kind == .conflict)
    #expect(refusal("permission_denied").kind == .forbidden)
    #expect(refusal("storage_unavailable", code: 5001).known == .storageUnavailable)
    let detailed = HermesGatewayError.rpc(code: 4001, message: "invalid_params", data: [
        "reason": "invalid_params", "field": "hidden", "detail": "must be a boolean", "fields": ["source", "cols"],
    ])
    #expect(detailed.refusal == GatewayRefusal(reason: "invalid_params", fields: ["source", "cols"], field: "hidden",
                                               detail: "must be a boolean"))
    let unsupported = HermesGatewayError.unsupportedOnAuthority("pdf.attach", "not served")
    #expect(unsupported.known == .unknownMethod)
    #expect(unsupported.kind == .unsupported)
    // A legacy error keeps its catalog reading, data or not.
    let legacy = HermesGatewayError.rpc(code: 4009, message: "session busy", data: nil)
    #expect(legacy.refusal == nil)
    #expect(legacy.known == GatewayErrorCatalog.known(code: 4009, message: "session busy"))
}
