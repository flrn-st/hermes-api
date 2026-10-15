import Foundation

/// A session as the authority keys it: the profile the calls named (`default` when none) and its id.
struct AuthorityOwner: Hashable, Sendable, CustomStringConvertible {
    let profile: String
    let sessionID: String

    init(sessionID: String, profile: String?) {
        self.sessionID = sessionID
        if let profile, !profile.isEmpty { self.profile = profile } else { self.profile = "default" }
    }

    var description: String { "\(profile)/\(sessionID)" }
}

/// What the runtime knows of one authority session.
struct AuthoritySession: Sendable, Equatable {
    /// The `profile` the app's calls named, sent again on the calls the runtime makes for it.
    var route: String?
    /// The compare-and-swap value the next mutation presents; `nil` until a snapshot or event says.
    var revision: Int?
    /// The execution identity turn controls and fenced mutations present. It never moves backwards.
    var generation: Int?
    /// This socket's subscription; `nil` until the session is attached over the current connection.
    var subscription: String?
}

/// A `session.mutate` request kept while its outcome is unknown, so a retry is the exact same request.
struct RetainedMutation: Sendable, Equatable {
    let owner: AuthorityOwner
    let route: String?
    let requestID: String
    let operation: String
    let payload: [String: JSONValue]
    let expectedRevision: Int
    let expectedGeneration: Int?

    var params: [String: JSONValue] {
        var params: [String: JSONValue] = [
            "session_id": .string(owner.sessionID), "request_id": .string(requestID),
            "expected_revision": .integer(expectedRevision), "operation": .string(operation),
            "payload": .object(payload),
        ]
        if let expectedGeneration { params["expected_generation"] = .integer(expectedGeneration) }
        if let route { params["profile"] = .string(route) }
        return params
    }
}

/// An approval or clarify prompt the authority published, kept until it settles.
struct AuthorityPrompt: Sendable, Equatable {
    enum Kind: String, Sendable { case approval, clarify }

    let kind: Kind
    let promptID: String
    let owner: AuthorityOwner
    let generation: Int
    let payload: [String: JSONValue]

    init?(kind rawKind: String, payload: [String: JSONValue], owner: AuthorityOwner, frameGeneration: Int?) {
        guard let kind = Kind(rawValue: rawKind), let promptID = payload["prompt_id"]?.stringValue,
              let generation = payload["execution_generation"]?.integerValue ?? frameGeneration else { return nil }
        self.kind = kind
        self.promptID = promptID
        self.owner = owner
        self.generation = generation
        self.payload = payload
    }

    /// The legacy server request the app's handler already answers: `approval` keyed by the prompt id, or
    /// `clarify` with its one question (`qid` = the prompt id).
    var serverRequestParams: JSONValue {
        let choices = payload["choices"]?.arrayValue ?? []
        var params: [String: JSONValue] = ["session_id": .string(owner.sessionID)]
        switch kind {
        case .approval:
            params["request_id"] = .string(promptID)
            params["choices"] = .array(choices)
            params["allow_permanent"] = .boolean(choices.contains(.string("always")))
            params["allow_session"] = .boolean(choices.contains(.string("session")))
            for key in ["command", "description", "edit", "timeout_s"] { params[key] = payload[key] }
        case .clarify:
            // One question per prompt; its prompt id doubles as the question id.
            var question: [String: JSONValue] = [
                "qid": .string(promptID), "question": payload["question"] ?? .string(""), "choices": .array(choices),
            ]
            question["multi_select"] = payload["multi_select"]
            params["questions"] = .array([.object(question.filter { !$0.value.isNull })])
            params["timeout_s"] = payload["timeout_s"]
        }
        return .object(params.filter { !$0.value.isNull })
    }
}

/// Which prerequisite of a call the runtime does not know yet; attaching the session learns it.
enum AuthorityPrerequisite: Error, Equatable {
    case revision
    case generation
}

/// The translation state for one gateway, mirroring Hermes Desktop's `CanonicalDesktopProtocol`
/// (`apps/desktop/src/api/canonical-protocol.ts`): revisions, execution generations, retained mutations and
/// creates, open prompts and model confirmation tokens. It is synchronous; `HermesGateway` sends what it
/// prepares and reports back what Hermes answered.
struct AuthorityProtocol: Sendable {
    private(set) var sessions: [AuthorityOwner: AuthoritySession] = [:]
    /// The replay epoch each session's watermark belongs to.
    private(set) var epochs: [String: String] = [:]
    private(set) var prompts: [String: AuthorityPrompt] = [:]
    private(set) var mutations: [MutationKey: RetainedMutation] = [:]
    private var creates: [JSONValue: String] = [:]
    private var modelConfirmations: [ModelKey: ModelConfirmation] = [:]
    private let newID: @Sendable () -> String

