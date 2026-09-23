REF ?= v2026.9.21

.PHONY: gen check-gen rest check-rest test

gen:
	uv run --locked python -m tools.fetch_spec --ref $(REF)
	uv run --locked python -m tools.schema_audit --ref $(REF)
	uv run --locked python -m tools.gen_gateway_models --ref $(REF)
	uv run --locked python -m tools.gen_gateway_api --ref $(REF)

rest:
	uv run --locked python -m tools.extract_openapi --ref $(REF)
	uv run --locked python -m tools.apply_overlay --ref $(REF)

check-rest:
	uv run --locked python -m tools.apply_overlay --ref $(REF) --check

check-gen:
	uv run --locked python -m tools.gen_gateway_models --ref $(REF) --check
	uv run --locked python -m tools.gen_gateway_api --ref $(REF) --check

test:
	uv run --locked pytest
	uv run --locked ruff check tools
	swift build
	swift test
	cd kotlin && ./gradlew check
