"""Only published Hermes release tags may be used as generation inputs."""

import re
from pathlib import Path

import yaml

_TAG = re.compile(r"v\d{4}\.\d{1,2}\.\d{1,2}\Z")


def require_release_tag(ref: str) -> str:
    if not isinstance(ref, str) or _TAG.fullmatch(ref) is None:
        raise ValueError(f"Expected a stable Hermes release tag, got {ref!r}")
    return ref


def load_releases(path: Path) -> tuple[str, ...]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("upstream") != "NousResearch/hermes-agent":
        raise ValueError("Invalid Hermes release configuration")
    raw = data.get("releases")
    if not isinstance(raw, list) or not raw:
        raise ValueError("At least one Hermes release is required")
    releases = tuple(require_release_tag(ref) for ref in raw)
    if len(set(releases)) != len(releases):
        raise ValueError("Duplicate Hermes release tag")
    return releases
