#!/usr/bin/env bash

set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

cd "$SCRIPT_DIR"

OUTPUT_DIR=${OUTPUT_DIR:-build}
BUILD_DIR_NAME=${BUILD_DIR_NAME:-bd}
LOG_DIR_NAME=${LOG_DIR_NAME:-logs}
EXTRA_CFLAGS=${EXTRA_CFLAGS:-}

export OUTPUT_DIR
export BUILD_DIR_NAME
export LOG_DIR_NAME
export EXTRA_CFLAGS

./build.sh

if [ -f "$OUTPUT_DIR/byte-store-stream" ]; then
  cp "$OUTPUT_DIR/byte-store-stream" "$OUTPUT_DIR/byte-store-stream-vm-noaddr"
fi
