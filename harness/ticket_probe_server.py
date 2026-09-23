"""Minimal WebSocket peer that enforces the Hermes ticket subprotocol shape."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
from urllib.parse import urlsplit

from websockets.asyncio.server import serve


async def main(port: int) -> None:
    ticket = os.environ["HERMES_LIVE_TICKET"]

    async def handler(socket) -> None:
        protocols = [value.strip() for value in socket.request.headers.get("Sec-WebSocket-Protocol", "").split(",")]
        if (socket.subprotocol != "hermes-gateway-v1" or
                protocols != ["hermes-gateway-v1", f"hermes-gateway-ticket.{ticket}"] or
                urlsplit(socket.request.path).query):
            await socket.close(code=1008, reason="Invalid ticket subprotocol")
            return
        await socket.send(json.dumps({"jsonrpc": "2.0", "method": "event", "params": {
            "type": "gateway.ready", "payload": {
                "skin": {}, "change_events": True, "replay_epoch": "ticket-probe", "heartbeat": True,
            },
        }}))
        async for text in socket:
            frame = json.loads(text)
            method = frame["method"]
            if method == "client.capabilities":
                result = {"server_requests": []}
            elif method == "ping":
                result = {"pong": True}
            elif method == "gateway.capabilities":
                result = {"per_session_exclusive_submit": True}
            else:
                await socket.send(json.dumps({"jsonrpc": "2.0", "id": frame["id"], "error": {
                    "code": -32601, "message": "Unknown probe method",
                }}))
                continue
            await socket.send(json.dumps({"jsonrpc": "2.0", "id": frame["id"], "result": result}))

    async with serve(handler, "127.0.0.1", port, subprotocols=["hermes-gateway-v1"]):
        await asyncio.Future()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    asyncio.run(main(args.port))
