package hermes.api.live

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import hermes.api.runtime.RESTBinary
import hermes.api.runtime.RESTFile
import hermes.api.runtime.RESTRedirect

/** One scenario call's arguments as JSON, read into the typed parameters of a generated REST method. */
public class RESTArguments(
    private val values: Map<String, Map<String, JsonElement>>,
    private val body: JsonElement?,
    private val json: Json,
) {
    private fun value(name: String, location: String): JsonElement? =
        values[location]?.get(name)?.takeUnless { it is JsonNull }

    private fun <T> required(name: String, location: String, read: (JsonElement) -> T?): T {
        val raw = value(name, location)
            ?: throw LiveScenarioFailure("REST scenario is missing $location argument $name")
        return read(raw) ?: throw LiveScenarioFailure("REST scenario $location argument $name has the wrong type: $raw")
    }

    private fun <T> optional(name: String, location: String, read: (JsonElement) -> T?): T? =
        if (value(name, location) == null) null else required(name, location, read)

    private fun primitive(value: JsonElement): JsonPrimitive? = value as? JsonPrimitive

    private fun stringOf(value: JsonElement): String? = primitive(value)?.takeIf { it.isString || it.longOrNull != null }?.contentOrNull
    private fun longOf(value: JsonElement): Long? = primitive(value)?.takeIf { !it.isString }?.longOrNull
    private fun doubleOf(value: JsonElement): Double? = primitive(value)?.takeIf { !it.isString }?.doubleOrNull
    private fun boolOf(value: JsonElement): Boolean? = primitive(value)?.takeIf { !it.isString }?.booleanOrNull

    /** A file is `{"filename": ..., "content_type": ..., "text": ...}`. */
    private fun fileOf(value: JsonElement): RESTFile? {
        val fields = value as? JsonObject ?: return null
        val filename = (fields["filename"] as? JsonPrimitive)?.contentOrNull ?: return null
        val text = (fields["text"] as? JsonPrimitive)?.contentOrNull ?: return null
        val contentType = (fields["content_type"] as? JsonPrimitive)?.contentOrNull ?: "application/octet-stream"
        return RESTFile(filename, text.encodeToByteArray(), contentType)
    }

    public fun string(name: String, location: String): String = required(name, location, ::stringOf)
    public fun optionalString(name: String, location: String): String? = optional(name, location, ::stringOf)
    public fun long(name: String, location: String): Long = required(name, location, ::longOf)
    public fun optionalLong(name: String, location: String): Long? = optional(name, location, ::longOf)
    public fun double(name: String, location: String): Double = required(name, location, ::doubleOf)
    public fun optionalDouble(name: String, location: String): Double? = optional(name, location, ::doubleOf)
    public fun bool(name: String, location: String): Boolean = required(name, location, ::boolOf)
    public fun optionalBool(name: String, location: String): Boolean? = optional(name, location, ::boolOf)
    public fun file(name: String, location: String): RESTFile = required(name, location, ::fileOf)
    public fun optionalFile(name: String, location: String): RESTFile? = optional(name, location, ::fileOf)

    public fun <T> body(serializer: KSerializer<T>): T =
        json.decodeFromJsonElement(serializer, body ?: throw LiveScenarioFailure("REST scenario is missing its request body"))

    public fun <T> optionalBody(serializer: KSerializer<T>): T? =
        body?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement(serializer, it) }

    public companion object {
        public fun encode(value: Unit): JsonElement = JsonNull
        public fun encode(value: String): JsonElement = JsonPrimitive(value)
        public fun encode(value: RESTBinary): JsonElement = JsonObject(mapOf(
            "size" to JsonPrimitive(value.data.size), "content_type" to (value.contentType?.let(::JsonPrimitive) ?: JsonNull)))
        public fun encode(value: RESTRedirect): JsonElement = JsonObject(mapOf(
            "status" to JsonPrimitive(value.status), "location" to JsonPrimitive(value.location)))
        public fun status(status: Int, value: JsonElement): JsonElement =
            JsonObject(mapOf("status" to JsonPrimitive(status), "value" to value))
    }
}
