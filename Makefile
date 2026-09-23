REF ?= v2026.9.21

.PHONY: gen check-gen test

gen:
	uv run --locked python -m tools.fetch_spec --ref $(REF)
	uv run --locked python -m tools.schema_audit --ref $(REF)
	uv run --locked python -m tools.gen_gateway_models --ref $(REF)
	uv run --locked python -m tools.gen_gateway_api --ref $(REF)

check-gen:
	uv run --locked python -m tools.gen_gateway_models --ref $(REF) --check
	uv run --locked python -m tools.gen_gateway_api --ref $(REF) --check

test:
	uv run --locked pytest
	uv run --locked ruff check tools
	swift build
	swift test
	cd kotlin && ./gradlew check
