"""Small OpenAI chat-completions peer for isolated tagged Hermes scenarios."""

from __future__ import annotations

import json
import threading
import time
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
RECONNECT_PROMPT = "Stream the HermesAPI reconnect fixture slowly."
RECONNECT_CHUNKS = [f"part{index:02d} " for index in range(1, 17)]
RECONNECT_REPLY = "".join(RECONNECT_CHUNKS).strip()
RECONNECT_CHUNK_DELAY = 0.25
# Stress fixtures: a very long reply streamed as fast as possible, and a long reply streamed over tens
# of seconds so network faults hit it mid-turn.
LONG_PROMPT = "Stream the HermesAPI long fixture."
LONG_CHUNKS = [f"segment {index:04d} of the HermesAPI long fixture. " for index in range(1, 2001)]
LONG_REPLY = "".join(LONG_CHUNKS).strip()
PACED_PROMPT = "Stream the HermesAPI paced fixture."
PACED_CHUNKS = [f"beat {index:03d} " for index in range(1, 301)]
PACED_REPLY = "".join(PACED_CHUNKS).strip()
PACED_CHUNK_DELAY = 0.05
# Feature fixtures: a turn that writes a todo list, one that delegates to a subagent (which the stub then
# answers like any other turn), and one that reasons before it answers.
TODO_PROMPT = "Plan the HermesAPI fixture with a todo list."
TODO_REPLY = "HermesAPI todo list written."
DELEGATE_PROMPT = "Delegate the HermesAPI fixture check to a subagent."
DELEGATE_REPLY = "HermesAPI subagent finished."
REASONING_PROMPT = "Think about the HermesAPI fixture before answering."
REASONING_CHUNKS = ["Considering ", "the HermesAPI ", "fixture."]
REASONING_REPLY = "HermesAPI reasoning complete."
# An approved command: it matches a dangerous pattern, so Hermes asks, and removes a path that never exists.
ACCEPT_PROMPT = "Run the fixture command that needs approval and report the result."
ACCEPT_COMMAND = "rm -rf /tmp/hermes-api-fixture-accepted-target"
ACCEPT_REPLY = "HermesAPI approval accepted."
# A skill that declares an environment variable Hermes asks the client for; the fixture skips it.
SECRET_SKILL = "hermes-api-fixture-secret"
SECRET_ENV_VAR = "HERMES_API_FIXTURE_SECRET"
SECRET_PROMPT = "Load the HermesAPI fixture skill that needs a secret."
SECRET_REPLY = "HermesAPI secret request answered."
# A question the client leaves open until the turn is interrupted, so Hermes withdraws it.
WITHDRAWN_PROMPT = "Ask a HermesAPI question that will be withdrawn."
WITHDRAWN_REPLY = "HermesAPI question withdrawn."
# Prompts answered with one tool call, then with a reply once the tool result is in.
SCRIPTED_TOOLS = {
    CLARIFY_PROMPT: ("clarify", {"questions": [{"question": "Which release channel?", "choices": ["Stable", "Beta"]}]},
                     CLARIFY_REPLY),
    APPROVAL_PROMPT: ("terminal", {"command": APPROVAL_COMMAND}, APPROVAL_REPLY),
    TODO_PROMPT: ("todo_list", {"todos": [{"id": "1", "content": "Check the HermesAPI fixture", "status": "in_progress"},
                                          {"id": "2", "content": "Report the result", "status": "pending"}]},
                  TODO_REPLY),
    ACCEPT_PROMPT: ("terminal", {"command": ACCEPT_COMMAND}, ACCEPT_REPLY),
    SECRET_PROMPT: ("skill_view", {"name": SECRET_SKILL}, SECRET_REPLY),
    WITHDRAWN_PROMPT: ("clarify", {"questions": [{"question": "Which HermesAPI question will be withdrawn?",
                                                  "choices": ["First", "Second"]}]}, WITHDRAWN_REPLY),
    DELEGATE_PROMPT: ("delegate_task", {"tasks": [{"goal": "Reply with a short greeting.",
                                                   "context": "HermesAPI fixture subagent."}]},
                      DELEGATE_REPLY),
}


