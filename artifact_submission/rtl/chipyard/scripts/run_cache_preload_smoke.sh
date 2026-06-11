#!/usr/bin/env bash

set -euo pipefail

ROOT=/path/to/chipyard
SIM_DIR="$ROOT/sims/verilator"
SPEC="${1:-$ROOT/software/cache_state_preload/examples/noop.preload.txt}"
BIN="${2:-$ROOT/toolchains/riscv-tools/riscv-tests/isa/rv64mi-p-cache-rw-engine-bypass-load-burst}"
CONFIG="${CONFIG:-SmallBoomV3Config}"
TIMEOUT_CYCLES="${TIMEOUT_CYCLES:-5000000}"

L1_BIN="$ROOT/software/cache_state_preload/examples/.smoke.l1.bin"
L2_BIN="$ROOT/software/cache_state_preload/examples/.smoke.l2.bin"

python3 "$ROOT/scripts/cache_state_preload_tool.py" build "$SPEC" --out-l1 "$L1_BIN" --out-l2 "$L2_BIN"

make -C "$SIM_DIR" \
  CONFIG="$CONFIG" \
  BINARY="$BIN" \
  LOADMEM=1 \
  BREAK_SIM_PREREQ=1 \
  TIMEOUT_CYCLES="$TIMEOUT_CYCLES" \
  EXTRA_SIM_FLAGS="+cachepreload_l1=$L1_BIN +cachepreload_l2=$L2_BIN" \
  run-binary-fast
