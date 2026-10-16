import Foundation

/// Why Hermes refused a REST session mutation (`PATCH` / `DELETE /api/sessions/{id}`, the session import and the
/// bulk session routes), read from FastAPI's `detail`.
public enum SessionMutationRefusal: Sendable, Hashable {
    /// 409 from a standalone dashboard (`hermes dashboard`) while a gateway runtime owns the profile: it applies
    /// mutations only under the profile's exclusive maintenance lock. `home` is the profile home Hermes named.
    /// The gateway's own session methods are the path that works while it runs; see `HermesSessionEditor`.
    case runtimeOwnsProfile(home: String?)
    /// 409: the request carried no `request_id` or `expected_revision`.
    case identityRequired
    /// 409: `expected_revision` is stale; read the mutation snapshot again.
    case revisionConflict
    /// 409: a turn or compression holds the session.
    case sessionBusy
    /// Another bounded reason (`stale_generation`, `profile_mismatch`, `not_found`, `permission_denied`,
    /// `storage_unavailable`, …).
    case other(String)
}

public extension HermesRESTError {
    /// The session mutation refusal this error carries, or `nil` for any other failure.
    var sessionMutationRefusal: SessionMutationRefusal? {
        guard case .http(let status, _) = self, [400, 403, 404, 409, 503].contains(status), let detail else {
            return nil
        }
        return SessionMutationRefusal(status: status, detail: detail)
    }
}

extension SessionMutationRefusal {
    init?(status: Int, detail: String) {
        // gateway/runtime_ownership.py: "Exclusive maintenance refused: Gateway runtime already owns profile
        // <home>. Drain and stop the gateway, then retry."
        if status == 409, let marker = detail.range(of: "already owns profile ") {
            let rest = detail[marker.upperBound...]
            let home = rest.range(of: ". Drain").map { String(rest[..<$0.lowerBound]) }
                ?? String(rest).trimmingCharacters(in: CharacterSet(charactersIn: ". "))
            self = .runtimeOwnsProfile(home: home.isEmpty ? nil : home)
            return
        }
        guard !detail.isEmpty, detail.allSatisfy({ $0.isLowercase || $0 == "_" }) else { return nil }
        switch detail {
        case "mutation_identity_required": self = .identityRequired
        case "revision_conflict": self = .revisionConflict
        case "session_busy": self = .sessionBusy
        default: self = .other(detail)
        }
    }
}

/// A change `HermesSessionEditor` makes to one stored session.
public enum SessionEdit: Sendable, Hashable {
    case rename(String)
    case setPinned(Bool)
    case setArchived(Bool)
    case setUnread(Bool)
    case setHidden(Bool)
    case delete

    /// The `sidebar` payload of a metadata edit (the fields `PATCH /api/sessions/{id}` takes), `nil` for delete.
    var sidebarPayload: [String: JSONValue]? {
        switch self {
        case .rename(let title): ["title": .string(title)]
        case .setPinned(let value): ["pinned": .boolean(value)]
        case .setArchived(let value): ["archived": .boolean(value)]
        case .setUnread(let value): ["unread": .boolean(value)]
        case .setHidden(let value): ["hidden": .boolean(value)]
        case .delete: nil
        }
    }
}

/// What Hermes answered for a `SessionEdit`.
public struct SessionEditReceipt: Sendable, Hashable {
    /// Which path applied the edit.
    public enum Route: Sendable, Hashable {
        /// The dashboard's fenced REST routes.
        case rest
        /// `session.mutate` on the gateway's session authority.
        case authority
        /// The legacy dispatcher's own writer on the dashboard's `/api/ws` (`session.title`, `session.archive`,
        /// `session.set_hidden`, `session.delete`): unfenced, with no revision or receipt.
        case legacyGateway
    }

    public let sessionId: String
    public let route: Route
    /// The session's revision after the edit; `nil` on the legacy gateway, which keeps none.
    public let revision: Int?
    /// A delete found the session already gone.
    public let alreadyAbsent: Bool

    public init(sessionId: String, route: Route, revision: Int?, alreadyAbsent: Bool = false) {
        self.sessionId = sessionId
        self.route = route
        self.revision = revision
        self.alreadyAbsent = alreadyAbsent
    }
}

/// An edit `HermesSessionEditor` could not route.
public enum HermesSessionEditError: Error, Sendable, Hashable {
    /// A gateway runtime owns the profile, so the standalone dashboard refuses the REST mutation, and the
    /// gateway the editor was given cannot apply this edit either: none was given, or it is the dashboard's
    /// legacy socket, which has no pin or unread verb and renames only a session it holds live.
    case runtimeOwnsProfile(home: String?, edit: SessionEdit)
}

