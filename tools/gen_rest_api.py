"""Generate typed REST models and methods from the reviewed OpenAPI operations.

Every operation that carries a reviewed overlay (``x-handler-hash``) becomes one method. Its models
come from the same strict type graph the gateway uses: closed objects reject unknown keys, enums
have no unknown case, and optional nullable request fields distinguish absent from ``null``.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass

from tools.fetch_spec import ROOT
from tools.gen.schema_ir import SchemaGraph, TypeRef
from tools.gen_gateway_models import KOTLIN_RESERVED, SWIFT_RESERVED, declarations
from tools.naming import camel, pascal
from tools.ref_policy import require_release

ORIGIN = "reviewed Hermes REST contract"
SWIFT_OUT = ROOT / "swift/Sources/HermesAPI/Generated/REST"
KOTLIN_OUT = ROOT / "kotlin/src/main/kotlin/hermes/api/generated/rest"
# The operation table the live scenarios and fixture decode tests drive every operation through.
SWIFT_LIVE_OUT = ROOT / "swift/Sources/HermesAPILiveScenarios/Generated/RESTOperations.swift"
KOTLIN_LIVE_OUT = ROOT / "kotlin/src/live/kotlin/hermes/api/live/generated/RESTOperations.kt"
REF = "#/components/schemas/"
METHODS = ("get", "head", "post", "put", "patch", "delete")
# Name prefix for a non-GET operation whose name another operation of the namespace already has.
VERB_PREFIX = {"head": "head", "post": "set", "put": "update", "patch": "patch", "delete": "delete"}
# Name for an operation whose path has no static segment after its namespace.
BARE_NAME = {"get": "get", "head": "head", "post": "create", "put": "update", "patch": "patch",
             "delete": "delete"}
STATUS_CASES = {200: "ok", 201: "created", 202: "accepted", 204: "noContent", 301: "movedPermanently",
                302: "found", 303: "seeOther", 307: "temporaryRedirect", 308: "permanentRedirect"}
SCALARS = {"string": ("String", "String"), "integer": ("Int", "Long"),
           "number": ("Double", "Double"), "boolean": ("Bool", "Boolean")}
RUNTIME_NAMES = {"RESTBinary", "RESTRedirect", "RESTFile", "RESTRequest", "RESTResponse", "RESTPath",
                 "RESTMultipart", "RESTCalling", "RESTCaller", "HermesREST", "JSONValue", "Patch",
                 "EmptyObject"}


@dataclass(frozen=True)
class Param:
    wire: str
    name: str
    swift_type: str
    kotlin_type: str
    required: bool
    location: str  # "path", "query" or "form"
    file: bool = False
    # Value constraints the server enforces (it answers 422 otherwise), for the generated doc comment.
    constraints: str | None = None


@dataclass(frozen=True)
class Outcome:
    status: int
    kind: str  # "json", "text", "binary", "redirect" or "empty"
    type: TypeRef | None = None

    @property
    def swift(self) -> str:
        if self.kind == "json" and self.type is not None:
            return self.type.swift + ("?" if self.type.nullable else "")
        return {"text": "String", "binary": "RESTBinary", "redirect": "RESTRedirect", "empty": "Void"}[self.kind]

    @property
    def kotlin(self) -> str:
        if self.kind == "json" and self.type is not None:
            return self.type.kotlin + ("?" if self.type.nullable else "")
        return {"text": "String", "binary": "RESTBinary", "redirect": "RESTRedirect", "empty": "Unit"}[self.kind]


@dataclass(frozen=True)
class Operation:
    method: str
    path: str
    namespace: str
    name: str
    params: tuple[Param, ...]
    body: TypeRef | None
    body_required: bool
    multipart: bool
    outcomes: tuple[Outcome, ...]
    result_name: str | None  # the generated enum when several statuses succeed

    @property
    def swift_result(self) -> str:
        return self.result_name or self.outcomes[0].swift

    @property
    def kotlin_result(self) -> str:
        return self.result_name or self.outcomes[0].kotlin


def _swift(name: str) -> str:
    return f"`{name}`" if name in SWIFT_RESERVED else name


def _kotlin(name: str) -> str:
    return f"`{name}`" if name in KOTLIN_RESERVED else name


def _param_constraints(schema: dict) -> str | None:
    variants = [item for item in schema.get("anyOf", [schema]) if item.get("type") != "null"]
    if len(variants) != 1:
        return None
    value = variants[0]
    rules = []
    low, high = value.get("minimum", value.get("exclusiveMinimum")), value.get("maximum", value.get("exclusiveMaximum"))
    if low is not None and high is not None:
        rules.append(f"{low} to {high}")
    elif low is not None:
        rules.append(f"at least {low}")
    elif high is not None:
        rules.append(f"at most {high}")
    if "minLength" in value or "maxLength" in value:
        rules.append(f"{value.get('minLength', 0)} to {value.get('maxLength', 'any')} characters")
    if "pattern" in value:
        rules.append(f"matches `{value['pattern']}`")
    if "enum" in value:
        rules.append("one of " + ", ".join(f"`{item}`" for item in value["enum"]))
    return "; ".join(rules) or None


def _scalar(schema: dict, where: str) -> tuple[str, str, bool]:
    """A parameter's Swift and Kotlin types, and whether null is allowed."""
    variants = schema.get("anyOf")
    nullable = False
    if variants is not None:
        non_null = [variant for variant in variants if variant.get("type") != "null"]
        if len(non_null) != 1:
            raise ValueError(f"Unsupported REST parameter union: {where}")
        nullable = len(non_null) < len(variants)
        schema = non_null[0]
    if schema.get("type") not in SCALARS:
        raise ValueError(f"Unsupported REST parameter type: {where}")
    swift, kotlin = SCALARS[schema["type"]]
    return swift, kotlin, nullable


