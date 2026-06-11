# Embench Microbenchmark Variants

This directory contains the source-level Embench variants used for the VM and
cache/address-crypto experiments. Generated binaries, simulator logs, run logs,
Python caches, and background-run directories are intentionally excluded.

## Variants

- `vm_crypto_embench_cycleprint/`: VM-enabled Embench variant with cache/address
  crypto enabled and cycle-marker reporting.
- `vm_embench_cycleprint/`: VM-enabled baseline that enters the benchmark at the
  SV39 virtual base while keeping the custom crypto CSRs cleared.
- `vm_crypto_embench_su_split/`: split supervisor/user-context variant used for
  two-context key-isolation experiments.

## Workspace Setup

The run scripts expect each variant to live under a complete Chipyard workspace's
`software/` directory because they derive the Chipyard root from their own path.
After preparing Chipyard and applying the RTL overlay, copy the variants into the
workspace:

```bash
cp -a /path/to/artifact/embench/vm_crypto_embench_cycleprint /path/to/chipyard/software/
cp -a /path/to/artifact/embench/vm_embench_cycleprint /path/to/chipyard/software/
cp -a /path/to/artifact/embench/vm_crypto_embench_su_split /path/to/chipyard/software/
```

Then load the Chipyard-managed environment:

```bash
cd /path/to/chipyard
source ./env.sh
```

## Build

Build each variant in place under `/path/to/chipyard/software/`:

```bash
cd /path/to/chipyard/software/vm_crypto_embench_cycleprint
./build.sh

cd /path/to/chipyard/software/vm_embench_cycleprint
./build.sh

cd /path/to/chipyard/software/vm_crypto_embench_su_split
./build.sh
```

The `build_vm_noaddr.sh` scripts build the no-address-crypto binary set when a
variant provides that mode. Runtime logs and generated binaries should remain
local working files and are not part of this Git artifact.

## Non-Verbose Smoke Run

Use a non-verbose run for a quick pass/fail check or total simulation cycle
count. This mode does not print BOOM `[COMMIT]` lines, so it is not suitable for
cycle-marker range extraction.

```bash
make -C /path/to/chipyard/sims/verilator \
  CONFIG=SmallBoomV3Config \
  BINARY=/path/to/chipyard/software/vm_crypto_embench_cycleprint/build/cubic \
  LOADMEM=1 \
  BREAK_SIM_PREREQ=1 \
  TIMEOUT_CYCLES=5000000 \
  run-binary-fast
```

## Commit-Log Cycleprint Run

Use `EXTRA_SIM_FLAGS=+verbose` when the log must contain BOOM `[COMMIT]` lines.
The commit log is the normal cycleprint path used to locate marker instructions
and compute benchmark cycle ranges. This measurement flow uses `+verbose` only.

```bash
mkdir -p /path/to/chipyard/software/vm_crypto_embench_cycleprint/run-logs-cycle-markers

make -C /path/to/chipyard/sims/verilator \
  CONFIG=SmallBoomV3Config \
  BINARY=/path/to/chipyard/software/vm_crypto_embench_cycleprint/build/cubic \
  LOADMEM=1 \
  BREAK_SIM_PREREQ=1 \
  TIMEOUT_CYCLES=7000000 \
  EXTRA_SIM_FLAGS=+verbose \
  run-binary-fast \
  > /path/to/chipyard/software/vm_crypto_embench_cycleprint/run-logs-cycle-markers/cubic.full.log 2>&1
```

The resulting `.full.log` contains the expanded simulator command followed by
`[COMMIT]` entries. The marker instructions are parsed from those commit lines
to compute the measured benchmark interval.

## Helper Scripts

For individual commit-log cycle measurements, use:

```bash
cd /path/to/chipyard/software/vm_crypto_embench_cycleprint
./run_single_smallboomv3_bench_cycles.py cubic --skip-build
```

This script runs `make ... EXTRA_SIM_FLAGS=+verbose run-binary-fast` internally.
By default it writes:

- `run-logs-cycle-markers/<bench>.full.log`
- `run-logs-cycle-markers/results.tsv`

For the two-stage breakdown flow, use:

```bash
cd /path/to/chipyard/software/vm_crypto_embench_cycleprint
./run_smallboomv3_embench_breakdown.py --skip-build cubic
```

The breakdown script first performs a non-verbose discovery pass, then reruns
passing benchmarks with `EXTRA_SIM_FLAGS=+verbose`. The verbose pass produces the
commit log used for marker-based cycle range extraction.

## Cycle Range Calculation

The preferred way to compute cycle ranges is to use the helper scripts rather
than manually subtracting cycles. The helpers parse `[COMMIT]` lines from the
verbose log and report the measured interval directly.

For a single benchmark, `run_single_smallboomv3_bench_cycles.py` reports:

- `MEASURE_START_CYCLE`: cycle of the committed `measure_start` marker.
- `VERIFY_START_CYCLE`: cycle of the committed `verify_start` marker.
- `BENCH_CYCLES`: `VERIFY_START_CYCLE - MEASURE_START_CYCLE`.

The marker instructions are fixed NOP encodings inserted into the benchmark
startup/measurement harness:

| Marker instruction | Marker name | Meaning |
| --- | --- | --- |
| `inst=0x10000013` | `orig_startup_end` | End of the original startup path. |
| `inst=0x10100013` | `startup_end` | End of VM/crypto setup before warm-up. |
| `inst=0x10200013` | `measure_start` | Start of the measured benchmark region. |
| `inst=0x10300013` | `verify_start` | End of the measured region; verification begins. |

Manual inspection should match the helper output. For example:

```bash
rg '\[COMMIT\].*inst=0x10200013|\[COMMIT\].*inst=0x10300013' \
  /path/to/chipyard/software/vm_crypto_embench_cycleprint/run-logs-cycle-markers/cubic.full.log
```

Convert the two `cycle=0x...` values from hexadecimal to integers, then compute:

```text
bench_cycles = verify_start_cycle - measure_start_cycle
```

The breakdown helper reports additional intervals from the same markers:

- `added_crypto_copy` for crypto variants, or `vm_setup` for the VM baseline:
  `startup_end - orig_startup_end`.
- `warm`: `measure_start - startup_end`.
- `measured`: `verify_start - measure_start`.
- `verify_tail`: `total_sim_cycles - verify_start`.

## Output Policy

Do not commit generated Embench outputs. Keep the following local to the review
workspace:

- `build/` and `build_vm_noaddr/`
- `run-logs-*` and `run-logs-cycle-markers/`
- `*.full.log`, `*.launcher.log`, `results.tsv`, and generated reports
