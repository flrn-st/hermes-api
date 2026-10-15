"""Only published, stable Hermes releases may be used as generation inputs.

A release is identified by its Hermes version, such as ``v0.21.4``. ``spec/refs.yaml`` maps every tracked
version to the upstream tag it was released as. Hermes has used two tag schemes:

- Date tags (``v2026.9.21``), up to v0.21.5. The tagged ``pyproject.toml`` states the version, and fetching
  checks it.
- Version tags (``v0.22.2``), from v0.22.1 on. The release automation now keeps ``pyproject.toml`` at the
  inert placeholder ``0.0.0`` (its ``scripts/releases/stamping.py`` says package versions are "inert source
  placeholders"), so the tree no longer names its version. What does is the tag itself: the automation
  (``scripts/releases/stable.py`` ``ensure_final_tag``) creates every stable tag as an annotated tag whose
  message is a JSON record ``{"schema": 1, "version": "0.22.2", "commit": "<sha>", ...}``, the record its own
  publication step re-verifies against the remote. A version tag must name its own version
  (``v0.22.2: v0.22.2``), and fetching requires an annotated tag whose record states that version and the
  commit the tag points at (:func:`release_record_version`). Lightweight tags, tags without a record, and
  records that disagree with the tag are refused.

Release candidates (``rc.2-v0.22.2``) and canaries (``v0.22.1+canary.…``) match neither scheme and are never
tracked.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
# A major of at most three digits keeps date tags such as v2026.9.21 from passing as versions.
_VERSION = re.compile(r"v(\d{1,3})\.(\d+)\.(\d+)\Z")
_TAG = re.compile(r"v(\d{4})\.(\d{1,2})\.(\d{1,2})(?:\.(\d+))?\Z")
# The schema of the JSON record an annotated stable release tag carries.
_RECORD_SCHEMA = 1


def require_release(ref: str) -> str:
    """A stable Hermes version, ``vMAJOR.MINOR.PATCH``."""
    if not isinstance(ref, str) or _VERSION.fullmatch(ref) is None:
        raise ValueError(f"Expected a stable Hermes version such as v0.21.4, got {ref!r}")
    return ref


def version_key(ref: str) -> tuple[int, int, int]:
    match = _VERSION.fullmatch(require_release(ref))
    assert match is not None
    return (int(match[1]), int(match[2]), int(match[3]))


def is_version_tag(tag: str) -> bool:
    """Whether ``tag`` uses the version scheme (``v0.22.2``) rather than the date scheme."""
    return isinstance(tag, str) and _VERSION.fullmatch(tag) is not None


def require_upstream_tag(tag: str) -> str:
    """A Hermes release tag: a version tag ``vX.Y.Z``, or a date tag ``vYYYY.M.D`` with an optional patch."""
    if not isinstance(tag, str) or (_TAG.fullmatch(tag) is None and _VERSION.fullmatch(tag) is None):
        raise ValueError(f"Expected a Hermes release tag such as v0.22.2 or v2026.9.21, got {tag!r}")
    return tag


def tag_key(tag: str) -> tuple[int, int, int, int, int]:
    """Release order of tags. Every version tag is newer than every date tag: the scheme changed once."""
    require_upstream_tag(tag)
    if is_version_tag(tag):
        major, minor, patch = version_key(tag)
        return (1, major, minor, patch, 0)
    match = _TAG.fullmatch(tag)
    assert match is not None
    return (0, int(match[1]), int(match[2]), int(match[3]), int(match[4] or 0))


def release_record_version(tag: str, tag_type: str, message: str, commit: str) -> str:
    """The version an annotated version tag was released as, verified against its release record.

    ``tag_type`` is ``git cat-file -t`` of the tag ref, ``message`` the tag's message
    (``git tag -l <tag> --format=%(contents)``) and ``commit`` the commit the tag points at. The record
    is the message up to an armored signature, as upstream's ``versioning.tag_record`` reads it.
    """
    if not is_version_tag(tag):
        raise ValueError(f"{tag!r} is not a version tag")
    if tag_type != "tag":
        raise ValueError(f"Tag {tag} is not annotated, so it carries no release record")
    try:
        record = json.loads(message.split("\n-----BEGIN ", 1)[0])
    except json.JSONDecodeError as error:
        raise ValueError(f"Tag {tag} carries no release record") from error
    if not isinstance(record, dict) or record.get("schema") != _RECORD_SCHEMA:
        raise ValueError(f"Tag {tag} carries no schema {_RECORD_SCHEMA} release record")
    if record.get("commit") != commit:
        raise ValueError(f"Tag {tag} points at {commit}, but its release record names {record.get('commit')!r}")
    version = record.get("version")
    if not isinstance(version, str) or f"v{version}" != tag:
        raise ValueError(f"Tag {tag} records version {version!r}")
    return require_release(f"v{version}")


def version_from_pyproject(version: str) -> str | None:
    """The release version for a tagged ``pyproject.toml`` version, or None for pre-releases."""
    ref = f"v{version}"
    return ref if _VERSION.fullmatch(ref) else None


def load_config(path: Path) -> dict:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("upstream") != "NousResearch/hermes-agent":
        raise ValueError("Invalid Hermes release configuration")
    return data


def load_releases(path: Path) -> dict[str, str]:
    """Tracked versions, oldest first, mapped to their upstream tags."""
    raw = load_config(path).get("releases")
    if not isinstance(raw, dict) or not raw:
        raise ValueError("At least one Hermes release is required")
    releases = {require_release(version): require_upstream_tag(tag) for version, tag in raw.items()}
    for version, tag in releases.items():
        if is_version_tag(tag) and tag != version:
            raise ValueError(f"Hermes {version} maps to the version tag {tag}, which names another version")
    if len(set(releases.values())) != len(releases):
        raise ValueError("Two Hermes versions map to the same tag")
    return dict(sorted(releases.items(), key=lambda item: version_key(item[0])))


def upstream_tag(ref: str, root: Path = ROOT) -> str:
    releases = load_releases(root / "spec/refs.yaml")
    if require_release(ref) not in releases:
        raise ValueError(f"Hermes {ref} is not tracked in spec/refs.yaml")
    return releases[ref]


def current_release(root: Path = ROOT) -> str:
    return require_release((root / "spec/current-release.txt").read_text(encoding="utf-8").strip())
