#!/usr/bin/env bash

set -eo pipefail

REPO_ROOT=/path/to/chipyard
TIMEOUT_CYCLES="${TIMEOUT_CYCLES:-900000}"
LOG_PATH="${LOG_PATH:-$REPO_ROOT/sims/verilator/crc32_log}"

cd "$REPO_ROOT"
source ./env.sh
set -u

make -C sims/verilator \
  CONFIG=SmallBoomV3Config \
  BINARY="$REPO_ROOT/software/vm_crypto_embench/build/crc32" \
  LOADMEM=1 \
  BREAK_SIM_PREREQ=1 \
  TIMEOUT_CYCLES="$TIMEOUT_CYCLES" \
  EXTRA_SIM_FLAGS=+verbose \
  run-binary-fast 2>&1 | tee "$LOG_PATH"

# make -C sims/verilator \
#   CONFIG=SmallBoomV3Config \
#   BINARY="$REPO_ROOT/software/vm_crypto_embench/build/crc32" \
#   LOADMEM=1 \
#   BREAK_SIM_PREREQ=1 \
#   TIMEOUT_CYCLES="$TIMEOUT_CYCLES" \
#   # EXTRA_SIM_FLAGS=+verbose \
#   run-binary-fast
