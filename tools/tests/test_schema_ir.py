import json
from pathlib import Path

from tools.gen.schema_ir import SchemaGraph
from tools.ref_policy import current_release

CURRENT = current_release()


def test_full_release_resolves_to_named_models() -> None:
    contract = json.loads(Path(f"spec/out/{CURRENT}/openrpc.json").read_text())
    graph = SchemaGraph(contract)
    assert len(graph.objects) + len([name for name in graph.enums if name in graph.schemas]) == 624
    assert len(graph.unions) >= 40
    assert graph.objects["WaitBarrierTarget"].fields[1].type.kotlin in graph.unions
    assert graph.unions["GoalSnapshotWaitBarrier"].discriminator == "type"
