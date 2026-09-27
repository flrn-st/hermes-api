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
    # The gateway scenario records and runs these in both clients.
    activate = next(item for item in data["entries"] if item["name"] == "session.activate")
    assert activate["live_swift"] and activate["live_kotlin"] and activate["fixture"] and activate["decode"]
    assert data["summary"]["gateway_method"]["live_both"] >= 117
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


def _copy(tmp_path, exemptions: str):
    import shutil

    for relative in (f"spec/out/{CURRENT}", f"fixtures/{CURRENT}", "coverage/evidence"):
        shutil.copytree(ROOT / relative, tmp_path / relative)
    (tmp_path / "spec/exemptions.yaml").write_text(exemptions)
    return tmp_path


OAUTH_SUBMIT = "POST /api/providers/oauth/{provider_id}/submit"


def test_exemptions_count_toward_the_gate_and_leave_the_gaps(tmp_path) -> None:
    root = _copy(tmp_path, "exemptions:\n- kind: rest\n  name: " + OAUTH_SUBMIT +
                 "\n  reason: Always answers 400 at this release.\n  reviewed_by: flrnst\n")
    data = report(CURRENT, root)
    submit = next(item for item in data["entries"] if item["name"] == OAUTH_SUBMIT)
    assert submit["exempt"] == "Always answers 400 at this release."
    assert data["exempt"] == 1 and data["summary"]["rest"]["exempt"] == 1


@pytest.mark.parametrize(("exemption", "problem"), [
    ("- kind: rest\n  name: GET /api/invented\n  reason: x\n  reviewed_by: y\n", "not a surface"),
    ("- kind: rest\n  name: " + OAUTH_SUBMIT + "\n  reason: x\n", "needs a reason and reviewed_by"),
    ("- kind: gateway_method\n  name: prompt.submit\n  reason: x\n  reviewed_by: y\n", "complete now"),
])
def test_exemptions_must_still_apply(tmp_path, exemption: str, problem: str) -> None:
    with pytest.raises(ValueError, match=problem):
        report(CURRENT, _copy(tmp_path, "exemptions:\n" + exemption))


def _with_reviews(tmp_path, exemptions: str = "exemptions: []\n", open_schemas: str = "reviewed: []\n"):
    root = _copy(tmp_path, exemptions)
    (root / "spec/open-schemas.yaml").write_text(open_schemas)
    return root


USAGE = "gateway:Usage"


def test_a_signed_free_form_location_types_the_surfaces_that_reach_it(tmp_path) -> None:
    before = report(CURRENT, _with_reviews(tmp_path / "a"))
    reaching = [entry["name"] for entry in before["entries"] if USAGE in entry["open"]]
    assert reaching
    after = report(CURRENT, _with_reviews(tmp_path / "b", open_schemas=(
        f'reviewed:\n- location: "{USAGE}"\n  reason: Provider counters pass through.\n  reviewed_by: flrnst\n')))
    for entry in after["entries"]:
        assert USAGE not in entry["open"]
    assert after["open_schemas"]["accepted"] == 1


def test_proposals_count_only_when_assumed(tmp_path) -> None:
    root = _with_reviews(tmp_path, exemptions=(
        "exemptions: []\nproposed:\n- kind: rest\n  name: " + OAUTH_SUBMIT + "\n  reason: Always 400.\n"),
        open_schemas=f'reviewed: []\nproposed:\n- location: "{USAGE}"\n  reason: Provider counters.\n')
    honest = report(CURRENT, root)
    assert honest["exempt"] == 0 and honest["proposed"] == 1
    assert any(USAGE in entry["open"] for entry in honest["entries"])
    assumed = report(CURRENT, root, assume_proposed=True)
    assert assumed["exempt"] == 1
    assert all(USAGE not in entry["open"] for entry in assumed["entries"])


@pytest.mark.parametrize(("open_schemas", "problem"), [
    ('reviewed:\n- location: "gateway:Invented"\n  reason: x\n  reviewed_by: y\n', "not an open location"),
    (f'reviewed:\n- location: "{USAGE}"\n  reason: x\n', "needs a reason and reviewed_by"),
    (f'reviewed: []\nproposed:\n- location: "{USAGE}"\n  reason: x\n- location: "{USAGE}"\n  reason: y\n',
     "listed twice"),
])
def test_free_form_reviews_must_still_apply(tmp_path, open_schemas: str, problem: str) -> None:
    with pytest.raises(ValueError, match=problem):
        report(CURRENT, _with_reviews(tmp_path, open_schemas=open_schemas))


def test_a_documented_redirect_has_nothing_left_to_type() -> None:
    login = next(item for item in report(CURRENT)["entries"] if item["name"] == "GET /auth/login")
    assert login["typed"]


def test_signing_moves_matching_proposals_with_the_reviewer(tmp_path) -> None:
    from tools.sign_reviews import sign

    root = _with_reviews(tmp_path, exemptions=(
        "# header\nexemptions: []\nproposed:\n- kind: rest\n  name: " + OAUTH_SUBMIT + "\n  reason: Always 400.\n"),
        open_schemas=f'reviewed: []\nproposed:\n- location: "{USAGE}"\n  reason: Provider counters.\n')
    assert sign("flrnst", match="oauth", root=root) == {"spec/exemptions.yaml": 1, "spec/open-schemas.yaml": 0}
    data = report(CURRENT, root)
    assert data["exempt"] == 1 and data["open_schemas"] == {"accepted": 0, "proposed": 1}
    assert (root / "spec/exemptions.yaml").read_text().startswith("# header\n")
    with pytest.raises(ValueError, match="reviewer"):
        sign(" ", root=root)
