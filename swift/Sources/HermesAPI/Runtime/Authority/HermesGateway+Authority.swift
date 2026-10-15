import Foundation

/// The deadline and waiting rule of the app's call a translation serves; every frame it sends shares them.
struct AuthorityCallContext: Sendable {
    let deadline: ContinuousClock.Instant
    let waitsForConnection: Bool

    func remaining() throws -> Duration {
        let remaining = deadline - ContinuousClock.now
        guard remaining > .zero else { throw HermesGatewayError.timeout }
        return remaining
    }
}

/// Where a rebind's snapshot left a session's replay: the epoch and the last sequence it covers.
struct AuthorityReplayPoint: Sendable, Equatable {
    let epoch: String
    let lastSequence: Int
}

/// The session authority (Hermes' unified gateway runtime): detection, and the translation of the generated
/// legacy calls to its canonical protocol and of its answers back to the legacy results. See docs/gateway.md,
/// "Unified runtime". Nothing here runs on a legacy connection.
extension HermesGateway {
    // MARK: - Detection

    /// Reads what the authority accepts. A backend that announced an authority but does not know
    /// `runtime.describe` is treated as legacy.
    func describeAuthority() async throws {
        do {
            let description = try await wireCall(
                "runtime.describe", params: [String: JSONValue](), as: JSONValue.self,
                deadline: .now + configuration.connectTimeout, waitsForConnection: false
            )
            authorityDescription = try? description.decoded(as: RuntimeDescribeResult.self)
            isAuthority = true
        } catch HermesGatewayError.rpc(code: -32601, _, _) {
            isAuthority = false
        } catch HermesGatewayError.rpc(_, let message, _) {
            // It is an authority that would not describe itself (a grant without capabilities, say): the
            // defaults Desktop uses still apply.
            configuration.logger.info("runtime.describe refused (\(message)); using the default create parameters")
            authorityDescription = nil
            isAuthority = true
        }
        if isAuthority { configuration.logger.info("Connected to a Hermes session authority") }
    }

    // MARK: - Calls

