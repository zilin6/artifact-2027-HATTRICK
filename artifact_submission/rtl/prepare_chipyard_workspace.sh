#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: prepare_chipyard_workspace.sh [--dry-run] /path/to/full-chipyard

Restore the artifact RTL sources into an existing, fully initialized Chipyard
workspace. The target workspace must be the official Chipyard 1.13.0 checkout
with normal submodules, support tools, environment setup, and caches.

This script replaces only the artifact-owned generator subtrees that carry the
submitted RTL changes, then restores the artifact's top-level Chipyard files,
Verilator scripts, and FireSim deploy configuration. Unmodified generator
subtrees remain from the official Chipyard base commit, not from any local
machine-specific generator snapshot.

Example:
  ./artifact_submission/rtl/prepare_chipyard_workspace.sh /path/to/chipyard-work
  cd /path/to/chipyard-work
  source ./env.sh
  cd sims/verilator
  CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1 make CONFIG=SmallBoomV3Config -j8
USAGE
}

dry_run=0

while [[ $# -gt 0 ]]; do
  case "${1}" in
    --dry-run)
      dry_run=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --*)
      echo "error: unknown option: ${1}" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "${target_root:-}" ]]; then
        echo "error: multiple target workspaces were provided" >&2
        usage >&2
        exit 2
      fi
      target_root=${1%/}
      shift
      ;;
  esac
done

if [[ -z "${target_root:-}" ]]; then
  usage >&2
  exit 2
fi

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
snapshot_root="${script_dir}/chipyard"
expected_chipyard_tag=1.13.0
expected_chipyard_commit=69eba860a352343e4ac6b6df0f3638a79a86ec78

if [[ ! -d "${snapshot_root}" ]]; then
  echo "error: artifact RTL snapshot not found: ${snapshot_root}" >&2
  exit 1
fi

if [[ ! -d "${target_root}" ]]; then
  echo "error: target Chipyard workspace does not exist: ${target_root}" >&2
  exit 1
fi

if current_commit=$(git -C "${target_root}" rev-parse HEAD 2>/dev/null); then
  if [[ "${current_commit}" != "${expected_chipyard_commit}" ]]; then
    echo "warning: target Chipyard HEAD is ${current_commit}; reference official Chipyard ${expected_chipyard_tag} commit is ${expected_chipyard_commit}" >&2
  fi
fi

for required in env.sh generators sims/verilator scripts/sbt-launch.jar tools/DRAMSim2 tools/torture generators/tracegen; do
  if [[ ! -e "${target_root}/${required}" ]]; then
    echo "error: target does not look like a complete Chipyard workspace; missing ${required}" >&2
    exit 1
  fi
done

copy_file() {
  local rel=$1
  if [[ ! -f "${snapshot_root}/${rel}" ]]; then
    return 0
  fi
  echo "restore file: ${rel}"
  if [[ ${dry_run} -eq 0 ]]; then
    mkdir -p "${target_root}/$(dirname "${rel}")"
    cp -p "${snapshot_root}/${rel}" "${target_root}/${rel}"
  fi
}

copy_tree() {
  local rel=$1
  if [[ ! -d "${snapshot_root}/${rel}" ]]; then
    return 0
  fi
  echo "restore tree: ${rel}"
  if [[ ${dry_run} -eq 0 ]]; then
    mkdir -p "${target_root}/${rel}"
    (
      cd "${snapshot_root}/${rel}"
      tar \
        --exclude='.git' \
        --exclude='.gitmodules' \
        --exclude='.codex' \
        --exclude='target' \
        --exclude='generated-src' \
        --exclude='output' \
        --exclude='*.log' \
        --exclude='*.pid' \
        --exclude='*.pyc' \
        --exclude='*.vcd' \
        --exclude='*.fsdb' \
        --exclude='*.dump' \
        -cf - .
    ) | (
      cd "${target_root}/${rel}"
      tar -xf -
    )
  fi
}

replace_tree() {
  local rel=$1
  if [[ ! -d "${snapshot_root}/${rel}" ]]; then
    echo "error: artifact replacement tree is missing: ${rel}" >&2
    exit 1
  fi
  echo "replace tree: ${rel}"
  if [[ ${dry_run} -eq 0 ]]; then
    rm -rf -- "${target_root:?}/${rel}"
    mkdir -p "${target_root}/$(dirname "${rel}")"
    (
      cd "${snapshot_root}/${rel}"
      tar \
        --exclude='.git' \
        --exclude='.gitmodules' \
        --exclude='.codex' \
        --exclude='target' \
        --exclude='generated-src' \
        --exclude='output' \
        --exclude='*.log' \
        --exclude='*.pid' \
        --exclude='*.pyc' \
        --exclude='*.vcd' \
        --exclude='*.fsdb' \
        --exclude='*.dump' \
        -cf - .
    ) | (
      mkdir -p "${target_root}/${rel}"
      cd "${target_root}/${rel}"
      tar -xf -
    )
  fi
}

copy_file build.sbt
copy_file common.mk
copy_file variables.mk
copy_file sims/common-sim-flags.mk
copy_file generators/tracegen/tracegen.mk
copy_file tools/torture.mk

copy_tree project
copy_tree scripts
replace_tree generators/chipyard
replace_tree generators/boom
replace_tree generators/rocket-chip
replace_tree generators/rocket-chip-inclusive-cache
replace_tree generators/testchipip
copy_tree sims/verilator
copy_tree sims/firesim/deploy

if [[ ${dry_run} -eq 1 ]]; then
  echo "dry-run complete; no files were changed"
else
  echo "restore complete: ${target_root}"
  echo "next: cd ${target_root} && source ./env.sh && cd sims/verilator && CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1 make CONFIG=SmallBoomV3Config -j8"
fi
