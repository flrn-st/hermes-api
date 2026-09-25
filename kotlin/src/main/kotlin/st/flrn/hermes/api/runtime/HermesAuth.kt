package st.flrn.hermes.api.runtime

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public sealed interface GatewayCredential {
    public data class Ticket(val value: String, val headers: Map<String, String>) : GatewayCredential
    public data class LocalToken(val value: String, val headers: Map<String, String>) : GatewayCredential
}

/** A new credential is requested on each connection, including reconnects. */
public interface HermesAuth {
    public suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential
}

public class DashboardTicketAuth(private val headers: suspend () -> Map<String, String>) : HermesAuth {
    override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential {
        val currentHeaders = headers()
        val (status, body) = http.post(baseURI.resolve("/api/auth/ws-ticket"), currentHeaders)
        if (status == 401 || status == 403) {
            throw HermesGatewayException.AuthenticationFailed("WebSocket ticket request returned HTTP $status")
        }
        if (status !in 200..299) throw HermesGatewayException.Transport("WebSocket ticket request returned HTTP $status")
        val ticket = Json.parseToJsonElement(body).jsonObject["ticket"]?.jsonPrimitive?.content
            ?: throw HermesGatewayException.Protocol("Ticket response has no ticket")
        if (ticket.isEmpty()) throw HermesGatewayException.Protocol("Empty WebSocket ticket")
        return GatewayCredential.Ticket(ticket, currentHeaders)
    }
}

public class LocalTokenAuth(private val token: String, private val headers: Map<String, String> = emptyMap()) : HermesAuth {
    override suspend fun credential(baseURI: URI, http: GatewayHTTPTransport): GatewayCredential =
        GatewayCredential.LocalToken(token, headers)
}
