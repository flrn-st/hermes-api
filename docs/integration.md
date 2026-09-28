# Adopting HermesAPI in an app

This guide covers replacing an app's own Hermes networking with the generated clients, on iOS and Android.
[The gateway guide](gateway.md) and [the REST guide](rest.md) describe each client's behaviour in detail.

## One client pair per dashboard

An app that talks to several Hermes dashboards (instances) keeps one `HermesREST` and one `HermesGateway` per
dashboard. Build both from the same address, credential and transports:

```swift
struct HermesConnection {
    let rest: HermesREST
    let gateway: HermesGateway

    init(instance: Instance, session: URLSession) {
        let address = HermesDashboardAddress { previousFailure in
            try await instance.reachableURL(after: previousFailure)   // LAN, VPN, relay or SSH tunnel
        }
        let http = URLSessionHTTPTransport(session: session)
        let auth = instance.credential(address: address, http: http)  // see "Credentials"
        rest = HermesREST(configuration: .init(address: address, auth: auth, transport: http,
                                               decoding: .tolerant, logger: AppHermesLogger()))
        gateway = HermesGateway(configuration: .init(
            address: address, auth: auth,
            transport: URLSessionGatewayTransport(session: session), httpTransport: http,
            minimumContract: 7,   // server requests arrived with contract 7; the pinned release reports 8
            logger: AppHermesLogger()))
    }
}
```

```kotlin
class HermesConnection(instance: Instance, client: HttpClient) {
    private val address = HermesDashboardAddress { previousFailure -> instance.reachableUri(previousFailure) }
    private val auth = instance.credential(address, client)
    val rest = HermesREST(HermesRESTConfiguration(address, auth, transport = KtorRESTTransport(client),
                                                  decoding = RESTDecoding.Tolerant))
    val gateway = HermesGateway(HermesGatewayConfiguration(
        address = address, auth = auth, transport = KtorGatewayTransport(client),
        networkMonitor = AndroidNetworkMonitor(context), logger = LogcatGatewayLogger()))
}
```

A dashboard has one gateway socket for every profile. The profile is a parameter of each call
(`profile:` on 211 of 237 gateway methods, and a `profile` query on REST routes that take one). `/api/ws`
ignores a `?profile=` query, so there's no need for one socket per profile.

## Credentials

| Dashboard | Credential | Notes |
|---|---|---|
| Loopback, or reached through an SSH tunnel | `LocalTokenAuth(token:)` | Sends `X-Hermes-Session-Token` on REST and `?token=` on the socket. |
| Gated, password provider | `PasswordSessionAuth(address:transport:credentials:)` | Signs in through `POST /auth/password-login`. The session cookie lives in the transport's cookie storage, so REST, the ticket request and the auth itself must share one transport (one `URLSession`, or one Ktor `HttpClient` with `HttpCookies`). The auth signs in again when Hermes rejects the session. |
| Gated, native OAuth | `NativeSessionAuth(address:tokens:onRotate:)` | Bearer tokens from `POST /auth/native/token`, refreshed ahead of expiry and after a 401; persist each pair from `onRotate`. |
| Behind a reverse proxy with its own credential | `StaticHeadersAuth` | Also usable for custom headers on top of cookies. |

Every credential serves both clients. Read secrets lazily, for example from the keychain in the
`credentials` closure, so a changed password takes effect on the next sign-in.

## Transports, trust and tunnels

- **Self-signed or pinned certificates**
  - Swift: create the `URLSession` with a delegate that answers the server-trust challenge, and pass it to both `URLSessionHTTPTransport(session:)` and `URLSessionGatewayTransport(session:)`. The HTTP transport adds its own task delegate, which only refuses redirects; trust challenges still reach your session delegate.
  - Kotlin: configure the Ktor client's engine, for example OkHttp's `sslSocketFactory`.
  - The live harness exercises both platforms against a self-signed TLS front over HTTPS and WSS, and checks that a session without the pin is refused.
- **Redirects:** transports must not follow them, and the default ones don't. Routes that redirect (logout, OAuth) return a `RESTRedirect`.
- **SSH tunnels and failover:** point the address resolver at the tunnel's local URL. `HermesDashboardAddress(resolve:)` runs before every connection attempt and every REST attempt, and receives the previous failure, so the app can reopen a tunnel or switch addresses without rebuilding the clients.
- **Other transports:** `GatewayTransport`, `HTTPTransport` (Kotlin: `GatewayTransport`, `GatewayHTTPTransport`, `RESTTransport`) are small protocols; wrap the default ones to add tracing or route through something other than URLSession or Ktor.

## Chat over the gateway

The gateway reconnects with backoff, reacts to network changes, detects dead sockets, rebinds sessions,
replays missed events without duplicates, re-delivers open server requests, and resumes sessions Hermes
reclaimed. An app deletes its own reconnect policy, ping loop, `session.events.since` bookkeeping and
socket pooling, and keeps only what it shows:

- Subscribe to `events()`, `connectionStates()` and `sessionRecoveries()` before `connect()`. Every call returns an independent stream; consume or cancel each one.
- On `.replayTruncated(sessionID:)`, reload the transcript with `session.history`.
- On `.resumed(previousSessionID:sessionID:storedSessionID:)`, move the UI to the new runtime id.
- On `.reclaimed(...)`, resume by the stored id the next time the session is opened.
- After `prompt.submit` fails with a transport error, watch the events rather than resubmitting; Hermes may already be running the turn.
- Forward the app lifecycle:
  - iOS: `enterBackground()` / `enterForeground()` from the scene phase.
  - Android: `gateway.bindToProcessLifecycle()`.

