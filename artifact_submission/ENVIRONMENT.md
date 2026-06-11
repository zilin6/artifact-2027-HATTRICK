# RTL and Embench Environment

This file records the reference software environment used for RTL simulation and
Embench runs. It intentionally focuses on the source-level RTL and Embench flow;
large release assets are restored separately.

## Chipyard Reference Version

The artifact RTL overlay is based on the official Chipyard release `1.13.0`
(tag commit `69eba860a352343e4ac6b6df0f3638a79a86ec78`). Use a complete Chipyard checkout at this release as
the clean base workspace before applying the artifact overlay. The overlay then
supplies the modified BOOM, Rocket Chip, inclusive-cache, testchipip, and
Chipyard configuration sources used by the submitted design.

## Setup

Run commands from a complete Chipyard workspace after applying the artifact RTL
overlay. The recommended sequence is:

```bash
/path/to/artifact/artifact_submission/rtl/prepare_chipyard_workspace.sh /path/to/chipyard-work
cd /path/to/chipyard-work
source ./env.sh
export CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1
```

The reference environment uses the Chipyard-managed toolchain and Verilator from
that workspace rather than system-wide tools. If the workspace has not cached sbt
artifacts yet, the first elaboration may download sbt/Scala dependencies unless
the reviewer pre-populates the standard Chipyard `.sbt` and `.ivy2` cache
locations. The artifact RTL flow supports `SmallBoomV3Config`; keep
`CHIPYARD_ARTIFACT_SMALLBOOM_ONLY=1` set when elaborating or building its
Verilator simulator.

## Host Runtime Link Compatibility

The Verilator simulator links against `libriscv.so` from the Chipyard RISC-V
tools environment. On the reference setup, that library requires host GLIBC
symbols up to `GLIBC_2.38`; on another setup it may require a different minimum
version depending on how `riscv-tools` was built.

If elaboration and firtool complete but the final simulator link fails with an
error such as `undefined reference to statx@GLIBC_2.28` or `GLIBC_x.y not found`,
treat it as a host runtime/toolchain compatibility issue rather than an RTL
elaboration failure. Use a host/container with a compatible glibc, or rebuild
Chipyard's `riscv-tools`/`riscv-isa-sim` in that workspace so `libriscv.so`
matches the local host runtime and sysroot.

## Reference Versions

| Component | Reference version | Used for |
| --- | --- | --- |
| Verilator | `5.022 2024-02-24 rev conda-forge build 1` | Verilator RTL simulator builds and runs |
| RISC-V bare-metal GCC | `riscv64-unknown-elf-gcc 13.2.0 (gc891d8dc23e)` | Embench binaries and cache-crypto bare-metal tests |
| Python | `3.10.14` | Embench runner scripts and helper scripts |
| OpenJDK | `20.0.2-internal` | Chipyard/Chisel elaboration |
| sbt | project `1.8.2`, runner `1.12.9` | Chipyard/Chisel elaboration |
| GNU Make | `4.4.1` | RTL simulator and benchmark build orchestration |

## Sanity Checks

After `source ./env.sh`, these commands should resolve to the Chipyard-managed
environment and report versions compatible with the table above:

```bash
verilator --version
riscv64-unknown-elf-gcc --version
python --version
java -version
sbt --version
make --version
```

Small patch-level differences in Python, Java, or Make are usually acceptable.
Use the listed Verilator and RISC-V GCC versions when reproducing cycle counts or
RTL regression behavior.
