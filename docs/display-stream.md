# Kotlin Bot Screen stream transport

Use generated `display.observe` on a dedicated gateway connection, then create a
`DisplayStreamRequest.fromGatewayURI` from that connection's actual WebSocket URI,
the returned ticket, and returned path. Do not resolve a fallback address again:
the ticket belongs to the host and profile that minted it. The helper preserves
the dashboard path prefix, replaces `/api/ws` with `/api/display/ws`, drops gateway
query credentials, and puts the new capability in `display_ticket`.

`KtorDisplayTransport` redeems the request once using an existing WebSocket-enabled
Ktor client and optional proxy headers. The caller retains ownership of the
client. The returned `DisplayConnection` exchanges binary RFB bytes and must be
closed in `finally`. Cancellation propagates. Transport failures omit sensitive
causes, URLs, ticket values, and remote close reasons; a close code is retained
when the engine makes it available. A request cannot be reused, including after
an unsuccessful connection attempt. Obtain a fresh ticket instead.

The configured maximum frame size bounds outgoing payloads and rejects oversized
incoming payloads after the engine receives them. It is not a pre-allocation
network limit. OkHttp does not support setting `WebSocketSession.maxFrameSize`;
the adapter does not attempt to change it. Clients should also bound buffering
between the transport and their renderer.

This transport does not implement RFB rendering, gateway lifetime ownership,
viewer-ID retention, lease acquisition/release, input gating, or reconnect policy.
The application must implement these using the generated display contracts.
Do not allow a background, retired instance or stale gateway generation to keep
its stream. Do not log `DisplayStreamRequest` internals or persist tickets.

Four tests in `DisplayTransportTest` cover URL/prefix/capability validation,
one-use requests, redacted diagnostics, and actual local WebSocket binary exchange
with proxy headers. The local server checks the client masking and exact upgrade
path; its private close reason never appears in the exception. These tests do
not establish live Hermes desktop rendering or production proxy/TLS behavior.
