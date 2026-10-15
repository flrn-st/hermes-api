/// A message template of the reviewed error catalog: the literal text around the parts Hermes fills in.
struct GatewayMessageTemplate: Sendable {
    let segments: [String]

    init(_ segments: [String]) { self.segments = segments }

    func matches(_ message: String) -> Bool {
        guard let first = segments.first, let last = segments.last else { return false }
        guard segments.count > 1 else { return message == first }
        guard message.hasPrefix(first) else { return false }
        var rest = message.dropFirst(first.count)
        // Each literal part at its earliest position leaves the most room for the parts after it.
        for segment in segments.dropFirst().dropLast() where !segment.isEmpty {
            guard let range = rest.range(of: segment) else { return false }
            rest = rest[range.upperBound...]
        }
        return rest.hasSuffix(last)
    }
}

struct GatewayMessageRule: Sendable {
    let template: GatewayMessageTemplate
    let kind: GatewayErrorKind
}

struct GatewayCodeRule: Sendable {
    let kind: GatewayErrorKind
    let messages: [GatewayMessageRule]
}

struct GatewayNameRule: Sendable {
    /// `nil` matches every message of the code.
    let template: GatewayMessageTemplate?
    let name: GatewayKnownError
}

/// The reviewed classification of the pinned release's error codes (`spec/gateway-errors.yaml`); the
/// rule tables are generated.
enum GatewayErrorCatalog {
    static func kind(code: Int, message: String) -> GatewayErrorKind {
        guard let rule = codes[code] else { return .unknown }
        return rule.messages.first { $0.template.matches(message) }?.kind ?? rule.kind
    }

    static func known(code: Int, message: String) -> GatewayKnownError? {
        names[code]?.first { $0.template?.matches(message) ?? true }?.name
    }
}

/// A refusal by Hermes' session authority (the unified gateway runtime), read from the `data` of its answer:
/// `{reason}`, plus `fields` for `invalid_params`. Hermes after v0.22.1 adds `field` and `detail` when the check
/// that fired names them; the pinned release never sends them, so they are `nil` there.
public struct GatewayRefusal: Sendable, Hashable {
    /// The reason as Hermes sent it.
    public let reason: String
    /// For `invalid_params` from the dispatcher: the params it refused (unknown or missing), as key paths.
    public let fields: [String]?
    /// The param or field the check that fired names.
    public let field: String?
    /// Why that check fired.
    public let detail: String?

    public init(reason: String, fields: [String]? = nil, field: String? = nil, detail: String? = nil) {
        self.reason = reason
        self.fields = fields
        self.field = field
        self.detail = detail
    }

    /// The reviewed reason, or `nil` for one the pinned release does not raise.
    public var known: GatewayRefusalReason? { GatewayRefusalReason(rawValue: reason) }

    /// Reads `data`; `nil` unless it is an object with a string `reason`.
    init?(data: JSONValue?) {
        guard case .object(let object)? = data, case .string(let reason)? = object["reason"] else { return nil }
        var fields: [String]?
        if case .array(let items)? = object["fields"] {
            fields = items.compactMap { item in
                if case .string(let path) = item { return path }
                return nil
            }
        }
        var field: String?
        if case .string(let value)? = object["field"] { field = value }
        var detail: String?
        if case .string(let value)? = object["detail"] { detail = value }
        self.init(reason: reason, fields: fields, field: field, detail: detail)
    }
}

public extension HermesGatewayError {
    /// What a client can do about an error Hermes answered, or `nil` when the failure is not an answer
    /// from Hermes. Hermes reuses codes for unrelated failures, so this reads the message as well; a session
    /// authority's refusal is classified by its reviewed reason.
    var kind: GatewayErrorKind? {
        guard case .rpc(let code, let message, _) = self else { return nil }
        if let reason = refusal?.known { return reason.kind }
        return GatewayErrorCatalog.kind(code: code, message: message)
    }

    /// The session authority's refusal, when Hermes answered with one: an error whose `data` carries a
    /// `reason` (`4001` refusals, `-32601 unknown_method`, `5001 storage_unavailable`).
    var refusal: GatewayRefusal? {
        guard case .rpc(_, _, let data) = self else { return nil }
        return GatewayRefusal(data: data)
    }

    /// The named error Hermes answered with, when it is one clients can rely on. A session authority's
    /// refusal is named by its reason where a legacy error means the same (`session_busy` is `.sessionBusy`,
    /// `not_found` `.sessionNotFound`), so apps keep branching on `known` against either runtime.
    var known: GatewayKnownError? {
        guard case .rpc(let code, let message, _) = self else { return nil }
        if let refusal = authorityRefusal { return refusal.legacyKnown }
        return GatewayErrorCatalog.known(code: code, message: message)
    }
}
