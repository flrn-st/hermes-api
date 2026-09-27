package hermes.api.runtime

import hermes.api.generated.gateway.GatewayErrorKind
import hermes.api.generated.gateway.GatewayErrorRules
import hermes.api.generated.gateway.GatewayKnownError

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
        val rule = GatewayErrorRules.codes[code] ?: return GatewayErrorKind.UNKNOWN
        val text = message.orEmpty()
        return rule.messages.firstOrNull { matchesTemplate(it.first, text) }?.second ?: rule.kind
    }

/** The named error Hermes answered with, when it is one clients can rely on. */
public val HermesGatewayException.RPC.known: GatewayKnownError?
    get() {
        val text = message.orEmpty()
        return GatewayErrorRules.names[code]?.firstOrNull { rule -> rule.first?.let { matchesTemplate(it, text) } ?: true }?.second
    }
