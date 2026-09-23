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

## Remaining validation

- Verify the exact `hermes web` flags and health endpoint in a running
  tag-pinned container.
- Check whether bundled plugin APIs are present in the isolated app import.
- Test the stub inference route with a real prompt, including streaming and
  approval flows.
