#=======================================================================
# Makefile for crypto-exec wrapped rv64mi tests
#-----------------------------------------------------------------------

XLEN ?= 64

RISCV_TESTS_DIR ?= ../../../toolchains/riscv-tools/riscv-tests
RISCV_PREFIX ?= riscv$(XLEN)-unknown-elf-
RISCV_GCC ?= $(RISCV_PREFIX)gcc
RISCV_GCC_OPTS ?= -static -mcmodel=medany -fvisibility=hidden -nostdlib -nostartfiles
RISCV_OBJDUMP ?= $(RISCV_PREFIX)objdump --disassemble-all --disassemble-zeroes --section=.text --section=.text.startup --section=.text.init --section=.data
RISCV_SIM ?= spike

rv64mi_cryptoexec_sc_tests = \
	icache-crypto-jump \
	cache-rw \
	cache-rw-amo-sc-hit \
	cache-rw-lrsc-hit \
	cache-rw-lrsc-reenc-boundary \
	cache-rw-small \
	cache-rw-small-baseline-no-csr \
	cache-rw-counter-init-refill \
	cache-rw-counter-alias-init-refill \
	cache-rw-counter-init-refill-two-line \
	cache-counter-uncached-alias-smoke \
	cache-rw-tiny \
	cache-rw-tiny-baseline-no-csr \
	cache-rw-reenc-tiny \
	cache-rw-reenc-cipher-tiny \
	cache-rw-reenc-counter-wb \
	cache-rw-reenc-held-replay-counter \
	cache-rw-reenc-engine-bypass-same-line \
	cache-rw-reenc-engine-bypass-interleave \
	cache-rw-reenc-engine-bypass-hot-chunk \
	cache-rw-engine-bypass-store-burst \
	cache-rw-engine-bypass-byte-same-word \
	cache-rw-engine-bypass-store-load-chain \
	cache-rw-membound-load-hot \
	cache-rw-membound-load-hot-baseline-no-csr \
	cache-rw-membound-store-hot \
	cache-rw-membound-store-hot-baseline-no-csr \
	cache-rw-membound-byte-store-stream \
	cache-rw-membound-byte-store-stream-baseline-no-csr \
	cache-rw-membound-copy-hot \
	cache-rw-membound-copy-hot-baseline-no-csr \
	cache-rw-membound-copy-thrash \
	cache-rw-membound-copy-thrash-baseline-no-csr \
	cache-rw-reenc-followed-by-store \
	cache-rw-reenc-probe-fetch-race \
	cache-rw-reenc-counter-wb-baseline-no-csr \
	cache-rw-reenc-tiny-2mib-evict \
	icache-crypto-two-line \
	access \
	breakpoint \
	csr \
	mcsr \
	illegal \
	ma_fetch \
	ma_addr \
	scall \
	sbreak \
	ld-misaligned \
	lw-misaligned \
	lh-misaligned \
	sh-misaligned \
	sw-misaligned \
	sd-misaligned \
	zicntr

tests = $(addprefix rv64mi-ce-, $(rv64mi_cryptoexec_sc_tests))
tests_dump = $(addsuffix .dump, $(tests))
tests_out = $(addsuffix .out, $(tests))

.SECONDARY: $(tests)

default: all

vpath %.S rv64mi

rv64mi-ce-%: rv64mi/%.S
	$(RISCV_GCC) -march=rv64g -mabi=lp64 $(RISCV_GCC_OPTS) \
		-I$(CURDIR)/include \
		-I$(RISCV_TESTS_DIR)/env \
		-I$(RISCV_TESTS_DIR)/env/p \
		-I$(RISCV_TESTS_DIR)/isa/macros/scalar \
		-T$(RISCV_TESTS_DIR)/env/p/link.ld \
		$< -o $@

%.dump: %
	$(RISCV_OBJDUMP) $< > $@

%.out: %
	$(RISCV_SIM) --isa=rv64gch_zfh_zicboz_svnapot_zicntr_zba_zbb_zbc_zbs --misaligned $< 2> $@

all: $(tests_dump)

run: $(tests_out)

list:
	@printf '%s\n' $(tests)

clean:
	rm -f $(tests) $(tests_dump) $(tests_out)

.PHONY: all clean default list run
