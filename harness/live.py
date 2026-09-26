"""Run client live scenarios against an isolated tagged Hermes FastAPI server.

Clients reach Hermes through a fault proxy; their reconnect scenarios call the control endpoint to
sever sockets mid-turn and to restart the server process.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from harness.faults import ControlServer, FaultProxy
from harness.stub_llm import APPROVAL_TARGET, MODEL, StubLLM
from tools.extract_openapi import extract
from tools.fetch_spec import ROOT
from tools.ref_policy import require_release


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


# A loaded CI runner starts Hermes in over ten seconds. With the stop's 15 s, a restart still answers
# within the clients' 120 s control timeout.
STARTUP_TIMEOUT = 90


def _await_status(url: str, token: str, server: subprocess.Popen[bytes]) -> None:
    deadline = time.monotonic() + STARTUP_TIMEOUT
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


CLIENTS = ("swift", "kotlin", "ios", "android")
# Simulator clients: runtime family in simctl identifiers and the device name prefix to pick.
SIMULATORS = {"ios": ("iOS", "iPhone")}


def _simulator_destination(client: str, env: dict[str, str]) -> str:
    """The newest available simulator for the client, unless HERMES_<CLIENT>_DESTINATION names one."""
    if destination := env.get(f"HERMES_{client.upper()}_DESTINATION"):
        return destination
    family, device_prefix = SIMULATORS[client]
    listing = subprocess.run(["xcrun", "simctl", "list", "devices", "available", "--json"],
                             env=env, check=True, capture_output=True, text=True)
    runtimes = json.loads(listing.stdout)["devices"]
    marker = f".{family}-"

    def version(runtime: str) -> tuple[int, ...]:
        return tuple(int(part) for part in runtime.rsplit(marker, 1)[1].split("-"))

    for runtime in sorted((name for name in runtimes if marker in name), key=version, reverse=True):
        for device in runtimes[runtime]:
            if device["name"].startswith(device_prefix):
                return f"platform={family} Simulator,id={device['udid']}"
    raise RuntimeError(f"No available {device_prefix} simulator; set HERMES_{client.upper()}_DESTINATION")


class HermesServer:
    """The tagged dashboard process on a fixed port, restartable in place."""

    def __init__(self, repo: Path, python: Path, port: int, token: str, env: dict[str, str], log_path: Path) -> None:
        self.repo, self.python, self.port, self.token, self.env = repo, python, port, token, env
        self.url = f"http://127.0.0.1:{port}"
        self.log_path = log_path
        self.process: subprocess.Popen[bytes] | None = None

    def start(self) -> None:
        with self.log_path.open("ab") as log:
            self.process = subprocess.Popen(
                [str(self.python), "-m", "uvicorn", "hermes_cli.web_server:app",
                 "--host", "127.0.0.1", "--port", str(self.port)],
                cwd=self.repo, env=self.env, stdout=log, stderr=subprocess.STDOUT,
            )
        _await_status(self.url, self.token, self.process)

    def stop(self) -> None:
        if self.process is None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.process = None

    def restart(self) -> None:
        self.stop()
        self.start()


def _client_command(client: str, env: dict[str, str], proxy: FaultProxy,
                    control: ControlServer) -> tuple[list[str], Path, dict[str, str]]:
    if client == "swift":
        return ["swift", "run", "--quiet", "hermes-api-cli", "smoke", "--url", env["HERMES_LIVE_URL"]], ROOT, env
    if client == "kotlin":
        return [str(ROOT / "kotlin" / "gradlew"), "smoke", "--quiet"], ROOT / "kotlin", env
    if client in SIMULATORS:
        # xcodebuild forwards TEST_RUNNER_-prefixed variables into the simulator test process.
        runner = {**env, "TEST_RUNNER_HERMES_LIVE_SCENARIOS": "1"}
        for name in ("HERMES_LIVE_URL", "HERMES_LIVE_TOKEN", "HERMES_LIVE_LIFECYCLE", "HERMES_LIVE_CONTROL"):
            runner["TEST_RUNNER_" + name] = env[name]
        # The whole suite runs in the simulator: the unit tests as well as the live scenarios. Serially,
        # as `swift test --no-parallel` runs it on macOS: in parallel the timing-sensitive gateway tests
        # and the live scenario starve each other on a CI runner.
        # Not quiet: a failing scenario reports its reason only in the test output.
        return ["xcodebuild", "test", "-scheme", "HermesAPI-Package",
                "-destination", _simulator_destination(client, env), "-derivedDataPath", str(ROOT / ".build" / "xcode"),
                "-only-testing:HermesAPITests", "-parallel-testing-enabled", "NO"], ROOT, runner
    if client == "android":
        adb = shutil.which("adb") or str(Path(env.get("ANDROID_HOME", "")) / "platform-tools" / "adb")
        # The emulator reaches the proxy and control endpoint on its own loopback.
        for port in (proxy.port, control.port):
            subprocess.run([adb, "reverse", f"tcp:{port}", f"tcp:{port}"], check=True, env=env,
                           stdout=subprocess.DEVNULL)
        arguments = [f"-Pandroid.testInstrumentationRunnerArguments.{name}={env[variable]}" for name, variable in (
            ("hermesUrl", "HERMES_LIVE_URL"), ("hermesToken", "HERMES_LIVE_TOKEN"),
            ("hermesLifecycle", "HERMES_LIVE_LIFECYCLE"), ("hermesControl", "HERMES_LIVE_CONTROL"))]
        # Not quiet: Gradle names a failing instrumented test only in its normal output.
        return [str(ROOT / "android" / "gradlew"), "connectedDebugAndroidTest", *arguments], \
            ROOT / "android", env
    raise ValueError(f"Unknown client {client}")


# The committed evidence records the Swift and Kotlin runs; the simulator and emulator clients run
# the same scenario code and must exercise at least as much.
EVIDENCE_PLATFORM = {"swift": "swift", "ios": "swift", "kotlin": "kotlin", "android": "kotlin"}


def _check_committed_evidence(ref: str, reports: dict[str, dict[str, list[str]]]) -> None:
    """Fail when a client no longer exercises something the committed coverage evidence credits it with."""
    path = ROOT / "coverage/evidence" / f"{ref}.json"
    if not path.exists():
        return
    claimed = json.loads(path.read_text(encoding="utf-8")).get("live_gateway", {})
    for client, report in reports.items():
        expected = claimed.get(EVIDENCE_PLATFORM[client], {})
        missing = {kind: sorted(set(names) - set(report[kind])) for kind, names in expected.items()}
        missing = {kind: names for kind, names in missing.items() if names}
        if missing:
            raise RuntimeError(f"{client} no longer exercises recorded coverage: {missing}")


def run(ref: str, source_repo: Path | None = None, *, record: bool = False,
        clients: tuple[str, ...] = ("swift", "kotlin")) -> None:
    ref = require_release(ref)
    if record and not {"swift", "kotlin"} <= set(clients):
        raise ValueError("Recording evidence needs both the swift and kotlin clients")
    extract(ref, source_repo)
    if APPROVAL_TARGET.exists():
        raise RuntimeError(f"Approval fixture target must be absent: {APPROVAL_TARGET}")
    repo = source_repo or ROOT / "spec" / ".upstream-rest" / ref
    python = repo / ".venv" / "bin" / "python"
    with tempfile.TemporaryDirectory(prefix="hermes-api-live-") as home:
        stub = StubLLM()
        (Path(home) / "config.yaml").write_text(
            f"model:\n  default: {MODEL}\n  provider: custom\n"
            f"  base_url: {stub.base_url}\n  api_key: fixture-key\n"
            "  api_mode: chat_completions\napprovals:\n  mode: manual\n", encoding="utf-8",
        )
        port = _free_port()
        token = secrets.token_urlsafe(24)
        env = os.environ.copy()
        env.update({
            "HERMES_HOME": home,
            "HERMES_TEST_ISOLATION": "1",
            "HERMES_DASHBOARD_SESSION_TOKEN": token,
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
        if os.environ.get("DEBUG_RST1_DIR"):  # [DEBUG-rst1]
            log_path = Path(os.environ["DEBUG_RST1_DIR"]) / "server.log"  # [DEBUG-rst1] survives a cancelled job
            env["PYTHONPATH"] = os.environ["DEBUG_RST1_DIR"]  # [DEBUG-rst1] sitecustomize: periodic stack dumps
        server = HermesServer(repo, python, port, token, env, log_path)
        proxy: FaultProxy | None = None
        control: ControlServer | None = None
        try:
            server.start()
            proxy = FaultProxy(port)
            control = ControlServer(proxy, server.restart)
            env["HERMES_LIVE_URL"] = f"http://127.0.0.1:{proxy.port}"
            env["HERMES_LIVE_CONTROL"] = control.url
            if record:
                subprocess.run(
                    [str(python), str(ROOT / "harness/record.py"),
                     "--scenario", str(ROOT / "scenarios/liveness.yaml"),
                     "--output", str(ROOT / "fixtures" / ref / "liveness.jsonl"),
                     "--openapi", str(ROOT / "spec/out" / ref / "openapi.json")],
                    cwd=repo, env={**env, "HERMES_LIVE_URL": server.url}, check=True,
                )
            reports: dict[str, dict[str, list[str]]] = {}
            for client in clients:
                restarts, drops, blackholes = control.restarts, proxy.drops, proxy.blackholes
                control.report = None
                command, cwd, client_env = _client_command(client, env, proxy, control)
                print(f"live: {client}", flush=True)
                subprocess.run(command, cwd=cwd, env=client_env, check=True)
                # The reconnect scenarios must have exercised both faults, not skipped them.
                if control.restarts - restarts != 1 or proxy.drops - drops < 3 \
                        or proxy.blackholes - blackholes != 1:
                    raise RuntimeError(f"{client} did not run the reconnect scenarios")
                if control.report is None:
                    raise RuntimeError(f"{client} did not report what it exercised")
                reports[client] = control.report
            if not record:
                _check_committed_evidence(ref, reports)
            if APPROVAL_TARGET.exists():
                raise RuntimeError(f"Denied approval command created its target: {APPROVAL_TARGET}")
            if not any(request["stream"] and request["model"] == MODEL for request in stub.requests):
                raise RuntimeError("No streamed prompt reached the isolated model stub")
            if record:
                subprocess.run(["swift", "test", "--no-parallel", "--quiet"],
                               cwd=ROOT, env=env, check=True)
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
                    "server_requests": sorted({entry["name"] for entry in recorded
                                               if entry["kind"] == "server_request"}),
                    "events": sorted({entry["name"] for entry in recorded if entry["kind"] == "event"}),
                    "rest": sorted({entry["name"] for entry in recorded if entry["kind"] == "rest"}),
                    # Gateway items each client exercised, measured on the wire by its live scenarios.
                    "live_gateway": {platform: reports[platform] for platform in ("swift", "kotlin")},
                    "live_rest": ["GET /api/audio/voice-live/status",
                                  "GET /api/profiles/active", "POST /api/profiles/active",
                                  "GET /api/sessions/empty/count"],
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
            diagnostic = log_path.read_text(errors="replace").splitlines()[-20:]
            print("\n".join(line.replace(token, "<redacted>") if "system_prompt" not in line
                            else "<redacted server frame>" for line in diagnostic)[-4000:])
            raise
        finally:
            if control is not None:
                control.close()
            if proxy is not None:
                proxy.close()
            server.stop()
            stub.close()

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--source-repo", type=Path)
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--clients", default="swift,kotlin",
                        help=f"comma-separated subset of {','.join(CLIENTS)}")
    args = parser.parse_args()
    clients = tuple(client for client in args.clients.split(",") if client)
    if not clients or not set(clients) <= set(CLIENTS):
        parser.error(f"--clients must be a subset of {','.join(CLIENTS)}")
    run(args.ref, args.source_repo, record=args.record, clients=clients)


if __name__ == "__main__":
    main()
