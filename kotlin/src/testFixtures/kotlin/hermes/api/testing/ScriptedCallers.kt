package hermes.api.testing

import java.net.URI
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import hermes.api.generated.gateway.GatewayMethodCatalog
import hermes.api.generated.rest.RESTMethodCatalog
import hermes.api.runtime.GatewayCaller
import hermes.api.runtime.HermesGatewayException
import hermes.api.runtime.HermesRESTException
import hermes.api.runtime.RESTCaller
import hermes.api.runtime.RESTDecoding
import hermes.api.runtime.RESTJson
import hermes.api.runtime.RESTRequest
import hermes.api.runtime.RESTResponse
import hermes.api.runtime.RESTTransport
import hermes.api.runtime.RESTTransportResponse

/** Answers a gateway call: the wire method and params in, the JSON result out. Throw
 *  [HermesGatewayException.RPC] to answer with an error. */
public typealias GatewayResponder = suspend (method: String, params: JsonElement) -> JsonElement

/** A gateway call as a scripted caller received it. */
public data class RecordedCall(val method: String, val params: JsonElement)

/** The error Hermes answers for a method it does not know. */
internal fun methodNotFound(method: String): HermesGatewayException.RPC =
    HermesGatewayException.RPC(-32601, "Method not found: $method", null)

internal fun responderFor(results: Map<String, JsonElement>): GatewayResponder =
    { method, _ -> results[method] ?: throw methodNotFound(method) }

/** The JSON configuration `HermesGateway` uses by default. */
internal val gatewayJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = true }

/** Encodes params and decodes the result through JSON exactly as on the wire, recording the call. */
internal class ScriptedCalls(private val respond: GatewayResponder, private val json: Json) {
    private val recorded = mutableListOf<RecordedCall>()

    val calls: List<RecordedCall> get() = synchronized(recorded) { recorded.toList() }

    suspend fun <Params : Any, Result : Any> call(
        method: String, params: Params, paramsSerializer: KSerializer<Params>, resultSerializer: KSerializer<Result>,
    ): Result {
        val encoded = json.encodeToJsonElement(paramsSerializer, params)
        synchronized(recorded) { recorded += RecordedCall(method, encoded) }
        val result = respond(method, encoded)
        return try {
            json.decodeFromJsonElement(resultSerializer, result)
        } catch (error: IllegalArgumentException) {
            throw HermesGatewayException.Protocol("Cannot decode $method result: ${error.message?.lineSequence()?.first()}")
        }
    }
}

/**
 * Answers gateway calls from a script, for code written against [GatewayMethodCatalog] or [GatewayCaller]:
 * `ScriptedGatewayCaller(...).methods.session.list(...)`. Params and results pass through JSON exactly as on
 * the wire, so the generated models are encoded and decoded for real.
 */
public class ScriptedGatewayCaller(respond: GatewayResponder) : GatewayCaller {
    /** Answers each method with a fixed result; any other method fails with Hermes' "method not found". */
    public constructor(results: Map<String, JsonElement>) : this(responderFor(results))

    private val scripted = ScriptedCalls(respond, gatewayJson)

    public val calls: List<RecordedCall> get() = scripted.calls

    public val methods: GatewayMethodCatalog = GatewayMethodCatalog(this)

    override suspend fun <Params : Any, Result : Any> call(
        method: String, params: Params, paramsSerializer: KSerializer<Params>, resultSerializer: KSerializer<Result>,
    ): Result = scripted.call(method, params, paramsSerializer, resultSerializer)
}

/** A JSON response as Hermes would send it. */
public fun jsonResponse(status: Int, body: String): RESTResponse =
    RESTResponse(status, mapOf("Content-Type" to "application/json"), body.encodeToByteArray())

/**
 * Answers REST calls from a script, for code written against [RESTMethodCatalog] or [RESTCaller]:
 * `ScriptedRESTCaller(...).methods.sessions.get(limit = 20)`. The generated methods build real requests and
 * decode the scripted bodies, so status handling and decoding behave as against Hermes.
 */
public class ScriptedRESTCaller(
    private val respond: suspend (RESTRequest) -> RESTResponse,
    decoding: RESTDecoding = RESTDecoding.Strict,
) : RESTCaller {
    /** Answers `"GET /api/status"`-style keys (method, space, path without query) with a status and JSON body;
     *  anything else gets 404. */
    public constructor(routes: Map<String, Pair<Int, String>>, decoding: RESTDecoding = RESTDecoding.Strict) : this({ request ->
        val (status, body) = routes["${request.method} ${request.path}"] ?: (404 to """{"detail":"Not Found"}""")
        jsonResponse(status, body)
    }, decoding)

    private val recorded = mutableListOf<RESTRequest>()

    override val json: Json = when (decoding) {
        RESTDecoding.Strict -> RESTJson
        RESTDecoding.Tolerant -> Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }

    public val requests: List<RESTRequest> get() = synchronized(recorded) { recorded.toList() }

    override val methods: RESTMethodCatalog = RESTMethodCatalog(this)

    override suspend fun send(request: RESTRequest): RESTResponse {
        synchronized(recorded) { recorded += request }
        return respond(request)
    }
}

/**
 * Answers HTTP requests from a script and records what was sent, for driving `HermesREST` (with its retries,
 * timeouts and authentication) without a server.
 */
public class ScriptedRESTTransport(steps: List<Step>) : RESTTransport {
    public constructor(vararg steps: Step) : this(steps.toList())

    public sealed interface Step {
        public data class Respond(val status: Int, val body: String, val headers: Map<String, String> = emptyMap()) : Step
        public data class Fail(val error: Exception) : Step
        /** Never answers; the caller's timeout or cancellation ends the request. */
        public data object Hang : Step
    }

    /** One request as the transport received it. */
    public class Request(
        public val method: String,
        public val uri: URI,
        public val headers: Map<String, String>,
        public val body: ByteArray?,
        public val contentType: String?,
    )

    private val lock = Any()
    private val remaining = steps.toMutableList()
    private val recorded = mutableListOf<Request>()

    public val requests: List<Request> get() = synchronized(lock) { recorded.toList() }

    override suspend fun request(
        method: String, uri: URI, headers: Map<String, String>, body: ByteArray?, contentType: String?,
    ): RESTTransportResponse {
        val step = synchronized(lock) {
            recorded += Request(method, uri, headers, body, contentType)
            remaining.removeFirstOrNull()
        } ?: throw HermesRESTException.Transport("No scripted response left")
        return when (step) {
            is Step.Respond -> RESTTransportResponse(step.status, step.headers, step.body.encodeToByteArray())
            is Step.Fail -> throw step.error
            Step.Hang -> awaitCancellation()
        }
    }
}
