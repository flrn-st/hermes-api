# Verification at Hermes `v2026.9.21`

The source examined is the immutable annotated tag `v2026.9.21`, commit
`d337b736aa1e8ebecfab043842d13e4a2d2f48a3`. The plan's earlier counts
were measured on a later `main` commit and do not describe this release.

## Contract

`apps/shared/src/gateway-contract.openrpc.json` contains 219 client methods,
12 server requests, 69 notifications, and 624 component schemas. The gateway
reports `DESKTOP_BACKEND_CONTRACT = 7` in `tui_gateway/server.py`. The
`pyproject.toml` package version is `0.21.4`. All extraction and generated
version constants must come from the tag, not from the plan's snapshot.

The schema audit found 1,446 `anyOf` nodes. Forty have more than one non-null
variant (for example `number | string | null` and `integer | string`), one
node is a discriminated `oneOf`, and 24 nodes are unrestricted JSON schemas.
The generator needs explicit union types and a deliberate JSON value type for
the unrestricted schemas. Treating every union as nullable single-type data
would misrepresent the tagged contract.

## Authentication and WebSocket upgrade

`/api/ws` is mounted by `hermes_cli/web_routers/chat_ws.py`. It first requires
embedded chat to be enabled, then applies authentication and request/origin
gates. In gated mode, an authenticated `POST /api/auth/ws-ticket` mints a
single-use ticket valid for 30 seconds (`dashboard_auth/routes.py` and
`dashboard_auth/ws_tickets.py`). The client may put it in `?ticket=` or send
the `hermes-gateway-v1` and `hermes-gateway-ticket.<ticket>` WebSocket
subprotocols; the latter prevents a credential appearing in the URL. The
server only echoes `hermes-gateway-v1`. In local or `--insecure` mode, the
legacy `?token=` session token is accepted; that token is rejected in gated
mode (`web_server_chat.py`). A reconnect needs a fresh ticket. The current
iOS app's `GatewayClient` uses a `ticket` query or a local `token` query.

The runtime must inject the app's HTTP credential provider for the ticket
request and WebSocket upgrade; it must not assume a cookie is always present.

## Stub inference

The documented custom provider uses `model.provider: custom` and
`model.base_url: http://.../v1` in the isolated `config.yaml`, with a model
name (`website/docs/integrations/providers.md`). `OPENAI_BASE_URL` applies to
the `openai-api` provider, and is not a general override for `custom`.
The harness should explicitly seed the custom provider and test it against
the stub server before recording fixtures.

## Offline REST extraction and plugins

The tagged repository does not commit a dashboard OpenAPI or Swagger file.
`hermes_cli.web_server` constructs a FastAPI app (`web_server.py:307`), so
`app.openapi()` is the correct base document for REST extraction. The tagged
dashboard routers do not declare `response_model=` values. The generated
OpenAPI document is therefore useful for route and request discovery but
needs reviewed response schema overlays before it can drive a trustworthy
typed REST client. Overlays should add only missing contract detail and cite
the tag's handler source and hash.

An isolated import of this release produced 293 OpenAPI paths and 334 operations.
All 333 JSON success response schemas were empty objects; the remaining
operation is a `HEAD` response without a body. The extraction recorded
per-operation source locations and SHA-256 hashes; two authentication responses,
the active-profile response, and the empty-session count are currently overlaid. The remaining operations
are deliberately untyped.

`hermes_cli.web_server` imports and mounts its ordinary routers, then calls
`_mount_plugin_api_routes()` during module import (`web_server.py:995`).
`app.openapi()` therefore includes whichever plugin routers pass that call's
trust/config gates. Bundled plugins may mount; user plugins need explicit
enablement; project plugins are never imported. Extraction must use a fresh
isolated `HERMES_HOME` and record exactly which bundled plugin routes mounted,
so a developer's installed plugins cannot change the spec. It must inspect
the imported app after the mount call and fail if expected bundled routes are
missing. User and project plugins are outside the core client.

## Headless release harness

The tag has a root `Dockerfile` and a published image entrypoint layout.
The container uses `/opt/data` as `HERMES_HOME`, with the Hermes checkout at
`/opt/hermes`. The harness can build that Dockerfile at a checked-out tag,
mount an isolated data volume, and invoke the documented `hermes web` CLI.
This has not yet been exercised: the local Docker daemon is unavailable.

A local tagged `uvicorn` process with a temporary `HERMES_HOME` did start.
Both the Swift URLSession and Kotlin Ktor clients connected to `/api/ws`,
advertised server-request capability, and completed the typed `ping` call.
`make live` now repeats this smoke test in CI. It does not yet exercise replay
or the Docker image.

The liveness scenario configures a custom provider against a deterministic
local OpenAI-compatible stub. It creates a session, submits a prompt, waits for
the streamed `message.complete` response, then lists and closes the session
through both clients. The recorder validates each frame against the tagged
Pydantic catalog before writing the fixture. It does not yet cover tool calls,
approvals, clarify, or subagent flows.
It also calls the reviewed `GET /api/profiles/active` and
`GET /api/sessions/empty/count` routes and validates each JSON response against
its overlay. Both mobile clients decode the results and exercise the generated
query parameter during the live scenario.

A separate strict WebSocket probe verifies both client adapters transmit the
public `hermes-gateway-v1` and private ticket subprotocols, with no ticket in
the URL. This found a Kotlin transport bug: an `io.ktor.http.headers` import
created and discarded a standalone headers object. App authentication headers
on the ticket POST were also lost. The transport now appends headers to the
actual Ktor request, uses the planned OkHttp engine by default, and a local
HTTP test checks the authenticated POST. The caller closes an owned transport
after disconnecting to release OkHttp resources.
`make live-ticket` runs both adapters against the probe in CI.

The session lifecycle fixture contains optional result fields explicitly set
to `null`. The model mapping in the implementation plan collapses absent and
null for optional results, so its strict fixture check compares object fields
after removing nulls and separately checks typed decode and model roundtrip.
The recorder validates the original frame with the tagged Pydantic contract.

The coverage gate accepts intentionally free-form method inputs when the
tagged contract models them as JSON values; `prompt.submit.text` is one such
field. It requires method results and event payloads to have complete types.

## Remaining validation

- Verify the exact `hermes web` flags and health endpoint in a running
  tag-pinned container.
- Check whether bundled plugin APIs are present in the isolated app import.
- Extend the stub inference scenarios to approval and tool-call flows.
