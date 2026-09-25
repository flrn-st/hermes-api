import json

import pytest

from tools.coverage import report
from tools.fetch_spec import ROOT
from tools.ref_policy import current_release

CURRENT = current_release()


def test_coverage_accounts_for_every_tagged_operation() -> None:
    data = report(CURRENT)
    contract = json.loads((ROOT / f"spec/out/{CURRENT}/openrpc.json").read_text())
    openapi = json.loads((ROOT / f"spec/out/{CURRENT}/openapi.json").read_text())
    expected = {
        "gateway_method": len(contract["methods"]),
        "server_request": len(contract["x-server-requests"]),
        "event": len(contract["x-notifications"]),
        "rest": sum(len(operations) for operations in openapi["paths"].values()),
    }
    # Every tagged operation is accounted for, whatever the release.
    assert {kind: data["summary"][kind]["total"] for kind in expected} == expected
    assert data["total"] == sum(expected.values())
    assert data["complete"] >= 14
    assert data["summary"]["gateway_method"]["fixture"] >= 6
    assert data["summary"]["server_request"]["complete"] >= 2
    assert data["summary"]["rest"]["fixture"] >= 4
    prompt = next(item for item in data["entries"] if item["name"] == "prompt.submit")
    assert prompt["typed"] and prompt["complete"]
    # Reconnect exercises these live, but no recorded scenario calls them, so they lack fixture credit.
    activate = next(item for item in data["entries"] if item["name"] == "session.activate")
    assert activate["live_swift"] and activate["live_kotlin"] and not activate["fixture"]
    symbols = json.loads((ROOT / f"spec/out/{CURRENT}/generated-rest-symbols.json").read_text())
    assert data["summary"]["rest"]["generated"] == len(symbols)


def test_live_gateway_evidence_must_name_contract_items(tmp_path) -> None:
    import shutil

    ref = CURRENT
    for relative in (f"spec/out/{ref}", f"fixtures/{ref}", "coverage/evidence"):
        shutil.copytree(ROOT / relative, tmp_path / relative)
    evidence_path = tmp_path / f"coverage/evidence/{ref}.json"
    evidence = json.loads(evidence_path.read_text())
    evidence["live_gateway"]["swift"]["methods"].append("session.invented")
    evidence_path.write_text(json.dumps(evidence))
    with pytest.raises(ValueError, match="unknown gateway_method"):
        report(ref, tmp_path)


def test_recorded_system_prompt_is_redacted() -> None:
    fixture = ROOT / f"fixtures/{CURRENT}/liveness.jsonl"
    records = [json.loads(line) for line in fixture.read_text(encoding="utf-8").splitlines()]
    protected = [record for record in records if "params.payload.system_prompt" in record.get("redacted_fields", [])]
    assert protected
    assert all(record["frame"]["params"]["payload"]["system_prompt"] == "<redacted>"
               for record in protected)
