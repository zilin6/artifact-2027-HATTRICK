#!/usr/bin/env bash

set -eo pipefail

ROOT=/path/to/chipyard
ISA_DIR="$ROOT/toolchains/riscv-tools/riscv-tests/isa"
SIM_DIR="$ROOT/sims/verilator"
CONFIG="${CONFIG:-SmallBoomV3Config}"
VERBOSE="${VERBOSE:-0}"
MAX_PARALLEL="${MAX_PARALLEL:-1}"
REBUILD_SIM="${REBUILD_SIM:-0}"
CACHE_PRELOAD_SPEC="${CACHE_PRELOAD_SPEC:-}"
CACHE_PRELOAD_L1="${CACHE_PRELOAD_L1:-}"
CACHE_PRELOAD_L2="${CACHE_PRELOAD_L2:-}"
CACHE_COUNTER_BASE_PRELOAD="${CACHE_COUNTER_BASE_PRELOAD:-}"
CACHE_COUNTER_BASE_PRELOAD_AUTO="${CACHE_COUNTER_BASE_PRELOAD_AUTO:-0}"
CACHE_COUNTER_BASE_PRELOAD_DELTA="${CACHE_COUNTER_BASE_PRELOAD_DELTA:-0x10000000}"

source "$ROOT/env.sh"
set -u

RUN_TS_HUMAN=$(date '+%Y-%m-%d %H:%M:%S %z')
RUN_TS_FILE=$(date '+%Y-%m-%d_%H-%M-%S')
OUTDIR="$ROOT/software/cache_crypto_regression/run-logs-$RUN_TS_FILE"
mkdir -p "$OUTDIR"

if [[ -n "$CACHE_PRELOAD_SPEC" ]]; then
  CACHE_PRELOAD_L1="$OUTDIR/cachepreload.l1.bin"
  CACHE_PRELOAD_L2="$OUTDIR/cachepreload.l2.bin"
  python3 "$ROOT/scripts/cache_state_preload_tool.py" build "$CACHE_PRELOAD_SPEC" \
    --out-l1 "$CACHE_PRELOAD_L1" --out-l2 "$CACHE_PRELOAD_L2"
fi

if [[ "$#" -gt 0 ]]; then
  tests=("$@")
else
  tests=(
  rv64mi-p-cache-rw-engine-bypass-store-burst
  rv64mi-p-cache-rw-engine-bypass-load-burst
  rv64mi-p-cache-rw-engine-bypass-store-load-chain
  rv64mi-p-cache-rw-engine-bypass-byte-same-word
  rv64mi-p-cache-rw-engine-bypass-store-burst-preload
  rv64mi-p-cache-rw-reenc-engine-bypass-same-line
  rv64mi-p-cache-rw-reenc-engine-bypass-hot-chunk
  rv64mi-p-cache-rw-reenc-engine-bypass-interleave
  rv64mi-p-cache-rw-reenc-followed-by-load
  rv64mi-p-cache-rw-reenc-followed-by-store
  rv64mi-p-cache-rw-reenc-followed-by-line-sweep
  rv64mi-p-cache-rw-reenc-dual-line-interleave
  rv64mi-p-cache-rw-reenc-double-rollover-sweep
  rv64mi-p-cache-rw-reenc-held-replay-counter
  )
fi

timeout_for() {
  case "$1" in
    rv64mi-p-cache-rw-engine-bypass-store-burst) echo 5000000 ;;
    rv64mi-p-cache-rw-engine-bypass-load-burst) echo 5000000 ;;
    rv64mi-p-cache-rw-engine-bypass-store-load-chain) echo 5000000 ;;
    rv64mi-p-cache-rw-engine-bypass-byte-same-word) echo 5000000 ;;
    rv64mi-p-cache-rw-engine-bypass-store-burst-preload) echo 5000000 ;;
    rv64mi-p-cache-rw-reenc-engine-bypass-same-line) echo 5000000 ;;
    rv64mi-p-cache-rw-reenc-engine-bypass-hot-chunk) echo 6000000 ;;
    rv64mi-p-cache-rw-reenc-engine-bypass-interleave) echo 6000000 ;;
    rv64mi-p-cache-rw-reenc-followed-by-load) echo 8000000 ;;
    rv64mi-p-cache-rw-reenc-followed-by-store) echo 8000000 ;;
    rv64mi-p-cache-rw-reenc-followed-by-line-sweep) echo 8000000 ;;
    rv64mi-p-cache-rw-reenc-dual-line-interleave) echo 10000000 ;;
    rv64mi-p-cache-rw-reenc-double-rollover-sweep) echo 10000000 ;;
    rv64mi-p-cache-rw-reenc-held-replay-counter) echo 12000000 ;;
    *) echo 8000000 ;;
  esac
}

extract_cycles() {
  perl -ne 'if(/\*\*\* (?:PASSED|FAILED) \*\*\*.*?after\s+([0-9]+)\s+simulation cycles/){$c=$1} END{print $c if defined $c}'
}