def _segments(path: str) -> tuple[str, list[str]]:
    """The namespace and the static words that name the operation."""
    parts = [part for part in path.strip("/").split("/") if part]
    if parts[:1] == ["api"] and parts[1:2] == ["plugins"] and len(parts) > 2:
        namespace, rest = parts[2], parts[3:]
    elif parts[:1] == ["api"] and len(parts) > 1:
        namespace, rest = parts[1], parts[2:]
    else:
        namespace, rest = "web", parts
    words: list[str] = []
    for part in rest:
        while "{" in part:
            before, _, after = part.partition("{")
            words.append(before)
            part = after.partition("}")[2]
        words.append(part)
    return camel(namespace), [word for word in (w.strip(".-_") for w in words) if word]


def _names(document: dict) -> dict[tuple[str, str], tuple[str, str]]:
    """(method, path) -> (namespace, name), unique per namespace and stable for a given path set."""
    entries: list[tuple[str, str, str, str, int]] = []
    for path, methods in document["paths"].items():
        for method, operation in methods.items():
            if "x-handler-hash" not in operation:
                continue
            namespace, words = _segments(path)
            stem = camel("_".join(words)) if words else ""
            params = sum(1 for parameter in operation.get("parameters", []) if parameter.get("in") == "path")
            entries.append((namespace, stem, method, path, params))
    # GETs claim names first, fewer path parameters before more, then the other methods in order.
    entries.sort(key=lambda entry: (entry[0], METHODS.index(entry[2]) if entry[2] == "get" else 1,
                                    entry[4], METHODS.index(entry[2]), entry[3]))
    taken: set[tuple[str, str]] = set()
    result: dict[tuple[str, str], tuple[str, str]] = {}
    for namespace, stem, method, path, _ in entries:
        params = [camel(name) for name in _path_params(path)]
        suffix = "By" + "And".join(name[:1].upper() + name[1:] for name in params) if params else ""
        base = stem or BARE_NAME[method]
        candidates = [base]
        if method != "get" and stem:
            candidates.append(VERB_PREFIX[method] + stem[:1].upper() + stem[1:])
        candidates += [candidate + suffix for candidate in list(candidates) if suffix]
        name = next((candidate for candidate in candidates if (namespace, candidate) not in taken), None)
        if name is None:
            raise ValueError(f"REST method name collision: {method.upper()} {path}")
        taken.add((namespace, name))
        result[(method, path)] = (namespace, name)
    return result


def _path_params(path: str) -> list[str]:
    names: list[str] = []
    rest = path
    while "{" in rest:
        rest = rest.partition("{")[2]
        name, _, rest = rest.partition("}")
        names.append(name.split(":", 1)[0])
    return names


def _outcomes(operation: dict, where: str, graph: SchemaGraph, hint: str) -> list[Outcome]:
    result: list[Outcome] = []
    for status, response in sorted(operation["responses"].items()):
        if status[0] not in "23":
            continue
        content = response.get("content", {})
        code = int(status)
        if status[0] == "3":
            result.append(Outcome(code, "redirect"))
        elif not content:
            result.append(Outcome(code, "empty"))
        elif list(content) == ["application/json"]:
            result.append(Outcome(code, "json", graph.resolve(content["application/json"]["schema"],
                                                             hint if len(result) == 0 else f"{hint}{code}")))
        elif all(media.startswith("text/") for media in content):
            result.append(Outcome(code, "text"))
        else:
            result.append(Outcome(code, "binary"))
    if not result:
        raise ValueError(f"REST operation documents no success response: {where}")
    return result


def _roots(document: dict) -> tuple[list[dict], list[dict]]:
    responses: list[dict] = []
    requests: list[dict] = []
    for methods in document["paths"].values():
        for operation in methods.values():
            if "x-handler-hash" not in operation:
                continue
            for status, response in operation["responses"].items():
                if status[0] in "23":
                    responses.extend(body["schema"] for body in response.get("content", {}).values()
                                     if "schema" in body)
            for body in operation.get("requestBody", {}).get("content", {}).values():
                requests.append(body["schema"])
    return responses + requests, requests


