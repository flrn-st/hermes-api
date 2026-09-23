from pathlib import Path

import pytest

from tools.ref_policy import load_releases, require_release_tag


@pytest.mark.parametrize("ref", ["main", "dev", "v2026.9.21-rc1", "2026.9.21", "abc"])
def test_rejects_non_release_refs(ref: str) -> None:
    with pytest.raises(ValueError):
        require_release_tag(ref)


def test_loads_only_targeted_release() -> None:
    releases = load_releases(Path("spec/refs.yaml"))
    assert releases == ("v2026.9.21",)
