import Foundation
import HermesAPI
import HermesAPITesting
import Testing

// The refusal a standalone v0.22.1 dashboard answers while a gateway runtime owns the profile, as recorded live.
private let ownedDetail = "Exclusive maintenance refused: Gateway runtime already owns profile "
    + "/Users/me/.hermes/profiles/work. Drain and stop the gateway, then retry."
private let owned = #"{"detail":"\#(ownedDetail)"}"#

private func snapshot(_ id: String, revision: Int, generation: Int = 0) -> RESTResponse {
    .json(200, #"{"session_id":"\#(id)","exists":true,"runtime_revision":\#(revision),"runtime_generation":\#(generation)}"#)
}

private func body(_ request: RESTRequest) throws -> [String: JSONValue] {
    let data = try #require(request.body)
    let value = try JSONValue(jsonData: data)
    return try #require(value.objectValue)
}

/// A dashboard whose mutation snapshot reads `revision` and whose mutations answer `mutation`.
private func dashboard(
    revision: Int = 4, generation: Int = 2,
    mutation: @escaping @Sendable (RESTRequest) async throws -> RESTResponse
) -> ScriptedRESTCaller {
    ScriptedRESTCaller { request in
        if request.path.hasSuffix("/mutation-snapshot") { return snapshot("s1", revision: revision, generation: generation) }
        return try await mutation(request)
    }
}

// MARK: - Refusals

@Test func readsTheRuntimeOwnershipRefusal() {
    let error = HermesRESTError.http(status: 409, body: owned)
    #expect(error.sessionMutationRefusal == .runtimeOwnsProfile(home: "/Users/me/.hermes/profiles/work"))
    #expect(HermesRESTError.http(status: 409, body: #"{"detail":"mutation_identity_required"}"#)
        .sessionMutationRefusal == .identityRequired)
    #expect(HermesRESTError.http(status: 409, body: #"{"detail":"revision_conflict"}"#)
        .sessionMutationRefusal == .revisionConflict)
    #expect(HermesRESTError.http(status: 409, body: #"{"detail":"session_busy"}"#).sessionMutationRefusal == .sessionBusy)
    #expect(HermesRESTError.http(status: 403, body: #"{"detail":"profile_mismatch"}"#)
        .sessionMutationRefusal == .other("profile_mismatch"))
    #expect(HermesRESTError.http(status: 404, body: #"{"detail":"Session not found"}"#).sessionMutationRefusal == nil)
    #expect(HermesRESTError.http(status: 500, body: #"{"detail":"revision_conflict"}"#).sessionMutationRefusal == nil)
    #expect(HermesRESTError.timeout.sessionMutationRefusal == nil)
}

// MARK: - REST

@Test func sendsAFencedPatchWithTheSnapshotRevision() async throws {
    let rest = dashboard { _ in .json(200, #"{"ok":true,"session_id":"s1","revision":5,"operation":"sidebar","pinned":true}"#) }
    let receipt = try await HermesSessionEditor(rest: rest).setPinned(true, for: "s1", profile: "work")
    #expect(receipt == SessionEditReceipt(sessionId: "s1", route: .rest, revision: 5))
    let requests = await rest.requests
    #expect(requests.map { "\($0.method) \($0.path)" } == [
        "GET /api/sessions/s1/mutation-snapshot", "PATCH /api/sessions/s1"])
    #expect(requests[0].query == ["profile": "work"])
    let sent = try body(requests[1])
    #expect(sent["expected_revision"] == .integer(4))
    #expect(sent["pinned"] == .boolean(true))
    #expect(sent["profile"] == .string("work"))
    #expect(sent["request_id"]?.stringValue?.isEmpty == false)
    #expect(sent["title"] == nil)
}

