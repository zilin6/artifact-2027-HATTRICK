#!/usr/bin/env python3

import argparse
import csv
import re
import subprocess
import sys
from collections import OrderedDict
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Dict, Iterable, List, Optional


BENCHMARK_MAX_CYCLES = OrderedDict([
    ("aha-mont64", 9_000_000),
    ("crc32", 18_000_000),
    ("cubic", 7_000_000),
    ("edn", 15_000_000),
    ("huffbench", 16_000_000),
    ("matmult-int", 14_000_000),
    ("minver", 3_000_000),
    ("nbody", 1_000_000),
    ("nettle-aes", 22_000_000),
    ("nettle-sha256", 18_000_000),
    ("nsichneu", 14_000_000),
    ("picojpeg", 20_000_000),
    ("qrduino", 19_000_000),
    ("sglib-combined", 15_000_000),
    ("slre", 11_000_000),
    ("st", 1_000_000),
    ("statemate", 60_000_000),
    ("ud", 14_000_000),
    ("wikisort", 9_000_000),
])

PASS_RE = re.compile(r"\*\*\* PASSED \*\*\* Completed after\s+(\d+)\s+simulation cycles")
MARKER_RE = re.compile(
    r"\[EMBENCH-MARKER\]\s+cycle=0x([0-9a-fA-F]+)\s+slot=\d+\s+pc=0x([0-9a-fA-F]+)\s+inst=0x([0-9a-fA-F]+)\s+name=([a-z_]+)"
)

REQUIRED_MARKERS = ("startup_end", "measure_start", "verify_start")
BENCHMARK_ORDER = list(BENCHMARK_MAX_CYCLES.keys())


@dataclass
class DiscoveryResult:
    bench: str
    status: str
    cycles: Optional[int]
    reason: str
    max_cycles: int
    log_path: Path


@dataclass
class BreakdownResult:
    bench: str
    status: str
    total: Optional[int]
    startup_orig: Optional[int]
    vm_setup: Optional[int]
    warm: Optional[int]
    measured: Optional[int]
    verify_tail: Optional[int]
    log_path: Path
    note: str = ""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run SmallBoomV3 plain-VM embench and keep a unified cycle breakdown report."
    )
    parser.add_argument("--skip-build", action="store_true", help="Reuse the existing embench build directory.")
    parser.add_argument(
        "--skip-discovery",
        action="store_true",
        help="Reuse an existing discovery TSV and only rerun the verbose pass set.",
    )
    parser.add_argument("--benchmarks", help="Optional comma-separated subset of benchmarks to run.")
    parser.add_argument("--run-date", help="Reuse a specific tag for logs/report instead of today's date.")
    parser.add_argument(
        "--max-cycles",
        type=int,
        help="Override all per-benchmark max cycle limits with one value.",
    )
    parser.add_argument(
        "--append-existing",
        action="store_true",
        help="Reuse existing TSV/report files and only run benchmarks not already recorded.",
    )
    return parser.parse_args()


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def repo_name() -> str:
    return repo_root().name


def embench_root() -> Path:
    return Path(__file__).resolve().parent


def shell_prefix() -> str:
    return (
        "if [ -n \"${CONDA_SH:-}\" ]; then source \"${CONDA_SH}\" >/dev/null 2>&1 || true; fi && "
    )


def build_benchmark_list(selected: Optional[str]) -> List[str]:
    if not selected:
        return BENCHMARK_ORDER.copy()
    wanted = [item.strip() for item in selected.split(",") if item.strip()]
    unknown = [item for item in wanted if item not in BENCHMARK_MAX_CYCLES]
    if unknown:
        raise SystemExit(f"Unknown benchmarks: {', '.join(unknown)}")
    return wanted


def run_shell(command: str, cwd: Path, log_path: Optional[Path] = None) -> subprocess.CompletedProcess:
    if log_path is None:
        return subprocess.run(
            ["bash", "-lc", command],
            cwd=cwd,
            text=True,
            capture_output=True,
            check=False,
        )

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
    cmd = f"{shell_prefix()}source {repo_root() / 'env.sh'} && ./build.sh"
    result = run_shell(cmd, embench_root())
    if result.returncode != 0:
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        raise SystemExit(result.returncode)


def binary_path(bench: str) -> Path:
    return embench_root() / "build" / bench


def run_benchmark(bench: str, max_cycles: int, verbose: bool, log_path: Path) -> subprocess.CompletedProcess:
    extra = " EXTRA_SIM_FLAGS=+verbose" if verbose else ""
    cmd = (
        f"{shell_prefix()}source {repo_root() / 'env.sh'} && "
        f"make -C {repo_root() / 'sims/verilator'} "
        f"CONFIG=SmallBoomV3Config "
        f"BINARY={binary_path(bench)} "
        f"LOADMEM=1 "
        f"BREAK_SIM_PREREQ=1 "
        f"TIMEOUT_CYCLES={max_cycles}"
        f"{extra} "
        f"run-binary-fast"
    )
    return run_shell(cmd, repo_root(), log_path=log_path)


