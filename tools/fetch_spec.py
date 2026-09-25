"""Extract the committed OpenRPC contract of one Hermes release from its immutable release tag."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import tomllib
from pathlib import Path

from tools.ref_policy import require_release, require_upstream_tag, upstream_tag

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = "https://github.com/NousResearch/hermes-agent.git"
OPENRPC_PATH = "apps/shared/src/gateway-contract.openrpc.json"
SERVER_PATH = "tui_gateway/server.py"
PYPROJECT_PATH = "pyproject.toml"


def _git(repo: Path, *args: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True,
        capture_output=True,
    )
    return result.stdout


def _prepare_remote(cache: Path, tag: str) -> None:
    if not (cache / ".git").is_dir():
        cache.mkdir(parents=True, exist_ok=True)
        _git(cache, "init", "--quiet")
        _git(cache, "remote", "add", "origin", UPSTREAM)
    remote = _git(cache, "remote", "get-url", "origin").decode().strip()
    if remote != UPSTREAM:
        raise ValueError(f"Unexpected upstream remote: {remote}")
    _git(cache, "fetch", "--quiet", "--force", "--filter=blob:none", "--depth=1", "origin",
         f"refs/tags/{tag}:refs/tags/{tag}")


def extract(ref: str, source_repo: Path, output_dir: Path, tag: str) -> dict[str, object]:
    """Write a tag-pinned contract and deterministic source metadata for Hermes version ``ref``."""
    require_release(ref)
    require_upstream_tag(tag)
    commit = _git(source_repo, "rev-parse", f"refs/tags/{tag}^{{commit}}").decode().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError(f"Invalid commit for {tag}: {commit}")

    contract_bytes = _git(source_repo, "show", f"{commit}:{OPENRPC_PATH}")
    contract = json.loads(contract_bytes)
    if contract.get("openrpc") != "1.3.2":
        raise ValueError(f"Unsupported OpenRPC version: {contract.get('openrpc')!r}")
    for key in ("methods", "x-server-requests", "x-notifications"):
        if not isinstance(contract.get(key), list):
            raise TypeError(f"Missing OpenRPC section: {key}")
    schemas = contract.get("components", {}).get("schemas")
    if not isinstance(schemas, dict):
        raise TypeError("Missing OpenRPC component schemas")

    pyproject = tomllib.loads(_git(source_repo, "show", f"{commit}:{PYPROJECT_PATH}").decode())
    if f"v{pyproject['project']['version']}" != ref:
        raise ValueError(f"Tag {tag} is Hermes {pyproject['project']['version']}, not {ref}")
    server = _git(source_repo, "show", f"{commit}:{SERVER_PATH}").decode()
    matches = re.findall(r"^DESKTOP_BACKEND_CONTRACT\s*=\s*(\d+)\s*$", server, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("Expected exactly one desktop contract constant")

    meta: dict[str, object] = {
        "ref": ref,
        "tag": tag,
        "commit": commit,
        "hermes_version": pyproject["project"]["version"],
        "desktop_contract": int(matches[0]),
        "openrpc_sha256": hashlib.sha256(contract_bytes).hexdigest(),
        "method_count": len(contract["methods"]),
        "server_request_count": len(contract["x-server-requests"]),
        "notification_count": len(contract["x-notifications"]),
        "schema_count": len(schemas),
    }

    existing = output_dir / "meta.json"
    if existing.exists():
        previous = json.loads(existing.read_text(encoding="utf-8"))
        if previous.get("commit") != commit:
            raise ValueError(f"Tag {tag} changed commit; refusing to overwrite pinned contract")

    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "openrpc.json").write_bytes(contract_bytes)
    existing.write_text(json.dumps(meta, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return meta


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--source-repo", type=Path, help="Existing tagged checkout (for offline use)")
    args = parser.parse_args()
    ref = require_release(args.ref)
    tag = upstream_tag(ref)
    repo = args.source_repo or ROOT / "spec" / ".upstream"
    if args.source_repo is None:
        _prepare_remote(repo, tag)
    meta = extract(ref, repo, ROOT / "spec" / "out" / ref, tag)
    print(json.dumps(meta, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
