from pathlib import Path

import pytest

from tools.track_upstream import adopt, newest_untracked, stable_tags, tag_key

TAGS = """aaa\trefs/tags/v2026.9.21
bbb\trefs/tags/v2026.9.23-rc1
ccc\trefs/tags/v2026.9.22
ccc^{}\trefs/tags/v2026.9.22^{}
ddd\trefs/tags/v2026.9.22.2
"""


def _root(tmp_path: Path) -> Path:
    (tmp_path / "spec").mkdir()
    (tmp_path / "kotlin").mkdir()
    (tmp_path / "spec/current-release.txt").write_text("v2026.9.21\n")
    (tmp_path / "spec/refs.yaml").write_text(
        "upstream: NousResearch/hermes-agent\nminimum_contract_tag: v2026.9.14\nreleases:\n  - v2026.9.21\n"
    )
    (tmp_path / "kotlin/build.gradle.kts").write_text('version = "2026.921.0"\n')
    return tmp_path


def test_detects_newest_stable_tag_and_patch(tmp_path: Path) -> None:
    root = _root(tmp_path)
    assert stable_tags(TAGS) == ["v2026.9.21", "v2026.9.22", "v2026.9.22.2"]
    assert newest_untracked(TAGS, root) == "v2026.9.22.2"
    assert tag_key("v2026.9.22.2") > tag_key("v2026.9.22")


def test_adoption_updates_current_and_package_version(tmp_path: Path) -> None:
    root = _root(tmp_path)
    assert adopt("v2026.9.22.2", root) == "2026.922.2"
    assert (root / "spec/current-release.txt").read_text() == "v2026.9.22.2\n"
    assert "v2026.9.22.2" in (root / "spec/refs.yaml").read_text()
    assert 'version = "2026.922.2"' in (root / "kotlin/build.gradle.kts").read_text()
    with pytest.raises(ValueError, match="does not advance"):
        adopt("v2026.9.21", root)
