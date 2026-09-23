import json
from pathlib import Path

from tools.gen.schema_ir import SchemaGraph


def test_full_release_resolves_to_named_models() -> None:
    contract = json.loads(Path("spec/out/v2026.9.21/openrpc.json").read_text())
    graph = SchemaGraph(contract)
    assert len(graph.objects) + len([name for name in graph.enums if name in graph.schemas]) == 624
    assert len(graph.unions) >= 40
    assert graph.objects["WaitBarrierTarget"].fields[1].type.kotlin in graph.unions
    assert graph.unions["GoalSnapshotWaitBarrier"].discriminator == "type"
