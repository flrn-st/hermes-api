"""Record real JSON-RPC frames after validating them with the tagged Pydantic catalog."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import socket
import time
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, quote, urlencode, urlparse
from urllib.request import (
    HTTPCookieProcessor,
    HTTPRedirectHandler,
    OpenerDirector,
    Request,
    build_opener,
)

import yaml
from tagside.openapi_check import Validator
from tui_gateway.contracts import EVENTS, METHODS, SERVER_REQUESTS
from websockets.asyncio.client import connect


def _resolve(value: object, captured: dict[str, object]) -> object:
    if isinstance(value, str) and value.startswith("${") and value.endswith("}"):
        return captured[value[2:-1]]
    if isinstance(value, list):
        return [_resolve(item, captured) for item in value]
    if isinstance(value, dict):
        return {key: _resolve(item, captured) for key, item in value.items()}
    return value


BINARY = {"type": "string", "format": "binary"}
TEXT = {"type": "string"}


class _NoRedirect(HTTPRedirectHandler):
    """A redirect is an operation's documented result, not something to follow."""

    def redirect_request(self, *_args: object, **_kwargs: object) -> None:
        return None


_OPENER = build_opener(_NoRedirect)


def _fetch_rest(request: Request, opener: OpenerDirector = _OPENER) -> tuple[int, dict[str, str], bytes]:
    try:
        with opener.open(request, timeout=120) as response:
            return response.status, {k.lower(): v for k, v in response.headers.items()}, response.read()
    except HTTPError as error:
        return error.code, {k.lower(): v for k, v in error.headers.items()}, error.read()


def _query_value(value: object) -> str:
    # FastAPI reads booleans as true/false; Python would send True/False.
    return ("true" if value else "false") if isinstance(value, bool) else str(value)


def _multipart(fields: dict) -> tuple[bytes, str]:
    boundary = "hermes-api-record-boundary"
    parts: list[bytes] = []
    for name, value in fields.items():
        if isinstance(value, dict):
            head = (f'Content-Disposition: form-data; name="{name}"; filename="{value["filename"]}"\r\n'
                    f'Content-Type: {value.get("content_type", "application/octet-stream")}\r\n')
            data = value["text"].encode("utf-8")
        else:
            head = f'Content-Disposition: form-data; name="{name}"\r\n'
            data = _query_value(value).encode("utf-8")
        parts.append(f"--{boundary}\r\n{head}\r\n".encode() + data + b"\r\n")
    return b"".join(parts) + f"--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


def _lookup(value: object, path: str) -> object:
    """A dotted path into a JSON value; ``$`` is the whole value."""
    if path == "$":
        return value
    for key in path.split("."):
        if isinstance(value, dict) and key in value:
            value = value[key]
        elif isinstance(value, list) and key.isdigit() and int(key) < len(value):
            value = value[int(key)]
        else:
            raise KeyError(path)
    return value


def _capture(body: object, pointer: str) -> object:
    """``path`` is a dotted path; ``path#param`` is the query parameter ``param`` of the URL there."""
    path, _, param = pointer.partition("#")
    value = _lookup(body, path)
    if not param:
        return value
    values = parse_qs(urlparse(str(value)).query).get(param)
    if not values:
        raise KeyError(pointer)
    return values[0]


def _substitute(value: object, captured: dict[str, object]) -> object:
    """``${name}`` is the captured value; inside a longer string it is the value's text."""
    if isinstance(value, str):
        if value.startswith("${") and value.endswith("}") and "${" not in value[2:]:
            return captured[value[2:-1]]
        for name, replacement in captured.items():
            if "${" + name + "}" in value:
                if not isinstance(replacement, (str, int)) or isinstance(replacement, bool):
                    raise TypeError(f"Captured {name} is not text")
                value = value.replace("${" + name + "}", str(replacement))
        return value
    if isinstance(value, list):
        return [_substitute(item, captured) for item in value]
    if isinstance(value, dict):
        return {key: _substitute(item, captured) for key, item in value.items()}
    return value


