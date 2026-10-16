# Verification at Hermes `v0.21.4` (tag `v2026.9.21`)

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
per-operation source locations and SHA-256 hashes (the whole handler, and the body without its
decorators so a contract checkout's handler can be compared with the tag's).

The response contracts on the fork's `feat/dashboard-rest-response-models` branch (commit
`3f5b9b1ce2`, ~2,200 commits past the tag) seed the overlays. 309 of 336 handler bodies are
byte-identical at the tag; 27 changed. Identical bodies do not guarantee identical output: running
the tag's own route tests with every response validated against the imported contracts found helpers
that drifted after the tag (session rows gained `_reset_from`/`_branched_from`, kanban tasks
`current_run_started_at`, memory setup info `python_dependencies_declared`, env rows `provider_*`,
local-model jobs pause/resume fields, file routes serving any media type). Those components, and the
27 changed handlers, are hand-written against the tag in `spec/overlay/rest/*.yaml` and override the
imported ones. After the corrections, the tag's tests exercise 186 overlaid operations and every
response validates except those from tests that stub a helper with a fake dict, and the recorded REST
scenario validates every response of the 268 operations it calls.

Six handlers read an undeclared JSON body (`await request.json()`); their overlays carry reviewed
request schemas. `POST /api/providers/oauth/{provider_id}/submit` always answers 400 and stays
undocumented.

Some operations act outside the isolated home: `POST /api/webhooks/enable` (and the gateway
start/stop/restart/migrate routes, WhatsApp/Telegram onboarding apply) restart the gateway through the
host's service supervisor, which restarted a developer machine's installed Hermes gateway during
authoring. The REST scenario never calls them.

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
Pydantic catalog before writing the fixture. A second prompt makes the stub
call `clarify`; the recorder and both mobile clients answer the real server
request with a typed result and verify the completed turn. A third prompt
requests terminal approval for a nonexistent fixture target. Both clients deny
it, and the recorder verifies the resulting turn. The fixture redacts
`session.info.system_prompt` after validating the original frame. Further prompts
approve a command that matches a dangerous pattern (`once`), load a fixture skill
whose required environment variable makes Hermes send a `secret` request (answered
empty, so nothing is stored), and leave a `clarify` open until `session.interrupt`
makes Hermes withdraw it with `request.cancel`. The fixture skill lives in a
`skills.external_dirs` directory: in the Hermes home's `skills/` it would become the
first learning-graph node, which the REST scenario edits as a memory.
It also calls the reviewed `GET /api/audio/voice-live/status`, `GET /api/profiles/active`,
`POST /api/profiles/active`, and `GET /api/sessions/empty/count` routes and
validates each JSON response against its overlay. Both mobile clients decode
the results and exercise generated JSON body and query parameters during the
live scenario.
The voice status handler always includes `reason`, which can be JSON `null`;
both generated decoders preserve that distinction and reject a missing key.

A separate strict WebSocket probe verifies both client adapters transmit the
public `hermes-gateway-v1` and private ticket subprotocols. Both clients also
put the ticket in `?ticket=`, because Hermes before 0.20.6 reads only the query
and refuses the upgrade without it. This found a Kotlin transport bug: an `io.ktor.http.headers` import
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

## Current iOS REST compatibility

A scan of static `/api/*` paths in `hermes-ios` main at `aa0a14d` found 60
paths in `HermesHTTPClient*.swift`; 57 exist in the tagged OpenAPI document.
Three app calls have no route at `v0.21.4`:

- `GET /api/dashboard/runtime`
- `GET /api/actions/gateway-restart/status`
- `POST /api/dashboard/restart`

These calls need a tagged-server replacement or an explicit app compatibility
path before the generated client can replace them. Dynamic path expressions
were excluded from this static comparison.

## Gateway error codes (v0.21.5)

The gateway contract declares no errors. Read from the tagged handlers, the 237 methods answer with
138 distinct codes, and codes do not identify errors on their own:

- 4001 is "session not found" for session methods, but also an invalid audio frame (`wake.feed`), a
  malformed MCP server config and a missing connector slug. 4030 is both "not permitted" and a missing
  `llm.oneshot` template. The runtimes therefore classify by code and message.
- 5035 means "backend is retiring" from dispatch and `prompt.*`, but `model.disconnect` and
  `tools.configure` use it for their own failures. The runtimes used to reconnect on any 5035; they now
  reconnect only on the retiring message.
- The runtimes' hand-written constants named 4015 "session unavailable" and 4006 "incompatible contract".
  At this release 4015 is invalid attachment or browser parameters and 4006 a missing `session_id`,
  `request_id` or read-only credentials. The generated catalog replaced them.
- `session.events.since` without `session_id` answers -32603 "internal error" rather than a
  validation error.
- 20 methods relay codes from a compute host, plugin or connector service; those have no fixed meaning.

## REST at v0.22.1

