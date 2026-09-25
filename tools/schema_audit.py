"""Fail closed when upstream uses a schema construct the generators do not model."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

from tools.ref_policy import require_release

ROOT = Path(__file__).resolve().parents[1]
_META = {"title", "description", "default"}
_TYPES = {"string", "integer", "number", "boolean", "null", "array", "object"}


def audit_contract(contract: dict[str, Any]) -> dict[str, int]:
    schemas = contract["components"]["schemas"]
    if not isinstance(schemas, dict):
        raise TypeError("components.schemas must be an object")
    counts: Counter[str] = Counter()

    def visit(schema: dict[str, Any], path: str) -> None:
        if not isinstance(schema, dict):
            raise TypeError(f"{path}: schema must be an object")
        if not set(schema) - _META:
            counts["freeform"] += 1
            return

        keys = set(schema) - _META
        if "$ref" in keys:
            if keys != {"$ref"}:
                raise ValueError(f"{path}: $ref must be the only schema construct")
            ref = schema["$ref"]
            prefix = "#/components/schemas/"
            if not isinstance(ref, str) or not ref.startswith(prefix) or ref[len(prefix):] not in schemas:
                raise ValueError(f"{path}: unresolved component reference {ref!r}")
            counts["ref"] += 1
            return

        if "anyOf" in keys:
            if keys != {"anyOf"}:
                raise ValueError(f"{path}: unsupported anyOf sibling {keys - {'anyOf'}}")
            variants = schema["anyOf"]
            if not isinstance(variants, list) or len(variants) < 2:
                raise ValueError(f"{path}: anyOf needs at least two variants")
            counts["anyOf"] += 1
            if any(v == {"type": "null"} for v in variants):
                counts["nullable_anyOf"] += 1
            if len([v for v in variants if v != {"type": "null"}]) > 1:
                counts["multi_type_anyOf"] += 1
            for index, variant in enumerate(variants):
                visit(variant, f"{path}/anyOf/{index}")
            return

        if "oneOf" in keys:
            if keys != {"oneOf", "discriminator"}:
                raise ValueError(f"{path}: oneOf needs a discriminator")
            discriminator = schema["discriminator"]
            variants = schema["oneOf"]
            if not isinstance(discriminator, dict) or set(discriminator) != {"mapping", "propertyName"}:
                raise ValueError(f"{path}: invalid discriminator")
            if not isinstance(variants, list) or not variants:
                raise ValueError(f"{path}: empty oneOf")
            variant_refs = {v.get("$ref") for v in variants if isinstance(v, dict)}
            if set(discriminator["mapping"].values()) != variant_refs:
                raise ValueError(f"{path}: discriminator mapping does not match variants")
            counts["discriminated_oneOf"] += 1
            for index, variant in enumerate(variants):
                visit(variant, f"{path}/oneOf/{index}")
            return

        kind = schema.get("type")
        if kind not in _TYPES:
            raise ValueError(f"{path}: unsupported type {kind!r}")
        allowed = {"type"}
        if kind == "object":
            allowed |= {"properties", "required", "additionalProperties"}
            properties = schema.get("properties", {})
            required = schema.get("required", [])
            additional = schema.get("additionalProperties", False)
            if not isinstance(properties, dict) or not isinstance(required, list):
                raise TypeError(f"{path}: invalid object properties or required list")
            if not set(required) <= set(properties):
                raise ValueError(f"{path}: required field is absent from properties")
            if not isinstance(additional, (bool, dict)):
                raise TypeError(f"{path}: unsupported additionalProperties")
            counts["object"] += 1
            if additional is True:
                counts["freeform_object"] += 1
            if isinstance(additional, dict):
                visit(additional, f"{path}/additionalProperties")
            for name, value in properties.items():
                visit(value, f"{path}/properties/{name}")
        elif kind == "array":
            allowed.add("items")
            if "items" not in schema:
                raise ValueError(f"{path}: array without items")
            counts["array"] += 1
            visit(schema["items"], f"{path}/items")
        else:
            allowed |= {"enum", "const"}
            if "enum" in schema:
                if kind != "string" or not isinstance(schema["enum"], list):
                    raise ValueError(f"{path}: unsupported enum")
                counts["enum"] += 1
            if "const" in schema:
                if kind != "string" or not isinstance(schema["const"], str):
                    raise ValueError(f"{path}: unsupported const")
                counts["const"] += 1
            counts[kind] += 1
        if extra := keys - allowed:
            raise ValueError(f"{path}: unsupported schema keys {sorted(extra)}")

    for name, schema in schemas.items():
        visit(schema, f"components/schemas/{name}")
    for section in ("methods", "x-server-requests", "x-notifications"):
        for item in contract[section]:
            for position, param in enumerate(item.get("params", [])):
                visit(param["schema"], f"{section}/{item['name']}/params/{position}")
            if "result" in item:
                visit(item["result"]["schema"], f"{section}/{item['name']}/result")
    return dict(sorted(counts.items()))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    args = parser.parse_args()
    ref = require_release(args.ref)
    path = ROOT / "spec" / "out" / ref
    contract = json.loads((path / "openrpc.json").read_text(encoding="utf-8"))
    counts = audit_contract(contract)
    (path / "schema-audit.json").write_text(json.dumps(counts, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(counts, indent=2))


if __name__ == "__main__":
    main()
