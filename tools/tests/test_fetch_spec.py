import json
import subprocess
from pathlib import Path

import pytest

from tools.fetch_spec import OPENRPC_PATH, PYPROJECT_PATH, SERVER_PATH, extract


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(repo), *args], check=True, capture_output=True, text=True
    ).stdout.strip()


def test_extract_pins_exact_tag_and_refuses_retarget(tmp_path: Path) -> None:
    repo = tmp_path / "upstream"
    repo.mkdir()
    git(repo, "init", "-q")
    git(repo, "config", "user.name", "Test")
    git(repo, "config", "user.email", "test@example.com")
    for name, body in {
        OPENRPC_PATH: json.dumps({
            "openrpc": "1.3.2", "methods": [{}], "x-server-requests": [],
            "x-notifications": [], "components": {"schemas": {"Example": {"type": "object"}}},
        }),
        PYPROJECT_PATH: '[project]\nversion = "0.21.4"\n',
        SERVER_PATH: "DESKTOP_BACKEND_CONTRACT = 7\n",
    }.items():
        path = repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "release")
    git(repo, "tag", "v2026.9.21")

    output = tmp_path / "out"
    meta = extract("v2026.9.21", repo, output)
    assert meta["commit"] == git(repo, "rev-parse", "HEAD")
    assert meta["desktop_contract"] == 7
    assert meta["method_count"] == 1
    assert json.loads((output / "meta.json").read_text()) == meta
    assert extract("v2026.9.21", repo, output) == meta

    git(repo, "tag", "-f", "v2026.9.21", "HEAD~0")
    (repo / PYPROJECT_PATH).write_text('[project]\nversion = "0.22.0"\n')
    git(repo, "add", ".")
    git(repo, "commit", "-qm", "retarget")
    git(repo, "tag", "-f", "v2026.9.21")
    with pytest.raises(ValueError, match="changed commit"):
        extract("v2026.9.21", repo, output)
