"""Reviewed REST operations become the same public symbols in both languages."""

from __future__ import annotations

import json
from copy import deepcopy

import pytest

from tools.fetch_spec import ROOT
from tools.gen_rest_api import build, generate
from tools.ref_policy import current_release

CURRENT = current_release()


def _document(paths: dict, schemas: dict | None = None) -> dict:
    for methods in paths.values():
        for operation in methods.values():
            operation.setdefault("x-handler-hash", "reviewed")
    return {"paths": paths, "components": {"schemas": schemas or {}}}


def _json(schema: dict, status: str = "200") -> dict:
    return {"responses": {status: {"content": {"application/json": {"schema": schema}}},
                          "422": {"description": "Validation Error"}}}


ITEM = {"type": "object", "additionalProperties": False, "required": ["id", "state"],
        "properties": {"id": {"type": "string"},
                       "state": {"type": "string", "enum": ["open", "done", ""]},
                       "note": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                       "pair": {"type": "array", "prefixItems": [{"type": "string"}, {"type": "integer"}],
                                "minItems": 2, "maxItems": 2}}}


def test_the_pinned_release_generates_every_reviewed_operation() -> None:
    document = json.loads((ROOT / f"spec/out/{CURRENT}/openapi.json").read_text())
    _, operations = build(document, set())
    overlaid = sum(1 for methods in document["paths"].values() for item in methods.values()
                   if "x-handler-hash" in item)
    assert len(operations) == overlaid
    names = {f"{op.namespace}.{op.name}" for op in operations}
    assert len(names) == len(operations)
    assert {"sessions.emptyCount", "profiles.active", "profiles.setActive", "auth.wsTicket",
            "audio.voiceLiveStatus", "sessions.getBySessionId", "web.authLogin"} <= names
    assert generate(CURRENT, check=True) == len(operations)


def test_nested_objects_arrays_enums_tuples_and_maps_become_named_types() -> None:
    document = _document({"/api/items": {"get": _json({"$ref": "#/components/schemas/ItemList"})}}, {
        "Item": ITEM,
        "ItemList": {"type": "object", "additionalProperties": False, "required": ["items", "by_id"],
                     "properties": {"items": {"type": "array", "items": {"$ref": "#/components/schemas/Item"}},
                                    "by_id": {"type": "object", "additionalProperties": {"$ref": "#/components/schemas/Item"}}}},
    })
    graph, (operation,) = build(document, set())
    assert (operation.namespace, operation.name, operation.swift_result) == ("items", "get", "ItemList")
    fields = {field.wire_name: field.type for field in graph.objects["ItemList"].fields}
    assert (fields["items"].swift, fields["items"].kotlin) == ("[Item]", "List<Item>")
    assert (fields["by_id"].swift, fields["by_id"].kotlin) == ("[String: Item]", "Map<String, Item>")
    assert graph.objects["Item"].reject_unknown
    # Strict enums reject unknown values unless the client decodes tolerantly, so they keep an unknown case;
    # odd wire values still get case names.
    assert graph.enums["ItemState"].strict and not graph.enums["ItemState"].closed
    assert graph.tuples["ItemPair"].items[1].kotlin == "Long"


