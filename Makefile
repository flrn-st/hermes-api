REF ?= $(shell cat spec/current-release.txt)
# Live clients: swift (macOS), kotlin (JVM), ios (simulator), android (connected emulator).
CLIENTS ?= swift,kotlin

.PHONY: gen check-gen rest check-rest live live-ticket record coverage test android apple stress bench

gen:
	uv run --locked python -m tools.fetch_spec --ref $(REF)
	uv run --locked python -m tools.schema_audit --ref $(REF)
	uv run --locked python -m tools.gen_gateway_models --ref $(REF)
	uv run --locked python -m tools.gen_gateway_api --ref $(REF)
	uv run --locked python -m tools.gen_rest_api --ref $(REF)

rest:
	uv run --locked python -m tools.extract_openapi --ref $(REF)
	uv run --locked python -m tools.extract_gateway_errors --ref $(REF)
	uv run --locked python -m tools.apply_overlay --ref $(REF)
	uv run --locked python -m tools.gen_rest_api --ref $(REF)

check-rest:
	uv run --locked python -m tools.apply_overlay --ref $(REF) --check
	uv run --locked python -m tools.gen_rest_api --ref $(REF) --check

check-gen:
	uv run --locked python -m tools.gen_gateway_models --ref $(REF) --check
	uv run --locked python -m tools.gen_gateway_api --ref $(REF) --check
	uv run --locked python -m tools.gen_rest_api --ref $(REF) --check

live:
	uv run --locked python -m harness.live --ref $(REF) --clients $(CLIENTS)

# Large data, long streams, concurrency and degraded networks (see harness/stress.py). SCALE grows the dataset.
SCALE ?= 1
stress:
	uv run --locked python -m harness.stress --ref $(REF) --clients $(CLIENTS) --scale $(SCALE)

# Decoding cost of large payloads in optimized builds, against budgets.
bench:
	swift run -c release --quiet hermes-api-cli bench --fixture fixtures/$(REF)/liveness.jsonl
	cd kotlin && ./gradlew bench --quiet

record:
	uv run --locked python -m harness.live --ref $(REF) --record

test:
	uv run --locked pytest
	uv run --locked ruff check tools harness
	swift build
	swift test --no-parallel
	cd kotlin && ./gradlew check

# Compile the Kotlin library for Android and lint it against minSdk; needs ANDROID_HOME.
android:
	cd android && ./gradlew assembleDebug assembleDebugAndroidTest lintDebug

# Build the Swift library and its test support for iOS and iPadOS devices and simulators; `swift build` covers macOS.
apple:
	for scheme in HermesAPI HermesAPITesting; do for platform in iOS 'iOS Simulator'; do \
		xcodebuild build -quiet -scheme $$scheme -destination "generic/platform=$$platform" \
			-derivedDataPath .build/xcode || exit 1; \
	done; done

coverage:
	uv run --locked python -m tools.coverage --ref $(REF)

live-ticket:
	uv run --locked python -m harness.ticket_probe --ref $(REF)
