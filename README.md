# HermesAPI

Generated Swift and Kotlin clients for published [Hermes Agent](https://github.com/NousResearch/hermes-agent) releases. This repository is under construction: the gateway models, method namespaces, notifications, and server requests are generated and compile on both platforms. Both gateway runtimes now support authenticated connections, typed calls, server requests, and reconnect replay. REST has a tested runtime and six reviewed operations, including JSON request bodies and required nullable response fields. Full REST and live coverage are still being built, so this is not yet an app-ready client. The current coverage gate is 12/634 and blocks publishing.

The source contract for the gateway is Hermes' committed OpenRPC document. The dashboard REST contract uses FastAPI's `app.openapi()` document from the tagged server, supplemented with reviewed overlays where response schemas are missing. The current tag has 334 REST operations; its upstream success response schemas are empty. Generated code is committed and verified against the exact upstream tag. Development branches are outside the current tracking policy.

`tools/fetch_spec.py` pins the OpenRPC artifact and source metadata to the
upstream tag. `tools/schema_audit.py` fails on unknown schema constructs.
`make gen REF=v2026.9.21` fetches and regenerates both platforms; `make
check-gen REF=v2026.9.21` fails if committed generated sources are stale.
Method naming is defined once in `tools/naming.py`: the first dotted segment
is the namespace and the remainder becomes one camelCase method (for example,
`session.events.since` becomes `session.eventsSince`). The same function will
drive both language generators.

`make rest REF=v2026.9.21` imports the tagged Hermes FastAPI app in an
isolated home, records the raw OpenAPI document and each handler's source hash,
then applies reviewed response overlays. `make check-rest REF=v2026.9.21`
checks the committed merged document and refuses stale overlay hashes.
`make live REF=v2026.9.21` starts the tagged server with an isolated home and
a deterministic local model stub, then runs the Swift and Kotlin gateway
clients through a streamed prompt turn.
`make live-ticket` verifies both clients send the authenticated ticket as a
WebSocket subprotocol. `make record` records the tagged liveness, prompt turn,
session lifecycle, and REST scenario and runs both fixture decode suites.
`make coverage` writes the per-operation coverage report. The scheduled
release watcher checks published Hermes tags only and opens an update PR with
contract changes, REST breaking changes, and the coverage report. Publication
is gated on complete evidence for every tracked operation.

See [the implementation plan](HermesAPI-Implementation-Plan.md) and [release findings](docs/findings.md).

```sh
swift build && swift test
cd kotlin && ./gradlew check
```
