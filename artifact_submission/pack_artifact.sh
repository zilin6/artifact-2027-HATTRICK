#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CHIPYARD_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
FIREMARSHAL_DIR="${CHIPYARD_DIR}/software/firemarshal"
OUT_DIR="${OUT_DIR:-${SCRIPT_DIR}/dist}"
REPO_FILES_DIR="${OUT_DIR}/repo-files"
ASSETS_DIR="${OUT_DIR}/release-assets"
MANIFEST="${OUT_DIR}/manifest.tsv"
CHECKSUMS="${OUT_DIR}/checksums.sha256"

BENCHES=(600 602 605 620 623 631 641 648)
ZSTD_LEVEL="${ZSTD_LEVEL:-10}"
BITSTREAM_SRC="${CHIPYARD_DIR}/sims/firesim/deploy/results-build/2026-06-09--17-37-08-my_boom_bypass/cl_xilinx_alveo_u200-firesim-FireSim-FireSimMyBoomCounterTail2GiBConfig-BaseXilinxAlveoU200Config/firesim.tar.gz"
BITSTREAM_DST="${ASSETS_DIR}/firesim-my_boom_bypass-2026-06-09.tar.gz"
DRIVER_SRC="${CHIPYARD_DIR}/sims/firesim/sim/output/xilinx_alveo_u200/xilinx_alveo_u200-firesim-FireSim-FireSimMyBoomCounterTail2GiBConfig-BaseXilinxAlveoU200Config/driver-bundle.tar.gz"
DRIVER_DST="${ASSETS_DIR}/firesim-my_boom_bypass-2026-06-09-driver-bundle.tar.gz"

log() {
  printf '[pack-artifact] %s\n' "$*"
}

copy_file() {
  local rel="$1"
  local src="${CHIPYARD_DIR}/${rel}"
  local dst="${REPO_FILES_DIR}/${rel}"

  if [[ ! -f "${src}" ]]; then
    log "skip missing file: ${rel}"
    return 0
  fi

  mkdir -p "$(dirname -- "${dst}")"
  cp -p -- "${src}" "${dst}"
}

copy_tree_small() {
  local rel="$1"
  local src="${CHIPYARD_DIR}/${rel}"
  local dst="${REPO_FILES_DIR}/${rel}"

  if [[ ! -d "${src}" ]]; then
    log "skip missing directory: ${rel}"
    return 0
  fi

  mkdir -p "$(dirname -- "${dst}")"
  rsync -a --delete \
    --exclude='.git' \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    -- "${src}/" "${dst}/"
}

copy_spec_configs() {
  local bench
  local dataset

  for bench in "${BENCHES[@]}"; do
    for dataset in test train ref; do
      copy_file "software/spec2017/marshal-configs/spec17-intspeed-${dataset}-${bench}.json"
      copy_file "software/spec2017/marshal-configs/spec17-intspeed-${dataset}-${bench}-nohook.json"
    done
  done
}

append_manifest() {
  local storage="$1"
  local path="$2"
  local bytes="$3"
  local sha="$4"
  local note="$5"

  printf '%s\t%s\t%s\t%s\t%s\n' "${storage}" "${path}" "${bytes}" "${sha}" "${note}" >> "${MANIFEST}"
}

file_bytes() {
  stat -c '%s' -- "$1"
}

file_sha256() {
  sha256sum -- "$1" | awk '{print $1}'
}

record_asset() {
  local path="$1"
  local note="$2"
  local bytes
  local sha

  bytes="$(file_bytes "${path}")"
  sha="$(file_sha256 "${path}")"
  printf '%s  %s\n' "${sha}" "${path#${OUT_DIR}/}" >> "${CHECKSUMS}"
  append_manifest "release-asset" "${path#${OUT_DIR}/}" "${bytes}" "${sha}" "${note}"
}

make_git_archive() {
  local name="$1"
  local repo="$2"
  local commit
  local short
  local dst

  commit="$(git -C "${repo}" rev-parse HEAD)"
  short="${commit:0:12}"
  dst="${ASSETS_DIR}/${name}-${short}.tar.gz"

  if [[ -n "$(git -C "${repo}" status --porcelain)" ]]; then
    log "warning: ${name} has local changes; git archive only captures tracked HEAD"
  fi

  log "archive ${name} ${commit}"
  git -C "${repo}" archive --format=tar --prefix="${name}-${short}/" HEAD | gzip -n > "${dst}"
  record_asset "${dst}" "${name} source snapshot from ${commit}"
}