def build(document: dict, reserved: set[str]) -> tuple[SchemaGraph, list[Operation]]:
    schemas = document.get("components", {}).get("schemas", {})
    roots, request_roots = _roots(document)
    # A REST type named like a gateway model gets a prefix in both languages; Swift shares one module.
    rename = {name: f"REST{name}" for name in schemas if name in reserved | RUNTIME_NAMES}
    graph = SchemaGraph.for_rest(schemas, roots, request_roots, rename)
    names = _names(document)
    operations: list[Operation] = []
    for path, methods in sorted(document["paths"].items()):
        for method, item in sorted(methods.items(), key=lambda pair: METHODS.index(pair[0])):
            if "x-handler-hash" not in item:
                continue
            where = f"{method.upper()} {path}"
            namespace, name = names[(method, path)]
            hint = pascal(namespace) + name[:1].upper() + name[1:]
            params: list[Param] = []
            declared = {parameter["name"]: parameter for parameter in item.get("parameters", [])}
            for wire in _path_params(path):
                parameter = declared.pop(wire, None)
                if parameter is None or parameter.get("in") != "path":
                    raise ValueError(f"Undeclared REST path parameter {wire}: {where}")
                swift, kotlin, _ = _scalar(parameter["schema"], f"{where} {wire}")
                params.append(Param(wire, camel(wire), swift, kotlin, True, "path",
                                    constraints=_param_constraints(parameter["schema"])))
            for wire, parameter in declared.items():
                if parameter.get("in") != "query":
                    raise ValueError(f"Unsupported REST {parameter.get('in')} parameter {wire}: {where}")
                swift, kotlin, _ = _scalar(parameter["schema"], f"{where} {wire}")
                params.append(Param(wire, camel(wire), swift, kotlin, parameter.get("required", False), "query",
                                    constraints=_param_constraints(parameter["schema"])))
            body: TypeRef | None = None
            body_required = False
            multipart = False
            if request := item.get("requestBody"):
                content = request.get("content", {})
                body_required = bool(request.get("required"))
                if list(content) == ["multipart/form-data"]:
                    multipart = True
                    params.extend(_form_fields(content["multipart/form-data"]["schema"], schemas, where))
                elif list(content) == ["application/json"]:
                    schema = content["application/json"]["schema"]
                    variants = schema.get("anyOf", [schema])
                    refs = [variant for variant in variants if "$ref" in variant]
                    if len(refs) != 1 or len(variants) - len(refs) > (1 if "anyOf" in schema else 0):
                        raise ValueError(f"REST request body must reference a named schema: {where}")
                    body = graph.resolve(refs[0], f"{hint}Request")
                else:
                    raise ValueError(f"Unsupported REST request media {sorted(content)}: {where}")
            # Path parameters in path order, then the other required parameters, then the optional ones.
            params.sort(key=lambda param: (param.location != "path", not param.required))
            names_seen = [param.name for param in params] + (["body"] if body else [])
            if len(names_seen) != len(set(names_seen)):
                raise ValueError(f"REST parameter name collision: {where}")
            outcomes = [Outcome(200, "empty")] if method == "head" else _outcomes(item, where, graph, f"{hint}Response")
            operations.append(Operation(
                method.upper(), path, namespace, name, tuple(params), body, body_required, multipart,
                tuple(outcomes), f"{hint}Result" if len(outcomes) > 1 else None,
            ))
    taken = set(graph.objects) | set(graph.enums) | set(graph.unions) | set(graph.tuples)
    collisions = sorted(name for name in taken if name in reserved | RUNTIME_NAMES)
    collisions += sorted(op.result_name for op in operations if op.result_name in taken | reserved)
    if collisions:
        raise ValueError(f"REST type names collide with other generated types: {collisions}")
    return graph, operations


def _form_fields(schema: dict, schemas: dict, where: str) -> list[Param]:
    ref = schema.get("$ref", "")
    if not ref.startswith(REF):
        raise ValueError(f"Multipart body must reference a named schema: {where}")
    model = schemas[ref.removeprefix(REF)]
    required = set(model.get("required", []))
    fields: list[Param] = []
    for wire, field in model.get("properties", {}).items():
        is_file = field.get("type") == "string" and (
            field.get("format") == "binary" or "contentMediaType" in field)
        if is_file:
            fields.append(Param(wire, camel(wire), "RESTFile", "RESTFile", wire in required, "form", True))
        else:
            swift, kotlin, _ = _scalar(field, f"{where} {wire}")
            fields.append(Param(wire, camel(wire), swift, kotlin, wire in required, "form"))
    return fields


