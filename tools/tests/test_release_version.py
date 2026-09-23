import re
from pathlib import Path

import pytest

from tools.release_version import version


def test_current_release_and_maven_version_match() -> None:
    assert re.fullmatch(r"\d{4}\.\d{3,4}\.\d+", version())


def test_version_mismatch_fails(tmp_path: Path) -> None:
    (tmp_path / "spec").mkdir()
    (tmp_path / "kotlin").mkdir()
    (tmp_path / "spec/current-release.txt").write_text("v2026.9.22\n")
    (tmp_path / "kotlin/build.gradle.kts").write_text('version = "2026.921.0"\n')
    with pytest.raises(ValueError, match="does not match"):
        version(tmp_path)
