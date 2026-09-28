package hermes.api.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The wire identity of the server request currently being handled. Read with
 * `currentCoroutineContext()[ServerRequestContext]` inside a server request handler.
 * Inherited by child coroutines, including across dispatcher changes. Calls such as
 * `clarify.lock` use [id], which is the JSON-RPC envelope ID, not a session ID.
 */
public class ServerRequestContext internal constructor(
    public val id: String,
    public val method: String,
) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<ServerRequestContext>
}
