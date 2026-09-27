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

public extension HermesGatewayError {
    /// What a client can do about an error Hermes answered, or `nil` when the failure is not an answer
    /// from Hermes. Hermes reuses codes for unrelated failures, so this reads the message as well.
    var kind: GatewayErrorKind? {
        guard case .rpc(let code, let message, _) = self else { return nil }
        return GatewayErrorCatalog.kind(code: code, message: message)
    }

    /// The named error Hermes answered with, when it is one clients can rely on.
    var known: GatewayKnownError? {
        guard case .rpc(let code, let message, _) = self else { return nil }
        return GatewayErrorCatalog.known(code: code, message: message)
    }
}
