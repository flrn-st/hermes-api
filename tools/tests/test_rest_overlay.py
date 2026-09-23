"""The overlay refuses stale or unreviewed REST response contracts."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from tools.apply_overlay import apply


def _fixture(root: Path, *, overlay_hash: str = "same") -> None:
    output = root / "spec/out/v2026.9.21"
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
    assert apply("v2026.9.21", root=tmp_path) == {"operations": 1, "overlaid": 1}
    assert apply("v2026.9.21", check=True, root=tmp_path) == {"operations": 1, "overlaid": 1}
    output = json.loads((tmp_path / "spec/out/v2026.9.21/openapi.json").read_text())
    operation = output["paths"]["/api/example"]["get"]
    assert operation["responses"]["200"]["content"]["application/json"]["schema"]["required"] == ["ok"]
    assert operation["x-handler-hash"] == "same"


def test_overlay_rejects_stale_handler(tmp_path: Path) -> None:
    _fixture(tmp_path, overlay_hash="old")
    with pytest.raises(ValueError, match="Stale handler"):
        apply("v2026.9.21", root=tmp_path)


def test_overlay_check_detects_uncommitted_output_change(tmp_path: Path) -> None:
    _fixture(tmp_path)
    apply("v2026.9.21", root=tmp_path)
    target = tmp_path / "spec/out/v2026.9.21/openapi.json"
    target.write_text("{}")
    with pytest.raises(ValueError, match="stale"):
        apply("v2026.9.21", check=True, root=tmp_path)
