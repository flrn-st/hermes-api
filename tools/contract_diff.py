"""Summarize OpenRPC wire changes between two tagged Hermes releases."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from tools.fetch_spec import ROOT
from tools.ref_policy import require_release_tag


def _fingerprint(value: object) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _section(contract: dict, key: str) -> dict[str, dict]:
    return {item["name"]: item for item in contract[key]}


def _lines(label: str, old: dict, new: dict) -> list[str]:
    removed = sorted(old.keys() - new.keys())
    added = sorted(new.keys() - old.keys())
    changed = sorted(key for key in old.keys() & new.keys() if _fingerprint(old[key]) != _fingerprint(new[key]))
    lines = [f"### {label}", "", f"Added: {len(added)} · Removed: {len(removed)} · Changed: {len(changed)}", ""]
    for heading, names in (("Removed", removed), ("Changed", changed), ("Added", added)):
        if names:
            lines.append(f"**{heading}**")
            lines.extend(f"- `{name}`" for name in names[:100])
            if len(names) > 100:
                lines.append(f"- …and {len(names) - 100} more")
            lines.append("")
    return lines


def render(previous: str, current: str, root: Path = ROOT) -> str:
    previous = require_release_tag(previous)
    current = require_release_tag(current)
    old = json.loads((root / "spec/out" / previous / "openrpc.json").read_text())
    new = json.loads((root / "spec/out" / current / "openrpc.json").read_text())
    lines = [f"## OpenRPC contract: {previous} → {current}", ""]
    for key, label in (("methods", "Client methods"),
                       ("x-server-requests", "Server requests"),
                       ("x-notifications", "Notifications")):
        lines.extend(_lines(label, _section(old, key), _section(new, key)))
    lines.extend(_lines("Component schemas", old["components"]["schemas"], new["components"]["schemas"]))
    return "\n".join(lines).rstrip() + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-ref", required=True)
    parser.add_argument("--to-ref", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(render(args.from_ref, args.to_ref), encoding="utf-8")


if __name__ == "__main__":
    main()
