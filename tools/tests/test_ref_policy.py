from pathlib import Path

import pytest

from tools.ref_policy import load_releases, require_release_tag


@pytest.mark.parametrize("ref", ["main", "dev", "v2026.9.21-rc1", "2026.9.21", "abc"])
def test_rejects_non_release_refs(ref: str) -> None:
    with pytest.raises(ValueError):
        require_release_tag(ref)


def test_loads_only_targeted_release() -> None:
    releases = load_releases(Path("spec/refs.yaml"))
    assert releases[0] == "v2026.9.21"
    assert releases[-1] == Path("spec/current-release.txt").read_text().strip()


def test_accepts_stable_patch_release() -> None:
    assert require_release_tag("v2026.8.16.2") == "v2026.8.16.2"
