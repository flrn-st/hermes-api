package hermes.api

import hermes.api.generated.gateway.GatewayErrorKind
import hermes.api.generated.gateway.GatewayKnownError
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.kind
import hermes.api.runtime.known
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GatewayErrorCatalogTest {
    private fun rpc(code: Int, message: String) = HermesGatewayException.RPC(code, message, null)

    @Test
    fun codesAreClassifiedByTheirMessage() {
        // Hermes answers 4001 both for a missing session and for a malformed audio frame.
        assertEquals(GatewayErrorKind.NOT_FOUND, rpc(4001, "session not found").kind)
        assertEquals(GatewayErrorKind.INVALID_REQUEST, rpc(4001, "invalid base64 pcm: bad padding").kind)
        assertEquals(GatewayErrorKind.UNAVAILABLE, rpc(5031, "could not reach browser CDP at http://127.0.0.1:9222").kind)
        assertEquals(GatewayErrorKind.SERVER_ERROR, rpc(5031, "dispatch failed: boom").kind)
        assertEquals(GatewayErrorKind.UNSUPPORTED, rpc(-32601, "unknown method: nope").kind)
        assertEquals(GatewayErrorKind.UNKNOWN, rpc(9999, "new in a later release").kind)
        assertNull(rpc(9999, "new in a later release").known)
    }

    @Test
    fun namedErrorsMatchCodeAndMessage() {
        assertEquals(GatewayKnownError.SESSION_NOT_FOUND, rpc(4001, "session not found").known)
        assertEquals(GatewayKnownError.SESSION_NOT_FOUND, rpc(4007, "session not found").known)
        assertEquals(GatewayKnownError.SESSION_NOT_LIVE, rpc(4007, "session no longer live; retry resume").known)
        assertEquals(GatewayKnownError.SESSION_SETTLING, rpc(4009, "session disconnect interrupt settling").known)
        assertEquals(GatewayKnownError.SESSION_BUSY, rpc(4009, "session busy").known)
        assertEquals(GatewayErrorKind.BUSY, GatewayKnownError.SESSION_BUSY.kind)
        assertEquals("sessionBusy", GatewayKnownError.SESSION_BUSY.catalogName)
        // A name without a message covers every message of its code.
        assertEquals(GatewayKnownError.SESSION_STARTING, rpc(5032, "agent initialization timed out after 30s").known)
        assertEquals(GatewayKnownError.PROFILE_NOT_FOUND, rpc(4064, "profile 'work' not found").known)
        assertNull(rpc(4064, "server 'files' not found").known)
        // 5035 is also a generic failure code; only the retiring message means the backend is leaving.
        assertEquals(GatewayKnownError.BACKEND_RETIRING, rpc(5035, "backend is retiring; reconnect to continue").known)
        assertNull(rpc(5035, "could not write the toolset config").known)
    }

    @Test
    fun templatesMatchWholeMessagesOnly() {
        assertNull(rpc(4064, "profile 'work' not found.").known)
        assertNull(rpc(4064, "the profile 'work' not found").known)
        assertEquals(GatewayKnownError.PROFILE_NOT_FOUND, rpc(4064, "profile '' not found").known)
        assertNull(rpc(4009, "session busy!").known)
    }
}
