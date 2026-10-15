extension GatewayRefusal {
    /// The reason the runtime answers a call it cannot express on a session authority.
    public static let unsupportedOnAuthority = "unsupported_on_authority"

    /// The legacy named error this reason means, where one exists.
    var legacyKnown: GatewayKnownError? {
        switch reason {
        case "session_busy": .sessionBusy
        case "not_found": .sessionNotFound
        case "invalid_params": .invalidParams
        case "profile_mismatch": .profileNotFound
        case "storage_unavailable": .storageUnavailable
        case "unknown_method", Self.unsupportedOnAuthority: .unknownMethod
        default: nil
        }
    }
}

extension HermesGatewayError {
    /// A refusal from a session authority: its message is its reason code (or the runtime refused the call
    /// before sending it). Legacy errors never match, so they keep their catalog names.
    var authorityRefusal: GatewayRefusal? {
        guard case .rpc(let code, let message, _) = self, let refusal else { return nil }
        if refusal.reason == GatewayRefusal.unsupportedOnAuthority { return refusal }
        guard message == refusal.reason || (code == -32601 && refusal.reason == "unknown_method") else { return nil }
        return refusal
    }

    /// The runtime cannot express `method` on a session authority; `detail` says why.
    static func unsupportedOnAuthority(_ method: String, _ detail: String) -> HermesGatewayError {
        .rpc(code: -32601, message: "\(method) is not available on this Hermes gateway: \(detail)", data: .object([
            "reason": .string(GatewayRefusal.unsupportedOnAuthority),
            "field": .string(method),
            "detail": .string(detail),
        ]))
    }
}
