"""Documents outside the pinned contract generate one Swift module over the HermesAPI REST runtime."""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest
import yaml

from tools.fetch_spec import ROOT
from tools.gen_openapi_client import DECODING_KEY, generate, load, outputs

ITEM = {"type": "object", "additionalProperties": False, "required": ["id"],
        "properties": {"id": {"type": "string"}, "state": {"type": "string", "enum": ["open", "done"]}}}


def _spec(path: Path, paths: dict, schemas: dict | None = None, *, as_json: bool = False) -> Path:
    document = {"openapi": "3.1.0", "info": {"title": path.stem, "version": "1"}, "paths": paths,
                "components": {"schemas": schemas or {}}}
    path.write_text(json.dumps(document) if as_json else yaml.safe_dump(document), encoding="utf-8")
    return path


def _get(ref: str) -> dict:
    return {"get": {"responses": {"200": {"content": {"application/json": {"schema": {"$ref": ref}}}}}}}


def test_documents_merge_into_one_catalog_with_a_namespace_each(tmp_path: Path) -> None:
    first = _spec(tmp_path / "mail.yaml", {"/api/plugins/demo-mail/items": _get("#/components/schemas/Item")},
                  {"Item": ITEM})
    second = _spec(tmp_path / "vault.json", {"/api/plugins/demo_vault/items/{item_id}": {"get": {
        "parameters": [{"name": "item_id", "in": "path", "required": True, "schema": {"type": "string"}}],
        "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Item"}}}}}}}},
        {"Item": ITEM}, as_json=True)
    out = tmp_path / "Generated"
    assert generate([first, second], "DemoCatalog", out, "demo contracts") == 2
    catalog = (out / "DemoCatalog.swift").read_text()
    assert "import HermesAPI" in catalog
    assert "public var demoMail: DemoMailMethods { DemoMailMethods(caller: caller) }" in catalog
    assert "public var demoVault: DemoVaultMethods" in catalog
    assert "        public func items(itemId: String) async throws -> Item {" in catalog
    assert '/api/plugins/demo_vault/items/\\(RESTPath.segment(itemId))' in catalog
    assert "public struct Item: Codable, Sendable, Hashable" in (out / "Models00.swift").read_text()
    assert f'CodingUserInfoKey(rawValue: "{DECODING_KEY}")' in (out / "Support.swift").read_text()
    assert generate([first, second], "DemoCatalog", out, "demo contracts", check=True) == 2
    (out / "Stale.swift").write_text("")
    with pytest.raises(ValueError, match="stale"):
        generate([first, second], "DemoCatalog", out, "demo contracts", check=True)


def test_a_path_or_a_differing_schema_defined_twice_fails(tmp_path: Path) -> None:
    item = _get("#/components/schemas/Item")
    first = _spec(tmp_path / "a.yaml", {"/api/plugins/a/items": item}, {"Item": ITEM})
    again = _spec(tmp_path / "b.yaml", {"/api/plugins/a/items": item}, {"Item": ITEM})
    with pytest.raises(ValueError, match="defined twice"):
        load([first, again])
    other = _spec(tmp_path / "c.yaml", {"/api/plugins/c/items": item}, {"Item": {**ITEM, "required": []}})
    with pytest.raises(ValueError, match="defined differently"):
        load([first, other])


def test_schemas_named_like_runtime_types_get_a_prefix(tmp_path: Path) -> None:
    spec = _spec(tmp_path / "a.yaml", {"/api/plugins/a/patch": _get("#/components/schemas/Patch")}, {"Patch": ITEM})
    _, (operation,) = outputs([spec], "DemoCatalog", tmp_path, "demo")
    assert operation.swift_result == "RESTPatch"


def test_the_support_key_is_the_runtime_decoding_key() -> None:
    runtime = (ROOT / "swift/Sources/HermesAPI/Runtime/HermesREST.swift").read_text()
    match = re.search(r'static let restDecoding = CodingUserInfoKey\(rawValue: "([^"]+)"\)', runtime)
    assert match is not None and match.group(1) == DECODING_KEY


def test_shared_parameters_responses_and_headers_are_inlined(tmp_path: Path) -> None:
    ok = {"description": "Done", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Item"}}}}
    document = {"openapi": "3.1.0", "info": {"title": "t", "version": "1"},
                "paths": {"/api/plugins/demo/devices/{device_id}": {
                    "parameters": [{"$ref": "#/components/parameters/DeviceID"}],
                    "post": {"parameters": [{"$ref": "#/components/parameters/Token"},
                                            {"name": "profile", "in": "query", "schema": {"type": "string"}}],
                             "responses": {"200": {"$ref": "#/components/responses/OK"}}}}},
                "components": {"schemas": {"Item": ITEM}, "responses": {"OK": ok}, "parameters": {
                    "DeviceID": {"name": "device_id", "in": "path", "required": True, "schema": {"type": "string"}},
                    "Token": {"name": "X-Token", "in": "header", "required": True, "schema": {"type": "string"}}}}}
    spec = tmp_path / "demo.json"
    spec.write_text(json.dumps(document), encoding="utf-8")
    files, (operation,) = outputs([spec], "DemoCatalog", tmp_path, "demo")
    assert [(p.name, p.location) for p in operation.params] == [
        ("deviceId", "path"), ("xToken", "header"), ("profile", "query")]
    catalog = files[tmp_path / "DemoCatalog.swift"]
    assert "public func devices(deviceId: String, xToken: String, profile: String? = nil) async throws -> Item" in catalog
    assert 'headers["X-Token"] = xToken' in catalog