/// Renames, pins, archives, marks unread, hides and deletes stored sessions with Hermes' fenced mutations, and
/// routes them through the gateway when the dashboard cannot apply them.
///
/// From v0.22.1 every REST session mutation carries a `request_id` and the `expected_revision` it last read. The
/// editor reads `GET /api/sessions/{id}/mutation-snapshot`, sends the edit with a fresh request id, sends the
/// exact same request again when the answer was lost (Hermes repeats the stored receipt), and reads the snapshot
/// again once after `revision_conflict`.
///
/// A standalone dashboard refuses every mutation while a gateway runtime owns the profile
/// (`SessionMutationRefusal.runtimeOwnsProfile`), and v0.22.1 offers no route from the dashboard to that
/// runtime. With a `gateway`, the editor then applies the edit there:
/// - on a session authority, as `session.mutate` (`sidebar`, or `delete` with the snapshot's generation), routed
///   to `profile` (see `ProfileRoutedGatewayCaller`);
/// - on the dashboard's legacy socket, with the legacy dispatcher's own writers: `session.archive`,
///   `session.set_hidden`, `session.delete` (Hermes refuses a session live in that dispatcher), and
///   `session.title` for a session this gateway holds live. These are unfenced writes beside the runtime: Hermes
///   records no revision and tells no other client. Pin and unread have no legacy verb and throw
///   `HermesSessionEditError.runtimeOwnsProfile`, as every edit does without a gateway.
public struct HermesSessionEditor: Sendable {
    private let rest: RESTMethodCatalog
    private let gateway: (any HermesGatewayClient)?
    private let retries: Int

    /// - Parameters:
    ///   - rest: the dashboard's REST client (a `HermesREST`, or any `RESTCalling`).
    ///   - gateway: the gateway to apply an edit through when the dashboard refuses it because a runtime owns the
    ///     profile; `nil` reports those edits as `HermesSessionEditError.runtimeOwnsProfile`.
    ///   - retries: how often an edit whose answer was lost (a transport failure or timeout) is sent again,
    ///     as the same request.
    public init(rest: any RESTCalling, gateway: (any HermesGatewayClient)? = nil, retries: Int = 2) {
        self.rest = RESTMethodCatalog(caller: rest)
        self.gateway = gateway
        self.retries = max(0, retries)
    }

    /// Applies `edit` to the stored session `sessionId` of `profile` (`nil` for the dashboard's default).
    @discardableResult
    public func apply(_ edit: SessionEdit, to sessionId: String, profile: String? = nil) async throws
        -> SessionEditReceipt {
        var conflicts = 1
        while true {
            let snapshot = try await rest.sessions.mutationSnapshot(sessionId: sessionId, profile: profile)
            do {
                return try await sendREST(edit, sessionId: sessionId, profile: profile, snapshot: snapshot)
            } catch let error as HermesRESTError {
                switch error.sessionMutationRefusal {
                case .revisionConflict? where conflicts > 0:
                    conflicts -= 1
                case .runtimeOwnsProfile(let home)?:
                    return try await applyOnGateway(edit, sessionId: sessionId, profile: profile,
                                                    snapshot: snapshot, home: home)
                default:
                    throw error
                }
            }
        }
    }

    public func rename(_ sessionId: String, to title: String, profile: String? = nil) async throws
        -> SessionEditReceipt {
        try await apply(.rename(title), to: sessionId, profile: profile)
    }

    public func setPinned(_ pinned: Bool, for sessionId: String, profile: String? = nil) async throws
        -> SessionEditReceipt {
        try await apply(.setPinned(pinned), to: sessionId, profile: profile)
    }

    public func setArchived(_ archived: Bool, for sessionId: String, profile: String? = nil) async throws
        -> SessionEditReceipt {
        try await apply(.setArchived(archived), to: sessionId, profile: profile)
    }

    public func setUnread(_ unread: Bool, for sessionId: String, profile: String? = nil) async throws
        -> SessionEditReceipt {
        try await apply(.setUnread(unread), to: sessionId, profile: profile)
    }

    public func setHidden(_ hidden: Bool, for sessionId: String, profile: String? = nil) async throws
        -> SessionEditReceipt {
        try await apply(.setHidden(hidden), to: sessionId, profile: profile)
    }

    public func delete(_ sessionId: String, profile: String? = nil) async throws -> SessionEditReceipt {
        try await apply(.delete, to: sessionId, profile: profile)
    }

    // MARK: - REST

    private func sendREST(
        _ edit: SessionEdit, sessionId: String, profile: String?, snapshot: SessionMutationSnapshotResponse
    ) async throws -> SessionEditReceipt {
        let requestID = UUID().uuidString.lowercased()
        return try await resendingLostAnswers { () async throws -> SessionEditReceipt in
            guard let payload = edit.sidebarPayload else {
                let answer = try await rest.sessions.delete(
                    sessionId: sessionId, profile: profile, requestId: requestID,
                    expectedRevision: snapshot.runtimeRevision, expectedGeneration: snapshot.runtimeGeneration ?? 0)
                return SessionEditReceipt(sessionId: answer.sessionId ?? sessionId, route: .rest,
                                          revision: answer.revision, alreadyAbsent: answer.alreadyAbsent ?? false)
            }
            var body = SessionRename(expectedRevision: .value(snapshot.runtimeRevision), requestId: .value(requestID))
            if let profile { body.profile = .value(profile) }
            if case .string(let title)? = payload["title"] { body.title = .value(title) }
            if case .boolean(let value)? = payload["pinned"] { body.pinned = .value(value) }
            if case .boolean(let value)? = payload["archived"] { body.archived = .value(value) }
            if case .boolean(let value)? = payload["unread"] { body.unread = .value(value) }
            if case .boolean(let value)? = payload["hidden"] { body.hidden = .value(value) }
            let answer = try await rest.sessions.patch(sessionId: sessionId, body: body)
            return SessionEditReceipt(sessionId: answer.sessionId ?? sessionId, route: .rest, revision: answer.revision)
        }
    }

