"""Run both client smoke commands against an isolated tagged Hermes FastAPI server."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from harness.stub_llm import MODEL, StubLLM
from tools.extract_openapi import extract
from tools.fetch_spec import ROOT
from tools.ref_policy import require_release_tag


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def _await_status(url: str, token: str, server: subprocess.Popen[bytes]) -> None:
    deadline = time.monotonic() + 30
    request = urllib.request.Request(url + "/api/status", headers={"X-Hermes-Session-Token": token})
    while time.monotonic() < deadline:
        if server.poll() is not None:
            raise RuntimeError(f"Hermes server exited during startup: {server.returncode}")
        try:
            with urllib.request.urlopen(request, timeout=1) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.2)
    raise TimeoutError("Hermes status route did not become ready")


def run(ref: str, source_repo: Path | None = None, *, record: bool = False) -> None:
    ref = require_release_tag(ref)
    extract(ref, source_repo)
    repo = source_repo or ROOT / "spec" / ".upstream-rest" / ref
    python = repo / ".venv" / "bin" / "python"
    with tempfile.TemporaryDirectory(prefix="hermes-api-live-") as home:
        stub = StubLLM()
        (Path(home) / "config.yaml").write_text(
            f"model:\n  default: {MODEL}\n  provider: custom\n"
            f"  base_url: {stub.base_url}\n  api_key: fixture-key\n"
            "  api_mode: chat_completions\n", encoding="utf-8",
        )
        port = _free_port()
        url = f"http://127.0.0.1:{port}"
        token = secrets.token_urlsafe(24)
        env = os.environ.copy()
        env.update({
            "HERMES_HOME": home,
            "HERMES_TEST_ISOLATION": "1",
            "HERMES_DASHBOARD_SESSION_TOKEN": token,
            "HERMES_LIVE_URL": url,
            "HERMES_LIVE_TOKEN": token,
            "HERMES_LIVE_LIFECYCLE": "1",
        })
        if "JAVA_HOME" not in env:
            homebrew_java = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
            if homebrew_java.is_dir():
                env["JAVA_HOME"] = str(homebrew_java)
        if "DEVELOPER_DIR" not in env:
            xcode = Path("/Applications/Xcode-26.5.0.app/Contents/Developer")
            if xcode.is_dir():
                env["DEVELOPER_DIR"] = str(xcode)
        log_path = Path(home) / "server.log"
        with log_path.open("wb") as log:
            server = subprocess.Popen(
                [str(python), "-m", "uvicorn", "hermes_cli.web_server:app",
                 "--host", "127.0.0.1", "--port", str(port)],
                cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT,
            )
            try:
                _await_status(url, token, server)
                if record:
                    subprocess.run(
                        [str(python), str(ROOT / "harness/record.py"),
                         "--scenario", str(ROOT / "scenarios/liveness.yaml"),
                         "--output", str(ROOT / "fixtures" / ref / "liveness.jsonl")],
                        cwd=repo, env=env, check=True,
                    )
                subprocess.run(
                    ["swift", "run", "--quiet", "hermes-api-cli", "smoke", "--url", url],
                    cwd=ROOT, env=env, check=True,
                )
                subprocess.run(
                    [str(ROOT / "kotlin" / "gradlew"), "smoke", "--quiet"],
                    cwd=ROOT / "kotlin", env=env, check=True,
                )
                if not any(request["stream"] and request["model"] == MODEL for request in stub.requests):
                    raise RuntimeError("No streamed prompt reached the isolated model stub")
                if record:
                    subprocess.run(["swift", "test", "--quiet"], cwd=ROOT, env=env, check=True)
                    subprocess.run([str(ROOT / "kotlin" / "gradlew"), "check", "--quiet"],
                                   cwd=ROOT / "kotlin", env=env, check=True)
                    fixture = ROOT / "fixtures" / ref / "liveness.jsonl"
                    meta = json.loads((ROOT / "spec/out" / ref / "meta.json").read_text())
                    recorded = [json.loads(line) for line in fixture.read_text(encoding="utf-8").splitlines()]
                    evidence = {
                        "ref": ref,
                        "commit": meta["commit"],
                        "scenario": "liveness",
                        "fixture": str(fixture.relative_to(ROOT)),
                        "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
                        "methods": sorted({entry["name"] for entry in recorded if entry["kind"] == "response"}),
                        "events": sorted({entry["name"] for entry in recorded if entry["kind"] == "event"}),
                        "live_methods": ["client.capabilities", "ping", "gateway.capabilities",
                                         "session.create", "prompt.submit", "session.list", "session.close"],
                        "live_events": ["gateway.ready", "message.complete"],
                        "decode_swift": True,
                        "decode_kotlin": True,
                        "live_swift": True,
                        "live_kotlin": True,
                    }
                    evidence_dir = ROOT / "coverage/evidence"
                    evidence_dir.mkdir(parents=True, exist_ok=True)
                    (evidence_dir / f"{ref}.json").write_text(
                        json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8"
                    )
            except Exception:
                print(log_path.read_text(errors="replace")[-4000:].replace(token, "<redacted>"))
                raise
            finally:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)
                stub.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--source-repo", type=Path)
    parser.add_argument("--record", action="store_true")
    args = parser.parse_args()
    run(args.ref, args.source_repo, record=args.record)


if __name__ == "__main__":
    main()
