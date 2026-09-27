"""Extract the error codes every gateway method of one immutable Hermes release can answer with.

Hermes' gateway contract declares no errors, so they are read from the tagged handlers (see
``_render_gateway_errors.py``). The result, ``spec/out/<ref>/gateway-errors.json``, is reviewed through
``spec/gateway-errors.yaml`` before the generators use it.
"""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from pathlib import Path

from tools.extract_openapi import _run, prepare
from tools.fetch_spec import ROOT
from tools.ref_policy import require_release


def extract(ref: str, source_repo: Path | None = None) -> dict[str, object]:
    ref = require_release(ref)
    repo, python, commit = prepare(ref, source_repo)
    output = ROOT / "spec" / "out" / ref / "gateway-errors.json"
    with tempfile.TemporaryDirectory(prefix="hermes-errors-") as isolated_home:
        env = os.environ.copy()
        env.update({"HERMES_HOME": isolated_home, "HERMES_TEST_ISOLATION": "1"})
        rendered = Path(isolated_home) / "gateway-errors.json"
        _run([str(python), str(ROOT / "tools" / "_render_gateway_errors.py"),
              "--source-repo", str(repo), "--output", str(rendered)], repo, env)
        errors = json.loads(rendered.read_text(encoding="utf-8"))
    errors.update({"ref": ref, "commit": commit})
    output.write_text(json.dumps(errors, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return {"ref": ref, "methods": len(errors["methods"]),
            "codes": len({entry["code"] for entries in errors["methods"].values() for entry in entries})}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--source-repo", type=Path)
    args = parser.parse_args()
    print(json.dumps(extract(args.ref, args.source_repo), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