def _redact_host(frame: dict) -> tuple[dict, list[str]]:
    """Replace the recording machine's home directory and hostname in a recorded frame.

    Responses echo host paths (working directories, plugin paths) and the hostname; fixtures are
    committed, so they carry stable placeholders instead. Captures still use the real values.
    """
    home = str(Path.home())
    hosts = {name for name in (socket.gethostname(), socket.gethostname().split(".")[0]) if name}
    applied: set[str] = set()

    def clean(value: object) -> object:
        if isinstance(value, str):
            if home and home in value:
                value = value.replace(home, "/home/recorder")
                applied.add("home")
            for host in hosts:
                if host in value:
                    value = value.replace(host, "recorder-host")
                    applied.add("hostname")
            return value
        if isinstance(value, list):
            return [clean(item) for item in value]
        if isinstance(value, dict):
            return {key: clean(item) for key, item in value.items()}
        return value

    return clean(frame), sorted(applied)


def record_rest(calls: list[dict], document: dict, base: str, token: str | None,
                captured: dict[str, object] | None = None, opener: OpenerDirector = _OPENER) -> list[dict]:
    """Run the REST scenario against Hermes, validating every response against its reviewed schema.

    ``token`` is the loopback dashboard's session token; without one the calls start unauthenticated
    and ``auth: native`` signs a call with the Bearer access token captured as ``tokens``.
    """
    validator = Validator(document.get("components", {}).get("schemas", {}))
    captured = {} if captured is None else captured
    entries: list[dict] = []
    for index, call in enumerate(calls, 1):
        operation_name = call["operation"]
        method, template = operation_name.split(" ", 1)
        operation = document["paths"][template][method.lower()]
        if "x-handler-hash" not in operation:
            raise ValueError(f"REST scenario calls an unreviewed operation: {operation_name}")
        path = template
        for name, value in _substitute(call.get("path", {}), captured).items():
            encoded = quote(str(value), safe="")
            path = path.replace("{" + name + "}", encoded).replace("{" + name + ":path}", encoded)
        if "{" in path:
            raise ValueError(f"REST scenario call {index} leaves a path parameter unset: {path}")
        query = {name: _query_value(value) for name, value in _substitute(call.get("query", {}), captured).items()
                 if value is not None}
        url = base + path + ("?" + urlencode(query, quote_via=quote) if query else "")
        headers = {"X-Hermes-Session-Token": token} if token else {}
        if call.get("auth") == "native":
            headers["Authorization"] = f"Bearer {captured['tokens']['access_token']}"
        data = None
        if "form" in call:
            data, headers["Content-Type"] = _multipart(_substitute(call["form"], captured))
        elif "body" in call:
            data = json.dumps(_substitute(call["body"], captured)).encode("utf-8")
            headers["Content-Type"] = "application/json"
        until = call.get("until")
        deadline = time.monotonic() + (until or {}).get("timeout", 0)
        while True:
            status, response_headers, raw = _fetch_rest(Request(url, method=method, headers=headers, data=data), opener)
            if not until or not 200 <= status < 300:
                break
            try:
                reached = _lookup(json.loads(raw), until["path"]) == until["equals"]
            except (KeyError, ValueError):
                reached = False
            if reached:
                break
            if time.monotonic() > deadline:
                raise TimeoutError(f"REST scenario call {index} {operation_name} never reached {until}")
            time.sleep(0.5)
        where = f"REST scenario call {index} {operation_name}"
        documented = operation["responses"].get(str(status))
        if documented is None or not 200 <= status < 400:
            raise ValueError(f"{where} answered {status}: {raw[:600].decode('utf-8', 'replace')}")
        media = response_headers.get("content-type", "").split(";")[0].strip()
        frame: dict[str, object] = {"status": status}
        content = documented.get("content", {})
        documented_media = next((item for item in content if item == media), None) or next(
            (item for item in content if item == "*/*" or (item.endswith("/*") and media.startswith(item[:-1]))), None)
        schema = content.get(documented_media, {}).get("schema") if documented_media else None
        if status >= 300:
            frame["location"] = response_headers["location"]
        elif method == "HEAD":
            pass
        elif content and documented_media is None:
            raise ValueError(f"{where} answered undocumented media {media!r}; documented {sorted(content)}")
        elif documented_media == "application/json" and schema not in (BINARY, TEXT):
            body = json.loads(raw)
            errors = validator.errors(body, schema)
            if errors:
                raise ValueError(f"{where} violates its reviewed schema: {errors[:8]}")
            frame.update(media=media, body=body)
            for name, pointer in call.get("capture", {}).items():
                captured[name] = _capture(body, pointer)
        elif all(item.startswith("text/") for item in content):
            frame.update(media=media, text=raw.decode("utf-8"))
        else:
            frame.update(media=media, size=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        redacted, redactions = _redact_host(frame)
        entries.append({"kind": "rest", "name": operation_name, "frame": redacted,
                        **({"redactions": redactions} if redactions else {})})
    return entries


async def record(scenario: Path, output: Path, openapi: Path, rest_scenario: Path | None = None,
                 gated_url: str | None = None) -> None:
    definition = yaml.safe_load(scenario.read_text(encoding="utf-8"))
    if not isinstance(definition, dict) or not isinstance(definition.get("calls"), list):
        raise TypeError("Invalid scenario")
    url = os.environ["HERMES_LIVE_URL"].replace("http://", "ws://").replace("https://", "wss://")
    token = os.environ["HERMES_LIVE_TOKEN"]
    entries: list[dict] = []
    captured: dict[str, object] = {}
    seen_events: list[str] = []
    seen_requests: list[str] = []
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
                    recorded_frame = json.loads(json.dumps(frame))
                    redacted_fields: list[str] = []
                    if name == "session.info" and "system_prompt" in recorded_frame["params"]["payload"]:
                        recorded_frame["params"]["payload"]["system_prompt"] = "<redacted>"
                        redacted_fields.append("params.payload.system_prompt")
                    entries.append({"kind": "event", "name": name, "frame": recorded_frame,
                                    **({"redacted_fields": redacted_fields} if redacted_fields else {})})
                    if expected_id is None:
                        return frame
                    continue
                if isinstance(frame.get("id"), str) and frame.get("method") in SERVER_REQUESTS:
                    name = frame["method"]
                    contract = SERVER_REQUESTS[name]
                    contract.params.model_validate(frame.get("params", {}))
                    answer = _resolve(definition.get("server_requests", {})[name], captured)
                    contract.result.model_validate(answer)
                    await socket.send(json.dumps({"jsonrpc": "2.0", "id": frame["id"], "result": answer}))
                    seen_requests.append(name)
                    entries.append({"kind": "server_request", "name": name, "frame": frame,
                                    "answer": answer})
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
            seen_requests.clear()
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
            expected_request = call.get("wait_for_server_request")
            if expected_request and expected_request not in seen_requests:
                raise ValueError(f"Expected server request {expected_request} was not received")
    document = json.loads(openapi.read_text(encoding="utf-8"))
    rest = yaml.safe_load(rest_scenario.read_text(encoding="utf-8")) if rest_scenario else {}
    entries.extend(await asyncio.to_thread(
        record_rest, rest.get("calls", []), document, os.environ["HERMES_LIVE_URL"], token))
    if gated_url and rest.get("gated_calls"):
        # The gated server authenticates by cookie and Bearer token, so this run keeps a cookie jar.
        opener = build_opener(_NoRedirect, HTTPCookieProcessor(CookieJar()))
        entries.extend(await asyncio.to_thread(
            record_rest, rest["gated_calls"], document, gated_url, None, None, opener))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("".join(json.dumps(entry, sort_keys=True) + "\n" for entry in entries), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--openapi", type=Path, required=True)
    parser.add_argument("--rest-scenario", type=Path)
    parser.add_argument("--gated-url", help="a second tagged server behind the dashboard auth gate")
    args = parser.parse_args()
    asyncio.run(record(args.scenario, args.output, args.openapi, args.rest_scenario, args.gated_url))


if __name__ == "__main__":
    main()
