"""Apply reviewed REST response overlays only when their handler source hashes match.

An overlay file holds ``operations:`` and, optionally, the named ``components:`` its schemas
reference. Each operation names the tagged handler (``x-source``, ``x-handler-hash``) and documents
its success responses, either as ``response`` (the 200 ``application/json`` schema) or as
``responses`` (OpenAPI response objects by 2xx/3xx status: JSON, binary, text or a redirect).
``x-contract`` optionally traces the schema to the response contract it was drafted from, and
``request`` documents a JSON body the handler reads without declaring it (``await request.json()``).
The documented responses replace the operation's success responses, which must all be untyped
(FastAPI's default); nothing may replace a schema upstream already documents.
"""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

import yaml

from tools.fetch_spec import ROOT
from tools.ref_policy import require_release

REQUIRED = {"method", "path", "x-source", "x-handler-hash"}
OPTIONAL = {"x-contract", "request"}
# Keywords that describe a schema without constraining it; a schema of only these is untyped.
ANNOTATIONS = {"title", "description"}
BINARY = {"type": "string", "format": "binary"}
TEXT = {"type": "string"}


def untyped(schema: object) -> bool:
    return isinstance(schema, dict) and not set(schema) - ANNOTATIONS


def _typed_json(schema: object, key: str) -> None:
    if not isinstance(schema, dict) or untyped(schema) or schema == {"type": "object"}:
        raise ValueError(f"REST response is not meaningfully typed: {key}")


def _success_responses(entry: dict, key: str) -> dict[str, dict]:
    if ("response" in entry) == ("responses" in entry):
        raise ValueError(f"REST overlay needs exactly one of response or responses: {key}")
    if "response" in entry:
        _typed_json(entry["response"], key)
        return {"200": {"description": "Successful Response",
                        "content": {"application/json": {"schema": entry["response"]}}}}
    responses = entry["responses"]
    if not isinstance(responses, dict) or not responses:
        raise ValueError(f"REST overlay responses must be a non-empty mapping: {key}")
    result: dict[str, dict] = {}
    for status, response in responses.items():
        status = str(status)
        if not (len(status) == 3 and status[0] in "23" and status.isdigit()):
            raise ValueError(f"REST overlay documents only success statuses: {key} {status}")
        if not isinstance(response, dict) or set(response) - {"description", "content", "headers"}:
            raise ValueError(f"Invalid REST overlay response {status}: {key}")
        content = response.get("content", {})
        if status.startswith("3"):
            location = response.get("headers", {}).get("Location", {}).get("schema")
            if content or location != TEXT:
                raise ValueError(f"A redirect documents only its Location header: {key} {status}")
        elif status == "204":
            if content:
                raise ValueError(f"A 204 response has no body: {key}")
        elif not content:
            raise ValueError(f"REST response has no documented body: {key} {status}")
        for media, body in content.items():
            schema = body.get("schema") if isinstance(body, dict) else None
            if media == "application/json":
                _typed_json(schema, key)
            elif schema not in (BINARY, TEXT):
                raise ValueError(f"Non-JSON body must be a binary or text string: {key} {media}")
        result[status] = copy.deepcopy(response)
    return result


