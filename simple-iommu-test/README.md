# SimpleIOMMU Test Package

This folder collects the IOMMU-focused test points and the build/run flow for the U250 native FireSim setup.
It is intentionally separate from the Linux/OpenSBI source trees.

## Relevant Source Files

- `artifact_submission/rtl/chipyard/generators/chipyard/src/main/scala/peripherals/mydma/MyDMA.scala`
- `artifact_submission/rtl/chipyard/generators/chipyard/src/main/scala/peripherals/iommu/SimpleIOMMU.scala`
- `artifact_submission/rtl/chipyard/generators/chipyard/src/main/scala/peripherals/iommu/IOMMUConfig.scala`
- `artifact_submission/rtl/chipyard/generators/chipyard/src/main/scala/config/BoomConfigs.scala`
- `artifact_submission/rtl/chipyard/generators/chipyard/src/main/scala/peripherals/mysubsystem/MyPeriphery.scala`

## Test Points

1. DMA round-trip with IOMMU enabled.
   - `CONFIG_MYDMA_PSEUDO_DISK=y`
   - `CONFIG_MYDMA_SIMPLE_IOMMU=y`
   - Expected: `verify=ok`, `total rc=0`

2. IOMMU fault handling.
   - unmapped IOVA
   - read on write-only mapping
   - write on read-only mapping
   - out-of-range IOVA
   - Expected: each negative case is rejected

3. Bypass comparison.
   - `CONFIG_MYDMA_SIMPLE_IOMMU=n`
   - Expected: the same user interface still works, but translation is bypassed

## Build

Linux:

```bash
cd /home/maochenyang/new_chipyard/chipyard/software/firemarshal/boards/default/linux
make -j2 ARCH=riscv \
  CROSS_COMPILE=riscv64-unknown-linux-gnu- \
  vmlinux arch/riscv/boot/Image
```

OpenSBI:

```bash
cd /home/maochenyang/new_chipyard/chipyard/software/firemarshal/boards/default/firmware/opensbi
make -j2 PLATFORM=generic \
  CROSS_COMPILE=riscv64-unknown-linux-gnu- \
  FW_PAYLOAD_PATH=build/platform/generic/firmware/fw_payload.elf
```

FireMarshal workload:

```bash
cd /home/maochenyang/new_chipyard/chipyard/software/firemarshal
export SUPERMIN_KERNEL=/tmp/firemarshal-vmlinuz
export LIBGUESTFS_BACKEND=direct
../../.conda-env/bin/python ./marshal build \
  example-workloads/exit-debug-hello.json
```

## Run

```bash
cd /home/maochenyang/new_chipyard/u250-hot-programming-lutiancheng/native-firesim
HELLO_PROTECTED=0 ./run-single-hello.sh dma
```

That wrapper rebuilds the workload, runs `infrasetup`, programs the U250, and launches `runworkload`.

## UART Logs

Latest run:

```text
/home/maochenyang/new_chipyard/u250-hot-programming-lutiancheng/native-firesim/single-runs/dma/<timestamp>/uartlog
```

Live log:

```text
/home/maochenyang/new_chipyard/u250-hot-programming-lutiancheng/native-firesim/run-farm/sim_slot_0/uartlog
```

Useful grep:

```bash
LC_ALL=C grep -a -E \
  'SimpleIOMMU|DMA (submit|completion|error)|verify=|fault-test|total rc|PASSED' \
  /path/to/uartlog
```

## Debug Printf

The DMA model already has a runtime plusarg gate:

```text
+simple_iommu_debug=1
```

It only prints when the active Chipyard config sets `MyDMAParams(debugPrintf = true)`.

