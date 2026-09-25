"""Only published, stable Hermes releases may be used as generation inputs.

A release is identified by its Hermes version, such as ``v0.21.4``. Hermes tags its releases by date
(``v2026.9.21``); ``spec/refs.yaml`` maps every tracked version to its tag, and fetching verifies that the
tagged ``pyproject.toml`` states exactly that version. Release candidates and canaries are never tracked.
"""

from __future__ import annotations

import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
# A major of at most three digits keeps date tags such as v2026.9.21 from passing as versions.
_VERSION = re.compile(r"v(\d{1,3})\.(\d+)\.(\d+)\Z")
_TAG = re.compile(r"v(\d{4})\.(\d{1,2})\.(\d{1,2})(?:\.(\d+))?\Z")


def require_release(ref: str) -> str:
    """A stable Hermes version, ``vMAJOR.MINOR.PATCH``."""
    if not isinstance(ref, str) or _VERSION.fullmatch(ref) is None:
        raise ValueError(f"Expected a stable Hermes version such as v0.21.4, got {ref!r}")
    return ref


def version_key(ref: str) -> tuple[int, int, int]:
    match = _VERSION.fullmatch(require_release(ref))
    assert match is not None
    return (int(match[1]), int(match[2]), int(match[3]))


def require_upstream_tag(tag: str) -> str:
    """A Hermes release tag, ``vYYYY.M.D`` with an optional patch number."""
    if not isinstance(tag, str) or _TAG.fullmatch(tag) is None:
        raise ValueError(f"Expected a Hermes release tag such as v2026.9.21, got {tag!r}")
    return tag


def tag_key(tag: str) -> tuple[int, int, int, int]:
    match = _TAG.fullmatch(require_upstream_tag(tag))
    assert match is not None
    return (int(match[1]), int(match[2]), int(match[3]), int(match[4] or 0))


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
