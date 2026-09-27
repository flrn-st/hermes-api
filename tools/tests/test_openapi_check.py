"""The recorder's schema check accepts exactly what the reviewed schemas allow."""

from __future__ import annotations

import pytest

from harness.tagside.openapi_check import Validator

COMPONENTS = {
    "Row": {"type": "object", "additionalProperties": False, "required": ["id", "tags"],
            "properties": {"id": {"type": "integer"}, "tags": {"type": "array", "items": {"type": "string"}},
                           "state": {"type": "string", "enum": ["open", "done"]},
                           "note": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                           "pair": {"type": "array", "prefixItems": [{"type": "string"}, {"type": "integer"}],
                                    "minItems": 2, "maxItems": 2}}},
    "Open": {"type": "object", "additionalProperties": {"type": "integer"},
             "properties": {"name": {"type": "string"}}},
}
VALIDATOR = Validator(COMPONENTS)
ROW = {"$ref": "#/components/schemas/Row"}


def test_valid_values_pass() -> None:
    assert VALIDATOR.errors({"id": 1, "tags": [], "state": "open", "note": None, "pair": ["a", 2]}, ROW) == []
    assert VALIDATOR.errors({"name": "x", "count": 3}, {"$ref": "#/components/schemas/Open"}) == []


@pytest.mark.parametrize(("value", "message"), [
    ({"tags": []}, "missing required 'id'"),
    ({"id": 1, "tags": [], "extra": 1}, "unexpected property 'extra'"),
    ({"id": True, "tags": []}, "expected integer"),
    ({"id": 1.5, "tags": []}, "expected integer"),
    ({"id": 1, "tags": [3]}, "$.tags[0]: expected string"),
    ({"id": 1, "tags": [], "state": "gone"}, "is not one of"),
    ({"id": 1, "tags": [], "note": 3}, "matches no anyOf variant"),
    ({"id": 1, "tags": [], "pair": ["a", 2, 3]}, "more than 2 items"),
    ({"id": 1, "tags": [], "pair": [1, 2]}, "$.pair[0]: expected string"),
])
def test_violations_are_reported(value: dict, message: str) -> None:
    assert any(message in error for error in VALIDATOR.errors(value, ROW))


def test_typed_additional_properties_are_checked() -> None:
    errors = VALIDATOR.errors({"name": "x", "count": "3"}, {"$ref": "#/components/schemas/Open"})
    assert errors == ["$.count: expected integer, got str"]


def test_unknown_keywords_fail_closed() -> None:
    with pytest.raises(ValueError, match="Unsupported schema keywords"):
        VALIDATOR.errors(1, {"type": "integer", "multipleOf": 2})
