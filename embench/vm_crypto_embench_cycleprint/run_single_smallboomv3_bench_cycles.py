#!/usr/bin/env python3

import argparse
import csv
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


BENCHMARK_MAX_CYCLES = {
    "aha-mont64": 9_000_000,
    "byte-store-stream": 5_000_000,
    "crc32": 18_000_000,
    "cubic": 7_000_000,
    "edn": 15_000_000,
    "huffbench": 16_000_000,
    "matmult-int": 14_000_000,
    "md5sum": 20_000_000,
    "minver": 3_000_000,
    "nbody": 1_000_000,
    "nettle-aes": 22_000_000,
    "nettle-sha256": 18_000_000,
    "nsichneu": 14_000_000,
    "picojpeg": 20_000_000,
    "primecount": 20_000_000,
    "qrduino": 19_000_000,
    "sglib-combined": 15_000_000,
    "slre": 11_000_000,
    "st": 1_000_000,
    "statemate": 60_000_000,
    "tarfind": 20_000_000,
    "ud": 14_000_000,
    "wikisort": 9_000_000,
}

PASS_RE = re.compile(r"\*\*\* PASSED \*\*\* Completed after\s+(\d+)\s+simulation cycles")
COMMIT_RE = re.compile(
    r"\[COMMIT\]\s+cycle=0x([0-9a-fA-F]+).*?pc=0x([0-9a-fA-F]+).*?inst=0x([0-9a-fA-F]+)"
)

MARKER_INSTRUCTIONS = {
    0x10000013: "orig_startup_end",
    0x10100013: "startup_end",
    0x10200013: "measure_start",
    0x10300013: "verify_start",
}
DEFAULT_EXCLUDED_BENCHMARKS = {"slre", "wikisort"}


@dataclass
class RunResult:
    benchmark: str
    status: str
    measure_start_cycle: int | None
    verify_start_cycle: int | None
    bench_cycles: int | None
    total_sim_cycles: int | None
    log_path: Path
    note: str = ""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run one or more vm_crypto_embench benchmarks on SmallBoomV3Config and print measured benchmark cycles from verbose marker logs."
    )
    parser.add_argument(
        "benchmarks",
        nargs="*",
        help="Optional benchmark names. If omitted, run all supported benchmarks except slre and wikisort.",
    )
    parser.add_argument(
        "--timeout-cycles",
        type=int,
        help="Override the default TIMEOUT_CYCLES for all selected benchmarks.",
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="Reuse the existing build directory instead of running ./build.sh first.",
    )
    parser.add_argument(
        "--log-path",
        help="Optional explicit path for the verbose simulator log. Only valid when running exactly one benchmark.",
    )
    parser.add_argument(
        "--summary-tsv",
        help="Optional explicit path for the batch summary TSV.",
    )
    parser.add_argument(
        "--include-skipped",
        action="store_true",
        help="When no benchmarks are named, include slre and wikisort in the default batch.",
    )
    return parser.parse_args()


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def embench_root() -> Path:
    return Path(__file__).resolve().parent


def shell_prefix() -> str:
    return (
        "if [ -n \"${CONDA_SH:-}\" ]; then source \"${CONDA_SH}\" >/dev/null 2>&1 || true; fi && "
        f"source {repo_root() / 'env.sh'} && "
    )


def run_shell(command: str, cwd: Path, log_path: Path | None = None) -> subprocess.CompletedProcess[str]:
    if log_path is None:
        return subprocess.run(
            ["bash", "-lc", command],
            cwd=cwd,
            text=True,
            capture_output=True,
            check=False,
        )

    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as handle:
        return subprocess.run(
            ["bash", "-lc", command],
            cwd=cwd,
            stdout=handle,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )


def ensure_built(skip_build: bool) -> None:
    if skip_build:
        return
    result = run_shell(f"{shell_prefix()}./build.sh", embench_root())
    if result.returncode != 0:
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        raise SystemExit(result.returncode)


def parse_marker_cycles(log_text: str) -> dict[str, int]:
    marker_cycles: dict[str, int] = {}
    for line in log_text.splitlines():
        match = COMMIT_RE.search(line)
        if not match:
            continue
        cycle = int(match.group(1), 16)
        inst = int(match.group(3), 16)
        name = MARKER_INSTRUCTIONS.get(inst)
        if name is not None:
            marker_cycles.setdefault(name, cycle)
    return marker_cycles


def parse_pass_cycles(log_text: str) -> int | None:
    match = PASS_RE.search(log_text)
    return int(match.group(1)) if match else None


