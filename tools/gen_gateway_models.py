"""Render tagged gateway models for Swift and Kotlin from the shared schema graph."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from jinja2 import Environment, FileSystemLoader, StrictUndefined

from tools.gen.schema_ir import (
    EnumDecl,
    Field,
    ObjectDecl,
    SchemaGraph,
    TupleDecl,
    UnionDecl,
    UnionVariant,
)
from tools.naming import camel, pascal
from tools.ref_policy import require_release

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = Path(__file__).resolve().parent / "gen" / "templates"
ORIGIN = "Hermes gateway contract"
SWIFT_OUT = ROOT / "swift" / "Sources" / "HermesAPI" / "Generated" / "Gateway"
KOTLIN_OUT = ROOT / "kotlin" / "src" / "main" / "kotlin" / "hermes" / "api" / "generated" / "gateway"
SWIFT_RESERVED = {
    "as", "associatedtype", "break", "case", "catch", "class", "continue", "default", "defer",
    "do", "else", "enum", "extension", "fallthrough", "false", "fileprivate", "for", "func",
    "guard", "if", "import", "in", "init", "inout", "internal", "is", "let", "nil", "operator",
    "private", "protocol", "public", "repeat", "rethrows", "return", "self", "Self", "static",
    "struct", "subscript", "super", "switch", "throw", "throws", "true", "try", "typealias",
    "var", "where", "while",
}
KOTLIN_RESERVED = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while",
}


def _swift(name: str) -> str:
    return f"`{name}`" if name in SWIFT_RESERVED else name


def _kotlin(name: str) -> str:
    return f"`{name}`" if name in KOTLIN_RESERVED else name


def _literal(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


def _kdoc_safe(text: str) -> str:
    """Keep text from closing a Kotlin block comment, or opening a nested one."""
    return text.replace("*/", "*\\/").replace("/*", "/&#42;")


def _field_view(field: Field) -> dict[str, object]:
    swift_type = (f"Patch<{field.type.swift}>" if field.patch else
                  field.type.swift + ("?" if field.type.nullable or not field.required else ""))
    kotlin_type = (f"Patch<{field.type.kotlin}>" if field.patch else
                   field.type.kotlin + ("?" if field.type.nullable or not field.required else ""))
    wire = _literal(field.wire_name)
    name = _kotlin(field.name)
    base = field.type.kotlin
    if field.patch:
        decode = (f"when (val raw = input[{wire}]) {{ null -> Patch.Absent; JsonNull -> Patch.Null; "
                  f"else -> Patch.Value(json.decodeFromJsonElement<{base}>(raw)) }}")
        encode = (f"when (val patch = value.{name}) {{\n"
                  f"    Patch.Absent -> Unit\n"
                  f"    Patch.Null -> output[{wire}] = JsonNull\n"
                  f"    is Patch.Value -> output[{wire}] = json.encodeToJsonElement(patch.value)\n"
                  f"}}")
    elif field.required:
        raw = f'(input[{wire}] ?: throw SerializationException("Missing {field.wire_name}"))'
        decode = (f"if ({raw} is JsonNull) null else json.decodeFromJsonElement<{base}>({raw})"
                  if field.type.nullable else f"json.decodeFromJsonElement<{base}>({raw})")
        encode = f"output[{wire}] = json.encodeToJsonElement(value.{name})"
    else:
        decode = f"input[{wire}]?.takeUnless {{ it is JsonNull }}?.let {{ json.decodeFromJsonElement<{base}>(it) }}"
        encode = f"value.{name}?.let {{ output[{wire}] = json.encodeToJsonElement(it) }}"
    text = " ".join(part for part in (field.description, field.constraints) if part)
    # Keep a pattern from closing a Kotlin block comment.
    doc = _kdoc_safe(text) if text else None
    check = None
    if field.const is not None and not field.patch:
        doc = f"Always `{field.const}`; decoding any other value fails."
        optional = field.type.nullable or not field.required
        check = {
            "swift": f"{_swift(field.name)} == {'nil || ' + _swift(field.name) + ' == ' if optional else ''}{field.const}",
            "kotlin": f"{name} == {'null || ' + name + ' == ' if optional else ''}{field.const}L",
            "message": f"{field.wire_name} must be {field.const}",
        }
    return {
        "doc": doc,
        "check": check,
        "swift_name": _swift(field.name),
        "kotlin_name": name,
        "wire_literal": wire,
        "swift_type": swift_type,
        "kotlin_type": kotlin_type,
        "base_swift": field.type.swift,
        "required": field.required,
        "nullable": field.type.nullable,
        "patch": field.patch,
        "swift_init_default": ".absent" if field.patch else ("nil" if not field.required else
                                                            (str(field.const) if field.const is not None else None)),
        "kotlin_init_default": "Patch.Absent" if field.patch else ("null" if not field.required else
                                                                   (str(field.const) if field.const is not None else None)),
        "decode_expression": decode,
        "encode_statement": encode,
    }


def _object_view(model: ObjectDecl) -> dict[str, object]:
    fields = [_field_view(field) for field in model.fields]
    return {
        "name": model.name,
        "fields": fields,
        "checks": [dict(field["check"], swift_name=field["swift_name"]) for field in fields if field["check"]],
        "always_null": ", ".join(f"`{name}`" for name in model.always_null),
        "open": model.open,
        "custom": model.open or any(field.patch for field in model.fields) or bool(
            model.reject_unknown and model.always_null),
        "reject_unknown": model.reject_unknown,
        "known_literals": ", ".join(_literal(key) for key in
                                    [field.wire_name for field in model.fields] + list(model.always_null)),
        "required_null": [{"literal": _literal(key), "name": key} for key in model.required_null],
        "extra_swift": (model.extra.swift + ("?" if model.extra.nullable else "")) if model.extra else "JSONValue",
        "extra_kotlin": (model.extra.kotlin + ("?" if model.extra.nullable else "")) if model.extra else "JsonElement",
        "description": _kdoc_safe(model.description) if model.description else None,
    }


def _case_words(value: str) -> str:
    """A wire value usable by camel/pascal; values such as ``""`` or ``audio/flac`` get safe words."""
    try:
        camel(value)
        return value
    except ValueError:
        words = re.findall(r"[A-Za-z0-9]+", value)
        if not words:
            return "empty"
        return "_".join((["value"] if words[0][0].isdigit() else []) + words)


def _enum_view(enum: EnumDecl) -> dict[str, object]:
    cases = []
    names: set[str] = set()
    for value in enum.values:
        words = _case_words(value)
        swift_name = camel(words)
        if swift_name == "unknown" and not enum.closed:
            swift_name = "knownUnknown"
        if swift_name in names:
            raise ValueError(f"Enum case collision in {enum.name}: {swift_name}")
        names.add(swift_name)
        cases.append({
            "swift_name": _swift(swift_name),
            "kotlin_name": "KnownUnknown" if value == "unknown" and not enum.closed else pascal(words),
            "wire_literal": _literal(value),
        })
    return {"name": enum.name, "cases": cases, "constant": enum.constant, "closed": enum.closed}


def _tuple_view(item: TupleDecl) -> dict[str, object]:
    if any(typ.nullable for typ in item.items):
        raise ValueError(f"Nullable tuple items are unsupported: {item.name}")
    return {"name": item.name, "elements": [
        {"name": f"item{index}", "swift_type": typ.swift, "kotlin_type": typ.kotlin}
        for index, typ in enumerate(item.items)]}


def _variant_view(variant: UnionVariant) -> dict[str, str]:
    return {
        "swift_name": _swift(camel(variant.name)),
        "kotlin_name": pascal(variant.name) + "Value",
        "swift_type": variant.type.swift,
        "kotlin_type": variant.type.kotlin,
    }


def _union_view(union: UnionDecl) -> dict[str, object]:
    variants = [_variant_view(variant) for variant in union.variants]
    by_name = {variant.name: view for variant, view in zip(union.variants, variants, strict=True)}
    mapping = [{**by_name[name], "wire_literal": _literal(wire)} for wire, name in union.mapping]
    return {
        "name": union.name,
        "variants": variants,
        "discriminator": union.discriminator,
        "discriminator_literal": _literal(union.discriminator) if union.discriminator else None,
        "mapping": mapping,
    }


def declarations(graph: SchemaGraph, origin: str) -> list[tuple[str, str, str]]:
    """Every named type in the graph as (name, Swift source, Kotlin source), sorted by name."""
    env = Environment(loader=FileSystemLoader(TEMPLATES), undefined=StrictUndefined,
                      trim_blocks=True, lstrip_blocks=True, keep_trailing_newline=True)
    result: list[tuple[str, str, str]] = []
    for enum in graph.enums.values():
        view = _enum_view(enum)
        result.append((enum.name,
                       env.get_template("swift_enum.j2").render(enum=view, origin=origin),
                       env.get_template("kotlin_enum.j2").render(enum=view, origin=origin)))
    for union in graph.unions.values():
        view = _union_view(union)
        result.append((union.name,
                       env.get_template("swift_union.j2").render(union=view, origin=origin),
                       env.get_template("kotlin_union.j2").render(union=view, origin=origin)))
    for model in graph.objects.values():
        view = _object_view(model)
        result.append((model.name,
                       env.get_template("swift_object.j2").render(model=view, origin=origin),
                       env.get_template("kotlin_object.j2").render(model=view, origin=origin)))
    for item in graph.tuples.values():
        view = _tuple_view(item)
        result.append((item.name,
                       env.get_template("swift_tuple.j2").render(tuple=view, origin=origin),
                       env.get_template("kotlin_tuple.j2").render(tuple=view, origin=origin)))
    result.sort(key=lambda item: item[0])
    return result


def _render(graph: SchemaGraph) -> tuple[dict[Path, str], list[str]]:
    declarations_ = declarations(graph, ORIGIN)
    output: dict[Path, str] = {}
    for index in range(0, len(declarations_), 35):
        batch = declarations_[index:index + 35]
        number = index // 35
        output[SWIFT_OUT / f"Models{number:02}.swift"] = (
            "// Generated by tools/gen_gateway_models.py. Do not edit.\n"
            "import Foundation\n\n" + "\n\n".join(item[1].strip() for item in batch) + "\n"
        )
        output[KOTLIN_OUT / f"Models{number:02}.kt"] = (
            "// Generated by tools/gen_gateway_models.py. Do not edit.\n"
            "package hermes.api.generated.gateway\n\n"
            "import kotlinx.serialization.*\n"
            "import kotlinx.serialization.descriptors.*\n"
            "import kotlinx.serialization.encoding.*\n"
            "import kotlinx.serialization.json.*\n"
            "import hermes.api.runtime.EmptyObject\n"
            "import hermes.api.runtime.Patch\n\n"
            + "\n\n".join(item[2].strip() for item in batch) + "\n"
        )
    return output, [item[0] for item in declarations_]


def generate(ref: str, *, check: bool = False) -> int:
    require_release(ref)
    contract = json.loads((ROOT / "spec" / "out" / ref / "openrpc.json").read_text())
    graph = SchemaGraph(contract)
    outputs, symbols = _render(graph)
    manifest = ROOT / "spec" / "out" / ref / "generated-model-symbols.json"
    outputs[manifest] = json.dumps(symbols, indent=2) + "\n"
    expected = set(outputs)
    existing = (set(SWIFT_OUT.glob("Models*.swift")) |
                set(KOTLIN_OUT.glob("Models*.kt")) | {manifest})
    stale = existing - expected
    changed = [path for path, body in outputs.items()
               if not path.exists() or path.read_text(encoding="utf-8") != body]
    if check:
        for path in stale | set(changed):
            print(f"Stale generated output: {path.relative_to(ROOT)}")
        return len(stale) + len(changed)
    for path in stale:
        path.unlink()
    for path, body in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
    print(f"Generated {len(symbols)} named gateway models in Swift and Kotlin")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    raise SystemExit(1 if generate(args.ref, check=args.check) else 0)


if __name__ == "__main__":
    main()