The contracts come from the fork's `feat/dashboard-rest-response-models-v0.22.1` (`000b6ee1cc`): the
tag plus one commit, every handler body byte-identical to the tag. 333 of 344 operations import from
it; ten stay hand-written for what the contracts still get wrong at the tag: six undeclared JSON
request bodies (the session import's now carries the fence), four file routes that serve any media
type, plugin manifest values Hermes copies unchecked (numbers, null), and the session table's
nullable counters. `POST /api/providers/oauth/{provider_id}/submit` still always answers 400.

The unified runtime changes what the dashboard can do on its own:

- Session reads never initialize the store; they answer 503 until a gateway runtime has created
  `state.db`. The dashboard's `/api/ws` starts that runtime as a process of its own (`gateway run
  --idle-exit`), which ends after ten idle minutes and is not stopped with the dashboard.
- Session mutations are fenced (`request_id`, `expected_revision`, and `expected_generation` for a
  delete, from `GET /api/sessions/{session_id}/mutation-snapshot`); a deleted id is retired and its
  import refused. A standalone dashboard applies them, and the bulk session maintenance routes, only
  while no gateway runtime owns the profile (otherwise 409, exclusive maintenance). A cron trigger
  starts such a runtime.
- `PUT /api/config/raw` refuses duplicate keys (ruamel.yaml replaced PyYAML).
- `POST /api/audio/tts-lease` installs the provider's SDK (edge-tts) on demand; the scenario no longer
  calls it.
- No bundled memory provider declares a config schema or an OAuth flow (holographic and honcho left
  the bundle), and disconnecting an OAuth provider with nothing stored answers 409.
- The subagent roster lists only running children.

The Swift client re-runs a clarification that reaches it again after it answered (the rebind's
`open_requests` and the live re-delivery both carry it), so the reconnect scenario sees it three
times; Kotlin keeps answered ids (`settledSnapshotRequests`). Until the Swift runtime does the same,
`make record` writes the fixture but not the coverage evidence, which needs both clients.

## Session edits behind a standalone dashboard (v0.22.1)

Read at the tag (`297552a9d6`), then checked live on 2026-10-16 against a v0.22.1 canary. The test was
a throwaway `hermes dashboard` on loopback and the gateway's authority listener, with throwaway
sessions only.

- `PATCH` / `DELETE /api/sessions/{id}` and the import go through `_mutate_session_request`
  (`hermes_cli/web_server_sessions.py:182-219`). Without a composed gateway (`app.state.session_authority`
  is `None`, lines 103-109: "its owner cutover is a follow-up") it writes offline under
  `exclusive_maintenance` (lines 222-234). That raises `OwnershipConflict` while a runtime holds the
  profile's `gateway.lock` (`gateway/runtime_ownership.py:165, 235-237`). The answer is 409 with
  `{"detail": "Exclusive maintenance refused: Gateway runtime already owns profile <home>. Drain and stop
  the gateway, then retry."}`, with no code field. The identity check comes first (line 197), so a request
  without `request_id` / `expected_revision` answers `mutation_identity_required` instead.
- The dashboard's `/api/ws` (`hermes_cli/web_routers/chat_ws.py:702-736`) runs the legacy dispatcher in
  process. Without a composed authority it neither proxies nor upgrades, and its REST never forwards.
  `/api/status` reports only `gateway_state`. Session tickets come from the `session-ticket` verb of the
  owner-only control socket (`gateway/control_socket.py:281-323`, `gateway/runtime_bootstrap.py:34-50`),
  and the listener accepts them from loopback peers only (`gateway/run_api.py:296-326`;
  `hermes_cli/dashboard_auth/native_http.py:133-147`). So an app behind the dashboard cannot reach
  the authority.
- The gateway's listener mounts the same dashboard with `session_authority` set
  (`gateway/run_api.py:63-72`), so its REST mutations go through the authority. Desktop uses that
  listener as its REST and WebSocket base (`apps/desktop/electron/local-gateway.ts:197`) and edits
  sessions over REST (`apps/desktop/src/api/sessions.ts:398-427`).
- The legacy dispatcher's own writers still work on the dashboard socket while a runtime owns the
  profile: `session.title` for a live session, `session.archive`, `session.set_hidden` and
  `session.delete` (`tui_gateway/methods_session.py:1172-1300`). They are unfenced, and `runtime_revision`
  does not move. Pin and unread have no legacy verb. On an authority socket Hermes refuses these writers
  (`tui_gateway/ws_legacy_fallback.py:26-36`).
- Canonical verbs take no `profile` parameter, but `AuthorityConnection.dispatch` reads `profile` off
  every frame first (`gateway/session_controls.py:148-160`). The own profile is stripped; another one
  goes to its sibling authority only for a host-scoped grant (lines 61-82), and is refused with
  `profile_mismatch` otherwise. Without it, the session is looked up in the socket's own profile.
  Live results:
  - own profile, with and without `profile`: applied;
  - another profile on a profile-scoped ticket: `profile_mismatch`;
  - another profile's session without `profile`: `not_found`, on both ticket scopes;
  - host-scoped ticket naming the other profile: applied (`rename`, `sidebar`);
  - a home path as `profile`: `profile_mismatch`.

  Desktop puts `profile` on the envelope of every canonical frame
  (`apps/desktop/src/api/canonical-protocol.ts:271-275`).
- Deleting a session of a served secondary profile through the authority answers
  `runtime_coordination_required` (`gateway/session_mutations.py:128-132`). A duplicate title on
  `rename` crashes the handler (`ValueError` in `hermes_state_titles.py`) and answers `-32603 internal
  error` on the canary.

## Remaining validation

- Verify the exact `hermes web` flags and health endpoint in a running
  tag-pinned container.
- Check whether bundled plugin APIs are present in the isolated app import.
- The desktop-only server requests (`terminal.read`, `preview.*`, `window.read`, `tour`) need a
  session on the desktop platform (`HERMES_DESKTOP=1`); the password-manager (`vault.*`) and
  `display.install.sudo` requests need host software, and `sudo` runs the host's real `sudo`.