### Server requests

Hermes asks the app questions mid-turn through server requests. Install one handler per gateway:

```swift
await gateway.setServerRequestHandler { request in
    switch request {
    case .approval(let p): return .approval(ApprovalResult(choice: try await ui.approve(p)))   // .once, .session, .always, .deny
    case .clarify(let p): return .clarify(try await ui.clarify(p))
    case .sudo(let p): return .sudo(ValueResult(value: try await ui.password(for: p)))
    case .secret(let p): return .secret(ValueResult(value: try await ui.secret(p.envVar, prompt: p.prompt)))
    default: throw HermesGatewayError.transport("Not supported by this app")
    }
}
```

- **Which request:** `ServerRequestContext.current` (Kotlin: `currentServerRequest()`) gives the handler Hermes' request id and method, for methods that take it (`clarify.lock`'s `request_id`) and for matching prompts to requests.
- **Relaunching with a question open:** requests that `session.resume` or `session.activate` report as open reach the same handler, so one handler covers live, replayed and resumed requests.
- **After a reconnect**, a request that is still open is delivered again with the same id to a new handler call, and the call before is cancelled. Re-bind the visible prompt to the new call by id.
- **Hermes withdraws a question** (interrupt, timeout, session closed): the gateway cancels the handler's task. Dismiss the prompt when the task is cancelled; `request.cancel` also arrives as an event.
- **The handler throws:** Hermes gets an error at once and stops waiting.
- **Answer kinds:** the answer must be the same kind as the request, otherwise the gateway refuses to send it.
- **Unsupported requests:** answer them with an error rather than leaving them open.
- **Legacy methods:** the `*.respond` methods (`clarify.respond`, `approval.respond`, `sudo.respond`, `secret.respond`) and the `*.request` events of older Hermes releases are gone. Server requests replace them.

## Versions

The package is generated from one Hermes release (`HermesGatewayContract.release`). To keep working with the
releases users actually run:

- **REST:** use `decoding: .tolerant` (Kotlin: `RESTDecoding.Tolerant`). Fields and enum values a newer release adds then decode, and a missing required field still fails.
- **Gateway:** unknown events arrive as `.unknown(type:raw:)`, and unknown enum values as `.unknown`.
  - Lower `minimumContract` to the oldest backend you support, and branch on `backendContract`.
  - A method an older backend lacks fails with `error.known == .unknownMethod`.

## Timeouts, logging and errors

- **Timeouts:** gateway calls default to 120 s, REST attempts to 60 s. `withHermesRequestTimeout(.seconds(300)) { … }` changes it for the calls inside, for example a long `session.compress`. Cancelling the task still ends a request at once.
- **Logging:** pass a `GatewayLogger` to forward diagnostics to the app's own logging. Messages never contain credentials, payloads or response bodies.
  - Swift: `OSLogGatewayLogger` by default.
  - Android: `LogcatGatewayLogger`.
- **Errors:** match on `error.known` or `error.kind` rather than on codes; see [the gateway guide](gateway.md#errors-hermes-answers-with). REST failures are `HermesRESTError`, with `detail` and `isAuthenticationFailure`.

## Keeping generated types at the edge

Keep generated models behind the app's own service layer. Map them to app types at the boundary, so a
Hermes release that changes a model changes one mapping instead of every screen. A migration can then
move one area at a time: keep an existing service's method signatures and replace their bodies with
`rest.<namespace>.<method>(…)` calls and a mapping.

## Testing the app

Link `HermesAPITesting` (Swift) or the test fixtures (`testFixtures("hermes:hermes-api-android:<version>")`):

| Fake | For |
|---|---|
| `FakeGateway` | Code written against `HermesGatewayClient`. Script call results, push events, states and recoveries, and put server requests to the app's handler with `request(_:)`. Records calls and lifecycle. |
| `ScriptedGatewayCaller`, `ScriptedRESTCaller` | Code written against `GatewayMethodCatalog` / `RESTMethodCatalog`. Generated methods encode and decode for real against scripted JSON. |
| `ScriptedGatewaySocket` with `ScriptedGatewayTransport`, `ScriptedNetworkMonitor`, `StaticTicketAuth` | Driving a real `HermesGateway` without a server (reconnects, replay, heartbeats). `GatewayFrames` builds what Hermes sends, and `SentCall` parses what the client sent. |
| `ScriptedHTTPTransport` (Kotlin: `ScriptedRESTTransport`) | Driving a real `HermesREST` (retries, deadlines, authentication). |

Depend on `any HermesGatewayClient` (Kotlin: `HermesGatewayClient`) and `any RESTCalling` /
`RESTMethodCatalog` where a model needs a fake.

## What the package does not cover

- **Plugin routes a dashboard serves beyond Hermes' bundled ones** (`/api/plugins/<name>/…` of third-party plugins): write a client beside HermesAPI, reusing `HermesDashboardAddress`, the credential and the transport.
- **Other WebSocket endpoints** (the kanban plugin's event stream, the display socket): not part of the generated contracts.
- **Methods or routes the pinned release does not have:** `gateway.call(_:params:as:)` (Kotlin: `call` with serializers) reaches any gateway method untyped, and `RESTCalling.send` any route.
