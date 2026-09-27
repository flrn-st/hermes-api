"""Run inside the tagged Hermes Python environment. Invoked by extract_gateway_errors.py.

Hermes declares no gateway errors in its contract: handlers answer ``_err(rid, code, message)``, often
through shared helpers and decorators. This finds, for every registered gateway method, the error codes
its handler can answer with. The registry and closures come from the imported server, so decorators
resolve to the functions they wrap and the codes they were given; the source is then read with ``ast``,
following calls to module-level functions by name. A code listed for a method is one some path of its
handler can produce, not one every call risks. Calls through variables, class methods and recursion past
its first level are not followed, so a handler can have codes the extraction misses.
"""

from __future__ import annotations

import argparse
import ast
import json
import sys
import types
from dataclasses import dataclass
from pathlib import Path

# Error builders and the positional index of their code argument; the message follows it.
BUILDERS = {"_err": 1, "_error": 0}
# Frames answered by the socket loop itself, whatever the method.
TRANSPORT_FILES = ("ws.py", "transport.py", "entry.py")


@dataclass(frozen=True)
class Param:
    """A value the enclosing function receives as its parameter ``name``."""
    name: str


DYNAMIC = "dynamic"


@dataclass(frozen=True)
class Site:
    code: object  # int, Param or DYNAMIC
    message: object  # str, Param or None
    file: str
    line: int


class Source:
    """Every function of the ``tui_gateway`` package, indexed for lookups by code object and by name."""

    def __init__(self, package: Path) -> None:
        self.package = package
        self.by_start: dict[tuple[str, int], ast.AST] = {}
        self.by_name: dict[str, list[tuple[str, ast.AST]]] = {}
        self.module_aliases: dict[str, set[str]] = {}
        self.constants: dict[str, dict[str, object]] = {}
        self.file_of: dict[int, str] = {}
        for path in sorted(package.rglob("*.py")):
            relative = path.relative_to(package.parent).as_posix()
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            aliases: set[str] = set()
            for node in ast.walk(tree):
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
                    starts = [node.lineno] + [d.lineno for d in getattr(node, "decorator_list", [])]
                    self.by_start[(relative, min(starts))] = node
                    self.by_start.setdefault((relative, node.lineno), node)
                    self.file_of[id(node)] = relative
                elif isinstance(node, ast.Import):
                    aliases.update(alias.asname or alias.name.split(".")[0] for alias in node.names)
                elif isinstance(node, ast.ImportFrom):
                    aliases.update(alias.asname or alias.name for alias in node.names)
            for node in tree.body:
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    self.by_name.setdefault(node.name, []).append((relative, node))
            self.module_aliases[relative] = aliases | {"server"}

    def load_constants(self) -> None:
        """Module globals holding ints or strings, as the running server sees them (imports included)."""
        for module in list(sys.modules.values()):
            file = getattr(module, "__file__", None)
            if not file:
                continue
            try:
                relative = Path(file).resolve().relative_to(self.package.parent.resolve()).as_posix()
            except ValueError:
                continue
            self.constants[relative] = {
                name: value for name, value in vars(module).items()
                if isinstance(value, (int, str)) and not isinstance(value, bool)
            }


