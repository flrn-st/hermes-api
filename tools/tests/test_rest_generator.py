"""Reviewed REST operations become the same public symbols in both languages."""

from __future__ import annotations

import json
from copy import deepcopy

import pytest

from tools.fetch_spec import ROOT
from tools.gen_rest_api import _typed_operations, generate


def test_tagged_rest_symbols_are_generated() -> None:
    document = json.loads((ROOT / "spec/out/v2026.9.21/openapi.json").read_text())
    operations = _typed_operations(document)
    assert [(op.namespace, op.name, op.type_name) for op in operations] == [
        ("auth", "me", "AuthMeResponse"),
        ("auth", "wsTicket", "AuthWsTicketResponse"),
        ("profiles", "active", "ProfilesActiveResponse"),
        ("sessions", "emptyCount", "SessionsEmptyCountResponse"),
    ]
    assert [(p.wire, p.name, p.swift_type, p.kotlin_type) for p in operations[3].query_params] == [
        ("profile", "profile", "String", "String"),
    ]
    current = (ROOT / "spec/current-release.txt").read_text().strip()
    assert generate(current, check=True) >= 2


def test_generator_rejects_unhandled_request_shape() -> None:
    document = json.loads((ROOT / "spec/out/v2026.9.21/openapi.json").read_text())
    document = deepcopy(document)
    document["paths"]["/api/auth/me"]["get"]["parameters"] = [{"name": "profile"}]
    with pytest.raises(ValueError, match="does not yet support"):
        _typed_operations(document)
