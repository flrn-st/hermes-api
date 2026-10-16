import copy
import hashlib
import json

import pytest
import yaml

from tools.development_overlay import (
    apply_gateway,
    apply_rest,
    gateway_contract,
    gateway_errors,
    load,
)
from tools.fetch_spec import ROOT
from tools.gateway_errors import load as load_errors
from tools.ref_policy import current_release

CURRENT = current_release()
WINDOW_PLANS = ROOT / "spec/overlay/development/window-plans.yaml"


def _contract() -> dict:
    return {
        "openrpc": "1.3.2",
        "methods": [{"name": "a.one", "params": [], "result": {"name": "result", "schema": {"$ref": "#/components/schemas/Row"}}},
                    {"name": "a.two", "params": []}],
        "x-server-requests": [], "x-notifications": [],
        "components": {"schemas": {"Row": {"type": "object", "additionalProperties": False,
                                           "properties": {"id": {"type": "string"}}}}},
    }


def _overlay(**gateway) -> dict:
    return {"source": {"repository": "r", "branch": "b", "commit": "c", "subject": "s"},
            "releases": [CURRENT], "gateway": gateway}


def test_gateway_additions_merge_in_place() -> None:
    contract = _contract()
    overlay = _overlay(
        methods=[{"after": "a.one", "errors": [], "method": {
            "name": "a.new", "params": [{"name": "params", "schema": {"$ref": "#/components/schemas/NewParams"}}]}}],
        schemas={"NewParams": {"type": "object", "properties": {"on": {"type": "boolean"}}}},
        properties={"Row": {"extra": {"schema": {"anyOf": [{"$ref": "#/components/schemas/NewParams"}, {"type": "null"}]}},
                         "first": {"after": "id", "schema": {"type": "string"}}}},
    )
    merged = apply_gateway(contract, overlay)
    assert [item["name"] for item in merged["methods"]] == ["a.one", "a.new", "a.two"]
    # A field lands where the branch declares it (after ``after``), or last.
    assert list(merged["components"]["schemas"]["Row"]["properties"]) == ["id", "first", "extra"]
    assert "required" not in merged["components"]["schemas"]["Row"]
    # The pinned document itself is never changed.
    assert contract == _contract()


@pytest.mark.parametrize(("gateway", "message"), [
    ({"methods": [{"after": "a.one", "errors": [], "method": {"name": "a.two"}}]}, "already in the contract"),
    ({"methods": [{"after": "a.missing", "errors": [], "method": {"name": "a.new"}}]}, "lacks"),
    ({"methods": [{"after": "a.one", "method": {"name": "a.new"}}]}, "after, method and errors"),
    ({"schemas": {"Row": {"type": "object"}}}, "already in the contract"),
    ({"properties": {"Row": {"id": {"schema": {"type": "string"}}}}}, "already in the contract"),
    ({"properties": {"Row": {"x": {"type": "string"}}}}, "needs a schema"),
    ({"properties": {"Row": {"x": {"after": "nope", "schema": {"type": "string"}}}}}, "lacks"),
    ({"properties": {"Missing": {"x": {"schema": {"type": "string"}}}}}, "not an object schema"),
    ({"properties": {"Row": {"x": {"schema": {"$ref": "#/components/schemas/Nowhere"}}}}}, "unresolved"),
])
def test_gateway_overlay_only_adds(gateway: dict, message: str) -> None:
    with pytest.raises((TypeError, ValueError), match=message):
        apply_gateway(_contract(), _overlay(**gateway))


def test_rest_additions_copy_gateway_schemas() -> None:
    gateway = apply_gateway(_contract(), _overlay(schemas={"Usage": {"type": "object", "properties": {}}}))
    document = {"components": {"schemas": {"ProviderRow": {"type": "object", "properties": {"slug": {"type": "string"}}}}}}
    overlay = {"rest": {"x-source": "hermes_cli/inventory.py", "gateway-schemas": ["Usage"], "properties": {
        "ProviderRow": {"usage": {"schema": {"anyOf": [{"$ref": "#/components/schemas/Usage"}, {"type": "null"}]}}}}}}
    apply_rest(document, overlay, gateway)
    assert document["components"]["schemas"]["Usage"] == gateway["components"]["schemas"]["Usage"]
    assert set(document["components"]["schemas"]["ProviderRow"]["properties"]) == {"slug", "usage"}
    with pytest.raises(ValueError, match="already in the contract"):
        apply_rest(document, overlay, gateway)
    with pytest.raises(ValueError, match="x-source"):
        apply_rest({}, {"rest": {"properties": {}}}, gateway)


