"""Report contract coverage honestly and block release publication below 100%."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from tools.fetch_spec import ROOT
from tools.ref_policy import require_release_tag


def _strict(schema: dict, components: dict, visited: set[str] | None = None) -> bool:
    if not schema:
        return False
    visited = visited or set()
    if "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        if name in visited:
            return True
        return _strict(components[name], components, visited | {name})
    if schema.get("additionalProperties") is True:
        return False
    if isinstance(schema.get("additionalProperties"), dict) and not _strict(schema["additionalProperties"], components, visited):
        return False
    if any(not _strict(value, components, visited) for value in schema.get("properties", {}).values()):
        return False
    if "items" in schema and not _strict(schema["items"], components, visited):
        return False
    for key in ("anyOf", "oneOf", "allOf"):
        if any(not _strict(value, components, visited) for value in schema.get(key, [])):
            return False
    return True


def _gateway_entries(contract: dict, symbols: dict) -> list[dict]:
    components = contract["components"]["schemas"]
    result: list[dict] = []
    for section, symbol_key, kind in (
        ("methods", "methods", "gateway_method"),
        ("x-server-requests", "server_requests", "server_request"),
        ("x-notifications", "events", "event"),
    ):
        generated = {item["wire"] for item in symbols[symbol_key]}
        for item in contract[section]:
            schemas = ([item["result"]["schema"]] if "result" in item else
                       [param["schema"] for param in item.get("params", [])])
            result.append({
                "kind": kind, "name": item["name"],
                "typed": all(_strict(schema, components) for schema in schemas),
                "generated": item["name"] in generated,
                "fixture": False, "decode": False, "live_swift": False, "live_kotlin": False,
            })
    return result


def _rest_entries(document: dict, generated_symbols: list[dict], hashes: dict) -> list[dict]:
    generated = {(item["method"], item["path"]) for item in generated_symbols}
    result: list[dict] = []
    for path, operations in document["paths"].items():
        for method, operation in operations.items():
            key = f"{method.upper()} {path}"
            responses = [value for status, value in operation["responses"].items() if status.startswith("2")]
            schemas = [media["schema"] for response in responses for media in response.get("content", {}).values()]
            typed = bool(schemas) and all(_strict(schema, document["components"]["schemas"]) for schema in schemas)
            if not schemas and method.lower() == "head":
                typed = True
            fresh = (operation.get("x-source") == hashes.get(key, {}).get("source") and
                     operation.get("x-handler-hash") == hashes.get(key, {}).get("handler_sha256"))
            result.append({
                "kind": "rest", "name": key,
                "typed": typed, "generated": (method.upper(), path) in generated,
                "fixture": False, "decode": False, "live_swift": False, "live_kotlin": False,
                "fresh": fresh,
            })
    return result


def report(ref: str, root: Path = ROOT) -> dict:
    ref = require_release_tag(ref)
    source = root / "spec/out" / ref
    contract = json.loads((source / "openrpc.json").read_text())
    symbols = json.loads((source / "gateway-symbols.json").read_text())
    openapi_path = source / "openapi.json"
    document = json.loads((openapi_path if openapi_path.exists() else source / "openapi.raw.json").read_text())
    generated_path = source / "generated-rest-symbols.json"
    rest_symbols = json.loads(generated_path.read_text()) if generated_path.exists() else []
    hashes = json.loads((source / "rest-hashes.json").read_text())
    entries = _gateway_entries(contract, symbols) + _rest_entries(document, rest_symbols, hashes)
    for entry in entries:
        checks = ("typed", "generated", "fixture", "decode", "live_swift", "live_kotlin")
        entry["complete"] = all(entry[name] for name in checks) and (entry.get("fresh", True))
    by_kind: dict[str, dict[str, int]] = {}
    for kind in ("gateway_method", "server_request", "event", "rest"):
        subset = [entry for entry in entries if entry["kind"] == kind]
        by_kind[kind] = {"total": len(subset), "complete": sum(entry["complete"] for entry in subset),
                         "typed": sum(entry["typed"] for entry in subset),
                         "generated": sum(entry["generated"] for entry in subset),
                         "fixture": sum(entry["fixture"] for entry in subset),
                         "decode": sum(entry["decode"] for entry in subset),
                         "live_both": sum(entry["live_swift"] and entry["live_kotlin"] for entry in subset)}
    return {"ref": ref, "summary": by_kind, "entries": entries,
            "total": len(entries), "complete": sum(entry["complete"] for entry in entries)}


def write_report(ref: str, root: Path = ROOT) -> dict:
    data = report(ref, root)
    output = root / "coverage"
    output.mkdir(exist_ok=True)
    (output / f"{ref}.json").write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
    lines = [f"# Coverage for {ref}", "", f"Complete: **{data['complete']} / {data['total']}**", "",
             "| Surface | Complete | Typed | Generated | Fixture | Decode | Live both | Total |",
             "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for kind, counts in data["summary"].items():
        lines.append(f"| {kind} | {counts['complete']} | {counts['typed']} | {counts['generated']} | "
                     f"{counts['fixture']} | {counts['decode']} | {counts['live_both']} | {counts['total']} |")
    lines.extend(["", "This report counts no fixture, decode, or live scenario evidence until the tag-pinned recorder and both client suites produce it. The live ping smoke test is a connection check, not full operation coverage.", ""])
    (output / f"{ref}.md").write_text("\n".join(lines))
    return data


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--gate", action="store_true", help="Fail if any operation lacks required evidence")
    args = parser.parse_args()
    data = write_report(args.ref)
    print(f"Coverage {data['complete']}/{data['total']} for {args.ref}")
    if args.gate and data["complete"] != data["total"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