def seed_secret_skill(home: Path) -> Path:
    """Install the skill whose required environment variable makes Hermes send a ``secret`` request, and
    return its directory for ``skills.external_dirs``. An external directory keeps it out of the learning
    graph, whose nodes the REST scenario edits."""
    directory = home / "fixture-skills"
    skill = directory / SECRET_SKILL
    skill.mkdir(parents=True, exist_ok=True)
    (skill / "SKILL.md").write_text(
        f"---\nname: {SECRET_SKILL}\ndescription: HermesAPI fixture skill that needs a secret.\n"
        f"required_environment_variables:\n  - name: {SECRET_ENV_VAR}\n"
        "    prompt: Enter the HermesAPI fixture secret\n---\n\nThe HermesAPI fixture skill.\n",
        encoding="utf-8",
    )
    return directory


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

            def _stream_slowly(self, model: str, usage: dict, chunks: list[str] = RECONNECT_CHUNKS,
                               delay: float = RECONNECT_CHUNK_DELAY) -> None:
                """Spread the reply over seconds so a client can lose its socket mid-turn."""
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Connection", "close")
                self.end_headers()
                for index, text in enumerate(chunks):
                    delta = {"role": "assistant", "content": text} if index == 0 else {"content": text}
                    chunk = {"id": "hermes-api-stub", "object": "chat.completion.chunk", "created": 1,
                             "model": model, "choices": [{"index": 0, "delta": delta, "finish_reason": None}]}
                    self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode())
                    self.wfile.flush()
                    if delay:
                        time.sleep(delay)
                final = {"id": "hermes-api-stub", "object": "chat.completion.chunk", "created": 1,
                         "model": model, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                         "usage": usage}
                self.wfile.write(("data: " + json.dumps(final) + "\n\ndata: [DONE]\n\n").encode())
                self.wfile.flush()
                self.close_connection = True

            def _stream_reasoning(self, model: str, usage: dict) -> None:
                """Stream reasoning deltas before the reply, as reasoning models do."""
                deltas = ([{"role": "assistant", "reasoning_content": REASONING_CHUNKS[0]}]
                          + [{"reasoning_content": text} for text in REASONING_CHUNKS[1:]]
                          + [{"content": REASONING_REPLY}])
                body = "".join("data: " + json.dumps({
                    "id": "hermes-api-stub", "object": "chat.completion.chunk", "created": 1, "model": model,
                    "choices": [{"index": 0, "delta": delta, "finish_reason": None}]}) + "\n\n" for delta in deltas)
                final = {"id": "hermes-api-stub", "object": "chat.completion.chunk", "created": 1, "model": model,
                         "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}], "usage": usage}
                self._answer("text/event-stream",
                             (body + "data: " + json.dumps(final) + "\n\ndata: [DONE]\n\n").encode())

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
                scripted = next((script for prompt, script in SCRIPTED_TOOLS.items() if prompt in latest_user), None)
                latest_user_index = max((index for index, message in enumerate(messages)
                                         if message.get("role") == "user"), default=-1)
                answered_tool = any(message.get("role") == "tool" for message in messages[latest_user_index + 1:])
                if scripted and not answered_tool:
                    tool_name, arguments = scripted[0], json.dumps(scripted[1])
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
                if RECONNECT_PROMPT in latest_user and request.get("stream"):
                    self._stream_slowly(model, usage)
                    return
                if LONG_PROMPT in latest_user and request.get("stream"):
                    self._stream_slowly(model, usage, LONG_CHUNKS, 0)
                    return
                if PACED_PROMPT in latest_user and request.get("stream"):
                    self._stream_slowly(model, usage, PACED_CHUNKS, PACED_CHUNK_DELAY)
                    return
                if REASONING_PROMPT in latest_user and request.get("stream"):
                    self._stream_reasoning(model, usage)
                    return
                reply = scripted[2] if scripted else REPLY
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
