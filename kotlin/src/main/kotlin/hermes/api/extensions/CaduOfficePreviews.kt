package hermes.api.extensions

import hermes.api.runtime.*
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Optional dashboard-plugin contract; deliberately separate from tagged Hermes generated APIs. */
public class CaduOfficePreviews(private val caller: RESTCaller) {
    public suspend fun capabilities(): OfficePreviewCapabilities {
        val response = caller.send(RESTRequest("GET", "$PREFIX/capabilities"))
        val result = response.json(caller.json, OfficePreviewCapabilities.serializer(), 200)
        if (result.version != 1 || !ENGINE.matches(result.engine) || result.extensions.isEmpty() ||
            result.extensions.any { !it.matches(Regex("[a-z0-9]+")) } ||
            result.maxInputBytes !in 1..MAX_INPUT || result.maxOutputBytes !in 1..MAX_OUTPUT) {
            throw HermesRESTException.Decoding("Invalid Office preview capabilities")
        }
        return result
    }

    /**
     * Uses the caller's existing authentication, address, transport and timeout policy.
     * Allow at least 60 seconds for conversion. HTTP errors retain their status; only
     * a caller-confirmed 404 indicates this optional plugin is unavailable.
     *
     * A transport may stream the binary body to disk. Before publishing a preview,
     * call [OfficePreview.verifyContent] on the returned bytes or the staged file.
     */
    public suspend fun preview(request: OfficePreviewRequest): OfficePreview {
        request.validate()
        val response = caller.send(RESTRequest("POST", "$PREFIX/preview",
            body = caller.json.encodeToString(OfficePreviewRequest.serializer(), request).encodeToByteArray(),
            contentType = "application/json"))
        val binary = response.binary(200)
        val source = response.headers["x-cadu-source-sha256"].orEmpty()
        val preview = response.headers["x-cadu-preview-sha256"].orEmpty()
        val engine = response.headers["x-cadu-preview-engine"].orEmpty()
        if (source != request.sourceSha256 || !SHA.matches(preview) || !ENGINE.matches(engine) ||
            binary.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/pdf") {
            throw HermesRESTException.Decoding("Invalid Office preview identity or content type")
        }
        return OfficePreview(binary, source, preview, engine)
    }

    private companion object {
        const val PREFIX = "/api/plugins/cadu-office-preview"
    }
}

@Serializable
public data class OfficePreviewCapabilities(
    val version: Int,
    val engine: String,
    val extensions: List<String>,
    @SerialName("max_input_bytes") val maxInputBytes: Long,
    @SerialName("max_output_bytes") val maxOutputBytes: Long,
)

@Serializable
public enum class OfficePreviewScope {
    @SerialName("managed") Managed,
    @SerialName("filesystem") Filesystem,
}

@Serializable
public data class OfficePreviewRequest(
    val path: String,
    @SerialName("source_sha256") val sourceSha256: String,
    val scope: OfficePreviewScope = OfficePreviewScope.Managed,
    val profile: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
) {
    internal fun validate() {
        require(path.isNotBlank() && path.length <= 4096 && '\u0000' !in path) { "Invalid preview path" }
        require(SHA.matches(sourceSha256)) { "Invalid source SHA-256" }
        require(profile == null || profile.isNotBlank() && profile.length <= 256) { "Invalid profile" }
        require(sessionId == null || sessionId.isNotBlank() && sessionId.length <= 256) { "Invalid session" }
        require(scope != OfficePreviewScope.Managed || profile == null && sessionId == null) {
            "Managed files do not accept session coordinates"
        }
    }
}

public class OfficePreview internal constructor(
    public val binary: RESTBinary,
    public val sourceSha256: String,
    public val previewSha256: String,
    public val engine: String,
) {
    /** Verifies bounded bytes, PDF signature and hash. Leaves the caller's stream open. */
    public fun verifyContent(stream: InputStream): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(5)
        val buffer = ByteArray(65536)
        var count = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) throw HermesRESTException.Decoding("Preview stream made no progress")
            if (count + read > MAX_OUTPUT) throw HermesRESTException.Decoding("Office preview exceeds size limit")
            if (count < prefix.size) buffer.copyInto(prefix, count.toInt(), 0, minOf(read, prefix.size - count.toInt()))
            digest.update(buffer, 0, read)
            count += read
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (count < 5 || !prefix.contentEquals("%PDF-".encodeToByteArray()) || actual != previewSha256) {
            throw HermesRESTException.Decoding("Office preview content does not match its identity")
        }
        return count
    }

    public fun verifyContent(): Long = binary.data.inputStream().use(::verifyContent)
}

private val SHA = Regex("[0-9a-f]{64}")
private val ENGINE = Regex("sha256:[0-9a-f]{64}")
private const val MAX_INPUT = 32L * 1024 * 1024
private const val MAX_OUTPUT = 64L * 1024 * 1024
