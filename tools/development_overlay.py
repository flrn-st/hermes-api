"""Reviewed contract additions from a Hermes development branch, merged on top of a pinned release.

``spec/overlay/development/*.yaml`` holds additions a development branch makes to the gateway's OpenRPC
document and to the dashboard REST rows, applied to the releases each file names (``releases:``). They
only add: a method, a schema or a property the pinned contract already has is an error, so the overlay
has to go once a release ships the addition. ``source`` names the branch commit the additions were copied
from (the branch is not fetched; the overlay is the reviewed record).

Every tool that generates, audits or counts the gateway reads the pinned contract through
:func:`gateway_contract`, so they all see the same merged document; ``spec/out/<ref>/openrpc.json`` stays
the upstream tag's byte-exact artifact. ``tools/apply_overlay.py`` applies the ``rest`` section with
:func:`apply_rest` when it writes ``spec/out/<ref>/openapi.json``.

File shape::

    source: {repository, branch, commit, subject}
    releases: [v0.22.1]
    gateway:
      methods:      # inserted after the method ``after`` names
        - after: billing.charge_status
          method: {name: ..., params: [...], result: {...}}
          errors: []    # its gateway-errors.json entry, read from the branch's handler
      schemas: {Name: schema}
      properties:   # optional fields only, placed after ``after`` (the branch's order) or last
        ExistingSchema: {field: {after: previous_field, schema: {...}}}
    rest:
      x-source: hermes_cli/inventory.py     # where the branch builds the rows
      gateway-schemas: [PlanUsage]          # copied from the merged gateway contract
      components: {Name: schema}
      properties: {ExistingComponent: {field: {schema: {...}}}}
"""

from __future__ import annotations

import copy
import json
from pathlib import Path

import yaml

from tools.fetch_spec import ROOT

OVERLAYS = Path("spec/overlay/development")
SOURCE = {"repository", "branch", "commit", "subject"}
TOP = {"source", "releases", "gateway", "rest"}
GATEWAY = {"methods", "schemas", "properties"}
REST = {"x-source", "gateway-schemas", "components", "properties"}
REF_PREFIX = "#/components/schemas/"


def load(ref: str, root: Path = ROOT) -> list[tuple[Path, dict]]:
    """The development overlays that apply to ``ref``, in file order."""
    result = []
    for path in sorted((root / OVERLAYS).glob("*.yaml")):
        overlay = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(overlay, dict) or set(overlay) - TOP or not {"source", "releases"} <= set(overlay):
            raise ValueError(f"Development overlay needs source, releases and gateway/rest sections only: {path}")
        source = overlay["source"]
        if not isinstance(source, dict) or set(source) != SOURCE or not all(
                isinstance(value, str) and value for value in source.values()):
            raise ValueError(f"Development overlay source needs exactly {sorted(SOURCE)}: {path}")
        releases = overlay["releases"]
        if not isinstance(releases, list) or not releases or not all(isinstance(item, str) for item in releases):
            raise ValueError(f"Development overlay releases must list release versions: {path}")
        if ref in releases:
            result.append((path, overlay))
    return result


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


def _add_properties(schemas: dict, additions: object, where: str) -> None:
    if not isinstance(additions, dict):
        raise TypeError(f"{where}: properties must map schema names to fields")
    for name, fields in additions.items():
        schema = schemas.get(name)
        if not isinstance(schema, dict) or schema.get("type") != "object":
            raise ValueError(f"{where}: {name} is not an object schema of the contract")
        if not isinstance(fields, dict) or not fields:
            raise ValueError(f"{where}: {name} adds no fields")
        properties = schema.setdefault("properties", {})
        for field, entry in fields.items():
            if field in properties:
                raise ValueError(f"{where}: {name}.{field} is already in the contract; drop it from the overlay")
            if (not isinstance(entry, dict) or "schema" not in entry or set(entry) - {"schema", "after"}
                    or not isinstance(entry["schema"], dict)):
                raise TypeError(f"{where}: {name}.{field} needs a schema and optionally after")
            # Added fields stay optional: a server without the branch never sends them.
            items = list(properties.items())
            after = entry.get("after")
            if after is None:
                position = len(items)
            elif after in properties:
                position = [key for key, _ in items].index(after) + 1
            else:
                raise ValueError(f"{where}: {name}.{field} follows {after!r}, which {name} lacks")
            items.insert(position, (field, copy.deepcopy(entry["schema"])))
            properties.clear()
            properties.update(items)


