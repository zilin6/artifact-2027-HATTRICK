#!/usr/bin/env bash
set -euo pipefail

CHIPYARD_DIR="${CHIPYARD_DIR:-/path/to/chipyard}"
FIRESIM_DIR="${FIRESIM_DIR:-${CHIPYARD_DIR}/sims/firesim}"
MARSHAL_DIR="${MARSHAL_DIR:-${CHIPYARD_DIR}/software/firemarshal}"
DEFAULT_SPEC_DIR="${SPEC_DIR:-/path/to/speccpu2017}"
DEFAULT_WORKLOAD="${CHIPYARD_DIR}/software/spec2017/marshal-configs/spec17-intspeed-test-600.json"
HWDB_CFG="${HWDB_CFG:-${FIRESIM_DIR}/deploy/config_hwdb.yaml}"
BUILD_RECIPES_CFG="${BUILD_RECIPES_CFG:-${FIRESIM_DIR}/deploy/config_build_recipes.yaml}"
RUNTIME_CFG_TEMPLATE="${RUNTIME_CFG_TEMPLATE:-${FIRESIM_DIR}/deploy/config_runtime.yaml}"
UART_LOG="${UART_LOG:-/path/to/firesim-results/sim_slot_0/uartlog}"

log() {
  printf '[spec17_firesim] %s\n' "$*"
}

die() {
  printf '[spec17_firesim] ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage:
  spec17_firesim.sh run [benchmark] [test|train|ref]
  spec17_firesim.sh run [workload_json]
  spec17_firesim.sh status
  spec17_firesim.sh stop
  spec17_firesim.sh help

Supported benchmark aliases:
  600 | 600.perlbench_s | perlbench_s
  602 | 602.gcc_s | gcc_s
  605 | 605.mcf_s | mcf_s
  620 | 620.omnetpp_s | omnetpp_s
  623 | 623.xalancbmk_s | xalancbmk_s
  631 | 631.deepsjeng_s | deepsjeng_s
  641 | 641.leela_s | leela_s
  648 | 648.exchange2_s | exchange2_s

Examples:
  CHIPYARD_DIR=/path/to/chipyard SPEC_DIR=/path/to/speccpu2017 ./spec17_firesim.sh run 600 test
  CHIPYARD_DIR=/path/to/chipyard SPEC_SKIP_COMPILE=1 ./spec17_firesim.sh run 602.gcc_s ref
  ./spec17_firesim.sh status
  ./spec17_firesim.sh stop

Environment:
  CHIPYARD_DIR          Chipyard checkout containing FireMarshal and FireSim
  SPEC_DIR              SPEC CPU2017 installation directory
  SPEC_SKIP_COMPILE     Set to 1 to reuse existing SPEC binaries when available
  SPEC_MON_PROTECTED    Defaults to 1 for the submitted runs
  HWDB_CFG              FireSim HWDB YAML path
  BUILD_RECIPES_CFG     FireSim build-recipes YAML path
  RUNTIME_CFG_TEMPLATE  FireSim runtime YAML template path
  UART_LOG              UART log path printed in the stop criterion

Run flow:
  1. ./marshal -v build
  2. ./marshal -v install
  3. firesim infrasetup
  4. firesim runworkload
EOF
}

is_spec_dataset() {
  case "${1:-}" in
    test|train|ref)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

normalize_spec_dataset() {
  local dataset="${1:-test}"

  if ! is_spec_dataset "${dataset}"; then
    die "Unsupported dataset: ${dataset}. Use one of: test train ref"
  fi

  printf '%s\n' "${dataset}"
}

