import json
from pathlib import Path

import pytest

from tools.ref_policy import current_release
from tools.schema_audit import audit_contract

CURRENT = current_release()


def test_target_release_schema_subset() -> None:
    contract = json.loads(Path(f"spec/out/{CURRENT}/openrpc.json").read_text())
    counts = audit_contract(contract)
    assert counts["discriminated_oneOf"] >= 1
    assert counts["multi_type_anyOf"] > 0
    assert counts["freeform"] > 0


def test_unknown_construct_fails_closed() -> None:
    contract = {
        "components": {"schemas": {"Broken": {"allOf": [{"type": "string"}]}}},
        "methods": [], "x-server-requests": [], "x-notifications": [],
    }
    with pytest.raises(ValueError, match="unsupported"):
        audit_contract(contract)


def _contract(schema: dict) -> dict:
    return {"components": {"schemas": {"Params": {"type": "object", "properties": {"value": schema}}}},
            "methods": [], "x-server-requests": [], "x-notifications": []}


def test_value_constraints_on_strings_and_arrays_are_supported() -> None:
    counts = audit_contract(_contract({"type": "string", "pattern": "^[a-z]+$", "minLength": 1, "maxLength": 9}))
    assert counts["pattern"] == counts["minLength"] == counts["maxLength"] == 1
    counts = audit_contract(_contract({"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 5}))
    assert counts["minItems"] == counts["maxItems"] == 1


@pytest.mark.parametrize("schema", [
    {"type": "integer", "pattern": "^1$"},
    {"type": "string", "pattern": "("},
    {"type": "string", "minLength": -1},
    {"type": "array", "items": {"type": "string"}, "maxItems": "2"},
    {"type": "integer", "minimum": 0},
])
def test_misplaced_or_unmodelled_constraints_fail_closed(schema: dict) -> None:
    with pytest.raises(ValueError):
        audit_contract(_contract(schema))
