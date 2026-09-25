# HermesAPI — Implementation Plan

Typed, generated Swift and Kotlin clients for the Hermes Agent backend, kept in sync with
published Hermes releases, with 100% API and test coverage. Written for an implementing agent.

> **Execution policy (2026-09-23):** Track stable published tags only; do not generate from or
> release against `main` or other development branches. The counts below describe an upstream
> `main` snapshot and are not the `v2026.9.21` contract. The target tag has 219 methods, 12
> server requests, 69 notifications, 624 schemas, and desktop contract 7. See
> `docs/findings.md` for source-checked details. Later sections mentioning branch builds or
> `main` are deferred until explicitly requested.

- **Working repo name:** `hermes-api` (may become an official NousResearch project — keep it
  generic, no Cadu-specific code, MIT licensed like Hermes).
- **Module name on both platforms:** `HermesAPI` (Swift module), `hermes-api` (Kotlin artifact),
  package `hermes.api` (interim; change if upstreamed).
- **First Hermes release to target:** `v0.21.4`, tag `v2026.9.21` (first contract-carrying release is `v0.21.3`, tag `v2026.9.14`).

---

## 1. Background and verified facts

Verified against upstream `NousResearch/hermes-agent` `main` @ `aaf0fd9` (2026-09-22, pyproject
`0.21.4`) unless noted.

### WebSocket / JSON-RPC gateway — already fully contracted upstream

- Source of truth: Pydantic models in `tui_gateway/contracts/*.py` (~7.4k LOC), registry in
  `tui_gateway/contracts/registry.py`. Added in PR #110522 (2026-09-14).
- Generated artifact: `apps/shared/src/gateway-contract.openrpc.json` (OpenRPC 1.3.2) produced by
  `scripts/gen_gateway_contracts.py`; also generates `apps/shared/src/gateway-contract.generated.ts`.
- Contents: **227 methods**, **12 server→client requests** (`x-server-requests`: `approval`,
  `clarify`, `preview.act`, `preview.read`, `secret`, `sudo`, `terminal.read`, `tour`, `vault.code`,
  `vault.save_login`, `vault.unlock_prompt`, `window.read`), **69 notifications**
  (`x-notifications`), **667 component schemas**. Every method has typed params and result.
- Completeness is enforced upstream: a `@method` handler without a contract fails at import;
  `tests/tui_gateway/contracts/test_generated.py` fails when the committed artifacts are stale.
- Runtime validation: unknown param keys → JSON-RPC error `4000` with field path. Results and
  event payloads are validated; with env `HERMES_TEST_ISOLATION` set, violations **raise**
  (`ContractViolation`) instead of logging. Use this in the test harness.
- All models use `extra="forbid"`. Results serialise with `exclude_none=False` — explicit `null`
  is distinct from an absent key.
- The generator supports only a small JSON-Schema subset (and raises on anything else):
  object/properties/required, primitives, enum, const, anyOf-with-null, array/items, `$ref`,
  oneOf + discriminator, additionalProperties. **Our generator must support exactly this subset.**
- Protocol version: `DESKTOP_BACKEND_CONTRACT = 8` in `tui_gateway/server.py`, reported as
  `desktop_contract` in session info payloads.

Wire envelopes (from `tui_gateway/server.py`, `tui_gateway/server_requests.py`):

```jsonc
// client → server call
{"jsonrpc":"2.0","id":<id>,"method":"session.create","params":{...}}
// event notification
{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"…","seq":42,"payload":{...}}}
// server → client request (string id); client answers with {"jsonrpc":"2.0","id":…,"result":{...}}
{"jsonrpc":"2.0","id":"<string>","method":"approval","params":{"session_id":"…", ...}}
// a pending server request is withdrawn with the event "request.cancel" {id, method, reason}
```

`seq` is a per-session monotonic counter; `session.events.since` resumes from a watermark
(`tui_gateway/event_replay.py`).

### REST (dashboard `/api/*`) — not contracted

