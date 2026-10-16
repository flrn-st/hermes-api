# REST client guide

`HermesREST` calls the Hermes dashboard's HTTP API. Every operation of the pinned release that has a
reviewed response contract is generated in both languages with the same names: the namespace is the
first path segment after `/api/` (`/api/plugins/<plugin>/…` uses the plugin, routes outside `/api/`
live under `web`), and the method is the rest of the static path in camelCase. Path parameters
become labelled arguments; an operation whose name another operation of its namespace already has
gets a verb prefix (`setActive`, `deleteJobs`) or a `By<Parameter>` suffix (`getBySessionId`).

```swift
let rest = HermesREST(configuration: .init(address: HermesDashboardAddress(url), auth: LocalTokenAuth(token: token)))
let sessions = try await rest.sessions.get(limit: 20)
let detail = try await rest.sessions.getBySessionId(sessionId: sessions.sessions[0].id)
try await rest.profiles.setActive(body: .init(name: "work"))
```

```kotlin
val rest = HermesREST(HermesRESTConfiguration(HermesDashboardAddress(uri), LocalTokenAuth(token)))
val sessions = rest.methods.sessions.get(limit = 20)
```

The base URL may carry a path, for a dashboard behind a reverse proxy (`https://host/hermes/`) or a relay
(`https://relay/agents/<id>`): every route is appended to it. The address is resolved before every attempt,
with the previous attempt's failure, so an app can fail over between addresses (see `HermesDashboardAddress`
and [the gateway guide](gateway.md#moving-between-addresses)).

## Authentication

| Deployment | Credential |
|---|---|
| Loopback dashboard (the default) | `LocalTokenAuth(token:)` sends `X-Hermes-Session-Token`. |
| Gated dashboard, native app | `NativeSessionAuth` holds the Bearer tokens from `POST /auth/native/token`. |
| Gated dashboard, password sign-in | `PasswordSessionAuth` signs in through `POST /auth/password-login` and signs in again after a 401; the transport's cookie storage carries the session, so share the transport with the gateway. |
| Gated dashboard, browser session | No credential: the transport's cookie storage carries the session. |
| Reverse proxy credential | `StaticHeadersAuth` (or `headers:` on the configuration for extra headers). |

`NativeSessionAuth` refreshes through `POST /auth/native/refresh` shortly before the access token
expires and after a request answers 401. Concurrent callers share one refresh, a 401 that arrives
after a refresh already happened retries with the new token instead of refreshing again, and
`onRotate` receives every new token pair so the app can persist it (Hermes rotates the refresh token
on every use). The same object authorizes `HermesGateway` connections, so one credential serves both
surfaces. The native sign-in itself is generated: `web.authNativeAuthorize`, `web.authPasswordLogin`
(password providers) and `web.authNativeToken`.

## Session mutations

From v0.22.1 every change to a stored session is fenced. `sessions.patch` (`PATCH
/api/sessions/{session_id}`, in its `SessionRename` body), `sessions.delete` (query) and the session
import (`POST /api/sessions/import`) carry a `request_id` the caller chooses and the `expected_revision` it last read
(`sessions.mutationSnapshot(sessionId:)`, or the `revision` of the previous receipt); a delete also carries
`expected_generation` (the snapshot's `runtime_generation`). Without them Hermes answers 409
`mutation_identity_required`; a stale revision is a 409 too. Sending the same `request_id` again
repeats the stored receipt, so a retry after a lost answer is safe, but a different mutation under a
used id is refused. Each answers a receipt (`operation`, `revision`, `execution_generation`). A deleted id
is retired and cannot be imported again, and importing a session that exists skips it.

A standalone dashboard applies these offline and refuses them while a gateway runtime owns the profile:
409 with `detail` "Exclusive maintenance refused: Gateway runtime already owns profile `<home>`. Drain and
stop the gateway, then retry." The same goes for `POST /api/sessions/bulk-delete`, `DELETE
/api/sessions/empty`, `POST /api/sessions/owner-backfill` and `POST /api/sessions/prune`. Hermes checks the
identity first, so a request without `request_id` / `expected_revision` answers
`mutation_identity_required` whether or not a runtime runs. v0.22.1 offers no route from the dashboard to
the runtime: its `/api/ws` is the legacy dispatcher, its REST does not forward, and session tickets for the
gateway's own listener come only from the owner-only control socket (`gateway.sock`). The gateway's own
listener serves the same REST routes through the session authority, which is how Hermes Desktop edits
sessions (its base URL is the runtime's `api_origin`).

`error.sessionMutationRefusal` (Kotlin: `HermesRESTException.HTTP.sessionMutationRefusal`) reads these
refusals: `.runtimeOwnsProfile(home:)`, `.identityRequired`, `.revisionConflict`, `.sessionBusy`, or
`.other(reason)`.

`HermesSessionEditor` does the whole edit: rename, pin, archive, unread, hide and delete.

```swift
let editor = HermesSessionEditor(rest: rest, gateway: gateway)
let receipt = try await editor.setPinned(true, for: sessionID, profile: "work")
```

```kotlin
val editor = HermesSessionEditor(rest, gateway)
val receipt = editor.setPinned(sessionId, true, profile = "work")
```

It reads the mutation snapshot and sends the edit with a fresh `request_id` and the snapshot's revision
(and generation, for a delete). It sends the same request again when the answer was lost, and reads the
snapshot again once after `revision_conflict`. When the dashboard answers `runtimeOwnsProfile`, the
editor applies the edit through `gateway`, and `receipt.route` says which path did it:

- **The gateway is a session authority (`authority`).** The edit goes as `session.mutate` (`sidebar`,
  or `delete`), with `profile` on the envelope (see [the gateway guide](gateway.md#profiles)).
- **The gateway is the dashboard's own socket (`legacyGateway`).** The edit goes through the legacy
  dispatcher's writers: `session.archive`, `session.set_hidden`, `session.delete` (refused for a session
  live in that dispatcher), and `session.title` for a session this gateway holds live
  (`liveSessionID(storedSessionID:profile:)`). These write `state.db` beside the runtime without its
  revision fence or receipt, the way the dashboard's chats already do. Hermes refuses the same writers
  on an authority socket for that reason (`tui_gateway/ws_legacy_fallback.py`).
- **The edit has no route.** Pin and unread have no legacy verb, a rename needs the session live, and an
  editor without a gateway has no fallback at all. These throw `HermesSessionEditError.runtimeOwnsProfile`
  (Kotlin: `HermesSessionEditException.RuntimeOwnsProfile`).

An authority refuses deleting a session of a profile it serves as a secondary with
`runtime_coordination_required`. That refusal reaches the caller unchanged.

## Timeouts, retries and cancellation

Each attempt has a deadline (`timeout`, 60 s by default) from sending the request to the last byte of
the response. `RESTRetryPolicy` (6 attempts by default) sends a request again:

- `GET` and `HEAD` after a timeout, a lost connection, 429 or 502/503/504;
- any method when the connection could not be established, because Hermes never saw the request.

`withHermesRequestTimeout(_:operation:)` (Kotlin: `withHermesRequestTimeout(millis) { }`) replaces the
deadline for the attempts made inside it. Waits grow exponentially from 250 ms to 8 s with full jitter; a
shorter `Retry-After` wins. A 401 lets
the credential renew once and then retries. `RESTRetryPolicy.none` disables retries. Cancelling the
calling task or coroutine cancels the request; it is never reported as a transport error.

## Errors

| Swift `HermesRESTError` | Kotlin `HermesRESTException` | Meaning |
|---|---|---|
| `.transport` | `Transport` | Hermes could not be reached after the retries allowed. |
| `.timeout` | `Timeout` | No response within the timeout, after the retries allowed. |
| `.http(status:body:)` | `HTTP` | A status the operation does not document as a success. `detail` is FastAPI's message; `isAuthenticationFailure` covers 401 and 403. |
| `.decoding` | `Decoding` | The response does not match the reviewed contract. |

Decoding is strict by default: a closed object with an unknown key, an enum value the contract does not
list, or a missing required key fails, so contract drift surfaces instead of being silently dropped.
An app that must keep working when its users' Hermes is newer than the pinned release sets
`decoding: .tolerant` (Kotlin: `RESTDecoding.Tolerant`): unknown keys are skipped and unlisted enum values
arrive in the enum's `unknown` case (Kotlin: `Unknown`), so fields and values a newer release adds do not
fail the call. A missing required key still fails in both modes, except where an older release
leaves it out: the generated response models decode as optional every field v0.22.1 requires that
v0.21.5 did not declare, did not require or allowed to be null (a variant of an untagged union relaxes
only the fields v0.21.5 did not declare, so its rows still tell it apart; derived from the two contracts by
`tools/gen_rest_api.py`; `spec/rest-compat.yaml` holds the floor and a few manual extras), so Hermes
v0.21.5 and v0.22.0 still read. Objects that the contract leaves open
keep their undeclared keys in `additionalProperties`.

## Results

| Documented response | Result |
|---|---|
| JSON | The generated model (`[Model]`, `[String: Model]` or an optional for arrays, maps and nullable roots). |
| Text (`text/*`) | `String` |
| Binary or several media types | `RESTBinary` (bytes and `Content-Type`) |
| Redirect | `RESTRedirect` (status and `Location`); transports must not follow redirects, and the default ones do not. |
| No body, and every `HEAD` | `Void` / `Unit` |
| Several success statuses | A generated enum with one case per status, such as `CronFireResult.accepted`. |

Uploads (`multipart/form-data`) take `RESTFile` values. Optional nullable request fields are
`Patch<T>`, so `.absent` and an explicit `.null` stay distinct.

## Testing code that uses it

Generated methods take any `RESTCalling` (Kotlin: `RESTCaller`) through `RESTMethodCatalog(caller:)`.
`ScriptedRESTCaller` from `HermesAPITesting` (Kotlin: the test fixtures) answers routes with scripted JSON
and records the requests, and `ScriptedHTTPTransport` (Kotlin: `ScriptedRESTTransport`) drives a real
`HermesREST` with its retries and authentication. See [the integration guide](integration.md#testing-the-app).

## Coverage and evidence

The generated operations, and the reviewed contracts behind them, are verified three ways: the
tagged Hermes test suite validates every response it produces against the contracts
(`tools/contract_gate.py`), `make record` runs `scenarios/rest.yaml` against an isolated tagged server
and validates every response, and both clients run the same scenario live through their generated
methods. See `coverage/<release>.md` for the per-operation state.

## Services beside Hermes

Routes Hermes does not define, such as a dashboard plugin's `/api/plugins/<plugin>/…`, can be
generated into their own Swift module from OpenAPI 3 documents the service owner writes:

```sh
uv run python -m tools.gen_openapi_client --spec mail.yaml --spec vault.yaml \
    --catalog PluginCatalog --swift-out ../app/Sources/PluginAPI/Generated --origin "plugin contracts"
```

Every operation of the documents is generated with the rules above (names, models, strict or
tolerant decoding, `Patch`, several success statuses) into one catalog type, one namespace per
plugin. Parameters may be path, query or header parameters, and may be shared through
`components/parameters` or at the path level. The module imports `HermesAPI` and its methods take
any `RESTCalling`, so `PluginCatalog(caller: rest)` shares an existing `HermesREST` client's
credential, address, retries, deadlines and decoding mode, and `ScriptedRESTCaller` answers it in
tests. The catalog also lists its `operations` and decodes a recorded response to any of them
(`decode(_:_:)`), so a test can check recorded responses against the contract. `--check` fails
when the committed sources are stale. Only Swift is generated.
