# HermesAPI

Generated Swift and Kotlin clients for published [Hermes Agent](https://github.com/NousResearch/hermes-agent) releases. This repository is under construction; the package currently exposes only release identity, not a usable API client.

The source contract for the gateway is Hermes' committed OpenRPC document. The dashboard REST contract will be extracted from FastAPI and supplemented with reviewed overlays. Generated code will be committed and verified against the exact upstream tag. Development branches are outside the current tracking policy.

`tools/fetch_spec.py` pins the OpenRPC artifact and source metadata to the
upstream tag. `tools/schema_audit.py` fails on unknown schema constructs.
Method naming is defined once in `tools/naming.py`: the first dotted segment
is the namespace and the remainder becomes one camelCase method (for example,
`session.events.since` becomes `session.eventsSince`). The same function will
drive both language generators.

See [the implementation plan](HermesAPI-Implementation-Plan.md) and [release findings](docs/findings.md).

```sh
swift build && swift test
cd kotlin && ./gradlew check
```
