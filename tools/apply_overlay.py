"""Apply reviewed REST response overlays only when their handler source hashes match."""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

import yaml

from tools.fetch_spec import ROOT
from tools.ref_policy import require_release_tag


def apply(ref: str, *, check: bool = False, root: Path = ROOT) -> dict[str, int]:
    ref = require_release_tag(ref)
    output = root / "spec" / "out" / ref
    document = json.loads((output / "openapi.raw.json").read_text(encoding="utf-8"))
    hashes = json.loads((output / "rest-hashes.json").read_text(encoding="utf-8"))
    seen: set[str] = set()
    for path in sorted((root / "spec" / "overlay" / "rest").glob("*.yaml")):
        overlay = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(overlay, dict) or not isinstance(overlay.get("operations"), list):
            raise TypeError(f"Invalid REST overlay: {path}")
        for entry in overlay["operations"]:
            if not isinstance(entry, dict):
                raise TypeError(f"Invalid operation in {path}")
            required = {"method", "path", "x-source", "x-handler-hash", "response"}
            if set(entry) != required:
                raise ValueError(f"REST overlay fields must be exactly {sorted(required)}: {path}")
            method = entry["method"].upper()
            route = entry["path"]
            key = f"{method} {route}"
            if key in seen:
                raise ValueError(f"Duplicate REST overlay operation: {key}")
            seen.add(key)
            actual = hashes.get(key)
            if actual is None:
                raise ValueError(f"Unknown REST operation: {key}")
            if actual["source"] != entry["x-source"] or actual["handler_sha256"] != entry["x-handler-hash"]:
                raise ValueError(f"Stale handler source for {key}; review the tagged handler before updating overlay")
            response = entry["response"]
            if not isinstance(response, dict) or not response or response == {"type": "object"}:
                raise ValueError(f"REST response is not meaningfully typed: {key}")
            operation = document["paths"][route][method.lower()]
            current = operation["responses"]["200"]["content"]["application/json"]["schema"]
            if current:
                raise ValueError(f"Overlay would replace an upstream response schema: {key}")
            operation["responses"]["200"]["content"]["application/json"]["schema"] = copy.deepcopy(response)
            operation["x-source"] = entry["x-source"]
            operation["x-handler-hash"] = entry["x-handler-hash"]
    rendered = json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    target = output / "openapi.json"
    if check:
        if not target.exists() or target.read_text(encoding="utf-8") != rendered:
            raise ValueError(f"Generated REST OpenAPI is stale: {target}")
    else:
        target.write_text(rendered, encoding="utf-8")
    return {"operations": len(hashes), "overlaid": len(seen)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    print(json.dumps(apply(args.ref, check=args.check), sort_keys=True))


if __name__ == "__main__":
    main()