class Analyzer:
    def __init__(self, source: Source) -> None:
        self.source = source
        self.memo: dict[tuple[int, tuple], frozenset[Site]] = {}
        self.active: set[tuple[int, tuple]] = set()

    def function_sites(self, node: ast.AST, bindings: dict[str, object], *, follow: bool = True) -> frozenset[Site]:
        key = (id(node), tuple(sorted((k, repr(v)) for k, v in bindings.items())), follow)
        if key in self.memo:
            return self.memo[key]
        if key in self.active:
            return frozenset()
        self.active.add(key)
        file = self.source.file_of[id(node)]
        params = _parameters(node)
        scope = {name: Param(name) for name in params}
        scope.update(bindings)
        sites: set[Site] = set()
        body = node.body if isinstance(node.body, list) else [node.body]
        for statement in body:
            for child in ast.walk(statement):
                if isinstance(child, ast.Call):
                    sites.update(self._call_sites(child, file, scope, follow))
                elif isinstance(child, ast.Dict):
                    sites.update(self._dict_sites(child, file, scope))
        result = frozenset(sites)
        self.active.discard(key)
        self.memo[key] = result
        return result

    def _call_sites(self, call: ast.Call, file: str, scope: dict[str, object], follow: bool) -> set[Site]:
        name = _callee(call, self.source.module_aliases.get(file, set()))
        if name is None:
            return set()
        if name in BUILDERS:
            index = BUILDERS[name]
            code = _argument(call, index, "code")
            message = _argument(call, index + 1, "msg") or _argument(call, index + 1, "message")
            codes = self._values(code, file, scope) if code is not None else {DYNAMIC}
            text = self._message(message, file, scope)
            return {Site(value, text, file, call.lineno) for value in codes}
        if not follow:
            return set()
        sites: set[Site] = set()
        for callee_file, callee in self.source.by_name.get(name, []):
            params = _parameters(callee)
            for site in self.function_sites(callee, {}):
                code, message = site.code, site.message
                if isinstance(code, Param):
                    argument = _bound_argument(call, params, code.name)
                    if argument is not None:
                        code_values = self._values(argument, file, scope)
                    elif (default := _default(callee, code.name)) is not None:
                        code_values = self._values(default, callee_file, {})
                    else:
                        code_values = {DYNAMIC}
                else:
                    code_values = {code}
                if isinstance(message, Param):
                    argument = _bound_argument(call, params, message.name)
                    if argument is not None:
                        message = self._message(argument, file, scope)
                    elif (default := _default(callee, message.name)) is not None:
                        message = self._message(default, callee_file, {})
                    else:
                        message = None
                sites.update(Site(value, message, site.file, site.line) for value in code_values)
        return sites

    def _dict_sites(self, node: ast.Dict, file: str, scope: dict[str, object]) -> set[Site]:
        fields = {key.value: value for key, value in zip(node.keys, node.values, strict=True)
                  if isinstance(key, ast.Constant) and isinstance(key.value, str)}
        if set(fields) != {"code", "message"} and set(fields) != {"code", "message", "data"}:
            return set()
        codes = self._values(fields["code"], file, scope)
        if not any(isinstance(value, int) and (value < 0 or 4000 <= value < 6000) for value in codes):
            return set()
        return {Site(value, self._message(fields["message"], file, scope), file, node.lineno) for value in codes}

    def _values(self, node: ast.AST, file: str, scope: dict[str, object]) -> set[object]:
        if isinstance(node, ast.Constant) and isinstance(node.value, int) and not isinstance(node.value, bool):
            return {node.value}
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            return {-value for value in self._values(node.operand, file, scope) if isinstance(value, int)} or {DYNAMIC}
        if isinstance(node, ast.Name):
            value = scope.get(node.id, self.source.constants.get(file, {}).get(node.id))
            if isinstance(value, (int, Param)) and not isinstance(value, bool):
                return {value}
            return {DYNAMIC}
        if isinstance(node, ast.IfExp):
            return self._values(node.body, file, scope) | self._values(node.orelse, file, scope)
        if isinstance(node, ast.BoolOp):
            values: set[object] = set()
            for operand in node.values:
                values |= self._values(operand, file, scope)
            return values
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "int" and node.args:
            return self._values(node.args[0], file, scope)
        return {DYNAMIC}

    def _message(self, node: ast.AST | None, file: str, scope: dict[str, object]) -> object:
        if node is None:
            return None
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        if isinstance(node, ast.JoinedStr):
            return "".join(part.value if isinstance(part, ast.Constant) else "{…}" for part in node.values)
        if isinstance(node, ast.Name):
            value = scope.get(node.id, self.source.constants.get(file, {}).get(node.id))
            if isinstance(value, (str, Param)):
                return value
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
            # Concatenated text: the parts that are not literal become placeholders.
            parts = [self._message(side, file, scope) for side in (node.left, node.right)]
            if any(isinstance(part, str) for part in parts):
                return "".join(part if isinstance(part, str) else "{…}" for part in parts)
        return None


def _parameters(node: ast.AST) -> list[str]:
    arguments = node.args
    return [arg.arg for arg in [*arguments.posonlyargs, *arguments.args, *arguments.kwonlyargs]]


def _default(function: ast.AST, name: str) -> ast.AST | None:
    """The default value of parameter ``name``, if it has one."""
    arguments = function.args
    positional = [*arguments.posonlyargs, *arguments.args]
    offset = len(positional) - len(arguments.defaults)
    for index, arg in enumerate(positional):
        if arg.arg == name and index >= offset:
            return arguments.defaults[index - offset]
    for arg, default in zip(arguments.kwonlyargs, arguments.kw_defaults, strict=True):
        if arg.arg == name:
            return default
    return None


