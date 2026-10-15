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
scope.launch { gateway.connectionStates.collect(::showStatus) }  // a StateFlow
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
| The app resumes or activates a session with questions still open (after a relaunch, say) | `session.resume` and `session.activate` return them as `open_requests`; the gateway puts each to the server request handler like a live one. Hermes settles an answer by its request id on any socket, so the app needs no `request.answer` path. |
| Replay window exceeded (512 events or 4 MiB per session) | Reports `.replayTruncated`; reload the transcript with `session.history`. |
| Credential rejected (HTTP 401/403 from the ticket request or the upgrade) | Asks the credential to renew once (`HermesAuth.renew`, for example signing in again) and retries at once with the new one. When it cannot renew, stops retrying and reports `.failed(error)`; call `connect()` again once fixed. `NativeSessionAuth` renews by refreshing its tokens, `PasswordSessionAuth` by signing in again. |
| Incompatible server | Stops retrying and reports `.failed(error)`; see [Backend versions](#backend-versions). |
| Hermes is retiring the backend (`GatewayKnownError.backendRetiring`) | Reconnects. Other failures that reuse code 5035 do not. |
| Calls made while reconnecting | Wait for the connection, up to their timeout. |
| Calls in flight when a socket dies | Sent again on the next connection when the frame never left, or when the method only reads state (`HermesGatewayContract.readOnlyMethods`, reviewed in `spec/gateway-read-only.yaml`), all within the call's timeout. Any other call whose response was lost fails with a transport error, because Hermes may already have run it: after `prompt.submit` fails that way, watch the session's events (reconnect replays them) and submit again only if the turn never starts. |
| Slow event consumer | Events and recoveries are buffered per subscriber (Kotlin: per collector); a slow consumer never stalls the socket, its heartbeat or another consumer. Consume or cancel every stream. |
| Large replies | Swift: messages up to 64 MiB (URLSession's default of 1 MiB is raised). Kotlin: the Ktor WebSockets plugin sets no frame limit by default. |
| A call needs a different deadline | `withHermesRequestTimeout(_:operation:)` (Kotlin: `withHermesRequestTimeout(millis) { }`) replaces `requestTimeout` for the calls inside it. |

Sessions created with `close_on_disconnect: true` are torn down by Hermes as soon
as the socket closes; the client reports them as `.unavailable` instead of
rebinding them.

## Profiles

One gateway serves every Hermes profile. `/api/ws` reads no profile from its URL (a `?profile=` query is
ignored; only the dashboard's terminal sockets read one): the profile is a parameter of each call. 211 of
the 237 methods take `profile`, as Hermes' desktop client sends it (`session.create`, `session.resume`,
`session.list`, `config.*`, `groups.*`, `connectors.*`, `projects.*`, `display.*`, `profiles.*`, …);
the others act on the dashboard's active profile or on none. The gateway resumes a reclaimed session in the
profile it was created or resumed with.

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

## The unified gateway runtime

From v0.22.1 a Hermes gateway can serve a session authority (`gateway/session_controls.py`), which answers a
socket on its own listener instead of the legacy dispatcher. The first frame tells them apart:
`gateway.ready`'s payload has `session_authority: true` only on an authority socket, and
`runtime.describe` answers there with the owner's capabilities and the `session.create` parameters it accepts.
The authority serves a strict wire (`hermes-gateway-v1`): Hermes lists its methods as `x-canonical-methods` in
the same OpenRPC document, and the generators expose them under `canonical`, beside the legacy namespaces:

```swift
let described = try await gateway.canonical.runtime.describe(RuntimeDescribeParams())
let snapshot = try await gateway.canonical.session.resume(CanonicalResumeParams(sessionId: id))
_ = try await gateway.canonical.session.mutate(SessionMutateParams(
    sessionId: id, requestId: UUID().uuidString, expectedRevision: snapshot.revision,
    operation: "rename", payload: ["title": .string("Plans")]))
```

Their types keep Hermes' component names: a name the authority serves with a different contract has a
`Canonical` variant (`CanonicalCreateParams` beside `SessionCreateParams`, `CanonicalSnapshot`), and the
authority's own verbs have their own (`SessionMutateParams`, `AdmissionStatus`, `RuntimeDescribeResult`). Every
canonical namespace type is `Canonical<Namespace>Methods`. A legacy gateway answers the authority's own verbs
with `-32601` and shared names with the legacy shapes, so pick the namespace from `session_authority`. The
authority serves other legacy methods with their legacy contracts; those stay in the legacy namespaces.

The authority refuses with `4001` and a reason (`not_found`, `session_busy`, `stale_generation`,
`revision_conflict`, …), sent as the message and as `data.reason`; parameters it refuses before the handler
runs come as `invalid_params` with `data.fields`. `error.refusal` (Kotlin: `HermesGatewayException.RPC.refusal`)
reads that data: `reason`, its reviewed `GatewayRefusalReason` (`known`), `fields`, and `field` and `detail`,
which Hermes sends after v0.22.1 when the check that fired names them. `error.kind` classifies a refusal by its
reviewed reason. `make rest` reads the reasons the authority's modules raise, and per served method the ones
its handler can reach, into `authority` in `spec/out/<ref>/gateway-errors.json`; `refusals` in
`spec/gateway-errors.yaml` gives each reason a kind and pins the list by hash. The per-method lists follow calls
by name, `self`, and the receivers named in `RECEIVERS` (`tools/_render_gateway_errors.py`), so shared helpers
make them generous and calls the analysis cannot resolve can hide a reason.

## Unified runtime: legacy calls on an authority

Hermes v0.22.1, its canaries and `main` add a *session authority*: the gateway's own listener serves a
canonical protocol (`hermes-gateway-v1`) in which sessions are revision-fenced, turns are durable
admissions and prompts are events. A standalone dashboard (`hermes dashboard`, the usual `:9119`), v0.21.x
and v0.22.2 keep the legacy dispatcher. Both runtimes speak to either: they detect the authority per
connection and translate the generated legacy calls, so apps keep calling `session.create`,
`prompt.submit` and the rest and decode the same result types.

### Detection

The first frame on every socket is `gateway.ready`; only an authority socket sets
`session_authority: true` in it. The runtime then calls `runtime.describe {}` once per connection for the
create sources and parameters. Without the flag, or when `runtime.describe` answers `-32601`, the
connection is legacy and every frame goes out exactly as before. `HermesGateway.sessionAuthority`
(Kotlin: `sessionAuthority`) reports which one the current connection is.

### State the runtime keeps

Per `(profile, session_id)` (profile `default` when the call names none): the `revision`, the
`execution_generation` (never moving backwards), the `replay_epoch` and the last delivered sequence, the
socket's `subscription_id`, the open prompts by `prompt_id`, and the retained mutations. Snapshots
(`session.create` / `session.resume`), `session.info` and `session.updated` events, event frames
(`execution_generation`) and mutation receipts update it, following Desktop's
`apps/desktop/src/api/canonical-protocol.ts`. A session is *attached* once this socket holds its
subscription; a call that needs one (`prompt.submit`, `approval.respond`, `clarify.respond`,
`session.interrupt`, `image.attach_bytes`, and any call that needs a revision or generation the runtime
does not know yet) resumes the session first.

### What is translated

| App calls | Authority receives |
|---|---|
| `session.create` | `source: "gui"`; `reasoning_effort` → `reasoning`; `fast` → `service_tier` (`priority` / `normal`); keys outside `runtime.describe`'s `session_create.parameters` (`cols`, `close_on_disconnect`, …) dropped; a `request_id` kept per identical create until it is answered, so a retry is deduplicated |
| `session.create` with `messages` / `parent_session_id` (fork) | The parent is attached, the seed's last message is matched to its stored row, then `session.mutate branch {through_message_id, title?}` on the parent and `session.resume` of the child, answered as the child's create result. Model and reasoning picks of the fork are not applied (the child inherits the parent's policy) |
| `session.resume` | `lazy` and `close_on_disconnect` dropped, keys outside the canonical resume contract dropped |
| `session.activate` (also the reconnect rebind) | `session.resume {session_id, profile}` |
| `session.close` | `session.detach {session_id, subscription_id}`; `closed` is `detached` |
| `session.title` | `session.mutate rename {title}` |
| `session.cwd.set` | `session.mutate workspace {cwd}` |
| `session.workspace.move` | `session.mutate workspace {cwd}` on `session_key` |
| `session.compress` | `session.mutate compress {focus?}`, then `session.resume` for the compacted `messages` and `info` |
| `session.undo` | `session.mutate rewind {target_message_id}` on the last user row; `removed` is `rewound_count` |
| `session.branch` | `session.mutate branch {title?}`, then the child is resumed; `count` cannot be expressed and is refused |
| `config.set key: model` (`"<model> --provider <id> [--session]"`) | `session.mutate model {model, provider}`; a guarded target answers `confirm_required` + `confirm_message`, and the resend with `confirm_expensive_model: true` carries the authority's token under the same `request_id` |
| `config.set` with `scope` on any key but `reasoning`, or `yolo` with a scope other than `global` | `scope` dropped |
| `slash.exec` `/model <m>`, `/branch [title]`, `/compress [focus]` | The same `session.mutate`, answered `{type: "exec", output}` |
| `session.usage` | `session.stats`, mapped onto the legacy counters (no cost or credit lines) |
| `session.interrupt`, `session.redirect`, `session.steer` | `execution_generation` added |
| `prompt.submit` | `submission_id` added (one per call, kept across resends of the same frame); keys outside the canonical submit contract dropped; `status: started` reads as `streaming` |
| `prompt.submit` with `truncate_before_row_id` (edit, rewind) | `session.mutate rewind {target_message_id}`, then the plain submit |
| `approval.respond {request_id, choice}` | `{prompt_id, choice, execution_generation}`; `resolved` is 1 for `resolved`, 0 for `already_resolved` |
| `clarify.lock {request_id, answer}` and `request.answer {id, result: {answer}}` for a prompt this socket was shown | `clarify.respond {prompt_id, answer, execution_generation}`; `status` is `ok` or `expired` |
| `session.events.since` | `replay_epoch` and `last_sequence` added |
| `session.list` | `limit` clamped to 1…200, unknown keys dropped |
| `commands.catalog` | `session_id` dropped |
| `reload.mcp` | `confirm: true` added |

Mutations follow Desktop's retry rules: one `request_id` per intent, kept with its `expected_revision` and
`expected_generation` while its outcome is unknown, so a retry after a lost reply is an exact retry; a
mutation on another subject retires the retained ones; an acknowledged revision retires older
value-setting edits (`rename`, `archive`, `model`, `reasoning`, `workspace`); `revision_conflict` (and
`session_busy` on `rewind` / `workspace`) retires the refused one, and the runtime then re-reads the
revision before the next mutation of that session. `prompt.submit` retires every retained mutation of its
session.

Results are given back in the legacy shapes the generated types decode: a `CanonicalSnapshot` becomes a
create / resume / activate result (`session_id`, `stored_session_id`, `messages`, `message_count`,
`running`, `info` with `running` and `stored_session_id`, `pending_approval` from the open approval). A
legacy field the authority has no equivalent for (`resumed`, `session_key`, `open_requests`, `todo_state`,
`user_row_id`, `survivor_*`, cost lines, `desktop_contract`) stays absent.

### Prompts and events

`approval.request` and `clarify.request` events reach the server request handler as `ServerRequest.approval`
(`request_id` = `prompt_id`, `choices` as offered) and `ServerRequest.clarify` (one `question` per
prompt). The handler's answer is sent as `approval.respond` / `clarify.respond`; `approval.settled`,
`clarify.settled` and a later answer cancel the handler's task like `request.cancel`. Open prompts in a
snapshot are delivered the same way, as `open_requests` were. A handler that throws leaves the prompt open
(the authority has no "declined" answer). `sudo` and `secret` remain server requests and are answered as
before. `session.updated` with `operation: rename` is followed by a legacy `session.title` event. Every
event is still delivered under its own type.

Replay is per session epoch: the watermark belongs to `(session_id, replay_epoch)` and restarts when an
event or snapshot names a new epoch. Reconnect rebinds through `session.resume`, then replays with
`session.events.since {replay_epoch, last_sequence}`; `snapshot_required` or `truncated` is reported as
`.replayTruncated`. `session.replay_gap` (the authority dropped this socket's subscription) is answered by
resuming the session and replaying from the watermark. A session the authority no longer finds is reported
`.unavailable`; the runtime never retries a resume answered `not_found`.

### Refusals

The authority refuses with `4001 {reason, field?, detail?, fields?}`. `HermesGatewayError.refusal`
(Kotlin: `HermesGatewayException.RPC.refusal`) is a `GatewayRefusal(reason, field, detail, fields)` whenever
`error.data.reason` is present. `known` and `kind` read the reason:

| `reason` | `known` | `kind` |
|---|---|---|
| `session_busy` | `.sessionBusy` | `busy` |
| `not_found` | `.sessionNotFound` | `notFound` |
| `invalid_params` | `.invalidParams` | `invalidRequest` |
| `profile_mismatch` | `.profileNotFound` | `notFound` |
| `storage_unavailable` | `.storageUnavailable` | `unavailable` |
| `unknown_method` | `.unknownMethod` | `unsupported` |
| `stale_generation`, `revision_conflict`, `snapshot_required`, `admission_conflict`, `stale_epoch` | — | `conflict` |
| `permission_denied` | — | `forbidden` |
| `runtime_draining`, `runtime_retired` | — | `unavailable` |
| `unsupported_*`, `use_session_mutation_model`, `unsupported_on_authority` | — | `unsupported` |

A call the runtime cannot express on the authority fails before it is sent with `-32601` and reason
`unsupported_on_authority` (`detail` says why): `pdf.attach` and `image.detach` (the sidecar never finds
an authority session), a fork whose seed does not match the parent's stored rows, `session.branch` with
`count`, `session.undo` without a user turn.

### Not translated

`process.stop` needs a `session_id` the legacy call may lack; `session.history` is gone (use the
snapshot's `messages` or REST); `clarify.lock` / `request.answer` for a prompt this socket never saw go to
the legacy sidecar unchanged; `session.list` lists only live (or `scope: stored` gui/cli/tui) sessions;
legacy `ios` / `cadu` rows answer `not_found` on resume and are readable over REST only. REST session
mutations (`PATCH` / `DELETE /api/sessions/{id}`) are not covered here.

### Kotlin specifics

The Kotlin runtime (`kotlin/src/main/kotlin/hermes/api/runtime/SessionAuthority.kt`, wired in
`HermesGateway.kt`) follows the rules above, with these differences:

- `HermesGatewayClient.runtime` is a `GatewayRuntime` with what `runtime.describe` answered (instance,
  profile home, capabilities, create sources and parameters); `sessionAuthority` reads it.
- A snapshot keeps its open prompts as `open_requests` (legacy shape: `id` = `prompt_id`, `method`
  `approval` / `clarify`), because apps that set `automaticallyRestoreServerRequests = false` present them
  through `restoreServerRequests`; an answer to one is sent as `approval.respond` / `clarify.respond`.
  `resumed` and `session_key` stay absent.
- `session.history` is translated: the transcript of a `session.resume` snapshot, as `{count, messages}`.
- `session.list` without `title` or `scope` asks for `scope: "stored"`, the stored history the legacy list
  returned (the authority's default lists only live sessions).
- `command.dispatch` (`name` + `arg`) takes the same `/model`, `/branch` and `/compress` mutations as
  `slash.exec`.
- A seed that does not line up with the parent's rows message by message is cut at the same user turn
  before it is refused.
- `session.usage` events and the `usage` of `message.complete` also carry the legacy counters, and a prompt
  settled while its handler still runs is followed by a legacy `request.cancel`.
- `request.answer` for a server request still open on the socket (`sudo`, `secret`) is sent as its
  JSON-RPC response.
- `kind` reads the generated `GatewayRefusalReason` classification first (for example `profile_mismatch`
  is `forbidden` there).
- Calls the authority deduplicates (retained request ids, submission ids, prompt answers, reads) are resent
  after a lost connection like read-only calls.
- `gradlew authoritySmoke` runs the legacy methods against a live authority (`HERMES_AUTHORITY_URL`, and
  `HERMES_AUTHORITY_TICKET_COMMAND` printing a native session ticket per connection).

## How it is tested

Unit tests drive both runtimes through a fake Hermes: a scripted transport,
network monitor and socket. They cover each row above deterministically
(`swift/Tests/HermesAPITests/HermesGatewayTests.swift`,
`kotlin/src/test/kotlin/hermes/api/HermesGatewayTest.kt`). The same fakes ship to apps in
`HermesAPITesting` and the Kotlin test fixtures, with `FakeGateway` for code written against
`HermesGatewayClient` (see [the integration guide](integration.md#testing-the-app)).

`make live CLIENTS=swift,kotlin,ios,android` runs the same live scenarios
against the pinned Hermes release in four places: macOS, the JVM, an iPhone
simulator and an Android emulator. The clients reach Hermes through a fault
proxy (`harness/faults.py`). The scenarios:
- drop the socket mid-stream and with a clarification open;
- stall it silently;
- restart the server, then prove the resumed session answers a new turn;
- deny and accept approvals, answer a `secret` request, and leave a question open until Hermes
  withdraws it with `request.cancel` (the handler's task must be cancelled);
- connect over HTTPS/WSS with a pinned self-signed certificate, and through a password session's cookie
  on a gated dashboard.

On Android, airplane mode must pause and resume the gateway through
`AndroidNetworkMonitor`. Each client reports what it exercised on the wire, and
that report is the coverage evidence (`coverage/evidence/`).

### Kotlin recovery identity after compression

`GatewaySessionRecovery.Resumed.storedSessionId` reports the resolved stored ID from the resume response: `stored_session_id`, then legacy `session_key`, then the requested ID if both are omitted. The tracked runtime uses the same precedence, so later reconnects resume the resolved conversation. Apps can associate the previously owned stored ID with this resolved ID when restoring per-conversation preferences. Tests exercise consecutive compression aliases, legacy responses, and repeated recovery when the server omits stored identity.

Kotlin apps that register conversation ownership before presenting prompts must set
`HermesGatewayConfiguration.automaticallyRestoreServerRequests = false`. They then
call `restoreServerRequests` after registering the returned runtime owner. The
default remains automatic delivery for clients that do not need this ordering.
Both paths deduplicate settled or withdrawn snapshot requests.

Kotlin `Resumed.snapshot` carries the typed `SessionResumeResult`, including effective model/settings, running state, and pending server requests. Register the replacement runtime owner before presenting requests from that snapshot through `restoreServerRequests`. Apps can compare the recovered settings with a saved conversation choice instead of blindly switching an already matching runtime.
