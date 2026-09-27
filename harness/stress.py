"""Stress the clients against an isolated tagged Hermes server seeded with a large dataset.

The server holds thousands of sessions from several sources (CLI and messaging bots), one very long
chat, extra profiles with their own sessions, and a large kanban board. Clients reach it through the
fault proxy, whose network profile the scenarios switch (LTE, 3G, EDGE, a flaky link that resets
connections). Each client runs its stress scenarios (``HERMES_LIVE_MODE=stress``) and reports timings;
the harness enforces the budgets below and writes a report.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from harness.faults import ControlServer, FaultProxy
from harness.live import CLIENTS, HermesServer, _client_command, _free_port
from harness.stub_llm import LONG_REPLY, MODEL, PACED_REPLY, StubLLM
from tools.extract_openapi import extract
from tools.fetch_spec import ROOT
from tools.ref_policy import require_release

# The dataset, scaled by --scale (1 is what CI runs).
SESSIONS = 3000
LONG_CHAT_MESSAGES = 3000
PROFILES = 8
SESSIONS_PER_PROFILE = 150
KANBAN_TASKS = 400
SOURCES = ["cli"] * 6 + ["telegram"] * 2 + ["discord", "cron"]

# Upper bounds in milliseconds for each metric the clients report, generous for a loaded CI runner.
# A client-side metric name missing here fails the run, so every measurement has a budget.
BUDGETS_MS = {
    "rest.sessions.page_through": 20_000,
    "rest.sessions.search": 5_000,
    "rest.sessions.sidebar": 10_000,
    "rest.sessions.projects_tree": 10_000,
    "rest.long_chat.page_through": 20_000,
    "rest.long_chat.export": 15_000,
    "rest.long_chat.timeline": 5_000,
    "rest.profiles.list": 10_000,
    "rest.kanban.board": 10_000,
    "rest.parallel_reads": 20_000,
    "rest.reads.3g": 60_000,
    "rest.reads.flaky": 90_000,
    "gateway.session_list": 5_000,
    "gateway.long_chat.resume": 15_000,
    "gateway.long_chat.history": 15_000,
    "gateway.long_stream": 60_000,
    "gateway.long_stream.first_delta": 30_000,
    "gateway.long_stream.3g": 120_000,
    "gateway.paced_stream.flaky": 120_000,
    "gateway.parallel_turns": 90_000,
}


def _request(base: str, token: str, method: str, path: str, body: object | None = None) -> object:
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method, headers={
        "X-Hermes-Session-Token": token, **({"Content-Type": "application/json"} if data else {})})
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            return json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{method} {path} answered {error.code}: {error.read()[:500]!r}") from error


def _session(session_id: str, source: str, started: float, messages: int, title: str) -> dict:
    rows = []
    for index in range(messages):
        role = "user" if index % 2 == 0 else "assistant"
        text = (f"{title}, message {index + 1}: " + ("The quick brown fox jumps over the lazy dog. " * 6)).strip()
        rows.append({"role": role, "content": text, "timestamp": started + index})
    row = {"id": session_id, "source": source, "started_at": started, "title": title, "messages": rows,
           "message_count": messages}
    if source != "cli":
        row["chat_id"] = f"{source}-chat-{session_id[-4:]}"
        row["chat_type"] = "group" if source == "discord" else "dm"
    return row


def _import(base: str, token: str, rows: list[dict], profile: str | None = None) -> None:
    """Import in batches within Hermes' limits (500 sessions, 50,000 messages per request)."""
    for offset in range(0, len(rows), 500):
        batch = rows[offset:offset + 500]
        result = _request(base, token, "POST", "/api/sessions/import",
                          {"sessions": batch, **({"profile": profile} if profile else {})})
        if not result["ok"] or result["imported"] != len(batch):
            raise RuntimeError(f"Session import failed: {str(result)[:300]}")


def seed(base: str, token: str, scale: float, rng: random.Random) -> dict:
    """Fill the server through its REST API; returns what the clients may rely on."""
    started = time.time() - 30 * 86400
    sessions = [
        _session(f"20260801_{index:06d}_stress", rng.choice(SOURCES), started + index * 600, 4,
                 f"Stress session {index:05d}")
        for index in range(int(SESSIONS * scale))
    ]
    _import(base, token, sessions)
    long_count = min(int(LONG_CHAT_MESSAGES * scale), 10_000)  # Hermes imports at most 10,000 per session
    long_chat = _session("20260901_000000_longchat", "cli", time.time() - 86400, long_count, "Stress long chat")
    _import(base, token, [long_chat])
    profiles = []
    for index in range(max(1, int(PROFILES * scale))):
        name = f"stress-bot-{index:02d}"
        _request(base, token, "POST", "/api/profiles", {"name": name, "description": f"Stress bot {index}"})
        rows = [_session(f"20260815_{index:02d}{row:04d}_bot", rng.choice(SOURCES[6:]), started + row * 900, 2,
                         f"{name} chat {row:04d}") for row in range(int(SESSIONS_PER_PROFILE * scale))]
        _import(base, token, rows, name)
        profiles.append(name)
    _request(base, token, "POST", "/api/plugins/kanban/boards", {"slug": "stress", "name": "Stress board"})
    tasks = int(KANBAN_TASKS * scale)
    for index in range(tasks):
        _request(base, token, "POST", "/api/plugins/kanban/tasks?board=stress",
                 {"title": f"Stress task {index:04d}", "body": "Seeded by the stress harness.", "triage": True})
    return {
        "sessions": len(sessions) + 1,
        "long_chat": {"id": long_chat["id"], "messages": long_count},
        "profiles": profiles,
        "profile_sessions": int(SESSIONS_PER_PROFILE * scale),
        "kanban": {"board": "stress", "tasks": tasks},
        "search": "Stress session 00042",
        "long_reply": LONG_REPLY,
        "paced_reply": PACED_REPLY,
    }


