# HermesAPI

Generated Swift and Kotlin clients for published [Hermes Agent](https://github.com/NousResearch/hermes-agent) releases. This repository is under construction: the gateway models, method namespaces, notifications, and server requests are generated and compile on both platforms. Both gateway runtimes now support authenticated connections, typed calls, server requests, and reconnect replay. REST has a tested runtime and six reviewed operations, including JSON request bodies and required nullable response fields. Full REST and live coverage are still being built, so this is not yet an app-ready client. The current coverage gate is 18/634 and blocks publishing.

The repository is pinned to a Hermes version: HermesAPI 0.21.4 is generated from and tested against Hermes Agent 0.21.4. Hermes tags its releases by date, so `spec/refs.yaml` maps each version to its release tag (`v0.21.4: v2026.9.21`), and fetching refuses a tag whose `pyproject.toml` states a different version. Release candidates and canaries are never tracked. Pinned data lives under the version (`spec/out/v0.21.4/`, `fixtures/v0.21.4/`).

The source contract for the gateway is Hermes' committed OpenRPC document. The dashboard REST contract uses FastAPI's `app.openapi()` document from the tagged server, supplemented with reviewed overlays where response schemas are missing. The current tag has 334 REST operations; its upstream success response schemas are empty. Generated code is committed and verified against the exact upstream tag. Development branches are outside the current tracking policy.

`tools/fetch_spec.py` pins the OpenRPC artifact and source metadata to the
upstream tag. `tools/schema_audit.py` fails on unknown schema constructs.
`make gen REF=v0.21.4` fetches and regenerates both platforms; `make
check-gen REF=v0.21.4` fails if committed generated sources are stale.
Method naming is defined once in `tools/naming.py`: the first dotted segment
is the namespace and the remainder becomes one camelCase method (for example,
`session.events.since` becomes `session.eventsSince`). The same function will
drive both language generators.

`make rest REF=v0.21.4` imports the tagged Hermes FastAPI app in an
isolated home, records the raw OpenAPI document and each handler's source hash,
then applies reviewed response overlays. `make check-rest REF=v0.21.4`
checks the committed merged document and refuses stale overlay hashes.
`make live REF=v0.21.4` starts the tagged server with an isolated home and
a deterministic local model stub, then runs the Swift and Kotlin gateway
clients through streamed prompt turns, a `clarify` request, and a denied
`approval` request. The clients reach the server through a fault proxy
(`harness/faults.py`). Their reconnect scenarios sever the socket mid-stream
and while a clarification is open, then restart the server process. They
check that replay delivers every event exactly once, that the open request is
delivered again, and that a session lost to a restart is resumed. `CLIENTS` picks where
the scenarios run: `swift` (macOS), `kotlin` (JVM), `ios` (the full Swift
suite in an iPhone simulator), or `android` (an instrumented test on a
connected emulator, from `android/`). `make android` compiles the Kotlin
sources for Android and lints every call against `minSdk` 26. `make apple`
builds the Swift library for iOS and iPadOS devices and simulators. Supported
platforms are iOS/iPadOS 17+, macOS 14+ and Android 8.0 (API 26)+, plus the
JVM. watchOS is not supported: it only allows WebSockets for audio-streaming
and VoIP apps
([TN3135](https://developer.apple.com/documentation/technotes/tn3135-low-level-networking-on-watchos)).
`make live-ticket` verifies both clients send the authenticated ticket as a
WebSocket subprotocol. `make record` records the tagged liveness, prompt turn,
session lifecycle, and REST scenario and runs both fixture decode suites.
`make coverage` writes the per-operation coverage report. The scheduled
release watcher checks published Hermes releases only (reading each new tag's version) and opens an update PR with
contract changes, REST breaking changes, and the coverage report. Publication
is gated on complete evidence for every tracked operation.

See [the gateway client guide](docs/gateway.md) for iOS and Android integration and connection behaviour, [the implementation plan](HermesAPI-Implementation-Plan.md), and [release findings](docs/findings.md).

```sh
swift build && swift test --no-parallel
cd kotlin && ./gradlew check
```
