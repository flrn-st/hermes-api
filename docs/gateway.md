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
    baseURL: dashboardURL,
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

Depend on `st.flrn.hermes:hermes-api-android`, not the JVM artifact. It adds the
network, lifecycle and Logcat adapters.

```kotlin
val gateway = HermesGateway(HermesGatewayConfiguration(
    baseURI = URI(dashboardUrl),
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

## What the client does for you

| Situation | Behaviour |
|---|---|
| Socket drops | Reconnects with full-jitter backoff (0–250 ms at first, up to 30 s). Rebinds every session it created, replays missed events in order without duplicates, and delivers open server requests again. |
| Network changes (Wi‑Fi ↔ cellular) | Reconnects at once instead of waiting for the old socket to time out. |
| No network | Stops retrying and reports `waitingForNetwork`. Reconnects as soon as a network appears. Nothing polls meanwhile. |
| Silent dead socket (half-open TCP, NAT timeout) | Sends `gateway.ping` after 15 s without inbound traffic. Any inbound frame counts as proof of life, so a streaming turn needs no pings. Reconnects after 45 s of silence. These match Hermes' own clients. |
| App goes to the background | `enterBackground()` lets a streaming turn finish (up to 25 s; on iOS inside `performExpiringActivity`), then closes the socket. No heartbeat or retry runs in the background. Hermes keeps running turns alive. |
| App returns | `enterForeground()` reconnects and replays what was missed. |
| Hermes reclaimed a session (it does so 20 s after its socket closes, and on restart) | Resumes it from storage with `session.resume`. The session continues under a new runtime id, reported as `.resumed(previousSessionID:sessionID:storedSessionID:)`. |
| Replay window exceeded (512 events or 4 MiB per session) | Reports `.replayTruncated`; reload the transcript with `session.history`. |
| Credential rejected (HTTP 401/403), incompatible server | Stops retrying and reports `.failed(error)`; call `connect()` again once fixed. |
| Hermes is retiring the backend (RPC error 5035) | Reconnects. |
| Calls made while reconnecting | Wait for the connection, up to their timeout. A call in flight when a socket dies fails with a transport error: Hermes may or may not have run it, so the client never retries it. |
| Slow event consumer | Events are buffered per subscriber; a slow consumer never stalls the socket or its heartbeat. |
| Large replies | Messages up to 64 MiB (URLSession's default of 1 MiB is raised). |

Sessions created with `close_on_disconnect: true` are torn down by Hermes as soon
as the socket closes; the client reports them as `.unavailable` instead of
rebinding them.

## How it is tested

Unit tests drive both runtimes through a fake Hermes: a scripted transport,
network monitor and socket. They cover each row above deterministically
(`swift/Tests/HermesAPITests/HermesGatewayTests.swift`,
`kotlin/src/test/kotlin/st/flrn/hermes/api/HermesGatewayTest.kt`).

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
