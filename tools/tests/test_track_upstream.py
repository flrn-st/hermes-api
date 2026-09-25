from pathlib import Path

import pytest

from tools.track_upstream import adopt, newest_untracked, stable_tags

TAGS = """aaa\trefs/tags/v2026.9.21
bbb\trefs/tags/rc.8-v0.21.5
ccc\trefs/tags/v2026.9.22
ccc^{}\trefs/tags/v2026.9.22^{}
ddd\trefs/tags/v2026.9.24
eee\trefs/tags/v0.21.4+canary.20260925T065930Z
fff\trefs/tags/v2026.9.14
"""

VERSIONS = {"v2026.9.14": "v0.21.3", "v2026.9.21": "v0.21.4", "v2026.9.22": None, "v2026.9.24": "v0.21.5"}


def release_root(tmp_path: Path) -> Path:
    (tmp_path / "spec").mkdir()
    (tmp_path / "kotlin").mkdir()
    (tmp_path / "spec/current-release.txt").write_text("v0.21.4\n")
    (tmp_path / "spec/refs.yaml").write_text(
        "upstream: NousResearch/hermes-agent\nminimum_contract_version: v0.21.3\n"
        "releases:\n  v0.21.3: v2026.9.14\n  v0.21.4: v2026.9.21\n"
    )
    (tmp_path / "kotlin/build.gradle.kts").write_text('version = "0.21.4"\n')
    return tmp_path


def test_detects_the_newest_released_version_and_skips_pre_releases(tmp_path: Path) -> None:
    root = release_root(tmp_path)
    assert stable_tags(TAGS) == ["v2026.9.14", "v2026.9.21", "v2026.9.22", "v2026.9.24"]
    assert newest_untracked(TAGS, root, VERSIONS.get) == ("v0.21.5", "v2026.9.24")
    # A newer tag that is only a pre-release in its pyproject.toml is never adopted.
    assert newest_untracked(TAGS, root, {**VERSIONS, "v2026.9.24": None}.get) is None


def test_adopts_a_version_with_its_tag(tmp_path: Path) -> None:
    root = release_root(tmp_path)
    assert adopt("v0.21.5", "v2026.9.24", root) == "0.21.5"
    assert (root / "spec/current-release.txt").read_text() == "v0.21.5\n"
    assert "v0.21.5: v2026.9.24" in (root / "spec/refs.yaml").read_text()
    assert (root / "kotlin/build.gradle.kts").read_text() == 'version = "0.21.5"\n'
    with pytest.raises(ValueError, match="does not advance"):
        adopt("v0.21.4", "v2026.9.21", root)
