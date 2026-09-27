"""Draft REST overlays for a pinned release from a Hermes checkout's response contracts.

Run by hand, never by ``make gen``. The contract checkout (a branch that documents every dashboard
route's responses) is an authoring aid only: each operation whose handler body is byte-identical at
the pinned tag and in the checkout gets an overlay entry carrying the tag's ``x-source`` and
``x-handler-hash``, the checkout's success responses, and ``x-contract`` naming the commit and model.
Handlers that differ are listed for manual review and never imported. The output replaces
``spec/overlay/rest/contracts/`` wholesale, so re-running is idempotent; hand-written overlays live
beside it and win: the importer skips their operations, and their components replace the
checkout's components of the same name (a correction for a helper that changed after the tag).
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path

import yaml

from tools.apply_overlay import untyped
from tools.fetch_spec import ROOT
from tools.ref_policy import require_release

CONTRACTS = Path("spec/overlay/rest/contracts")
REF_PREFIX = "#/components/schemas/"


def overlay_file(source: str) -> str:
    """The overlay file stem for a handler or model source path such as ``hermes_cli/web_routers/x.py``."""
    parts = source.split(":", 1)[0].split("/")
    if parts[0] == "plugins" and len(parts) > 1:
        return parts[1]
    # A router module names its file; any other package (hermes_cli/dashboard_auth/routes.py) names it.
    if len(parts) == 3 and parts[1] not in {"web_routers", "web_responses"}:
        return parts[1]
    return parts[-1].removesuffix(".py")


def _strip_titles(schema: object) -> object:
    """Drop the ``title`` keyword Pydantic puts on every schema; ``properties`` keys stay intact."""
    if isinstance(schema, dict):
        result = {}
        for key, value in schema.items():
            if key == "title":
                continue
            if key == "properties" and isinstance(value, dict):
                result[key] = {name: _strip_titles(item) for name, item in value.items()}
            else:
                result[key] = _strip_titles(value)
        return result
    if isinstance(schema, list):
        return [_strip_titles(item) for item in schema]
    return schema


def _refs(schema: object) -> set[str]:
    found: set[str] = set()
    if isinstance(schema, dict):
        ref = schema.get("$ref")
        if isinstance(ref, str) and ref.startswith(REF_PREFIX):
            found.add(ref.removeprefix(REF_PREFIX))
        for value in schema.values():
            found |= _refs(value)
    elif isinstance(schema, list):
        for value in schema:
            found |= _refs(value)
    return found


def _closure(names: set[str], schemas: dict[str, dict]) -> set[str]:
    pending, seen = list(names), set()
    while pending:
        name = pending.pop()
        if name in seen:
            continue
        seen.add(name)
        pending.extend(_refs(schemas[name]) - seen)
    return seen


def _success(operation: dict) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for status, response in sorted(operation["responses"].items()):
        if status[0] not in "23":
            continue
        result[status] = {key: _strip_titles(value) for key, value in response.items()
                          if key in {"description", "content", "headers"}}
    return result


def _model(responses: dict[str, dict]) -> str:
    names: list[str] = []
    for status, response in responses.items():
        for media, body in response.get("content", {}).items():
            schema = body["schema"]
            if media != "application/json":
                names.append(f"{status} {media}")
            elif "$ref" in schema:
                names.append(schema["$ref"].removeprefix(REF_PREFIX))
            else:
                names.append(json.dumps(schema, sort_keys=True))
        if not response.get("content"):
            names.append(f"{status} redirect" if status[0] == "3" else status)
    return ", ".join(names)


def _manual_overlays(root: Path) -> tuple[set[str], dict[str, dict]]:
    operations: set[str] = set()
    components: dict[str, dict] = {}
    directory = root / "spec/overlay/rest"
    for path in sorted(directory.rglob("*.yaml")):
        if path.is_relative_to(root / CONTRACTS):
            continue
        overlay = yaml.safe_load(path.read_text(encoding="utf-8"))
        operations |= {f"{entry['method'].upper()} {entry['path']}" for entry in overlay["operations"]}
        components.update(overlay.get("components", {}))
    return operations, components


def plan(tag: dict, tag_hashes: dict, branch: dict, branch_hashes: dict, commit: str,
         modules: dict[str, str], manual_ops: set[str], manual_components: dict[str, dict]
         ) -> tuple[dict[str, dict], dict[str, list[str]]]:
    """Overlay documents by file stem, and a report of what was not imported."""
    tag_schemas = tag.get("components", {}).get("schemas", {})
    branch_schemas = {name: _strip_titles(schema)
                      for name, schema in branch.get("components", {}).get("schemas", {}).items()}
    report: dict[str, list[str]] = {"changed": [], "branch_only": sorted(set(branch_hashes) - set(tag_hashes)),
                                    "missing": [], "undocumented": [], "manual": [], "overridden": []}
    files: dict[str, dict] = {}
    owners: dict[str, str] = {}
    for key in sorted(tag_hashes):
        method, path = key.split(" ", 1)
        if key in manual_ops:
            report["manual"].append(key)
            continue
        if key not in branch_hashes:
            report["missing"].append(key)
            continue
        if tag_hashes[key]["body_sha256"] != branch_hashes[key]["body_sha256"]:
            report["changed"].append(key)
            continue
        responses = _success(branch["paths"][path][method.lower()])
        documented = all(response.get("content") or status[0] == "3" or status == "204"
                         for status, response in responses.items())
        if not responses or not documented or any(
                untyped(body["schema"]) for response in responses.values()
                for body in response.get("content", {}).values()):
            report["undocumented"].append(key)
            continue
        stem = overlay_file(tag_hashes[key]["source"])
        entry = {
            "method": method,
            "path": path,
            "x-source": tag_hashes[key]["source"],
            "x-handler-hash": tag_hashes[key]["handler_sha256"],
            "x-contract": {"commit": commit, "model": _model(responses)},
            "responses": responses,
        }
        files.setdefault(stem, {"components": {}, "operations": []})["operations"].append(entry)
        for name in _closure(_refs(responses), branch_schemas):
            owners.setdefault(name, stem)
    for name in sorted(owners):
        schema = branch_schemas[name]
        if name in tag_schemas:
            if _strip_titles(tag_schemas[name]) != schema:
                raise ValueError(f"Contract component {name} differs from the tag's schema of that name")
            continue
        if name in manual_components:
            # A hand-written component corrects the contract for the tag; every import uses it.
            if manual_components[name] != schema:
                report["overridden"].append(name)
            continue
        stem = overlay_file(modules[name]) if name in modules else owners[name]
        files.setdefault(stem, {"components": {}, "operations": []})["components"][name] = schema
    for document in files.values():
        if not document["components"]:
            del document["components"]
        if not document["operations"]:
            document["operations"] = []
    return files, report


def _run(args: list[str], cwd: Path, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(args, cwd=cwd, env=env, text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(f"{' '.join(args[:2])} failed: {result.stderr.strip()[-2000:]}")
    return result.stdout.strip()


def render_branch(repo: Path) -> tuple[dict, dict, str, dict[str, str]]:
    """The contract checkout's OpenAPI, handler hashes, commit and model source files."""
    if _run(["git", "status", "--porcelain", "--untracked-files=no"], repo):
        raise ValueError("Contract checkout has modified tracked files")
    commit = _run(["git", "rev-parse", "HEAD"], repo)
    python = repo / ".venv" / "bin" / "python"
    with tempfile.TemporaryDirectory(prefix="hermes-contracts-") as scratch:
        home, output = Path(scratch) / "home", Path(scratch) / "out"
        home.mkdir()
        env = {**os.environ, "HERMES_HOME": str(home), "HERMES_TEST_ISOLATION": "1"}
        _run([str(python), str(ROOT / "tools/_render_openapi.py"), "--source-repo", str(repo),
              "--output-dir", str(output)], repo, env)
        modules = json.loads(_run([str(python), str(ROOT / "tools/_contract_modules.py"),
                                   "--source-repo", str(repo)], repo, env))
        document = json.loads((output / "openapi.raw.json").read_text(encoding="utf-8"))
        hashes = json.loads((output / "rest-hashes.json").read_text(encoding="utf-8"))
    return document, hashes, commit, modules


