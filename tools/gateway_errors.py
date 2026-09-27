"""The reviewed gateway error catalog: extracted codes checked against ``spec/gateway-errors.yaml``.

``spec/out/<ref>/gateway-errors.json`` lists what the tagged handlers can answer (see
``extract_gateway_errors.py``); the YAML classifies it. Loading fails whenever the two disagree: a code
nobody classified, a classified code the release no longer uses, a code whose message templates changed
since review, or an override or name that refers to a template the release does not produce.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

import yaml

from tools.ref_policy import ROOT, require_release

REVIEW = ROOT / "spec" / "gateway-errors.yaml"
KINDS = ("invalidRequest", "unsupported", "notFound", "conflict", "busy", "forbidden", "unavailable", "serverError")
KIND_SUMMARIES = {
    "invalidRequest": "The call itself is wrong: fix the parameters, the method or the order of calls.",
    "unsupported": "This backend, agent or host does not offer it.",
    "notFound": "The target does not exist, or no longer does.",
    "conflict": "The current state rules it out until something else changes.",
    "busy": "Hermes is occupied; the same call can succeed shortly.",
    "forbidden": "This client, account or configuration may not do it.",
    "unavailable": "A part of Hermes is down or starting; retry later, possibly after reconnecting.",
    "serverError": "Hermes failed while doing it.",
}
# A template's placeholder for text Hermes fills in at run time.
PLACEHOLDER = "{…}"


@dataclass(frozen=True)
class Code:
    code: int
    kind: str
    # Message templates whose kind differs from the code's, in a stable order.
    messages: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class Name:
    name: str
    summary: str
    kind: str
    # (code, template); a template of None matches every message of the code.
    match: tuple[tuple[int, str | None], ...]


@dataclass(frozen=True)
class MethodError:
    code: int | None  # None for codes Hermes relays from elsewhere
    messages: tuple[str, ...]


@dataclass(frozen=True)
class Catalog:
    codes: tuple[Code, ...]
    names: tuple[Name, ...]
    common: tuple[MethodError, ...]
    methods: dict[str, tuple[MethodError, ...]]


def reviewed_hash(templates: set[str]) -> str:
    """What ``reviewed`` pins: the SHA-256 of the sorted templates as JSON."""
    return hashlib.sha256(json.dumps(sorted(templates), ensure_ascii=False).encode("utf-8")).hexdigest()


def segments(template: str) -> list[str]:
    """The literal text around each placeholder; the runtimes match messages against these."""
    return template.split(PLACEHOLDER)


def informative(template: str) -> bool:
    """Whether a template says anything beyond placeholders, and can therefore identify a message."""
    return bool(template.replace(PLACEHOLDER, "").strip())


def _method_errors(entries: list[dict[str, object]]) -> tuple[MethodError, ...]:
    return tuple(
        MethodError(entry["code"] if isinstance(entry["code"], int) else None,
                    tuple(message for message in entry["messages"] if informative(message)))
        for entry in entries
    )


def load(ref: str, *, extracted_path: Path | None = None, review_path: Path = REVIEW) -> Catalog:
    ref = require_release(ref)
    path = extracted_path or ROOT / "spec" / "out" / ref / "gateway-errors.json"
    extracted = json.loads(path.read_text(encoding="utf-8"))
    if extracted.get("ref") != ref:
        raise ValueError(f"{path} was extracted from {extracted.get('ref')}, not {ref}")
    review = yaml.safe_load(review_path.read_text(encoding="utf-8"))

    templates: dict[int, set[str]] = {}
    for entries in [extracted["common"], *extracted["methods"].values()]:
        for entry in entries:
            if isinstance(entry["code"], int):
                templates.setdefault(entry["code"], set()).update(entry["messages"])

    problems: list[str] = []
    reviewed_codes = review.get("codes") or {}
    for code in sorted(set(templates) - set(reviewed_codes)):
        problems.append(f"code {code} is new: classify it (messages {sorted(templates[code])}, "
                        f"reviewed: {reviewed_hash(templates[code])})")
    for code in sorted(set(reviewed_codes) - set(templates)):
        problems.append(f"code {code} is no longer answered by {ref}: remove it")

    codes: list[Code] = []
    kind_of: dict[tuple[int, str | None], str] = {}
    for code in sorted(set(reviewed_codes) & set(templates)):
        entry = reviewed_codes[code] or {}
        unknown = set(entry) - {"kind", "messages", "reviewed"}
        if unknown:
            problems.append(f"code {code} has unknown keys {sorted(unknown)}")
        kind = entry.get("kind")
        if kind not in KINDS:
            problems.append(f"code {code} has kind {kind!r}; expected one of {KINDS}")
        expected = reviewed_hash(templates[code])
        if entry.get("reviewed") != expected:
            problems.append(f"code {code} changed since review: messages {sorted(templates[code])}; "
                            f"review its kind and overrides, then set reviewed: {expected}")
        overrides = entry.get("messages") or {}
        for template, override in sorted(overrides.items()):
            if template not in templates[code]:
                problems.append(f"code {code} overrides {template!r}, which {ref} never answers with it")
            elif override not in KINDS or override == kind:
                problems.append(f"code {code} overrides {template!r} with {override!r}, not a different kind")
            elif not informative(template):
                problems.append(f"code {code} overrides {template!r}, which matches every message")
            kind_of[(code, template)] = override
        kind_of[(code, None)] = kind
        codes.append(Code(code, kind, tuple(sorted(overrides.items()))))

    names: list[Name] = []
    claimed: dict[tuple[int, str | None], str] = {}
    for name, entry in (review.get("names") or {}).items():
        match: list[tuple[int, str | None]] = []
        kinds: set[str] = set()
        for item in entry.get("match") or []:
            code, template = item.get("code"), item.get("message")
            if code not in templates:
                problems.append(f"name {name} matches code {code}, which {ref} never answers with")
                continue
            if template is not None and template not in templates[code]:
                problems.append(f"name {name} matches {template!r}, which code {code} never carries")
                continue
            if (code, template) in claimed:
                problems.append(f"name {name} and {claimed[(code, template)]} both match {code} {template!r}")
            claimed[(code, template)] = name
            match.append((code, template))
            kinds.add(kind_of.get((code, template), kind_of.get((code, None), "")))
        if not match:
            problems.append(f"name {name} matches nothing")
        if len(kinds) > 1:
            problems.append(f"name {name} spans kinds {sorted(kinds)}; a name must mean one thing")
        if not isinstance(entry.get("summary"), str) or not entry["summary"].strip():
            problems.append(f"name {name} needs a summary")
        names.append(Name(name, str(entry.get("summary", "")).strip(), next(iter(kinds), ""), tuple(match)))

    if problems:
        raise ValueError(f"{review_path.relative_to(ROOT) if review_path.is_relative_to(ROOT) else review_path} "
                         "disagrees with the extracted errors:\n  " + "\n  ".join(problems))
    return Catalog(
        codes=tuple(codes),
        names=tuple(sorted(names, key=lambda item: item.name)),
        common=_method_errors(extracted["common"]),
        methods={name: _method_errors(entries) for name, entries in sorted(extracted["methods"].items())},
    )
