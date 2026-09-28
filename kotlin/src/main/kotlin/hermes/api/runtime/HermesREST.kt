package hermes.api.runtime

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.nio.charset.CharacterCodingException
import java.util.UUID
import kotlin.random.Random
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import hermes.api.generated.rest.RESTMethodCatalog

/** One REST request as generated methods build it. [path] is already percent-encoded. */
public data class RESTRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
)

/** A REST response with any status; generated methods decide which statuses succeed. */
public class RESTResponse(
    public val status: Int,
    headers: Map<String, String>,
    public val body: ByteArray,
) {
    /** Header values by lowercased name. */
    public val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

    /** The error for a status the operation does not document. */
    public fun undocumented(): HermesRESTException =
        HermesRESTException.HTTP(status, body.decodeToString().take(1024))

    public fun <Result> json(json: Json, serializer: KSerializer<Result>, expected: Int): Result {
        if (status != expected) throw undocumented()
        return try { json.decodeFromString(serializer, body.decodeToString(throwOnInvalidSequence = true)) }
        catch (error: Exception) { throw HermesRESTException.Decoding(error.message ?: "Cannot decode REST response") }
    }

    public fun text(expected: Int): String {
        if (status != expected) throw undocumented()
        return try { body.decodeToString(throwOnInvalidSequence = true) }
        catch (error: CharacterCodingException) { throw HermesRESTException.Decoding("REST text body is not UTF-8") }
    }

    public fun binary(expected: Int): RESTBinary {
        if (status != expected) throw undocumented()
        return RESTBinary(body, headers["content-type"])
    }

    public fun redirect(expected: Int): RESTRedirect {
        if (status != expected) throw undocumented()
        val location = headers["location"] ?: throw HermesRESTException.Decoding("Redirect has no Location")
        return RESTRedirect(status, location)
    }

    public fun empty(expected: Int) {
        if (status != expected) throw undocumented()
    }
}

/** A file or other raw body; [contentType] is the server's `Content-Type`, if any. */
public class RESTBinary(public val data: ByteArray, public val contentType: String?)

/** A redirect the operation answers with; the transport must not follow it. */
public data class RESTRedirect(val status: Int, val location: String)

/** A file part of a multipart upload. */
public class RESTFile(
    public val filename: String,
    public val data: ByteArray,
    public val contentType: String = "application/octet-stream",
)

/** Percent-encoding for path segments and query values: everything but RFC 3986 unreserved characters. */
public object RESTPath {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    public fun segment(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val char = (byte.toInt() and 0xff).toChar()
            if (char in UNRESERVED) append(char) else append('%').append("%02X".format(byte.toInt() and 0xff))
        }
    }
}

/** A `multipart/form-data` body. */
public class RESTMultipart(public val boundary: String = "hermes-api-${UUID.randomUUID()}") {
    private val parts = mutableListOf<Pair<String, ByteArray>>()

    public val contentType: String get() = "multipart/form-data; boundary=$boundary"

    public fun text(name: String, value: String) {
        parts += "Content-Disposition: form-data; name=\"${quoted(name)}\"\r\n" to value.encodeToByteArray()
    }

    public fun file(name: String, file: RESTFile) {
        parts += ("Content-Disposition: form-data; name=\"${quoted(name)}\"; filename=\"${quoted(file.filename)}\"\r\n" +
            "Content-Type: ${file.contentType}\r\n") to file.data
    }

    public fun encoded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        for ((headers, data) in parts) {
            output.write("--$boundary\r\n$headers\r\n".encodeToByteArray())
            output.write(data)
            output.write("\r\n".encodeToByteArray())
        }
        output.write("--$boundary--\r\n".encodeToByteArray())
        return output.toByteArray()
    }

    private fun quoted(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")
}

/** The JSON configuration generated REST calls use: unknown keys fail, absent optionals stay absent. */
public val RESTJson: Json = Json { ignoreUnknownKeys = false; encodeDefaults = false }

/** How strictly REST responses are decoded against the reviewed contracts. */
public enum class RESTDecoding(internal val json: Json) {
    /** Fails on a key a closed object does not declare and on an enum value the contract does not list, so
     *  contract drift surfaces at once. */
    Strict(RESTJson),
    /** Skips undeclared keys and keeps unlisted enum values in their `Unknown` case, so a newer Hermes that
     *  adds fields or values still decodes. A missing required key still fails. */
    Tolerant(Json { ignoreUnknownKeys = true; encodeDefaults = false }),
}

/** Whether generated REST models skip what their contract does not declare ([RESTDecoding.Tolerant]). */
internal val Decoder.decodesTolerantly: Boolean
    get() = (this as? JsonDecoder)?.json?.configuration?.ignoreUnknownKeys == true

