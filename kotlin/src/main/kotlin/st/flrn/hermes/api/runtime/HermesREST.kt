package st.flrn.hermes.api.runtime

import java.net.URI
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import st.flrn.hermes.api.generated.rest.RESTMethodCatalog

public interface RESTCaller {
    public suspend fun <Result : Any> request(method: String, path: String, resultSerializer: KSerializer<Result>): Result
}

public interface RESTTransport {
    public suspend fun request(method: String, uri: URI, headers: Map<String, String>): Pair<Int, String>
}

/** Inject a configured Ktor client for app TLS trust, cookies and SSH tunnels. */
public class KtorRESTTransport(public val client: HttpClient = HttpClient(CIO)) : RESTTransport {
    override suspend fun request(method: String, uri: URI, headers: Map<String, String>): Pair<Int, String> {
        val response = client.request(uri.toString()) {
            this.method = HttpMethod.parse(method)
            headers.forEach { (name, value) -> this.headers.append(name, value) }
        }
        return response.status.value to response.bodyAsText()
    }
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

    override suspend fun <Result : Any> request(method: String, path: String, resultSerializer: KSerializer<Result>): Result {
        if (!path.startsWith("/api/")) throw HermesRESTException.Transport("Invalid REST path")
        val uri = configuration.baseURI.resolve(path)
        val (status, body) = try { configuration.transport.request(method, uri, configuration.headers()) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw HermesRESTException.Transport(error.message ?: "HTTP transport failed") }
        if (status !in 200..299) throw HermesRESTException.HTTP(status, body.take(1024))
        return try { json.decodeFromString(resultSerializer, body) }
        catch (error: Exception) { throw HermesRESTException.Decoding(error.message ?: "Cannot decode REST response") }
    }
}