def _add_schemas(schemas: dict, additions: object, where: str) -> None:
    if not isinstance(additions, dict):
        raise TypeError(f"{where}: schemas must map names to schemas")
    for name, schema in additions.items():
        if name in schemas:
            raise ValueError(f"{where}: schema {name} is already in the contract; drop it from the overlay")
        if not isinstance(schema, dict):
            raise TypeError(f"{where}: schema {name} is not a schema")
        schemas[name] = copy.deepcopy(schema)


def _check_refs(document: object, schemas: dict, where: str) -> None:
    missing = sorted(_refs(document) - set(schemas))
    if missing:
        raise ValueError(f"{where}: unresolved schema references {missing}")


def apply_gateway(contract: dict, overlay: dict, where: str = "development overlay") -> dict:
    """``contract`` with the overlay's gateway additions (a copy; ``contract`` is unchanged)."""
    section = overlay.get("gateway")
    if section is None:
        return contract
    if not isinstance(section, dict) or set(section) - GATEWAY:
        raise ValueError(f"{where}: gateway section takes only {sorted(GATEWAY)}")
    contract = copy.deepcopy(contract)
    schemas = contract.setdefault("components", {}).setdefault("schemas", {})
    _add_schemas(schemas, section.get("schemas", {}), where)
    _add_properties(schemas, section.get("properties", {}), where)
    methods = contract["methods"]
    for entry in section.get("methods", []):
        if (not isinstance(entry, dict) or set(entry) != {"after", "method", "errors"}
                or not isinstance(entry["method"], dict) or not isinstance(entry["errors"], list)):
            raise ValueError(f"{where}: a method entry needs exactly after, method and errors")
        method = copy.deepcopy(entry["method"])
        names = [item["name"] for item in methods]
        name = method.get("name")
        if not isinstance(name, str) or name in names:
            raise ValueError(f"{where}: method {name!r} is already in the contract; drop it from the overlay")
        if entry["after"] not in names:
            raise ValueError(f"{where}: {name} follows {entry['after']!r}, which the contract lacks")
        methods.insert(names.index(entry["after"]) + 1, method)
    _check_refs(contract, schemas, where)
    return contract


def apply_rest(document: dict, overlay: dict, gateway: dict, where: str = "development overlay") -> dict:
    """``document`` (an OpenAPI document) with the overlay's REST additions, in place. ``gateway`` is the
    merged gateway contract the ``gateway-schemas`` entries are copied from."""
    section = overlay.get("rest")
    if section is None:
        return document
    if not isinstance(section, dict) or set(section) - REST or "x-source" not in section:
        raise ValueError(f"{where}: rest section needs x-source and takes only {sorted(REST)}")
    schemas = document.setdefault("components", {}).setdefault("schemas", {})
    gateway_schemas = gateway["components"]["schemas"]
    copied = {}
    for name in section.get("gateway-schemas", []):
        if name not in gateway_schemas:
            raise ValueError(f"{where}: gateway schema {name} is not in the merged gateway contract")
        copied[name] = gateway_schemas[name]
    _add_schemas(schemas, copied, where)
    _add_schemas(schemas, section.get("components", {}), where)
    _add_properties(schemas, section.get("properties", {}), where)
    _check_refs(document, schemas, where)
    return document


def gateway_contract(ref: str, root: Path = ROOT) -> dict:
    """The pinned OpenRPC contract of ``ref`` with every development overlay for it merged in."""
    contract = json.loads((root / "spec" / "out" / ref / "openrpc.json").read_text(encoding="utf-8"))
    for path, overlay in load(ref, root):
        contract = apply_gateway(contract, overlay, str(path.relative_to(root)))
    return contract


def gateway_errors(ref: str, root: Path = ROOT) -> dict[str, list]:
    """The extracted-error entries (``spec/out/<ref>/gateway-errors.json`` shape) of the methods the
    development overlays for ``ref`` add, by method name."""
    result: dict[str, list] = {}
    for _path, overlay in load(ref, root):
        for entry in (overlay.get("gateway") or {}).get("methods", []):
            result[entry["method"]["name"]] = copy.deepcopy(entry["errors"])
    return result


def apply_rest_overlays(document: dict, ref: str, root: Path = ROOT) -> dict:
    """``document`` with the REST additions of every development overlay for ``ref``."""
    overlays = load(ref, root)
    if overlays:
        gateway = gateway_contract(ref, root)
        for path, overlay in overlays:
            apply_rest(document, overlay, gateway, str(path.relative_to(root)))
    return document
