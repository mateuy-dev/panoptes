#!/usr/bin/env bash
# Starts Panoptes on the project in the current directory (or the closest parent with .panoptes/ or .env).
#   ./run.sh              -> desktop UI (Compose)
#   ./run.sh --cli [args] -> terminal CLI (fat JAR, so stdin/password prompts work)
# Set PANOPTES_PROJECT to open another project.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"

# Gradle runs the desktop app from its module folder, so the project is passed explicitly
export PANOPTES_PROJECT="${PANOPTES_PROJECT:-$PWD}"

if [[ "${1:-}" == "--cli" ]]; then
    shift
    "$root/gradlew" -q -p "$root" :core:fatJar
    exec java -jar "$root/core/build/libs/panoptes-1.0.0.jar" "$@"
fi

exec "$root/gradlew" -p "$root" :desktop:run "$@"
