"""Validate JSON values against the reviewed OpenAPI schemas, with no third-party dependency.

Runs inside the tagged Hermes environment (the fixture recorder and the tag contract gate), so it
imports nothing from this repository. It covers the keywords the reviewed schemas use and rejects
any other constraining keyword, so a schema can never pass a value by being misunderstood.
"""

from __future__ import annotations

import re
from typing import Any

# Keywords that annotate without constraining.
_ANNOTATIONS = {"title", "description", "default", "examples", "format", "contentMediaType",
                "writeOnly", "readOnly", "deprecated", "discriminator"}
_KNOWN = _ANNOTATIONS | {
    "$ref", "type", "enum", "const", "anyOf", "oneOf", "allOf", "properties", "required",
    "additionalProperties", "items", "prefixItems", "minItems", "maxItems", "minLength",
    "maxLength", "pattern", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
}
_REF = "#/components/schemas/"


def _type_ok(value: Any, kind: str) -> bool:
    if kind == "null":
        return value is None
    if kind == "boolean":
        return isinstance(value, bool)
    if kind == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if kind == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if kind == "string":
        return isinstance(value, str)
    if kind == "array":
        return isinstance(value, list)
    if kind == "object":
        return isinstance(value, dict)
    raise ValueError(f"Unknown JSON Schema type: {kind}")


class Validator:
    def __init__(self, components: dict[str, dict]) -> None:
        self.components = components

    def errors(self, value: Any, schema: dict, path: str = "$") -> list[str]:
        unknown = set(schema) - _KNOWN
        if unknown:
            raise ValueError(f"Unsupported schema keywords at {path}: {sorted(unknown)}")
        if "$ref" in schema:
            ref = schema["$ref"]
            if not ref.startswith(_REF):
                raise ValueError(f"Unsupported $ref at {path}: {ref}")
            return self.errors(value, self.components[ref.removeprefix(_REF)], path)
        found: list[str] = []
        kind = schema.get("type")
        if kind is not None:
            kinds = kind if isinstance(kind, list) else [kind]
            if not any(_type_ok(value, item) for item in kinds):
                return [f"{path}: expected {kind}, got {type(value).__name__}"]
        if "enum" in schema and not any(_same(value, item) for item in schema["enum"]):
            found.append(f"{path}: {value!r} is not one of {schema['enum']}")
        if "const" in schema and not _same(value, schema["const"]):
            found.append(f"{path}: {value!r} is not {schema['const']!r}")
        if "anyOf" in schema:
            options = [self.errors(value, item, path) for item in schema["anyOf"]]
            if all(options):
                found.append(f"{path}: matches no anyOf variant ({_closest(options)})")
        if "oneOf" in schema:
            options = [self.errors(value, item, path) for item in schema["oneOf"]]
            matched = sum(not option for option in options)
            if matched != 1:
                found.append(f"{path}: matches {matched} oneOf variants ({_closest(options)})")
        for item in schema.get("allOf", []):
            found.extend(self.errors(value, item, path))
        if isinstance(value, dict):
            found.extend(self._object(value, schema, path))
        if isinstance(value, list):
            found.extend(self._array(value, schema, path))
        if isinstance(value, str):
            if "minLength" in schema and len(value) < schema["minLength"]:
                found.append(f"{path}: shorter than {schema['minLength']}")
            if "maxLength" in schema and len(value) > schema["maxLength"]:
                found.append(f"{path}: longer than {schema['maxLength']}")
            if "pattern" in schema and not re.search(schema["pattern"], value):
                found.append(f"{path}: does not match {schema['pattern']}")
        if _type_ok(value, "number"):
            for key, fails in (("minimum", lambda v, b: v < b), ("maximum", lambda v, b: v > b),
                               ("exclusiveMinimum", lambda v, b: v <= b),
                               ("exclusiveMaximum", lambda v, b: v >= b)):
                if key in schema and fails(value, schema[key]):
                    found.append(f"{path}: violates {key} {schema[key]}")
        return found

    def _object(self, value: dict, schema: dict, path: str) -> list[str]:
        found: list[str] = []
        properties = schema.get("properties", {})
        for name in schema.get("required", []):
            if name not in value:
                found.append(f"{path}: missing required {name!r}")
        additional = schema.get("additionalProperties", True)
        for name, item in value.items():
            where = f"{path}.{name}"
            if name in properties:
                found.extend(self.errors(item, properties[name], where))
            elif additional is False:
                found.append(f"{path}: unexpected property {name!r}")
            elif isinstance(additional, dict):
                found.extend(self.errors(item, additional, where))
        return found

    def _array(self, value: list, schema: dict, path: str) -> list[str]:
        found: list[str] = []
        if "minItems" in schema and len(value) < schema["minItems"]:
            found.append(f"{path}: fewer than {schema['minItems']} items")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            found.append(f"{path}: more than {schema['maxItems']} items")
        prefix = schema.get("prefixItems", [])
        for index, item in enumerate(value):
            if index < len(prefix):
                found.extend(self.errors(item, prefix[index], f"{path}[{index}]"))
            elif "items" in schema:
                found.extend(self.errors(item, schema["items"], f"{path}[{index}]"))
        return found


def _same(left: Any, right: Any) -> bool:
    return type(left) is type(right) and left == right if isinstance(left, bool) or isinstance(right, bool) \
        else left == right


def _closest(options: list[list[str]]) -> str:
    best = min(options, key=len)
    return "; ".join(best[:3])


def success_schema(operation: dict, status: int, media: str = "application/json") -> dict | None:
    """The documented schema for a success status and media type, or None when it documents none."""
    response = operation.get("responses", {}).get(str(status))
    if response is None:
        return None
    return response.get("content", {}).get(media, {}).get("schema")