def run(ref: str, clients: tuple[str, ...], scale: float) -> dict:
    ref = require_release(ref)
    extract(ref)
    repo = ROOT / "spec" / ".upstream-rest" / ref
    python = repo / ".venv" / "bin" / "python"
    report: dict[str, dict] = {}
    with tempfile.TemporaryDirectory(prefix="hermes-api-stress-", dir=os.environ.get("HERMES_LIVE_TMPDIR")) as home:
        stub = StubLLM()
        (Path(home) / "config.yaml").write_text(
            f"model:\n  default: {MODEL}\n  provider: custom\n  base_url: {stub.base_url}\n  api_key: fixture-key\n"
            "  api_mode: chat_completions\napprovals:\n  mode: manual\n", encoding="utf-8")
        port, token = _free_port(), secrets.token_urlsafe(24)
        env = {**os.environ, "HERMES_HOME": home, "HERMES_TEST_ISOLATION": "1",
               "HERMES_DASHBOARD_SESSION_TOKEN": token, "HERMES_LIVE_TOKEN": token, "HERMES_LIVE_LIFECYCLE": "0",
               "HERMES_LIVE_MODE": "stress"}
        server = HermesServer(repo, python, port, token, env, Path(home) / "server.log")
        proxy = control = None
        try:
            server.start()
            began = time.monotonic()
            dataset = seed(server.url, token, scale, random.Random(7))
            print(f"stress: seeded {dataset['sessions']} sessions, {len(dataset['profiles'])} profiles, "
                  f"{dataset['kanban']['tasks']} tasks in {time.monotonic() - began:.0f}s", flush=True)
            proxy = FaultProxy(port)
            control = ControlServer(proxy, server.restart, {"calls": []}, stress=dataset)
            env["HERMES_LIVE_URL"] = f"http://127.0.0.1:{proxy.port}"
            env["HERMES_LIVE_CONTROL"] = control.url
            for client in clients:
                control.metrics = None
                command, cwd, client_env = _client_command(client, env, proxy, control, None)
                print(f"stress: {client}", flush=True)
                resets = proxy.resets
                subprocess.run(command, cwd=cwd, env=client_env, check=True)
                proxy.set_profile("none")
                # The flaky phase must really have cut connections under the client.
                if proxy.resets == resets:
                    raise RuntimeError(f"{client}'s flaky phase saw no connection resets")
                print(f"stress: {client} survived {proxy.resets - resets} connection resets", flush=True)
                if control.metrics is None:
                    raise RuntimeError(f"{client} did not report its stress metrics")
                report[client] = control.metrics
        except Exception:
            print("\n".join(Path(home, "server.log").read_text(errors="replace").splitlines()[-20:])
                  .replace(token, "<redacted>")[-4000:])
            raise
        finally:
            if control is not None:
                control.close()
            if proxy is not None:
                proxy.close()
            server.stop()
            stub.close()
    return report


def check(report: dict[str, dict]) -> list[str]:
    failures = []
    for client, metrics in report.items():
        missing = set(BUDGETS_MS) - set(metrics)
        if missing:
            failures.append(f"{client} did not measure {sorted(missing)}")
        for name, value in metrics.items():
            budget = BUDGETS_MS.get(name)
            if budget is None:
                failures.append(f"{client} measured {name}, which has no budget")
            elif value > budget:
                failures.append(f"{client} {name} took {value:.0f} ms, over its {budget} ms budget")
    return failures


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--clients", default="swift,kotlin")
    parser.add_argument("--scale", type=float, default=1.0, help="multiply the dataset size")
    parser.add_argument("--output", type=Path, default=ROOT / ".build" / "stress-report.json")
    args = parser.parse_args()
    clients = tuple(client for client in args.clients.split(",") if client)
    if not clients or not set(clients) <= set(CLIENTS):
        parser.error(f"--clients must be a subset of {','.join(CLIENTS)}")
    report = run(args.ref, clients, args.scale)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    names = sorted({name for metrics in report.values() for name in metrics})
    print(f"{'metric':38}" + "".join(f"{client:>12}" for client in report) + f"{'budget':>12}")
    for name in names:
        print(f"{name:38}" + "".join(f"{report[c].get(name, float('nan')):>12.0f}" for c in report)
              + f"{BUDGETS_MS.get(name, 0):>12}")
    if failures := check(report):
        raise SystemExit("\n".join(failures))


if __name__ == "__main__":
    main()