benchmark_name() {
  case "${1:-}" in
    perlbench_s|600|600.perlbench_s)
      printf '%s\n' "600.perlbench_s"
      ;;
    gcc_s|602|602.gcc_s)
      printf '%s\n' "602.gcc_s"
      ;;
    mcf_s|605|605.mcf_s)
      printf '%s\n' "605.mcf_s"
      ;;
    omnetpp_s|620|620.omnetpp_s)
      printf '%s\n' "620.omnetpp_s"
      ;;
    xalancbmk_s|623|623.xalancbmk_s)
      printf '%s\n' "623.xalancbmk_s"
      ;;
    deepsjeng_s|631|631.deepsjeng_s)
      printf '%s\n' "631.deepsjeng_s"
      ;;
    leela_s|641|641.leela_s)
      printf '%s\n' "641.leela_s"
      ;;
    exchange2_s|648|648.exchange2_s)
      printf '%s\n' "648.exchange2_s"
      ;;
    *)
      return 1
      ;;
  esac
}

create_intspeed_single_workload() {
  local benchmark="$1"
  local dataset="$2"
  local bench_id="${benchmark%%.*}"
  local workload_path

  workload_path="$(mktemp "${CHIPYARD_DIR}/software/spec2017/marshal-configs/spec17-intspeed-${dataset}-${bench_id}-XXXXXX.json")"

  cat > "${workload_path}" <<EOF
{
  "name" : "spec17-intspeed-${dataset}-${bench_id}",
  "base" : "br-base.json",
  "workdir" : "..",
  "host-init" : "build-intspeed.sh ${dataset} ${benchmark}",
  "overlay" : "speckle/build/overlay/intspeed/${dataset}",
  "rootfs-size" : "3GiB",
  "outputs" : ["/output"],
  "post_run_hook" : "handle-results.py -d ${dataset} -s intspeed",
  "jobs" : [
    {
      "name": "${benchmark}",
      "command": "./intspeed.sh ${benchmark} --threads 1"
    }
  ]
}
EOF

  printf '%s\n' "${workload_path}"
}

maybe_static_intspeed_workload() {
  local benchmark="$1"
  local dataset="$2"
  local bench_id="${benchmark%%.*}"
  local static_workload

  static_workload="${CHIPYARD_DIR}/software/spec2017/marshal-configs/spec17-intspeed-${dataset}-${bench_id}.json"
  if [[ -f "${static_workload}" ]]; then
    printf '%s\n' "${static_workload}"
    return 0
  fi

  return 1
}

resolve_intspeed_workload() {
  local benchmark="$1"
  local dataset

  dataset="$(normalize_spec_dataset "${2:-test}")"

  if maybe_static_intspeed_workload "${benchmark}" "${dataset}"; then
    return 0
  fi

  create_intspeed_single_workload "${benchmark}" "${dataset}"
}

resolve_workload() {
  local workload_arg="${1:-$DEFAULT_WORKLOAD}"
  local workload_subarg="${2:-}"
  local benchmark

  if benchmark="$(benchmark_name "${workload_arg}")"; then
    resolve_intspeed_workload "${benchmark}" "${workload_subarg}"
    return 0
  fi

  printf '%s\n' "${workload_arg}"
}

create_workload_without_post_run_hook() {
  local workload_json="$1"
  local workload_dir
  local workload_file
  local workload_stem
  local sanitized_json

  if ! grep -q '"post_run_hook"' "${workload_json}"; then
    printf '%s\n' "${workload_json}"
    return 0
  fi

  workload_dir="$(dirname "${workload_json}")"
  workload_file="$(basename "${workload_json}")"
  workload_stem="${workload_file%.json}"
  sanitized_json="${workload_dir}/${workload_stem}-nohook.json"

  python3 - <<'PY' "${workload_json}" "${sanitized_json}"
import json
import pathlib
import sys

src = pathlib.Path(sys.argv[1])
dst = pathlib.Path(sys.argv[2])

data = json.loads(src.read_text())
data.pop("post_run_hook", None)

text = json.dumps(data, indent=2) + "\n"
if not dst.exists() or dst.read_text() != text:
    dst.write_text(text)
PY

  printf '%s\n' "${sanitized_json}"
}

