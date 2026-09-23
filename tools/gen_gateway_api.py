"""Generate typed gateway method namespaces, events, and server requests."""

from __future__ import annotations

import argparse
import json
from collections import defaultdict

from jinja2 import Environment, FileSystemLoader, StrictUndefined

from tools.gen_gateway_models import (
    KOTLIN_OUT,
    ROOT,
    SWIFT_OUT,
    TEMPLATES,
    _kotlin,
    _literal,
    _swift,
)
from tools.naming import camel, checked_method_symbols, pascal
from tools.ref_policy import require_release_tag


def _ref(schema: dict[str, object]) -> str:
    value = schema.get("$ref")
    if not isinstance(value, str) or not value.startswith("#/components/schemas/"):
        raise ValueError(f"Expected a named component schema: {schema}")
    return value.rsplit("/", 1)[-1]


def _method_view(item: dict[str, object], public_name: str) -> dict[str, str]:
    params = item["params"]
    if not isinstance(params, list) or len(params) != 1:
        raise ValueError(f"Expected one params schema for {item['name']}")
    return {
        "wire_name": item["name"],
        "wire_literal": _literal(item["name"]),
        "swift_name": _swift(public_name),
        "kotlin_name": _kotlin(public_name),
        "params": _ref(params[0]["schema"]),
        "result": _ref(item["result"]["schema"]),
    }


def _views(contract: dict[str, object]) -> tuple[dict[str, object], dict[str, object]]:
    methods = contract["methods"]
    symbols = checked_method_symbols(item["name"] for item in methods)
    namespaces: dict[str, list[dict[str, str]]] = defaultdict(list)
    root_methods: list[dict[str, str]] = []
    for item, symbol in zip(methods, symbols, strict=True):
        view = _method_view(item, symbol.name)
        (namespaces[symbol.namespace] if symbol.namespace else root_methods).append(view)
    namespace_views = [
        {
            "wire_name": name,
            "swift_name": _swift(name),
            "kotlin_name": _kotlin(name),
            "type_name": f"{pascal(name)}Methods",
            "methods": sorted(items, key=lambda item: item["wire_name"]),
        }
        for name, items in sorted(namespaces.items())
    ]
    method_view = {"namespaces": namespace_views,
                   "root_methods": sorted(root_methods, key=lambda item: item["wire_name"])}

    events: list[dict[str, object]] = []
    for item in contract["x-notifications"]:
        params = item["params"]
        if len(params) != 1:
            raise ValueError(f"Expected one event payload: {item['name']}")
        schema = params[0]["schema"]
        empty = schema == {"type": "object", "additionalProperties": False}
        payload = "EmptyObject" if empty else _ref(schema)
        events.append({
            "wire_name": item["name"], "wire_literal": _literal(item["name"]),
            "swift_name": _swift(camel(item["name"])),
            "kotlin_name": pascal(item["name"]), "payload": payload, "empty": empty,
        })
    requests: list[dict[str, str]] = []
    for item in contract["x-server-requests"]:
        view = _method_view(item, camel(item["name"]))
        view["kotlin_name"] = pascal(item["name"])
        requests.append(view)
    events.sort(key=lambda item: item["wire_name"])
    requests.sort(key=lambda item: item["wire_name"])
    return method_view, {"events": events, "requests": requests}


def generate(ref: str, *, check: bool = False) -> int:
    require_release_tag(ref)
    contract = json.loads((ROOT / "spec" / "out" / ref / "openrpc.json").read_text())
    meta = json.loads((ROOT / "spec" / "out" / ref / "meta.json").read_text())
    methods, events = _views(contract)
    env = Environment(loader=FileSystemLoader(TEMPLATES), undefined=StrictUndefined,
                      trim_blocks=True, lstrip_blocks=True, keep_trailing_newline=True)
    outputs = {
        SWIFT_OUT / "GatewayMethods.swift": env.get_template("swift_methods.j2").render(**methods),
        KOTLIN_OUT / "GatewayMethods.kt": env.get_template("kotlin_methods.j2").render(**methods),
        SWIFT_OUT / "GatewayEvents.swift": env.get_template("swift_events.j2").render(**events),
        KOTLIN_OUT / "GatewayEvents.kt": env.get_template("kotlin_events.j2").render(**events),
        SWIFT_OUT / "GatewayRelease.swift": env.get_template("swift_release.j2").render(
            ref_literal=_literal(meta["ref"]), version_literal=_literal(meta["hermes_version"]),
            desktop_contract=meta["desktop_contract"]),
        KOTLIN_OUT / "GatewayRelease.kt": env.get_template("kotlin_release.j2").render(
            ref_literal=_literal(meta["ref"]), version_literal=_literal(meta["hermes_version"]),
            desktop_contract=meta["desktop_contract"]),
    }
    manifest = {
        "methods": [
            {"wire": item["wire_name"], "public": (f"{ns['wire_name']}.{item['swift_name']}"),
             "params": item["params"], "result": item["result"]}
            for ns in methods["namespaces"] for item in ns["methods"]
        ],
        "events": [{"wire": item["wire_name"], "public": item["swift_name"]}
                   for item in events["events"]],
        "server_requests": [{"wire": item["wire_name"], "public": item["swift_name"],
                             "params": item["params"], "result": item["result"]}
                            for item in events["requests"]],
    }
    for item in methods["root_methods"]:
        manifest["methods"].append({"wire": item["wire_name"], "public": item["swift_name"],
                                    "params": item["params"], "result": item["result"]})
    manifest["methods"].sort(key=lambda item: item["wire"])
    outputs[ROOT / "spec" / "out" / ref / "gateway-symbols.json"] = (
        json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    )
    changed = [path for path, content in outputs.items()
               if not path.exists() or path.read_text(encoding="utf-8") != content]
    if check:
        for path in changed:
            print(f"Stale generated output: {path.relative_to(ROOT)}")
        return len(changed)
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    print(f"Generated {len(manifest['methods'])} methods, {len(manifest['events'])} events, "
          f"{len(manifest['server_requests'])} server requests")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    raise SystemExit(1 if generate(args.ref, check=args.check) else 0)


if __name__ == "__main__":
    main()
