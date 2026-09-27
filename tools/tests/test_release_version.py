from pathlib import Path

import pytest

from tools.release_version import bump_minor, version


def _root(tmp_path: Path, gradle: str) -> Path:
    (tmp_path / "kotlin").mkdir()
    (tmp_path / "kotlin/build.gradle.kts").write_text(gradle)
    return tmp_path


def test_package_version_is_the_gradle_version(tmp_path: Path) -> None:
    assert version(_root(tmp_path, 'group = "hermes"\nversion = "1.4.2"\n')) == "1.4.2"


def test_committed_version_is_semver() -> None:
    assert len(version().split(".")) == 3


@pytest.mark.parametrize("gradle", ['version = "1.4"\n', 'version = "1.0.0"\nversion = "1.0.1"\n', ""])
def test_version_must_be_single_semver(tmp_path: Path, gradle: str) -> None:
    with pytest.raises(ValueError, match="Expected one"):
        version(_root(tmp_path, gradle))


def test_bump_minor_resets_the_patch(tmp_path: Path) -> None:
    root = _root(tmp_path, 'plugins {}\nversion = "1.4.2"\n')
    assert bump_minor(root) == "1.5.0"
    assert (root / "kotlin/build.gradle.kts").read_text() == 'plugins {}\nversion = "1.5.0"\n'
