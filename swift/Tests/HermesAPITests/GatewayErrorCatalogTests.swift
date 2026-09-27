import HermesAPI
import Testing

private func rpc(_ code: Int, _ message: String) -> HermesGatewayError {
    .rpc(code: code, message: message, data: nil)
}

@Test func codesAreClassifiedByTheirMessage() {
    // Hermes answers 4001 both for a missing session and for a malformed audio frame.
    #expect(rpc(4001, "session not found").kind == .notFound)
    #expect(rpc(4001, "invalid base64 pcm: bad padding").kind == .invalidRequest)
    #expect(rpc(5031, "could not reach browser CDP at http://127.0.0.1:9222").kind == .unavailable)
    #expect(rpc(5031, "dispatch failed: boom").kind == .serverError)
    #expect(rpc(-32601, "unknown method: nope — the client and the Hermes backend are out of sync").kind == .unsupported)
}

@Test func unknownCodesAndNonAnswersHaveNoCatalogMeaning() {
    #expect(rpc(9999, "new in a later release").kind == .unknown)
    #expect(rpc(9999, "new in a later release").known == nil)
    #expect(HermesGatewayError.timeout.kind == nil)
    #expect(HermesGatewayError.transport("reset").known == nil)
}

@Test func namedErrorsMatchCodeAndMessage() {
    #expect(rpc(4001, "session not found").known == .sessionNotFound)
    #expect(rpc(4007, "session not found").known == .sessionNotFound)
    #expect(rpc(4007, "session no longer live; retry resume").known == .sessionNotLive)
    #expect(rpc(4009, "session disconnect interrupt settling").known == .sessionSettling)
    #expect(rpc(4009, "session busy").known == .sessionBusy)
    #expect(rpc(4009, "session busy").known?.kind == .busy)
    // A name without a message covers every message of its code.
    #expect(rpc(5032, "agent initialization timed out after 30s — your message was not sent").known == .sessionStarting)
    #expect(rpc(4064, "profile 'work' not found").known == .profileNotFound)
    #expect(rpc(4064, "server 'files' not found").known == nil)
    // 5035 is also a generic failure code; only the retiring message means the backend is leaving.
    #expect(rpc(5035, "backend is retiring; reconnect to continue").known == .backendRetiring)
    #expect(rpc(5035, "could not write the toolset config").known == nil)
}

@Test func templatesMatchWholeMessagesOnly() {
    #expect(rpc(4064, "profile 'work' not found.").known == nil)
    #expect(rpc(4064, "the profile 'work' not found").known == nil)
    #expect(rpc(4064, "profile '' not found").known == .profileNotFound)
    #expect(rpc(4009, "session busy!").known == nil)
}
