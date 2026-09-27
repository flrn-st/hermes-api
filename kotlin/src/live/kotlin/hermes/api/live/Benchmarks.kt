package hermes.api.live

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import hermes.api.generated.gateway.GatewayEventPayload
import hermes.api.generated.rest.SessionExportResponse
import hermes.api.generated.rest.SessionListResponse
import hermes.api.generated.rest.SessionMessagesResponse
import hermes.api.runtime.RESTJson

/** Decoding cost of large payloads, built by inflating recorded frames (`fixtures/<release>/liveness.jsonl`):
 *  what a chat view, a session list and a long streamed reply cost the app, in milliseconds. */
public object Benchmarks {
    /** Upper bounds on a CI runner's JVM, in milliseconds. */
    public val budgets: Map<String, Double> = mapOf(
        "rest.decode.session_page_100" to 25.0,
        "rest.decode.messages_page_200" to 25.0,
        "rest.decode.export_10000" to 600.0,
        "gateway.decode.delta_events_100000" to 1_500.0,
    )

    public fun run(fixture: Path): Map<String, Double> {
        val json = RESTJson
        val records = mutableMapOf<String, JsonElement>()
        for (line in Files.readAllLines(fixture)) {
            val record = Json.parseToJsonElement(line).jsonObject
            records.putIfAbsent(record.getValue("name").jsonPrimitive.content, record.getValue("frame"))
        }
        fun body(name: String) = (records[name]?.jsonObject?.get("body") as? JsonObject)
            ?: throw LiveScenarioFailure("The fixture has no $name body")
        fun inflate(body: JsonObject, key: String, count: Int): String {
            val template = (body[key] as? JsonArray)?.firstOrNull()?.jsonObject
                ?: throw LiveScenarioFailure("The fixture's $key is empty")
            val copies = (0 until count).map { index ->
                val id = template["id"] as? JsonPrimitive
                JsonObject(template + ("id" to when {
                    id == null -> JsonPrimitive(index)
                    id.isString -> JsonPrimitive("${id.content}-$index")
                    else -> JsonPrimitive(index + 1)
                }))
            }
            return JsonObject(body + (key to JsonArray(copies))).toString()
        }
        val results = mutableMapOf<String, Double>()
        // Warm the JIT on each shape first, as a long-running app would have.
        val page = inflate(body("GET /api/sessions"), "sessions", 100)
        results["rest.decode.session_page_100"] = time(20) { json.decodeFromString(SessionListResponse.serializer(), page) }
        val messages = inflate(body("GET /api/sessions/{session_id}/messages"), "messages", 200)
        results["rest.decode.messages_page_200"] =
            time(20) { json.decodeFromString(SessionMessagesResponse.serializer(), messages) }
        val export = inflate(body("GET /api/sessions/{session_id}/export"), "messages", 10_000)
        results["rest.decode.export_10000"] = time(3) { json.decodeFromString(SessionExportResponse.serializer(), export) }
        // A long streamed reply: each frame parsed and its payload decoded, as the gateway's reader does.
        val delta = (records["message.delta"] ?: throw LiveScenarioFailure("The fixture has no message.delta")).toString()
        results["gateway.decode.delta_events_100000"] = time(1) {
            repeat(100_000) {
                val frame = json.parseToJsonElement(delta).jsonObject
                val params = frame.getValue("params").jsonObject
                GatewayEventPayload.decode(params.getValue("type").jsonPrimitive.content,
                    params["payload"] ?: JsonObject(emptyMap()), json)
                params["seq"]?.jsonPrimitive?.longOrNull
            }
        }
        return results
    }

    private fun time(repeats: Int, block: () -> Unit): Double {
        repeat(repeats) { block() }  // warm-up
        val started = System.nanoTime()
        repeat(repeats) { block() }
        return (System.nanoTime() - started) / 1_000_000.0 / repeats
    }
}
