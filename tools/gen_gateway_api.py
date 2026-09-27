"""Generate typed gateway method namespaces, events, and server requests."""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict

import yaml
from jinja2 import Environment, FileSystemLoader, StrictUndefined

from tools.gateway_errors import KIND_SUMMARIES, KINDS, Catalog, MethodError, segments
from tools.gateway_errors import load as load_errors
from tools.gen_gateway_models import (
    KOTLIN_OUT,
    ROOT,
    SWIFT_OUT,
    TEMPLATES,
    _kdoc_safe,
    _kotlin,
    _literal,
    _swift,
)
from tools.naming import camel, checked_method_symbols, pascal
from tools.ref_policy import require_release


def _ref(schema: dict[str, object]) -> str:
    value = schema.get("$ref")
    if not isinstance(value, str) or not value.startswith("#/components/schemas/"):
        raise ValueError(f"Expected a named component schema: {schema}")
    return value.rsplit("/", 1)[-1]


# Messages listed per code in a method's doc comment; the rest are in spec/out/<ref>/gateway-errors.json.
_DOC_MESSAGES = 4


def _error_lines(errors: tuple[MethodError, ...], catalog: Catalog) -> list[str]:
    """One line per code a handler can answer with: its kinds and the messages that carry them."""
    rules = {code.code: code for code in catalog.codes}
    lines = []
    relayed = False
    for error in errors:
        if error.code is None:
            relayed = True
            continue
        rule = rules[error.code]
        by_kind: dict[str, list[str]] = {}
        for message in error.messages:
            by_kind.setdefault(dict(rule.messages).get(message, rule.kind), []).append(message)
        parts = []
        for kind in sorted(by_kind, key=lambda item: (item != rule.kind, item)):
            shown = [f'"{message}"' for message in by_kind[kind][:_DOC_MESSAGES]]
            more = ", …" if len(by_kind[kind]) > _DOC_MESSAGES else ""
            parts.append(f"{kind}: {', '.join(shown)}{more}")
        lines.append(f"- {error.code} " + ("; ".join(parts) if parts else rule.kind))
    if relayed:
        lines.append("- codes relayed unchanged from a compute host, plugin or connector service")
    return lines


def _docs(summary: str, errors: list[str]) -> tuple[list[str], list[str]]:
    text = " ".join(summary.split())
    body = [text] if text else []
    if errors:
        body += ([""] if body else []) + ["Hermes' handler can answer with these errors:"] + errors
    swift = [f"/// {line}".rstrip() for line in body]
    kotlin = [] if not body else (["/**"] + [f" * {_kdoc_safe(line)}".rstrip() for line in body] + [" */"])
    return swift, kotlin


def _method_view(item: dict[str, object], public_name: str, catalog: Catalog | None = None) -> dict[str, object]:
    params = item["params"]
    if not isinstance(params, list) or len(params) != 1:
        raise ValueError(f"Expected one params schema for {item['name']}")
    errors = _error_lines(catalog.methods.get(item["name"], ()), catalog) if catalog else []
    swift_doc, kotlin_doc = _docs(str(item.get("summary") or ""), errors) if catalog else ([], [])
    return {
        "swift_doc": swift_doc,
        "kotlin_doc": kotlin_doc,
        "wire_name": item["name"],
        "wire_literal": _literal(item["name"]),
        "swift_name": _swift(public_name),
        "kotlin_name": _kotlin(public_name),
        "params": _ref(params[0]["schema"]),
        "result": _ref(item["result"]["schema"]),
    }


def _views(contract: dict[str, object], catalog: Catalog) -> tuple[dict[str, object], dict[str, object]]:
    methods = contract["methods"]
    missing = sorted({item["name"] for item in methods} - set(catalog.methods))
    if missing:
        raise ValueError(f"No extracted errors for {missing}; run make rest")
    symbols = checked_method_symbols(item["name"] for item in methods)
    namespaces: dict[str, list[dict[str, str]]] = defaultdict(list)
    root_methods: list[dict[str, str]] = []
    for item, symbol in zip(methods, symbols, strict=True):
        view = _method_view(item, symbol.name, catalog)
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


