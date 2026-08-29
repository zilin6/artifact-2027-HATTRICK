# Crypto-Exec Test Harness

This directory hosts a parallel test tree for running existing assembly
`rv64mi` tests under an encrypted I$ execution flow.

The harness keeps a small plaintext bootstrap in `_start`, then:

1. Initializes a local counter backing store for the encrypted text region.
2. Temporarily enables the D$ crypto path.
3. Rewrites the whole encrypted text region line-by-line in place so every
   executable cache line becomes ciphertext.
4. Turns on the I$ crypto bit and jumps into the encrypted trap vector and
   test body.

The original upstream-style test sources are not modified in place. Each test
in [`rv64mi`](./rv64mi) is a thin wrapper that includes the corresponding file
from `toolchains/riscv-tools/riscv-tests/isa/rv64mi/`, but compiles it against
the local [`include/riscv_test.h`](./include/riscv_test.h) override.

## Build

From this directory:

```bash
make
```

To build a single test:

```bash
make rv64mi-ce-access
make rv64mi-ce-cache-rw-tiny
```

## Run On Verilator

Example: run `rv64mi-ce-icache-crypto-jump`.

1. Load the Chipyard environment:

```bash
cd /path/to/chipyard
source ./env.sh
```

2. Build the test binary:

```bash
make -C /path/to/chipyard/generators/boom/cryptoexec_tests -j1 \
  rv64mi-ce-icache-crypto-jump
```

3. Rebuild the Verilator simulator only if RTL changed since the last build:

```bash
cd /path/to/chipyard/sims/verilator
make CONFIG=SmallBoomV3Config -j2
```

If the RTL did not change, skip this step.

4. Run the test in fast mode:

```bash
cd /path/to/chipyard/sims/verilator
RUN_TS=$(date +%F_%H-%M-%S)
LD_LIBRARY_PATH=/path/to/chipyard/tools/DRAMSim2:/path/to/chipyard/sims/verilator:$LD_LIBRARY_PATH \
make CONFIG=SmallBoomV3Config -j2 \
  BINARY=/path/to/chipyard/generators/boom/cryptoexec_tests/rv64mi-ce-icache-crypto-jump \
  LOADMEM=1 \
  TIMEOUT_CYCLES=1000000 \
  EXTRA_SIM_OUT_NAME=${RUN_TS} \
  run-binary-fast
```

`run-binary-fast` avoids the slow instruction-disassembly path, and
`LOADMEM=1` uses the direct `+loadmem` memory preload path.

With `EXTRA_SIM_OUT_NAME=${RUN_TS}`, the simulation log is stored with a
timestamped filename under `sims/verilator/output/...`, for example:

```text
.../rv64mi-ce-icache-crypto-jump.2026-04-03_14-30-00.log
```

The `run-binary-fast` rule now records both simulator `stdout` and `stderr`
into that timestamped `.log`, so common RTL `printf`/`$display` output and
assert/error text are captured together in one file.

`TIMEOUT_CYCLES=1000000` maps to `+max-cycles=1000000` in the generated
simulator invocation.

You can replace `rv64mi-ce-icache-crypto-jump` with any other test binary in
this directory and reuse the same flow.

## Scope

This harness currently targets the assembly `rv64mi` test family. Standalone
programs under `../../tests/` are not auto-wrapped here yet because they do not
share the `riscv-tests` macro/entry contract.



  /path/to/chipyard/sims/verilator/run-cache-crypto-regression.sh

  如果这次改了 RTL，也可以让它顺手重编 simulator：
  如果只想跑某几个点，也可以直接跟测试名：

  /path/to/chipyard/sims/verilator/run-cache-crypto-regression.sh \
    rv64mi-ce-cache-rw-reenc-counter-wb \
    rv64mi-ce-cache-rw-reenc-held-replay-counter
