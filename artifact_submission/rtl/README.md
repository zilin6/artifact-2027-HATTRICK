# RTL Source Artifact

This directory contains a source-only RTL snapshot for the submitted design. It
includes the current working-tree changes in BOOM, Rocket Chip,
inclusive-cache, and Chipyard. It is intentionally copied without Git metadata,
generated build products, logs, waveforms, simulator binaries, or bitstream
files.

The recommended RTL build flow is to restore this snapshot onto an official
Chipyard `1.13.0` workspace. The official Chipyard commit is the reproducible
base; artifact-specific RTL changes are provided by the committed files in this
repository. The flow does not rely on local-only BOOM/Rocket submodule commits
being fetchable by reviewers.

## Layout

```text
prepare_chipyard_workspace.sh  Restore artifact RTL onto a full Chipyard tree
chipyard/
  build.sbt
  common.mk
  variables.mk
  env.sh                       Reference only; use target Chipyard env.sh
  project/
  scripts/
  generators/
    chipyard/
    boom/
    rocket-chip/
    rocket-chip-inclusive-cache/
    testchipip/
  sims/
    verilator/
    firesim/deploy/
```

## Source Versions

`SOURCE_VERSION.tsv` records the source identifiers used by this artifact. The
clean base workspace is the official Chipyard release `1.13.0` (tag commit
`69eba860a352343e4ac6b6df0f3638a79a86ec78`). For strict reproduction, prepare a complete Chipyard workspace
at that release before applying the artifact restore script. Local-only
submodule commit IDs are intentionally not required; the artifact repository
contains the submitted RTL source trees that differ from the base release.

## Recommended Build Flow

Prepare or restore a complete Chipyard workspace first. Use official Chipyard
release `1.13.0` (tag commit `69eba860a352343e4ac6b6df0f3638a79a86ec78`) for the reference flow. The target workspace
should already contain Chipyard support tools and submodules such as
`tools/DRAMSim2`, `tools/axe`, `tools/torture`, `generators/tracegen`, the
Chipyard-managed conda environment, and any sbt/ivy cache needed for local
compilation.

Then restore the artifact RTL sources onto that workspace:

```bash
/path/to/artifact/artifact_submission/rtl/prepare_chipyard_workspace.sh /path/to/chipyard-work
cd /path/to/chipyard-work
source ./env.sh
cd sims/verilator
CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1 make CONFIG=SmallBoomV3Config -j8
```

The restore script intentionally keeps the target workspace's `env.sh`, support
tools, and unmodified generator subtrees from official Chipyard `1.13.0`. It
replaces the artifact-owned generator subtrees (`chipyard`, `boom`,
`rocket-chip`, `rocket-chip-inclusive-cache`, and `testchipip`) so stale files
from the base release are not compiled together with artifact sources.

In this artifact mode, `build.sbt` also accepts source-only artifact generator trees without `.git` metadata.

Set `CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1` for artifact RTL builds. This
SmallBoom-only build mode keeps the BOOM/Rocket/TestChipIP/inclusive-cache path
needed by `SmallBoomV3Config`, disables discovery of unrelated optional
generators, and filters Chipyard example/config sources that reference those
unused generators.

If this command reaches Verilog generation but fails while linking the final
`simulator-chipyard.harness-SmallBoomV3Config` binary against `libriscv.so`, see
`artifact_submission/ENVIRONMENT.md` for host GLIBC/toolchain compatibility
notes. That failure mode is separate from RTL elaboration.

## Direct Snapshot Builds

Building directly inside `artifact_submission/rtl/chipyard` is not the supported
flow because the artifact excludes large or unmodified Chipyard support content.
Use the restore flow above for RTL compilation and cache-crypto regression runs.

## Excluded Files

The snapshot excludes `.git`, generated `target` directories, generated Verilog,
simulator binaries, logs, waveforms, Vivado/FireSim build products, active
bitstream bundles, and unmodified heavyweight support/build outputs. Bitstreams
and FireSim driver bundles are distributed through the artifact release assets.
