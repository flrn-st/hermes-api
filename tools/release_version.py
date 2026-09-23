"""Verify the SwiftPM tag and Kotlin Maven version for the current stable Hermes release."""

from __future__ import annotations

import re
from pathlib import Path

from tools.fetch_spec import ROOT
from tools.ref_policy import require_release_tag


def version(root: Path = ROOT) -> str:
    ref = require_release_tag((root / "spec/current-release.txt").read_text(encoding="utf-8").strip())
    parts = [int(part) for part in ref[1:].split(".")]
    year, month, day = parts[:3]
    patch = parts[3] if len(parts) == 4 else 0
    value = f"{year}.{month * 100 + day}.{patch}"
    gradle = (root / "kotlin/build.gradle.kts").read_text(encoding="utf-8")
    if not re.search(rf'^version = "{re.escape(value)}"$', gradle, re.MULTILINE):
        raise ValueError("Kotlin Maven version does not match current Hermes release")
    return value


def main() -> None:
    print(version())


if __name__ == "__main__":
    main()
