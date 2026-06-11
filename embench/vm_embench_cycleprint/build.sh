#!/usr/bin/env bash

set -e

echo  "Building embench-iot for riscv64"
BUILDDIR=$(pwd)/${OUTPUT_DIR:-build}
mkdir -p $BUILDDIR

cd embench-iot
BOARD_NAME=${BOARD_NAME:-ri5cyverilator}
BUILD_DIR_NAME=${BUILD_DIR_NAME:-bd}
LOG_DIR_NAME=${LOG_DIR_NAME:-logs}
EXTRA_CFLAGS_VALUE="-DVM_PLAIN_NO_ADDR_CRYPTO=1 ${EXTRA_CFLAGS:-}"
EXTRA_LDFLAGS_VALUE=${EXTRA_LDFLAGS:-}
BOARD_DIR=$(pwd)/config/riscv32/boards/$BOARD_NAME
# use the riscv32 target, but use riscv64 compiler
./build_all.py --builddir="$BUILD_DIR_NAME" --logdir="$LOG_DIR_NAME" --arch riscv32 --chip generic --board "$BOARD_NAME" --cc riscv64-unknown-elf-gcc --cflags="-c -O2 -ffunction-sections -mabi=lp64d -specs=htif_nano.specs $EXTRA_CFLAGS_VALUE" --ldflags="-Wl,-gc-sections -Wl,-T,$BOARD_DIR/htif.ld -specs=htif_nano.specs $EXTRA_LDFLAGS_VALUE" --user-libs="-lm" --clean -v

echo "Copying binaries to $BUILDDIR"
bmarks=("aha-mont64" "crc32" "cubic" "edn" "huffbench"
        "matmult-int" "md5sum" "minver" "nbody"
        "nettle-aes" "nettle-sha256" "nsichneu" "picojpeg"
        "primecount" "qrduino" "sglib-combined" "slre" "st"
        "statemate" "tarfind" "ud" "wikisort" "byte-store-stream")
for bmark in "${bmarks[@]}"
do
    if [ -f "$BUILD_DIR_NAME"/src/$bmark/$bmark ]; then
        cp "$BUILD_DIR_NAME"/src/$bmark/$bmark $BUILDDIR/
    fi
done
