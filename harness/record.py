"""Record real JSON-RPC frames after validating them with the tagged Pydantic catalog."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path

import yaml
from tui_gateway.contracts import EVENTS, METHODS
from websockets.asyncio.client import connect


def _resolve(value: object, captured: dict[str, object]) -> object:
    if isinstance(value, str) and value.startswith("${") and value.endswith("}"):
        return captured[value[2:-1]]
    if isinstance(value, list):
        return [_resolve(item, captured) for item in value]
    if isinstance(value, dict):
        return {key: _resolve(item, captured) for key, item in value.items()}
    return value


async def record(scenario: Path, output: Path) -> None:
    definition = yaml.safe_load(scenario.read_text(encoding="utf-8"))
    if not isinstance(definition, dict) or not isinstance(definition.get("calls"), list):
        raise TypeError("Invalid scenario")
    url = os.environ["HERMES_LIVE_URL"].replace("http://", "ws://").replace("https://", "wss://")
    token = os.environ["HERMES_LIVE_TOKEN"]
    entries: list[dict] = []
    captured: dict[str, object] = {}
    seen_events: list[str] = []
    seen_frames: dict[str, dict] = {}
    async with connect(f"{url}/api/ws?token={token}", origin=os.environ["HERMES_LIVE_URL"]) as socket:
        async def receive(expected_id: int | None = None) -> dict:
            while True:
                frame = json.loads(await asyncio.wait_for(socket.recv(), 10))
                if frame.get("method") == "event":
                    params = frame["params"]
                    contract = EVENTS[params["type"]]
                    if contract.payload is not None:
                        contract.payload.model_validate(params.get("payload", {}))
                    name = params["type"]
                    if name == "error":
                        raise RuntimeError("Hermes emitted an error event")
                    seen_events.append(name)
                    seen_frames[name] = frame
                    entries.append({"kind": "event", "name": name, "frame": frame})
                    if expected_id is None:
                        return frame
                    continue
                if expected_id is None or frame.get("id") != expected_id:
                    raise ValueError(f"Unexpected response id: {frame.get('id')}")
                return frame

        while "gateway.ready" not in seen_events:
            await receive()
        calls = [{"method": "client.capabilities", "params": {"server_requests": True}},
                 *definition["calls"]]
        for identifier, call in enumerate(calls, 1):
            seen_events.clear()
            seen_frames.clear()
            method = call["method"]
            contract = METHODS[method]
            params = contract.params.model_validate(_resolve(call["params"], captured)).model_dump(exclude_unset=True)
            await socket.send(json.dumps({"jsonrpc": "2.0", "id": identifier,
                                          "method": method, "params": params}))
            frame = await receive(identifier)
            if "error" in frame:
                raise ValueError(f"Scenario method {method} returned {frame['error']}")
            contract.result.model_validate(frame["result"])
            for name, field in call.get("capture", {}).items():
                captured[name] = frame["result"][field]
            entries.append({"kind": "response", "name": method, "frame": frame})
            wait_for = call.get("wait_for_event")
            if wait_for:
                while wait_for not in seen_events:
                    await receive()
                expected_text = call.get("expect_event_text")
                if expected_text is not None:
                    actual = seen_frames[wait_for]["params"]["payload"].get("text")
                    if actual != expected_text:
                        raise ValueError(f"Unexpected {wait_for} text: {actual!r}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("".join(json.dumps(entry, sort_keys=True) + "\n" for entry in entries), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    asyncio.run(record(args.scenario, args.output))


if __name__ == "__main__":
    main()
