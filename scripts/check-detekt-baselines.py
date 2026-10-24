#!/usr/bin/env python3
"""Reject baseline growth relative to a review base; allow the initial Detekt onboarding."""
import argparse
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree


def entries(xml):
    root = ElementTree.fromstring(xml)
    return {(section.tag, issue.text) for section in root for issue in section}


def additions(previous, current):
    return entries(current) - entries(previous)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="HEAD")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]

    def git(*arguments):
        return subprocess.run(
            ["git", *arguments], cwd=repo, capture_output=True, text=True, check=False
        )

    revision = git("rev-parse", "--verify", f"{args.base}^{{commit}}")
    if revision.returncode:
        sys.exit(f"Cannot resolve baseline review base {args.base}: {revision.stderr.strip()}")
    base = revision.stdout.strip()
    inventory = git("ls-tree", "-r", "--name-only", base, "config/detekt")
    if inventory.returncode:
        sys.exit(inventory.stderr)
    previous_paths = set(inventory.stdout.splitlines())
    if "config/detekt/detekt.yml" not in previous_paths:
        print("Initial Detekt onboarding: no previous debt inventory exists at the review base.")
        return

    empty = "<SmellBaseline><CurrentIssues/></SmellBaseline>"
    violations = []
    for path in sorted((repo / "config/detekt/baselines").glob("*.xml")):
        relative = path.relative_to(repo).as_posix()
        if relative in previous_paths:
            result = git("show", f"{base}:{relative}")
            if result.returncode:
                sys.exit(result.stderr)
            previous = result.stdout
        else:
            previous = empty
        for section, finding in sorted(additions(previous, path.read_text())):
            violations.append(f"{relative}: {section}: {finding}")
    if violations:
        sys.exit("Detekt baselines may only shrink. New exceptions:\n" + "\n".join(violations))
    print("Detekt baselines have no new exceptions.")


if __name__ == "__main__":
    main()
