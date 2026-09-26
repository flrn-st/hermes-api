"""Warm a freshly started Hermes process with one throwaway turn before any client scenario runs.

Hermes pays a one-time cost per process on its first turn: it discovers and imports its tools and builds
the agent. On a loaded CI runner that took over a minute, long enough to fail a client's turn deadline
for a reason that has nothing to do with the client. Runs under the tagged Hermes venv, like record.py.
"""

from __future__ import annotations

import argparse
import asyncio
import itertools
import json

from websockets.asyncio.client import connect


async def warm(url: str, token: str) -> None:
    ids = itertools.count(1)
    async with connect(f"{url.replace('http', 'ws', 1)}/api/ws?token={token}", origin=url) as socket:

        async def call(method: str, params: dict) -> dict:
            call_id = next(ids)
            await socket.send(json.dumps({"jsonrpc": "2.0", "id": call_id, "method": method, "params": params}))
            async for raw in socket:
                frame = json.loads(raw)
                if frame.get("id") == call_id:
                    if "error" in frame:
                        raise RuntimeError(f"Warm-up {method} failed: {frame['error']}")
                    return frame["result"]
            raise RuntimeError(f"Socket closed during warm-up {method}")

        await call("client.capabilities", {"server_requests": True})
        session = await call("session.create", {"cwd": "/tmp", "title": "Harness warm-up",
                                                "close_on_disconnect": True})
        await call("prompt.submit", {"session_id": session["session_id"], "text": "Reply with a short greeting."})
        async for raw in socket:
            frame = json.loads(raw)
            params = frame.get("params") or {}
            if frame.get("method") == "event" and params.get("session_id") == session["session_id"] \
                    and params.get("type") in ("message.complete", "error"):
                if params["type"] == "error":
                    raise RuntimeError(f"Warm-up turn failed: {params.get('payload')}")
                return
        raise RuntimeError("Socket closed before the warm-up turn completed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--token", required=True)
    args = parser.parse_args()
    asyncio.run(warm(args.url, args.token))


if __name__ == "__main__":
    main()
