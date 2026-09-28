package hermes.api.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * The server request a handler is answering, in the coroutine context of the handler passed to
 * `setServerRequestHandler` (and of coroutines it starts); read it with [currentServerRequest].
 *
 * [id] is Hermes' request id (`srq-…`). Some methods take it, such as `clarify.lock`'s `request_id`. After a
 * reconnect the gateway delivers a request that is still open again, with the same id, to a new handler call,
 * and cancels the call before; an app that shows one prompt per request re-binds it to the newest call by id.
 */
public class ServerRequestContext(public val id: String, public val method: String) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<ServerRequestContext>

    override fun toString(): String = "ServerRequestContext($method $id)"
}

/** The server request the calling handler answers, or `null` outside a server request handler. */
public suspend fun currentServerRequest(): ServerRequestContext? = currentCoroutineContext()[ServerRequestContext]