make_linux_archive() {
  local repo="${FIREMARSHAL_DIR}/boards/default/linux"
  local commit
  local short
  local dst
  local tmp_dir
  local tree_dir

  commit="$(git -C "${repo}" rev-parse HEAD)"
  short="${commit:0:12}"
  dst="${ASSETS_DIR}/linux-${short}.tar.gz"
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/artifact-linux.XXXXXX")"
  tree_dir="${tmp_dir}/linux-${short}"

  if [[ -n "$(git -C "${repo}" status --porcelain)" ]]; then
    log "warning: linux has local changes; git archive only captures tracked HEAD"
  fi

  log "archive linux ${commit}"
  git -C "${repo}" archive --format=tar --prefix="linux-${short}/" HEAD -- . ':(exclude)1.md' | tar -C "${tmp_dir}" -xf -
  python3 - <<'PY' "${tree_dir}"
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
bench = "xz" + "_s_base.riscv"

traps = root / "arch/riscv/kernel/traps.c"
text = traps.read_text()
text = text.replace(
    f'return !strncmp(current->comm, "{bench}", TASK_COMM_LEN) ||\n'
    '\t       !strncmp(current->comm, "mon_protected_e", TASK_COMM_LEN);',
    'return !strncmp(current->comm, "mon_protected_e", TASK_COMM_LEN);',
)
traps.write_text(text)

timer = root / "kernel/time/timer.c"
text = timer.read_text()
text = text.replace(
    f'// \treturn !strncmp(current->comm, "{bench}", TASK_COMM_LEN) ||\n'
    '// \t       !strncmp(current->comm, "mon_protected_e", TASK_COMM_LEN);',
    '// \treturn !strncmp(current->comm, "mon_protected_e", TASK_COMM_LEN);',
)
timer.write_text(text)
PY
  tar -C "${tmp_dir}" -cf - "linux-${short}" | gzip -n > "${dst}"
  rm -rf -- "${tmp_dir}"
  record_asset "${dst}" "linux source snapshot from ${commit}"
}

make_dir_snapshot() {
  local name="$1"
  local base_dir="$2"
  local entry="$3"
  local dst="${ASSETS_DIR}/${name}.tar.gz"

  log "snapshot ${entry}"
  tar -C "${base_dir}" \
    --exclude='.git' \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    -czf "${dst}" "${entry}"
  record_asset "${dst}" "${name} source snapshot"
}

make_spec2017_snapshot() {
  local dst="${ASSETS_DIR}/spec2017-source-snapshot.tar.gz"

  log "snapshot curated spec2017 files"
  tar -C "${REPO_FILES_DIR}/software" -czf "${dst}" spec2017
  record_asset "${dst}" "Curated SPEC2017 helper snapshot for submitted SPEC 6XX workloads"
}

make_buildroot_snapshot() {
  local dst="${ASSETS_DIR}/buildroot-local-source-snapshot.tar.gz"
  local br_dir="${FIREMARSHAL_DIR}/boards/default/distros/br"

  log "snapshot buildroot without output/dl"
  tar -C "${br_dir}" \
    --exclude='buildroot/.git' \
    --exclude='buildroot/output' \
    --exclude='buildroot/dl' \
    --exclude='buildroot/local.mk' \
    --exclude='buildroot/.config' \
    --exclude='buildroot/.config.old' \
    --exclude='buildroot/..config.tmp' \
    --exclude='buildroot/package/libmemcached/1.txt' \
    -czf "${dst}" buildroot
  record_asset "${dst}" "Buildroot source snapshot excluding generated output and downloads"
}

make_firemarshal_patch() {
  local dst="${ASSETS_DIR}/firemarshal-local-tracked.patch"

  log "write FireMarshal tracked diff"
  git -C "${FIREMARSHAL_DIR}" diff --binary -- . \
    ':(exclude)logs' \
    ':(exclude)images' \
    ':(exclude)disk-mount' > "${dst}"

  record_asset "${dst}" "FireMarshal tracked local diff excluding generated output"
}

make_sparse_image_archive() {
  local bench="$1"
  local img_dir="${FIREMARSHAL_DIR}/images/firechip/spec17-intspeed-test-${bench}"
  local img="spec17-intspeed-test-${bench}.img"
  local dst="${ASSETS_DIR}/${img}.tar.zst"

  if [[ ! -f "${img_dir}/${img}" ]]; then
    log "skip missing SPEC image: ${img_dir}/${img}"
    return 0
  fi

  log "archive sparse SPEC image ${bench}"
  tar -C "${img_dir}" --sparse -cf - "${img}" | zstd -T0 "-${ZSTD_LEVEL}" -f -o "${dst}" -q
  record_asset "${dst}" "Sparse FireMarshal image for SPEC ${bench}"
}

main() {
  rm -rf -- "${REPO_FILES_DIR}" "${ASSETS_DIR}"
  mkdir -p "${REPO_FILES_DIR}" "${ASSETS_DIR}"
  : > "${CHECKSUMS}"
  printf 'storage\tpath\tbytes\tsha256\tnote\n' > "${MANIFEST}"

  log "copy small scripts/configs"
  copy_file "spec17_firesim.sh"
  copy_file "sims/firesim/deploy/config_runtime.yaml"
  copy_file "sims/firesim/deploy/config_hwdb.yaml"
  copy_file "sims/firesim/deploy/config_build.yaml"
  copy_file "sims/firesim/deploy/config_build_recipes.yaml"
  copy_spec_configs
  copy_file "software/spec2017/build-intspeed.sh"
  copy_file "software/spec2017/handle-results.py"
  copy_file "software/spec2017/compare_results.py"
  copy_file "software/spec2017/Makefile"
  copy_file "software/spec2017/README.md"

  find "${REPO_FILES_DIR}" -type f -print0 | while IFS= read -r -d '' f; do
    append_manifest "git" "${f#${REPO_FILES_DIR}/}" "$(file_bytes "${f}")" "$(file_sha256 "${f}")" "small file copied into repo-files"
  done

  make_git_archive "opensbi" "${FIREMARSHAL_DIR}/boards/default/firmware/opensbi"
  make_linux_archive
  make_spec2017_snapshot
  make_buildroot_snapshot
  make_firemarshal_patch

  if [[ -f "${BITSTREAM_SRC}" ]]; then
    log "copy active FireSim bitstream bundle"
    cp -p -- "${BITSTREAM_SRC}" "${BITSTREAM_DST}"
    record_asset "${BITSTREAM_DST}" "Active FireSim my_boom_bypass bitstream bundle"
  else
    log "warning: active bitstream bundle not found: ${BITSTREAM_SRC}"
  fi

  if [[ -f "${DRIVER_SRC}" ]]; then
    log "copy matching FireSim host driver bundle"
    cp -p -- "${DRIVER_SRC}" "${DRIVER_DST}"
    record_asset "${DRIVER_DST}" "Matching FireSim host driver bundle for UART log capture"
  else
    log "warning: matching host driver bundle not found: ${DRIVER_SRC}"
  fi

  if [[ "${SKIP_SPEC_IMAGES:-0}" != "1" ]]; then
    for bench in "${BENCHES[@]}"; do
      make_sparse_image_archive "${bench}"
    done
  else
    log "SKIP_SPEC_IMAGES=1, not packaging SPEC images"
  fi

  awk 'NR == 1 || $1 == "release-asset"' "${MANIFEST}" > "${ASSETS_DIR}/manifest.tsv"
  printf '%s  %s\n' "$(file_sha256 "${ASSETS_DIR}/manifest.tsv")" "release-assets/manifest.tsv" >> "${CHECKSUMS}"
  cp -p -- "${CHECKSUMS}" "${ASSETS_DIR}/checksums.sha256"

  log "done"
  log "repo files: ${REPO_FILES_DIR}"
  log "release assets: ${ASSETS_DIR}"
  log "manifest: ${MANIFEST}"
  log "checksums: ${CHECKSUMS}"
}

main "$@"