    struct MutationKey: Hashable, Sendable {
        let owner: AuthorityOwner
        let operation: String
        let payload: [String: JSONValue]
    }

    private struct ModelKey: Hashable, Sendable {
        let owner: AuthorityOwner
        let target: [String: JSONValue]
    }

    private struct ModelConfirmation: Sendable {
        let token: String
        var sent: Bool
    }

    init(newID: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() }) {
        self.newID = newID
    }

    // MARK: - Sessions

    func session(_ owner: AuthorityOwner) -> AuthoritySession? { sessions[owner] }

    /// Every owner of `sessionID`; one, unless several profiles hold the same stored id.
    func owners(of sessionID: String) -> [AuthorityOwner] {
        sessions.keys.filter { $0.sessionID == sessionID }.sorted { $0.profile < $1.profile }
    }

    func isAttached(_ owner: AuthorityOwner) -> Bool { sessions[owner]?.subscription != nil }

    /// A new connection holds no subscription.
    mutating func connectionChanged() {
        for owner in sessions.keys { sessions[owner]?.subscription = nil }
    }

    mutating func remember(_ owner: AuthorityOwner, route: String?) {
        var session = sessions[owner] ?? AuthoritySession()
        if let route { session.route = route }
        sessions[owner] = session
    }

    /// The session left this client: detached, closed or gone.
    mutating func forget(_ owner: AuthorityOwner) {
        sessions[owner] = nil
        retireMutations(of: owner)
        for (id, prompt) in prompts where prompt.owner == owner { prompts[id] = nil }
        if owners(of: owner.sessionID).isEmpty { epochs[owner.sessionID] = nil }
    }

    mutating func detached(_ owner: AuthorityOwner) { sessions[owner]?.subscription = nil }

    /// A refusal that says the runtime's copy is stale: the next call that needs it attaches first.
    mutating func forgetRevision(of owner: AuthorityOwner) { sessions[owner]?.revision = nil }

    mutating func forgetGeneration(of owner: AuthorityOwner) { sessions[owner]?.generation = nil }

    mutating func adoptGeneration(_ generation: Int?, for owner: AuthorityOwner) {
        guard let generation else { return }
        var session = sessions[owner] ?? AuthoritySession()
        if generation >= (session.generation ?? Int.min) { session.generation = generation }
        sessions[owner] = session
    }

    /// Sets the replay epoch of `sessionID` and says whether it changed from a known one.
    @discardableResult
    mutating func adoptEpoch(_ epoch: String, for sessionID: String) -> Bool {
        let previous = epochs[sessionID]
        epochs[sessionID] = epoch
        return previous != nil && previous != epoch
    }

    /// Adopts a snapshot. Returns the prompts it lists that this client has not been shown yet; prompts of the
    /// session the snapshot no longer lists have settled.
    mutating func adoptSnapshot(
        _ snapshot: CanonicalSnapshot, prompts listed: [[String: JSONValue]], owner: AuthorityOwner, route: String?
    ) -> [AuthorityPrompt] {
        var session = sessions[owner] ?? AuthoritySession()
        if let route { session.route = route }
        session.revision = snapshot.revision
        if snapshot.executionGeneration >= (session.generation ?? Int.min) {
            session.generation = snapshot.executionGeneration
        }
        session.subscription = snapshot.subscriptionId
        sessions[owner] = session
        var open: Set<String> = []
        var fresh: [AuthorityPrompt] = []
        for payload in listed {
            guard let kind = payload["kind"]?.stringValue,
                  let prompt = AuthorityPrompt(kind: kind, payload: payload, owner: owner, frameGeneration: nil)
            else { continue }
            open.insert(prompt.promptID)
            if prompts[prompt.promptID] == nil { fresh.append(prompt) }
            prompts[prompt.promptID] = prompt
        }
        for (id, prompt) in prompts where prompt.owner == owner && !open.contains(id) { prompts[id] = nil }
        return fresh
    }

    // MARK: - Events

    enum EventEffect: Equatable {
        case none
        /// A prompt to put to the server request handler.
        case prompt(AuthorityPrompt)
        /// A prompt settled; its handler, if any, stops.
        case settled(String)
    }

    /// Reads one event of `sessionID` for the state it carries (`session.info` / `session.updated` revisions,
    /// the frame's execution generation, prompts). A frame stamped with an older generation than the one known
    /// changes nothing.
    mutating func event(type: String, sessionID: String, frame: [String: JSONValue]) -> EventEffect {
        let payload = frame["payload"]?.objectValue ?? [:]
        var owners = owners(of: sessionID)
        if owners.isEmpty {
            let owner = AuthorityOwner(sessionID: sessionID, profile: nil)
            sessions[owner] = AuthoritySession()
            owners = [owner]
        }
        let generation = frame["execution_generation"]?.integerValue ?? payload["execution_generation"]?.integerValue
        if let generation {
            guard owners.allSatisfy({ generation >= (sessions[$0]?.generation ?? Int.min) }) else { return .none }
            for owner in owners { sessions[owner]?.generation = generation }
        }
        if let revision = payload["revision"]?.integerValue {
            for owner in owners {
                if type == "session.info" { sessions[owner]?.revision = revision }
                if type == "session.updated", (payload["session_id"]?.stringValue ?? sessionID) == sessionID,
                   revision > (sessions[owner]?.revision ?? -1) {
                    sessions[owner]?.revision = revision
                    retireSupersededMutations(of: owner, at: revision)
                }
            }
        }
        guard let promptID = payload["prompt_id"]?.stringValue else { return .none }
        if type.hasSuffix(".settled") {
            prompts[promptID] = nil
            return .settled(promptID)
        }
        guard type == "approval.request" || type == "clarify.request",
              let owner = owners.first,
              let prompt = AuthorityPrompt(kind: String(type.dropLast(".request".count)), payload: payload,
                                           owner: owner, frameGeneration: generation)
        else { return .none }
        let isNew = prompts[promptID] == nil
        prompts[promptID] = prompt
        return isNew ? .prompt(prompt) : .none
    }

    mutating func promptAnswered(_ promptID: String) { prompts[promptID] = nil }

    // MARK: - Creation

    /// `session.create`'s parameters the authority accepts when `runtime.describe` did not say (Desktop's list).
    static let defaultCreateParameters: Set<String> = [
        "request_id", "cwd", "model", "provider", "toolsets", "reasoning", "title", "hidden",
        "follow_profile_config", "service_tier", "cwd_explicit",
    ]

    /// The legacy create in the authority's contract: `source: gui`, the reasoning effort as `reasoning`, the
    /// fast pick as a `service_tier`, only the parameters `allowed` names, and one `request_id` per identical
    /// create until it is answered.
    mutating func prepareCreate(_ input: [String: JSONValue], allowed: Set<String>) -> [String: JSONValue] {
        var params = input
        let effort = params.removeValue(forKey: "reasoning_effort")?.stringValue
        let fast = params.removeValue(forKey: "fast")?.boolValue
        let picked = params.removeValue(forKey: "service_tier")?.stringValue
        let tier = picked.flatMap { $0.isEmpty ? nil : $0 } ?? fast.map { $0 ? "priority" : "normal" }
        if let effort, !effort.isEmpty { params["reasoning"] = .string(effort) }
        if let tier { params["service_tier"] = .string(tier) }
        if params["cwd_explicit"]?.boolValue == nil { params["cwd_explicit"] = nil }
        let route = params["profile"]
        var prepared = params.filter { allowed.contains($0.key) && $0.key != "source" && !$0.value.isNull }
        let explicit = prepared.removeValue(forKey: "request_id")?.stringValue
        let key = JSONValue.object(prepared.merging(["profile": route ?? .null]) { current, _ in current })
        let requestID = explicit ?? creates[key] ?? newID()
        creates[key] = requestID
        prepared["request_id"] = .string(requestID)
        prepared["source"] = .string("gui")
        if let route, !route.isNull { prepared["profile"] = route }
        return prepared
    }

    /// The create with this id was answered; the next identical create is a new one.
    mutating func createAnswered(_ requestID: String) {
        for (key, id) in creates where id == requestID { creates[key] = nil }
    }

    // MARK: - Mutations

    /// The `session.mutate` for an edit of `owner`: the retained one when this exact edit is still unresolved,
    /// otherwise a new request at the known revision (and generation, when `fenced`). Any other retained edit of
    /// the session is retired: only a session's very next control may retry an ambiguous one.
    mutating func mutation(
        _ owner: AuthorityOwner, operation: String, payload: [String: JSONValue], fenced: Bool
    ) throws(AuthorityPrerequisite) -> RetainedMutation {
        let key = MutationKey(owner: owner, operation: operation, payload: payload)
        retireMutations(of: owner, keeping: key)
        if let retained = mutations[key] { return retained }
        let session = sessions[owner]
        guard let revision = session?.revision else { throw .revision }
        let generation = session?.generation
        if fenced && generation == nil { throw .generation }
        let mutation = RetainedMutation(
            owner: owner, route: session?.route, requestID: newID(), operation: operation, payload: payload,
            expectedRevision: revision, expectedGeneration: fenced ? generation : nil
        )
        mutations[key] = mutation
        return mutation
    }

    /// Every retained edit of `owner` except `key` is retired.
    mutating func retireMutations(of owner: AuthorityOwner, keeping key: MutationKey? = nil) {
        for candidate in mutations.keys where candidate.owner == owner && candidate != key {
            mutations[candidate] = nil
        }
    }

    /// The authority answered `mutation`. Returns the confirmation token when it asks to confirm a model.
    mutating func mutationAnswered(_ mutation: RetainedMutation, receipt: SessionMutateResult) {
        removeMutation(requestID: mutation.requestID)
        settleModelConfirmation(mutation)
        if let revision = receipt.revision {
            // An exact retry's receipt replays its original revision: never move backwards.
            if revision >= (sessions[mutation.owner]?.revision ?? -1) { sessions[mutation.owner]?.revision = revision }
            retireSupersededMutations(of: mutation.owner, at: revision)
        }
        adoptGeneration(receipt.executionGeneration, for: mutation.owner)
        if mutation.operation == "model", receipt.status == "confirmation_required", let token = receipt.confirm {
            modelConfirmations[ModelKey(owner: mutation.owner, target: mutation.payload)] =
                ModelConfirmation(token: token, sent: false)
        }
    }

    /// The authority refused `mutation` (`reason`), or its answer was lost (`nil`). A typed refusal wrote
    /// nothing; a refusal that proves the presented revision or generation stale retires the request, and the
    /// runtime re-reads the value before the next edit. A lost answer keeps the request for an exact retry.
    mutating func mutationRefused(_ mutation: RetainedMutation, reason: String?) {
        guard let reason else { return }
        settleModelConfirmation(mutation)
        switch reason {
        case "revision_conflict":
            removeMutation(requestID: mutation.requestID)
            forgetRevision(of: mutation.owner)
        case "stale_generation":
            removeMutation(requestID: mutation.requestID)
            forgetGeneration(of: mutation.owner)
        case "session_busy" where mutation.operation == "rewind" || mutation.operation == "workspace":
            removeMutation(requestID: mutation.requestID)
        default:
            break
        }
    }

    /// Value-setting edits: re-applying one is harmless, so a later acknowledged revision retires it.
    static let valueOperations: Set<String> = ["rename", "archive", "model", "reasoning", "workspace"]

    private mutating func retireSupersededMutations(of owner: AuthorityOwner, at revision: Int) {
        for (key, mutation) in mutations where key.owner == owner && Self.valueOperations.contains(key.operation)
            && mutation.expectedRevision < revision {
            mutations[key] = nil
            settleModelConfirmation(mutation)
        }
    }

    private mutating func removeMutation(requestID: String) {
        for (key, mutation) in mutations where mutation.requestID == requestID { mutations[key] = nil }
    }

    // MARK: - Model confirmation

    /// The model payload to send: with the authority's confirmation token when the app confirms the guarded
    /// target it asked about (`confirm_expensive_model`), or when a confirmed request's answer was lost.
    mutating func modelPayload(_ owner: AuthorityOwner, target: [String: JSONValue], confirmed: Bool) -> [String: JSONValue] {
        let key = ModelKey(owner: owner, target: target)
        guard var confirmation = modelConfirmations[key], confirmed || confirmation.sent else { return target }
        confirmation.sent = true
        modelConfirmations[key] = confirmation
        return target.merging(["confirm": .string(confirmation.token)]) { _, token in token }
    }

    private mutating func settleModelConfirmation(_ mutation: RetainedMutation) {
        guard mutation.operation == "model", let token = mutation.payload["confirm"]?.stringValue else { return }
        var target = mutation.payload
        target["confirm"] = nil
        let key = ModelKey(owner: mutation.owner, target: target)
        if modelConfirmations[key]?.token == token { modelConfirmations[key] = nil }
    }
}
