"""Run inside the tagged Hermes Python environment. Invoked by extract_openapi.py."""

from __future__ import annotations

import argparse
import hashlib
import inspect
import json
from pathlib import Path

from fastapi.routing import APIRoute
from hermes_cli.web_server import _get_dashboard_plugins, app


def render(source_repo: Path, output_dir: Path) -> dict[str, object]:
    source_repo = source_repo.resolve()
    openapi = app.openapi()
    paths = openapi.get("paths")
    if not isinstance(paths, dict) or not paths:
        raise ValueError("Hermes FastAPI returned no OpenAPI paths")

    mounted = {
        path.split("/")[3]
        for path in paths
        if path.startswith("/api/plugins/") and len(path.split("/")) > 3
    }
    bundled = {
        plugin["name"]
        for plugin in _get_dashboard_plugins()
        if plugin.get("source") == "bundled" and plugin.get("_api_file")
    }
    missing = bundled - mounted
    if missing:
        raise ValueError(f"Bundled plugin API routes failed to mount: {sorted(missing)}")
    unexpected = mounted - bundled
    if unexpected:
        raise ValueError(f"Untrusted plugin API routes mounted in isolated extraction: {sorted(unexpected)}")

    routes: dict[tuple[str, str], APIRoute] = {}
    for route in app.routes:
        if not isinstance(route, APIRoute) or not route.include_in_schema:
            continue
        for method in route.methods or ():
            routes[(route.path_format, method.lower())] = route

    hashes: dict[str, dict[str, str | int]] = {}
    for path, operations in paths.items():
        for method in operations:
            if method.lower() not in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}:
                continue
            route = routes.get((path, method.lower()))
            if route is None:
                raise ValueError(f"OpenAPI operation has no FastAPI route: {method.upper()} {path}")
            endpoint = route.endpoint
            source_file = inspect.getsourcefile(endpoint)
            if source_file is None:
                raise ValueError(f"Cannot locate handler source for {method.upper()} {path}")
            file = Path(source_file).resolve()
            try:
                relative = file.relative_to(source_repo)
            except ValueError as exc:
                raise ValueError(f"Handler outside tagged checkout: {file}") from exc
            lines, line = inspect.getsourcelines(endpoint)
            key = f"{method.upper()} {path}"
            hashes[key] = {
                "source": f"{relative.as_posix()}:{line}",
                "handler_sha256": hashlib.sha256("".join(lines).encode()).hexdigest(),
            }

    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "openapi.raw.json").write_text(
        json.dumps(openapi, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (output_dir / "rest-hashes.json").write_text(
        json.dumps(hashes, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return {
        "path_count": len(paths),
        "operation_count": len(hashes),
        "bundled_plugin_apis": sorted(bundled),
        "mounted_plugin_apis": sorted(mounted),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-repo", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(render(args.source_repo, args.output_dir), sort_keys=True))


if __name__ == "__main__":
    main()
