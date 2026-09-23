"""One wire-name to public-name mapping shared by Swift and Kotlin generators.

For RPC calls, the first dot segment is the namespace and the remaining
segments are joined as one camelCase method. A call without a dot is directly
on the gateway. Event and server-request names use all segments for a single
camelCase case name. Underscores and hyphens separate words within a segment.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass

_WORD = re.compile(r"[A-Za-z0-9]+")


def _parts(wire_name: str) -> list[str]:
    if not isinstance(wire_name, str) or not wire_name:
        raise ValueError("Empty wire name")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", wire_name):
        raise ValueError(f"Unsupported wire name: {wire_name!r}")
    if wire_name.startswith(".") or wire_name.endswith(".") or ".." in wire_name:
        raise ValueError(f"Empty wire name segment: {wire_name!r}")
    words = _WORD.findall(wire_name)
    if not words or words[0][0].isdigit():
        raise ValueError(f"Invalid wire name: {wire_name!r}")
    return words


def camel(wire_name: str) -> str:
    words = _parts(wire_name)
    return words[0][0].lower() + words[0][1:] + "".join(w[:1].upper() + w[1:] for w in words[1:])


def pascal(wire_name: str) -> str:
    words = _parts(wire_name)
    return "".join(w[:1].upper() + w[1:] for w in words)


@dataclass(frozen=True)
class MethodSymbol:
    wire_name: str
    namespace: str | None
    name: str

    @property
    def public_name(self) -> str:
        return f"{self.namespace}.{self.name}" if self.namespace else self.name


def method_symbol(wire_name: str) -> MethodSymbol:
    segments = wire_name.split(".")
    if len(segments) == 1:
        return MethodSymbol(wire_name, None, camel(wire_name))
    return MethodSymbol(wire_name, camel(segments[0]), camel(".".join(segments[1:])))


def checked_method_symbols(wire_names: Iterable[str]) -> list[MethodSymbol]:
    symbols = [method_symbol(name) for name in wire_names]
    names = [symbol.public_name for symbol in symbols]
    if len(names) != len(set(names)):
        duplicate = next(name for name in names if names.count(name) > 1)
        raise ValueError(f"Public method name collision: {duplicate}")
    return symbols
