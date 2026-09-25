package hermes.api.runtime

import kotlinx.serialization.KSerializer

/** A typed JSON-RPC call boundary. The gateway runtime supplies its implementation. */
public interface GatewayCaller {
    public suspend fun <Params : Any, Result : Any> call(
        method: String,
        params: Params,
        paramsSerializer: KSerializer<Params>,
        resultSerializer: KSerializer<Result>,
    ): Result
}
