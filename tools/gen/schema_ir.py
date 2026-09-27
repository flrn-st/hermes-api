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
    description: str | None = None


@dataclass(frozen=True)
class ObjectDecl:
    name: str
    fields: tuple[Field, ...]
    open: bool
    # Wire names of properties that are always null (Hermes redacts them); they carry no value.
    always_null: tuple[str, ...] = ()
    # The always-null properties that are always present (strict models require and emit them).
    required_null: tuple[str, ...] = ()
    # Strict decoding fails on a key the closed object does not declare; tolerant decoding skips it.
    reject_unknown: bool = False
    description: str | None = None
    # The type of undeclared keys' values in an open object; None means any JSON value.
    extra: TypeRef | None = None


@dataclass(frozen=True)
class EnumDecl:
    name: str
    values: tuple[str, ...]
    constant: bool
    # No case for unknown values: decoding one fails.
    closed: bool = False
    # Strict decoding rejects a value outside `values`; tolerant decoding keeps it in the unknown case.
    strict: bool = False


@dataclass(frozen=True)
class TupleDecl:
    """A fixed-length JSON array whose items have positional types (``prefixItems``)."""

    name: str
    items: tuple[TypeRef, ...]


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
        self._setup(contract["components"]["schemas"], strict=False, rename={})
        roots: list[dict[str, Any]] = []
        for section in ("methods", "x-server-requests"):
            for method in self.contract[section]:
                roots.extend(item["schema"] for item in method.get("params", []))
        self.param_reachable = self._reachable(roots)
        for name, schema in self.schemas.items():
            self._named(name, schema)
        self._check_unique_names()

    @classmethod
    def for_rest(cls, schemas: dict[str, dict[str, Any]], roots: list[dict[str, Any]],
                 request_roots: list[dict[str, Any]], rename: dict[str, str]) -> SchemaGraph:
        """The components the REST operations reach, strictly: closed objects reject unknown keys,
        enums have no unknown case, and inline objects and tuples get names from their position."""
        graph = cls.__new__(cls)
        graph.contract = {}
        graph._setup(schemas, strict=True, rename=rename)
        graph.param_reachable = graph._reachable(request_roots)
        for name in sorted(graph._reachable(roots)):
            graph._named(name, schemas[name])
        graph._check_unique_names()
        return graph

    def _setup(self, schemas: dict[str, dict[str, Any]], *, strict: bool, rename: dict[str, str]) -> None:
        self.schemas: dict[str, dict[str, Any]] = schemas
        self.strict = strict
        self.rename = rename
        self.objects: dict[str, ObjectDecl] = {}
        self.enums: dict[str, EnumDecl] = {}
        self.unions: dict[str, UnionDecl] = {}
        self.tuples: dict[str, TupleDecl] = {}

    def type_name(self, component: str) -> str:
        return self.rename.get(component, component)

    def _reachable(self, roots: list[dict[str, Any]]) -> set[str]:
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
            for key in ("anyOf", "oneOf", "prefixItems"):
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
        names = list(self.objects) + list(self.enums) + list(self.unions) + list(self.tuples)
        if len(names) != len(set(names)):
            raise ValueError("Generated type name collision")

    def _named(self, component: str, schema: dict[str, Any], declared: str | None = None) -> None:
        """Declare a named type; ``declared`` names an inline schema, else the component names it."""
        name = declared or self.type_name(component)
        if schema.get("type") == "object":
            props = schema.get("properties", {})
            required = set(schema.get("required", []))
            fields: list[Field] = []
            always_null: list[str] = []
            for wire_name, value in props.items():
                if value.get("type") == "null" and not set(value) - {"type", "title", "description", "default"}:
                    if wire_name in required and not self.strict:
                        raise ValueError(f"Required always-null property {name}.{wire_name}")
                    always_null.append(wire_name)
                    continue
                value, const = _without_integer_const(value)
                typ = self.resolve(value, f"{name}{pascal(wire_name)}")
                fields.append(Field(
                    name=camel(wire_name), wire_name=wire_name, type=typ,
                    required=wire_name in required,
                    patch=(wire_name not in required and typ.nullable and component in self.param_reachable),
                    constraints=_constraints(value), const=const,
                    description=_one_line(value.get("description")) if self.strict else None,
                ))
            additional = schema.get("additionalProperties")
            is_open = additional is True or (self.strict and isinstance(additional, dict))
            extra = self.resolve(additional, f"{name}Extra") if isinstance(additional, dict) and self.strict else None
            if self.strict and "additionalProperties" not in schema and component not in self.param_reachable:
                # JSON Schema leaves an object without the keyword open; only request models,
                # which are encoded and never decoded, may omit it.
                is_open = True
            self.objects[name] = ObjectDecl(
                name, tuple(fields), is_open, tuple(always_null),
                reject_unknown=self.strict and not is_open,
                description=_one_line(schema.get("description")) if self.strict else None,
                extra=extra,
                required_null=tuple(key for key in always_null if key in required),
            )
        elif schema.get("type") == "string" and "enum" in schema:
            self.enums[name] = EnumDecl(name, tuple(schema["enum"]), False, strict=self.strict)
        else:
            raise ValueError(f"Unsupported named component {name}")

    def resolve(self, schema: dict[str, Any], name: str) -> TypeRef:
        if "$ref" in schema:
            ref = self.type_name(schema["$ref"].rsplit("/", 1)[-1])
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
            self.enums[name] = EnumDecl(name, values, "const" in schema, closed="const" in schema, strict=self.strict)
            return TypeRef(name, name)
        if self.strict and kind == "array" and "prefixItems" in schema:
            items = tuple(self.resolve(item, f"{name}{index}") for index, item in enumerate(schema["prefixItems"]))
            count = len(items)
            if schema.get("minItems") != count or schema.get("maxItems") != count or "items" in schema:
                raise ValueError(f"Only fixed-length tuples are supported: {name}")
            self.tuples[name] = TupleDecl(name, items)
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
                if not self.strict:
                    raise ValueError(f"Inline object needs a named component: {name}")
                self._named(name, schema, declared=name)
                return TypeRef(name, name)
            additional = schema.get("additionalProperties", self.strict)
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




def _one_line(text: object) -> str | None:
    """A description as one documentation line (Swift ``///`` and KDoc render it verbatim)."""
    if not isinstance(text, str) or not text.strip():
        return None
    return " ".join(text.split())


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