def test_path_parameters_query_and_json_bodies() -> None:
    document = _document({"/api/items/{item_id}/notes": {"post": {
        "parameters": [{"name": "item_id", "in": "path", "required": True, "schema": {"type": "string"}},
                       {"name": "limit", "in": "query", "schema": {"anyOf": [{"type": "integer"}, {"type": "null"}]}}],
        "requestBody": {"required": True, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/NoteCreate"}}}},
        **_json({"$ref": "#/components/schemas/Item"}),
    }}}, {"Item": ITEM, "NoteCreate": {"type": "object", "required": ["text"], "properties": {
        "text": {"type": "string"}, "tag": {"anyOf": [{"type": "string"}, {"type": "null"}]}}}})
    graph, (operation,) = build(document, set())
    assert [(p.wire, p.location, p.required) for p in operation.params] == [
        ("item_id", "path", True), ("limit", "query", False)]
    assert operation.body is not None and operation.body.swift == "NoteCreate"
    # An optional nullable request field can be absent or an explicit null.
    tag = next(field for field in graph.objects["NoteCreate"].fields if field.wire_name == "tag")
    assert tag.patch


def test_non_json_bodies_redirects_and_several_success_statuses() -> None:
    document = _document({
        "/api/files/download": {"get": {"responses": {"200": {"content": {
            "*/*": {"schema": {"type": "string", "format": "binary"}}}}}}},
        "/assets/{name}.css": {"get": {
            "parameters": [{"name": "name", "in": "path", "required": True, "schema": {"type": "string"}}],
            "responses": {"200": {"content": {"text/css": {"schema": {"type": "string"}}}}}}},
        "/auth/login": {"get": {"responses": {"302": {"headers": {"Location": {"schema": {"type": "string"}}}}}}},
        "/api/jobs/fire": {"post": {"responses": {
            "200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Item"}}}},
            "202": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Item"}}}}}}},
    }, {"Item": ITEM})
    _, operations = build(document, set())
    results = {f"{op.namespace}.{op.name}": op.swift_result for op in operations}
    assert results == {"files.download": "RESTBinary", "web.assetsCss": "String",
                       "web.authLogin": "RESTRedirect", "jobs.fire": "JobsFireResult"}


def test_multipart_uploads_become_parameters() -> None:
    document = _document({"/api/files/upload": {"post": {
        "requestBody": {"required": True, "content": {"multipart/form-data": {
            "schema": {"$ref": "#/components/schemas/Body_upload"}}}},
        **_json({"$ref": "#/components/schemas/Item"}),
    }}}, {"Item": ITEM, "Body_upload": {"type": "object", "required": ["file", "path"], "properties": {
        "file": {"type": "string", "contentMediaType": "application/octet-stream"},
        "overwrite": {"type": "boolean", "default": True}, "path": {"type": "string"}}}})
    _, (operation,) = build(document, set())
    assert operation.multipart and operation.body is None
    assert [(p.name, p.swift_type, p.required) for p in operation.params] == [
        ("file", "RESTFile", True), ("path", "String", True), ("overwrite", "Bool", False)]


def test_types_named_like_gateway_models_get_a_prefix() -> None:
    document = _document({"/api/items": {"get": _json({"$ref": "#/components/schemas/Item"})}}, {"Item": ITEM})
    graph, (operation,) = build(document, {"Item"})
    assert operation.swift_result == "RESTItem" and "RESTItem" in graph.objects


def test_method_names_are_unique_per_namespace() -> None:
    reference = {"$ref": "#/components/schemas/Item"}
    path_param = [{"name": "job_id", "in": "path", "required": True, "schema": {"type": "string"}}]
    document = _document({
        "/api/cron/jobs": {"get": _json(reference), "post": _json(reference)},
        "/api/cron/jobs/{job_id}": {"get": {"parameters": path_param, **_json(reference)},
                                    "delete": {"parameters": path_param, **_json(reference)}},
    }, {"Item": ITEM})
    _, operations = build(document, set())
    assert sorted(f"{op.method} {op.name}" for op in operations) == [
        "DELETE deleteJobs", "GET jobs", "GET jobsByJobId", "POST setJobs"]


@pytest.mark.parametrize(("mutate", "message"), [
    (lambda op: op.update(parameters=[{"name": "x", "in": "header", "schema": {"type": "string"}}]), "header parameter"),
    (lambda op: op.update(parameters=[{"name": "x", "in": "query", "schema": {"type": "array"}}]), "parameter type"),
    (lambda op: op.update(requestBody={"content": {"application/xml": {"schema": {}}}}), "request media"),
    (lambda op: op.update(requestBody={"content": {"application/json": {"schema": {"type": "object"}}}}), "named schema"),
    (lambda op: op.update(responses={"422": {}}), "no success response"),
])
def test_unsupported_shapes_fail_generation(mutate, message: str) -> None:
    operation = _json({"$ref": "#/components/schemas/Item"})
    mutate(operation)
    with pytest.raises(ValueError, match=message):
        build(_document({"/api/items": {"get": deepcopy(operation)}}, {"Item": ITEM}), set())
