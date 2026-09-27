"""Contracts seed overlays only for tag handlers whose body the contract checkout did not change."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

from tools.apply_overlay import apply
from tools.import_contracts import overlay_file, plan, write

COMMIT = "c0ffee"


def _operation(ref: str) -> dict:
    return {"responses": {
        "200": {"description": "Successful Response",
                "content": {"application/json": {"schema": {"$ref": f"#/components/schemas/{ref}"}}}},
        "422": {"description": "Validation Error"},
    }}


def _branch() -> dict:
    return {
        "paths": {
            "/api/items": {"get": _operation("ItemList")},
            "/api/items/{item_id}": {"get": _operation("Item")},
            "/api/other": {"get": {"responses": {"200": {"content": {"application/json": {"schema": {}}}}}}},
        },
        "components": {"schemas": {
            "ItemList": {"title": "ItemList", "type": "object", "additionalProperties": False,
                         "required": ["items"],
                         "properties": {"items": {"title": "Items", "type": "array",
                                                  "items": {"$ref": "#/components/schemas/Item"}}}},
            "Item": {"title": "Item", "type": "object", "additionalProperties": False,
                     "required": ["title"], "properties": {"title": {"title": "Title", "type": "string"}}},
            "Shared": {"type": "object", "properties": {"x": {"type": "string"}}},
        }},
    }


def _tag() -> dict:
    untyped = {"responses": {"200": {"content": {"application/json": {"schema": {}}}}}}
    return {"paths": {"/api/items": {"get": untyped}, "/api/items/{item_id}": {"get": untyped},
                      "/api/other": {"get": untyped}},
            "components": {"schemas": {"Shared": {"type": "object", "properties": {"x": {"type": "string"}}}}}}


def _hashes(body: str = "same") -> tuple[dict, dict]:
    tag = {
        "GET /api/items": {"source": "hermes_cli/web_routers/items.py:10", "handler_sha256": "t1",
                           "body_sha256": "b1"},
        "GET /api/items/{item_id}": {"source": "hermes_cli/web_routers/items.py:20",
                                     "handler_sha256": "t2", "body_sha256": body},
        "GET /api/other": {"source": "plugins/kanban/dashboard/plugin_api.py:5", "handler_sha256": "t3",
                           "body_sha256": "b3"},
    }
    branch = {
        "GET /api/items": {"source": "hermes_cli/web_routers/items.py:12", "handler_sha256": "x1",
                           "body_sha256": "b1"},
        "GET /api/items/{item_id}": {"source": "hermes_cli/web_routers/items.py:24",
                                     "handler_sha256": "x2", "body_sha256": "same"},
        "GET /api/other": {"source": "plugins/kanban/dashboard/plugin_api.py:9", "handler_sha256": "x3",
                           "body_sha256": "b3"},
        "POST /api/new": {"source": "hermes_cli/web_routers/items.py:40", "handler_sha256": "x4",
                          "body_sha256": "b4"},
    }
    return tag, branch


def test_identical_bodies_import_with_tag_provenance() -> None:
    tag_hashes, branch_hashes = _hashes()
    files, report = plan(_tag(), tag_hashes, _branch(), branch_hashes, COMMIT,
                         {"Item": "hermes_cli/web_responses/items.py"}, set(), {})
    entries = files["items"]["operations"]
    assert [entry["path"] for entry in entries] == ["/api/items", "/api/items/{item_id}"]
    assert entries[0]["x-source"] == "hermes_cli/web_routers/items.py:10"
    assert entries[0]["x-handler-hash"] == "t1"
    assert entries[0]["x-contract"] == {"commit": COMMIT, "model": "ItemList"}
    assert set(entries[0]["responses"]) == {"200"}
    # Components travel without Pydantic titles; a property named "title" survives.
    assert files["items"]["components"]["Item"] == {
        "type": "object", "additionalProperties": False, "required": ["title"],
        "properties": {"title": {"type": "string"}}}
    assert report["undocumented"] == ["GET /api/other"]
    assert report["branch_only"] == ["POST /api/new"]


def test_changed_bodies_are_reported_not_imported() -> None:
    tag_hashes, branch_hashes = _hashes(body="edited")
    files, report = plan(_tag(), tag_hashes, _branch(), branch_hashes, COMMIT, {}, set(), {})
    assert [entry["path"] for entry in files["items"]["operations"]] == ["/api/items"]
    assert report["changed"] == ["GET /api/items/{item_id}"]
    # The changed operation's model still arrives, because the imported list references it.
    assert "Item" in files["items"]["components"]


def test_hand_written_overlays_win() -> None:
    tag_hashes, branch_hashes = _hashes()
    corrected = {"type": "object", "additionalProperties": False, "properties": {}}
    files, report = plan(_tag(), tag_hashes, _branch(), branch_hashes, COMMIT, {},
                         {"GET /api/items/{item_id}"}, {"Item": corrected})
    assert [entry["path"] for entry in files["items"]["operations"]] == ["/api/items"]
    assert "Item" not in files["items"].get("components", {})
    assert report["manual"] == ["GET /api/items/{item_id}"]
    assert report["overridden"] == ["Item"]


def test_a_component_that_differs_from_the_tags_fails() -> None:
    tag_hashes, branch_hashes = _hashes()
    tag = _tag()
    tag["components"]["schemas"]["Item"] = {"type": "string"}
    with pytest.raises(ValueError, match="differs from the tag"):
        plan(tag, tag_hashes, _branch(), branch_hashes, COMMIT, {}, set(), {})


def test_imported_overlays_apply_and_reimport_idempotently(tmp_path: Path) -> None:
    tag_hashes, branch_hashes = _hashes()
    output = tmp_path / "spec/out/v0.21.4"
    output.mkdir(parents=True)
    (output / "openapi.raw.json").write_text(json.dumps(_tag()), encoding="utf-8")
    (output / "rest-hashes.json").write_text(json.dumps(tag_hashes), encoding="utf-8")
    files, _ = plan(_tag(), tag_hashes, _branch(), branch_hashes, COMMIT, {}, set(), {})
    stale = tmp_path / "spec/overlay/rest/contracts/removed.yaml"
    stale.parent.mkdir(parents=True)
    stale.write_text("operations: []\n", encoding="utf-8")
    write(files, tmp_path)
    assert not stale.exists()
    first = {path.name: path.read_text() for path in (tmp_path / "spec/overlay/rest/contracts").iterdir()}
    write(files, tmp_path)
    assert first == {path.name: path.read_text() for path in (tmp_path / "spec/overlay/rest/contracts").iterdir()}
    assert apply("v0.21.4", root=tmp_path) == {"operations": 3, "overlaid": 2}
    # A tag handler edited after the import makes the overlay stale.
    tag_hashes["GET /api/items"]["handler_sha256"] = "moved"
    (output / "rest-hashes.json").write_text(json.dumps(tag_hashes), encoding="utf-8")
    with pytest.raises(ValueError, match="Stale handler"):
        apply("v0.21.4", root=tmp_path)
    assert yaml.safe_load(first["items.yaml"])["operations"][0]["method"] == "GET"


@pytest.mark.parametrize(("source", "stem"), [
    ("hermes_cli/web_routers/sessions.py:12", "sessions"),
    ("hermes_cli/web_responses/sessions.py", "sessions"),
    ("hermes_cli/dashboard_auth/routes.py:449", "dashboard_auth"),
    ("hermes_cli/web_server_dashboard.py:188", "web_server_dashboard"),
    ("plugins/kanban/dashboard/plugin_api.py:359", "kanban"),
])
def test_overlay_files_follow_the_handler_module(source: str, stem: str) -> None:
    assert overlay_file(source) == stem