- FastAPI app in `hermes_cli/web_server.py` + routers in `hermes_cli/web_routers/`.
- `app.openapi()` can be produced **by importing the module — no server, no auth** (verified on
  0.20.5: 259 paths, 300 operations, 108 component schemas, but only **1** operation with a typed
  200 response). Upstream main still has ~9 `BaseModel`s and 4 `response_model`s in the dashboard.
- Plugin routers (`/api/plugins/<name>/…`) are mounted at server start; a plain import likely does
  not include them. Needs investigation (Phase 0).
- There is also an aiohttp `gateway/platforms/api_server.py` (`/v1/*`, OpenAI-compatible). **Out of
  scope** — the mobile apps don't use it.

### Current iOS app (this repo) — for later integration

- Hand-written client: `HermesAgent/Networking/HermesHTTPClient.swift` (+ `+Audio`, `+Kanban`,
  `+Mail`), `HermesAgent/Networking/HermesTransport.swift`, `HermesAgent/Gateway/*`
  (`GatewayClient`, `JSONRPC`, `RPCRequest`, `GatewayEvent` with 19 custom decoders, `EventReplay`),
  models in `HermesAgent/Models/`.
- **Known drift:** the app uses `approval.request`, `clarify.request`, `clarify.expire`,
  `mcp.setup.request`, `mcp.setup.respond`, `tool.progress` — none exist in the current contract
  (approvals/clarify became server→client requests). Likely broken against Hermes ≥ `v2026.9.14`.
- App deployment target iOS 26.0, Swift 6.0 language mode.

---

## 2. Goals and non-goals

**Goals**

1. Swift and Kotlin clients for the gateway (WebSocket JSON-RPC) and dashboard REST API, generated
   from specs derived from Hermes source for every tracked Hermes ref.
2. Identical API shape and names on both platforms, all derived from the spec by one naming
   function.
3. Measured 100% coverage: every method, server request, event and REST operation is typed,
   generated for both languages, and exercised by tests against a real Hermes at that ref.
4. Fully automated: new Hermes tag or branch commit → spec → code → tests → PR → tag.

**Non-goals**

- App/product logic (chat state machine, caching, UI state) — stays in each app.
- The aiohttp `/v1/*` API server.
- Cadu plugin APIs (cadu-mail, cadu-device, secrets vault, browser stream) — separate package later
  (Phase 7), never in `HermesAPI`.
- SSH tunnelling, mosh, WebRTC — the client accepts an injected transport instead.

---

## 3. Fixed technical decisions

