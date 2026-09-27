# Gateway client on iOS and Android

The gateway is Hermes' WebSocket JSON-RPC API at `/api/ws`. Chat runs over it:
`prompt.submit` returns at once, the reply streams back as `message.start`,
`message.delta` and `message.complete` events, and Hermes asks the app
questions mid-turn through server requests such as `clarify` and `approval`.

Supported platforms: iOS and iPadOS 17+, macOS 14+, Android 8.0 (API 26)+, and
the JVM. watchOS is not supported: it only allows WebSockets for audio-streaming
and VoIP apps
([TN3135](https://developer.apple.com/documentation/technotes/tn3135-low-level-networking-on-watchos)).

## Wiring it up

### Swift (iOS, iPadOS, macOS)

```swift
let gateway = HermesGateway(configuration: .init(
    address: HermesDashboardAddress(dashboardURL),
    auth: DashboardTicketAuth { ["Authorization": "Bearer \(appToken)"] }
))
await gateway.setServerRequestHandler { request in
    switch request {
    case .clarify(let params): return .clarify(try await askUser(params))
    case .approval(let params): return .approval(try await confirm(params))
    // A thrown error is answered as a JSON-RPC error, so Hermes stops waiting at once.
    default: throw HermesGatewayError.transport("Unsupported request")
    }
}

// Subscribe before connecting; every call returns an independent stream.
Task { for await event in gateway.events() { render(event) } }
Task { for await state in gateway.connectionStates() { showStatus(state) } }
Task { for await recovery in gateway.sessionRecoveries() { handle(recovery) } }

try await gateway.connect()

// In the SwiftUI app, forward the scene phase:
.onChange(of: scenePhase) { _, phase in
    Task {
        switch phase {
        case .background: await gateway.enterBackground()
        case .active: await gateway.enterForeground()
        default: break
        }
    }
}
```

### Android

Depend on `hermes:hermes-api-android`, not the JVM artifact. It adds the
network, lifecycle and Logcat adapters.

```kotlin
val gateway = HermesGateway(HermesGatewayConfiguration(
    address = HermesDashboardAddress(URI(dashboardUrl)),
    auth = DashboardTicketAuth { mapOf("Authorization" to "Bearer $appToken") },
    networkMonitor = AndroidNetworkMonitor(context),
    logger = LogcatGatewayLogger(),
))
gateway.bindToProcessLifecycle()  // once, e.g. in Application.onCreate
gateway.setServerRequestHandler { request -> answer(request) }

scope.launch { gateway.events.collect(::render) }          // collect before connecting
scope.launch { gateway.sessionRecoveries.collect(::handle) }
gateway.connect()
```

The dashboard URL may carry a path (a reverse proxy's or relay's prefix); the ticket request and `/api/ws`
are appended to it.

### Moving between addresses

A dashboard reachable at more than one address (a LAN and a VPN address, say) needs no second client. The
gateway resolves its `HermesDashboardAddress` before every connection attempt and passes the failure that
ended the attempt or connection before, so the app decides where to connect next without losing the
gateway's sessions:

```swift
let address = HermesDashboardAddress { previousFailure in
    previousFailure == nil ? await hosts.current() : await hosts.probe()
}
```

`HermesREST` resolves it the same way before every request attempt, so its retries fail over too.

## What the client does for you

| Situation | Behaviour |
|---|---|
| Socket drops | Reconnects with full-jitter backoff (0–250 ms at first, up to 30 s). Rebinds every session it created, replays missed events in order without duplicates, and delivers open server requests again. |
| Network changes (Wi‑Fi ↔ cellular) | Reconnects at once instead of waiting for the old socket to time out. |
| No network | Stops retrying and reports `waitingForNetwork`. Reconnects as soon as a network appears. Nothing polls meanwhile. |
| Silent dead socket (half-open TCP, NAT timeout) | Sends `gateway.ping` after 15 s without inbound traffic. Any inbound frame counts as proof of life, so a streaming turn needs no pings. Reconnects after 45 s of silence, and only once a ping has gone unanswered, so a pause (a suspended app) never drops a healthy socket unasked. These match Hermes' own clients. |
| App goes to the background | `enterBackground()` lets a streaming turn finish (up to 25 s; on iOS inside `performExpiringActivity`), then closes the socket. No heartbeat or retry runs in the background. Hermes keeps running turns alive. |
| App returns | `enterForeground()` reconnects and replays what was missed. |
| Hermes reclaimed a session (it does so 20 s after its socket closes, and on restart) | Resumes it from storage with `session.resume`. The session continues under a new runtime id, reported as `.resumed(previousSessionID:sessionID:storedSessionID:)`. A turn that was still streaming keeps writing to Hermes' replay buffer under the previous id, so the client keeps delivering its events (under the previous id) until the turn completes: a reply that finished during a long outage still arrives. |
| Replay window exceeded (512 events or 4 MiB per session) | Reports `.replayTruncated`; reload the transcript with `session.history`. |
| Credential rejected (HTTP 401/403 from the ticket request or the upgrade) | Asks the credential to renew once (`HermesAuth.renew`, for example signing in again) and retries at once with the new one. When it cannot renew, stops retrying and reports `.failed(error)`; call `connect()` again once fixed. `NativeSessionAuth` renews by refreshing its tokens. |
| Incompatible server | Stops retrying and reports `.failed(error)`; see [Backend versions](#backend-versions). |
| Hermes is retiring the backend (`GatewayKnownError.backendRetiring`) | Reconnects. Other failures that reuse code 5035 do not. |
| Calls made while reconnecting | Wait for the connection, up to their timeout. |
| Calls in flight when a socket dies | Sent again on the next connection when the frame never left, or when the method only reads state (`HermesGatewayContract.readOnlyMethods`, reviewed in `spec/gateway-read-only.yaml`), all within the call's timeout. Any other call whose response was lost fails with a transport error, because Hermes may already have run it: after `prompt.submit` fails that way, watch the session's events (reconnect replays them) and submit again only if the turn never starts. |
| Slow event consumer | Events are buffered per subscriber; a slow consumer never stalls the socket or its heartbeat. |
| Large replies | Messages up to 64 MiB (URLSession's default of 1 MiB is raised). |

Sessions created with `close_on_disconnect: true` are torn down by Hermes as soon
as the socket closes; the client reports them as `.unavailable` instead of
rebinding them.

## Backend versions

Session results carry Hermes' desktop contract, a number Hermes raises whenever clients need something new
from the backend (v7 turned blocking prompts into server requests, for example). The gateway accepts any
backend reporting `minimumContract` or higher; the default is the contract of the pinned release
(`HermesGatewayContract.desktopContract`), so a newer backend works and an older one fails the call with
`incompatibleServer`. An app that must keep working with older backends lowers `minimumContract` and reads
`backendContract` (the last contract Hermes reported) to leave out what those backends lack.

## Errors Hermes answers with

A refused call throws `HermesGatewayError.rpc(code:message:data:)` (Kotlin: `HermesGatewayException.RPC`).
Hermes reuses codes for unrelated failures (4001 is "session not found" for session methods and an
invalid audio frame for `wake.feed`), so the client classifies each error by code and message against a
reviewed catalog of the pinned release:

- `error.kind` is a `GatewayErrorKind`: what the app can do about it (`invalidRequest`, `unsupported`,
  `notFound`, `conflict`, `busy`, `forbidden`, `unavailable`, `serverError`, or `unknown` for codes
  the release does not answer with).
- `error.known` is a `GatewayKnownError` for errors with a stable meaning, such as `.sessionNotFound`,
  `.sessionBusy`, `.sessionStarting`, `.unknownMethod` or `.backendRetiring`.
- Every generated method's documentation lists the codes its handler can answer with, and their messages.

```swift
do {
    _ = try await gateway.prompt.submit(.init(sessionId: id, text: .string(text)))
} catch let error as HermesGatewayError where error.known == .sessionBusy {
    // Queue the message until the turn finishes.
} catch let error as HermesGatewayError where error.kind == .notFound {
    // The session is gone: resume it by its stored id.
}
```

Hermes declares no errors in its gateway contract, so `make rest` reads them from the tagged handlers into
`spec/out/<ref>/gateway-errors.json`, following decorators, shared helpers and codes passed through
them. `spec/gateway-errors.yaml` classifies every code; each entry pins its message templates by hash,
and generation stops when a release adds, removes or changes a code until it is reviewed again. The live
scenarios provoke refusals on purpose (an unknown method, a wrongly typed parameter, a missing session
and profile) and both clients must classify them as the catalog says.

## How it is tested

Unit tests drive both runtimes through a fake Hermes: a scripted transport,
network monitor and socket. They cover each row above deterministically
(`swift/Tests/HermesAPITests/HermesGatewayTests.swift`,
`kotlin/src/test/kotlin/hermes/api/HermesGatewayTest.kt`).

`make live CLIENTS=swift,kotlin,ios,android` runs the same live scenarios
against the pinned Hermes release in four places: macOS, the JVM, an iPhone
simulator and an Android emulator. The clients reach Hermes through a fault
proxy (`harness/faults.py`). The scenarios:
- drop the socket mid-stream and with a clarification open;
- stall it silently;
- restart the server, then prove the resumed session answers a new turn.

On Android, airplane mode must pause and resume the gateway through
`AndroidNetworkMonitor`. Each client reports what it exercised on the wire, and
that report is the coverage evidence (`coverage/evidence/`).