def apply(ref: str, *, check: bool = False, root: Path = ROOT) -> dict[str, int]:
    ref = require_release(ref)
    output = root / "spec" / "out" / ref
    document = json.loads((output / "openapi.raw.json").read_text(encoding="utf-8"))
    hashes = json.loads((output / "rest-hashes.json").read_text(encoding="utf-8"))
    schemas = document.setdefault("components", {}).setdefault("schemas", {})
    seen: set[str] = set()
    for path in sorted((root / "spec" / "overlay" / "rest").rglob("*.yaml")):
        overlay = yaml.safe_load(path.read_text(encoding="utf-8"))
        if (not isinstance(overlay, dict) or not isinstance(overlay.get("operations"), list)
                or set(overlay) - {"operations", "components"}):
            raise TypeError(f"Invalid REST overlay: {path}")
        components = overlay.get("components", {})
        if not isinstance(components, dict):
            raise TypeError(f"Invalid REST overlay components: {path}")
        for name, schema in components.items():
            if name in schemas:
                raise ValueError(f"REST overlay component {name} clashes with an existing schema: {path}")
            if not isinstance(schema, dict) or untyped(schema):
                raise ValueError(f"REST overlay component {name} is not meaningfully typed: {path}")
            schemas[name] = copy.deepcopy(schema)
        for entry in overlay["operations"]:
            if not isinstance(entry, dict):
                raise TypeError(f"Invalid operation in {path}")
            fields = set(entry) - {"response", "responses"}
            if not REQUIRED <= fields or fields - REQUIRED - OPTIONAL:
                raise ValueError(f"REST overlay fields must be {sorted(REQUIRED)}, a response and "
                                 f"optionally {sorted(OPTIONAL)}: {path}")
            method = entry["method"].upper()
            route = entry["path"]
            key = f"{method} {route}"
            if key in seen:
                raise ValueError(f"Duplicate REST overlay operation: {key}")
            seen.add(key)
            actual = hashes.get(key)
            if actual is None:
                raise ValueError(f"Unknown REST operation: {key}")
            if actual["source"] != entry["x-source"] or actual["handler_sha256"] != entry["x-handler-hash"]:
                raise ValueError(f"Stale handler source for {key}; review the tagged handler before updating overlay")
            operation = document["paths"][route][method.lower()]
            # The overlay documents every success response; upstream's are FastAPI's untyped defaults.
            for status in [status for status in operation["responses"] if status[0] in "23"]:
                current = operation["responses"].pop(status).get("content", {})
                if any(not untyped(body.get("schema", {})) for body in current.values()):
                    raise ValueError(f"Overlay would replace an upstream response schema: {key}")
            operation["responses"].update(_success_responses(entry, key))
            if "request" in entry:
                request = entry["request"]
                if "requestBody" in operation:
                    raise ValueError(f"Overlay would replace an upstream request body: {key}")
                if (not isinstance(request, dict) or set(request) != {"required", "schema"}
                        or not isinstance(request["required"], bool)):
                    raise ValueError(f"REST overlay request needs exactly required and schema: {key}")
                _typed_json(request["schema"], key)
                operation["requestBody"] = {"required": request["required"], "content": {
                    "application/json": {"schema": copy.deepcopy(request["schema"])}}}
            if "x-contract" in entry:
                contract = entry["x-contract"]
                if not isinstance(contract, dict) or set(contract) != {"commit", "model"}:
                    raise ValueError(f"x-contract needs exactly commit and model: {key}")
                operation["x-contract"] = copy.deepcopy(contract)
            operation["x-source"] = entry["x-source"]
            operation["x-handler-hash"] = entry["x-handler-hash"]
    _check_refs(document)
    rendered = json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    target = output / "openapi.json"
    if check:
        if not target.exists() or target.read_text(encoding="utf-8") != rendered:
            raise ValueError(f"Generated REST OpenAPI is stale: {target}")
    else:
        target.write_text(rendered, encoding="utf-8")
    return {"operations": len(hashes), "overlaid": len(seen)}


def _check_refs(document: dict) -> None:
    schemas = document.get("components", {}).get("schemas", {})

    def walk(value: object) -> None:
        if isinstance(value, dict):
            ref = value.get("$ref")
            if (isinstance(ref, str) and ref.startswith("#/components/schemas/")
                    and ref.rsplit("/", 1)[-1] not in schemas):
                raise ValueError(f"REST overlay references an undefined component: {ref}")
            for item in value.values():
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)

    walk(document)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    print(json.dumps(apply(args.ref, check=args.check), sort_keys=True))


if __name__ == "__main__":
    main()
