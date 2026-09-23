# Implementation rules

- Generate against published Hermes release tags from `spec/refs.yaml`. Do not track development branches.
- Never edit `Generated/` files directly. Change the generator or spec and regenerate.
- Never edit upstream Hermes source in this repository.
- Swift library: Swift 6 strict concurrency; no `@unchecked Sendable`, `nonisolated(unsafe)`, `try!`, force unwraps, `print`, or MainActor isolation.
- Kotlin library: no `!!`, `runBlocking`, or `GlobalScope`.
- Do not skip tests, weaken a schema to make tests pass, or edit recorded fixtures by hand.
- Coverage exemptions are added by humans only. Every REST overlay entry needs `x-source` and `x-handler-hash`.
- Before a PR, run `swift build && swift test`, `kotlin/gradlew check`, `make gen REF=<tag>`, `make live REF=<tag>`, and `make coverage REF=<tag>` when those targets are available for its phase.
- Keep commits reviewable and include the coverage diff in PR descriptions.
