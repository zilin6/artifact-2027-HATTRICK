#!/usr/bin/env bash

set -o pipefail

ROOT=/path/to/chipyard
DOC="$ROOT/SmallBoomV3_embench_results_2026-04-08.md"
CONFIG=SmallBoomV3Config
MAX_PARALLEL=4

export PATH=/path/to/workspace/miniforge3/bin:$PATH
source "$ROOT/env.sh"
set -u

RUN_TS_HUMAN=$(date '+%Y-%m-%d %H:%M:%S %z')
RUN_TS_FILE=$(date '+%Y-%m-%d_%H-%M-%S')
OUTDIR="$ROOT/software/embench/run-logs-${RUN_TS_FILE}-smallboomv3-noverbose-bg"
RUNNER_LOG="$ROOT/embench-noverbose-bg-${RUN_TS_FILE}.log"
PID_FILE="$ROOT/.latest_embench_noverbose_bg_pid"
OUTDIR_FILE="$ROOT/.latest_embench_noverbose_bg_dir"

mkdir -p "$OUTDIR"
echo "$$" > "$PID_FILE"
echo "$OUTDIR" > "$OUTDIR_FILE"

benchlist=(
  aha-mont64
  crc32
  cubic
  edn
  huffbench
  matmult-int
  minver
  nbody
  nettle-aes
  nettle-sha256
  nsichneu
  picojpeg
  qrduino
  sglib-combined
  slre
  st
  statemate
  ud
  wikisort
)

timeout_for() {
  case "$1" in
    aha-mont64) echo 9000000 ;;
    crc32) echo 18000000 ;;
    cubic) echo 7000000 ;;
    edn) echo 15000000 ;;
    huffbench) echo 16000000 ;;
    matmult-int) echo 14000000 ;;
    minver) echo 3000000 ;;
    nbody) echo 1000000 ;;
    nettle-aes) echo 22000000 ;;
    nettle-sha256) echo 18000000 ;;
    nsichneu) echo 14000000 ;;
    picojpeg) echo 20000000 ;;
    qrduino) echo 19000000 ;;
    sglib-combined) echo 15000000 ;;
    slre) echo 11000000 ;;
    st) echo 1000000 ;;
    statemate) echo 60000000 ;;
    ud) echo 14000000 ;;
    wikisort) echo 9000000 ;;
    *) echo 20000000 ;;
  esac
}

run_one() {
  local bench="$1"
  local max_cycles
  local bin
  local fulllog
  local resultfile
  local rc
  local status
  local reason
  local cycles
  local exit_reason

  max_cycles=$(timeout_for "$bench")
  bin="$ROOT/software/embench/build/$bench"
  fulllog="$OUTDIR/$bench.full.log"
  resultfile="$OUTDIR/$bench.result.tsv"

  rm -f "$resultfile"

  make -C "$ROOT/sims/verilator" \
    CONFIG="$CONFIG" \
    BINARY="$bin" \
    LOADMEM=1 \
    BREAK_SIM_PREREQ=1 \
    TIMEOUT_CYCLES="$max_cycles" \
    run-binary-fast >"$fulllog" 2>&1
  rc=$?

  if [ "$rc" -eq 0 ]; then
    status="success"
    reason="passed"
    cycles=""
  elif rg -q '\*\*\* FAILED \*\*\* *\(timeout\)' "$fulllog"; then
    status="failure"
    reason="timeout"
    cycles=$(perl -ne 'if(/after\s+([0-9]+)\s+simulation cycles/){$c=$1} END{print $c if defined $c}' "$fulllog")
  else
    status="failure"
    exit_reason=$(rg -o '\*\*\* FAILED \*\*\* *\(exit code = [0-9]+' "$fulllog" | tail -n1 | sed -E 's/.*exit code = ([0-9]+)/exit-code-\1/' || true)
    reason="${exit_reason:-make-exit-$rc}"
    cycles=$(perl -ne 'if(/after\s+([0-9]+)\s+simulation cycles/){$c=$1} END{print $c if defined $c}' "$fulllog")
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$bench" "$status" "${cycles:-}" "$reason" "$max_cycles" "$fulllog" > "$resultfile"
}

results_to_markdown() {
  local results_tsv="$1"
  local success_count
  local failure_count
  local total_count

  success_count=$(awk -F'\t' 'NR > 1 && $2 == "success" { c++ } END { print c + 0 }' "$results_tsv")
  failure_count=$(awk -F'\t' 'NR > 1 && $2 == "failure" { c++ } END { print c + 0 }' "$results_tsv")
  total_count=$(awk 'END { print NR - 1 }' "$results_tsv")

  {
    echo
    echo "## Background Non-Verbose Embench Rerun ($RUN_TS_HUMAN)"
    echo
    echo "- Config: \`$CONFIG\`"
    echo "- Run mode: \`run-binary-fast\` with \`LOADMEM=1\`, \`BREAK_SIM_PREREQ=1\`, and no \`+verbose\`"
    echo "- Result log directory: [$OUTDIR]($OUTDIR)"
    echo "- Background runner log: [$RUNNER_LOG]($RUNNER_LOG)"
    echo "- Total benchmarks run: \`$total_count\`"
    echo "- Success: \`$success_count\`"
    echo "- Failure: \`$failure_count\`"
    echo "- Note: successful runs do not print cycle counts without \`+verbose\`, so their cycle field is recorded as \`N/A\`."
    echo
    echo "| Benchmark | Status | Cycles | Note | Max cycles |"
    echo "| --- | --- | --- | --- | ---: |"
    tail -n +2 "$results_tsv" | while IFS=$'\t' read -r bench status cycles reason max_cycles log; do
      if [ -n "${cycles:-}" ]; then
        cycles_md="$cycles"
      else
        cycles_md="N/A"
      fi

      if [ "$status" = "success" ]; then
        status_md="Success"
      else
        status_md="Failure"
      fi

      printf '| `%s` | %s | %s | %s | %s |\n' \
        "$bench" "$status_md" "$cycles_md" "$reason" "$max_cycles"
    done
  } >> "$DOC"
}

echo "[$(date '+%Y-%m-%d %H:%M:%S %z')] starting non-verbose Embench rerun" >> "$RUNNER_LOG"
echo "outdir=$OUTDIR" >> "$RUNNER_LOG"

active=0
for bench in "${benchlist[@]}"; do
  run_one "$bench" >>"$RUNNER_LOG" 2>&1 &
  active=$((active + 1))
  if [ "$active" -ge "$MAX_PARALLEL" ]; then
    wait -n || true
    active=$((active - 1))
  fi
done

wait || true

RESULTS_TSV="$OUTDIR/results.tsv"
{
  printf 'benchmark\tstatus\tcycles\treason\tmax_cycles\tlog\n'
  for f in "$OUTDIR"/*.result.tsv; do
    [ -f "$f" ] && cat "$f"
  done | sort
} > "$RESULTS_TSV"

results_to_markdown "$RESULTS_TSV"

echo "[$(date '+%Y-%m-%d %H:%M:%S %z')] completed non-verbose Embench rerun" >> "$RUNNER_LOG"
