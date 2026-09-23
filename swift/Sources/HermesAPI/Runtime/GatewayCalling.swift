/// A typed JSON-RPC call boundary. The gateway runtime supplies its implementation.
public protocol GatewayCalling: Sendable {
    func call<Params: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String,
        params: Params,
        as resultType: Result.Type
    ) async throws -> Result
}
