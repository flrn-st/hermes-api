package st.flrn.hermes.api.runtime

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import st.flrn.hermes.api.generated.rest.RESTMethodCatalog

public interface RESTCaller {
    public suspend fun <Result : Any> request(
        method: String, path: String, resultSerializer: KSerializer<Result>, query: Map<String, String>, body: String?,
    ): Result
}

public interface RESTTransport {
    public suspend fun request(method: String, uri: URI, headers: Map<String, String>, body: String?): Pair<Int, String>
}

/** Inject a configured Ktor client for app TLS trust, cookies and SSH tunnels. */
public class KtorRESTTransport(public val client: HttpClient = HttpClient(CIO)) : RESTTransport, AutoCloseable {
    override suspend fun request(method: String, uri: URI, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val response = client.request(uri.toString()) {
            this.method = HttpMethod.parse(method)
            headers.forEach { (name, value) -> this.headers.append(name, value) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return response.status.value to response.bodyAsText()
    }

    override fun close(): Unit = client.close()
}

public sealed class HermesRESTException(message: String) : Exception(message) {
    public class Transport(message: String) : HermesRESTException(message)
    public class HTTP(public val status: Int, public val body: String) : HermesRESTException("HTTP $status")
    public class Decoding(message: String) : HermesRESTException(message)
}

public data class HermesRESTConfiguration(
    val baseURI: URI,
    val headers: suspend () -> Map<String, String> = { emptyMap() },
    val transport: RESTTransport = KtorRESTTransport(),
)

/** Typed REST calls generated only for responses reviewed against the tagged handler. */
public class HermesREST(
    private val configuration: HermesRESTConfiguration,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : RESTCaller {
    public val methods: RESTMethodCatalog = RESTMethodCatalog(this)

    override suspend fun <Result : Any> request(
        method: String, path: String, resultSerializer: KSerializer<Result>, query: Map<String, String>, body: String?,
    ): Result {
        if (!path.startsWith("/api/")) throw HermesRESTException.Transport("Invalid REST path")
        val suffix = query.toSortedMap().entries.joinToString("&") { (name, value) ->
            "${URLEncoder.encode(name, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        val base = configuration.baseURI.resolve(path)
        val uri = if (suffix.isEmpty()) base else URI("$base?$suffix")
        val (status, responseBody) = try { configuration.transport.request(method, uri, configuration.headers(), body) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw HermesRESTException.Transport(error.message ?: "HTTP transport failed") }
        if (status !in 200..299) throw HermesRESTException.HTTP(status, responseBody.take(1024))
        return try { json.decodeFromString(resultSerializer, responseBody) }
        catch (error: Exception) { throw HermesRESTException.Decoding(error.message ?: "Cannot decode REST response") }
    }
}