def _swift_literal(value: str) -> str:
    """A Swift string literal; JSON's ``\\uXXXX`` escapes are ``\\u{XXXX}`` in Swift."""
    # An even run of backslashes before "u" is escaped text, not an escape.
    return re.sub(r"(?<!\\)((?:\\\\)*)\\u([0-9a-fA-F]{4})", r"\1\\u{\2}", _literal(value))


def _template_literals(template: str | None) -> dict[str, str] | None:
    if template is None:
        return None
    parts = segments(template)
    return {"swift": "[" + ", ".join(_swift_literal(part) for part in parts) + "]",
            "kotlin": "listOf(" + ", ".join(_literal(part).replace("$", "\\$") for part in parts) + ")"}


def _constant(name: str) -> str:
    """A Kotlin enum constant: ``sessionNotFound`` becomes ``SESSION_NOT_FOUND``."""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()


def _error_views(catalog: Catalog) -> dict[str, object]:
    kinds = [{"name": kind, "summary": KIND_SUMMARIES[kind], "kotlin": _constant(kind)} for kind in KINDS]
    names = [{"name": name.name, "summary": name.summary, "kind": name.kind,
              "kotlin": _constant(name.name), "kotlin_kind": _constant(name.kind)} for name in catalog.names]
    name_rules: dict[int, list[dict[str, object]]] = defaultdict(list)
    for name in catalog.names:
        for code, template in name.match:
            name_rules[code].append({"template": _template_literals(template), "name": name.name,
                                     "kotlin": _constant(name.name), "specific": template is not None})
    codes = []
    for code in catalog.codes:
        codes.append({
            "code": code.code, "kind": code.kind, "kotlin_kind": _constant(code.kind),
            "messages": [{"template": _template_literals(template), "kind": kind, "kotlin_kind": _constant(kind)}
                         for template, kind in code.messages],
            # A message template before the entries that match every message of the code.
            "names": sorted(name_rules.get(code.code, []), key=lambda rule: not rule["specific"]),
        })
    return {"kinds": kinds, "names": names, "codes": codes}


def generate(ref: str, *, check: bool = False) -> int:
    require_release(ref)
    contract = json.loads((ROOT / "spec" / "out" / ref / "openrpc.json").read_text())
    meta = json.loads((ROOT / "spec" / "out" / ref / "meta.json").read_text())
    catalog = load_errors(ref)
    methods, events = _views(contract, catalog)
    errors = _error_views(catalog)
    read_only = yaml.safe_load((ROOT / "spec" / "gateway-read-only.yaml").read_text(encoding="utf-8"))["methods"]
    unknown = sorted(set(read_only) - {item["name"] for item in contract["methods"]})
    if unknown:
        raise ValueError(f"spec/gateway-read-only.yaml names methods the contract lacks: {unknown}")
    read_only_literals = ", ".join(_literal(name) for name in sorted(read_only))
    env = Environment(loader=FileSystemLoader(TEMPLATES), undefined=StrictUndefined,
                      trim_blocks=True, lstrip_blocks=True, keep_trailing_newline=True)
    outputs = {
        SWIFT_OUT / "GatewayMethods.swift": env.get_template("swift_methods.j2").render(**methods),
        KOTLIN_OUT / "GatewayMethods.kt": env.get_template("kotlin_methods.j2").render(**methods),
        SWIFT_OUT / "GatewayEvents.swift": env.get_template("swift_events.j2").render(**events),
        KOTLIN_OUT / "GatewayEvents.kt": env.get_template("kotlin_events.j2").render(**events),
        SWIFT_OUT / "GatewayErrors.swift": env.get_template("swift_errors.j2").render(**errors),
        KOTLIN_OUT / "GatewayErrors.kt": env.get_template("kotlin_errors.j2").render(**errors),
        SWIFT_OUT / "GatewayRelease.swift": env.get_template("swift_release.j2").render(
            ref_literal=_literal(meta["ref"]), version_literal=_literal(meta["hermes_version"]),
            tag_literal=_literal(meta["tag"]),
            desktop_contract=meta["desktop_contract"], read_only=read_only_literals),
        KOTLIN_OUT / "GatewayRelease.kt": env.get_template("kotlin_release.j2").render(
            ref_literal=_literal(meta["ref"]), version_literal=_literal(meta["hermes_version"]),
            tag_literal=_literal(meta["tag"]),
            desktop_contract=meta["desktop_contract"], read_only=read_only_literals),
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
