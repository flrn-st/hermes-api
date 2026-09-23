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
    assert data["complete"] == 3
    assert data["summary"]["gateway_method"]["fixture"] == 3
    symbols = json.loads((ROOT / "spec/out/v2026.9.21/generated-rest-symbols.json").read_text())
    assert data["summary"]["rest"]["generated"] == len(symbols)
