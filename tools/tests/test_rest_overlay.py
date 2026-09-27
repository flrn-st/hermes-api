"""The overlay refuses stale or unreviewed REST response contracts."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from tools.apply_overlay import apply


def _fixture(root: Path, *, overlay_hash: str = "same") -> None:
    output = root / "spec/out/v0.21.4"
    output.mkdir(parents=True)
    (root / "spec/overlay/rest").mkdir(parents=True)
    raw = {"paths": {"/api/example": {"get": {"responses": {
        "200": {"content": {"application/json": {"schema": {}}}}
    }}}}}
    (output / "openapi.raw.json").write_text(json.dumps(raw), encoding="utf-8")
    (output / "rest-hashes.json").write_text(json.dumps({
        "GET /api/example": {"source": "example.py:4", "handler_sha256": "same"}
    }), encoding="utf-8")
    (root / "spec/overlay/rest/example.yaml").write_text(f"""operations:
  - method: GET
    path: /api/example
    x-source: example.py:4
    x-handler-hash: {overlay_hash}
    response:
      type: object
      required: [ok]
      properties:
        ok: {{type: boolean}}
""", encoding="utf-8")


def test_overlay_applies_and_checks_reproducibly(tmp_path: Path) -> None:
    _fixture(tmp_path)
    assert apply("v0.21.4", root=tmp_path) == {"operations": 1, "overlaid": 1}
    assert apply("v0.21.4", check=True, root=tmp_path) == {"operations": 1, "overlaid": 1}
    output = json.loads((tmp_path / "spec/out/v0.21.4/openapi.json").read_text())
    operation = output["paths"]["/api/example"]["get"]
    assert operation["responses"]["200"]["content"]["application/json"]["schema"]["required"] == ["ok"]
    assert operation["x-handler-hash"] == "same"


def test_overlay_rejects_stale_handler(tmp_path: Path) -> None:
    _fixture(tmp_path, overlay_hash="old")
    with pytest.raises(ValueError, match="Stale handler"):
        apply("v0.21.4", root=tmp_path)


def test_overlay_check_detects_uncommitted_output_change(tmp_path: Path) -> None:
    _fixture(tmp_path)
    apply("v0.21.4", root=tmp_path)
    target = tmp_path / "spec/out/v0.21.4/openapi.json"
    target.write_text("{}")
    with pytest.raises(ValueError, match="stale"):
        apply("v0.21.4", check=True, root=tmp_path)


def _write(root: Path, overlay: str, *, raw: dict | None = None) -> None:
    output = root / "spec/out/v0.21.4"
    output.mkdir(parents=True, exist_ok=True)
    (root / "spec/overlay/rest/contracts").mkdir(parents=True, exist_ok=True)
    raw = raw or {"paths": {"/api/example": {"get": {"responses": {
        "200": {"content": {"application/json": {"schema": {"title": "Response Example"}}}},
        "422": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/HTTPValidationError"}}}},
    }}}}, "components": {"schemas": {"HTTPValidationError": {"type": "object", "properties": {}}}}}
    (output / "openapi.raw.json").write_text(json.dumps(raw), encoding="utf-8")
    (output / "rest-hashes.json").write_text(json.dumps({
        "GET /api/example": {"source": "example.py:4", "handler_sha256": "same", "body_sha256": "body"}
    }), encoding="utf-8")
    (root / "spec/overlay/rest/contracts/example.yaml").write_text(overlay, encoding="utf-8")


ENTRY = """operations:
  - method: GET
    path: /api/example
    x-source: example.py:4
    x-handler-hash: same
"""


def test_overlay_merges_components_and_traces_the_contract(tmp_path: Path) -> None:
    _write(tmp_path, """components:
  Example:
    type: object
    additionalProperties: false
    required: [ok]
    properties:
      ok: {type: boolean}
""" + ENTRY + """    x-contract: {commit: abc, model: Example}
    responses:
      '200':
        description: Successful Response
        content:
          application/json:
            schema: {$ref: '#/components/schemas/Example'}
""")
    apply("v0.21.4", root=tmp_path)
    output = json.loads((tmp_path / "spec/out/v0.21.4/openapi.json").read_text())
    operation = output["paths"]["/api/example"]["get"]
    assert output["components"]["schemas"]["Example"]["required"] == ["ok"]
    assert operation["x-contract"] == {"commit": "abc", "model": "Example"}
    assert set(operation["responses"]) == {"200", "422"}


def test_overlay_documents_redirects_and_binary_bodies(tmp_path: Path) -> None:
    _write(tmp_path, ENTRY + """    responses:
      '302':
        description: To the login form.
        headers:
          Location: {description: Redirect target., schema: {type: string}}
""")
    apply("v0.21.4", root=tmp_path)
    operation = json.loads((tmp_path / "spec/out/v0.21.4/openapi.json").read_text())["paths"]["/api/example"]["get"]
    # The untyped FastAPI default 200 is replaced by what the handler really answers.
    assert set(operation["responses"]) == {"302", "422"}
    _write(tmp_path, ENTRY + """    responses:
      '200':
        content:
          application/zip:
            schema: {type: string, format: binary}
""")
    apply("v0.21.4", root=tmp_path)


def test_overlay_documents_an_undeclared_json_request_body(tmp_path: Path) -> None:
    _write(tmp_path, ENTRY + """    request:
      required: true
      schema:
        type: object
        required: [action]
        properties:
          action: {type: string}
    response:
      type: object
      additionalProperties: false
      properties:
        ok: {type: boolean}
""")
    apply("v0.21.4", root=tmp_path)
    operation = json.loads((tmp_path / "spec/out/v0.21.4/openapi.json").read_text())["paths"]["/api/example"]["get"]
    assert operation["requestBody"]["required"] is True


@pytest.mark.parametrize(("overlay", "message"), [
    ("components:\n  HTTPValidationError: {type: object, properties: {a: {type: string}}}\n" + ENTRY
     + "    response: {type: object, properties: {ok: {type: boolean}}}\n", "clashes"),
    (ENTRY + "    response: {$ref: '#/components/schemas/Missing'}\n", "undefined component"),
    (ENTRY + "    responses:\n      '404': {content: {application/json: {schema: {type: string}}}}\n",
     "only success statuses"),
    (ENTRY + "    responses:\n      '200': {content: {text/html: {schema: {type: object}}}}\n", "Non-JSON body"),
    (ENTRY + "    responses:\n      '302': {description: x}\n", "Location header"),
    (ENTRY + "    responses:\n      '200': {content: {application/json: {schema: {title: X}}}}\n",
     "not meaningfully typed"),
    (ENTRY + "    response: {type: string}\n    responses: {'200': {}}\n", "exactly one"),
    (ENTRY + "    response: {type: string}\n    x-contract: {commit: abc}\n", "x-contract"),
    (ENTRY + "    response: {type: string}\n    x-extra: 1\n", "fields must be"),
])
def test_overlay_rejects_invalid_entries(tmp_path: Path, overlay: str, message: str) -> None:
    _write(tmp_path, overlay)
    with pytest.raises(ValueError, match=message):
        apply("v0.21.4", root=tmp_path)


def test_overlay_never_replaces_an_upstream_schema(tmp_path: Path) -> None:
    raw = {"paths": {"/api/example": {"get": {"responses": {
        "200": {"content": {"application/json": {"schema": {"type": "string"}}}}}}}}}
    _write(tmp_path, ENTRY + "    response: {type: integer}\n", raw=raw)
    with pytest.raises(ValueError, match="replace an upstream response schema"):
        apply("v0.21.4", root=tmp_path)
