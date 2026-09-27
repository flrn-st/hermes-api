"""Sign proposed coverage reviews: move them from ``proposed`` to the signed list with a reviewer.

A person runs this after reading the proposals in ``spec/exemptions.yaml`` and ``spec/open-schemas.yaml``;
``--reviewer`` records who reviewed them. ``--match`` limits signing to entries whose name or location
matches a regular expression, so a reviewer can accept some proposals and leave the rest.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

import yaml

from tools.fetch_spec import ROOT

FILES = {"spec/exemptions.yaml": ("exemptions", "name"), "spec/open-schemas.yaml": ("reviewed", "location")}


def sign(reviewer: str, match: str | None = None, root: Path = ROOT) -> dict[str, int]:
    if not reviewer.strip():
        raise ValueError("A reviewer is required")
    pattern = re.compile(match) if match else None
    signed: dict[str, int] = {}
    for relative, (target, key) in FILES.items():
        path = root / relative
        document = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        header = "".join(line for line in path.read_text(encoding="utf-8").splitlines(keepends=True)
                         if line.startswith("#"))
        accepted, remaining = [], []
        for item in document.get("proposed") or []:
            chosen = pattern is None or pattern.search(str(item.get(key, "")))
            (accepted if chosen else remaining).append(item)
        document[target] = (document.get(target) or []) + [{**item, "reviewed_by": reviewer} for item in accepted]
        document["proposed"] = remaining
        body = yaml.safe_dump({target: document[target], "proposed": document["proposed"]},
                              sort_keys=False, allow_unicode=True, width=120)
        path.write_text(header + body, encoding="utf-8")
        signed[relative] = len(accepted)
    return signed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reviewer", required=True, help="who reviewed the proposals")
    parser.add_argument("--match", help="sign only entries whose name or location matches this expression")
    args = parser.parse_args()
    for path, count in sign(args.reviewer, args.match).items():
        print(f"{path}: signed {count}")


if __name__ == "__main__":
    main()
