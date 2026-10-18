#!/usr/bin/env bash
set -euo pipefail

# Works from any directory; uses the repository-pinned ktlint through the Gradle wrapper.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec ./gradlew ktlintFormat "$@"
