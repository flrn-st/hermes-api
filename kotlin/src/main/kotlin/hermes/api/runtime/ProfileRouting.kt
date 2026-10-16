package hermes.api.runtime

import hermes.api.generated.gateway.CanonicalMethodCatalog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val routingJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = true }

/**
 * Sends every call with a `profile` on its envelope, the way Hermes Desktop routes canonical calls.
 *
 * A session authority reads `profile` off any frame before it checks the method's parameters
 * (`gateway/session_controls.py`, `AuthorityConnection.dispatch`): naming the socket's own profile is the same as
 * naming none, and naming another profile reaches that profile's authority when the socket's grant is
 * host-scoped, and is refused with `4001 profile_mismatch` otherwise. Without it a call acts on the socket's own
 * profile, where another profile's session id answers `not_found`. The canonical parameter types leave it out
 * because the authority strips it before validation; this caller adds it.
 *
 * A call whose parameters already name a `profile` keeps its own. [profile] is a profile name as the REST routes
 * take it (`"work"`, `"default"`), not a profile home path.
 */
public class ProfileRoutedGatewayCaller(
    public val base: GatewayCaller,
    public val profile: String,
) : GatewayCaller {
    override suspend fun <Params : Any, Result : Any> call(
        method: String,
        params: Params,
        paramsSerializer: KSerializer<Params>,
        resultSerializer: KSerializer<Result>,
    ): Result {
        val encoded = try {
            routingJson.encodeToJsonElement(paramsSerializer, params)
        } catch (error: SerializationException) {
            throw HermesGatewayException.Protocol("Parameters of $method cannot be encoded: ${error.message}")
        }
        val fields = encoded as? JsonObject
            ?: throw HermesGatewayException.Protocol("Parameters of $method are not an object")
        val named = (fields["profile"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val routed = if (named.isNullOrEmpty()) JsonObject(fields + ("profile" to JsonPrimitive(profile))) else fields
        return base.call(method, routed, JsonObject.serializer(), resultSerializer)
    }
}

/** The canonical methods with `profile` on every call (see [ProfileRoutedGatewayCaller]); `null` or an empty name
 *  sends them as they are. */
public fun CanonicalMethodCatalog(caller: GatewayCaller, profile: String?): CanonicalMethodCatalog =
    CanonicalMethodCatalog(if (profile.isNullOrEmpty()) caller else ProfileRoutedGatewayCaller(caller, profile))

/** The unified runtime's strict methods, routed to [profile]'s session authority:
 *  `gateway.canonical("work").session.mutate(...)`. See [ProfileRoutedGatewayCaller]. */
public fun HermesGatewayClient.canonical(profile: String?): CanonicalMethodCatalog = CanonicalMethodCatalog(this, profile)
