"""Report versioned Maven payloads and estimate the Central File Count."""

import argparse
from collections import Counter
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("repository", type=Path)
parser.add_argument("group")
parser.add_argument("version")
parser.add_argument("--max-files", type=int, help="Fail if the estimated Central File Count exceeds this budget.")
args = parser.parse_args()
if args.max_files is not None and args.max_files < 0:
    parser.error("--max-files must be non-negative")

group_directory = args.repository.joinpath(*args.group.split("."))
modules = Counter()
platforms = Counter()
file_types = Counter()
targets = ("iossimulatorarm64", "iosarm64", "wasm-js", "android", "jvm", "js")
jar_classifiers = ("-javadoc.jar", "-sources.jar", "-metadata.jar")

if not group_directory.is_dir():
    parser.error(f"Maven group directory does not exist: {group_directory}")

for artifact_directory in sorted(group_directory.iterdir()):
    version_directory = artifact_directory / args.version
    if not version_directory.is_dir():
        continue
    artifact = artifact_directory.name
    target = next((name for name in targets if artifact.endswith("-" + name)), "root")
    module = artifact.removesuffix("-" + target) if target != "root" else artifact
    for payload in version_directory.iterdir():
        if not payload.is_file() or payload.suffix in {".asc", ".md5", ".sha1", ".sha256", ".sha512"}:
            continue
        modules[module] += 1
        platforms[target] += 1
        file_type = next((name for name in jar_classifiers if payload.name.endswith(name)), payload.suffix)
        file_types[file_type] += 1

total = sum(modules.values())
if not total:
    parser.error(f"No payloads found for {args.group}:{args.version}")

# Actron 1.0.0-beta.1 had one signature and two checksums per payload in Central.
print(f"### Maven publication files for `{args.group}:{args.version}`\n")
estimated_files = total * 4
print(f"{total:,} payloads. Estimated Central File Count **{estimated_files:,}**.")
print("Estimate assumes one signature and two checksums per payload, as observed in Actron 1.0.0-beta.1.")
if args.max_files is not None:
    remaining = args.max_files - estimated_files
    if remaining >= 0:
        print(f"Configured budget **{args.max_files:,}**. Headroom **{remaining:,}** files.")
    else:
        print(f"Configured budget **{args.max_files:,}**. Estimate exceeds it by **{-remaining:,}** files.")

for heading, counts in (("Module", modules), ("Platform", platforms), ("File type", file_types)):
    print(f"\n| {heading} | Payloads | Estimated Central files |")
    print("| --- | ---: | ---: |")
    for name, count in counts.most_common():
        print(f"| `{name}` | {count:,} | {count * 4:,} |")

if args.max_files is not None and estimated_files > args.max_files:
    parser.exit(1, "Estimated Maven Central file count exceeds the configured budget.\n")
