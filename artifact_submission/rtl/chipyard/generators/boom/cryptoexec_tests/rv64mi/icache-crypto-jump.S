# See LICENSE for license details.

#*****************************************************************************
# icache-crypto-jump.S
#-----------------------------------------------------------------------------
#
# BOOM I$ cache-crypto smoke test:
# 1) In M-mode bootstrap, initialize the backing counter store.
# 2) In M-mode bootstrap, enable the D$/L2 crypto path and copy one encrypted
#    cache line into an executable buffer at code_buf_body.
# 3) Bootstrap then enables the dedicated I$ crypto CSR before mret.
# 4) In U-mode, the pre-encrypted cryptoexec_test_entry jumps into code_buf_body.
#    The body is fetched as ciphertext and must be decrypted by the I$ path.
#
# Pass condition: the returned signature matches the expected constant.
#

#define cus_reg mscratch
#include "riscv_test.h"
#include "test_macros.h"

#undef EXTRA_INIT
#define EXTRA_INIT                                                     \
  la   t0, counter_store;                                              \
  li   t1, 512;                                                        \
  li   t2, 1;                                                          \
1:                                                                     \
  sd   t2, 0(t0);                                                      \
  addi t0, t0, 8;                                                      \
  addi t1, t1, -1;                                                     \
  bnez t1, 1b;                                                         \
  fence rw, rw;                                                        \
  fence.i;                                                             \
  fence rw, rw;                                                        \
  la   t0, counter_store;                                              \
  li   t1, 0x10000000;                                                 \
  sub  t0, t0, t1;                                                     \
  csrw 0x3f3, t0;                                                      \
  li   t0, 2;                                                          \
  csrw 0x3f2, t0;                                                      \
  fence rw, rw;                                                        \
  la   t0, enc_template_start;                                         \
  la   t1, code_buf_body;                                              \
  li   t2, 8;                                                          \
2:                                                                     \
  ld   t3, 0(t0);                                                      \
  sd   t3, 0(t1);                                                      \
  addi t0, t0, 8;                                                      \
  addi t1, t1, 8;                                                      \
  addi t2, t2, -1;                                                     \
  bnez t2, 2b;                                                         \
  fence rw, rw;                                                        \
  fence.i;                                                             \
  csrwi 0x3f2, 0

RVTEST_RV64U
  .option norvc
RVTEST_CODE_BEGIN

  la   t0, code_buf_body
  li   TESTNUM, 2
  jalr ra, t0, 0

  li   t0, 797
  bne  a0, t0, fail

pass_tohost:
  RVTEST_PASS

fail:
  RVTEST_FAIL

cryptoexec_encrypt_limit:

RVTEST_CODE_END

  .data
RVTEST_DATA_BEGIN

  TEST_DATA

RVTEST_DATA_END

  .section .rodata
  .align 6
enc_template_start:
  addi t0, zero, 100
  slli t0, t0, 3
  addi t1, zero, 55
  add  a0, t0, t1
  xori a0, a0, 0x5a
  addi a0, a0, 16
  addi t2, zero, 797
  bne  a0, t2, 1f
  jalr zero, ra, 0
1:
  addi a0, zero, -1
  jalr zero, ra, 0
  nop
  nop
  nop
  nop
  nop

  .section .bss
  .align 6
code_buf_body:
  .space 64

  .align 12
counter_store:
  .space 4096
