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
| Gated dashboard, browser session | No credential: the transport's cookie storage carries the session. |
| Reverse proxy credential | `StaticHeadersAuth` (or `headers:` on the configuration for extra headers). |

`NativeSessionAuth` refreshes through `POST /auth/native/refresh` shortly before the access token
expires and after a request answers 401. Concurrent callers share one refresh, a 401 that arrives
after a refresh already happened retries with the new token instead of refreshing again, and
`onRotate` receives every new token pair so the app can persist it (Hermes rotates the refresh token
on every use). The same object authorizes `HermesGateway` connections, so one credential serves both
surfaces. The native sign-in itself is generated: `web.authNativeAuthorize`, `web.authPasswordLogin`
(password providers) and `web.authNativeToken`.

## Timeouts, retries and cancellation

Each attempt has a deadline (`timeout`, 60 s by default) from sending the request to the last byte of
the response. `RESTRetryPolicy` (6 attempts by default) sends a request again:

- `GET` and `HEAD` after a timeout, a lost connection, 429 or 502/503/504;
- any method when the connection could not be established, because Hermes never saw the request.

Waits grow exponentially from 250 ms to 8 s with full jitter; a shorter `Retry-After` wins. A 401 lets
the credential renew once and then retries. `RESTRetryPolicy.none` disables retries. Cancelling the
calling task or coroutine cancels the request; it is never reported as a transport error.

## Errors

| Swift `HermesRESTError` | Kotlin `HermesRESTException` | Meaning |
|---|---|---|
| `.transport` | `Transport` | Hermes could not be reached after the retries allowed. |
| `.timeout` | `Timeout` | No response within the timeout, after the retries allowed. |
| `.http(status:body:)` | `HTTP` | A status the operation does not document as a success. `detail` is FastAPI's message; `isAuthenticationFailure` covers 401 and 403. |
| `.decoding` | `Decoding` | The response does not match the reviewed contract. |

Decoding is strict: a closed object with an unknown key, an enum value the contract does not list,
or a missing required key fails, so contract drift surfaces instead of being silently dropped.
Objects that the contract leaves open keep their undeclared keys in `additionalProperties`.

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

## Coverage and evidence

The generated operations, and the reviewed contracts behind them, are verified three ways: the
tagged Hermes test suite validates every response it produces against the contracts
(`tools/contract_gate.py`), `make record` runs `scenarios/rest.yaml` against an isolated tagged server
and validates every response, and both clients run the same scenario live through their generated
methods. See `coverage/<release>.md` for the per-operation state.
