# RTL Source Manifest

The RTL artifact is stored as source files under `artifact_submission/rtl/chipyard`.
No patch files are used for the primary artifact. For compilation, apply the
snapshot as an overlay onto a complete Chipyard workspace using
`prepare_chipyard_workspace.sh`.

Required checked-in Verilog resource files are included even when upstream
`.gitignore` patterns ignore generic `*.v` files.

## Included Source Trees

| Path | Purpose |
| --- | --- |
| `prepare_chipyard_workspace.sh` | Applies the artifact RTL sources to a complete Chipyard workspace. |
| `chipyard/generators/chipyard` | Chipyard configuration, including SmallBoomV3 crypto configuration. |
| `chipyard/generators/boom` | BOOM V3 core RTL with cache/address crypto control and key handling. |
| `chipyard/generators/rocket-chip` | Rocket-chip CSR, key-engine, subsystem, and dependency sources. |
| `chipyard/generators/rocket-chip-inclusive-cache` | Inclusive-cache RTL and source-level tests for crypto/re-encrypt behavior. |
| `chipyard/generators/testchipip` | Testchipip simulation support including DRAMSim2 line-aligned transactions. |
| `chipyard/sims/verilator` | Verilator makefiles and cache-crypto regression scripts, without generated output. |
| `chipyard/sims/common-sim-flags.mk` | Shared simulator compile/link flags required by Verilator makefiles. |
| `chipyard/tools/torture.mk` | Required Chipyard make fragment included by `common.mk`. |
| `chipyard/generators/tracegen/tracegen.mk` | Required tracegen make fragment included by `common.mk`. |
| `chipyard/sims/firesim/deploy` | FireSim deploy configuration files, without generated build results. |

## Excluded Generated Content

The following are intentionally excluded from Git:

- `.git` metadata and remotes
- `target/`, `generated-src/`, and simulator build outputs
- `simulator-*`, waveforms, logs, and run directories
- Vivado and FireSim generated result directories
- bitstream and driver bundles, which are release assets


The supported build flow relies on the target Chipyard workspace for unmodified
support submodules and build infrastructure such as `tools/DRAMSim2`, `tools/axe`,
`tools/torture`, sbt/ivy caches, and the Chipyard-managed environment. Set
`CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1` so `build.sbt` compiles only the
SmallBoomV3 artifact path and skips unrelated optional generators.

## Anonymization

Documentation and scripts in this snapshot avoid personal machine paths and
non-anonymous repository URLs. Placeholder paths use `/path/to/chipyard` where a
local workspace path is needed.
