"""Extract dashboard OpenAPI from one immutable Hermes release in an isolated home."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path

from tools.fetch_spec import ROOT, UPSTREAM
from tools.ref_policy import require_release, upstream_tag


def _run(args: list[str], cwd: Path, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(args, cwd=cwd, env=env, text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(f"{' '.join(args[:2])} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def extract(ref: str, source_repo: Path | None = None) -> dict[str, object]:
    ref = require_release(ref)
    expected = json.loads((ROOT / "spec" / "out" / ref / "meta.json").read_text(encoding="utf-8"))
    if expected["ref"] != ref:
        raise ValueError("Pinned contract metadata has a different ref")
    tag = upstream_tag(ref)
    repo = source_repo or ROOT / "spec" / ".upstream-rest" / ref
    if source_repo is None and not (repo / ".git").exists():
        repo.parent.mkdir(parents=True, exist_ok=True)
        _run(["git", "clone", "--quiet", "--filter=blob:none", "--depth=1", "--branch", tag,
              UPSTREAM, str(repo)], ROOT)
    commit = _run(["git", "rev-parse", "HEAD"], repo)
    tag_commit = _run(["git", "rev-parse", f"refs/tags/{tag}^{{commit}}"], repo)
    if commit != tag_commit or commit != expected["commit"]:
        raise ValueError(f"Checkout is not the pinned {ref} commit")
    if _run(["git", "status", "--porcelain", "--untracked-files=no"], repo):
        raise ValueError("Tagged Hermes checkout has modified tracked files")

    _run(["uv", "sync", "--frozen", "--extra", "web", "--no-dev", "--python", "3.12", "--compile-bytecode"], repo)
    python = repo / ".venv" / "bin" / "python"
    # Uncompiled imports cost the live harness seconds on every cold server start and first turn, and on
    # a loaded CI runner long enough for clients to give up; the bytecode is gitignored upstream.
    _run([str(python), "-m", "compileall", "-q", "-j", "0", "-x", r"/\.venv/", "."], repo)
    output = ROOT / "spec" / "out" / ref
    with tempfile.TemporaryDirectory(prefix="hermes-openapi-") as isolated_home:
        env = os.environ.copy()
        env.update({"HERMES_HOME": isolated_home, "HERMES_TEST_ISOLATION": "1"})
        report = json.loads(_run([
            str(python), str(ROOT / "tools" / "_render_openapi.py"),
            "--source-repo", str(repo), "--output-dir", str(output),
        ], repo, env))
    report.update({"ref": ref, "commit": commit})
    (output / "rest-meta.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--source-repo", type=Path)
    args = parser.parse_args()
    print(json.dumps(extract(args.ref, args.source_repo), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
