# VM Embench Cycleprint

This directory is a plain-VM cycle-marker Embench variant.

- `./build.sh` builds into `build/` with `-DVM_PLAIN_NO_ADDR_CRYPTO=1` by default.
- The `VM_PLAIN_NO_ADDR_CRYPTO` startup path enters the benchmark at the normal SV39 virtual base (`0x40000000`) with all custom cache/address crypto control CSRs cleared.
- `./run_single_smallboomv3_bench_cycles.py` runs the `build/<benchmark>` binaries and extracts benchmark cycles from verbose commit markers.

Example:

```sh
cd /path/to/chipyard/software/vm_embench_cycleprint
./build.sh
./run_single_smallboomv3_bench_cycles.py --skip-build st
```
