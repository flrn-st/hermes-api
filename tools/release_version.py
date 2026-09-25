"""Verify the SwiftPM tag and Kotlin Maven version for the current stable Hermes release."""

from __future__ import annotations

import re
from pathlib import Path

from tools.fetch_spec import ROOT
from tools.ref_policy import current_release


def version(root: Path = ROOT) -> str:
    """The package version is the Hermes version it was generated from: HermesAPI 0.21.4 speaks Hermes 0.21.4."""
    value = current_release(root)[1:]
    gradle = (root / "kotlin/build.gradle.kts").read_text(encoding="utf-8")
    if not re.search(rf'^version = "{re.escape(value)}"$', gradle, re.MULTILINE):
        raise ValueError("Kotlin Maven version does not match current Hermes release")
    return value


def main() -> None:
    print(version())


if __name__ == "__main__":
    main()
