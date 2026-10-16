import Foundation

/// Sends every call with a `profile` on its envelope, the way Hermes Desktop routes canonical calls.
///
/// A session authority reads `profile` off any frame before it checks the method's parameters
/// (`gateway/session_controls.py`, `AuthorityConnection.dispatch`): naming the socket's own profile is the same
/// as naming none, and naming another profile reaches that profile's authority when the socket's grant is
/// host-scoped, and is refused with `4001 profile_mismatch` otherwise. Without it a call acts on the socket's
/// own profile, where another profile's session id answers `not_found`. The canonical parameter types leave it
/// out because the authority strips it before validation; this caller adds it.
///
/// A call whose parameters already name a `profile` keeps its own. `profile` is a profile name as the REST
/// routes take it (`"work"`, `"default"`), not a profile home path.
public struct ProfileRoutedGatewayCaller: GatewayCalling {
    public let base: any GatewayCalling
    public let profile: String

    public init(_ base: any GatewayCalling, profile: String) {
        self.base = base
        self.profile = profile
    }

    public func call<Params: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, params: Params, as resultType: Result.Type
    ) async throws -> Result {
        let encoded: JSONValue
        do {
            encoded = try JSONValue(jsonData: JSONEncoder().encode(params))
        } catch {
            throw HermesGatewayError.decoding("Parameters of \(method) cannot be encoded: \(error.localizedDescription)")
        }
        guard var fields = encoded.objectValue else {
            throw HermesGatewayError.decoding("Parameters of \(method) are not an object")
        }
        if fields["profile"]?.stringValue?.isEmpty ?? true {
            fields["profile"] = .string(profile)
        }
        return try await base.call(method, params: JSONValue.object(fields), as: resultType)
    }
}

public extension CanonicalMethodCatalog {
    /// The canonical methods with `profile` on every call (see `ProfileRoutedGatewayCaller`); `nil` or an empty
    /// name sends them as they are.
    init(caller: any GatewayCalling, profile: String?) {
        if let profile, !profile.isEmpty {
            self.init(caller: ProfileRoutedGatewayCaller(caller, profile: profile))
        } else {
            self.init(caller: caller)
        }
    }
}

public extension HermesGatewayClient {
    /// The unified runtime's strict methods, routed to `profile`'s session authority:
    /// `gateway.canonical(profile: "work").session.mutate(...)`. See `ProfileRoutedGatewayCaller`.
    func canonical(profile: String?) -> CanonicalMethodCatalog {
        CanonicalMethodCatalog(caller: self, profile: profile)
    }
}