    /// Runs `send` again, unchanged, while its answer was lost: Hermes deduplicates by request id.
    private func resendingLostAnswers<T: Sendable>(_ send: () async throws -> T) async throws -> T {
        var attempt = 0
        while true {
            do {
                return try await send()
            } catch let error as HermesRESTError where attempt < retries && error.answerWasLost {
                attempt += 1
            } catch let error as HermesGatewayError where attempt < retries && error.answerWasLost {
                attempt += 1
            }
        }
    }

    // MARK: - Gateway

    private func applyOnGateway(
        _ edit: SessionEdit, sessionId: String, profile: String?, snapshot: SessionMutationSnapshotResponse,
        home: String?
    ) async throws -> SessionEditReceipt {
        guard let gateway else { throw HermesSessionEditError.runtimeOwnsProfile(home: home, edit: edit) }
        if await gateway.sessionAuthority {
            return try await mutateOnAuthority(gateway, edit, sessionId: sessionId, profile: profile, snapshot: snapshot)
        }
        return try await applyOnLegacyGateway(gateway, edit, sessionId: sessionId, profile: profile, home: home)
    }

    private func mutateOnAuthority(
        _ gateway: any HermesGatewayClient, _ edit: SessionEdit, sessionId: String, profile: String?,
        snapshot: SessionMutationSnapshotResponse
    ) async throws -> SessionEditReceipt {
        let session = gateway.canonical(profile: profile).session
        var snapshot = snapshot
        var conflicts = 1
        while true {
            let params = SessionMutateParams(
                sessionId: sessionId, requestId: UUID().uuidString.lowercased(),
                expectedRevision: snapshot.runtimeRevision, operation: edit.sidebarPayload == nil ? "delete" : "sidebar",
                payload: edit.sidebarPayload ?? [:],
                expectedGeneration: edit.sidebarPayload == nil ? .value(snapshot.runtimeGeneration ?? 0) : .absent)
            do {
                let receipt = try await resendingLostAnswers { try await session.mutate(params) }
                return SessionEditReceipt(sessionId: receipt.sessionId, route: .authority, revision: receipt.revision)
            } catch let error as HermesGatewayError where error.refusal?.known == .revisionConflict && conflicts > 0 {
                conflicts -= 1
                snapshot = try await rest.sessions.mutationSnapshot(sessionId: sessionId, profile: profile)
            }
        }
    }

    private func applyOnLegacyGateway(
        _ gateway: any HermesGatewayClient, _ edit: SessionEdit, sessionId: String, profile: String?, home: String?
    ) async throws -> SessionEditReceipt {
        let methods = gateway.methods.session
        let route: Patch<String> = profile.map { .value($0) } ?? .absent
        switch edit {
        case .setArchived(let archived):
            let answer = try await methods.archive(.init(sessionId: .value(sessionId), archived: archived, profile: route))
            return SessionEditReceipt(sessionId: answer.sessionKey, route: .legacyGateway, revision: nil)
        case .setHidden(let hidden):
            let answer = try await methods.setHidden(.init(sessionId: sessionId, hidden: hidden, profile: route))
            return SessionEditReceipt(sessionId: answer.sessionKey, route: .legacyGateway, revision: nil)
        case .delete:
            let answer = try await methods.delete(.init(sessionId: sessionId, profile: route))
            return SessionEditReceipt(sessionId: answer.deleted, route: .legacyGateway, revision: nil)
        case .rename(let title):
            guard let liveID = await gateway.liveSessionID(storedSessionID: sessionId, profile: profile) else {
                throw HermesSessionEditError.runtimeOwnsProfile(home: home, edit: edit)
            }
            let answer = try await methods.title(.init(sessionId: liveID, title: .value(title)))
            return SessionEditReceipt(sessionId: answer.sessionKey ?? sessionId, route: .legacyGateway, revision: nil)
        case .setPinned, .setUnread:
            throw HermesSessionEditError.runtimeOwnsProfile(home: home, edit: edit)
        }
    }
}

private extension HermesRESTError {
    /// Hermes may have applied the request: it was sent and no answer came back.
    var answerWasLost: Bool {
        switch self {
        case .transport, .timeout: true
        case .http, .decoding: false
        }
    }
}

private extension HermesGatewayError {
    var answerWasLost: Bool {
        switch self {
        case .transport, .timeout: true
        default: false
        }
    }
}