public interface RESTCaller {
    public val json: Json get() = RESTJson

    public suspend fun send(request: RESTRequest): RESTResponse
}

public class RESTTransportResponse(
    public val status: Int,
    public val headers: Map<String, String>,
    public val body: ByteArray,
)

public interface RESTTransport {
    public suspend fun request(
        method: String, uri: URI, headers: Map<String, String>, body: ByteArray?, contentType: String?,
    ): RESTTransportResponse
}

/**
 * Inject a configured Ktor client for app TLS trust, cookies and SSH tunnels. It must not follow
 * redirects: an operation that redirects (dashboard login, OAuth) documents the redirect as its result.
 */
public class KtorRESTTransport(
    public val client: HttpClient = HttpClient(CIO) { followRedirects = false },
) : RESTTransport, AutoCloseable {
    override suspend fun request(
        method: String, uri: URI, headers: Map<String, String>, body: ByteArray?, contentType: String?,
    ): RESTTransportResponse {
        val response = client.request(uri.toString()) {
            this.method = HttpMethod.parse(method)
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            if (body != null) {
                setBody(ByteArrayContent(body, contentType?.let(ContentType::parse) ?: ContentType.Application.OctetStream))
            }
        }
        val responseHeaders = response.headers.entries().associate { (name, values) -> name to values.joinToString(",") }
        return RESTTransportResponse(response.status.value, responseHeaders, response.readRawBytes())
    }

    override fun close(): Unit = client.close()
}

