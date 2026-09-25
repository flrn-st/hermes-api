import json
from pathlib import Path

from tools.gen_gateway_api import generate as generate_api
from tools.gen_gateway_models import generate as generate_models
from tools.ref_policy import current_release

CURRENT = current_release()


def test_committed_generated_sources_match_pinned_contract() -> None:
    current = Path("spec/current-release.txt").read_text().strip()
    assert generate_models(current, check=True) == 0
    assert generate_api(current, check=True) == 0


def test_public_symbol_manifest_covers_every_gateway_item() -> None:
    manifest = json.loads(Path(f"spec/out/{CURRENT}/gateway-symbols.json").read_text())
    assert len(manifest["methods"]) == 219
    assert len(manifest["events"]) == 69
    assert len(manifest["server_requests"]) == 12
    assert next(item for item in manifest["methods"] if item["wire"] == "session.events.since")["public"] == "session.eventsSince"