def parse_pass_cycles(log_text: str) -> Optional[int]:
    match = PASS_RE.search(log_text)
    return int(match.group(1)) if match else None


def parse_marker_cycles(log_text: str) -> Dict[str, int]:
    marker_cycles: Dict[str, int] = {}
    for match in MARKER_RE.finditer(log_text):
        name = match.group(4)
        cycle = int(match.group(1), 16)
        marker_cycles.setdefault(name, cycle)
    return marker_cycles


def parse_discovery_result(bench: str, max_cycles: int, log_path: Path, returncode: int) -> DiscoveryResult:
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    passed_cycles = parse_pass_cycles(log_text)
    finished = "Verilog $finish" in log_text
    if passed_cycles is not None:
        return DiscoveryResult(bench, "success", passed_cycles, "passed", max_cycles, log_path)
    if returncode == 0 and finished:
        return DiscoveryResult(bench, "success", None, "finished-no-pass-line", max_cycles, log_path)
    if "timeout" in log_text.lower():
        return DiscoveryResult(bench, "failure", None, "timeout", max_cycles, log_path)
    return DiscoveryResult(bench, "failure", None, f"make-exit-{returncode}", max_cycles, log_path)


def parse_breakdown(bench: str, log_path: Path) -> BreakdownResult:
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    total = parse_pass_cycles(log_text)
    if total is None:
        return BreakdownResult(bench, "failure", None, None, None, None, None, None, log_path, "verbose run did not pass")

    marker_cycles = parse_marker_cycles(log_text)
    missing = [name for name in REQUIRED_MARKERS if name not in marker_cycles]
    if missing:
        return BreakdownResult(
            bench,
            "failure",
            total,
            None,
            None,
            None,
            None,
            None,
            log_path,
            f"missing markers: {', '.join(missing)}",
        )

    startup_end = marker_cycles["startup_end"]
    measure_start = marker_cycles["measure_start"]
    verify_start = marker_cycles["verify_start"]
    orig_startup_end = marker_cycles.get("orig_startup_end", startup_end)

    startup_orig = orig_startup_end
    vm_setup = startup_end - orig_startup_end
    warm = measure_start - startup_end
    measured = verify_start - measure_start
    verify_tail = total - verify_start

    if min(startup_orig, vm_setup, warm, measured, verify_tail) < 0:
        return BreakdownResult(
            bench,
            "failure",
            total,
            startup_orig,
            vm_setup,
            warm,
            measured,
            verify_tail,
            log_path,
            "non-monotonic marker cycles",
        )

    return BreakdownResult(
        bench,
        "success",
        total,
        startup_orig,
        vm_setup,
        warm,
        measured,
        verify_tail,
        log_path,
    )


def format_cycles(value: Optional[int]) -> str:
    return "" if value is None else f"{value:,}"


def format_markdown_table(headers: Iterable[str], rows: Iterable[Iterable[str]]) -> str:
    rows = list(rows)
    header_line = "| " + " | ".join(headers) + " |"
    separator = "| " + " | ".join("---" for _ in headers) + " |"
    body = ["| " + " | ".join(row) + " |" for row in rows]
    return "\n".join([header_line, separator] + body)


