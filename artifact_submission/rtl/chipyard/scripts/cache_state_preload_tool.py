#!/usr/bin/env python3

from __future__ import annotations

import argparse
import pathlib
import struct
import sys


TARGETS = {
    "END": 0,
    "L1_META": 1,
    "L1_DATA": 2,
    "L1_COUNTER": 3,
    "L2_DIR": 4,
    "L2_DATA": 5,
    "L2_COUNTER": 6,
}

L1_TARGETS = {"L1_META", "L1_DATA", "L1_COUNTER"}
L2_TARGETS = {"L2_DIR", "L2_DATA", "L2_COUNTER"}
L2_STATE_NAMES = {
    "INVALID": 0,
    "BRANCH": 1,
    "TRUNK": 2,
    "TIP": 3,
}


def parse_int(text: str) -> int:
    return int(text, 0)


def parse_line(line: str, lineno: int) -> tuple[str, int, int, int, int, int]:
    toks = line.split()
    if not toks:
        raise ValueError(f"line {lineno}: empty record")
    target = toks[0].upper()
    if target not in TARGETS:
        raise ValueError(f"line {lineno}: unknown target {target}")

    fields = {
        "set": 0,
        "way": 0,
        "beat": 0,
        "mask": 0,
        "payload": 0,
    }
    for tok in toks[1:]:
        if "=" not in tok:
            raise ValueError(f"line {lineno}: expected key=value, got {tok}")
        k, v = tok.split("=", 1)
        k = k.lower()
        if k not in fields:
            raise ValueError(f"line {lineno}: unknown field {k}")
        fields[k] = parse_int(v)

    return (
        target,
        fields["set"],
        fields["way"],
        fields["beat"],
        fields["mask"],
        fields["payload"],
    )


def encode_record(target: str, set_idx: int, way: int, beat: int, mask: int, payload: int) -> bytes:
    header = (
        (TARGETS[target] & 0xFF)
        | ((set_idx & 0xFFFF) << 8)
        | ((way & 0xFF) << 24)
        | ((beat & 0xFF) << 32)
        | ((mask & 0xFF) << 40)
    )
    return struct.pack("<QQ", header, payload & 0xFFFFFFFFFFFFFFFF)


def write_records(path: pathlib.Path, records: list[bytes]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        for rec in records:
            f.write(rec)
        f.write(encode_record("END", 0, 0, 0, 0, 0))


def pack_l1_meta(args: argparse.Namespace) -> int:
    coh = parse_int(args.coh)
    tag = parse_int(args.tag)
    reenc = 1 if args.reenc else 0
    crypto = 1 if args.crypto else 0
    payload = (
        ((coh & ((1 << args.coh_bits) - 1)) << (args.tag_bits + 2))
        | ((tag & ((1 << args.tag_bits) - 1)) << 2)
        | ((reenc & 0x1) << 1)
        | crypto
    )
    print(hex(payload))
    return 0


def pack_l2_dir(args: argparse.Namespace) -> int:
    state = L2_STATE_NAMES.get(args.state.upper(), None)
    if state is None:
        raise SystemExit(f"unknown L2 state {args.state}")
    dirty = 1 if args.dirty else 0
    clients = parse_int(args.clients)
    tag = parse_int(args.tag)
    crypto = 1 if args.crypto else 0
    data_valid = 1 if args.data_valid else 0
    counter_valid = 1 if args.counter_valid else 0
    payload = (
        ((dirty & 0x1) << (args.state_bits + args.client_bits + args.tag_bits + 3))
        | ((state & ((1 << args.state_bits) - 1)) << (args.client_bits + args.tag_bits + 3))
        | ((clients & ((1 << args.client_bits) - 1)) << (args.tag_bits + 3))
        | ((tag & ((1 << args.tag_bits) - 1)) << 3)
        | ((crypto & 0x1) << 2)
        | ((data_valid & 0x1) << 1)
        | counter_valid
    )
    print(hex(payload))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Build cache preload binary records")
    sub = ap.add_subparsers(dest="cmd", required=True)

    build = sub.add_parser("build", help="build preload binary/binaries from text spec")
    build.add_argument("spec", type=pathlib.Path, help="text preload spec")
    build.add_argument("--out", type=pathlib.Path, help="single output binary")
    build.add_argument("--out-l1", type=pathlib.Path, help="filtered L1 preload binary")
    build.add_argument("--out-l2", type=pathlib.Path, help="filtered L2 preload binary")

    p1 = sub.add_parser("pack-l1-meta", help="pack named L1 metadata fields into payload")
    p1.add_argument("--tag", required=True)
    p1.add_argument("--coh", required=True, help="ClientMetadata.state numeric value")
    p1.add_argument("--tag-bits", type=int, required=True)
    p1.add_argument("--coh-bits", type=int, default=2)
    p1.add_argument("--reenc", action="store_true")
    p1.add_argument("--crypto", action="store_true")

    p2 = sub.add_parser("pack-l2-dir", help="pack named inclusive-cache directory fields into payload")
    p2.add_argument("--state", required=True, choices=sorted(L2_STATE_NAMES.keys()))
    p2.add_argument("--dirty", action="store_true")
    p2.add_argument("--clients", required=True)
    p2.add_argument("--tag", required=True)
    p2.add_argument("--tag-bits", type=int, required=True)
    p2.add_argument("--client-bits", type=int, required=True)
    p2.add_argument("--state-bits", type=int, default=2)
    p2.add_argument("--crypto", action="store_true")
    p2.add_argument("--data-valid", action="store_true")
    p2.add_argument("--counter-valid", action="store_true")

    args = ap.parse_args()

    if args.cmd == "pack-l1-meta":
        return pack_l1_meta(args)
    if args.cmd == "pack-l2-dir":
        return pack_l2_dir(args)

    if not args.out and not (args.out_l1 and args.out_l2):
        ap.error("use --out or both --out-l1 and --out-l2")

    raw_records: list[tuple[str, int, int, int, int, int]] = []
    for lineno, raw in enumerate(args.spec.read_text().splitlines(), start=1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        raw_records.append(parse_line(line, lineno))

    if args.out:
        families = {
            "L1" if rec[0] in L1_TARGETS else "L2"
            for rec in raw_records
            if rec[0] != "END"
        }
        if len(families) > 1:
            raise SystemExit("mixed L1/L2 targets require --out-l1 and --out-l2")
        write_records(args.out, [encode_record(*rec) for rec in raw_records])

    if args.out_l1 and args.out_l2:
        l1_records = [encode_record(*rec) for rec in raw_records if rec[0] in L1_TARGETS]
        l2_records = [encode_record(*rec) for rec in raw_records if rec[0] in L2_TARGETS]
        write_records(args.out_l1, l1_records)
        write_records(args.out_l2, l2_records)

    return 0


if __name__ == "__main__":
    sys.exit(main())
