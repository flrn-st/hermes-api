REF ?= $(shell cat spec/current-release.txt)

.PHONY: gen check-gen rest check-rest live live-ticket record coverage test

gen:
	uv run --locked python -m tools.fetch_spec --ref $(REF)
	uv run --locked python -m tools.schema_audit --ref $(REF)
	uv run --locked python -m tools.gen_gateway_models --ref $(REF)
	uv run --locked python -m tools.gen_gateway_api --ref $(REF)
	uv run --locked python -m tools.gen_rest_api --ref $(REF)

rest:
	uv run --locked python -m tools.extract_openapi --ref $(REF)
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
	uv run --locked python -m harness.live --ref $(REF)

record:
	uv run --locked python -m harness.live --ref $(REF) --record

test:
	uv run --locked pytest
	uv run --locked ruff check tools harness
	swift build
	swift test
	cd kotlin && ./gradlew check

coverage:
	uv run --locked python -m tools.coverage --ref $(REF)

live-ticket:
	uv run --locked python -m harness.ticket_probe --ref $(REF)
