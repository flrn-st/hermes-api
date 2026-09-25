from pathlib import Path

import pytest

from tools.ref_policy import (
    current_release,
    load_releases,
    require_release,
    require_upstream_tag,
    upstream_tag,
    version_from_pyproject,
    version_key,
)


@pytest.mark.parametrize("ref", ["main", "dev", "v2026.9.21", "0.21.4", "v0.21", "v0.21.5-rc.8", "rc.8-v0.21.5"])
def test_rejects_anything_but_a_stable_version(ref: str) -> None:
    with pytest.raises(ValueError):
        require_release(ref)


@pytest.mark.parametrize("tag", ["v0.21.4", "rc.8-v0.21.5", "v0.21.4+canary.20260925T065930Z", "main"])
def test_rejects_anything_but_a_release_tag(tag: str) -> None:
    with pytest.raises(ValueError):
        require_upstream_tag(tag)


def test_accepts_versions_and_date_tags() -> None:
    assert require_release("v0.21.4") == "v0.21.4"
    assert require_upstream_tag("v2026.9.21") == "v2026.9.21"
    assert require_upstream_tag("v2026.8.16.2") == "v2026.8.16.2"
    assert version_key("v0.21.10") > version_key("v0.21.9")


def test_pre_release_pyproject_versions_are_not_releases() -> None:
    assert version_from_pyproject("0.21.5") == "v0.21.5"
    assert version_from_pyproject("0.21.5rc8") is None
    assert version_from_pyproject("0.22.0.dev1") is None


def test_current_release_is_tracked_with_its_tag() -> None:
    releases = load_releases(Path("spec/refs.yaml"))
    current = current_release()
    assert list(releases)[-1] == current
    assert upstream_tag(current) == releases[current]