def _swift_path(op: Operation) -> str:
    path = op.path
    for param in op.params:
        if param.location == "path":
            value = _swift(param.name) if param.swift_type == "String" else f"String({_swift(param.name)})"
            path = path.replace("{" + param.wire + "}", f"\\(RESTPath.segment({value}))", 1)
            path = path.replace("{" + param.wire + ":path}", f"\\(RESTPath.segment({value}))", 1)
    return path


def _kotlin_text(param: Param, value: str) -> str:
    return value if param.kotlin_type == "String" else f"{value}.toString()"


def _kotlin_path(op: Operation) -> str:
    path = op.path
    for param in op.params:
        if param.location == "path":
            segment = f"${{RESTPath.segment({_kotlin_text(param, _kotlin(param.name))})}}"
            path = path.replace("{" + param.wire + "}", segment, 1)
            path = path.replace("{" + param.wire + ":path}", segment, 1)
    return path


def _swift_decode(outcome: Outcome) -> str:
    if outcome.kind == "json":
        return f"try response.json({outcome.swift}.self, status: {outcome.status})"
    return f"try response.{outcome.kind}(status: {outcome.status})"


def _kotlin_decode(outcome: Outcome) -> str:
    if outcome.kind == "json":
        return f"response.json(caller.json, serializer<{outcome.kotlin}>(), {outcome.status})"
    return f"response.{outcome.kind}({outcome.status})"


def _swift_method(op: Operation) -> list[str]:
    args: list[str] = []
    for param in op.params:
        optional = "" if param.required else "?"
        default = "" if param.required else " = nil"
        args.append(f"{param.name}: {param.swift_type}{optional}{default}")
    if op.body is not None:
        body_type = op.body.swift + ("" if op.body_required else "?")
        args.insert(_body_index(op), f"body: {body_type}{'' if op.body_required else ' = nil'}")
    lines = [f"    /// `{op.method} {op.path}`"]
    lines += [f"    /// - Parameter {param.name}: {param.constraints.replace('*/', '*\\/')}."
              for param in op.params if param.constraints]
    lines += [f"    public func {_swift(op.name)}({', '.join(args)}) async throws -> {op.swift_result} {{"]
    query = [param for param in op.params if param.location == "query"]
    lines.append("        var query: [String: String] = [:]" if query else "        let query: [String: String] = [:]")
    for param in query:
        name = _swift(param.name)
        value = name if param.swift_type == "String" else f"String({name})"
        if param.required:
            lines.append(f'        query["{param.wire}"] = {value}')
        else:
            inner = "value" if param.swift_type == "String" else "String(value)"
            lines.append(f'        if let value = {name} {{ query["{param.wire}"] = {inner} }}')
    body, content_type = "nil", "nil"
    if op.multipart:
        lines.append("        var form = RESTMultipart()")
        for param in op.params:
            if param.location != "form":
                continue
            name = _swift(param.name)
            call = "file" if param.file else "text"
            value = "value" if param.file or param.swift_type == "String" else "String(value)"
            if param.required:
                value = name if param.file or param.swift_type == "String" else f"String({name})"
                lines.append(f'        form.{call}("{param.wire}", {value})')
            else:
                lines.append(f'        if let value = {name} {{ form.{call}("{param.wire}", {value}) }}')
        body, content_type = "form.encoded()", "form.contentType"
    elif op.body is not None:
        if op.body_required:
            body = "try JSONEncoder().encode(body)"
        else:
            body = "try body.map { try JSONEncoder().encode($0) }"
        content_type = '"application/json"'
    extra = "" if body == "nil" else f", body: {body}, contentType: {content_type}"
    lines.append(f'        let response = try await caller.send(RESTRequest(method: "{op.method}", '
                 f'path: "{_swift_path(op)}", query: query{extra}))')
    if op.result_name is None:
        outcome = op.outcomes[0]
        if outcome.kind == "empty":
            lines.append(f"        try response.empty(status: {outcome.status})")
        else:
            lines.append(f"        return {_swift_decode(outcome)}")
    else:
        lines.append("        switch response.status {")
        for outcome in op.outcomes:
            case = STATUS_CASES.get(outcome.status, f"status{outcome.status}")
            value = "" if outcome.kind == "empty" else f"({_swift_decode(outcome)})"
            if outcome.kind == "empty":
                lines.append(f"        case {outcome.status}: try response.empty(status: {outcome.status}); "
                             f"return .{case}")
            else:
                lines.append(f"        case {outcome.status}: return .{case}{value}")
        lines.append("        default: throw response.undocumented()")
        lines.append("        }")
    lines.append("    }")
    return lines


