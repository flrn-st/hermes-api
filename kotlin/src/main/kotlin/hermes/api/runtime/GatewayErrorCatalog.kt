package hermes.api.runtime

import hermes.api.generated.gateway.GatewayErrorKind
import hermes.api.generated.gateway.GatewayErrorRules
import hermes.api.generated.gateway.GatewayKnownError
import hermes.api.generated.gateway.GatewayRefusalReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Whether [message] fits a template of the reviewed error catalog: the literal parts around the text
 *  Hermes fills in, in order. */
internal fun matchesTemplate(segments: List<String>, message: String): Boolean {
    val first = segments.firstOrNull() ?: return false
    if (segments.size == 1) return message == first
    if (!message.startsWith(first)) return false
    var rest = first.length
    // Each literal part at its earliest position leaves the most room for the parts after it.
    for (segment in segments.subList(1, segments.size - 1)) {
        if (segment.isEmpty()) continue
        val found = message.indexOf(segment, rest)
        if (found < 0) return false
        rest = found + segment.length
    }
    val last = segments.last()
    return message.length - rest >= last.length && message.endsWith(last)
}

/** What a client can do about this error, from the reviewed catalog of the pinned release
 *  (`spec/gateway-errors.yaml`). Hermes reuses codes for unrelated failures, so this reads the message as well. */
public val HermesGatewayException.RPC.kind: GatewayErrorKind
    get() {
        refusal?.let { refusal ->
            refusal.known?.let { return it.kind }
            if (refusal.reason == GatewayRefusal.UNSUPPORTED_ON_AUTHORITY) return GatewayErrorKind.UNSUPPORTED
        }
        val rule = GatewayErrorRules.codes[code] ?: return GatewayErrorKind.UNKNOWN
        val text = message.orEmpty()
        return rule.messages.firstOrNull { matchesTemplate(it.first, text) }?.second ?: rule.kind
    }

/** The named error Hermes answered with, when it is one clients can rely on. */
public val HermesGatewayException.RPC.known: GatewayKnownError?
    get() {
        authorityRefusal?.let { return it.legacyKnown }
        val text = message.orEmpty()
        return GatewayErrorRules.names[code]?.firstOrNull { rule -> rule.first?.let { matchesTemplate(it, text) } ?: true }?.second
    }

/**
 * A refusal by Hermes' session authority (the unified gateway runtime), read from the `data` of its answer:
 * `{reason}`, plus [fields] for `invalid_params`. Hermes after v0.22.1 adds [field] and [detail] when the check
 * that fired names them; the pinned release never sends them, so they are null there.
 */
public data class GatewayRefusal(
    /** The reason as Hermes sent it. */
    public val reason: String,
    /** For `invalid_params` from the dispatcher: the params it refused (unknown or missing), as key paths. */
    public val fields: List<String>? = null,
    /** The param or field the check that fired names. */
    public val field: String? = null,
    /** Why that check fired. */
    public val detail: String? = null,
) {
    /** The reviewed reason, or null for one the pinned release does not raise. */
    public val known: GatewayRefusalReason? get() = GatewayRefusalReason.fromWire(reason)

    /** The legacy named error with the same meaning, so apps branching on [HermesGatewayException.RPC.known]
     *  (`session_busy` is [GatewayKnownError.SESSION_BUSY], `not_found` is [GatewayKnownError.SESSION_NOT_FOUND])
     *  keep working against the authority. */
    internal val legacyKnown: GatewayKnownError?
        get() = when (reason) {
            "session_busy" -> GatewayKnownError.SESSION_BUSY
            "not_found" -> GatewayKnownError.SESSION_NOT_FOUND
            "invalid_params" -> GatewayKnownError.INVALID_PARAMS
            "profile_mismatch" -> GatewayKnownError.PROFILE_NOT_FOUND
            "storage_unavailable" -> GatewayKnownError.STORAGE_UNAVAILABLE
            "unknown_method", UNSUPPORTED_ON_AUTHORITY -> GatewayKnownError.UNKNOWN_METHOD
            else -> null
        }

    public companion object {
        /** The reason the client answers a legacy call it cannot express on a session authority, before sending
         *  anything; [detail] says why. */
        public const val UNSUPPORTED_ON_AUTHORITY: String = "unsupported_on_authority"
    }
}

/** A refusal from a session authority: its message is its reason code (or the client refused the call before
 *  sending it). Legacy errors never match, so they keep their catalog classification. */
internal val HermesGatewayException.RPC.authorityRefusal: GatewayRefusal?
    get() {
        val refusal = refusal ?: return null
        if (refusal.reason == GatewayRefusal.UNSUPPORTED_ON_AUTHORITY) return refusal
        val authority = (code == 4001 || code == 5001 || code == -32601) &&
            (message == refusal.reason || (code == -32601 && refusal.reason == "unknown_method"))
        return refusal.takeIf { authority }
    }

/** The client cannot express [method] on a session authority; [detail] says why. */
internal fun unsupportedOnAuthority(method: String, detail: String): HermesGatewayException.RPC =
    HermesGatewayException.RPC(-32601, "$method is not available on this Hermes gateway: $detail", JsonObject(mapOf(
        "reason" to JsonPrimitive(GatewayRefusal.UNSUPPORTED_ON_AUTHORITY),
        "field" to JsonPrimitive(method),
        "detail" to JsonPrimitive(detail),
    )))

private fun JsonObject.refusalText(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The session authority's refusal, when Hermes answered with one: an error whose `data` carries a `reason`
 * (`4001` refusals, `-32601 unknown_method`, `5001 storage_unavailable`).
 */
public val HermesGatewayException.RPC.refusal: GatewayRefusal?
    get() {
        val body = data as? JsonObject ?: return null
        val reason = body.refusalText("reason") ?: return null
        val fields = (body["fields"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return GatewayRefusal(reason, fields, body.refusalText("field"), body.refusalText("detail"))
    }
