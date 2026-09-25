"""Resolve every OpenRPC schema to a named, language-neutral type graph."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from tools.naming import camel, pascal
from tools.schema_audit import audit_contract


@dataclass(frozen=True)
class TypeRef:
    swift: str
    kotlin: str
    nullable: bool = False


@dataclass(frozen=True)
class Field:
    name: str
    wire_name: str
    type: TypeRef
    required: bool
    patch: bool
    # Human-readable value constraints (pattern, length, item count), for the generated doc comment.
    constraints: str | None = None
    # An integer the field always holds; decoding any other value fails.
    const: int | None = None


@dataclass(frozen=True)
class ObjectDecl:
    name: str
    fields: tuple[Field, ...]
    open: bool
    # Wire names of properties that are always null (Hermes redacts them); they carry no value.
    always_null: tuple[str, ...] = ()


@dataclass(frozen=True)
class EnumDecl:
    name: str
    values: tuple[str, ...]
    constant: bool


@dataclass(frozen=True)
class UnionVariant:
    name: str
    type: TypeRef


@dataclass(frozen=True)
class UnionDecl:
    name: str
    variants: tuple[UnionVariant, ...]
    discriminator: str | None = None
    mapping: tuple[tuple[str, str], ...] = ()


class SchemaGraph:
    def __init__(self, contract: dict[str, Any]) -> None:
        audit_contract(contract)
        self.contract = contract
        self.schemas: dict[str, dict[str, Any]] = contract["components"]["schemas"]
        self.param_reachable = self._param_reachable()
        self.objects: dict[str, ObjectDecl] = {}
        self.enums: dict[str, EnumDecl] = {}
        self.unions: dict[str, UnionDecl] = {}
        for name, schema in self.schemas.items():
            self._named(name, schema)
        self._check_unique_names()

    def _param_reachable(self) -> set[str]:
        roots: list[dict[str, Any]] = []
        for section in ("methods", "x-server-requests"):
            for method in self.contract[section]:
                roots.extend(item["schema"] for item in method.get("params", []))
        visited: set[str] = set()

        def walk(schema: dict[str, Any]) -> None:
            if "$ref" in schema:
                name = schema["$ref"].rsplit("/", 1)[-1]
                if name in visited:
                    return
                visited.add(name)
                walk(self.schemas[name])
            for value in schema.get("properties", {}).values():
                walk(value)
            for key in ("anyOf", "oneOf"):
                for value in schema.get(key, []):
                    walk(value)
            if "items" in schema:
                walk(schema["items"])
            if isinstance(schema.get("additionalProperties"), dict):
                walk(schema["additionalProperties"])

        for root in roots:
            walk(root)
        return visited

    def _check_unique_names(self) -> None:
        names = list(self.objects) + list(self.enums) + list(self.unions)
        if len(names) != len(set(names)):
            raise ValueError("Generated type name collision")

    def _named(self, name: str, schema: dict[str, Any]) -> None:
        if schema.get("type") == "object":
            props = schema.get("properties", {})
            required = set(schema.get("required", []))
            fields: list[Field] = []
            always_null: list[str] = []
            for wire_name, value in props.items():
                if value.get("type") == "null" and not set(value) - {"type", "title", "description", "default"}:
                    if wire_name in required:
                        raise ValueError(f"Required always-null property {name}.{wire_name}")
                    always_null.append(wire_name)
                    continue
                value, const = _without_integer_const(value)
                typ = self.resolve(value, f"{name}{pascal(wire_name)}")
                fields.append(Field(
                    name=camel(wire_name), wire_name=wire_name, type=typ,
                    required=wire_name in required,
                    patch=(wire_name not in required and typ.nullable and name in self.param_reachable),
                    constraints=_constraints(value), const=const,
                ))
            self.objects[name] = ObjectDecl(
                name, tuple(fields), schema.get("additionalProperties") is True, tuple(always_null)
            )
        elif schema.get("type") == "string" and "enum" in schema:
            self.enums[name] = EnumDecl(name, tuple(schema["enum"]), False)
        else:
            raise ValueError(f"Unsupported named component {name}")

    def resolve(self, schema: dict[str, Any], name: str) -> TypeRef:
        if "$ref" in schema:
            ref = schema["$ref"].rsplit("/", 1)[-1]
            return TypeRef(ref, ref)
        if "anyOf" in schema:
            variants = [item for item in schema["anyOf"] if item.get("type") != "null"]
            nullable = len(variants) < len(schema["anyOf"])
            if len(variants) == 1:
                resolved = self.resolve(variants[0], name)
                return TypeRef(resolved.swift, resolved.kotlin, nullable)
            self._union(name, variants)
            return TypeRef(name, name, nullable)
        if "oneOf" in schema:
            self._union(name, schema["oneOf"], schema["discriminator"])
            return TypeRef(name, name)

        kind = schema.get("type")
        if kind == "integer" and "const" in schema:
            # Only object fields model integer constants; anywhere else would silently lose the check.
            raise ValueError(f"Unsupported integer const outside an object field: {name}")
        if kind == "string" and ("enum" in schema or "const" in schema):
            values = tuple(schema.get("enum", [schema.get("const")]))
            self.enums[name] = EnumDecl(name, values, "const" in schema)
            return TypeRef(name, name)
        primitives = {
            "string": ("String", "String"),
            "integer": ("Int", "Long"),
            "number": ("Double", "Double"),
            "boolean": ("Bool", "Boolean"),
        }
        if kind in primitives:
            swift, kotlin = primitives[kind]
            return TypeRef(swift, kotlin)
        if kind == "array":
            element = self.resolve(schema["items"], f"{name}Item")
            swift = f"[{element.swift}{'?' if element.nullable else ''}]"
            kotlin = f"List<{element.kotlin}{'?' if element.nullable else ''}>"
            return TypeRef(swift, kotlin)
        if kind == "object":
            if schema.get("properties"):
                raise ValueError(f"Inline object needs a named component: {name}")
            additional = schema.get("additionalProperties", False)
            if additional is False:
                return TypeRef("EmptyObject", "EmptyObject")
            if additional is True:
                return TypeRef("[String: JSONValue]", "Map<String, JsonElement>")
            value = self.resolve(additional, f"{name}Value")
            return TypeRef(
                f"[String: {value.swift}{'?' if value.nullable else ''}]",
                f"Map<String, {value.kotlin}{'?' if value.nullable else ''}>",
            )
        if kind == "null":
            raise ValueError(f"Bare null has no value type: {name}")
        if not set(schema) - {"title", "description", "default"}:
            return TypeRef("JSONValue", "JsonElement")
        raise ValueError(f"Unsupported schema for {name}: {schema}")

    def _union(
        self, name: str, variants: list[dict[str, Any]], discriminator: dict[str, Any] | None = None
    ) -> None:
        if name in self.unions:
            return
        members: list[UnionVariant] = []
        used: set[str] = set()
        for index, variant in enumerate(variants):
            typ = self.resolve(variant, f"{name}Option{index + 1}")
            if "$ref" in variant:
                label = variant["$ref"].rsplit("/", 1)[-1]
            elif typ.kotlin.startswith("List<"):
                label = "Array"
            elif typ.kotlin.startswith("Map<"):
                label = "Object"
            elif typ.kotlin == "JsonElement":
                label = "Json"
            else:
                label = typ.kotlin
            if label in used:
                label = f"{label}{index + 1}"
            used.add(label)
            members.append(UnionVariant(label, typ))
        mapping: list[tuple[str, str]] = []
        if discriminator is not None:
            for value, ref in discriminator["mapping"].items():
                target = ref.rsplit("/", 1)[-1]
                variant = next(member for member in members if member.type.kotlin == target)
                mapping.append((value, variant.name))
        self.unions[name] = UnionDecl(
            name, tuple(members), discriminator["propertyName"] if discriminator else None,
            tuple(mapping),
        )




def _constraints(schema: dict[str, Any]) -> str | None:
    """Describe the value constraints of a (nullable) string or array field, if any."""
    variants = [item for item in schema.get("anyOf", [schema]) if item.get("type") != "null"]
    if len(variants) != 1:
        return None
    value = variants[0]
    sentences: list[str] = []
    if value.get("type") == "array":
        bounds = _bounds(value.get("minItems"), value.get("maxItems"), "item")
        if bounds:
            sentences.append(f"Must have {bounds}.")
        items = value.get("items")
        if isinstance(items, dict) and items.get("type") == "string" and (rules := _string_rules(items)):
            sentences.append(f"Each value must {rules}.")
    elif value.get("type") == "string" and (rules := _string_rules(value)):
        sentences.append(f"Must {rules}.")
    return " ".join(sentences) or None


def _string_rules(schema: dict[str, Any]) -> str:
    rules = [f"match `{schema['pattern']}`"] if "pattern" in schema else []
    if bounds := _bounds(schema.get("minLength"), schema.get("maxLength"), "character"):
        rules.append(f"have {bounds}")
    return " and ".join(rules)


def _bounds(low: int | None, high: int | None, unit: str) -> str:
    def count(value: int) -> str:
        return f"{value} {unit}{'' if value == 1 else 's'}"
    if low is not None and high is not None:
        return f"{low} to {count(high)}"
    if low is not None:
        return f"at least {count(low)}"
    if high is not None:
        return f"at most {count(high)}"
    return ""


def _without_integer_const(schema: dict[str, Any]) -> tuple[dict[str, Any], int | None]:
    """Split an integer ``const`` (directly or under a nullable anyOf) from a field schema."""
    def strip(item: dict[str, Any]) -> tuple[dict[str, Any], int | None]:
        if item.get("type") == "integer" and "const" in item:
            return {key: value for key, value in item.items() if key != "const"}, item["const"]
        return item, None
    if "anyOf" in schema:
        variants = [strip(item) for item in schema["anyOf"]]
        consts = [const for _, const in variants if const is not None]
        if not consts:
            return schema, None
        return {**schema, "anyOf": [item for item, _ in variants]}, consts[0]
    return strip(schema)