def _kotlin_method(op: Operation) -> list[str]:
    args: list[str] = []
    for param in op.params:
        args.append(f"{_kotlin(param.name)}: {param.kotlin_type}{'' if param.required else '? = null'}")
    if op.body is not None:
        args.insert(_body_index(op), f"body: {op.body.kotlin}{'' if op.body_required else '? = null'}")
    documented = [param for param in op.params if param.constraints]
    if documented:
        lines = ["    /**", f"     * `{op.method} {op.path}`", "     *"]
        lines += [f"     * @param {_kotlin(param.name)} {_kdoc(param.constraints)}." for param in documented]
        lines += ["     */"]
    else:
        lines = [f"    /** `{op.method} {op.path}` */"]
    lines += [f"    public suspend fun {_kotlin(op.name)}({', '.join(args)}): {op.kotlin_result} {{"]
    query = [param for param in op.params if param.location == "query"]
    if query:
        lines.append("        val query = buildMap<String, String> {")
        for param in query:
            name = _kotlin(param.name)
            if param.required:
                lines.append(f'            put("{param.wire}", {_kotlin_text(param, name)})')
            else:
                lines.append(f'            {name}?.let {{ put("{param.wire}", {_kotlin_text(param, "it")}) }}')
        lines.append("        }")
    else:
        lines.append("        val query = emptyMap<String, String>()")
    body, content_type = "null", "null"
    if op.multipart:
        lines.append("        val form = RESTMultipart()")
        for param in op.params:
            if param.location != "form":
                continue
            name = _kotlin(param.name)
            call = "file" if param.file else "text"
            value = "it" if param.file else _kotlin_text(param, "it")
            if param.required:
                lines.append(f'        form.{call}("{param.wire}", {name if param.file else _kotlin_text(param, name)})')
            else:
                lines.append(f'        {name}?.let {{ form.{call}("{param.wire}", {value}) }}')
        body, content_type = "form.encoded()", "form.contentType"
    elif op.body is not None:
        encode = f"caller.json.encodeToString(serializer<{op.body.kotlin}>(), body).encodeToByteArray()"
        body = encode if op.body_required else f"body?.let {{ {encode.replace('), body)', '), it)')} }}"
        content_type = '"application/json"'
    extra = "" if body == "null" else f", {body}, {content_type}"
    lines.append(f'        val response = caller.send(RESTRequest("{op.method}", "{_kotlin_path(op)}", query{extra}))')
    if op.result_name is None:
        outcome = op.outcomes[0]
        if outcome.kind == "empty":
            lines.append(f"        response.empty({outcome.status})")
        else:
            lines.append(f"        return {_kotlin_decode(outcome)}")
    else:
        lines.append("        return when (response.status) {")
        for outcome in op.outcomes:
            case = STATUS_CASES.get(outcome.status, f"status{outcome.status}")
            case = case[:1].upper() + case[1:]
            if outcome.kind == "empty":
                lines.append(f"            {outcome.status} -> {{ response.empty({outcome.status}); "
                             f"{op.result_name}.{case} }}")
            else:
                lines.append(f"            {outcome.status} -> {op.result_name}.{case}({_kotlin_decode(outcome)})")
        lines.append("            else -> throw response.undocumented()")
        lines.append("        }")
    lines.append("    }")
    return lines


def _swift_result_enum(op: Operation) -> str:
    cases = []
    for outcome in op.outcomes:
        case = STATUS_CASES.get(outcome.status, f"status{outcome.status}")
        cases.append(f"    /// HTTP {outcome.status}.\n    case {case}" +
                     ("" if outcome.kind == "empty" else f"({outcome.swift})"))
    return (f"/// The documented success responses of `{op.method} {op.path}`, by status.\n"
            f"public enum {op.result_name}: Sendable, Hashable {{\n" + "\n".join(cases) + "\n}")


def _kotlin_result_enum(op: Operation) -> str:
    cases = []
    for outcome in op.outcomes:
        case = STATUS_CASES.get(outcome.status, f"status{outcome.status}")
        case = case[:1].upper() + case[1:]
        if outcome.kind == "empty":
            cases.append(f"    /** HTTP {outcome.status}. */\n    public data object {case} : {op.result_name}")
        else:
            cases.append(f"    /** HTTP {outcome.status}. */\n"
                         f"    public data class {case}(public val value: {outcome.kotlin}) : {op.result_name}")
    return (f"/** The documented success responses of `{op.method} {op.path}`, by status. */\n"
            f"public sealed interface {op.result_name} {{\n" + "\n".join(cases) + "\n}")


