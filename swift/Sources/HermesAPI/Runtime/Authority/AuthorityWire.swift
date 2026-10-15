import Foundation

// The authority's canonical results are read with the generated canonical types (`CanonicalSnapshot`,
// `AdmissionStatus`, `SessionMutateResult`, `CanonicalEventsResult`, `RuntimeDescribeResult`). Its events are
// not typed in the contract, so the runtime reads their payloads as raw JSON.

extension JSONValue {
    /// Reads this value as `T`, reporting a mismatch as a decoding failure.
    func decoded<T: Decodable>(as type: T.Type) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: JSONEncoder().encode(self))
        } catch {
            throw HermesGatewayError.decoding("\(T.self): \(error.localizedDescription)")
        }
    }

    var boolValue: Bool? { if case .boolean(let value) = self { value } else { nil } }
}

extension SessionMutateResult {
    var executionGeneration: Int? { additionalProperties["execution_generation"]?.integerValue }
    var status: String? { additionalProperties["status"]?.stringValue }
    var confirm: String? { additionalProperties["confirm"]?.stringValue }
}

extension CanonicalEventsResult {
    /// Hermes no longer holds every event after the watermark, or the epoch it was asked about is gone.
    var needsSnapshot: Bool { truncated || snapshotRequired }
}
