# HermesAPI

Generated Swift and Kotlin clients for published [Hermes Agent](https://github.com/NousResearch/hermes-agent) releases. This repository is under construction; the package currently exposes only release identity, not a usable API client.

The source contract for the gateway is Hermes' committed OpenRPC document. The dashboard REST contract will be extracted from FastAPI and supplemented with reviewed overlays. Generated code will be committed and verified against the exact upstream tag. Development branches are outside the current tracking policy.

See [the implementation plan](HermesAPI-Implementation-Plan.md) and [release findings](docs/findings.md).

```sh
swift build && swift test
cd kotlin && ./gradlew check
```
