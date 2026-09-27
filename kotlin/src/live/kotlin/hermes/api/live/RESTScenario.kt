package hermes.api.live

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import hermes.api.live.generated.RESTOperations
import hermes.api.runtime.HermesREST
import kotlinx.coroutines.delay

/** Runs every call of the harness's REST scenario (`scenarios/rest.yaml`) through the generated operation
 *  table, in order, so each operation's typed method, request encoding and strict response decoding
 *  meet the tagged server. */
internal object RESTScenario {
    suspend fun run(calls: JsonArray, rest: HermesREST, observations: LiveObservations) {
        val captured = mutableMapOf<String, JsonElement>()
        for ((index, element) in calls.withIndex()) {
            val call = element.jsonObject
            val operation = call.getValue("operation").jsonPrimitive.content
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

    /** A dotted path into a JSON value; `$` is the whole value. */
    fun lookup(value: JsonElement, path: String): JsonElement? {
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