build_tests() {
  make -C "$ISA_DIR" "${tests[@]}"
}

build_sim_if_needed() {
  local sim="$SIM_DIR/simulator-chipyard.harness-$CONFIG"
  if [[ "$REBUILD_SIM" == "1" || ! -x "$sim" ]]; then
    make -C "$SIM_DIR" CONFIG="$CONFIG" -j16
  fi
}

derive_counter_base_preload() {
  local bin="$1"
  local sym
  local delta
  local value

  sym=$(riscv64-unknown-elf-readelf -s "$bin" | awk '/ counter_store$/{print $2; exit}')
  if [[ -z "$sym" ]]; then
    return 1
  fi

  delta=$((CACHE_COUNTER_BASE_PRELOAD_DELTA))
  value=$((16#$sym - delta))
  printf '%u' "$value"
}

run_one() {
  local test="$1"
  local max_cycles
  local bin
  local log
  local rc
  local status
  local reason
  local cycles
  local extra_args=()
  local preload_args=""
  local counter_base_preload=""

  max_cycles=$(timeout_for "$test")
  bin="$ISA_DIR/$test"
  log="$OUTDIR/$test.log"

  if [[ "$VERBOSE" == "1" ]]; then
    preload_args+=" +verbose"
  fi
  if [[ -n "$CACHE_COUNTER_BASE_PRELOAD" ]]; then
    counter_base_preload="$((CACHE_COUNTER_BASE_PRELOAD))"
  elif [[ "$CACHE_COUNTER_BASE_PRELOAD_AUTO" == "1" ]]; then
    counter_base_preload=$(derive_counter_base_preload "$bin" || true)
  fi
  if [[ -n "$CACHE_PRELOAD_L1" || -n "$CACHE_PRELOAD_L2" ]]; then
    [[ -n "$CACHE_PRELOAD_L1" ]] && preload_args+=" +cachepreload_l1=$CACHE_PRELOAD_L1"
    [[ -n "$CACHE_PRELOAD_L2" ]] && preload_args+=" +cachepreload_l2=$CACHE_PRELOAD_L2"
  fi
  if [[ -n "$counter_base_preload" ]]; then
    preload_args+=" +cache_crypto_base_preload_enable=1 +cache_crypto_base_preload_value=${counter_base_preload}"
  fi
  if [[ -n "$preload_args" ]]; then
    extra_args+=("EXTRA_SIM_FLAGS=${preload_args# }")
  fi

  set +e
  make -C "$SIM_DIR" \
    CONFIG="$CONFIG" \
    BINARY="$bin" \
    LOADMEM=1 \
    BREAK_SIM_PREREQ=1 \
    TIMEOUT_CYCLES="$max_cycles" \
    "${extra_args[@]}" \
    run-binary-fast >"$log" 2>&1
  rc=$?
  set -e

  cycles=$(extract_cycles < "$log" || true)

  if [[ "$rc" -eq 0 ]] && ! rg -q '\*\*\* FAILED \*\*\*' "$log"; then
    status="success"
    reason="completed"
  elif rg -q '\*\*\* FAILED \*\*\* *\(timeout\)' "$log"; then
    status="failure"
    reason="timeout"
  else
    status="failure"
    reason="make-exit-$rc"
    if rg -q '\*\*\* FAILED \*\*\*' "$log"; then
      reason=$(rg -o '\*\*\* FAILED \*\*\* *\(exit code = [0-9]+' "$log" | tail -n1 | sed -E 's/.*exit code = ([0-9]+)/exit-code-\1/' || true)
      reason="${reason:-make-exit-$rc}"
    fi
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$test" "$status" "${cycles:-}" "$reason" "$max_cycles" "$log"
}

echo "[$RUN_TS_HUMAN] building tests"
build_tests
echo "[$RUN_TS_HUMAN] ensuring simulator exists"
build_sim_if_needed

RESULTS_TSV="$OUTDIR/results.tsv"
printf 'test\tstatus\tcycles\treason\tmax_cycles\tlog\n' > "$RESULTS_TSV"

echo "[$RUN_TS_HUMAN] running tests"
active=0
for test in "${tests[@]}"; do
  {
    run_one "$test"
  } >> "$RESULTS_TSV" &
  active=$((active + 1))
  if [[ "$active" -ge "$MAX_PARALLEL" ]]; then
    wait -n || true
    active=$((active - 1))
  fi
done
wait || true

{
  printf 'test\tstatus\tcycles\treason\tmax_cycles\tlog\n'
  tail -n +2 "$RESULTS_TSV" | sort -k1,1
} > "$RESULTS_TSV.tmp"
mv "$RESULTS_TSV.tmp" "$RESULTS_TSV"

echo
echo "Cache-crypto regression summary"
echo "Config: $CONFIG"
echo "Outdir: $OUTDIR"
column -t -s $'\t' "$RESULTS_TSV"
