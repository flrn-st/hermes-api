package hermes.api.live

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import hermes.api.live.generated.GatewayOperations
import hermes.api.runtime.HermesDashboardAddress
import hermes.api.runtime.HermesGateway
import hermes.api.runtime.HermesGatewayConfiguration
import hermes.api.runtime.KtorGatewayTransport

/** Runs every call of the harness's gateway scenario (`scenarios/gateway.yaml`) through the generated
 *  operation table, in order, so each method's typed parameters, generated method and strict result
 *  decoding meet the tagged server. */
internal object GatewayScenario {
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun run(calls: JsonArray, environment: LiveScenarioEnvironment, observations: LiveObservations) {
        val ktor = KtorGatewayTransport()
        val gateway = HermesGateway(HermesGatewayConfiguration(
            HermesDashboardAddress(environment.url), environment.auth, ObservingTransport(ktor, observations), httpTransport = ktor,
            networkMonitor = environment.networkMonitor, logger = environment.logger,
        ))
        try {
            gateway.connect()
            run(calls, gateway)
        } finally {
            gateway.disconnect()
            ktor.close()
        }
    }

    private suspend fun run(calls: JsonArray, gateway: HermesGateway): Unit = coroutineScope {
        // Names the scenario creates carry a per-run value, so every client can run it against one server.
        val captured = mutableMapOf<String, JsonElement>("@unique" to JsonPrimitive("kotlin${System.currentTimeMillis() / 1000}"))
        for ((index, element) in calls.withIndex()) {
            val call = element.jsonObject
            val method = call.getValue("method").jsonPrimitive.content
            val label = "Gateway scenario call ${index + 1} $method"
            val wait = (call["wait"] as? JsonPrimitive)?.contentOrNull
            val params = RESTScenario.resolve(call["params"] ?: JsonObject(emptyMap()), captured)
            // The call's own session: a subagent's events must not end the wait.
            val session = ((params as? JsonObject)?.get("session_id") as? JsonPrimitive)?.contentOrNull
            // Subscribe before calling, so the awaited event cannot precede the subscription.
            val awaited = wait?.let { type ->
                async(start = CoroutineStart.UNDISPATCHED) { gateway.events.first { it.type == type && it.sessionId == session } }
            }
            val result = try {
                GatewayOperations.call(method, gateway, params, json)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                awaited?.cancel()
                throw LiveScenarioFailure("$label failed: $error")
            }
            (call["capture"] as? JsonObject)?.forEach { (name, path) ->
                captured[name] = RESTScenario.lookup(result, path.jsonPrimitive.content)
                    ?: throw LiveScenarioFailure("$label has no $path to capture")
            }
            if (awaited != null) deadline(60_000, "$wait after $label") { awaited.await() }
        }
    }
}