    func authorityCall<Params: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, params: Params, as resultType: Result.Type, context: AuthorityCallContext
    ) async throws -> Result {
        let arguments: [String: JSONValue]
        do {
            arguments = try JSONValue(jsonData: JSONEncoder().encode(params)).objectValue ?? [:]
        } catch {
            throw HermesGatewayError.decoding("Parameters of \(method) are not an object")
        }
        let result = try await translate(method, arguments, context)
        switch method {
        case "session.create", "session.resume", "session.activate", "session.branch":
            trackSession(method: method, params: params, result: result)
        default:
            break
        }
        if Result.self == JSONValue.self, let result = result as? Result { return result }
        return try result.decoded(as: resultType)
    }

    /// Sends one canonical frame within the app call's deadline.
    private func wire(
        _ method: String, _ params: [String: JSONValue], _ context: AuthorityCallContext
    ) async throws -> JSONValue {
        try await wireCall(method, params: params, as: JSONValue.self, deadline: context.deadline,
                           waitsForConnection: context.waitsForConnection)
    }

    private func translate(
        _ method: String, _ params: [String: JSONValue], _ context: AuthorityCallContext
    ) async throws -> JSONValue {
        switch method {
        case "session.create":
            if params["messages"] != nil || params["parent_session_id"] != nil {
                return try await fork(params, context)
            }
            return try await create(params, context)
        case "session.resume":
            let prepared = params.filter { Self.resumeKeys.contains($0.key) && !$0.value.isNull }
            return try await open(prepared, context)
        case "session.activate":
            let owner = ownerOf(params)
            var prepared = params.filter { ["session_id", "profile", "cols", "omit_messages"].contains($0.key) }
            if prepared["profile"] == nil, let route = authority.session(owner)?.route { prepared["profile"] = .string(route) }
            return try await open(prepared, context)
        case "session.close":
            return try await close(params, context)
        case "session.title":
            guard let title = params["title"], !title.isNull else {
                // Reading the title: the authority's session.info carries it.
                let info = try await wire("session.info", ["session_id": params["session_id"] ?? .string("")], context)
                return .object(["title": info["title"] ?? .string(""), "session_key": params["session_id"] ?? .null])
            }
            let receipt = try await mutate(ownerOf(params), "rename", ["title": title], fenced: false, context)
            return .object(["title": receipt["title"] ?? title, "session_key": params["session_id"] ?? .null])
        case "session.cwd.set":
            let cwd = params["cwd"] ?? .string("")
            var receipt = try await mutate(ownerOf(params), "workspace", ["cwd": cwd], fenced: true, context)
            receipt["cwd"] = receipt["cwd"] ?? cwd
            return .object(Self.dropping(receipt, ["session_id", "operation", "revision", "execution_generation"]))
        case "session.workspace.move":
            let cwd = params["cwd"] ?? .string("")
            let key = params["session_key"]?.stringValue ?? ""
            let owner = AuthorityOwner(sessionID: key, profile: params["profile"]?.stringValue)
            let receipt = try await mutate(owner, "workspace", ["cwd": cwd], fenced: true, context)
            return .object(["cwd": receipt["cwd"] ?? cwd, "branch": receipt["branch"] ?? .null])
        case "session.compress":
            return try await compress(params, context)
        case "session.undo":
            return try await undo(params, context)
        case "session.branch":
            return try await branch(params, context)
        case "config.set":
            return try await configSet(params, context)
        case "slash.exec":
            return try await slashExec(params, context)
        case "session.usage":
            return try await usage(params, context)
        case "session.interrupt", "session.redirect", "session.steer":
            return try await turnControl(method, params, context)
        case "prompt.submit":
            return try await submit(params, context)
        case "approval.respond":
            return try await respondToApproval(params, context)
        case "clarify.lock":
            return try await answerClarify(promptID: params["request_id"]?.stringValue, answer: params["answer"],
                                           original: (method, params), context)
        case "request.answer":
            return try await answerClarify(promptID: params["id"]?.stringValue, answer: params["result"]?["answer"],
                                           original: (method, params), context)
        case "clarify.respond":
            try await attachIfNeeded(ownerOf(params), context)
            return try await wire(method, params, context)
        case "session.events.since":
            return try await eventsSince(params, context)
        case "session.list":
            var prepared = params.filter { ["profile", "scope", "limit", "title", "include_hidden"].contains($0.key) }
            if let limit = prepared["limit"]?.integerValue { prepared["limit"] = .integer(min(max(limit, 1), 200)) }
            return try await wire(method, prepared, context)
        case "commands.catalog":
            return try await wire(method, params.filter { $0.key != "session_id" }, context)
        case "reload.mcp":
            var prepared = params
            if prepared["confirm"]?.boolValue == nil { prepared["confirm"] = .boolean(true) }
            return try await wire(method, prepared, context)
        case "pdf.attach", "image.detach":
            throw HermesGatewayError.unsupportedOnAuthority(
                method, "the session authority does not serve it; send files with file.attach")
        case "image.attach_bytes":
            if params["session_id"]?.stringValue != nil { try await attachIfNeeded(ownerOf(params), context) }
            return try await wire(method, params, context)
        default:
            return try await wire(method, params, context)
        }
    }

    /// The session a call names, under the profile it names or, failing that, the one it is known under.
    private func ownerOf(_ params: [String: JSONValue], key: String = "session_id") -> AuthorityOwner {
        let sessionID = params[key]?.stringValue ?? ""
        if let profile = params["profile"]?.stringValue, !profile.isEmpty {
            return AuthorityOwner(sessionID: sessionID, profile: profile)
        }
        let known = authority.owners(of: sessionID)
        return known.count == 1 ? known[0] : AuthorityOwner(sessionID: sessionID, profile: nil)
    }

    /// `CanonicalResumeParams`.
    static let resumeKeys: Set<String> = [
        "session_id", "profile", "title", "latest", "workspace", "source", "editor", "api_key", "defer_history",
        "omit_messages", "cols", "display_projection",
    ]

    /// `CanonicalSubmitParams`.
    static let submitKeys: Set<String> = [
        "session_id", "profile", "text", "submission_id", "input_id", "queued", "attachments", "finite", "unattended",
        "surface", "voice_context", "interrupted", "voice_turn", "display_kind", "title_preview", "turn_author",
        "api_text", "model_override", "clarify_batch",
    ]

    /// The legacy truncating-submit keys (edit, regenerate, restore).
    static let truncateKeys: Set<String> = [
        "confirm_truncate", "confirm_empty_truncate", "confirm_deep_truncate", "truncate_before_row_id",
        "truncate_before_message_id", "truncate_before_user_ordinal", "rebind_survivor_row_ids",
    ]

    private static func dropping(_ object: [String: JSONValue], _ keys: Set<String>) -> [String: JSONValue] {
        object.filter { !keys.contains($0.key) }
    }

    // MARK: - Sessions

    private func create(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        var allowed = AuthorityProtocol.defaultCreateParameters
        allowed.formUnion(authorityDescription?.sessionCreate.parameters ?? [])
        let prepared = authority.prepareCreate(params, allowed: allowed)
        let snapshot = try await wire("session.create", prepared, context)
        if let requestID = prepared["request_id"]?.stringValue { authority.createAnswered(requestID) }
        return try adoptSnapshot(snapshot, route: params["profile"]?.stringValue)
    }

    /// Resumes (attaches) a session and answers in the legacy shape.
    private func open(_ prepared: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        let snapshot = try await wire("session.resume", prepared, context)
        return try adoptSnapshot(snapshot, route: prepared["profile"]?.stringValue)
    }

    /// Attaches `owner` over this socket and returns the snapshot's raw fields (its full `messages`).
    @discardableResult
    func attach(_ owner: AuthorityOwner, _ context: AuthorityCallContext) async throws -> [String: JSONValue] {
        let route = authority.session(owner)?.route ?? (owner.profile == "default" ? nil : owner.profile)
        var params: [String: JSONValue] = ["session_id": .string(owner.sessionID)]
        if let route { params["profile"] = .string(route) }
        let snapshot = try await wire("session.resume", params, context)
        let adopted = try adoptSnapshot(snapshot, route: route)
        trackSession(method: "session.resume", params: params, result: adopted)
        return snapshot.objectValue ?? [:]
    }

    private func attachIfNeeded(_ owner: AuthorityOwner, _ context: AuthorityCallContext) async throws {
        guard !owner.sessionID.isEmpty, !authority.isAttached(owner) else { return }
        try await attach(owner, context)
    }

    /// Adopts a `CanonicalSnapshot`: the session's revision, generation, subscription and replay position, and
    /// its open prompts, which go to the server request handler. Returns it in the legacy result shape.
    func adoptSnapshot(_ value: JSONValue, route: String?) throws -> JSONValue {
        guard var object = value.objectValue else { throw HermesGatewayError.decoding("Snapshot is not an object") }
        let snapshot = try value.decoded(as: CanonicalSnapshot.self)
        let owner = AuthorityOwner(sessionID: snapshot.sessionId, profile: route)
        let listed = object["prompts"]?.arrayValue?.compactMap(\.objectValue) ?? []
        let open = authority.adoptSnapshot(snapshot, prompts: listed, owner: owner, route: route)
        let sessionID = snapshot.sessionId
        do {
            let epoch = snapshot.replayEpoch
            let last = snapshot.lastSequence
            if replayHold[sessionID] != nil {
                // A reconnect is recovering this session: the replay after the rebind decides the watermark.
                recoverySnapshots[sessionID] = AuthorityReplayPoint(epoch: epoch, lastSequence: last)
            } else if authority.adoptEpoch(epoch, for: sessionID) || lastSequence[sessionID] == nil {
                lastSequence[sessionID] = last
            } else {
                // Everything up to the snapshot is in its messages.
                lastSequence[sessionID] = max(lastSequence[sessionID] ?? 0, last)
            }
        }
        forgotten.remove(sessionID)
        for prompt in open { deliverPrompt(prompt) }

        var info = object["info"]?.objectValue ?? [:]
        info["running"] = object["running"] ?? info["running"]
        info["stored_session_id"] = object["stored_session_id"] ?? .string(sessionID)
        object["info"] = .object(info)
        object["stored_session_id"] = object["stored_session_id"] ?? .string(sessionID)
        object["messages"] = object["messages"] ?? .array([])
        object["message_count"] = object["message_count"] ?? .integer(object["messages"]?.arrayValue?.count ?? 0)
        if let approval = listed.first(where: { $0["kind"]?.stringValue == "approval" }),
           let prompt = AuthorityPrompt(kind: "approval", payload: approval, owner: owner, frameGeneration: nil),
           case .object(let pending) = prompt.serverRequestParams {
            object["pending_approval"] = .object(pending.filter { $0.key != "session_id" })
        }
        return .object(object)
    }

    private func close(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        let owner = ownerOf(params)
        let session = authority.session(owner)
        forgetSession(owner.sessionID)
        authority.forget(owner)
        guard let subscription = session?.subscription else { return .object(["closed": .boolean(false)]) }
        var detach: [String: JSONValue] = [
            "session_id": .string(owner.sessionID), "subscription_id": .string(subscription),
        ]
        if let route = session?.route { detach["profile"] = .string(route) }
        let result = try await wire("session.detach", detach, context)
        return .object(["closed": .boolean(result["detached"]?.boolValue ?? false)])
    }

    // MARK: - Mutations

    /// Sends one `session.mutate` with Desktop's retry rules (see `AuthorityProtocol.mutation`), attaching the
    /// session first so the revision and generation it presents are current.
    private func mutate(
        _ owner: AuthorityOwner, _ operation: String, _ payload: [String: JSONValue], fenced: Bool,
        _ context: AuthorityCallContext
    ) async throws -> [String: JSONValue] {
        try await attachIfNeeded(owner, context)
        let mutation: RetainedMutation
        do {
            mutation = try authority.mutation(owner, operation: operation, payload: payload, fenced: fenced)
        } catch {
            try await attach(owner, context)
            do {
                mutation = try authority.mutation(owner, operation: operation, payload: payload, fenced: fenced)
            } catch {
                throw HermesGatewayError.unsupportedOnAuthority(
                    "session.mutate", "Hermes reported no \(error == .revision ? "revision" : "execution generation") for the session")
            }
        }
        do {
            let value = try await wire("session.mutate", mutation.params, context)
            let receipt = try value.decoded(as: SessionMutateResult.self)
            guard receipt.sessionId == owner.sessionID else {
                throw HermesGatewayError.decoding("The mutation receipt names another session")
            }
            authority.mutationAnswered(mutation, receipt: receipt)
            return value.objectValue ?? [:]
        } catch let error as HermesGatewayError {
            if case .rpc(let code, _, _) = error {
                authority.mutationRefused(mutation, reason: error.refusal?.reason ?? "rpc_\(code)")
            }
            throw error
        }
    }

    /// A seeded `session.create` (fork) as Hermes Desktop expresses it: a `branch` mutation on the parent
    /// through the seed's last stored row, then the child is resumed and answered as the created session.
    private func fork(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        guard let parent = params["parent_session_id"]?.stringValue, !parent.isEmpty else {
            throw HermesGatewayError.unsupportedOnAuthority(
                "session.create", "a seeded session needs parent_session_id: the authority only branches a stored session")
        }
        let route = params["profile"]?.stringValue
        let owner = AuthorityOwner(sessionID: parent, profile: route)
        authority.remember(owner, route: route)
        let snapshot = try await attach(owner, context)
        var payload: [String: JSONValue] = [:]
        if let seed = params["messages"]?.arrayValue {
            let rows = snapshot["messages"]?.arrayValue ?? []
            guard let through = Self.lastStoredRow(of: seed, in: rows) else {
                throw HermesGatewayError.unsupportedOnAuthority(
                    "session.create", "the fork's messages do not match the parent's stored transcript")
            }
            payload["through_message_id"] = .integer(through)
        }
        if let title = params["title"]?.stringValue, !title.isEmpty { payload["title"] = .string(title) }
        let receipt = try await mutate(owner, "branch", payload, fenced: true, context)
        guard let child = receipt["branched_session_id"]?.stringValue else {
            throw HermesGatewayError.decoding("The branch receipt names no child session")
        }
        var resume: [String: JSONValue] = ["session_id": .string(child)]
        if let route { resume["profile"] = .string(route) }
        return try await open(resume, context)
    }

    /// The stored row id of the last seed message, matching the seed in order against the parent's user,
    /// assistant and system rows with text (what a fork carries); `nil` when they do not line up.
    static func lastStoredRow(of seed: [JSONValue], in rows: [JSONValue]) -> Int? {
        func text(_ message: JSONValue) -> String? {
            let value = message["text"]?.stringValue ?? message["content"]?.stringValue
            let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return trimmed.isEmpty ? nil : trimmed
        }
        let carried: Set<String> = ["user", "assistant", "system"]
        let candidates = rows.filter { carried.contains($0["role"]?.stringValue ?? "") && text($0) != nil }
        var index = candidates.startIndex
        var last: Int?
        for message in seed {
            guard let role = message["role"]?.stringValue, let wanted = text(message) else { continue }
            while index < candidates.endIndex,
                  candidates[index]["role"]?.stringValue != role || text(candidates[index]) != wanted {
                index += 1
            }
            guard index < candidates.endIndex else { return nil }
            last = candidates[index]["row_id"]?.integerValue
            guard last != nil else { return nil }
            index += 1
        }
        return last
    }

    private func branch(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        if params["count"] != nil {
            throw HermesGatewayError.unsupportedOnAuthority(
                "session.branch", "the authority branches through a stored row, not a message count")
        }
        let owner = ownerOf(params)
        var payload: [String: JSONValue] = [:]
        if let title = params["name"]?.stringValue, !title.isEmpty { payload["title"] = .string(title) }
        let receipt = try await mutate(owner, "branch", payload, fenced: true, context)
        guard let child = receipt["branched_session_id"]?.stringValue else {
            throw HermesGatewayError.decoding("The branch receipt names no child session")
        }
        var resume: [String: JSONValue] = ["session_id": .string(child)]
        if let route = authority.session(owner)?.route { resume["profile"] = .string(route) }
        guard case .object(var result) = try await open(resume, context) else {
            throw HermesGatewayError.decoding("Snapshot is not an object")
        }
        result["parent"] = .string(owner.sessionID)
        result["title"] = result["info"]?["title"] ?? payload["title"] ?? .string("")
        return .object(result)
    }

    private func compress(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        let owner = ownerOf(params)
        var payload: [String: JSONValue] = [:]
        if let focus = params["focus_topic"]?.stringValue, !focus.isEmpty { payload["focus"] = .string(focus) }
        let receipt = try await mutate(owner, "compress", payload, fenced: true, context)
        if receipt["status"]?.stringValue == "preview" {
            let lines = receipt["lines"]?.arrayValue?.compactMap(\.stringValue) ?? []
            return .object(["status": .string("preview"), "message": .string(lines.joined(separator: "\n"))])
        }
        // The receipt carries counts, not the compacted transcript: read it back.
        var resume: [String: JSONValue] = ["session_id": .string(owner.sessionID)]
        if let route = authority.session(owner)?.route { resume["profile"] = .string(route) }
        let resumed = try await open(resume, context)
        var result: [String: JSONValue] = [
            "status": .string("compressed"), "compressed": .boolean(true),
            "messages": resumed["messages"] ?? .array([]), "info": resumed["info"] ?? .object([:]),
        ]
        if let count = receipt["message_count"]?.integerValue { result["after_messages"] = .integer(count) }
        return .object(result)
    }

    private func undo(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        let owner = ownerOf(params)
        let snapshot = try await attach(owner, context)
        let rows = snapshot["messages"]?.arrayValue ?? []
        guard let index = rows.lastIndex(where: { $0["role"]?.stringValue == "user" && $0["row_id"]?.integerValue != nil }),
              let row = rows[index]["row_id"]?.integerValue else {
            throw HermesGatewayError.unsupportedOnAuthority("session.undo", "the session has no stored user turn to undo")
        }
        let receipt = try await mutate(owner, "rewind", ["target_message_id": .integer(row)], fenced: true, context)
        return .object(["removed": .integer(receipt["rewound_count"]?.integerValue ?? rows.count - index)])
    }

    /// `config.set` for the authority: a picker model change is a `model` mutation with its confirmation
    /// handshake; a `scope` only the authority's `reasoning` and global `yolo` take is dropped elsewhere.
    private func configSet(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        let key = params["key"]?.stringValue ?? ""
        if key == "model", params["session_id"]?.stringValue != nil,
           let pick = Self.pickerModel(params["value"]?.stringValue ?? "") {
            let owner = ownerOf(params)
            let target: [String: JSONValue] = ["model": .string(pick.model), "provider": .string(pick.provider)]
            let payload = authority.modelPayload(owner, target: target,
                                                 confirmed: params["confirm_expensive_model"]?.boolValue == true)
            let receipt = try await mutate(owner, "model", payload, fenced: true, context)
            if receipt["status"]?.stringValue == "confirmation_required" {
                return .object(["key": "model", "value": .string(pick.model), "confirm_required": true,
                                "confirm_message": receipt["confirm_message"] ?? .null])
            }
            return .object(["key": "model", "value": receipt["model"] ?? .string(pick.model)])
        }
        var prepared = params
        if let scope = prepared["scope"]?.stringValue,
           key != "reasoning" && !(key == "yolo" && scope == "global") {
            prepared["scope"] = nil
        }
        return try await wire("config.set", prepared, context)
    }

    /// The composer picker's `<model> --provider <id> [--session]`; any other shape stays `config.set`.
    static func pickerModel(_ value: String) -> (model: String, provider: String)? {
        var parts = value.split(whereSeparator: \.isWhitespace).map(String.init)
        if parts.last == "--session" { parts.removeLast() }
        guard parts.count == 3, !parts[0].hasPrefix("-"), parts[1] == "--provider", !parts[2].hasPrefix("-") else {
            return nil
        }
        return (parts[0], parts[2])
    }

    /// The typed directives that are mutations on the authority: `/model <name>`, `/branch [title]`,
    /// `/compress [focus]`.
    static func slashMutation(_ command: String) -> (operation: String, payload: [String: JSONValue])? {
        var text = Substring(command.trimmingCharacters(in: .whitespacesAndNewlines))
        while text.hasPrefix("/") { text = text.dropFirst() }
        let words = text.split(whereSeparator: \.isWhitespace).map(String.init)
        guard let name = words.first else { return nil }
        let argument = words.dropFirst().joined(separator: " ")
        switch name {
        case "model":
            guard words.count == 2, !argument.hasPrefix("-") else { return nil }
            return ("model", ["model": .string(argument)])
        case "branch":
            return ("branch", argument.isEmpty ? [:] : ["title": .string(argument)])
        case "compress":
            return ("compress", argument.isEmpty ? [:] : ["focus": .string(argument)])
        default:
            return nil
        }
    }

    private func slashExec(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        guard let directive = Self.slashMutation(params["command"]?.stringValue ?? "") else {
            return try await wire("slash.exec", params, context)
        }
        let owner = ownerOf(params)
        let payload = directive.operation == "model"
            ? authority.modelPayload(owner, target: directive.payload, confirmed: false) : directive.payload
        let receipt = try await mutate(owner, directive.operation, payload, fenced: true, context)
        if directive.operation == "model", receipt["status"]?.stringValue == "confirmation_required" {
            return .object(["type": "exec", "output": receipt["confirm_message"] ?? .string(""),
                            "status": "confirmation_required"])
        }
        let output: String
        switch directive.operation {
        case "model":
            output = "model: \(receipt["model"]?.stringValue ?? directive.payload["model"]?.stringValue ?? "")"
        case "branch":
            output = "branch: \(receipt["branched_session_id"]?.stringValue ?? "")"
        default:
            output = receipt["status"]?.stringValue == "preview"
                ? (receipt["lines"]?.arrayValue?.compactMap(\.stringValue) ?? []).joined(separator: "\n")
                : "compress: \(receipt["target_session_id"]?.stringValue ?? owner.sessionID)"
        }
        return .object(["type": "exec", "output": .string(output)])
    }

    // MARK: - Turns

    private func usage(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        let stats = try await wire("session.stats", ["session_id": params["session_id"] ?? .string("")], context)
        guard let fields = stats.objectValue else { return .object([:]) }
        var usage: [String: JSONValue] = [:]
        let names = [
            ("input", "input_tokens"), ("output", "output_tokens"), ("reasoning", "reasoning_tokens"),
            ("prompt", "prompt_tokens"), ("completion", "completion_tokens"), ("total", "total_tokens"),
            ("calls", "api_calls"), ("compressions", "compression_count"), ("context_used", "context_tokens"),
            ("context_max", "context_length"), ("context_source", "context_source"),
            ("cache_read", "cache_read_tokens"), ("cache_write", "cache_write_tokens"),
            ("avg_latency_s", "avg_latency_s"), ("avg_tps", "tokens_per_s"), ("active_subagents", "active_subagents"),
            ("model", "model"),
        ]
        for (legacy, canonical) in names {
            if let value = fields[canonical], !value.isNull { usage[legacy] = value }
        }
        if let used = fields["context_tokens"]?.integerValue, let limit = fields["context_length"]?.integerValue, limit > 0 {
            usage["context_percent"] = .integer(Int((Double(used) * 100 / Double(limit)).rounded()))
        }
        return .object(usage)
    }

    /// Interrupt, redirect and steer present the execution generation they act on.
    private func turnControl(
        _ method: String, _ params: [String: JSONValue], _ context: AuthorityCallContext
    ) async throws -> JSONValue {
        let owner = ownerOf(params)
        var prepared = params.filter { $0.key != "expected_hosted_task_id" }
        if prepared["execution_generation"] == nil {
            if authority.session(owner)?.generation == nil || !authority.isAttached(owner) {
                try await attach(owner, context)
            }
            guard let generation = authority.session(owner)?.generation else {
                throw HermesGatewayError.unsupportedOnAuthority(method, "Hermes reported no execution generation")
            }
            prepared["execution_generation"] = .integer(generation)
        }
        let result: JSONValue
        do {
            result = try await wire(method, prepared, context)
        } catch let error as HermesGatewayError where error.refusal?.reason == "stale_generation" {
            authority.forgetGeneration(of: owner)
            throw error
        }
        authority.adoptGeneration(result["execution_generation"]?.integerValue, for: owner)
        guard method == "session.interrupt" else { return result }
        return .object(["status": "interrupted", "interrupted": true])
    }

    private func submit(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        let owner = ownerOf(params)
        var params = params
        if !Self.truncateKeys.isDisjoint(with: params.keys) {
            // The authority's cut is a rewind of the user row, then a plain submit.
            if let row = params["truncate_before_row_id"]?.integerValue, row > 0 {
                _ = try await mutate(owner, "rewind", ["target_message_id": .integer(row)], fenced: true, context)
            } else if params["truncate_before_row_id"] != nil || params["truncate_before_message_id"] != nil
                        || params["truncate_before_user_ordinal"] != nil {
                throw HermesGatewayError.unsupportedOnAuthority(
                    "prompt.submit", "this message is not saved yet; wait for it to settle, then edit it again")
            }
            params = params.filter { !Self.truncateKeys.contains($0.key) }
        }
        try await attachIfNeeded(owner, context)
        authority.retireMutations(of: owner)
        var prepared = params.filter { Self.submitKeys.contains($0.key) && !$0.value.isNull }
        if prepared["submission_id"] == nil && prepared["input_id"] == nil {
            // One identity per call; a resend of the same frame after a lost connection keeps it.
            prepared["submission_id"] = .string(UUID().uuidString.lowercased())
        }
        let value = try await wire("prompt.submit", prepared, context)
        let admission = try value.decoded(as: AdmissionStatus.self)
        guard admission.ref.sessionId == owner.sessionID else {
            throw HermesGatewayError.decoding("The admission receipt names another session")
        }
        authority.adoptGeneration(admission.executionGeneration, for: owner)
        var result = value.objectValue ?? [:]
        result["session_id"] = .string(admission.ref.sessionId)
        result["submission_id"] = prepared["submission_id"] ?? prepared["input_id"]
        if admission.status == .started { result["status"] = "streaming" }
        return .object(result)
    }

    // MARK: - Prompts

    private func respondToApproval(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        let promptID = params["request_id"]?.stringValue ?? params["prompt_id"]?.stringValue ?? ""
        var prompt = authority.prompts[promptID]
        if prompt == nil, params["session_id"]?.stringValue != nil {
            // Learn the open prompts from a snapshot, as an app answering from a notification needs.
            try await attach(ownerOf(params), context)
            prompt = authority.prompts[promptID]
        }
        guard let prompt else {
            throw HermesGatewayError.unsupportedOnAuthority("approval.respond", "the approval is not open on this gateway")
        }
        let result = try await respond(to: prompt, field: "choice", value: params["choice"] ?? .string("deny"), context)
        return .object(["resolved": .integer(result == "resolved" ? 1 : 0), "status": .string(result),
                        "prompt_id": .string(promptID)])
    }

    /// `clarify.lock` / `request.answer` for a clarify prompt this socket was shown; anything else goes out as
    /// it is (the legacy sidecar answers it).
    private func answerClarify(
        promptID: String?, answer: JSONValue?, original: (method: String, params: [String: JSONValue]),
        _ context: AuthorityCallContext
    ) async throws -> JSONValue {
        guard let promptID, let prompt = authority.prompts[promptID], prompt.kind == .clarify else {
            return try await wire(original.method, original.params, context)
        }
        let text = answer?.stringValue ?? answer.map(\.description) ?? ""
        let result = try await respond(to: prompt, field: "answer", value: .string(text), context)
        return .object(["status": .string(result == "resolved" ? "ok" : "expired"), "remaining": .array([])])
    }

    /// Answers a prompt (`approval.respond` / `clarify.respond`) and stops its handler. Returns the status.
    private func respond(
        to prompt: AuthorityPrompt, field: String, value: JSONValue, _ context: AuthorityCallContext
    ) async throws -> String {
        var params: [String: JSONValue] = [
            "session_id": .string(prompt.owner.sessionID), "execution_generation": .integer(prompt.generation),
            "prompt_id": .string(prompt.promptID), field: value,
        ]
        if let route = authority.session(prompt.owner)?.route { params["profile"] = .string(route) }
        let method = prompt.kind == .approval ? "approval.respond" : "clarify.respond"
        let result = try await wire(method, params, context)
        authority.promptAnswered(prompt.promptID)
        serverTasks.removeValue(forKey: prompt.promptID)?.cancel()
        return result["status"]?.stringValue ?? "resolved"
    }

    /// Puts an authority prompt to the server request handler as the legacy request it replaces, and sends the
    /// handler's answer as `approval.respond` / `clarify.respond`. A handler that throws leaves the prompt open:
    /// the authority has no declined answer.
    func deliverPrompt(_ prompt: AuthorityPrompt) {
        let id = prompt.promptID
        guard serverTasks[id] == nil, activeSocket != nil, let handler = serverRequestHandler,
              let request = try? ServerRequest.decode(method: prompt.kind.rawValue, params: prompt.serverRequestParams)
        else { return }
        let current = generation
        serverTasks[id] = Task {
            do {
                let response = try await ServerRequestContext.$current.withValue(
                    ServerRequestContext(id: id, method: prompt.kind.rawValue)
                ) { try await handler(request) }
                guard !Task.isCancelled, current == self.generation else { return }
                try await self.answer(prompt, with: response, request: request)
            } catch {
                if !Task.isCancelled {
                    self.configuration.logger.info("Prompt \(id) left open: \(Self.gatewayError(error).logDescription)")
                }
            }
            self.finishServerRequest(id)
        }
    }

    private func answer(_ prompt: AuthorityPrompt, with response: ServerRequestResult, request: ServerRequest) async throws {
        guard response.matches(request) else {
            throw HermesGatewayError.decoding("Server request result kind does not match \(prompt.kind.rawValue)")
        }
        let field: String
        let value: JSONValue
        switch response {
        case .approval(let approval):
            field = "choice"
            value = .string(approval.choice.rawValue)
        case .clarify(let clarify):
            // An empty answer is the authority's "skipped".
            field = "answer"
            let answers = clarify.answers ?? [:]
            let direct: String?? = answers[prompt.promptID]
            let first: String? = answers.sorted(by: { $0.key < $1.key }).first?.value ?? nil
            value = .string((direct ?? first) ?? "")
        default:
            throw HermesGatewayError.decoding("Server request result kind does not match \(prompt.kind.rawValue)")
        }
        let context = AuthorityCallContext(deadline: .now + configuration.requestTimeout, waitsForConnection: false)
        _ = try await respond(to: prompt, field: field, value: value, context)
    }

    // MARK: - Events and replay

    /// Applies an authority event to the translation state, then delivers it, followed by the legacy event it
    /// replaces where the app knows only that one.
    func authorityEvent(_ event: GatewayEvent, frame: [String: JSONValue], sessionID: String) {
        switch event.type {
        case "message.start": setTurn(sessionID, active: true)
        case "message.complete": setTurn(sessionID, active: false)
        default: break
        }
        switch authority.event(type: event.type, sessionID: sessionID, frame: frame) {
        case .prompt(let prompt): deliverPrompt(prompt)
        case .settled(let promptID): serverTasks.removeValue(forKey: promptID)?.cancel()
        case .none: break
        }
        eventBroadcast.yield(event)
        let payload = frame["payload"]?.objectValue ?? [:]
        if event.type == "session.updated", payload["operation"]?.stringValue == "rename",
           let title = payload["title"]?.stringValue {
            let legacy = SessionTitlePayload(sessionId: payload["session_id"]?.stringValue ?? sessionID, title: title)
            eventBroadcast.yield(GatewayEvent(type: "session.title", sessionID: sessionID, seq: event.seq,
                                              payload: .sessionTitle(legacy), replayed: event.replayed))
        }
        if event.type == "session.replay_gap" && !event.replayed {
            recoverFromReplayGap(sessionID)
        }
    }

    /// `session.events.since` with the session's replay epoch, answered in the legacy shape.
    private func eventsSince(_ params: [String: JSONValue], _ context: AuthorityCallContext) async throws -> JSONValue {
        let sessionID = params["session_id"]?.stringValue ?? ""
        var prepared: [String: JSONValue] = [
            "session_id": .string(sessionID),
            "last_sequence": params["last_sequence"] ?? params["last_seen"] ?? .integer(lastSequence[sessionID] ?? 0),
        ]
        if let epoch = params["replay_epoch"] ?? authority.epochs[sessionID].map(JSONValue.string) {
            prepared["replay_epoch"] = epoch
        }
        if let profile = params["profile"] { prepared["profile"] = profile }
        var result = try await wire("session.events.since", prepared, context).objectValue ?? [:]
        result["open_requests"] = result["open_requests"] ?? .array([])
        result["epoch"] = result["epoch"] ?? result["replay_epoch"] ?? .string("")
        if result["snapshot_required"]?.boolValue == true { result["truncated"] = .boolean(true) }
        return .object(result)
    }

    /// Rebinds one session after a reconnect (the translated `session.activate`) and replays what this client
    /// missed in the session's epoch. A session the authority no longer finds is reported unavailable; a
    /// resume answered `not_found` is never retried.
    func recoverOnAuthority(_ sessionID: String, session: TrackedSession) async throws {
        let watermark = lastSequence[sessionID] ?? 0
        let previousEpoch = authority.epochs[sessionID]
        let context = AuthorityCallContext(deadline: .now + Self.recoveryTimeout, waitsForConnection: false)
        var params: [String: JSONValue] = ["session_id": .string(sessionID), "omit_messages": true]
        if let profile = session.profile { params["profile"] = .string(profile) }
        do {
            _ = try await translate("session.activate", params, context)
        } catch HermesGatewayError.rpc(_, let message, let data) {
            let reason = GatewayRefusal(data: data)?.reason ?? message
            configuration.logger.info("Rebind \(sessionID) refused: \(reason)")
            for owner in authority.owners(of: sessionID) { authority.forget(owner) }
            retire(sessionID)
            recoveryBroadcast.yield(.unavailable(sessionID: sessionID, reason: reason))
            return
        }
        try await replayOnAuthority(sessionID, from: watermark, previousEpoch: previousEpoch, context)
    }

    private func replayOnAuthority(
        _ sessionID: String, from watermark: Int, previousEpoch: String?, _ context: AuthorityCallContext
    ) async throws {
        guard let point = recoverySnapshots.removeValue(forKey: sessionID) else { return }
        if let previousEpoch, previousEpoch != point.epoch {
            // The authority re-minted the epoch: what this client missed is only in the snapshot.
            authority.adoptEpoch(point.epoch, for: sessionID)
            lastSequence[sessionID] = point.lastSequence
            recoveryBroadcast.yield(.replayTruncated(sessionID: sessionID))
            return
        }
        authority.adoptEpoch(point.epoch, for: sessionID)
        var params: [String: JSONValue] = [
            "session_id": .string(sessionID), "last_sequence": .integer(watermark), "replay_epoch": .string(point.epoch),
        ]
        if let route = authority.owners(of: sessionID).first.flatMap({ authority.session($0)?.route }) {
            params["profile"] = .string(route)
        }
        let page: CanonicalEventsResult
        let events: [JSONValue]
        do {
            let value = try await wire("session.events.since", params, context)
            page = try value.decoded(as: CanonicalEventsResult.self)
            events = value["events"]?.arrayValue ?? []
        } catch HermesGatewayError.rpc(_, let message, _) {
            configuration.logger.error("Replay refused: \(message)")
            lastSequence[sessionID] = point.lastSequence
            recoveryBroadcast.yield(.replayTruncated(sessionID: sessionID))
            return
        }
        configuration.logger.debug(
            "Replay \(sessionID) after \(watermark): \(page.events.count) events, latest \(page.latestSeq), snapshot required \(page.needsSnapshot)")
        if page.needsSnapshot {
            lastSequence[sessionID] = max(page.latestSeq, point.lastSequence)
            recoveryBroadcast.yield(.replayTruncated(sessionID: sessionID))
            return
        }
        for event in events { handleEvent(event, replayed: true) }
    }

    /// The authority dropped this socket's subscription after its backlog overflowed: resume the session and
    /// replay from the watermark, holding live events until then.
    private func recoverFromReplayGap(_ sessionID: String) {
        for owner in authority.owners(of: sessionID) { authority.detached(owner) }
        guard ready, replayHold[sessionID] == nil else { return }
        replayHold[sessionID] = []
        let current = generation
        Task {
            await self.recoverAfterGap(sessionID, generation: current)
        }
    }

    private func recoverAfterGap(_ sessionID: String, generation current: Int) async {
        defer { if current == generation { releaseHeldEvents(for: sessionID) } }
        guard current == generation else { return }
        do {
            try await recoverOnAuthority(sessionID, session: sessions[sessionID] ?? TrackedSession())
        } catch {
            recoverySnapshots[sessionID] = nil
            recoveryBroadcast.yield(.replayTruncated(sessionID: sessionID))
        }
    }
}
