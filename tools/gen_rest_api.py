"""Generate typed REST responses and methods from reviewed OpenAPI operations."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass

from tools.fetch_spec import ROOT
from tools.naming import camel, pascal
from tools.ref_policy import require_release

SWIFT_OUT = ROOT / "swift/Sources/HermesAPI/Generated/REST"
KOTLIN_OUT = ROOT / "kotlin/src/main/kotlin/st/flrn/hermes/api/generated/rest"


@dataclass(frozen=True)
class Property:
    wire: str
    name: str
    swift_type: str
    kotlin_type: str
    required: bool
    nullable: bool = False


@dataclass(frozen=True)
class QueryParam:
    wire: str
    name: str
    swift_type: str
    kotlin_type: str
    required: bool


@dataclass(frozen=True)
class Operation:
    method: str
    path: str
    namespace: str
    name: str
    type_name: str
    properties: tuple[Property, ...]
    query_params: tuple[QueryParam, ...]
    request_type_name: str | None
    request_properties: tuple[Property, ...]


def _typed_operations(document: dict) -> list[Operation]:
    result: list[Operation] = []
    names: set[str] = set()
    for path, methods in sorted(document["paths"].items()):
        for method, item in sorted(methods.items()):
            if "x-handler-hash" not in item:
                continue
            query_params: list[QueryParam] = []
            for parameter in item.get("parameters", []):
                if parameter.get("in") != "query":
                    raise ValueError(f"REST generator does not yet support {parameter.get('in')} parameters: {method} {path}")
                wire = parameter["name"]
                schema = parameter["schema"]
                variants = schema.get("anyOf")
                if variants is not None:
                    non_null = [variant for variant in variants if variant.get("type") != "null"]
                    if len(variants) != 2 or len(non_null) != 1:
                        raise ValueError(f"Unsupported REST query union: {method} {path} {wire}")
                    schema = non_null[0]
                types = {"string": ("String", "String"), "integer": ("Int", "Long"),
                         "number": ("Double", "Double"), "boolean": ("Bool", "Boolean")}
                if schema.get("type") not in types:
                    raise ValueError(f"Unsupported REST query type: {method} {path} {wire}")
                swift, kotlin = types[schema["type"]]
                query_params.append(QueryParam(wire, camel(wire), swift, kotlin, parameter.get("required", False)))
            response = item["responses"]["200"]["content"]["application/json"]["schema"]
            if response.get("type") != "object" or response.get("additionalProperties") is not False:
                raise ValueError(f"REST response must be a closed object: {method} {path}")
            parts = path.strip("/").split("/")
            if parts[0] != "api" or len(parts) < 3 or any("{" in part for part in parts):
                raise ValueError(f"Unsupported REST path: {path}")
            namespace = camel(parts[1])
            name = camel("_".join(parts[2:]))
            symbol = f"{namespace}.{name}"
            if symbol in names:
                prefix = {"post": "set", "put": "update", "patch": "patch", "delete": "delete"}.get(method)
                if prefix is None:
                    raise ValueError(f"REST method collision: {symbol}")
                name = prefix + name[:1].upper() + name[1:]
                symbol = f"{namespace}.{name}"
                if symbol in names:
                    raise ValueError(f"REST method collision: {symbol}")
            type_name = pascal(f"{parts[1]}_{name}") + "Response"
            names.add(symbol)
            required = set(response.get("required", []))
            props: list[Property] = []
            for wire, schema in response["properties"].items():
                nullable = False
                if "anyOf" in schema:
                    variants = schema["anyOf"]
                    non_null = [variant for variant in variants if variant.get("type") != "null"]
                    if len(variants) != 2 or len(non_null) != 1 or set(schema) != {"anyOf"}:
                        raise ValueError(f"Unsupported REST union {type_name}.{wire}: {schema}")
                    schema = non_null[0]
                    nullable = True
                kind = schema.get("type")
                types = {
                    "string": ("String", "String"),
                    "integer": ("Int", "Long"),
                    "number": ("Double", "Double"),
                    "boolean": ("Bool", "Boolean"),
                }
                if kind not in types or len(schema) != 1:
                    raise ValueError(f"Unsupported REST field {type_name}.{wire}: {schema}")
                swift, kotlin = types[kind]
                props.append(Property(wire, camel(wire), swift, kotlin, wire in required, nullable))
            if not props or required - set(response["properties"]):
                raise ValueError(f"Invalid reviewed REST response: {method} {path}")
            request_type_name: str | None = None
            request_props: list[Property] = []
            if body := item.get("requestBody"):
                request_schema = body.get("content", {}).get("application/json", {}).get("schema", {})
                ref = request_schema.get("$ref", "")
                if not body.get("required") or not ref.startswith("#/components/schemas/"):
                    raise ValueError(f"REST request body must reference a named schema: {method} {path}")
                request_type_name = pascal(f"{parts[1]}_{name}") + "Request"
                ref_name = ref.rsplit("/", 1)[-1]
                request_schema = document["components"]["schemas"][ref_name]
                if request_schema.get("type") != "object" or not request_schema.get("properties"):
                    raise ValueError(f"REST request must be an object: {method} {path}")
                request_required = set(request_schema.get("required", []))
                for wire, field in request_schema["properties"].items():
                    types = {"string": ("String", "String"), "integer": ("Int", "Long"),
                             "number": ("Double", "Double"), "boolean": ("Bool", "Boolean")}
                    if field.get("type") not in types or set(field) - {"type", "title", "description", "default"}:
                        raise ValueError(f"Unsupported REST request field: {method} {path} {wire}")
                    swift, kotlin = types[field["type"]]
                    request_props.append(Property(wire, camel(wire), swift, kotlin, wire in request_required))
                if request_required - set(request_schema["properties"]):
                    raise ValueError(f"Invalid REST request required keys: {method} {path}")
            result.append(Operation(method.upper(), path, namespace, name, type_name,
                                    tuple(props), tuple(query_params), request_type_name,
                                    tuple(request_props)))
    return result


def _swift_model(op: Operation, *, request: bool = False) -> str:
    properties = op.request_properties if request else op.properties
    type_name = op.request_type_name if request else op.type_name
    source = "OpenAPI request" if request else "reviewed REST response"
    fields = "\n".join(f"    public let {p.name}: {p.swift_type}{'?' if p.nullable or not p.required else ''}" for p in properties)
    args = ", ".join(f"{p.name}: {p.swift_type}{'?' if p.nullable or not p.required else ''}{' = nil' if not p.required else ''}" for p in properties)
    assigns = "\n".join(f"        self.{p.name} = {p.name}" for p in properties)
    keys = "\n".join(f'        case {p.name} = {json.dumps(p.wire)}' for p in properties)
    decode = "\n".join(
        (f"        guard container.contains(.{p.name}) else {{\n"
         f"            throw DecodingError.keyNotFound(CodingKeys.{p.name}, .init(codingPath: decoder.codingPath, debugDescription: \"Missing required REST field\"))\n"
         "        }\n" if p.required and p.nullable else "") +
        f"        {p.name} = try container.{('decodeIfPresent' if p.nullable or not p.required else 'decode')}({p.swift_type}.self, forKey: .{p.name})"
        for p in properties
    )
    encode = "\n".join(
        f"        try container.{('encode' if p.required else 'encodeIfPresent')}({p.name}, forKey: .{p.name})"
        for p in properties
    )
    allowed = ", ".join(json.dumps(p.wire) for p in properties)
    return f'''/// Generated from the {source} for {op.method} {op.path}.
public struct {type_name}: Codable, Sendable, Hashable {{
{fields}

    public init({args}) {{
{assigns}
    }}

    private enum CodingKeys: String, CodingKey {{
{keys}
    }}

    public init(from decoder: Decoder) throws {{
        let raw = try decoder.container(keyedBy: DynamicCodingKey.self)
        let allowed: Set<String> = [{allowed}]
        guard raw.allKeys.allSatisfy({{ allowed.contains($0.stringValue) }}) else {{
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Unexpected REST {"request" if request else "response"} field"))
        }}
        let container = try decoder.container(keyedBy: CodingKeys.self)
{decode}
    }}

    public func encode(to encoder: Encoder) throws {{
        var container = encoder.container(keyedBy: CodingKeys.self)
{encode}
    }}
}}'''


def _kotlin_model(op: Operation, *, request: bool = False) -> str:
    properties = op.request_properties if request else op.properties
    type_name = op.request_type_name if request else op.type_name
    source = "OpenAPI request" if request else "reviewed REST response"
    fields = "\n".join(
        f'    @SerialName({json.dumps(p.wire)})\n    public val {p.name}: {p.kotlin_type}{"?" if p.nullable or not p.required else ""}{"" if p.required else " = null"},'
        for p in properties
    )
    return f'''/** Generated from the {source} for {op.method} {op.path}. */
@Serializable
public data class {type_name}(\n{fields}\n)'''


def _swift_methods(ops: list[Operation]) -> str:
    groups: dict[str, list[Operation]] = {}
    for op in ops:
        groups.setdefault(op.namespace, []).append(op)
    lines = ["// Generated by tools/gen_rest_api.py. Do not edit.", "import Foundation", "",
             "public struct RESTMethodCatalog: Sendable {", "    private let caller: any RESTCalling",
             "    public init(caller: any RESTCalling) { self.caller = caller }"]
    for namespace in sorted(groups):
        lines.append(f"    public var {namespace}: {pascal(namespace)}RESTMethods {{ {pascal(namespace)}RESTMethods(caller: caller) }}")
    lines.append("}")
    for namespace, entries in sorted(groups.items()):
        lines.extend(["", f"public struct {pascal(namespace)}RESTMethods: Sendable {{",
                      "    private let caller: any RESTCalling",
                      "    init(caller: any RESTCalling) { self.caller = caller }"])
        for op in entries:
            args = [f"{p.name}: {p.swift_type}{'' if p.required else '?'}"
                    f"{'' if p.required else ' = nil'}" for p in op.query_params]
            if op.request_type_name:
                args.insert(0, f"body: {op.request_type_name}")
            lines.append(f"    public func {op.name}({', '.join(args)}) async throws -> {op.type_name} {{")
            lines.append("        var query: [String: String] = [:]" if op.query_params else
                         "        let query: [String: String] = [:]")
            for p in op.query_params:
                value = p.name if p.swift_type == "String" else f"String({p.name})"
                if p.required:
                    lines.append(f'        query["{p.wire}"] = {value}')
                else:
                    lines.append(f'        if let {p.name} {{ query["{p.wire}"] = {value} }}')
            lines.append(f'        return try await caller.request("{op.method}", path: "{op.path}", '
                         f'as: {op.type_name}.self, query: query, '
                         f'body: {"JSONEncoder().encode(body)" if op.request_type_name else "nil"})')
            lines.append("    }")
        lines.append("}")
    lines.extend(["", "public extension HermesREST {"])
    for namespace in sorted(groups):
        lines.append(f"    var {namespace}: {pascal(namespace)}RESTMethods {{ {pascal(namespace)}RESTMethods(caller: self) }}")
    lines.append("}")
    return "\n".join(lines) + "\n"


def _kotlin_methods(ops: list[Operation]) -> str:
    groups: dict[str, list[Operation]] = {}
    for op in ops:
        groups.setdefault(op.namespace, []).append(op)
    lines = ["// Generated by tools/gen_rest_api.py. Do not edit.",
             "package st.flrn.hermes.api.generated.rest", "",
             "import kotlinx.serialization.serializer", "import kotlinx.serialization.json.Json",
             "import st.flrn.hermes.api.runtime.RESTCaller", "",
             "public class RESTMethodCatalog(private val caller: RESTCaller) {"]
    for namespace in sorted(groups):
        lines.append(f"    public val {namespace}: {pascal(namespace)}RESTMethods = {pascal(namespace)}RESTMethods(caller)")
    lines.append("}")
    for namespace, entries in sorted(groups.items()):
        lines.extend(["", f"public class {pascal(namespace)}RESTMethods(private val caller: RESTCaller) {{"])
        for op in entries:
            args = [f"{p.name}: {p.kotlin_type}{'' if p.required else '?'}"
                    f"{'' if p.required else ' = null'}" for p in op.query_params]
            if op.request_type_name:
                args.insert(0, f"body: {op.request_type_name}")
            lines.append(f"    public suspend fun {op.name}({', '.join(args)}): {op.type_name} {{")
            if op.query_params:
                lines.append("        val query = buildMap<String, String> {")
                for p in op.query_params:
                    if p.required:
                        lines.append(f'            put("{p.wire}", {p.name}.toString())')
                    else:
                        lines.append(f'            {p.name}?.let {{ put("{p.wire}", it.toString()) }}')
                lines.append("        }")
            else:
                lines.append("        val query = emptyMap<String, String>()")
            lines.append(f'        return caller.request("{op.method}", "{op.path}", '
                         f'serializer<{op.type_name}>(), query, '
                         f'{"Json.encodeToString(body)" if op.request_type_name else "null"})')
            lines.append("    }")
        lines.append("}")
    return "\n".join(lines) + "\n"


def generate(ref: str, *, check: bool = False) -> int:
    ref = require_release(ref)
    document = json.loads((ROOT / "spec/out" / ref / "openapi.json").read_text(encoding="utf-8"))
    ops = _typed_operations(document)
    outputs = {
        SWIFT_OUT / "RESTModels.swift": "// Generated by tools/gen_rest_api.py. Do not edit.\nimport Foundation\n\n" + "\n\n".join(
            model for op in ops for model in ([_swift_model(op, request=True)] if op.request_type_name else []) + [_swift_model(op)]
        ) + "\n",
        SWIFT_OUT / "RESTMethods.swift": _swift_methods(ops),
        KOTLIN_OUT / "RESTModels.kt": "// Generated by tools/gen_rest_api.py. Do not edit.\npackage st.flrn.hermes.api.generated.rest\n\nimport kotlinx.serialization.SerialName\nimport kotlinx.serialization.Serializable\n\n" + "\n\n".join(
            model for op in ops for model in ([_kotlin_model(op, request=True)] if op.request_type_name else []) + [_kotlin_model(op)]
        ) + "\n",
        KOTLIN_OUT / "RESTMethods.kt": _kotlin_methods(ops),
        ROOT / "spec/out" / ref / "generated-rest-symbols.json": json.dumps([
            {"method": op.method, "path": op.path, "public_name": f"{op.namespace}.{op.name}",
             "request": op.request_type_name, "response": op.type_name}
            for op in ops
        ], indent=2, sort_keys=True) + "\n",
    }
    changed = [path for path, body in outputs.items() if not path.exists() or path.read_text(encoding="utf-8") != body]
    if check:
        if changed:
            raise ValueError(f"Generated REST sources stale: {[str(p) for p in changed]}")
    else:
        for path, body in outputs.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding="utf-8")
    return len(ops)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    print(f"Generated {generate(args.ref, check=args.check)} reviewed REST operations")


if __name__ == "__main__":
    main()
