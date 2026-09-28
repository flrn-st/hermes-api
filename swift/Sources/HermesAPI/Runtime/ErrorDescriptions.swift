import Foundation

extension HermesGatewayError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .transport(let message): "Hermes could not be reached: \(message)"
        case .rpc(_, let message, _): message
        case .decoding: "Hermes sent a response this version of the app cannot read."
        case .timeout: "Hermes did not answer in time."
        case .cancelled: "The request was cancelled."
        case .incompatibleServer(let contract): "This Hermes version is not supported (desktop contract \(contract))."
        case .authenticationFailed: "Hermes rejected the credentials."
        }
    }
}

extension HermesRESTError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .transport(let message): "Hermes could not be reached: \(message)"
        case .timeout: "Hermes did not answer in time."
        case .http(let status, _): detail ?? "Hermes answered with HTTP \(status)."
        case .decoding: "Hermes sent a response this version of the app cannot read."
        }
    }
}

public extension RESTCalling {
    /// Generated typed REST namespaces over this caller.
    var methods: RESTMethodCatalog { RESTMethodCatalog(caller: self) }
}
