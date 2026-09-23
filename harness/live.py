"""Run both client smoke commands against an isolated tagged Hermes FastAPI server."""

from __future__ import annotations

import argparse
import os
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

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


def run(ref: str, source_repo: Path | None = None) -> None:
    ref = require_release_tag(ref)
    extract(ref, source_repo)
    repo = source_repo or ROOT / "spec" / ".upstream-rest" / ref
    python = repo / ".venv" / "bin" / "python"
    with tempfile.TemporaryDirectory(prefix="hermes-api-live-") as home:
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
        })
        if "JAVA_HOME" not in env:
            homebrew_java = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
            if homebrew_java.is_dir():
                env["JAVA_HOME"] = str(homebrew_java)
        log_path = Path(home) / "server.log"
        with log_path.open("wb") as log:
            server = subprocess.Popen(
                [str(python), "-m", "uvicorn", "hermes_cli.web_server:app",
                 "--host", "127.0.0.1", "--port", str(port)],
                cwd=repo, env=env, stdout=log, stderr=subprocess.STDOUT,
            )
            try:
                _await_status(url, token, server)
                subprocess.run(
                    ["swift", "run", "--quiet", "hermes-api-cli", "smoke", "--url", url],
                    cwd=ROOT, env=env, check=True,
                )
                subprocess.run(
                    [str(ROOT / "kotlin" / "gradlew"), "smoke", "--quiet"],
                    cwd=ROOT / "kotlin", env=env, check=True,
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--source-repo", type=Path)
    args = parser.parse_args()
    run(args.ref, args.source_repo)


if __name__ == "__main__":
    main()
