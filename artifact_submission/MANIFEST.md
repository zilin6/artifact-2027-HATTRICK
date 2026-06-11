# Artifact Manifest

This manifest describes what should be submitted and how each item should be
stored on GitHub. Paths are relative to `/path/to/chipyard`
unless noted otherwise.

| Item | Current path | Size observed | Storage |
| --- | --- | ---: | --- |
| Main SPEC FireSim runner | `spec17_firesim.sh` | 12 KiB | Git |
| RTL/Embench environment | `artifact_submission/ENVIRONMENT.md` | small | Git |
| RTL overlay helper | `artifact_submission/rtl/prepare_chipyard_workspace.sh` | small | Git |
| FireSim runtime config | `sims/firesim/deploy/config_runtime.yaml` | small | Git |
| FireSim HWDB config | `sims/firesim/deploy/config_hwdb.yaml` | small | Git |
| FireSim build recipes | `sims/firesim/deploy/config_build_recipes.yaml` | small | Git |
| FireSim build config | `sims/firesim/deploy/config_build.yaml` | small | Git |
| SPEC2017 helper scripts/configs | curated `software/spec2017` files for the submitted 600/602/605/620/623/631/641/648 workloads | small | Git and release asset source snapshot |
| FireMarshal local patch | `software/firemarshal` tracked diff | < 1 MiB | Git or release asset patch |
| Buildroot local source snapshot | `software/firemarshal/boards/default/distros/br/buildroot` excluding `output`, `dl`, `.git` | about 20 MiB | Release asset |
| RTL source artifact | `artifact_submission/rtl/` | about 30 MiB | Git source snapshot |
| OpenSBI source snapshot | `software/firemarshal/boards/default/firmware/opensbi` | 98 MiB working tree, smaller source archive | Release asset |
| Linux source snapshot | `software/firemarshal/boards/default/linux` | 2.9 GiB working tree, source archive only | Release asset |
| Active bitstream bundle | `sims/firesim/deploy/results-build/2026-06-09--17-37-08-my_boom_bypass/.../firesim.tar.gz` | 32 MiB | Release asset or Git |
| Matching host driver bundle | `sims/firesim/sim/output/xilinx_alveo_u200/xilinx_alveo_u200-firesim-FireSim-FireSimMyBoomCounterTail2GiBConfig-BaseXilinxAlveoU200Config/driver-bundle.tar.gz` | 81 MiB | Release asset |
| SPEC 600 image | `software/firemarshal/images/firechip/spec17-intspeed-test-600/spec17-intspeed-test-600.img` | 3.0 GiB apparent, 335 MiB allocated | Release asset as sparse tar.zst |
| SPEC 602 image | `software/firemarshal/images/firechip/spec17-intspeed-test-602/spec17-intspeed-test-602.img` | 3.0 GiB apparent, 335 MiB allocated | Release asset as sparse tar.zst |
| SPEC 605 image | `software/firemarshal/images/firechip/spec17-intspeed-test-605/spec17-intspeed-test-605.img` | 3.0 GiB apparent, 338 MiB allocated | Release asset as sparse tar.zst |
| SPEC 620 image | `software/firemarshal/images/firechip/spec17-intspeed-test-620/spec17-intspeed-test-620.img` | 3.0 GiB apparent, 341 MiB allocated | Release asset as sparse tar.zst |
| SPEC 623 image | `software/firemarshal/images/firechip/spec17-intspeed-test-623/spec17-intspeed-test-623.img` | 3.0 GiB apparent, 335 MiB allocated | Release asset as sparse tar.zst |
| SPEC 631 image | `software/firemarshal/images/firechip/spec17-intspeed-test-631/spec17-intspeed-test-631.img` | 3.0 GiB apparent, 335 MiB allocated | Release asset as sparse tar.zst |
| SPEC 641 image | `software/firemarshal/images/firechip/spec17-intspeed-test-641/spec17-intspeed-test-641.img` | 3.0 GiB apparent, 335 MiB allocated | Release asset as sparse tar.zst |
| SPEC 648 image | `software/firemarshal/images/firechip/spec17-intspeed-test-648/spec17-intspeed-test-648.img` | 3.0 GiB apparent, 335 MiB allocated | Release asset as sparse tar.zst |

The generated `dist/manifest.tsv` produced by `pack_artifact.sh` records the
packaged Git files and release assets. The copy under
`dist/release-assets/manifest.tsv` records only the release assets to upload.