def test_overlay_applies_only_to_the_releases_it_names() -> None:
    overlay = yaml.safe_load(WINDOW_PLANS.read_text(encoding="utf-8"))
    for release in overlay["releases"]:
        assert WINDOW_PLANS in [path for path, _ in load(release)]
    assert load("v0.21.5") == []
    raw = json.loads((ROOT / "spec/out/v0.21.5/openrpc.json").read_text())
    assert gateway_contract("v0.21.5") == raw


def test_window_plans_overlay_on_the_pinned_release() -> None:
    raw_bytes = (ROOT / f"spec/out/{CURRENT}/openrpc.json").read_bytes()
    meta = json.loads((ROOT / f"spec/out/{CURRENT}/meta.json").read_text())
    # The fetched artifact stays the tag's bytes; the overlay is merged only when it is read.
    assert hashlib.sha256(raw_bytes).hexdigest() == meta["openrpc_sha256"]
    raw = json.loads(raw_bytes)
    merged = gateway_contract(CURRENT)
    names = [item["name"] for item in merged["methods"]]
    assert set(names) - {item["name"] for item in raw["methods"]} == {"billing.keep_going_on_credits"}
    assert names[names.index("billing.charge_status") + 1] == "billing.keep_going_on_credits"
    schemas = merged["components"]["schemas"]
    assert set(schemas) - set(raw["components"]["schemas"]) == {
        "BillingKeepGoingOnCreditsParams", "PlanUsage", "PlanUsageGauge",
        "UsageLimit", "UsageLimitCredits", "UsageLimitSuggestion"}
    added = {name: sorted(set(schemas[name]["properties"]) - set(raw["components"]["schemas"][name]["properties"]))
             for name in raw["components"]["schemas"]
             if schemas[name] != raw["components"]["schemas"][name]}
    assert added == {
        "BillingStateResult": ["keep_going_on_credits", "plan_usage"],
        "ErrorSurface": ["usage_limit"],
        "ModelOptionProvider": ["plan_usage", "usage_levels"],
        "SessionUsageResult": ["credits_usage_window"],
        "SubscriptionStateResult": ["plan_usage"],
    }
    # Nothing else in the release changes, and every addition is optional.
    for name, fields in added.items():
        assert set(schemas[name].get("required", [])) == set(raw["components"]["schemas"][name].get("required", []))
        stripped = copy.deepcopy(schemas[name])
        for field in fields:
            del stripped["properties"][field]
        assert stripped == raw["components"]["schemas"][name]
    assert gateway_errors(CURRENT) == {"billing.keep_going_on_credits": []}
    assert load_errors(CURRENT).methods["billing.keep_going_on_credits"] == ()


def test_window_plans_rest_row_on_the_pinned_release() -> None:
    document = json.loads((ROOT / f"spec/out/{CURRENT}/openapi.json").read_text())
    schemas = document["components"]["schemas"]
    row = schemas["ModelOptionProviderRow"]["properties"]
    assert row["plan_usage"]["anyOf"][0] == {"$ref": "#/components/schemas/PlanUsage"}
    assert row["usage_levels"]["anyOf"][0] == {"type": "object", "additionalProperties": {"type": "string"}}
    gateway = gateway_contract(CURRENT)["components"]["schemas"]
    assert schemas["PlanUsage"] == gateway["PlanUsage"]
    assert schemas["PlanUsageGauge"] == gateway["PlanUsageGauge"]
    assert "plan_usage" not in schemas["ModelOptionProviderRow"].get("required", [])
