#!/bin/bash

set -ex

export SPEC_FILE_KEY_HEX="${SPEC_FILE_KEY_HEX:-0123456789abcdeffedcba9876543210}"
export SPEC_FILE_IV_HEX="${SPEC_FILE_IV_HEX:-1032547698badcfeefcdab8967452301}"

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

encrypt_mcf_test_input() {
    local script_dir="$1"
    local crypto_dir
    local tool_dir
    local encryptor
    local input_file
    local plain_file
    local encrypted_file
    local roundtrip_file
    local source_file

    crypto_dir="${script_dir}/../firemarshal/example-workloads/exit-debug-protected-file"
    tool_dir="${script_dir}/speckle/build/host-tools"
    encryptor="${tool_dir}/encrypt_demo_file"
    input_file="${script_dir}/speckle/build/overlay/intspeed/test/605.mcf_s/inp.in"
    source_file="${SPEC_DIR}/benchspec/CPU/605.mcf_s/run/run_base_test_host-m64.0000/inp.in"
    plain_file="${tool_dir}/605.mcf_s-inp.in.plain"
    encrypted_file="${input_file}.encrypted"
    roundtrip_file="${tool_dir}/605.mcf_s-inp.in.roundtrip"

    if [ ! -f "${source_file}" ]; then
        echo "ERROR: missing 605.mcf_s source input: ${source_file}" >&2
        exit 1
    fi

    mkdir -p "${tool_dir}"
    "${HOSTCC:-cc}" -O2 -Wall -Wextra -Werror \
        -o "${encryptor}" \
        "${crypto_dir}/encrypt_demo_file.c" \
        "${crypto_dir}/aes128_ctr.c"
    "${encryptor}" --self-test

    cp "${source_file}" "${plain_file}"
    "${encryptor}" --key "${SPEC_FILE_KEY_HEX}" --iv "${SPEC_FILE_IV_HEX}" \
        "${plain_file}" "${encrypted_file}"
    "${encryptor}" --key "${SPEC_FILE_KEY_HEX}" --iv "${SPEC_FILE_IV_HEX}" \
        "${encrypted_file}" "${roundtrip_file}"
    cmp "${plain_file}" "${roundtrip_file}"
    if cmp -s "${plain_file}" "${encrypted_file}"; then
        echo "ERROR: encrypted 605.mcf_s input equals plaintext" >&2
        exit 1
    fi

    echo "605.mcf_s plaintext: $(sha256sum "${plain_file}")"
    echo "605.mcf_s ciphertext: $(sha256sum "${encrypted_file}")"
    mv "${encrypted_file}" "${input_file}"
    rm -f "${plain_file}" "${roundtrip_file}"
    echo "Encrypted 605.mcf_s test input in overlay: ${input_file}"
}

encrypt_xalancbmk_test_inputs() {
    local script_dir="$1"
    local crypto_dir
    local tool_dir
    local encryptor
    local input_dir
    local input_file
    local plain_file
    local encrypted_file
    local roundtrip_file
    local source_file

    crypto_dir="${script_dir}/../firemarshal/example-workloads/exit-debug-protected-file"
    tool_dir="${script_dir}/speckle/build/host-tools"
    encryptor="${tool_dir}/encrypt_demo_file"
    input_dir="${script_dir}/speckle/build/overlay/intspeed/test/623.xalancbmk_s"
    source_dir="${SPEC_DIR}/benchspec/CPU/623.xalancbmk_s/run/run_base_test_host-m64.0000"

    mkdir -p "${tool_dir}"
    "${HOSTCC:-cc}" -O2 -Wall -Wextra -Werror \
        -o "${encryptor}" \
        "${crypto_dir}/encrypt_demo_file.c" \
        "${crypto_dir}/aes128_ctr.c"
    "${encryptor}" --self-test

    for input_file in test.xml test.lst 100mb.xsd xalanc.xsl; do
        input_file="${input_dir}/${input_file}"
        source_file="${source_dir}/$(basename "${input_file}")"
        if [ ! -f "${source_file}" ]; then
            echo "ERROR: missing 623.xalancbmk_s source input: ${source_file}" >&2
            exit 1
        fi

        plain_file="${tool_dir}/623.xalancbmk_s-$(basename "${input_file}").plain"
        encrypted_file="${input_file}.encrypted"
        roundtrip_file="${tool_dir}/623.xalancbmk_s-$(basename "${input_file}").roundtrip"
        cp "${source_file}" "${plain_file}"
        "${encryptor}" --key "${SPEC_FILE_KEY_HEX}" --iv "${SPEC_FILE_IV_HEX}" \
            "${plain_file}" "${encrypted_file}"
        "${encryptor}" --key "${SPEC_FILE_KEY_HEX}" --iv "${SPEC_FILE_IV_HEX}" \
            "${encrypted_file}" "${roundtrip_file}"
        cmp "${plain_file}" "${roundtrip_file}"
        if cmp -s "${plain_file}" "${encrypted_file}"; then
            echo "ERROR: encrypted 623.xalancbmk_s input equals plaintext" >&2
            exit 1
        fi

        echo "623.xalancbmk_s plaintext: $(sha256sum "${plain_file}")"
        echo "623.xalancbmk_s ciphertext: $(sha256sum "${encrypted_file}")"
        mv "${encrypted_file}" "${input_file}"
        rm -f "${plain_file}" "${roundtrip_file}"
    done
    echo "Encrypted 623.xalancbmk_s test inputs in overlay: ${input_dir}"
}

