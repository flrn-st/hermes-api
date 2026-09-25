from pathlib import Path

import pytest

from tools.ref_policy import current_release
from tools.release_version import version


def test_package_version_is_the_hermes_version() -> None:
    assert version() == current_release()[1:]


def test_version_mismatch_fails(tmp_path: Path) -> None:
    (tmp_path / "spec").mkdir()
    (tmp_path / "kotlin").mkdir()
    (tmp_path / "spec/current-release.txt").write_text("v0.21.5\n")
    (tmp_path / "kotlin/build.gradle.kts").write_text('version = "0.21.4"\n')
    with pytest.raises(ValueError, match="does not match"):
        version(tmp_path)