def _argument(call: ast.Call, index: int, keyword: str) -> ast.AST | None:
    for item in call.keywords:
        if item.arg == keyword:
            return item.value
    if index < len(call.args) and not any(isinstance(arg, ast.Starred) for arg in call.args[:index + 1]):
        return call.args[index]
    return None


def _bound_argument(call: ast.Call, params: list[str], name: str) -> ast.AST | None:
    index = params.index(name) if name in params else len(call.args) + 1
    return _argument(call, index, name)


def _callee(call: ast.Call, module_aliases: set[str]) -> str | None:
    if isinstance(call.func, ast.Name):
        return call.func.id
    if (isinstance(call.func, ast.Attribute) and isinstance(call.func.value, ast.Name)
            and call.func.value.id in module_aliases):
        return call.func.attr
    return None


def _entries(fn: object) -> list[tuple[types.CodeType, dict[str, object]]]:
    """The code objects a registered handler runs, with the values its closures were built with."""
    found: list[tuple[types.CodeType, dict[str, object]]] = []
    seen: set[int] = set()
    pending = [fn]
    while pending:
        item = pending.pop()
        if id(item) in seen:
            continue
        seen.add(id(item))
        if isinstance(item, types.MethodType):
            pending.append(item.__func__)
            continue
        inner = getattr(item, "func", None)
        if inner is not None and callable(inner):
            pending.append(inner)
        wrapped = getattr(item, "__wrapped__", None)
        if wrapped is not None:
            pending.append(wrapped)
        code = getattr(item, "__code__", None)
        if not isinstance(code, types.CodeType):
            continue
        bindings: dict[str, object] = {}
        for name, cell in zip(code.co_freevars, getattr(item, "__closure__", None) or (), strict=True):
            try:
                value = cell.cell_contents
            except ValueError:
                continue
            if isinstance(value, (int, str)) and not isinstance(value, bool):
                bindings[name] = value
            elif callable(value):
                pending.append(value)
        found.append((code, bindings))
    return found


def _describe(sites: set[Site]) -> list[dict[str, object]]:
    by_code: dict[object, dict[str, object]] = {}
    for site in sites:
        code = site.code if isinstance(site.code, int) else DYNAMIC
        entry = by_code.setdefault(code, {"code": code, "messages": set(), "sites": set()})
        if isinstance(site.message, str):
            entry["messages"].add(site.message)
        entry["sites"].add(f"{site.file}:{site.line}")
    return [
        {"code": entry["code"], "messages": sorted(entry["messages"]), "sites": sorted(entry["sites"])}
        for entry in sorted(by_code.values(), key=lambda item: (not isinstance(item["code"], int),
                                                                   item["code"] if isinstance(item["code"], int) else 0))
    ]


def render(source_repo: Path) -> dict[str, object]:
    from tui_gateway import rpc_dispatch, server

    return analyze(source_repo.resolve() / "tui_gateway", server._methods, rpc_dispatch.handle_request)


def analyze(package: Path, methods: dict[str, object], dispatch: object) -> dict[str, object]:
    """The errors of every registered method, and of the dispatch they all pass through, for an imported
    package whose source is at ``package``."""
    source = Source(package)
    source.load_constants()
    analyzer = Analyzer(source)

    def sites_of(fn: object, *, follow: bool = True) -> set[Site]:
        sites: set[Site] = set()
        for code, bindings in _entries(fn):
            try:
                relative = Path(code.co_filename).resolve().relative_to(package.parent).as_posix()
            except ValueError:
                continue
            node = source.by_start.get((relative, code.co_firstlineno))
            if node is None:
                raise ValueError(f"No source function starts at {relative}:{code.co_firstlineno}")
            sites |= analyzer.function_sites(node, bindings, follow=follow)
        return sites

    # Every method passes through dispatch before its handler runs.
    common = sites_of(dispatch)
    for relative, node in ((source.file_of[id(node)], node) for node in source.by_start.values()):
        if relative.rsplit("/", 1)[-1] in TRANSPORT_FILES:
            common |= analyzer.function_sites(node, {}, follow=False)
    common = {site for site in common if isinstance(site.code, int)}

    return {"common": _describe(common),
            "methods": {name: _describe(sites_of(methods[name])) for name in sorted(methods)}}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-repo", type=Path, required=True)
    # Importing the server takes over stdout for its stdio transport, so the result goes to a file.
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(render(args.source_repo), indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