@Test func deletesWithTheSnapshotRevisionAndGeneration() async throws {
    let rest = dashboard { _ in .json(200, #"{"ok":true,"already_absent":true}"#) }
    let receipt = try await HermesSessionEditor(rest: rest).delete("s1")
    #expect(receipt == SessionEditReceipt(sessionId: "s1", route: .rest, revision: nil, alreadyAbsent: true))
    let delete = try #require(await rest.requests.last)
    #expect(delete.method == "DELETE")
    #expect(delete.query["expected_revision"] == "4")
    #expect(delete.query["expected_generation"] == "2")
    #expect(delete.query["request_id"]?.isEmpty == false)
    #expect(delete.query["profile"] == nil)
}

@Test func resendsTheSameRequestWhenTheAnswerWasLost() async throws {
    let attempts = Counter()
    let rest = dashboard { _ in
        if await attempts.next() == 1 { throw HermesRESTError.timeout }
        return .json(200, #"{"ok":true,"session_id":"s1","revision":5,"title":"Plans"}"#)
    }
    _ = try await HermesSessionEditor(rest: rest).rename("s1", to: "Plans")
    let patches = await rest.requests.filter { $0.method == "PATCH" }
    #expect(patches.count == 2)
    #expect(try body(patches[0])["request_id"] == body(patches[1])["request_id"])
    #expect(await rest.requests.filter { $0.method == "GET" }.count == 1)
}

@Test func readsTheSnapshotAgainAfterARevisionConflict() async throws {
    let reads = Counter()
    let rest = ScriptedRESTCaller { request in
        if request.path.hasSuffix("/mutation-snapshot") { return snapshot("s1", revision: await reads.next() == 1 ? 4 : 7) }
        let sent = try body(request)
        if sent["expected_revision"] == .integer(4) { return .json(409, #"{"detail":"revision_conflict"}"#) }
        return .json(200, #"{"ok":true,"session_id":"s1","revision":8,"archived":true}"#)
    }
    let receipt = try await HermesSessionEditor(rest: rest).setArchived(true, for: "s1")
    #expect(receipt.revision == 8)
    let patches = await rest.requests.filter { $0.method == "PATCH" }
    #expect(patches.count == 2)
    #expect(try body(patches[0])["request_id"] != body(patches[1])["request_id"])
}

@Test func otherRefusalsReachTheCaller() async throws {
    let rest = dashboard { _ in .json(409, #"{"detail":"session_busy"}"#) }
    await #expect(throws: HermesRESTError.http(status: 409, body: #"{"detail":"session_busy"}"#)) {
        try await HermesSessionEditor(rest: rest).setUnread(true, for: "s1")
    }
}

// MARK: - A runtime owns the profile

@Test func withoutAGatewayAnOwnedProfileIsReported() async throws {
    let rest = dashboard { _ in .json(409, owned) }
    await #expect(throws: HermesSessionEditError.runtimeOwnsProfile(
        home: "/Users/me/.hermes/profiles/work", edit: .rename("Plans"))) {
        try await HermesSessionEditor(rest: rest).rename("s1", to: "Plans", profile: "work")
    }
}

@Test func anAuthorityAppliesTheEditRoutedToTheProfile() async throws {
    let rest = dashboard { _ in .json(409, owned) }
    let gateway = FakeGateway { method, params in
        #expect(method == "session.mutate")
        return .object(["session_id": params["session_id"] ?? .null, "operation": params["operation"] ?? .null,
                        "revision": .integer(5)])
    }
    await gateway.setSessionAuthority(true)
    let editor = HermesSessionEditor(rest: rest, gateway: gateway)

    let pinned = try await editor.setPinned(true, for: "s1", profile: "work")
    #expect(pinned == SessionEditReceipt(sessionId: "s1", route: .authority, revision: 5))
    _ = try await editor.delete("s1", profile: "work")

    let calls = await gateway.calls
    #expect(calls.count == 2)
    let sidebar = try #require(calls[0].params.objectValue)
    #expect(sidebar["profile"] == .string("work"))
    #expect(sidebar["operation"] == .string("sidebar"))
    #expect(sidebar["payload"] == .object(["pinned": .boolean(true)]))
    #expect(sidebar["expected_revision"] == .integer(4))
    #expect(sidebar["expected_generation"] == nil)
    let delete = try #require(calls[1].params.objectValue)
    #expect(delete["operation"] == .string("delete"))
    #expect(delete["payload"] == .object([:]))
    #expect(delete["expected_generation"] == .integer(2))
    // Hermes' REST receipt never ran: both refusals came from the dashboard, nothing was written there.
    #expect(await rest.requests.filter { $0.method != "GET" }.count == 2)
}

@Test func anAuthorityRevisionConflictReadsTheSnapshotAgain() async throws {
    let reads = Counter()
    let rest = ScriptedRESTCaller { request in
        if request.path.hasSuffix("/mutation-snapshot") { return snapshot("s1", revision: await reads.next() == 1 ? 4 : 9) }
        return .json(409, owned)
    }
    let gateway = FakeGateway { _, params in
        if params["expected_revision"] == .integer(4) {
            throw HermesGatewayError.rpc(code: 4001, message: "revision_conflict",
                                         data: .object(["reason": .string("revision_conflict")]))
        }
        return .object(["session_id": .string("s1"), "operation": .string("sidebar"), "revision": .integer(10)])
    }
    await gateway.setSessionAuthority(true)
    let receipt = try await HermesSessionEditor(rest: rest, gateway: gateway).setHidden(true, for: "s1")
    #expect(receipt.revision == 10)
    #expect(await gateway.calls.map { $0.params["expected_revision"] } == [.integer(4), .integer(9)])
}

@Test func theDashboardSocketAppliesWhatItsLegacyWritersCan() async throws {
    let rest = dashboard { _ in .json(409, owned) }
    let gateway = FakeGateway { method, params in
        switch method {
        case "session.archive":
            return .object(["archived": params["archived"] ?? .null, "session_key": params["session_id"] ?? .null])
        case "session.set_hidden":
            return .object(["hidden": params["hidden"] ?? .null, "session_key": params["session_id"] ?? .null])
        case "session.delete":
            return .object(["deleted": params["session_id"] ?? .null])
        case "session.title":
            return .object(["title": params["title"] ?? .null, "pending": .boolean(false)])
        default:
            throw HermesGatewayError.rpc(code: -32601, message: "Method not found: \(method)", data: nil)
        }
    }
    await gateway.setLiveSession("rt-9", storedSessionID: "s1", profile: "work")
    let editor = HermesSessionEditor(rest: rest, gateway: gateway)

    #expect(try await editor.setArchived(true, for: "s1", profile: "work").route == .legacyGateway)
    #expect(try await editor.setHidden(false, for: "s1", profile: "work").revision == nil)
    #expect(try await editor.rename("s1", to: "Plans", profile: "work").sessionId == "s1")
    #expect(try await editor.delete("s1", profile: "work").sessionId == "s1")
    await #expect(throws: HermesSessionEditError.runtimeOwnsProfile(
        home: "/Users/me/.hermes/profiles/work", edit: .setPinned(true))) {
        try await editor.setPinned(true, for: "s1", profile: "work")
    }
    await #expect(throws: HermesSessionEditError.runtimeOwnsProfile(
        home: "/Users/me/.hermes/profiles/work", edit: .rename("Elsewhere"))) {
        try await editor.rename("s2", to: "Elsewhere", profile: "work")
    }

    let calls = await gateway.calls
    #expect(calls.map(\.method) == ["session.archive", "session.set_hidden", "session.title", "session.delete"])
    #expect(calls[0].params["profile"] == .string("work"))
    #expect(calls[2].params == .object(["session_id": .string("rt-9"), "title": .string("Plans")]))
}

// MARK: - Profile routing

@Test func canonicalCallsCarryTheProfileTheyAreRoutedTo() async throws {
    let gateway = FakeGateway { _, params in
        .object(["session_id": params["session_id"] ?? .null, "operation": .string("rename")])
    }
    let params = SessionMutateParams(sessionId: "s1", requestId: "r1", expectedRevision: 3, operation: "rename",
                                     payload: ["title": .string("Plans")])
    _ = try await gateway.canonical(profile: "work").session.mutate(params)
    _ = try await gateway.canonical(profile: nil).session.mutate(params)
    _ = try await gateway.canonical(profile: "").session.mutate(params)
    _ = try await CanonicalMethodCatalog(caller: gateway, profile: "home").session.mutate(params)
    let calls = await gateway.calls
    #expect(calls[0].params["profile"] == .string("work"))
    #expect(calls[0].params["expected_revision"] == .integer(3))
    #expect(calls[1].params["profile"] == nil)
    #expect(calls[2].params["profile"] == nil)
    #expect(calls[3].method == "session.mutate")
    #expect(calls[3].params["profile"] == .string("home"))
}

@Test func aCallThatNamesItsProfileKeepsIt() async throws {
    let caller = ScriptedGatewayCaller { _, params in .object(["echo": params]) }
    let routed = ProfileRoutedGatewayCaller(caller, profile: "work")
    _ = try await routed.call("session.list", params: JSONValue.object(["profile": .string("home")]), as: JSONValue.self)
    _ = try await routed.call("session.list", params: JSONValue.object(["profile": .string("")]), as: JSONValue.self)
    let calls = await caller.calls
    #expect(calls[0].params["profile"] == .string("home"))
    #expect(calls[1].params["profile"] == .string("work"))
    await #expect(throws: HermesGatewayError.self) {
        _ = try await routed.call("ping", params: [1, 2], as: JSONValue.self)
    }
}

private actor Counter {
    private var value = 0
    func next() -> Int {
        value += 1
        return value
    }
}
