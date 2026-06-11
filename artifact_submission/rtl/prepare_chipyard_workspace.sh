#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: prepare_chipyard_workspace.sh [--dry-run] /path/to/full-chipyard

Overlay the artifact RTL sources onto an existing, fully initialized Chipyard
workspace. The target workspace must provide the normal Chipyard environment,
submodules, support tools, and caches. This script overwrites matching source
files from the artifact but intentionally keeps the target workspace's env.sh,
Git metadata, generated outputs, and unmodified support tool submodules.

Example:
  ./artifact_submission/rtl/prepare_chipyard_workspace.sh /path/to/chipyard-work
  cd /path/to/chipyard-work
  source ./env.sh
  cd sims/verilator
  CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1 make CONFIG=SmallBoomV3Config -j8
USAGE
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage >&2
  exit 2
fi

dry_run=0
if [[ "${1:-}" == "--dry-run" ]]; then
  dry_run=1
  shift
fi

if [[ $# -ne 1 || "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

target_root=${1%/}
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
overlay_root="${script_dir}/chipyard"
expected_chipyard_tag=1.13.0
expected_chipyard_commit=69eba860a352343e4ac6b6df0f3638a79a86ec78

if [[ ! -d "${overlay_root}" ]]; then
  echo "error: artifact RTL snapshot not found: ${overlay_root}" >&2
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

for required in env.sh generators sims/verilator scripts/sbt-launch.jar; do
  if [[ ! -e "${target_root}/${required}" ]]; then
    echo "error: target does not look like a complete Chipyard workspace; missing ${required}" >&2
    exit 1
  fi
done

copy_file() {
  local rel=$1
  if [[ ! -f "${overlay_root}/${rel}" ]]; then
    return 0
  fi
  echo "overlay file: ${rel}"
  if [[ ${dry_run} -eq 0 ]]; then
    mkdir -p "${target_root}/$(dirname "${rel}")"
    cp -p "${overlay_root}/${rel}" "${target_root}/${rel}"
  fi
}

copy_tree() {
  local rel=$1
  if [[ ! -d "${overlay_root}/${rel}" ]]; then
    return 0
  fi
  echo "overlay tree: ${rel}"
  if [[ ${dry_run} -eq 0 ]]; then
    mkdir -p "${target_root}/${rel}"
    (
      cd "${overlay_root}/${rel}"
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

copy_file build.sbt
copy_file common.mk
copy_file variables.mk
copy_file sims/common-sim-flags.mk
copy_file generators/tracegen/tracegen.mk
copy_file tools/torture.mk

copy_tree project
copy_tree scripts
copy_tree generators/chipyard
copy_tree generators/boom
copy_tree generators/rocket-chip
copy_tree generators/rocket-chip-inclusive-cache
copy_tree generators/testchipip
copy_tree sims/verilator
copy_tree sims/firesim/deploy

if [[ ${dry_run} -eq 1 ]]; then
  echo "dry-run complete; no files were changed"
else
  echo "overlay complete: ${target_root}"
  echo "next: cd ${target_root} && source ./env.sh && cd sims/verilator && CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1 make CONFIG=SmallBoomV3Config -j8"
fi
