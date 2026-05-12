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

    set -- {{args}}

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
        "--jdownloader.api.url=${JDOWNLOADER_API_URL}"
        "${SPRING_ARGS[@]}"
    )

    echo ./gradlew bootRun --args="${ARGS[*]}"

    ./gradlew bootRun --args="${ARGS[*]}"

# Build the app
build:
    ./gradlew bootJar