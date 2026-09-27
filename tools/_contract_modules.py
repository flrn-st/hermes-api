"""Run inside a Hermes checkout that has response contracts. Invoked by import_contracts.py.

Prints the source file (relative to the checkout) that defines each response contract model, keyed
by the model's OpenAPI component name.
"""

from __future__ import annotations

import argparse
import inspect
import json
from pathlib import Path

from hermes_cli.web_responses.base import ResponseModel
from hermes_cli.web_server import app


def modules(source_repo: Path) -> dict[str, str]:
    app.openapi()  # imports every router and bundled plugin API, and with them their contracts
    source_repo = source_repo.resolve()
    found: dict[str, set[str]] = {}
    pending: list[type] = [ResponseModel]
    while pending:
        model = pending.pop()
        pending.extend(model.__subclasses__())
        source = inspect.getsourcefile(model)
        if source is None:
            continue
        relative = Path(source).resolve().relative_to(source_repo).as_posix()
        found.setdefault(model.__name__, set()).add(relative)
    # A name defined twice cannot be placed; the importer falls back to the referencing operation.
    return {name: next(iter(files)) for name, files in sorted(found.items()) if len(files) == 1}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-repo", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(modules(args.source_repo), sort_keys=True))


if __name__ == "__main__":
    main()
