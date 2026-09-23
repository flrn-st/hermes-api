# HermesAPI

Generated Swift and Kotlin clients for published [Hermes Agent](https://github.com/NousResearch/hermes-agent) releases. This repository is under construction: the gateway models, method namespaces, notifications, and server requests are generated and compile on both platforms. The concrete connection and REST runtimes are still being built, so this is not yet an app-ready client.

The source contract for the gateway is Hermes' committed OpenRPC document. The dashboard REST contract will use FastAPI's `app.openapi()` document as its base, supplemented with reviewed overlays where response schemas are missing. Generated code is committed and verified against the exact upstream tag. Development branches are outside the current tracking policy.

`tools/fetch_spec.py` pins the OpenRPC artifact and source metadata to the
upstream tag. `tools/schema_audit.py` fails on unknown schema constructs.
`make gen REF=v2026.9.21` fetches and regenerates both platforms; `make
check-gen REF=v2026.9.21` fails if committed generated sources are stale.
Method naming is defined once in `tools/naming.py`: the first dotted segment
is the namespace and the remainder becomes one camelCase method (for example,
`session.events.since` becomes `session.eventsSince`). The same function will
drive both language generators.

See [the implementation plan](HermesAPI-Implementation-Plan.md) and [release findings](docs/findings.md).

```sh
swift build && swift test
cd kotlin && ./gradlew check
```