| Area | Decision |
|---|---|
| Repo | Single monorepo `hermes-api`; `Package.swift` at root; Kotlin under `kotlin/`. |
| Generator | **In-house, Python 3.12**, Jinja2 templates, one naming module shared by both languages. Consumes OpenRPC (gateway) and OpenAPI (REST). Rationale: identical names across languages (third-party generators differ), tiny schema subset, upstreamable next to `gen_gateway_contracts.py`. |
| Generated code | **Committed** (`Generated/` dirs), never hand-edited, no SwiftPM build plugins. |
| Swift | Swift 6 language mode, strict concurrency complete, **no third-party dependencies** (Foundation + URLSession only). Platforms: iOS 17, macOS 14, watchOS 10, tvOS 17, visionOS 1. Library code is **not** MainActor-isolated. Swift Testing for tests. |
| Kotlin | Kotlin 2.x, JVM library (JVM 17 bytecode; Android minSdk 26), kotlinx.serialization, kotlinx.coroutines (Flow), Ktor client (core + websockets; OkHttp engine by default, engine injectable). kotlin.test + JUnit 5 + kotlinx-coroutines-test + Turbine. Explicit API mode on. |
| Versioning | The Hermes version itself: Hermes Agent `v0.21.4` (released as tag `v2026.9.21`) → HermesAPI `0.21.4`. Same version string for SwiftPM tag and Maven. `spec/refs.yaml` maps each version to its tag. |
| Fixtures | Git LFS (SwiftPM consumers don't fetch LFS objects). |
| REST schema gaps | OpenAPI Overlay 1.0 files in `spec/overlay/rest/`. |
| CI | GitHub Actions with path filters. |

---

## 4. Repository layout

```
hermes-api/
├── Package.swift                     # products: HermesAPI (library), hermes-api-cli (executable, macOS)
├── swift/
│   ├── Sources/HermesAPI/
│   │   ├── Generated/Gateway/        # models, method table, event + server-request enums
│   │   ├── Generated/REST/           # models, operations
│   │   ├── Runtime/                  # hand-written: connection, transport, auth, reconnect, errors, JSONValue
│   │   └── HermesAPI.swift           # public entry points
│   ├── Sources/hermes-api-cli/
│   └── Tests/HermesAPITests/
├── kotlin/                           # Gradle project, same structure: generated/ + runtime/ + test/
├── spec/
│   ├── refs.yaml                     # tracked Hermes refs (tags pattern, branches)
│   ├── overlay/rest/*.yaml           # REST schema overlay (OpenAPI Overlay 1.0)
│   ├── exemptions.yaml               # human-approved coverage exemptions (reason required)
│   └── out/<ref>/                    # openrpc.json, openapi.json (merged), hashes.json, meta.json
├── fixtures/<ref>/                   # LFS: recorded frames/responses per scenario
├── scenarios/                        # scenario definitions (YAML), shared by all test levels
├── tools/                            # Python: extract, merge, generate, coverage, naming
├── harness/                          # Dockerfile/compose, stub LLM, scenario recorder
├── coverage/<ref>.json|.md
├── .github/workflows/
├── CLAUDE.md                         # agent rules (section 10)
└── README.md
```

---

## 5. Generated API shape (both languages)

All names come from `tools/naming.py`. RPC prefix → namespace, remainder → method name (camelCase),
schema names kept verbatim from the contract (`PromptSubmitParams`, `ApprovalRequestParams`).
Multi-segment names (`session.events.since`) → nested namespace or joined camelCase — decide once in
`naming.py`, document it, apply everywhere.

### Swift

```swift
import HermesAPI

let gateway = HermesGateway(configuration: .init(baseURL: url, auth: auth, transport: .urlSession))
try await gateway.connect()

let created = try await gateway.session.create(.init(/* … */))           // typed params → typed result

for await event in gateway.events {                                        // AsyncStream<GatewayEvent>
    switch event.payload {
    case .messageDelta(let delta): …
    case .toolStart(let tool): …
    case .unknown(let type, let raw): …                                    // forward compatible
    }
}

await gateway.setServerRequestHandler { request in                        // typed server→client requests
    switch request {
    case .approval(let params): return .approval(.init(/* decision */))
    default: return try .reject(…)
    }
}

let sessions = try await rest.sessions.list(.init(limit: 50))              // REST, same style
```

### Kotlin

```kotlin
val gateway = HermesGateway(HermesConfig(baseUrl = url, auth = auth, httpClient = client))
gateway.connect()
val created = gateway.session.create(SessionCreateParams(/* … */))
gateway.events.collect { event -> when (val p = event.payload) {
    is GatewayEventPayload.MessageDelta -> …
    is GatewayEventPayload.Unknown -> …
} }
gateway.setServerRequestHandler { request -> when (request) {
    is ServerRequest.Approval -> ServerRequestResult.Approval(/* … */)
    else -> …
} }
```

### Type mapping rules

| Schema | Swift | Kotlin |
|---|---|---|
| object | `public struct X: Codable, Sendable, Hashable` with explicit `CodingKeys` (snake_case) | `@Serializable data class X` with `@SerialName` |
| enum / WireEnum | open enum: known cases + `case unknown(String)` (custom Codable) | `sealed interface` with `data object`s + `Unknown(val raw: String)` (custom serializer) |
| const | literal-typed property, validated on decode | same |
| oneOf + discriminator | `enum` with associated values | `sealed interface` + `@JsonClassDiscriminator` |
| anyOf [T, null], required | `T?` | `T?` |
| anyOf [T, null], optional, in **results/payloads** | `T?` (absent and null collapse) | `T? = null` |
| anyOf [T, null], optional, in **params** | `Patch<T>` (`.absent / .null / .value(T)`) so callers can send explicit null | same as sealed `Patch<T>` |
| additionalProperties: T | `[String: T]` | `Map<String, T>` |
| free-form | `JSONValue` | `JsonElement` |
| event payload `None` | case without associated value | `data object` |

Decoding policy: **tolerant in production** (ignore unknown keys, unknown enum → `unknown`,
unknown event → `.unknown(type, raw)`, unknown server request → auto-reject with JSON-RPC
method-not-found), **strict in tests** (a test helper re-encodes each decoded frame and fails on any
key present in the input but not modelled).

---

## 6. Runtime (hand-written, both languages, same semantics)

- **Transport abstraction** — Swift: `protocol GatewayTransport: Sendable` producing a
  WebSocket connection, and `protocol HTTPTransport: Sendable` for REST; defaults on URLSession.
  Kotlin: injectable Ktor `HttpClient`. Apps inject their own for self-signed trust, cookie jars,
  custom headers, SSH-tunnelled local ports.
- **Auth** — `HermesAuth` protocol: provides request headers/cookies and fetches the WebSocket ticket
  (`/api/auth/ws-ticket` in current app; verify flow in Phase 0).
- **Connection** — Swift `actor`, Kotlin class confined to a coroutine scope. Request-id correlation,
  per-call timeouts, cancellation (cancelling the awaiting task abandons the call), ping/keepalive,
  connection state stream (`connecting / connected / reconnecting(attempt) / disconnected(error)`).
- **Server requests** — dispatch to the registered handler; answer with typed result; cancel the
  handler task on `request.cancel`; no handler → reject.
- **Reconnect + replay** — exponential backoff with jitter; track last `seq` per session; after
  reconnect call `session.events.since` and re-emit replayed events flagged `replayed = true`,
  de-duplicated by `(session_id, seq)`.
- **Errors** — one error type: `transport`, `rpc(code, message, data)`, `decoding(path, underlying)`,
  `timeout`, `cancelled`, `incompatibleServer(contract)`. Known domain codes (e.g. `4000`, `4006`,
  `4015`) exposed as constants.
- **Compatibility** — generated constant `HermesAPI.contractVersion` (from the ref) and
  `supportedContractRange`; the client reads `desktop_contract` from session info and surfaces
  `incompatibleServer`.
- **Logging** — injectable logger protocol; no `print`.

---

## 7. Test and coverage system

### Harness (`harness/`)

- Builds/runs Hermes at a given ref in Docker (reuse upstream Dockerfile if present) with an
  isolated `HERMES_HOME`, `HERMES_TEST_ISOLATION=1`, seeded data (sessions, cron jobs, skills,
  files, profiles).
- **Stub LLM** (`harness/stub_llm/`): OpenAI-compatible server returning scripted streams per
  scenario: plain text, reasoning, tool call, dangerous command (→ `approval` server request),
  clarify, subagent spawn, error.
- **Recorder**: drives scenarios with a minimal Python client (validates every frame against the
  upstream Pydantic contracts), writes `fixtures/<ref>/<scenario>.jsonl`.
- Fails the run on any `ContractViolation` or contract error in Hermes logs.

### Test levels (run in both Swift and Kotlin)

1. **Generator tests** (Python): golden-file tests per schema feature; the full 667-schema contract
   must generate and compile in both languages.
2. **Decode tests**: every fixture frame decodes into its typed model; strict key check; round-trip
   encode → decode equality. Derived automatically from fixtures.
3. **Behaviour tests** (fake transport, scripted from fixtures): call correlation, timeouts,
   cancellation, server-request handling and `request.cancel`, reconnect + replay de-dup.
4. **Live tests** against the harness (`HERMES_LIVE_URL`): the scenario list in `scenarios/` is run
   by both clients.

### Coverage gate (`tools/coverage.py`)

For every item at a ref — 227 methods, 12 server requests, 69 events, every REST operation — record:

| Check | Pass condition |
|---|---|
| typed | schema has no `{}` / free-form result (REST: after overlay) |
| generated | present in Swift and Kotlin output |
| fixture | ≥1 recorded real frame/response |
| decode | decode test passes in both languages |
| live | exercised in ≥1 live scenario in both languages |
| fresh | REST only: handler source hash matches the overlay entry's `x-handler-hash` |

Output `coverage/<ref>.json` + `.md`. CI fails below 100%. Items that genuinely can't run in the
harness (external billing, third-party OAuth) go in `spec/exemptions.yaml` with a reason — **added
only by a human**, reported separately, never silently counted as covered.

---

## 8. Phases

Work in small, reviewable checkpoints (one PR each). Each phase lists its exit criteria.

### Phase 0 — Bootstrap and verification spikes

- Create repo, layout, `CLAUDE.md`, toolchains (Swift, Gradle wrapper, Python via `uv`), CI skeleton
  with path filters, Git LFS for `fixtures/`.
- `spec/refs.yaml` with `v0.21.4: v2026.9.21` only.
- Spikes (write findings to `docs/findings.md`):
  1. Dashboard auth + WebSocket ticket flow at the target ref (read `hermes_cli/web_server.py`,
     `tui_gateway/ws.py`; compare with this app's `HermesAgent/Gateway/GatewayClient.swift`).
  2. How to point Hermes at a custom OpenAI-compatible base URL (stub LLM).
  3. How plugin routers are mounted and how to include them in `app.openapi()` offline.
  4. How to run Hermes headless in Docker at an arbitrary ref.
- **Exit:** empty packages build in CI on both platforms; findings documented.

### Phase 1 — Gateway model generator

- `tools/fetch_spec.py --ref` → `spec/out/<ref>/openrpc.json` + `meta.json` (ref, sha, pyproject
  version, `DESKTOP_BACKEND_CONTRACT`).
- `tools/naming.py`, `tools/gen/` with Swift and Kotlin templates for the mapping in section 5.
  Raise on any unsupported schema construct.
- Generate: models, method table (name → params/result), `GatewayEventPayload`, `ServerRequest` /
  `ServerRequestResult`, namespace accessors.
- **Exit:** full contract generates; `swift build` and `./gradlew build` pass with zero warnings;
  golden tests pass.

### Phase 2 — Gateway runtime

- Implement section 6 for the gateway in Swift and Kotlin; behaviour tests with fake transport.
- `hermes-api-cli` (macOS): `connect`, `call <method> <json>`, `events [--session]`,
  `run-scenario <file>` — the fast, simulator-free loop for agents.
- **Exit:** behaviour tests green in both languages; CLI can create a session and stream a turn
  against a manually started Hermes.

### Phase 3 — Harness, scenarios, fixtures, live tests

- Section 7 harness, stub LLM, recorder, scenarios covering at least: session lifecycle, prompt
  turn with deltas, tool start/complete, approval accept/deny, clarify, request cancel, subagent,
  reconnect + replay, errors.
- **Exit:** `make live REF=v0.21.4` records fixtures and runs live tests for both clients.

### Phase 4 — Coverage to 100% (gateway)

- Implement `tools/coverage.py` and the CI gate.
- Expand scenarios/seed data until every method, server request and event is live-covered or
  explicitly exempted by a human.
- **Exit:** gateway coverage 100% for `v0.21.4`.

### Phase 5 — REST

- `tools/extract_openapi.py --ref`: uv venv at ref, import `hermes_cli.web_server`, mount plugins
  per Phase 0 finding (Hermes' own plugins only), `app.openapi()`, per-operation handler source
  hashes (`inspect.getsource` → sha256) → `hashes.json`.
- `tools/apply_overlay.py`: merge `spec/overlay/rest/*.yaml` → `spec/out/<ref>/openapi.json`.
  Every overlay action carries `x-source: <file>:<line>` and `x-handler-hash`.
- Order of work: operations used by the iOS app first (collect paths from
  `HermesAgent/Networking/HermesHTTPClient*.swift`, `HermesAgent/Kanban/`, `HermesAgent/Mail/`),
  then the rest to 100%.
- Extend the generator for OpenAPI (paths, params, request/response bodies); REST runtime on
  `HTTPTransport`. Streaming/binary endpoints (upload-stream, download) may be hand-written in
  `Runtime/` but still count toward coverage and need tests.
- Harness scenarios + fixtures for REST; same coverage gate.
- **Exit:** REST coverage 100% (or human exemptions) for `v0.21.4`.

### Phase 6 — Version matrix and release automation

- `track-upstream.yml` (cron): detects new Hermes tags / new commits on tracked branches → runs
  fetch/extract → generate → all tests → opens PR on `hermes/<ref>` with breaking-change report
  (`oasdiff` for REST, a JSON diff of methods/events/schemas for OpenRPC) and coverage report.
- Stale REST hashes turn items red → agent PR updates overlay entries.
- On merge: tag `YYYY.MDD.R`, publish Kotlin artifact (GitHub Packages Maven), SwiftPM via tag.
- **Exit:** a new upstream tag produces a green PR and a published release without manual steps.

### Phase 7 — Plugin clients (separate, outside `HermesAPI`)

- In this app repo's `ServerPlugins/*/dashboard/plugin_api.py`: add Pydantic request/response models
  to every route.
- Separate package `CaduAPI` generated with the same toolchain from the plugins' OpenAPI.
- **Exit:** plugin routes typed and generated; not part of `HermesAPI`.

### Phase 8 — iOS app integration (follow-up; separate PRs in hermes-ios)

1. Add `HermesAPI` as a local path package during development.
2. Fix the approval/clarify/mcp.setup drift using generated `ServerRequest` types.
3. Replace `HermesAgent/Gateway/*` wire types with `HermesAPI`, keeping `ChatSession` logic; inject a
   transport that preserves `HermesTransport` behaviour (trust policy, cookies, headers, SSH).
4. Migrate REST area by area behind `HermesHTTPClient`.
5. Switch to the tagged release.

### Phase 9 — Upstream proposal

- Draft an issue for NousResearch proposing official Swift/Kotlin clients generated from
  `tui_gateway/contracts`, and typed REST models in the same style so `app.openapi()` becomes
  complete (overlay shrinks to zero).

---

## 9. Definition of done

- `HermesAPI` builds for all listed Apple platforms and Kotlin JVM/Android, zero warnings.
- Coverage 100% (gateway + REST) for every ref in `refs.yaml`, exemptions human-approved and listed.
- New upstream tag → automated green PR → tagged release, no manual steps.
- Public API identical in naming across Swift and Kotlin (checked by a test comparing generated
  symbol lists from `naming.py` against both outputs).
- README documents installation (SwiftPM, Gradle), usage, transport/auth injection, versioning.

---

## 10. Rules for implementing agents (`CLAUDE.md` in the new repo)

- Never edit anything under `Generated/` or `kotlin/**/generated/`. Fix the generator or the spec.
- Never edit Hermes source. Upstream changes go through separate PR branches against
  `NousResearch/hermes-agent`.
- Swift: no `@unchecked Sendable`, `nonisolated(unsafe)`, `try!`, force unwraps, `print`, or
  MainActor isolation in library code. Kotlin: no `!!`, no `runBlocking` in library code, no
  `GlobalScope`.
- Don't silence failures: no skipped/disabled tests, no widening schemas to free-form to make a
  test pass, no editing fixtures by hand (re-record).
- Coverage exemptions are added by humans only.
- Every REST overlay entry cites `x-source` and `x-handler-hash`.
- Build/test commands: `swift build && swift test`, `./gradlew check`, `make gen REF=…`,
  `make live REF=…`, `make coverage REF=…` — run before every PR.
- Keep PRs to one checkpoint; include the coverage diff in the PR description.

---

## 11. Open questions (resolve in Phase 0, record answers in `docs/findings.md`)

1. Exact WebSocket auth/ticket flow and cookie requirements at the target ref.
2. Stub LLM configuration mechanism.
3. Offline plugin router mounting for `app.openapi()`.
4. Whether upstream ships a Dockerfile usable at arbitrary refs.
5. Final Kotlin package/group ID if the project moves under NousResearch.
6. Which events/methods are unreachable in a harness (candidates for human exemptions).
