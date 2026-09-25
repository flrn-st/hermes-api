import json
from pathlib import Path

from tools.contract_diff import render


def test_reports_wire_removal_and_schema_change(tmp_path: Path) -> None:
    for ref, methods, schema in (
        ("v0.21.4", [{"name": "ping", "result": {"type": "boolean"}}], {"type": "string"}),
        ("v0.21.5", [], {"type": "integer"}),
    ):
        output = tmp_path / "spec/out" / ref
        output.mkdir(parents=True)
        (output / "openrpc.json").write_text(json.dumps({
            "methods": methods, "x-server-requests": [], "x-notifications": [],
            "components": {"schemas": {"Example": schema}},
        }))
    report = render("v0.21.4", "v0.21.5", tmp_path)
    assert "Removed: 1" in report
    assert "`ping`" in report
    assert "`Example`" in report
