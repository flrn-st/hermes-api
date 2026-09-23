"""Detect and adopt published Hermes tags; development branches are ignored."""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path

from tools.fetch_spec import ROOT, UPSTREAM
from tools.ref_policy import load_releases, require_release_tag


def tag_key(ref: str) -> tuple[int, int, int, int]:
    parts = [int(part) for part in require_release_tag(ref)[1:].split(".")]
    return (parts[0], parts[1], parts[2], parts[3] if len(parts) == 4 else 0)


def stable_tags(remote_output: str) -> list[str]:
    tags: set[str] = set()
    for line in remote_output.splitlines():
        if "\trefs/tags/" not in line:
            continue
        ref = line.rsplit("refs/tags/", 1)[-1]
        try:
            require_release_tag(ref)
        except ValueError:
            continue
        tags.add(ref)
    return sorted(tags, key=tag_key)


def newest_untracked(remote_output: str, root: Path = ROOT) -> str | None:
    current = require_release_tag((root / "spec/current-release.txt").read_text(encoding="utf-8").strip())
    minimum = require_release_tag(
        re.search(r"^minimum_contract_tag:\s*(\S+)",
                  (root / "spec/refs.yaml").read_text(encoding="utf-8"), re.MULTILINE).group(1)
    )
    candidates = [tag for tag in stable_tags(remote_output)
                  if tag_key(tag) > tag_key(current) and tag_key(tag) >= tag_key(minimum)]
    return candidates[-1] if candidates else None


def detect(root: Path = ROOT) -> str | None:
    result = subprocess.run(
        ["git", "ls-remote", "--tags", "--refs", UPSTREAM],
        check=True, text=True, capture_output=True,
    )
    return newest_untracked(result.stdout, root)


def adopt(ref: str, root: Path = ROOT) -> str:
    ref = require_release_tag(ref)
    current_file = root / "spec/current-release.txt"
    current = require_release_tag(current_file.read_text(encoding="utf-8").strip())
    if tag_key(ref) <= tag_key(current):
        raise ValueError(f"Release {ref} does not advance {current}")
    config = root / "spec/refs.yaml"
    releases = load_releases(config)
    if ref in releases:
        raise ValueError(f"Release {ref} is already tracked")
    year, month, day, patch = tag_key(ref)
    version = f"{year}.{month * 100 + day}.{patch}"
    gradle = root / "kotlin/build.gradle.kts"
    body = gradle.read_text(encoding="utf-8")
    new, count = re.subn(r'^version = "\d+\.\d+\.\d+"$', f'version = "{version}"', body, flags=re.MULTILINE)
    if count != 1:
        raise ValueError("Expected one Kotlin package version")
    with config.open("a", encoding="utf-8") as stream:
        stream.write(f"  - {ref}\n")
    current_file.write_text(ref + "\n", encoding="utf-8")
    gradle.write_text(new, encoding="utf-8")
    return version


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adopt", metavar="RELEASE")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    if args.adopt:
        print(adopt(args.adopt))
    else:
        ref = detect()
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as stream:
                stream.write(f"ref={ref or ''}\n")
        print(ref or "none")


if __name__ == "__main__":
    main()
