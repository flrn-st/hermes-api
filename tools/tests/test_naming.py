import json
from pathlib import Path

import pytest

from tools.naming import camel, checked_method_symbols, method_symbol, pascal


def test_shared_method_naming() -> None:
    assert method_symbol("session.create").public_name == "session.create"
    assert method_symbol("session.events.since").public_name == "session.eventsSince"
    assert method_symbol("mcp.setup_request").public_name == "mcp.setupRequest"
    assert method_symbol("ping").public_name == "ping"
    assert camel("message.delta") == "messageDelta"
    assert pascal("vault.save_login") == "VaultSaveLogin"


def test_rejects_collisions_and_invalid_names() -> None:
    with pytest.raises(ValueError, match="collision"):
        checked_method_symbols(["a.b_c", "a.b.c"])
    with pytest.raises(ValueError):
        camel("a..b")


def test_target_contract_has_unique_method_names() -> None:
    contract = json.loads(Path("spec/out/v2026.9.21/openrpc.json").read_text())
    methods = checked_method_symbols(item["name"] for item in contract["methods"])
    assert len(methods) == 219
