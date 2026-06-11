#!/bin/bash

set -ex

LOCK_FD=9

sanitize_lock_token() {
    printf '%s' "${1:-all}" | tr -c 'A-Za-z0-9._-' '_'
}

acquire_spec_artifact_lock() {
    local suite="$1"
    local input="$2"
    local benchmark="${3:-all}"
    local lock_root
    local lock_file
    local benchmark_key

    : "${SPEC_DIR:?SPEC_DIR must be set before building SPEC workloads}"
    lock_root="${SPEC_LOCK_DIR:-${SPEC_DIR}/.chipyard-locks}"
    benchmark_key="$(sanitize_lock_token "${benchmark}")"
    lock_file="${lock_root}/${suite}-${input}-${benchmark_key}.lock"

    mkdir -p "${lock_root}"
    if ! command -v flock >/dev/null 2>&1; then
        echo "ERROR: flock command not found; cannot serialize SPEC artifact reuse safely"
        exit 1
    fi

    # Serialize build/reuse for the same suite/input/benchmark so a
    # SPEC_SKIP_COMPILE=1 invocation cannot race ahead of an in-flight compile.
    exec 9>"${lock_file}"
    echo "Waiting for SPEC artifact lock: ${lock_file}"
    flock "${LOCK_FD}"
    echo "Acquired SPEC artifact lock: ${lock_file}"
}

if [ -z "${RISCV:-}" ]; then
    for candidate in \
        /path/to/chipyard/.conda-env/riscv-tools \
        /path/to/chipyard/.conda-env/riscv-tools \
        /path/to/riscv-tools
    do
        if [ -x "${candidate}/bin/riscv64-unknown-linux-gnu-g++" ]; then
            export RISCV="${candidate}"
            break
        fi
    done
fi

if [ "$1" != "ref" ] && [ "$1" != "test" ] && [ "$1" != "train" ]; then
    echo "Must specify ref/test/train"
    exit 1
fi

echo "Building SPEC2017 Intspeed with $1 inputs"
echo "SPEC_SKIP_COMPILE=${SPEC_SKIP_COMPILE:-0}"
if [ "${SPEC_SKIP_COMPILE:-0}" = "1" ]; then
    echo "Reusing existing SPEC binaries because SPEC_SKIP_COMPILE=1"
fi
acquire_spec_artifact_lock "intspeed" "$1" "${2:-all}"
if [ $# -ge 2 ]; then
    echo "Limiting build to benchmark $2"
    rm -rf "speckle/build/overlay/intspeed/$1"
    make spec17-intspeed INPUT="$1" BENCHMARKS="$2"
else
    make spec17-intspeed INPUT="$1"
fi
