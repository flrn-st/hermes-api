"""Report contract coverage honestly and block release publication below 100%."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import yaml

from tools.fetch_spec import ROOT
from tools.gateway_errors import load as load_errors
from tools.ref_policy import require_release


def _open_locations(schema: dict, components: dict, surface: str, path: str = "$",
                    visited: frozenset[str] = frozenset()) -> set[str]:
    """Where a schema stops being strict: values of any shape, and objects that admit undeclared keys.

    A location inside a component is ``Component.field[]{}``; one outside every component is
    ``surface$...``. ``spec/open-schemas.yaml`` names the locations reviewed as free-form by design.
    """
    if "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        if name in visited:
            return set()
        return _open_locations(components[name], components, surface, name, visited | {name})
    if not any(key in schema for key in ("type", "enum", "const", "anyOf", "oneOf", "allOf")):
        return {path if not path.startswith("$") else surface + path}
    found: set[str] = set()
    extra = schema.get("additionalProperties")
    if extra is True:
        found.add(path if not path.startswith("$") else surface + path)
    elif isinstance(extra, dict):
        found |= _open_locations(extra, components, surface, path + "{}", visited)
    for name, value in schema.get("properties", {}).items():
        found |= _open_locations(value, components, surface, f"{path}.{name}", visited)
    if "items" in schema:
        found |= _open_locations(schema["items"], components, surface, path + "[]", visited)
    for key in ("anyOf", "oneOf", "allOf"):
        for value in schema.get(key, []):
            found |= _open_locations(value, components, surface, path, visited)
    return found


def _gateway_entries(contract: dict, symbols: dict) -> list[dict]:
    components = contract["components"]["schemas"]
    result: list[dict] = []
    for section, symbol_key, kind in (
        ("methods", "methods", "gateway_method"),
        ("x-server-requests", "server_requests", "server_request"),
        ("x-notifications", "events", "event"),
    ):
        generated = {item["wire"] for item in symbols[symbol_key]}
        for item in contract[section]:
            # Tagged methods may deliberately accept free-form JSON input (for
            # example prompt.submit.text). Strict typed credit requires a
            # closed result, while generation still covers every param type.
            schemas = ([item["result"]["schema"]] if "result" in item else
                       [param["schema"] for param in item.get("params", [])])
            result.append({
                "kind": kind, "name": item["name"],
                "open": sorted({f"gateway:{location}" for schema in schemas
                                for location in _open_locations(schema, components, item["name"])}),
                "generated": item["name"] in generated,
                "fixture": False, "decode": False, "live_swift": False, "live_kotlin": False,
            })
    return result


def _rest_entries(document: dict, generated_symbols: list[dict], hashes: dict) -> list[dict]:
    generated = {(item["method"], item["path"]) for item in generated_symbols}
    result: list[dict] = []
    for path, operations in document["paths"].items():
        for method, operation in operations.items():
            key = f"{method.upper()} {path}"
            responses = [value for status, value in operation["responses"].items() if status.startswith(("2", "3"))]
            schemas = [media["schema"] for response in responses for media in response.get("content", {}).values()]
            # A documented response without a body (HEAD, 204, a redirect) has nothing left to type.
            open_locations = {f"rest:{location}" for schema in schemas
                              for location in _open_locations(schema, document["components"]["schemas"], key)}
            if not responses:
                open_locations.add(f"rest:{key}$")
            fresh = (operation.get("x-source") == hashes.get(key, {}).get("source") and
                     operation.get("x-handler-hash") == hashes.get(key, {}).get("handler_sha256"))
            result.append({
                "kind": "rest", "name": key, "open": sorted(open_locations),
                "generated": (method.upper(), path) in generated,
                "fixture": False, "decode": False, "live_swift": False, "live_kotlin": False,
                "fresh": fresh,
            })
    return result


def _apply_evidence(entries: list[dict], ref: str, root: Path, commit: str) -> None:
    path = root / "coverage/evidence" / f"{ref}.json"
    if not path.exists():
        return
    evidence = json.loads(path.read_text(encoding="utf-8"))
    if evidence["ref"] != ref or evidence["commit"] != commit:
        raise ValueError("Coverage evidence does not match the pinned release")
    fixture = root / "fixtures" / ref / "liveness.jsonl"
    if evidence["fixture"] != str(fixture.relative_to(root)):
        raise ValueError("Coverage fixture path does not match the tagged scenario")
    if evidence["fixture_sha256"] != hashlib.sha256(fixture.read_bytes()).hexdigest():
        raise ValueError("Coverage fixture changed after evidence was recorded")
    recorded = {json.loads(line)["name"] for line in fixture.read_text(encoding="utf-8").splitlines()}
    declared = (set(evidence["methods"]) | set(evidence["server_requests"]) |
                set(evidence["events"]) | set(evidence["rest"]))
    if recorded != declared:
        raise ValueError("Coverage evidence does not match recorded frame names")
    for platform in ("swift", "kotlin"):
        if not set(evidence["live_rest"][platform]) <= set(evidence["rest"]):
            raise ValueError(f"{platform} live evidence includes an unrecorded REST operation")
    names = {name.name for name in load_errors(ref).names}
    for platform in ("swift", "kotlin"):
        unknown = set(evidence.get("live_errors", {}).get(platform, [])) - names
        if unknown:
            raise ValueError(f"{platform} live evidence names errors the catalog lacks: {sorted(unknown)}")
    # Gateway live credit is what each client's scenarios exercised, measured on the wire.
    report_key = {"gateway_method": "methods", "event": "events", "server_request": "server_requests"}
    known = {(entry["kind"], entry["name"]) for entry in entries}
    live_gateway = evidence["live_gateway"]
    for platform in ("swift", "kotlin"):
        for kind, key in report_key.items():
            unknown = {name for name in live_gateway[platform][key] if (kind, name) not in known}
            if unknown:
                raise ValueError(f"{platform} live evidence names unknown {kind} items: {sorted(unknown)}")
    for entry in entries:
        if entry["kind"] in report_key:
            key = report_key[entry["kind"]]
            entry["fixture"] = entry["name"] in evidence[key]
            entry["live_swift"] = entry["name"] in live_gateway["swift"][key]
            entry["live_kotlin"] = entry["name"] in live_gateway["kotlin"][key]
        else:
            entry["fixture"] = entry["name"] in evidence["rest"]
            entry["live_swift"] = entry["name"] in evidence["live_rest"]["swift"]
            entry["live_kotlin"] = entry["name"] in evidence["live_rest"]["kotlin"]
        entry["decode"] = entry["fixture"] and evidence["decode_swift"] and evidence["decode_kotlin"]


CHECKS = ("typed", "generated", "fixture", "decode", "live_swift", "live_kotlin")
KINDS = ("gateway_method", "server_request", "event", "rest")


def _load(root: Path, relative: str) -> dict:
    path = root / relative
    return (yaml.safe_load(path.read_text(encoding="utf-8")) or {}) if path.exists() else {}


def _reviewed(item: dict) -> bool:
    return bool(str(item.get("reason") or "").strip() and str(item.get("reviewed_by") or "").strip())


def _apply_open_schemas(entries: list[dict], root: Path, assume_proposed: bool) -> tuple[set[str], set[str]]:
    """Accept the open locations a human reviewed as free-form by design; a surface is typed when every open
    location it reaches is accepted. Returns the accepted and the proposed locations."""
    document = _load(root, "spec/open-schemas.yaml")
    reachable = {location for entry in entries for location in entry["open"]}
    problems: list[str] = []
    accepted: set[str] = set()
    proposed: set[str] = set()
    for section in ("reviewed", "proposed"):
        for item in document.get(section) or []:
            location = str(item.get("location") or "")
            if location not in reachable:
                problems.append(f"{location or item} is not an open location of this release")
            elif location in accepted | proposed:
                problems.append(f"{location} is listed twice")
            elif section == "reviewed" and not _reviewed(item):
                problems.append(f"{location} needs a reason and reviewed_by")
            elif section == "proposed" and not str(item.get("reason") or "").strip():
                problems.append(f"proposed {location} needs a reason")
            (accepted if section == "reviewed" else proposed).add(location)
    if problems:
        raise ValueError("spec/open-schemas.yaml:\n  " + "\n  ".join(problems))
    counted = accepted | proposed if assume_proposed else accepted
    for entry in entries:
        entry["open"] = [location for location in entry["open"] if location not in counted]
        entry["typed"] = not entry["open"]
    return accepted, proposed


def _apply_exemptions(entries: list[dict], root: Path, assume_proposed: bool) -> None:
    """Mark surfaces a human exempted from the release gate, and reject exemptions that no longer apply.
    Proposed exemptions carry a reason but no reviewer yet; they count only with ``assume_proposed``."""
    document = _load(root, "spec/exemptions.yaml")
    by_key = {(entry["kind"], entry["name"]): entry for entry in entries}
    problems = []
    seen: set[tuple[str, str]] = set()
    for section in ("exemptions", "proposed"):
        for item in document.get(section) or []:
            key = (item.get("kind"), item.get("name"))
            reason = str(item.get("reason") or "").strip()
            if key not in by_key:
                problems.append(f"{key} is not a surface of this release")
            elif key in seen:
                problems.append(f"{key} is exempted twice")
            elif not reason or (section == "exemptions" and not _reviewed(item)):
                problems.append(f"{key} needs a reason{' and reviewed_by' if section == 'exemptions' else ''}")
            elif by_key[key]["complete"]:
                problems.append(f"{key} is complete now; remove its exemption")
            elif section == "exemptions":
                by_key[key]["exempt"] = reason
            else:
                by_key[key]["proposed"] = reason
                if assume_proposed:
                    by_key[key]["exempt"] = reason
            seen.add(key)
    if problems:
        raise ValueError("spec/exemptions.yaml:\n  " + "\n  ".join(problems))


def report(ref: str, root: Path = ROOT, *, assume_proposed: bool = False) -> dict:
    """Coverage of the pinned release. ``assume_proposed`` counts proposed reviews as signed, to show what
    signing them would change."""
    ref = require_release(ref)
    source = root / "spec/out" / ref
    contract = json.loads((source / "openrpc.json").read_text())
    symbols = json.loads((source / "gateway-symbols.json").read_text())
    openapi_path = source / "openapi.json"
    document = json.loads((openapi_path if openapi_path.exists() else source / "openapi.raw.json").read_text())
    generated_path = source / "generated-rest-symbols.json"
    rest_symbols = json.loads(generated_path.read_text()) if generated_path.exists() else []
    hashes = json.loads((source / "rest-hashes.json").read_text())
    entries = _gateway_entries(contract, symbols) + _rest_entries(document, rest_symbols, hashes)
    meta = json.loads((source / "meta.json").read_text())
    _apply_evidence(entries, ref, root, meta["commit"])
    accepted, proposed_open = _apply_open_schemas(entries, root, assume_proposed)
    for entry in entries:
        entry["complete"] = all(entry[name] for name in CHECKS) and (entry.get("fresh", True))
        entry["exempt"] = None
        entry["proposed"] = None
    _apply_exemptions(entries, root, assume_proposed)
    by_kind: dict[str, dict[str, int]] = {}
    for kind in KINDS:
        subset = [entry for entry in entries if entry["kind"] == kind]
        by_kind[kind] = {"total": len(subset), "complete": sum(entry["complete"] for entry in subset),
                         "exempt": sum(entry["exempt"] is not None for entry in subset),
                         "typed": sum(entry["typed"] for entry in subset),
                         "generated": sum(entry["generated"] for entry in subset),
                         "fixture": sum(entry["fixture"] for entry in subset),
                         "decode": sum(entry["decode"] for entry in subset),
                         "live_both": sum(entry["live_swift"] and entry["live_kotlin"] for entry in subset)}
    return {"ref": ref, "summary": by_kind, "entries": entries, "errors": _error_coverage(ref, root),
            "total": len(entries), "complete": sum(entry["complete"] for entry in entries),
            "exempt": sum(entry["exempt"] is not None for entry in entries),
            "proposed": sum(entry["proposed"] is not None and entry["exempt"] is None for entry in entries),
            "open_schemas": {"accepted": len(accepted), "proposed": len(proposed_open)}}


def _missing(entry: dict) -> list[str]:
    missing = [name if name != "typed" else f"typed ({', '.join(entry['open'])})" for name in CHECKS
               if not entry[name]]
    if not entry.get("fresh", True):
        missing.append("fresh")
    return missing


def _error_coverage(ref: str, root: Path) -> dict:
    """Named gateway errors each client met live. Informational: many cannot be provoked on demand."""
    names = sorted(name.name for name in load_errors(ref).names)
    path = root / "coverage/evidence" / f"{ref}.json"
    live = json.loads(path.read_text(encoding="utf-8")).get("live_errors", {}) if path.exists() else {}
    swift, kotlin = set(live.get("swift", [])), set(live.get("kotlin", []))
    return {"named": len(names), "live_both": sorted(swift & kotlin),
            "not_live": [name for name in names if name not in swift & kotlin]}


def write_report(ref: str, root: Path = ROOT) -> dict:
    data = report(ref, root)
    output = root / "coverage"
    output.mkdir(exist_ok=True)
    (output / f"{ref}.json").write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
    reviews = data["open_schemas"]
    headline = (f"Complete: **{data['complete']} / {data['total']}**, exempted: **{data['exempt']}**, "
                f"awaiting review: **{data['proposed']}** proposed exemptions and **{reviews['proposed']}** proposed "
                f"free-form schema locations ({reviews['accepted']} reviewed).")
    lines = [f"# Coverage for {ref}", "", headline, "",
             "| Surface | Complete | Exempt | Typed | Generated | Fixture | Decode | Live both | Total |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for kind, counts in data["summary"].items():
        lines.append(f"| {kind} | {counts['complete']} | {counts['exempt']} | {counts['typed']} | "
                     f"{counts['generated']} | {counts['fixture']} | {counts['decode']} | {counts['live_both']} | "
                     f"{counts['total']} |")
    errors = data["errors"]
    live = ", ".join(errors["live_both"]) or "none"
    summary = f"**{len(errors['live_both'])} / {errors['named']}** ({live})"
    lines.extend(["", f"Named gateway errors classified live by both clients: {summary}."])
    lines.extend(["", "Fixture and live credit requires a recorded tag-pinned scenario, both clients passing live calls, and both fixture decode suites passing. The remaining operations require new scenarios and reviewed REST schemas.", ""])
    gaps = [entry for entry in data["entries"] if not entry["complete"] and entry["exempt"] is None]
    if gaps:
        lines.extend([f"## Gaps ({len(gaps)})", "",
                      "Neither complete nor exempted in `spec/exemptions.yaml`, with the evidence each lacks.", ""])
        for kind in KINDS:
            subset = sorted((entry for entry in gaps if entry["kind"] == kind), key=lambda entry: entry["name"])
            if subset:
                lines.extend([f"### {kind} ({len(subset)})", ""])
                lines.extend(f"- `{entry['name']}`: {', '.join(_missing(entry))}"
                             + (" — proposed exemption" if entry["proposed"] else "") for entry in subset)
                lines.append("")
    (output / f"{ref}.md").write_text("\n".join(lines))
    return data


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--gate", action="store_true", help="Fail if any operation lacks required evidence")
    parser.add_argument("--assume-proposed", action="store_true",
                        help="Only print what the gate would say if every proposed review were signed")
    args = parser.parse_args()
    if args.assume_proposed:
        data = report(args.ref, assume_proposed=True)
        left = [entry for entry in data["entries"] if not entry["complete"] and entry["exempt"] is None]
        print(f"With every proposal signed: {data['complete']}/{data['total']} complete, {data['exempt']} exempted, "
              f"{len(left)} left")
        for entry in left:
            print(f"  {entry['kind']} {entry['name']}: {', '.join(_missing(entry))}")
        raise SystemExit(1 if left and args.gate else 0)
    data = write_report(args.ref)
    print(f"Coverage {data['complete']}/{data['total']} for {args.ref}, {data['exempt']} exempted")
    if args.gate and data["complete"] + data["exempt"] != data["total"]:
        print(f"{data['total'] - data['complete'] - data['exempt']} surfaces are neither complete nor exempted; "
              f"see coverage/{args.ref}.md")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