def _swift_methods(ops: list[Operation]) -> str:
    groups: dict[str, list[Operation]] = {}
    for op in ops:
        groups.setdefault(op.namespace, []).append(op)
    lines = ["// Generated by tools/gen_rest_api.py. Do not edit.", "import Foundation", "",
             "public struct RESTMethodCatalog: Sendable {", "    private let caller: any RESTCalling",
             "    public init(caller: any RESTCalling) { self.caller = caller }"]
    for namespace in sorted(groups):
        lines.append(f"    public var {namespace}: {pascal(namespace)}RESTMethods "
                     f"{{ {pascal(namespace)}RESTMethods(caller: caller) }}")
    lines.append("}")
    for namespace, entries in sorted(groups.items()):
        lines.extend(["", f"public struct {pascal(namespace)}RESTMethods: Sendable {{",
                      "    private let caller: any RESTCalling",
                      "    init(caller: any RESTCalling) { self.caller = caller }"])
        for op in sorted(entries, key=lambda item: item.name):
            lines.append("")
            lines.extend(_swift_method(op))
        lines.append("}")
    for op in ops:
        if op.result_name:
            lines.extend(["", _swift_result_enum(op)])
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
             "package hermes.api.generated.rest", "",
             "import kotlinx.serialization.json.JsonElement",
             "import kotlinx.serialization.serializer",
             "import hermes.api.runtime.RESTBinary",
             "import hermes.api.runtime.RESTCaller",
             "import hermes.api.runtime.RESTFile",
             "import hermes.api.runtime.RESTMultipart",
             "import hermes.api.runtime.RESTPath",
             "import hermes.api.runtime.RESTRedirect",
             "import hermes.api.runtime.RESTRequest", "",
             "public class RESTMethodCatalog(private val caller: RESTCaller) {"]
    for namespace in sorted(groups):
        lines.append(f"    public val {namespace}: {pascal(namespace)}RESTMethods = {pascal(namespace)}RESTMethods(caller)")
    lines.append("}")
    for namespace, entries in sorted(groups.items()):
        lines.extend(["", f"public class {pascal(namespace)}RESTMethods(private val caller: RESTCaller) {{"])
        for index, op in enumerate(sorted(entries, key=lambda item: item.name)):
            if index:
                lines.append("")
            lines.extend(_kotlin_method(op))
        lines.append("}")
    for op in ops:
        if op.result_name:
            lines.extend(["", _kotlin_result_enum(op)])
    return "\n".join(lines) + "\n"


ACCESSORS = {"String": "string", "Int": "int", "Double": "double", "Bool": "bool", "RESTFile": "file"}
KOTLIN_ACCESSORS = {"String": "string", "Long": "long", "Double": "double", "Boolean": "bool", "RESTFile": "file"}


def _body_index(op: Operation) -> int:
    """The body argument follows the path parameters and precedes the other parameters."""
    return sum(1 for param in op.params if param.location == "path")


def _swift_arguments(op: Operation) -> str:
    args: list[str] = []
    for param in op.params:
        accessor = ACCESSORS[param.swift_type]
        if not param.required:
            accessor = "optional" + accessor[:1].upper() + accessor[1:]
        args.append(f'{param.name}: try arguments.{accessor}("{param.wire}", in: .{param.location})')
    if op.body is not None:
        call = "body" if op.body_required else "optionalBody"
        args.insert(_body_index(op), f"body: try arguments.{call}({op.body.swift}.self)")
    return ", ".join(args)


def _kotlin_arguments(op: Operation) -> str:
    args: list[str] = []
    for param in op.params:
        accessor = KOTLIN_ACCESSORS[param.kotlin_type]
        if not param.required:
            accessor = "optional" + accessor[:1].upper() + accessor[1:]
        args.append(f'{_kotlin(param.name)} = arguments.{accessor}("{param.wire}", "{param.location}")')
    if op.body is not None:
        call = "body" if op.body_required else "optionalBody"
        args.insert(_body_index(op), f"body = arguments.{call}(serializer<{op.body.kotlin}>())")
    return ", ".join(args)


def _kdoc(text: str) -> str:
    return text.replace("*/", "*\\/").replace("/*", "/&#42;")


def _swift_encode_result(op: Operation, value: str, indent: str) -> list[str]:
    if op.result_name is None:
        if op.outcomes[0].kind == "empty":
            return [f"{indent}return .null"]
        return [f"{indent}return try RESTArguments.encode({value})"]
    lines = [f"{indent}switch {value} {{"]
    for outcome in op.outcomes:
        case = STATUS_CASES.get(outcome.status, f"status{outcome.status}")
        if outcome.kind == "empty":
            lines.append(f"{indent}case .{case}: return RESTArguments.status({outcome.status}, .null)")
        else:
            lines.append(f"{indent}case .{case}(let value): return RESTArguments.status({outcome.status}, "
                         f"try RESTArguments.encode(value))")
    lines.append(f"{indent}}}")
    return lines


