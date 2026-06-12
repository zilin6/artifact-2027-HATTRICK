# Artifact Repository

This repository contains the source-level and configuration files needed to
reproduce the submitted SPEC CPU2017 speed experiments on FireSim.

The supported benchmarks are:

- `600.perlbench_s`
- `602.gcc_s`
- `605.mcf_s`
- `620.omnetpp_s`
- `623.xalancbmk_s`
- `631.deepsjeng_s`
- `641.leela_s`
- `648.exchange2_s`

Large binary artifacts are distributed as release assets, not as normal Git
files. Download all files from the release asset list before running the
experiments, then verify them with `checksums.sha256`.

Anonymous binary release assets are available at:

https://github.com/HATTRICK11111/artifact-2027-HATTRICK/releases/tag/artifact-v1

## Repository Layout

```text
artifact_submission/          Artifact packaging notes and helper script
artifact_submission/ENVIRONMENT.md  RTL/Verilator/Embench tool versions
artifact_submission/rtl/      Source-only RTL snapshot and manifest
embench/                         Source-only VM/crypto Embench variants
sims/firesim/deploy/          FireSim runtime, build, HWDB configs
software/spec2017/            SPEC helper scripts and workload JSON files
spec17_firesim.sh             Main run script
```

## Release Assets

The release assets contain:

- OpenSBI and Linux source snapshots.
- FireMarshal and Buildroot support snapshots.
- Source-only Embench VM/crypto microbenchmark variants.
- The FireSim bitstream bundle.
- The matching FireSim host driver bundle for FPGA simulation and UART output.
- Sparse compressed FireMarshal disk images for the eight supported SPEC
  workloads.
- `manifest.tsv` and `checksums.sha256`.

## Basic Restore Flow

Download all release assets into a local `release-assets/` directory and verify:

```bash
sha256sum -c release-assets/checksums.sha256
```

Then restore source snapshots and SPEC disk images as described in
`artifact_submission/README.md`. See `embench/README.md` for build and run commands
for the VM/crypto Embench variants.

## RTL and Embench Environment

Use a complete Chipyard workspace for RTL simulator builds and apply the artifact
RTL overlay before compiling:

```bash
/path/to/artifact/artifact_submission/rtl/prepare_chipyard_workspace.sh /path/to/chipyard-work
cd /path/to/chipyard-work
source ./env.sh
export CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1
```

Base Chipyard release: `1.13.0` (tag commit `69eba860a352343e4ac6b6df0f3638a79a86ec78`). The
reference Verilator, RISC-V GCC, Java, sbt, Python, and Make versions are
listed in `artifact_submission/ENVIRONMENT.md`. Detailed RTL build instructions
are in `artifact_submission/rtl/README.md`.


## Anonymization Notes

The committed artifact avoids author-specific local paths and personal repository
URLs. Paths in documentation use placeholders such as `/path/to/chipyard`, and
large generated outputs remain outside Git so they can be distributed through the
anonymous artifact channel.

The FireSim HWDB entry must reference both the bitstream and the matching host
driver bundle:

```yaml
my_boom_bypass:
    bitstream_tar: file:///absolute/path/to/firesim-my_boom_bypass-2026-06-09.tar.gz
    driver_tar: file:///absolute/path/to/firesim-my_boom_bypass-2026-06-09-driver-bundle.tar.gz
```
