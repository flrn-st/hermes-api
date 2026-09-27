"""The HermesAPI package version: the SwiftPM tag and the Kotlin and Android Maven version.

HermesAPI has its own semantic version, so a fix to the library ships without waiting for a Hermes
release. The Hermes release it speaks is ``spec/current-release.txt``, exposed at run time as
``HermesGatewayContract.release``. Adopting a new Hermes release bumps the minor version; a release
that removes or changes generated symbols needs a major bump in review.
"""

from __future__ import annotations

import re
from pathlib import Path

from tools.fetch_spec import ROOT

_GRADLE_VERSION = re.compile(r'^version = "(\d+)\.(\d+)\.(\d+)"$', re.MULTILINE)


def version(root: Path = ROOT) -> str:
    """The one version in ``kotlin/build.gradle.kts``; the Android build reads the same line."""
    gradle = (root / "kotlin/build.gradle.kts").read_text(encoding="utf-8")
    found = _GRADLE_VERSION.findall(gradle)
    if len(found) != 1:
        raise ValueError("Expected one MAJOR.MINOR.PATCH version in kotlin/build.gradle.kts")
    return ".".join(found[0])


def bump_minor(root: Path = ROOT) -> str:
    """Raise the minor version and reset the patch, for a newly adopted Hermes release."""
    major, minor, _ = (int(part) for part in version(root).split("."))
    new = f"{major}.{minor + 1}.0"
    gradle = root / "kotlin/build.gradle.kts"
    gradle.write_text(_GRADLE_VERSION.sub(f'version = "{new}"', gradle.read_text(encoding="utf-8")),
                      encoding="utf-8")
    return new


def main() -> None:
    print(version())


if __name__ == "__main__":
    main()
