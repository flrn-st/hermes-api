"""Small OpenAI chat-completions peer for isolated tagged Hermes scenarios."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODEL = "hermes-api-fixture"
REPLY = "HermesAPI fixture reply."
CLARIFY_PROMPT = "Ask which release channel to use for HermesAPI."
CLARIFY_REPLY = "HermesAPI stable release selected."
APPROVAL_PROMPT = "Try the fixture cleanup command and report whether it was approved."
APPROVAL_TARGET = Path("/tmp/hermes-api-fixture-approval-target")
APPROVAL_COMMAND = f"rm -rf {APPROVAL_TARGET}"
APPROVAL_REPLY = "HermesAPI approval denied as expected."


class StubLLM:
    def __init__(self) -> None:
        self.requests: list[dict] = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, _format: str, *_args: object) -> None:
                pass

            def _answer(self, content_type: str, payload: bytes) -> None:
                self.send_response(200)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self) -> None:
                if self.path != "/v1/models":
                    self.send_error(404)
                    return
                self._answer("application/json", json.dumps({
                    "object": "list", "data": [{"id": MODEL, "object": "model"}],
                }).encode())

            def do_POST(self) -> None:
                if self.path != "/v1/chat/completions":
                    self.send_error(404)
                    return
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                owner.requests.append({"model": request.get("model"), "stream": request.get("stream")})
                model = request.get("model", MODEL)
                usage = {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}
                messages = request.get("messages", [])
                latest_user = next((str(message.get("content")) for message in reversed(messages)
                                    if message.get("role") == "user"), "")
                clarify_turn = CLARIFY_PROMPT in latest_user
                approval_turn = APPROVAL_PROMPT in latest_user
                latest_user_index = max((index for index, message in enumerate(messages)
                                         if message.get("role") == "user"), default=-1)
                answered_tool = any(message.get("role") == "tool" for message in messages[latest_user_index + 1:])
                if (clarify_turn or approval_turn) and not answered_tool:
                    tool_name = "clarify" if clarify_turn else "terminal"
                    arguments = (json.dumps({"questions": [{"question": "Which release channel?",
                                                          "choices": ["Stable", "Beta"]}]}) if clarify_turn
                                 else json.dumps({"command": APPROVAL_COMMAND}))
                    choice = {"index": 0, "delta": {"role": "assistant", "content": None,
                              "tool_calls": [{"index": 0, "id": f"call_hermes_api_{tool_name}",
                                              "type": "function", "function": {"name": tool_name,
                                                                          "arguments": arguments}}]},
                              "finish_reason": "tool_calls"}
                    if request.get("stream"):
                        chunk = {"id": "hermes-api-stub", "object": "chat.completion.chunk",
                                 "created": 1, "model": model, "choices": [choice]}
                        self._answer("text/event-stream", ("data: " + json.dumps(chunk) + "\n\n"
                                                           + "data: [DONE]\n\n").encode())
                    else:
                        message = {"role": "assistant", "content": None,
                                   "tool_calls": choice["delta"]["tool_calls"]}
                        self._answer("application/json", json.dumps({
                            "id": "hermes-api-stub", "object": "chat.completion", "created": 1,
                            "model": model, "choices": [{"index": 0, "message": message,
                                                        "finish_reason": "tool_calls"}], "usage": usage,
                        }).encode())
                    return
                reply = CLARIFY_REPLY if clarify_turn else APPROVAL_REPLY if approval_turn else REPLY
                if request.get("stream"):
                    chunks = [
                        {"id": "hermes-api-stub", "object": "chat.completion.chunk", "created": 1,
                         "model": model, "choices": [{"index": 0,
                                                     "delta": {"role": "assistant", "content": reply},
                                                     "finish_reason": None}]},
                        {"id": "hermes-api-stub", "object": "chat.completion.chunk", "created": 1,
                         "model": model, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                         "usage": usage},
                    ]
                    payload = ("".join("data: " + json.dumps(chunk) + "\n\n" for chunk in chunks)
                               + "data: [DONE]\n\n").encode()
                    self._answer("text/event-stream", payload)
                else:
                    self._answer("application/json", json.dumps({
                        "id": "hermes-api-stub", "object": "chat.completion", "created": 1,
                        "model": model,
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": reply},
                                     "finish_reason": "stop"}],
                        "usage": usage,
                    }).encode())

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_port}/v1"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
