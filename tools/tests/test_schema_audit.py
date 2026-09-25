import json
from pathlib import Path

import pytest

from tools.ref_policy import current_release
from tools.schema_audit import audit_contract

CURRENT = current_release()


def test_target_release_schema_subset() -> None:
    contract = json.loads(Path(f"spec/out/{CURRENT}/openrpc.json").read_text())
    counts = audit_contract(contract)
    assert counts["discriminated_oneOf"] == 1
    assert counts["multi_type_anyOf"] > 0
    assert counts["freeform"] > 0


def test_unknown_construct_fails_closed() -> None:
    contract = {
        "components": {"schemas": {"Broken": {"allOf": [{"type": "string"}]}}},
        "methods": [], "x-server-requests": [], "x-notifications": [],
    }
    with pytest.raises(ValueError, match="unsupported"):
        audit_contract(contract)