def _swift_operations(ops: list[Operation]) -> str:
    lines = ["// Generated by tools/gen_rest_api.py. Do not edit.", "import Foundation", "import HermesAPI", "",
             "/// Every generated REST operation by `METHOD /path`, for the data-driven live scenario and the",
             "/// fixture decode tests. Results are the typed values re-encoded as JSON.",
             "public enum RESTOperations {",
             "    public static let all: [String] = [",
             *[f'        "{op.method} {op.path}",' for op in ops],
             "    ]", "",
             "    /// Calls the operation through its generated method.",
             "    public static func call(_ operation: String, on rest: HermesREST, with arguments: RESTArguments)",
             "        async throws -> JSONValue {",
             "        switch operation {"]
    for op in ops:
        lines.append(f'        case "{op.method} {op.path}":')
        call = f"rest.{op.namespace}.{_swift(op.name)}({_swift_arguments(op)})"
        if op.result_name is None and op.outcomes[0].kind == "empty":
            lines.append(f"            try await {call}")
            lines.append("            return .null")
        else:
            lines.append(f"            let result = try await {call}")
            lines.extend(_swift_encode_result(op, "result", "            "))
    lines.extend(['        default: throw LiveScenarioError("Unknown REST operation \\(operation)")', "        }", "    }", "",
                  "    /// Decodes a recorded response with the operation's generated model.",
                  "    public static func decode(_ operation: String, _ response: RESTResponse) throws -> JSONValue {",
                  "        switch operation {"])
    for op in ops:
        lines.append(f'        case "{op.method} {op.path}":')
        if op.result_name is None:
            outcome = op.outcomes[0]
            if outcome.kind == "empty":
                lines.append(f"            try response.empty(status: {outcome.status})")
                lines.append("            return .null")
            else:
                lines.append(f"            return try RESTArguments.encode({_swift_decode(outcome)})")
        else:
            lines.append("            switch response.status {")
            for outcome in op.outcomes:
                if outcome.kind == "empty":
                    lines.append(f"            case {outcome.status}: return RESTArguments.status({outcome.status}, .null)")
                else:
                    lines.append(f"            case {outcome.status}: return RESTArguments.status({outcome.status}, "
                                 f"try RESTArguments.encode({_swift_decode(outcome)}))")
            lines.append("            default: throw response.undocumented()")
            lines.append("            }")
    lines.extend(['        default: throw LiveScenarioError("Unknown REST operation \\(operation)")', "        }", "    }", "}"])
    return "\n".join(lines) + "\n"


def _kotlin_encode_result(op: Operation, value: str) -> str:
    if op.result_name is None:
        outcome = op.outcomes[0]
        if outcome.kind == "json":
            return f"json.encodeToJsonElement(serializer<{outcome.kotlin}>(), {value})"
        return f"RESTArguments.encode({value})"
    cases = []
    for outcome in op.outcomes:
        case = STATUS_CASES.get(outcome.status, f"status{outcome.status}")
        case = case[:1].upper() + case[1:]
        if outcome.kind == "empty":
            cases.append(f"is {op.result_name}.{case} -> RESTArguments.status({outcome.status}, JsonNull)")
        elif outcome.kind == "json":
            cases.append(f"is {op.result_name}.{case} -> RESTArguments.status({outcome.status}, "
                         f"json.encodeToJsonElement(serializer<{outcome.kotlin}>(), it.value))")
        else:
            cases.append(f"is {op.result_name}.{case} -> RESTArguments.status({outcome.status}, "
                         f"RESTArguments.encode(it.value))")
    return f"{value}.let {{ when (it) {{ {'; '.join(cases)} }} }}"