def resolve_benchmarks(args: argparse.Namespace) -> list[str]:
    if args.benchmarks:
        unknown = sorted(set(args.benchmarks) - set(BENCHMARK_MAX_CYCLES))
        if unknown:
            known = ", ".join(sorted(BENCHMARK_MAX_CYCLES))
            raise SystemExit(f"Unknown benchmarks: {', '.join(unknown)}. Known benchmarks: {known}")
        return args.benchmarks

    selected = list(BENCHMARK_MAX_CYCLES)
    if not args.include_skipped:
        selected = [bench for bench in selected if bench not in DEFAULT_EXCLUDED_BENCHMARKS]
    return selected


def default_summary_tsv() -> Path:
    return embench_root() / "run-logs-cycle-markers" / "results.tsv"


def run_one_benchmark(bench: str, timeout_cycles: int, log_path: Path) -> RunResult:
    binary = embench_root() / "build" / bench
    if not binary.is_file():
        return RunResult(bench, "failure", None, None, None, None, log_path, f"missing binary: {binary}")

    cmd = (
        f"{shell_prefix()}"
        f"make -C {repo_root() / 'sims/verilator'} "
        f"CONFIG=SmallBoomV3Config "
        f"BINARY={binary} "
        f"LOADMEM=1 "
        f"BREAK_SIM_PREREQ=1 "
        f"TIMEOUT_CYCLES={timeout_cycles} "
        f"EXTRA_SIM_FLAGS=+verbose "
        f"run-binary-fast"
    )
    result = run_shell(cmd, repo_root(), log_path=log_path)

    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    if result.returncode != 0:
        return RunResult(bench, "failure", None, None, None, None, log_path, f"sim exit {result.returncode}")

    marker_cycles = parse_marker_cycles(log_text)
    missing = [name for name in ("measure_start", "verify_start") if name not in marker_cycles]
    if missing:
        return RunResult(bench, "failure", None, None, None, None, log_path, f"missing markers: {', '.join(missing)}")

    start_cycle = marker_cycles["measure_start"]
    end_cycle = marker_cycles["verify_start"]
    if end_cycle < start_cycle:
        return RunResult(
            bench,
            "failure",
            start_cycle,
            end_cycle,
            None,
            parse_pass_cycles(log_text),
            log_path,
            "non-monotonic markers",
        )

    total_cycles = parse_pass_cycles(log_text)
    return RunResult(
        bench,
        "success",
        start_cycle,
        end_cycle,
        end_cycle - start_cycle,
        total_cycles,
        log_path,
    )


def write_summary_tsv(path: Path, results: list[RunResult]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t")
        writer.writerow([
            "benchmark",
            "status",
            "measure_start_cycle",
            "verify_start_cycle",
            "bench_cycles",
            "total_sim_cycles",
            "log_path",
            "note",
        ])
        for result in results:
            writer.writerow([
                result.benchmark,
                result.status,
                result.measure_start_cycle if result.measure_start_cycle is not None else "",
                result.verify_start_cycle if result.verify_start_cycle is not None else "",
                result.bench_cycles if result.bench_cycles is not None else "",
                result.total_sim_cycles if result.total_sim_cycles is not None else "",
                str(result.log_path),
                result.note,
            ])


def print_result(result: RunResult) -> None:
    print(f"BENCHMARK={result.benchmark}")
    print(f"STATUS={result.status}")
    if result.measure_start_cycle is not None:
        print(f"MEASURE_START_CYCLE={result.measure_start_cycle}")
    if result.verify_start_cycle is not None:
        print(f"VERIFY_START_CYCLE={result.verify_start_cycle}")
    if result.bench_cycles is not None:
        print(f"BENCH_CYCLES={result.bench_cycles}")
    if result.total_sim_cycles is not None:
        print(f"TOTAL_SIM_CYCLES={result.total_sim_cycles}")
    print(f"LOG_PATH={result.log_path}")
    if result.note:
        print(f"NOTE={result.note}")
    print()


def main() -> int:
    args = parse_args()
    benchmarks = resolve_benchmarks(args)
    if args.log_path and len(benchmarks) != 1:
        raise SystemExit("--log-path is only supported when running exactly one benchmark.")

    ensure_built(args.skip_build)
    summary_tsv = Path(args.summary_tsv) if args.summary_tsv else default_summary_tsv()

    results: list[RunResult] = []
    failures = 0
    for bench in benchmarks:
        timeout_cycles = args.timeout_cycles or BENCHMARK_MAX_CYCLES[bench]
        log_path = Path(args.log_path) if args.log_path else (
            embench_root() / "run-logs-cycle-markers" / f"{bench}.full.log"
        )
        result = run_one_benchmark(bench, timeout_cycles, log_path)
        results.append(result)
        print_result(result)
        if result.status != "success":
            failures += 1

    write_summary_tsv(summary_tsv, results)
    print(f"SUMMARY_TSV={summary_tsv}")
    print(f"TOTAL_BENCHMARKS={len(results)}")
    print(f"FAILED_BENCHMARKS={failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