encrypt_gcc_test_input() {
    local script_dir="$1"
    local crypto_dir
    local tool_dir
    local encryptor
    local input_file
    local source_file
    local plain_file
    local encrypted_file
    local roundtrip_file

    crypto_dir="${script_dir}/../firemarshal/example-workloads/exit-debug-protected-file"
    tool_dir="${script_dir}/speckle/build/host-tools"
    encryptor="${tool_dir}/encrypt_demo_file"
    input_file="${script_dir}/speckle/build/overlay/intspeed/test/602.gcc_s/t1.c"
    source_file="${SPEC_DIR}/benchspec/CPU/602.gcc_s/run/run_base_test_host-m64.0000/t1.c"
    plain_file="${tool_dir}/602.gcc_s-t1.c.plain"
    encrypted_file="${input_file}.encrypted"
    roundtrip_file="${tool_dir}/602.gcc_s-t1.c.roundtrip"

    if [ ! -f "${source_file}" ]; then
        echo "ERROR: missing 602.gcc_s source input: ${source_file}" >&2
        exit 1
    fi

    mkdir -p "${tool_dir}"
    "${HOSTCC:-cc}" -O2 -Wall -Wextra -Werror \
        -o "${encryptor}" \
        "${crypto_dir}/encrypt_demo_file.c" \
        "${crypto_dir}/aes128_ctr.c"
    "${encryptor}" --self-test
    cp "${source_file}" "${plain_file}"
    "${encryptor}" --key "${SPEC_FILE_KEY_HEX}" --iv "${SPEC_FILE_IV_HEX}" \
        "${plain_file}" "${encrypted_file}"
    "${encryptor}" --key "${SPEC_FILE_KEY_HEX}" --iv "${SPEC_FILE_IV_HEX}" \
        "${encrypted_file}" "${roundtrip_file}"
    cmp "${plain_file}" "${roundtrip_file}"
    mv "${encrypted_file}" "${input_file}"
    rm -f "${plain_file}" "${roundtrip_file}"
    echo "Encrypted 602.gcc_s test input in overlay: ${input_file}"
}

encrypt_perlbench_test_inputs() {
    local script_dir="$1"
    local crypto_dir
    local tool_dir
    local encryptor
    local input_dir
    local source_dir
    local source_file
    local relative_file
    local input_file
    local plain_file
    local encrypted_file
    local roundtrip_file

    crypto_dir="${script_dir}/../firemarshal/example-workloads/exit-debug-protected-file"
    tool_dir="${script_dir}/speckle/build/host-tools"
    encryptor="${tool_dir}/encrypt_demo_file"
    input_dir="${script_dir}/speckle/build/overlay/intspeed/test/600.perlbench_s"
    source_dir="${SPEC_DIR}/benchspec/CPU/600.perlbench_s/run/run_base_test_host-m64.0000"

    mkdir -p "${tool_dir}"
    "${HOSTCC:-cc}" -O2 -Wall -Wextra -Werror \
        -o "${encryptor}" \
        "${crypto_dir}/encrypt_demo_file.c" \
        "${crypto_dir}/aes128_ctr.c"
    "${encryptor}" --self-test

    while IFS= read -r -d '' source_file; do
        relative_file="${source_file#${source_dir}/}"
        case "${relative_file}" in
            compare.cmd|speccmds.cmd|perlbench_s_base.host-m64)
                continue
                ;;
        esac
        input_file="${input_dir}/${relative_file}"
        if [ ! -f "${input_file}" ]; then
            continue
        fi
        plain_file="${tool_dir}/600.perlbench_s-${relative_file//\//_}.plain"
        encrypted_file="${input_file}.encrypted"
        roundtrip_file="${tool_dir}/600.perlbench_s-${relative_file//\//_}.roundtrip"
        mkdir -p "$(dirname "${encrypted_file}")"
        cp "${source_file}" "${plain_file}"
        "${encryptor}" --key "${SPEC_FILE_KEY_HEX}" --iv "${SPEC_FILE_IV_HEX}" \
            "${plain_file}" "${encrypted_file}"
        "${encryptor}" --key "${SPEC_FILE_KEY_HEX}" --iv "${SPEC_FILE_IV_HEX}" \
            "${encrypted_file}" "${roundtrip_file}"
        cmp "${plain_file}" "${roundtrip_file}"
        mv "${encrypted_file}" "${input_file}"
        chmod --reference="${source_file}" "${input_file}"
        rm -f "${plain_file}" "${roundtrip_file}"
    done < <(find "${source_dir}" -type f -print0)
    echo "Encrypted 600.perlbench_s test inputs in overlay: ${input_dir}"
}

if [ -z "${RISCV:-}" ]; then
    for candidate in \
        /home/mcy/chipyard/.conda-env/riscv-tools \
        /home/mcy/new_chipyard/chipyard/.conda-env/riscv-tools \
        /home/mcy/opt/riscv-tools-1.0.6/riscv-tools
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

if [ "$1" = "test" ] && [ "${2:-}" = "605.mcf_s" ]; then
    encrypt_mcf_test_input "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi

if [ "$1" = "test" ] && [ "${2:-}" = "623.xalancbmk_s" ]; then
    encrypt_xalancbmk_test_inputs "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi

if [ "$1" = "test" ] && [ "${2:-}" = "602.gcc_s" ]; then
    encrypt_gcc_test_input "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi

if [ "$1" = "test" ] && [ "${2:-}" = "600.perlbench_s" ]; then
    encrypt_perlbench_test_inputs "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi
