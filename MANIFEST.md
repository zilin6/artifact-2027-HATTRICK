# Repository Manifest

Files committed to Git are intentionally limited to scripts, configuration,
documentation, and small workload descriptors.

## Git Files

- `README.md`
- `MANIFEST.md`
- `spec17_firesim.sh`
- `artifact_submission/`
- `artifact_submission/ENVIRONMENT.md`
- `artifact_submission/rtl/prepare_chipyard_workspace.sh`
- `embench/`
- `sims/firesim/deploy/config_hwdb.yaml`
- `sims/firesim/deploy/config_runtime.yaml`
- `sims/firesim/deploy/config_build.yaml`
- `sims/firesim/deploy/config_build_recipes.yaml`
- `software/spec2017/`

The `embench/` tree is source-only. Build outputs, simulator logs, run logs,
Python caches, and local machine paths are intentionally excluded.

## Release Assets

Upload the files generated under the original workspace's
`artifact_submission/dist/release-assets/` as release assets. They must not be
committed to Git history.

Expected release assets:

- `opensbi-e60248380a6a.tar.gz`
- `linux-671eb8b7a4bd.tar.gz`
- `buildroot-local-source-snapshot.tar.gz`
- `firemarshal-local-tracked.patch`
- `spec2017-source-snapshot.tar.gz`
- `firesim-my_boom_bypass-2026-06-09.tar.gz`
- `firesim-my_boom_bypass-2026-06-09-driver-bundle.tar.gz`
- `spec17-intspeed-test-600.img.tar.zst`
- `spec17-intspeed-test-602.img.tar.zst`
- `spec17-intspeed-test-605.img.tar.zst`
- `spec17-intspeed-test-620.img.tar.zst`
- `spec17-intspeed-test-623.img.tar.zst`
- `spec17-intspeed-test-631.img.tar.zst`
- `spec17-intspeed-test-641.img.tar.zst`
- `spec17-intspeed-test-648.img.tar.zst`
- `manifest.tsv`
- `checksums.sha256`

