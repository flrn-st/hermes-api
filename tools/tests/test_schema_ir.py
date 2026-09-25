import json
from pathlib import Path

from tools.gen.schema_ir import SchemaGraph
from tools.ref_policy import current_release

CURRENT = current_release()


def test_full_release_resolves_to_named_models() -> None:
    contract = json.loads(Path(f"spec/out/{CURRENT}/openrpc.json").read_text())
    graph = SchemaGraph(contract)
    named = [name for name, schema in contract["components"]["schemas"].items()
             if schema.get("type") == "object" or (schema.get("type") == "string" and "enum" in schema)]
    # Every named object and enum component becomes exactly one generated model.
    assert len(graph.objects) + len([name for name in graph.enums if name in graph.schemas]) == len(named)
    assert len(graph.unions) >= 40
    assert graph.objects["WaitBarrierTarget"].fields[1].type.kotlin in graph.unions
    assert graph.unions["GoalSnapshotWaitBarrier"].discriminator == "type"


def _graph(properties: dict, required: list[str] | None = None) -> SchemaGraph:
    return SchemaGraph({
        "components": {"schemas": {"Model": {"type": "object", "properties": properties, "required": required or []}}},
        "methods": [], "x-server-requests": [], "x-notifications": [],
    })


def test_value_constraints_become_field_documentation() -> None:
    model = _graph({
        "name": {"type": "string", "pattern": "^[a-z]+$", "minLength": 1},
        "ids": {"type": "array", "items": {"type": "string", "pattern": "^x$"}, "maxItems": 3},
        "plain": {"type": "string"},
    }).objects["Model"]
    docs = {field.wire_name: field.constraints for field in model.fields}
    assert docs == {
        "name": "Must match `^[a-z]+$` and have at least 1 character.",
        "ids": "Must have at most 3 items. Each value must match `^x$`.",
        "plain": None,
    }
    assert model.fields[0].type.swift == "String"


def test_integer_const_fields_carry_their_value() -> None:
    model = _graph({"version": {"type": "integer", "const": 1}}, ["version"]).objects["Model"]
    assert model.fields[0].const == 1
    assert model.fields[0].type.kotlin == "Long"


def test_integer_const_outside_a_field_fails_closed() -> None:
    graph_input = {"items": {"type": "array", "items": {"type": "integer", "const": 1}}}
    try:
        _graph(graph_input)
    except ValueError as error:
        assert "integer const" in str(error)
    else:
        raise AssertionError("An integer const in an array must not be silently dropped")


def test_always_null_properties_are_omitted() -> None:
    model = _graph({"viewer_id": {"type": "null", "default": None}, "since": {"type": "number"}}).objects["Model"]
    assert [field.wire_name for field in model.fields] == ["since"]
    assert model.always_null == ("viewer_id",)
