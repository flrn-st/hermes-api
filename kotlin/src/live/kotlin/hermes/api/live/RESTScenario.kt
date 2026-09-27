package hermes.api.live

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import hermes.api.live.generated.RESTOperations
import java.net.URI
import java.net.URLDecoder
import hermes.api.runtime.HermesDashboardAddress
import hermes.api.runtime.HermesREST
import hermes.api.runtime.HermesRESTAuth
import hermes.api.runtime.HermesRESTConfiguration
import hermes.api.runtime.NativeSessionAuth
import hermes.api.runtime.RESTTransport
import kotlinx.coroutines.delay

/** Runs every call of the harness's REST scenario (`scenarios/rest.yaml`) through the generated operation
 *  table, in order, so each operation's typed method, request encoding and strict response decoding
 *  meet the tagged server. */
internal object RESTScenario {
    /** Runs [calls] against [baseURI]. [auth] signs every call; a call marked `auth: native` is signed by
     *  a [NativeSessionAuth] holding the tokens the scenario captured as `tokens`. */
    suspend fun run(calls: JsonArray, baseURI: URI, auth: HermesRESTAuth?, transport: RESTTransport,
                    observations: LiveObservations) {
        val plain = HermesREST(HermesRESTConfiguration(HermesDashboardAddress(baseURI), auth, transport = transport))
        var native: HermesREST? = null
        val captured = mutableMapOf<String, JsonElement>()
        for ((index, element) in calls.withIndex()) {
            val call = element.jsonObject
            val operation = call.getValue("operation").jsonPrimitive.content
            val rest = if ((call["auth"] as? JsonPrimitive)?.contentOrNull == "native") {
                native ?: run {
                    val issued = captured["tokens"] as? JsonObject
                        ?: throw LiveScenarioFailure("REST scenario call ${index + 1} needs captured native tokens")
                    fun text(name: String) = (issued[name] as? JsonPrimitive)?.contentOrNull
                    val session = NativeSessionAuth(HermesDashboardAddress(baseURI), NativeSessionAuth.Tokens(
                        text("access_token") ?: throw LiveScenarioFailure("Captured tokens have no access_token"),
                        text("refresh_token") ?: throw LiveScenarioFailure("Captured tokens have no refresh_token"),
                        text("expires_at")?.toLongOrNull(), text("provider") ?: ""), transport)
                    HermesREST(HermesRESTConfiguration(HermesDashboardAddress(baseURI), session, transport = transport)).also { native = it }
                }
            } else {
                plain
            }
            fun section(name: String): Map<String, JsonElement> =
                (call[name] as? JsonObject)?.mapValues { resolve(it.value, captured) } ?: emptyMap()
            val arguments = RESTArguments(
                mapOf("path" to section("path"), "query" to section("query"), "form" to section("form")),
                call["body"]?.let { resolve(it, captured) }, rest.json)
            val until = call["until"] as? JsonObject
            val deadline = System.nanoTime() + ((until?.get("timeout") as? JsonPrimitive)?.contentOrNull?.toDouble() ?: 0.0)
                .times(1_000_000_000).toLong()
            var result: JsonElement
            while (true) {
                result = try {
                    RESTOperations.call(operation, rest, arguments)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw LiveScenarioFailure("REST scenario call ${index + 1} $operation failed: $error")
                }
                if (until == null || lookup(result, until.getValue("path").jsonPrimitive.content) == until["equals"]) break
                if (System.nanoTime() > deadline) {
                    throw LiveScenarioFailure("REST scenario call ${index + 1} $operation never reached $until")
                }
                delay(500)
            }
            (call["capture"] as? JsonObject)?.forEach { (name, path) ->
                captured[name] = lookup(result, path.jsonPrimitive.content)
                    ?: throw LiveScenarioFailure("REST scenario call ${index + 1} $operation has no $path to capture")
            }
            observations.rest(operation)
        }
    }

    /** Replaces `${name}` with a captured value: the whole value when the string is only the
     *  placeholder, its text when the placeholder is part of a longer string. */
    fun resolve(value: JsonElement, captured: Map<String, JsonElement>): JsonElement = when (value) {
        is JsonPrimitive -> {
            val text = value.contentOrNull
            if (!value.isString || text == null) {
                value
            } else if (text.startsWith("\${") && text.endsWith("}") && !text.drop(2).contains("\${")) {
                val name = text.drop(2).dropLast(1)
                captured[name] ?: throw LiveScenarioFailure("REST scenario has not captured $name")
            } else {
                var result: String = text
                for ((name, captive) in captured) {
                    if ("\${$name}" !in result) continue
                    val replacement = (captive as? JsonPrimitive)?.contentOrNull
                        ?: throw LiveScenarioFailure("Captured $name is not text")
                    result = result.replace("\${$name}", replacement)
                }
                JsonPrimitive(result)
            }
        }
        is JsonArray -> JsonArray(value.map { resolve(it, captured) })
        is JsonObject -> JsonObject(value.mapValues { resolve(it.value, captured) })
    }

    /** A dotted path into a JSON value; `$` is the whole value, and `path#param` the query parameter
     *  `param` of the URL at `path`. */
    fun lookup(value: JsonElement, path: String): JsonElement? {
        if ('#' in path) {
            val url = (lookup(value, path.substringBefore('#')) as? JsonPrimitive)?.contentOrNull ?: return null
            val query = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
            val parameter = query.split("&").map { it.split("=", limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == path.substringAfter('#') } ?: return null
            return JsonPrimitive(URLDecoder.decode(parameter[1], "UTF-8"))
        }
        if (path == "$") return value
        var current: JsonElement? = value
        for (key in path.split(".")) {
            current = when (val node = current) {
                is JsonObject -> node[key]
                is JsonArray -> key.toIntOrNull()?.let { node.getOrNull(it) }
                else -> return null
            }
        }
        return current
    }
}
