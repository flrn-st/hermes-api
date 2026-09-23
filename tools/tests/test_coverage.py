import json

from tools.coverage import report
from tools.fetch_spec import ROOT


def test_coverage_accounts_for_every_tagged_operation() -> None:
    data = report("v2026.9.21")
    assert data["total"] == 634
    assert data["summary"]["gateway_method"]["total"] == 219
    assert data["summary"]["server_request"]["total"] == 12
    assert data["summary"]["event"]["total"] == 69
    assert data["summary"]["rest"]["total"] == 334
    assert data["complete"] >= 13
    assert data["summary"]["gateway_method"]["fixture"] >= 6
    assert data["summary"]["server_request"]["complete"] >= 1
    assert data["summary"]["rest"]["fixture"] >= 4
    prompt = next(item for item in data["entries"] if item["name"] == "prompt.submit")
    assert prompt["typed"] and prompt["complete"]
    symbols = json.loads((ROOT / "spec/out/v2026.9.21/generated-rest-symbols.json").read_text())
    assert data["summary"]["rest"]["generated"] == len(symbols)


def test_recorded_system_prompt_is_redacted() -> None:
    fixture = ROOT / "fixtures/v2026.9.21/liveness.jsonl"
    records = [json.loads(line) for line in fixture.read_text(encoding="utf-8").splitlines()]
    protected = [record for record in records if "params.payload.system_prompt" in record.get("redacted_fields", [])]
    assert protected
    assert all(record["frame"]["params"]["payload"]["system_prompt"] == "<redacted>"
               for record in protected)
