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


@dataclass(frozen=True)
class ObjectDecl:
    name: str
    fields: tuple[Field, ...]
    open: bool


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
            for wire_name, value in props.items():
                typ = self.resolve(value, f"{name}{pascal(wire_name)}")
                fields.append(Field(
                    name=camel(wire_name), wire_name=wire_name, type=typ,
                    required=wire_name in required,
                    patch=(wire_name not in required and typ.nullable and name in self.param_reachable),
                ))
            self.objects[name] = ObjectDecl(name, tuple(fields), schema.get("additionalProperties") is True)
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
