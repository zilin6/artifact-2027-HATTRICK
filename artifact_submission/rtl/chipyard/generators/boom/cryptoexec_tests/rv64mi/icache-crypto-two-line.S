# See LICENSE for license details.

#*****************************************************************************
# icache-crypto-two-line.S
#-----------------------------------------------------------------------------
#
# BOOM I$ cache-crypto two-line smoke test:
# 1) In M-mode bootstrap, enable store-only D$/L2 crypto and copy two
#    encrypted cache lines into an executable buffer.
# 2) Bootstrap enables the dedicated I$ crypto CSR before mret.
# 3) In U-mode, the pre-encrypted entry jumps into the first encrypted line,
#    which then jumps into the second encrypted line.
# 4) After the first return, jump directly into the encrypted body again while
#    I$ crypto remains enabled, exercising a second round of crypto hits.
#

#define cus_reg mscratch
#include "riscv_test.h"
#include "test_macros.h"

#undef EXTRA_INIT
#define EXTRA_INIT                                                     \
  la   t0, cryptoexec_counter_store;                                   \
  li   t1, 0x10000000;                                                 \
  sub  t0, t0, t1;                                                     \
  csrw 0x3f3, t0;                                                      \
  li   t0, 2;                                                          \
  csrw 0x3f2, t0;                                                      \
  fence rw, rw;                                                        \
  la   t0, enc_template_line0;                                         \
  la   t1, code_buf_body0;                                             \
  li   t2, 8;                                                          \
1:                                                                     \
  ld   t3, 0(t0);                                                      \
  sd   t3, 0(t1);                                                      \
  addi t0, t0, 8;                                                      \
  addi t1, t1, 8;                                                      \
  addi t2, t2, -1;                                                     \
  bnez t2, 1b;                                                         \
  la   t0, enc_template_line1;                                         \
  la   t1, code_buf_body1;                                             \
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

  la   s4, code_buf_body0
  la   s5, code_buf_body1

  li   TESTNUM, 2
  jalr ra, s4, 0

  li   t0, 797
  bne  a0, t0, fail

  li   TESTNUM, 3
  jalr ra, s4, 0

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
enc_template_line0:
  addi a0, zero, 700
  addi a0, a0, 50
  addi a0, a0, 40
  addi t0, zero, 7
  add  a0, a0, t0
  addi t1, zero, 0
  add  a0, a0, t1
  nop
  nop
  nop
  nop
  nop
  nop
  nop
  nop
  jalr zero, s5, 0

  .align 6
enc_template_line1:
  addi t2, zero, 797
  bne  a0, t2, 1f
  addi t3, zero, 1
  slli t3, t3, 3
  addi t3, t3, -8
  add  a0, a0, t3
  jalr zero, ra, 0
1:
  addi a0, zero, -1
  jalr zero, ra, 0
  nop
  nop
  nop
  nop
  nop
  nop
  nop

  .section .bss
  .align 6
code_buf_body0:
  .space 64

code_buf_body1:
  .space 64
