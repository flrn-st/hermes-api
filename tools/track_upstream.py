"""Detect and adopt published Hermes releases by version; pre-releases and branches are ignored."""

from __future__ import annotations

import argparse
import re
import subprocess
import tomllib
from collections.abc import Callable
from pathlib import Path

from tools.fetch_spec import PYPROJECT_PATH, ROOT, UPSTREAM, _git, _prepare_remote
from tools.ref_policy import (
    current_release,
    load_config,
    load_releases,
    require_release,
    require_upstream_tag,
    tag_key,
    version_from_pyproject,
    version_key,
)


def stable_tags(remote_output: str) -> list[str]:
    """Hermes release tags (``vYYYY.M.D``) from ``git ls-remote --tags``, oldest first."""
    tags: set[str] = set()
    for line in remote_output.splitlines():
        if "\trefs/tags/" not in line:
            continue
        tag = line.rsplit("refs/tags/", 1)[-1]
        try:
            require_upstream_tag(tag)
        except ValueError:
            continue
        tags.add(tag)
    return sorted(tags, key=tag_key)


def tag_version(tag: str, cache: Path = ROOT / "spec" / ".upstream") -> str | None:
    """The Hermes version a tag was released as, from its pyproject.toml; None for a pre-release."""
    _prepare_remote(cache, tag)
    pyproject = tomllib.loads(_git(cache, "show", f"refs/tags/{tag}:{PYPROJECT_PATH}").decode())
    return version_from_pyproject(str(pyproject["project"]["version"]))


def newest_untracked(
    remote_output: str, root: Path = ROOT, version_of: Callable[[str], str | None] = tag_version,
) -> tuple[str, str] | None:
    """The newest released version after the current one, with its tag."""
    current = current_release(root)
    config = load_config(root / "spec/refs.yaml")
    minimum = require_release(config["minimum_contract_version"])
    current_tag = load_releases(root / "spec/refs.yaml")[current]
    for tag in reversed(stable_tags(remote_output)):
        if tag_key(tag) <= tag_key(current_tag):
            break
        version = version_of(tag)
        if version and version_key(version) > version_key(current) and version_key(version) >= version_key(minimum):
            return version, tag
    return None


def detect(root: Path = ROOT) -> tuple[str, str] | None:
    result = subprocess.run(
        ["git", "ls-remote", "--tags", "--refs", UPSTREAM],
        check=True, text=True, capture_output=True,
    )
    return newest_untracked(result.stdout, root)


def adopt(ref: str, tag: str, root: Path = ROOT) -> str:
    """Track Hermes ``ref`` (released as ``tag``) as the current release; returns the package version."""
    ref = require_release(ref)
    tag = require_upstream_tag(tag)
    current = current_release(root)
    if version_key(ref) <= version_key(current):
        raise ValueError(f"Hermes {ref} does not advance {current}")
    config = root / "spec/refs.yaml"
    releases = load_releases(config)
    if ref in releases or tag in releases.values():
        raise ValueError(f"Hermes {ref} ({tag}) is already tracked")
    version = ref[1:]
    gradle = root / "kotlin/build.gradle.kts"
    body = gradle.read_text(encoding="utf-8")
    new, count = re.subn(r'^version = "\d+\.\d+\.\d+"$', f'version = "{version}"', body, flags=re.MULTILINE)
    if count != 1:
        raise ValueError("Expected one Kotlin package version")
    with config.open("a", encoding="utf-8") as stream:
        stream.write(f"  {ref}: {tag}\n")
    (root / "spec/current-release.txt").write_text(ref + "\n", encoding="utf-8")
    gradle.write_text(new, encoding="utf-8")
    return version


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adopt", metavar="VERSION")
    parser.add_argument("--tag", help="upstream release tag of the adopted version")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    if args.adopt:
        if not args.tag:
            parser.error("--adopt needs --tag")
        print(adopt(args.adopt, args.tag))
        return
    found = detect()
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as stream:
            stream.write(f"ref={found[0] if found else ''}\ntag={found[1] if found else ''}\n")
    print(f"{found[0]} ({found[1]})" if found else "none")


if __name__ == "__main__":
    main()
