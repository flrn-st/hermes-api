"""Small OpenAI chat-completions peer for isolated tagged Hermes scenarios."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = "hermes-api-fixture"
REPLY = "HermesAPI fixture reply."


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
                if request.get("stream"):
                    chunks = [
                        {"id": "hermes-api-stub", "object": "chat.completion.chunk", "created": 1,
                         "model": model, "choices": [{"index": 0,
                                                     "delta": {"role": "assistant", "content": REPLY},
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
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": REPLY},
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