def write_tsv(path: Path, headers: Iterable[str], rows: Iterable[Iterable[str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t")
        writer.writerow(list(headers))
        writer.writerows(rows)


def read_discovery_tsv(path: Path) -> List[DiscoveryResult]:
    results = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        for row in reader:
            results.append(
                DiscoveryResult(
                    bench=row["bench"],
                    status=row["status"],
                    cycles=int(row["cycles"]) if row["cycles"] else None,
                    reason=row["reason"],
                    max_cycles=int(row["max_cycles"]),
                    log_path=Path(row["log_path"]),
                )
            )
    return results


def read_breakdown_tsv(path: Path) -> List[BreakdownResult]:
    results = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        for row in reader:
            results.append(
                BreakdownResult(
                    bench=row["bench"],
                    status=row["status"],
                    total=int(row["total"]) if row["total"] else None,
                    startup_orig=int(row["startup_orig"]) if row["startup_orig"] else None,
                    vm_setup=int(row["vm_setup"]) if row["vm_setup"] else None,
                    warm=int(row["warm"]) if row["warm"] else None,
                    measured=int(row["measured"]) if row["measured"] else None,
                    verify_tail=int(row["verify_tail"]) if row["verify_tail"] else None,
                    log_path=Path(row["log_path"]),
                    note=row["note"],
                )
            )
    return results


def resolve_max_cycles(bench: str, override: Optional[int]) -> int:
    if override is not None:
        return override
    return BENCHMARK_MAX_CYCLES[bench]


def render_report(
    report_path: Path,
    discovery_results: List[DiscoveryResult],
    breakdown_results: List[BreakdownResult],
    discover_dir: Path,
    verbose_dir: Path,
    run_tag: str,
) -> None:
    discovery_by_bench = {result.bench: result for result in discovery_results}
    breakdown_by_bench = {result.bench: result for result in breakdown_results}

    summary_rows = []
    for bench in BENCHMARK_ORDER:
        discovery = discovery_by_bench.get(bench)
        if discovery is None:
            continue
        breakdown = breakdown_by_bench.get(bench)
        verbose_status = ""
        verbose_log = ""
        if breakdown:
            verbose_status = breakdown.status if not breakdown.note else f"{breakdown.status} ({breakdown.note})"
            verbose_log = str(breakdown.log_path)
        summary_rows.append([
            f"`{bench}`",
            discovery.status,
            format_cycles(discovery.cycles),
            discovery.reason,
            format_cycles(discovery.max_cycles),
            verbose_status,
            str(discovery.log_path),
            verbose_log,
        ])

    passed_rows = []
    for bench in BENCHMARK_ORDER:
        breakdown = breakdown_by_bench.get(bench)
        if not breakdown or breakdown.status != "success":
            continue
        setup_ratio = ""
        if breakdown.total and breakdown.vm_setup is not None:
            setup_ratio = f"{(breakdown.vm_setup / breakdown.total) * 100:.3f}%"
        passed_rows.append([
            f"`{bench}`",
            format_cycles(breakdown.total),
            format_cycles(breakdown.startup_orig),
            format_cycles(breakdown.vm_setup),
            format_cycles(breakdown.warm),
            format_cycles(breakdown.measured),
            format_cycles(breakdown.verify_tail),
            setup_ratio,
            str(breakdown.log_path),
        ])

    failed_verbose_rows = []
    for bench in BENCHMARK_ORDER:
        breakdown = breakdown_by_bench.get(bench)
        if not breakdown or breakdown.status == "success":
            continue
        failed_verbose_rows.append([
            f"`{bench}`",
            breakdown.status,
            breakdown.note,
            str(breakdown.log_path),
        ])

    sections = [
        "# Embench SmallBoomV3 Unified Marker Breakdown",
        "",
        f"Repo: `{repo_name()}`",
        f"Run tag: `{run_tag}`",
        f"Rendered: {date.today().isoformat()}",
        "",
        "Column meanings for the breakdown table:",
        "- `startup(orig)`: cycles before the original shared warm-up entry.",
        "- `plain VM setup`: VM setup cycles inserted ahead of the original warm-up entry.",
        "- `warm_caches(orig)`: original Embench warm-up phase.",
        "- `measured bench(orig)`: original benchmark region bracketed by `start_trigger()`/`stop_trigger()`.",
        "- `verify+tail(orig)`: cycles after the measured region, including verification, exit, and simulator tail.",
        "",
        f"Discovery logs: `{discover_dir}`",
        f"Verbose logs: `{verbose_dir}`",
        "",
        "## Run Summary",
        "",
        format_markdown_table(
            [
                "bench",
                "discover status",
                "discover cycles",
                "discover reason",
                "max_cycles",
                "verbose status",
                "discover log",
                "verbose log",
            ],
            summary_rows,
        ),
        "",
        "## Breakdown",
        "",
    ]

    if passed_rows:
        sections.extend([
            format_markdown_table(
                [
                    "bench",
                    "total",
                    "startup(orig)",
                    "plain VM setup",
                    "warm_caches(orig)",
                    "measured bench(orig)",
                    "verify+tail(orig)",
                    "vm_setup/total",
                    "verbose log",
                ],
                passed_rows,
            ),
            "",
        ])
    else:
        sections.extend([
            "No passing verbose breakdowns recorded yet.",
            "",
        ])

    if failed_verbose_rows:
        sections.extend([
            "## Verbose Parse Issues",
            "",
            format_markdown_table(
                ["bench", "status", "note", "verbose log"],
                failed_verbose_rows,
            ),
            "",
        ])

    report_path.write_text("\n".join(sections), encoding="utf-8")


def main() -> int:
    args = parse_args()
    benchmarks = build_benchmark_list(args.benchmarks)
    embench_dir = embench_root()
    run_tag = args.run_date if args.run_date else date.today().isoformat()
    discover_dir = embench_dir / f"run-logs-{run_tag}-smallboomv3-unified-marker-discover-loadmem"
    verbose_dir = embench_dir / f"run-logs-{run_tag}-smallboomv3-unified-marker-verbose-loadmem"
    discover_dir.mkdir(parents=True, exist_ok=True)
    verbose_dir.mkdir(parents=True, exist_ok=True)
    discover_tsv = discover_dir / "results.tsv"
    verbose_tsv = verbose_dir / "results.tsv"
    report_path = embench_dir / f"embench-smallboomv3-unified-marker-breakdown-{run_tag}.md"

    ensure_built(args.skip_build)

    if args.skip_discovery:
        if not discover_tsv.exists():
            raise SystemExit(f"Discovery TSV not found: {discover_tsv}")
        discovery_results = read_discovery_tsv(discover_tsv)
        discovery_results = [result for result in discovery_results if result.bench in benchmarks]
        if args.append_existing and verbose_tsv.exists():
            breakdown_results = read_breakdown_tsv(verbose_tsv)
            breakdown_results = [result for result in breakdown_results if result.bench in benchmarks]
        else:
            breakdown_results = []
    else:
        discovery_results = []
        breakdown_results = []
        if args.append_existing and discover_tsv.exists():
            discovery_results = read_discovery_tsv(discover_tsv)
            discovery_results = [result for result in discovery_results if result.bench in benchmarks]
        if args.append_existing and verbose_tsv.exists():
            breakdown_results = read_breakdown_tsv(verbose_tsv)
            breakdown_results = [result for result in breakdown_results if result.bench in benchmarks]
        if not args.append_existing:
            write_tsv(
                discover_tsv,
                ["bench", "status", "cycles", "reason", "max_cycles", "log_path"],
                [],
            )
            write_tsv(
                verbose_tsv,
                [
                    "bench",
                    "status",
                    "total",
                    "startup_orig",
                    "vm_setup",
                    "warm",
                    "measured",
                    "verify_tail",
                    "log_path",
                    "note",
                ],
                [],
            )
        existing_discovery = {result.bench for result in discovery_results}
        for bench in benchmarks:
            if bench in existing_discovery:
                continue
            log_path = discover_dir / f"{bench}.full.log"
            max_cycles = resolve_max_cycles(bench, args.max_cycles)
            result = run_benchmark(bench, max_cycles, verbose=False, log_path=log_path)
            discovery = parse_discovery_result(bench, max_cycles, log_path, result.returncode)
            discovery_results.append(discovery)
            discovery_results.sort(key=lambda item: BENCHMARK_ORDER.index(item.bench))
            write_tsv(
                discover_tsv,
                ["bench", "status", "cycles", "reason", "max_cycles", "log_path"],
                [
                    [
                        item.bench,
                        item.status,
                        "" if item.cycles is None else str(item.cycles),
                        item.reason,
                        str(item.max_cycles),
                        str(item.log_path),
                    ]
                    for item in discovery_results
                ],
            )
            render_report(report_path, discovery_results, breakdown_results, discover_dir, verbose_dir, run_tag)

    if not args.skip_discovery and not args.append_existing:
        breakdown_results = []
    render_report(report_path, discovery_results, breakdown_results, discover_dir, verbose_dir, run_tag)
    existing_breakdowns = {result.bench for result in breakdown_results}
    passing = [result.bench for result in discovery_results if result.status == "success"]
    for bench in passing:
        if bench in existing_breakdowns:
            continue
        log_path = verbose_dir / f"{bench}.full.log"
        max_cycles = resolve_max_cycles(bench, args.max_cycles)
        run_benchmark(bench, max_cycles, verbose=True, log_path=log_path)
        breakdown = parse_breakdown(bench, log_path)
        breakdown_results.append(breakdown)
        breakdown_results.sort(key=lambda item: BENCHMARK_ORDER.index(item.bench))
        write_tsv(
            verbose_tsv,
            [
                "bench",
                "status",
                "total",
                "startup_orig",
                "vm_setup",
                "warm",
                "measured",
                "verify_tail",
                "log_path",
                "note",
            ],
            [
                [
                    item.bench,
                    item.status,
                    "" if item.total is None else str(item.total),
                    "" if item.startup_orig is None else str(item.startup_orig),
                    "" if item.vm_setup is None else str(item.vm_setup),
                    "" if item.warm is None else str(item.warm),
                    "" if item.measured is None else str(item.measured),
                    "" if item.verify_tail is None else str(item.verify_tail),
                    str(item.log_path),
                    item.note,
                ]
                for item in breakdown_results
            ],
        )
        render_report(report_path, discovery_results, breakdown_results, discover_dir, verbose_dir, run_tag)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