workload_config_name() {
  local workload_json="$1"

  python3 - <<'PY' "${workload_json}"
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
print(data.get("name") or path.stem)
PY
}

workload_needs_spec_dir() {
  local workload_json="$1"

  case "${workload_json}" in
    "${CHIPYARD_DIR}/software/spec2017/"*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

print_stop_criterion() {
  cat <<EOF
[spec17_firesim] Stop criterion:
[spec17_firesim]   1. Watch UART log: ${UART_LOG}
[spec17_firesim]   2. If the shell prompt is idle after benchmark completion, wait 20 seconds.
[spec17_firesim]   3. If no new UART output appears, stop the simulation.
[spec17_firesim]      Manual commands:
[spec17_firesim]        screen -ls
[spec17_firesim]        screen -S <session>.fsim0 -X quit
EOF
}

check_paths() {
  local workload_json="$1"
  local spec_dir="${SPEC_DIR:-$DEFAULT_SPEC_DIR}"

  [[ -d "${FIRESIM_DIR}" ]] || die "FireSim dir not found: ${FIRESIM_DIR}"
  [[ -d "${MARSHAL_DIR}" ]] || die "FireMarshal dir not found: ${MARSHAL_DIR}"
  [[ -f "${workload_json}" ]] || die "Workload json not found: ${workload_json}"
  if workload_needs_spec_dir "${workload_json}"; then
    [[ -d "${spec_dir}" ]] || die "SPEC_DIR not found: ${spec_dir}"
  fi
  [[ -f "${HWDB_CFG}" ]] || die "Missing FireSim HWDB config: ${HWDB_CFG}"
  [[ -f "${BUILD_RECIPES_CFG}" ]] || die "Missing FireSim build-recipes config: ${BUILD_RECIPES_CFG}"
  [[ -f "${RUNTIME_CFG_TEMPLATE}" ]] || die "Missing FireSim runtime config: ${RUNTIME_CFG_TEMPLATE}"
}

load_firesim_env() {
  cd "${FIRESIM_DIR}"
  local had_nounset=0
  if [[ $- == *u* ]]; then
    had_nounset=1
    set +u
  fi
  # shellcheck disable=SC1091
  source "${FIRESIM_DIR}/sourceme-manager.sh" --skip-ssh-setup
  if [[ "${had_nounset}" -eq 1 ]]; then
    set -u
  fi
  export SPEC_DIR="${SPEC_DIR:-$DEFAULT_SPEC_DIR}"
}

run_step() {
  log "BEGIN: $*"
  "$@"
  log "END: $*"
}

prepare_runtime_cfg() {
  local workload_json="$1"
  local workload_name
  local generated_workload
  local runtime_cfg

  workload_name="$(workload_config_name "${workload_json}")"
  workload_name="${workload_name}.json"
  generated_workload="${FIRESIM_DIR}/deploy/workloads/${workload_name}"
  if [[ ! -f "${generated_workload}" ]]; then
    local fallback_name

    fallback_name="$(basename "${workload_json}")"
    generated_workload="${FIRESIM_DIR}/deploy/workloads/${fallback_name}"
    if [[ ! -f "${generated_workload}" && "${fallback_name}" == *-nohook.json ]]; then
      fallback_name="${fallback_name%-nohook.json}.json"
      generated_workload="${FIRESIM_DIR}/deploy/workloads/${fallback_name}"
    fi
  fi
  [[ -f "${generated_workload}" ]] || die "Generated FireSim workload not found after marshal install: ${generated_workload}"

  runtime_cfg="$(mktemp /tmp/spec17-firesim-runtime-XXXXXX.yaml)"
  sed -E "s#^([[:space:]]*workload_name:).*#\\1 ${workload_name}#" \
    "${RUNTIME_CFG_TEMPLATE}" > "${runtime_cfg}"
  echo "${runtime_cfg}"
}

run_workflow() {
  local workload_json
  local runtime_workload_json
  local runtime_cfg

  workload_json="$(resolve_workload "${1:-$DEFAULT_WORKLOAD}" "${2:-}")"
  runtime_workload_json="$(create_workload_without_post_run_hook "${workload_json}")"
  check_paths "${runtime_workload_json}"
  load_firesim_env

  if workload_needs_spec_dir "${runtime_workload_json}"; then
    export SPEC_MON_PROTECTED="${SPEC_MON_PROTECTED:-1}"
    log "Using SPEC_DIR=${SPEC_DIR}"
    log "SPEC_SKIP_COMPILE=${SPEC_SKIP_COMPILE:-0}"
    log "SPEC_MON_PROTECTED=${SPEC_MON_PROTECTED}"
  fi
  if [[ "${runtime_workload_json}" != "${workload_json}" ]]; then
    log "Using workload=${workload_json} (post_run_hook removed via ${runtime_workload_json})"
  else
    log "Using workload=${runtime_workload_json}"
  fi

  cd "${MARSHAL_DIR}"
  run_step ./marshal -v build "${runtime_workload_json}"
  run_step ./marshal -v install "${runtime_workload_json}"
  runtime_cfg="$(prepare_runtime_cfg "${runtime_workload_json}")"
  log "Using runtime config=${runtime_cfg}"

  cd "${FIRESIM_DIR}"
  run_step firesim -c "${runtime_cfg}" infrasetup -a "${HWDB_CFG}" -r "${BUILD_RECIPES_CFG}"

  print_stop_criterion
  log "BEGIN: firesim -c ${runtime_cfg} runworkload -a ${HWDB_CFG} -r ${BUILD_RECIPES_CFG}"
  firesim -c "${runtime_cfg}" runworkload -a "${HWDB_CFG}" -r "${BUILD_RECIPES_CFG}"
  log "END: firesim -c ${runtime_cfg} runworkload -a ${HWDB_CFG} -r ${BUILD_RECIPES_CFG}"
}

status_sessions() {
  command -v screen >/dev/null 2>&1 || die "screen command not found"
  log "Listing screen sessions matching *.fsim0"
  screen -ls | grep '\.fsim0' || true
}

stop_session() {
  local sessions
  local count
  command -v screen >/dev/null 2>&1 || die "screen command not found"

  sessions="$(screen -ls | awk '/\.fsim0[[:space:]]/ {print $1}')"
  count="$(printf '%s\n' "${sessions}" | sed '/^$/d' | wc -l)"

  if [[ "${count}" -eq 0 ]]; then
    die "No *.fsim0 screen session found"
  fi

  if [[ "${count}" -gt 1 ]]; then
    printf '[spec17_firesim] Multiple *.fsim0 sessions found:\n%s\n' "${sessions}" >&2
    die "Refusing to stop automatically. Please choose one manually with: screen -S <session>.fsim0 -X quit"
  fi

  log "Stopping screen session ${sessions}"
  screen -S "${sessions}" -X quit
  log "Stopped ${sessions}"
}

main() {
  local cmd="${1:-run}"

  case "${cmd}" in
    run)
      shift || true
      run_workflow "${1:-$DEFAULT_WORKLOAD}" "${2:-}"
      ;;
    status)
      status_sessions
      ;;
    stop)
      stop_session
      ;;
    600|600.perlbench_s|perlbench_s|602|602.gcc_s|gcc_s|605|605.mcf_s|mcf_s|620|620.omnetpp_s|omnetpp_s|623|623.xalancbmk_s|xalancbmk_s|631|631.deepsjeng_s|deepsjeng_s|641|641.leela_s|leela_s|648|648.exchange2_s|exchange2_s)
      shift || true
      run_workflow "${cmd}" "${1:-}"
      ;;
    help|-h|--help)
      usage
      ;;
    *)
      run_workflow "${cmd}" "${1:-}"
      ;;
  esac
}

main "$@"