def write(files: dict[str, dict], root: Path = ROOT) -> None:
    directory = root / CONTRACTS
    directory.mkdir(parents=True, exist_ok=True)
    for stale in set(directory.glob("*.yaml")) - {directory / f"{stem}.yaml" for stem in files}:
        stale.unlink()
    for stem, document in sorted(files.items()):
        header = ("# Drafted by tools/import_contracts.py from the response contracts named in x-contract;\n"
                  "# re-run the importer rather than editing, and review changes like any overlay.\n")
        body = yaml.safe_dump(document, sort_keys=False, allow_unicode=True, width=110)
        (directory / f"{stem}.yaml").write_text(header + body, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--contracts-repo", type=Path, required=True,
                        help="Hermes checkout whose routes document their responses")
    parser.add_argument("--report", type=Path, help="write the not-imported operations here as JSON")
    args = parser.parse_args()
    ref = require_release(args.ref)
    output = ROOT / "spec/out" / ref
    tag = json.loads((output / "openapi.raw.json").read_text(encoding="utf-8"))
    tag_hashes = json.loads((output / "rest-hashes.json").read_text(encoding="utf-8"))
    branch, branch_hashes, commit, modules = render_branch(args.contracts_repo.resolve())
    manual_ops, manual_components = _manual_overlays(ROOT)
    files, report = plan(tag, tag_hashes, branch, branch_hashes, commit, modules,
                         manual_ops, manual_components)
    write(files)
    imported = sum(len(document["operations"]) for document in files.values())
    summary = {"commit": commit, "imported": imported, **{key: len(value) for key, value in report.items()}}
    if args.report:
        args.report.write_text(json.dumps({**summary, **report}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, sort_keys=True))


if __name__ == "__main__":
    main()