def _kotlin_operations(ops: list[Operation]) -> str:
    # One lambda per operation: a single `when` over every operation exceeds the JVM's method size.
    lines = ["// Generated by tools/gen_rest_api.py. Do not edit.",
             "package hermes.api.live.generated", "",
             "import kotlinx.serialization.json.Json",
             "import kotlinx.serialization.json.JsonElement",
             "import kotlinx.serialization.json.JsonNull",
             "import kotlinx.serialization.serializer",
             "import hermes.api.generated.rest.*",
             "import hermes.api.live.LiveScenarioFailure",
             "import hermes.api.live.RESTArguments",
             "import hermes.api.runtime.HermesREST",
             "import hermes.api.runtime.RESTResponse", "",
             "/** Every generated REST operation by `METHOD /path`, for the data-driven live scenario and the",
             " *  fixture decode tests. Results are the typed values re-encoded as JSON. */",
             "public object RESTOperations {",
             "    private val calls: Map<String, suspend (HermesREST, RESTArguments, Json) -> JsonElement> = mapOf("]
    for op in ops:
        call = f"rest.methods.{op.namespace}.{_kotlin(op.name)}({_kotlin_arguments(op)})"
        if op.result_name is None and op.outcomes[0].kind == "empty":
            body = f"{call}; JsonNull"
        else:
            body = _kotlin_encode_result(op, call)
        lines.append(f'        "{op.method} {op.path}" to {{ rest, arguments, json -> {body} }},')
    lines.extend(["    )", "",
                  "    private val decoders: Map<String, (RESTResponse, Json) -> JsonElement> = mapOf("])
    for op in ops:
        if op.result_name is None:
            outcome = op.outcomes[0]
            if outcome.kind == "empty":
                body = f"response.empty({outcome.status}); JsonNull"
            elif outcome.kind == "json":
                body = (f"json.encodeToJsonElement(serializer<{outcome.kotlin}>(), "
                        f"response.json(json, serializer<{outcome.kotlin}>(), {outcome.status}))")
            else:
                body = f"RESTArguments.encode(response.{outcome.kind}({outcome.status}))"
        else:
            cases = []
            for outcome in op.outcomes:
                if outcome.kind == "empty":
                    cases.append(f"{outcome.status} -> RESTArguments.status({outcome.status}, JsonNull)")
                elif outcome.kind == "json":
                    cases.append(f"{outcome.status} -> RESTArguments.status({outcome.status}, json.encodeToJsonElement("
                                 f"serializer<{outcome.kotlin}>(), response.json(json, serializer<{outcome.kotlin}>(), "
                                 f"{outcome.status})))")
                else:
                    cases.append(f"{outcome.status} -> RESTArguments.status({outcome.status}, "
                                 f"RESTArguments.encode(response.{outcome.kind}({outcome.status})))")
            body = f"when (response.status) {{ {'; '.join(cases)}; else -> throw response.undocumented() }}"
        lines.append(f'        "{op.method} {op.path}" to {{ response, json -> {body} }},')
    lines.extend(["    )", "",
                  "    public val all: List<String> get() = calls.keys.toList()", "",
                  "    /** Calls the operation through its generated method. */",
                  "    public suspend fun call(operation: String, rest: HermesREST, arguments: RESTArguments): JsonElement =",
                  '        (calls[operation] ?: throw LiveScenarioFailure("Unknown REST operation $operation"))(rest, arguments, rest.json)',
                  "",
                  "    /** Decodes a recorded response with the operation's generated model. */",
                  "    public fun decode(operation: String, response: RESTResponse, json: Json): JsonElement =",
                  '        (decoders[operation] ?: throw LiveScenarioFailure("Unknown REST operation $operation"))(response, json)',
                  "}"])
    return "\n".join(lines) + "\n"


def _symbols(ops: list[Operation]) -> list[dict]:
    return [{"method": op.method, "path": op.path, "public_name": f"{op.namespace}.{op.name}",
             "request": op.body.swift if op.body else ("multipart" if op.multipart else None),
             "response": op.swift_result}
            for op in ops]


def outputs(ref: str) -> tuple[dict, list[Operation]]:
    document = json.loads((ROOT / "spec/out" / ref / "openapi.json").read_text(encoding="utf-8"))
    gateway = set(json.loads((ROOT / "spec/out" / ref / "generated-model-symbols.json").read_text()))
    graph, ops = build(document, gateway)
    models = declarations(graph, ORIGIN)
    result = {}
    per_file = 35
    for index in range(0, len(models), per_file):
        batch = models[index:index + per_file]
        number = index // per_file
        result[SWIFT_OUT / f"RESTModels{number:02}.swift"] = (
            "// Generated by tools/gen_rest_api.py. Do not edit.\nimport Foundation\n\n"
            + "\n\n".join(item[1].strip() for item in batch) + "\n")
        result[KOTLIN_OUT / f"RESTModels{number:02}.kt"] = (
            "// Generated by tools/gen_rest_api.py. Do not edit.\n"
            "package hermes.api.generated.rest\n\n"
            "import kotlinx.serialization.*\n"
            "import kotlinx.serialization.descriptors.*\n"
            "import kotlinx.serialization.encoding.*\n"
            "import kotlinx.serialization.json.*\n"
            "import hermes.api.runtime.EmptyObject\n"
            "import hermes.api.runtime.Patch\n\n"
            + "\n\n".join(item[2].strip() for item in batch) + "\n")
    result[SWIFT_OUT / "RESTMethods.swift"] = _swift_methods(ops)
    result[SWIFT_LIVE_OUT] = _swift_operations(ops)
    result[KOTLIN_LIVE_OUT] = _kotlin_operations(ops)
    result[KOTLIN_OUT / "RESTMethods.kt"] = _kotlin_methods(ops)
    result[ROOT / "spec/out" / ref / "generated-rest-symbols.json"] = json.dumps(
        _symbols(ops), indent=2, sort_keys=True) + "\n"
    return result, ops


def generate(ref: str, *, check: bool = False) -> int:
    ref = require_release(ref)
    files, ops = outputs(ref)
    existing = set(SWIFT_OUT.glob("*.swift")) | set(KOTLIN_OUT.glob("*.kt"))
    stale = existing - set(files)
    changed = [path for path, body in files.items()
               if not path.exists() or path.read_text(encoding="utf-8") != body]
    if check:
        if changed or stale:
            raise ValueError(f"Generated REST sources stale: {sorted(str(p) for p in [*changed, *stale])}")
        return len(ops)
    for path in stale:
        path.unlink()
    for path, body in files.items():
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
