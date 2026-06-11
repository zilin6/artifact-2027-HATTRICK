#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  extract_clean_boom_commit_logs.sh <log_a> <log_b> [out_dir]

Extract only BOOM commit-log lines that begin with "cycle=" from two raw logs,
and write a short comparison summary alongside the cleaned logs.
EOF
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage >&2
  exit 1
fi

log_a=$1
log_b=$2
out_dir=${3:-$(pwd)}

for log in "$log_a" "$log_b"; do
  if [[ ! -f "$log" ]]; then
    echo "Missing log: $log" >&2
    exit 1
  fi
done

mkdir -p "$out_dir"

clean_name() {
  local src=$1
  local base
  base=$(basename "$src")
  printf '%s/%s.commit-only.log' "$out_dir" "${base%.*}"
}

summary_name="$out_dir/boom-commit-log-compare-summary.txt"
clean_a=$(clean_name "$log_a")
clean_b=$(clean_name "$log_b")

grep '^cycle=' "$log_a" > "$clean_a"
grep '^cycle=' "$log_b" > "$clean_b"

line_count() {
  wc -l < "$1" | tr -d ' '
}

first_line() {
  head -n 1 "$1" || true
}

last_line() {
  tail -n 1 "$1" || true
}

completed_line() {
  grep 'Completed after' "$1" | tail -n 1 || true
}

{
  echo "left_raw=$log_a"
  echo "left_clean=$clean_a"
  echo "left_commit_lines=$(line_count "$clean_a")"
  echo "left_completed=$(completed_line "$log_a")"
  echo "left_first_commit=$(first_line "$clean_a")"
  echo "left_last_commit=$(last_line "$clean_a")"
  echo
  echo "right_raw=$log_b"
  echo "right_clean=$clean_b"
  echo "right_commit_lines=$(line_count "$clean_b")"
  echo "right_completed=$(completed_line "$log_b")"
  echo "right_first_commit=$(first_line "$clean_b")"
  echo "right_last_commit=$(last_line "$clean_b")"
} > "$summary_name"

echo "Wrote:"
echo "  $clean_a"
echo "  $clean_b"
echo "  $summary_name"
