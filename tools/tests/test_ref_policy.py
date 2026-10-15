import json
from pathlib import Path

import pytest

from tools.ref_policy import (
    current_release,
    load_releases,
    release_record_version,
    require_release,
    require_upstream_tag,
    tag_key,
    upstream_tag,
    version_from_pyproject,
    version_key,
)


@pytest.mark.parametrize("ref", ["main", "dev", "v2026.9.21", "0.21.4", "v0.21", "v0.21.5-rc.8", "rc.8-v0.21.5"])
def test_rejects_anything_but_a_stable_version(ref: str) -> None:
    with pytest.raises(ValueError):
        require_release(ref)


@pytest.mark.parametrize("tag", [
    "rc.8-v0.21.5", "v0.21.4+canary.20260925T065930Z", "abandoned-rc.1-v0.22.2", "v0.22", "v0.22.2-rc.1", "main",
])
def test_rejects_anything_but_a_release_tag(tag: str) -> None:
    with pytest.raises(ValueError):
        require_upstream_tag(tag)


def test_accepts_versions_and_date_tags() -> None:
    assert require_release("v0.21.4") == "v0.21.4"
    assert require_upstream_tag("v2026.9.21") == "v2026.9.21"
    assert require_upstream_tag("v2026.8.16.2") == "v2026.8.16.2"
    assert require_upstream_tag("v0.22.2") == "v0.22.2"
    assert version_key("v0.21.10") > version_key("v0.21.9")


def test_version_tags_follow_every_date_tag() -> None:
    assert tag_key("v0.22.1") > tag_key("v2026.9.24")
    assert tag_key("v0.22.10") > tag_key("v0.22.2")
    assert tag_key("v2026.9.24") > tag_key("v2026.8.16.2")


COMMIT = "d923a5eb487a17c0a68801bba9ef0f56c81040a8"
RECORD = json.dumps({"schema": 1, "version": "0.22.2", "commit": COMMIT, "releaseId": 413474259})


def test_a_version_tag_is_verified_by_its_release_record() -> None:
    assert release_record_version("v0.22.2", "tag", RECORD + "\n", COMMIT) == "v0.22.2"
    signed = RECORD + "\n-----BEGIN PGP SIGNATURE-----\nabc\n-----END PGP SIGNATURE-----\n"
    assert release_record_version("v0.22.2", "tag", signed, COMMIT) == "v0.22.2"


@pytest.mark.parametrize(("tag_type", "message", "commit", "match"), [
    ("commit", "", COMMIT, "not annotated"),
    ("tag", "Hermes Agent v0.22.2", COMMIT, "no release record"),
    ("tag", json.dumps({"schema": 2, "version": "0.22.2", "commit": COMMIT}), COMMIT, "schema 1"),
    ("tag", RECORD, "0" * 40, "release record names"),
    ("tag", json.dumps({"schema": 1, "version": "0.22.1", "commit": COMMIT}), COMMIT, "records version"),
    ("tag", json.dumps({"schema": 1, "version": "0.22.2rc1", "commit": COMMIT}), COMMIT, "records version"),
])
def test_a_version_tag_without_a_matching_record_is_refused(
    tag_type: str, message: str, commit: str, match: str,
) -> None:
    with pytest.raises(ValueError, match=match):
        release_record_version("v0.22.2", tag_type, message, commit)


def test_a_version_tag_must_name_its_own_version(tmp_path: Path) -> None:
    config = tmp_path / "refs.yaml"
    config.write_text("upstream: NousResearch/hermes-agent\nreleases:\n  v0.21.5: v2026.9.24\n  v0.22.2: v0.22.1\n")
    with pytest.raises(ValueError, match="names another version"):
        load_releases(config)
    config.write_text("upstream: NousResearch/hermes-agent\nreleases:\n  v0.21.5: v2026.9.24\n  v0.22.2: v0.22.2\n")
    assert load_releases(config) == {"v0.21.5": "v2026.9.24", "v0.22.2": "v0.22.2"}


def test_pre_release_pyproject_versions_are_not_releases() -> None:
    assert version_from_pyproject("0.21.5") == "v0.21.5"
    assert version_from_pyproject("0.21.5rc8") is None
    assert version_from_pyproject("0.22.0.dev1") is None


def test_current_release_is_tracked_with_its_tag() -> None:
    releases = load_releases(Path("spec/refs.yaml"))
    current = current_release()
    assert list(releases)[-1] == current
    assert upstream_tag(current) == releases[current]
