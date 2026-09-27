"""Pytest plugin: hold the tagged Hermes handlers to the reviewed REST overlays.

Loaded into the tag's own test suite by ``tools/contract_gate.py`` (``-p contract_gate_plugin``).
It wraps every FastAPI route before any router exists and records, for each response of an
overlaid operation, whether the reviewed OpenAPI documents its status and media type and whether a
JSON body validates. It records rather than raises, so one drifted contract cannot hide another and
the suite's own assertions still decide its tests.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from fastapi.routing import APIRoute
from openapi_check import Validator

_DOCUMENT = json.loads(Path(os.environ["HERMES_CONTRACT_OPENAPI"]).read_text(encoding="utf-8"))
_REPORT = Path(os.environ["HERMES_CONTRACT_REPORT"])
_VALIDATOR = Validator(_DOCUMENT.get("components", {}).get("schemas", {}))
_OPERATIONS = {
    f"{method.upper()} {path}": operation
    for path, methods in _DOCUMENT["paths"].items()
    for method, operation in methods.items()
    if "x-handler-hash" in operation
}


def _media_matches(media: str, documented: str) -> bool:
    if documented == "*/*":
        return True
    if documented.endswith("/*"):
        return media.startswith(documented[:-1])
    return media == documented


def _check(route: APIRoute, method: str, response: object) -> None:
    key = f"{method} {route.path_format}"
    operation = _OPERATIONS.get(key)
    status = getattr(response, "status_code", 0)
    if operation is None or not 200 <= status < 400:
        return
    record: dict[str, object] = {"operation": key, "status": status,
                                 "test": os.environ.get("PYTEST_CURRENT_TEST", "").split(" ")[0]}
    media = (getattr(response, "headers", {}).get("content-type") or "").split(";")[0].strip()
    documented = operation["responses"].get(str(status))
    body = getattr(response, "body", None)
    errors: list[str] = []
    if documented is None:
        errors.append(f"status {status} is not documented")
    elif status < 300 and method != "HEAD":
        content = documented.get("content", {})
        if not any(_media_matches(media, item) for item in content):
            errors.append(f"media {media!r} is not documented for {status}: {sorted(content)}")
        elif "json" in media and isinstance(body, (bytes, bytearray)):
            schema = content.get("application/json", {}).get("schema")
            if schema is not None:
                try:
                    value = json.loads(body)
                except ValueError:
                    errors.append("body is not JSON")
                else:
                    errors.extend(_VALIDATOR.errors(value, schema)[:8])
        elif not isinstance(body, (bytes, bytearray)):
            record["streamed"] = True
    record["errors"] = errors
    with _REPORT.open("a", encoding="utf-8") as report:
        report.write(json.dumps(record, sort_keys=True) + "\n")


_original = APIRoute.get_route_handler


def _get_route_handler(self: APIRoute):
    handler = _original(self)

    async def checked(request):
        response = await handler(request)
        _check(self, request.method, response)
        return response

    return checked


APIRoute.get_route_handler = _get_route_handler
