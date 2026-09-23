"""Generate typed REST responses and methods from reviewed OpenAPI operations."""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass

from tools.fetch_spec import ROOT
from tools.naming import camel, pascal
from tools.ref_policy import require_release_tag

SWIFT_OUT = ROOT / "swift/Sources/HermesAPI/Generated/REST"
KOTLIN_OUT = ROOT / "kotlin/src/main/kotlin/st/flrn/hermes/api/generated/rest"


@dataclass(frozen=True)
class Property:
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


def _typed_operations(document: dict) -> list[Operation]:
    result: list[Operation] = []
    names: set[str] = set()
    for path, methods in sorted(document["paths"].items()):
        for method, item in sorted(methods.items()):
            if "x-handler-hash" not in item:
                continue
            if item.get("parameters") or item.get("requestBody"):
                raise ValueError(f"REST generator does not yet support parameters or bodies: {method} {path}")
            response = item["responses"]["200"]["content"]["application/json"]["schema"]
            if response.get("type") != "object" or response.get("additionalProperties") is not False:
                raise ValueError(f"REST response must be a closed object: {method} {path}")
            parts = path.strip("/").split("/")
            if parts[0] != "api" or len(parts) < 3 or any("{" in part for part in parts):
                raise ValueError(f"Unsupported REST path: {path}")
            namespace = camel(parts[1])
            name = camel("_".join(parts[2:]))
            type_name = pascal("_".join(parts[1:])) + "Response"
            symbol = f"{namespace}.{name}"
            if symbol in names:
                raise ValueError(f"REST method collision: {symbol}")
            names.add(symbol)
            required = set(response.get("required", []))
            props: list[Property] = []
            for wire, schema in response["properties"].items():
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
                props.append(Property(wire, camel(wire), swift, kotlin, wire in required))
            if not props or required - set(response["properties"]):
                raise ValueError(f"Invalid reviewed REST response: {method} {path}")
            result.append(Operation(method.upper(), path, namespace, name, type_name, tuple(props)))
    return result


def _swift_model(op: Operation) -> str:
    fields = "\n".join(f"    public let {p.name}: {p.swift_type}{'' if p.required else '?'}" for p in op.properties)
    args = ", ".join(f"{p.name}: {p.swift_type}{'' if p.required else '?'}{' = nil' if not p.required else ''}" for p in op.properties)
    assigns = "\n".join(f"        self.{p.name} = {p.name}" for p in op.properties)
    keys = "\n".join(f'        case {p.name} = {json.dumps(p.wire)}' for p in op.properties)
    decode = "\n".join(
        f"        {p.name} = try container.{('decode' if p.required else 'decodeIfPresent')}({p.swift_type}.self, forKey: .{p.name})"
        for p in op.properties
    )
    encode = "\n".join(
        f"        try container.{('encode' if p.required else 'encodeIfPresent')}({p.name}, forKey: .{p.name})"
        for p in op.properties
    )
    allowed = ", ".join(json.dumps(p.wire) for p in op.properties)
    return f'''/// Generated from the reviewed REST response for {op.method} {op.path}.
public struct {op.type_name}: Codable, Sendable, Hashable {{
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
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Unexpected REST response field"))
        }}
        let container = try decoder.container(keyedBy: CodingKeys.self)
{decode}
    }}

    public func encode(to encoder: Encoder) throws {{
        var container = encoder.container(keyedBy: CodingKeys.self)
{encode}
    }}
}}'''


def _kotlin_model(op: Operation) -> str:
    fields = "\n".join(
        f'    @SerialName({json.dumps(p.wire)})\n    public val {p.name}: {p.kotlin_type}{"" if p.required else "?"}{"" if p.required else " = null"},'
        for p in op.properties
    )
    return f'''/** Generated from the reviewed REST response for {op.method} {op.path}. */
@Serializable
public data class {op.type_name}(\n{fields}\n)'''


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
            lines.extend([f"    public func {op.name}() async throws -> {op.type_name} {{",
                          f'        try await caller.request("{op.method}", path: "{op.path}", as: {op.type_name}.self)',
                          "    }"])
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
             "import kotlinx.serialization.serializer", "import st.flrn.hermes.api.runtime.RESTCaller", "",
             "public class RESTMethodCatalog(private val caller: RESTCaller) {"]
    for namespace in sorted(groups):
        lines.append(f"    public val {namespace}: {pascal(namespace)}RESTMethods = {pascal(namespace)}RESTMethods(caller)")
    lines.append("}")
    for namespace, entries in sorted(groups.items()):
        lines.extend(["", f"public class {pascal(namespace)}RESTMethods(private val caller: RESTCaller) {{"])
        for op in entries:
            lines.extend([f"    public suspend fun {op.name}(): {op.type_name} =",
                          f'        caller.request("{op.method}", "{op.path}", serializer<{op.type_name}>())'])
        lines.append("}")
    return "\n".join(lines) + "\n"


def generate(ref: str, *, check: bool = False) -> int:
    ref = require_release_tag(ref)
    document = json.loads((ROOT / "spec/out" / ref / "openapi.json").read_text(encoding="utf-8"))
    ops = _typed_operations(document)
    outputs = {
        SWIFT_OUT / "RESTModels.swift": "// Generated by tools/gen_rest_api.py. Do not edit.\nimport Foundation\n\n" + "\n\n".join(map(_swift_model, ops)) + "\n",
        SWIFT_OUT / "RESTMethods.swift": _swift_methods(ops),
        KOTLIN_OUT / "RESTModels.kt": "// Generated by tools/gen_rest_api.py. Do not edit.\npackage st.flrn.hermes.api.generated.rest\n\nimport kotlinx.serialization.SerialName\nimport kotlinx.serialization.Serializable\n\n" + "\n\n".join(map(_kotlin_model, ops)) + "\n",
        KOTLIN_OUT / "RESTMethods.kt": _kotlin_methods(ops),
        ROOT / "spec/out" / ref / "generated-rest-symbols.json": json.dumps([
            {"method": op.method, "path": op.path, "public_name": f"{op.namespace}.{op.name}", "response": op.type_name}
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
