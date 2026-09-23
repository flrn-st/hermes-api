"""Exercise URLSession and Ktor ticket subprotocol upgrades against a strict peer."""

from __future__ import annotations

import argparse
import os
import secrets
import socket
import subprocess
import tempfile
import time
from pathlib import Path

from harness.live import _free_port
from tools.extract_openapi import extract
from tools.fetch_spec import ROOT
from tools.ref_policy import require_release_tag


def _await_listener(port: int, server: subprocess.Popen[bytes]) -> None:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if server.poll() is not None:
            raise RuntimeError(f"Ticket probe server exited during startup: {server.returncode}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError:
            time.sleep(0.1)
    raise TimeoutError("Ticket probe server did not become ready")


def run(ref: str, source_repo: Path | None = None) -> None:
    ref = require_release_tag(ref)
    extract(ref, source_repo)
    repo = source_repo or ROOT / "spec/.upstream-rest" / ref
    python = repo / ".venv/bin/python"
    port = _free_port()
    env = os.environ.copy()
    env.pop("HERMES_LIVE_TOKEN", None)
    env.pop("HERMES_LIVE_LIFECYCLE", None)
    env.update({"HERMES_LIVE_URL": f"http://127.0.0.1:{port}",
                "HERMES_LIVE_TICKET": secrets.token_urlsafe(24)})
    if "JAVA_HOME" not in env:
        homebrew_java = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
        if homebrew_java.is_dir():
            env["JAVA_HOME"] = str(homebrew_java)
    if "DEVELOPER_DIR" not in env:
        xcode = Path("/Applications/Xcode-26.5.0.app/Contents/Developer")
        if xcode.is_dir():
            env["DEVELOPER_DIR"] = str(xcode)
    with tempfile.TemporaryDirectory(prefix="hermes-ticket-probe-") as temp:
        log_path = Path(temp) / "probe.log"
        with log_path.open("wb") as log:
            server = subprocess.Popen(
                [str(python), str(ROOT / "harness/ticket_probe_server.py"), "--port", str(port)],
                cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT,
            )
            try:
                _await_listener(port, server)
                subprocess.run(["swift", "run", "--quiet", "hermes-api-cli", "smoke",
                                "--url", env["HERMES_LIVE_URL"]], cwd=ROOT, env=env,
                               check=True, timeout=45)
                subprocess.run([str(ROOT / "kotlin/gradlew"), "smoke", "--quiet"],
                               cwd=ROOT / "kotlin", env=env, check=True, timeout=45)
            except Exception:
                print(log_path.read_text(errors="replace")[-4000:].replace(env["HERMES_LIVE_TICKET"], "<redacted>"))
                raise
            finally:
                server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--source-repo", type=Path)
    args = parser.parse_args()
    run(args.ref, args.source_repo)
