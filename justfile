# justfile

set dotenv-load := true

# Default target
default:
    @just --list

# Usage:
#   just dev
#   just dev --disable tokyoInsider
#   just dev --disable tokyoInsider --disable animeTosho
#
# Development runner
dev *args:
    #!/usr/bin/env bash
    set -euo pipefail

    echo "BLACKHOLE_FOLDER=${BLACKHOLE_FOLDER}"
    echo "DOWNLOAD_FOLDER=${DOWNLOAD_FOLDER}"

    ls -ld "${BLACKHOLE_FOLDER}"
    ls -ld "${DOWNLOAD_FOLDER}"

    SPRING_ARGS=()

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --disable)
                shift
                SPRING_ARGS+=("--app.sources.$1.enabled=false")
                ;;
            *)
                echo "Unknown argument: $1"
                exit 1
                ;;
        esac
        shift
    done

    ARGS=(
        "--blackhole.folder=${BLACKHOLE_FOLDER}"
        "--download.folder=${DOWNLOAD_FOLDER}"
        "--logging.level.dev.ddlproxy=${LOG_LEVEL}"
        "--base.url=${BASE_URL}"
        "${SPRING_ARGS[@]}"
    )

    ./gradlew bootRun --args="${ARGS[*]}"

# Build the app
build:
    ./gradlew bootJar