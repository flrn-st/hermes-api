"""Run the tagged Hermes test suite with every overlaid REST response checked against its schema.

Run by hand when reviewing overlays. The contract checkout proves its contracts against its own
handlers; this proves the overlays against the pinned tag's handlers, through the tag's own route
tests (``harness/tagside/contract_gate_plugin.py``). The tag's dev dependencies go into a separate
environment, so the extraction and live-harness environment stays exactly ``--extra web``.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from collections import defaultdict
from pathlib import Path

from tools.fetch_spec import ROOT
from tools.ref_policy import require_release

MARKERS = ("TestClient", "ASGITransport")
# End-to-end suites need messaging extras and external services.
SKIPPED = {"e2e"}


def _tests(repo: Path) -> list[str]:
    selected = []
    for path in sorted((repo / "tests").rglob("test_*.py")):
        if path.relative_to(repo / "tests").parts[0] in SKIPPED:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if any(marker in text for marker in MARKERS):
            selected.append(str(path.relative_to(repo)))
    return selected


def summarize(records: list[dict], document: dict) -> dict:
    overlaid = sorted(f"{method.upper()} {path}" for path, methods in document["paths"].items()
                      for method, operation in methods.items() if "x-handler-hash" in operation)
    passed: dict[str, int] = defaultdict(int)
    violations: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        if record["errors"]:
            violations[record["operation"]].append(
                {"status": record["status"], "test": record["test"], "errors": record["errors"]})
        else:
            passed[record["operation"]] += 1
    return {
        "overlaid": len(overlaid),
        "exercised": len(set(passed) | set(violations)),
        "clean": sorted(set(passed) - set(violations)),
        "violations": {key: value[:3] for key, value in sorted(violations.items())},
        "unexercised": sorted(set(overlaid) - set(passed) - set(violations)),
    }


def run(ref: str, tests: list[str] | None, extra: list[str]) -> dict:
    ref = require_release(ref)
    repo = ROOT / "spec" / ".upstream-rest" / ref
    venv = ROOT / "spec" / ".upstream-rest" / f"{ref}-gate-venv"
    env = {**os.environ, "UV_PROJECT_ENVIRONMENT": str(venv)}
    subprocess.run(["uv", "sync", "--frozen", "--extra", "web", "--extra", "dev", "--python", "3.12"],
                   cwd=repo, env=env, check=True, capture_output=True)
    openapi = ROOT / "spec" / "out" / ref / "openapi.json"
    with tempfile.TemporaryDirectory(prefix="hermes-contract-gate-") as scratch:
        report = Path(scratch) / "records.jsonl"
        report.touch()
        env.update({
            "HERMES_CONTRACT_OPENAPI": str(openapi),
            "HERMES_CONTRACT_REPORT": str(report),
            "PYTHONPATH": str(ROOT / "harness" / "tagside"),
            # An environment variable, not -p, so per-file subprocess runs load the plugin too.
            "PYTEST_PLUGINS": "contract_gate_plugin",
        })
        env.pop("VIRTUAL_ENV", None)
        selected = tests or _tests(repo)
        subprocess.run([str(venv / "bin" / "python"), "-m", "pytest", "-q", "-p", "no:cacheprovider", "--continue-on-collection-errors",
                        *extra, *selected], cwd=repo, env=env, check=False)
        records = [json.loads(line) for line in report.read_text(encoding="utf-8").splitlines() if line]
    return summarize(records, json.loads(openapi.read_text(encoding="utf-8")))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--output", type=Path, required=True, help="write the JSON summary here")
    parser.add_argument("tests", nargs="*", help="tag test paths (default: every route test)")
    args, extra = parser.parse_known_args()
    summary = run(args.ref, args.tests or None, extra)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"overlaid": summary["overlaid"], "exercised": summary["exercised"],
                      "clean": len(summary["clean"]), "violated": len(summary["violations"])}))


if __name__ == "__main__":
    main()