public sealed class HermesRESTException(message: String) : Exception(message) {
    /** Hermes could not be reached (after any retries the policy allows). */
    public class Transport(message: String) : HermesRESTException(message)
    /** No response arrived within the configured timeout (after any retries the policy allows). */
    public class Timeout : HermesRESTException("REST request timed out")
    /** A status the operation does not document as a success. */
    public class HTTP(public val status: Int, public val body: String) : HermesRESTException("HTTP $status") {
        /** FastAPI's `detail` message, when the body carries one. */
        public val detail: String? get() = try {
            when (val detail = (Json.parseToJsonElement(body) as? JsonObject)?.get("detail")) {
                is JsonPrimitive -> detail.contentOrNull
                is JsonObject -> ((detail["message"] ?: detail["error"]) as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        } catch (error: SerializationException) {
            null
        }

        /** 401 or 403: the credential was missing, expired or not allowed. */
        public val isAuthenticationFailure: Boolean get() = status == 401 || status == 403
    }
    public class Decoding(message: String) : HermesRESTException(message)
}

/**
 * When [HermesREST] sends a request again.
 *
 * Safe methods (`GET`, `HEAD`) are retried after a timeout, a lost connection, 429 or 502/503/504. Any
 * method is retried when the connection could not be established, because Hermes never saw the
 * request. Waits grow exponentially with full jitter; a `Retry-After` header wins when it is shorter
 * than [maximumDelayMillis].
 */
public data class RESTRetryPolicy(
    /** Attempts per request, including the first. `1` disables retries. */
    val maxAttempts: Int = 6,
    val initialDelayMillis: Long = 250,
    val maximumDelayMillis: Long = 8_000,
    val retryableStatuses: Set<Int> = setOf(429, 502, 503, 504),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    internal fun delayMillis(beforeAttempt: Int, retryAfterMillis: Long?): Long {
        if (retryAfterMillis != null && retryAfterMillis <= maximumDelayMillis) return retryAfterMillis
        val cap = minOf(maximumDelayMillis, initialDelayMillis shl minOf(beforeAttempt - 2, 16))
        return Random.nextLong(0, cap + 1)
    }

    public companion object {
        public val None: RESTRetryPolicy = RESTRetryPolicy(maxAttempts = 1)

        internal fun isSafe(method: String): Boolean = method.uppercase() in setOf("GET", "HEAD", "OPTIONS")
    }
}

public data class HermesRESTConfiguration(
    /** Resolved before every attempt; see [HermesDashboardAddress]. */
    val address: HermesDashboardAddress,
    /** Authenticates every request; `null` for public routes or cookie sessions the transport carries. */
    val auth: HermesRESTAuth? = null,
    /** Extra headers on every request, for example a reverse proxy's credential. */
    val headers: suspend () -> Map<String, String> = { emptyMap() },
    val transport: RESTTransport = KtorRESTTransport(),
    /** Bounds each attempt, from sending the request to the last byte of the response. An enclosing
     *  [withHermesRequestTimeout] replaces it for the calls it wraps. */
    val timeoutMillis: Long = 60_000,
    val retry: RESTRetryPolicy = RESTRetryPolicy(),
    /** [RESTDecoding.Strict] by default; apps that must keep working with newer Hermes releases choose
     *  [RESTDecoding.Tolerant]. */
    val decoding: RESTDecoding = RESTDecoding.Strict,
    val logger: GatewayLogger = GatewayLogger.None,
)

/** Typed REST calls generated only for responses reviewed against the tagged handler. */
public class HermesREST(
    private val configuration: HermesRESTConfiguration,
    override val json: Json = configuration.decoding.json,
) : RESTCaller {
    public val methods: RESTMethodCatalog = RESTMethodCatalog(this)

    /** How one attempt failed, before the retry policy decides. */
    private sealed class Attempt(message: String) : Exception(message) {
        /** No connection was established, so Hermes never saw the request. */
        class NotConnected(message: String) : Attempt(message)
        /** The connection failed after the request may have been sent. */
        class Interrupted(message: String) : Attempt(message)
        class TimedOut : Attempt("timed out")
    }

    override suspend fun send(request: RESTRequest): RESTResponse {
        val retry = configuration.retry
        val safe = RESTRetryPolicy.isSafe(request.method)
        var attempt = 1
        var renewed = false
        var previousFailure: HermesRESTException? = null
        while (true) {
            val uri = uri(request, resolveAddress(previousFailure))
            val credential = configuration.auth?.authorizationHeaders() ?: emptyMap()
            val headers = configuration.headers() + credential
            var retryAfterMillis: Long? = null
            val failure: HermesRESTException = try {
                val response = perform(request, uri, headers)
                val auth = configuration.auth
                if (response.status == 401 && !renewed && auth != null && auth.renew(credential, response)) {
                    renewed = true
                    previousFailure = null
                    continue
                }
                if (response.status !in retry.retryableStatuses || !safe || attempt >= retry.maxAttempts) return response
                retryAfterMillis = response.headers["retry-after"]?.trim()?.toLongOrNull()?.coerceAtLeast(0)?.times(1000)
                response.undocumented()
            } catch (error: Attempt) {
                val failure = when (error) {
                    is Attempt.TimedOut -> HermesRESTException.Timeout()
                    else -> HermesRESTException.Transport(error.message ?: "HTTP transport failed")
                }
                if (attempt >= retry.maxAttempts || (error !is Attempt.NotConnected && !safe)) throw failure
                failure
            }
            attempt += 1
            previousFailure = failure
            // Only the status for HTTP failures: logs never carry response bodies.
            val reason = if (failure is HermesRESTException.HTTP) "HTTP ${failure.status}" else failure.message
            configuration.logger.log(GatewayLogLevel.INFO, "Retrying ${request.method} (attempt $attempt) after $reason")
            delay(retry.delayMillis(attempt, retryAfterMillis))
        }
    }

    private suspend fun resolveAddress(previousFailure: HermesRESTException?): URI = try {
        configuration.address.resolve(previousFailure)
    } catch (error: CancellationException) {
        throw error
    } catch (error: HermesRESTException) {
        throw error
    } catch (error: Exception) {
        throw HermesRESTException.Transport("Cannot resolve the dashboard address: ${error.message}")
    }

    private fun uri(request: RESTRequest, base: URI): URI {
        if (!request.path.startsWith("/") || request.path.startsWith("//")) {
            throw HermesRESTException.Transport("Invalid REST path")
        }
        val suffix = request.query.toSortedMap().entries.joinToString("&") { (name, value) ->
            "${RESTPath.segment(name)}=${RESTPath.segment(value)}"
        }
        return dashboardURI(base, request.path, suffix)
            ?: throw HermesRESTException.Transport("Invalid REST path")
    }

    private suspend fun perform(request: RESTRequest, uri: URI, headers: Map<String, String>): RESTResponse {
        val response = try {
            val timeoutMillis = currentCoroutineContext()[HermesRequestTimeout]?.millis ?: configuration.timeoutMillis
            withTimeout(timeoutMillis) {
                configuration.transport.request(request.method, uri, headers, request.body, request.contentType)
            }
        } catch (error: TimeoutCancellationException) {
            throw Attempt.TimedOut()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw classify(error)
        }
        return RESTResponse(response.status, response.headers, response.body)
    }

    private fun classify(error: Throwable): Attempt {
        var cause: Throwable? = error
        while (cause != null) {
            when (cause) {
                is ConnectException, is UnknownHostException, is NoRouteToHostException, is UnresolvedAddressException ->
                    return Attempt.NotConnected(cause.message ?: "Could not connect")
                is SocketTimeoutException, is HttpRequestTimeoutException -> return Attempt.TimedOut()
            }
            cause = cause.cause
        }
        return Attempt.Interrupted(error.message ?: "HTTP transport failed")
    }
}
