# See LICENSE for license details.

#*****************************************************************************
# cache-rw-amo-sc-hit.S
#-----------------------------------------------------------------------------
#
# Regression target for BOOM v3 L1D cache-crypto cached AMO/SC hit support.
#
# The test keeps all atomic accesses on one 64B resident cache line so the
# intended execution path is:
#   cache hit -> crypto engine -> delayed LSU response
#
# On current RTL, this test is expected to expose the missing AMO/SC hit
# support when cache-crypto is enabled. After the RTL patch lands, it should
# pass and remain in regression.
#

#define cus_reg mscratch
#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64M
RVTEST_CODE_BEGIN

  # -------------------------------------------------------------
  # Configure the D$ cache-crypto counter backing store and enable
  # the D$ crypto path for the data buffer tested below.
  # -------------------------------------------------------------
  la   t0, counter_store
  li   t1, 0x10000000
  sub  t0, t0, t1
  csrw 0x3f3, t0
  li   t0, 3
  csrw 0x3f2, t0
  fence rw, rw

  # -------------------------------------------------------------
  # Phase 1: prime one 64B line under crypto mode.
  # -------------------------------------------------------------
  la   s0, buf_amo
  li   s1, 0x0011223344556677
  li   s2, 0x7766554433221100
  sd   s1, 0(s0)
  sd   s2, 8(s0)
  ld   t0, 0(s0)
  ld   t1, 8(s0)
  li   TESTNUM, 2
  bne  t0, s1, fail
  bne  t1, s2, fail

  # -------------------------------------------------------------
  # Phase 2: successful LR/SC hit.
  # -------------------------------------------------------------
  li   s3, 0x1122334455667788
  li   TESTNUM, 3
  lr.d t2, (s0)
  bne  t2, s1, fail
  sc.d t3, s3, (s0)
  bnez t3, fail
  ld   t4, 0(s0)
  bne  t4, s3, fail

  # -------------------------------------------------------------
  # Phase 3: SC success followed by SC failure without a fresh LR.
  # -------------------------------------------------------------
  li   s4, 0x99aabbccddeeff00
  li   s5, 0xdeadbeefdeadbeef
  li   TESTNUM, 4
  lr.d t2, (s0)
  bne  t2, s3, fail
  sc.d t3, s4, (s0)
  bnez t3, fail
  sc.d t3, s5, (s0)
  li   t5, 1
  bne  t3, t5, fail
  ld   t4, 0(s0)
  bne  t4, s4, fail

  # -------------------------------------------------------------
  # Phase 4: AMOSWAP hit. Return old value, commit new value.
  # -------------------------------------------------------------
  li   s6, 0x1111111111111111
  li   TESTNUM, 5
  amoswap.d t2, s6, (s0)
  bne  t2, s4, fail
  ld   t4, 0(s0)
  bne  t4, s6, fail

  # -------------------------------------------------------------
  # Phase 5: AMOADD hit.
  # -------------------------------------------------------------
  li   s7, 0x10
  li   s8, 0x1111111111111121
  li   TESTNUM, 6
  amoadd.d t2, s7, (s0)
  bne  t2, s6, fail
  ld   t4, 0(s0)
  bne  t4, s8, fail

  # -------------------------------------------------------------
  # Phase 6a: AMOXOR hit.
  # -------------------------------------------------------------
  li   s9,  0x00ff00ff00ff00ff
  li   s10, 0x11ee11ee11ee11de
  li   TESTNUM, 7
  amoxor.d t2, s9, (s0)
  bne  t2, s8, fail
  ld   t4, 0(s0)
  bne  t4, s10, fail

  # -------------------------------------------------------------
  # Phase 6b: AMOOR hit.
  # -------------------------------------------------------------
  li   s11, 0x2000000000000001
  li   s1,  0x31ee11ee11ee11df
  li   TESTNUM, 8
  amoor.d t2, s11, (s0)
  bne  t2, s10, fail
  ld   t4, 0(s0)
  bne  t4, s1, fail

  # -------------------------------------------------------------
  # Phase 6c: AMOAND hit.
  # -------------------------------------------------------------
  li   s2, 0x3fff00ff3fff00ff
  li   s3, 0x31ee00ee11ee00df
  li   TESTNUM, 9
  amoand.d t2, s2, (s0)
  bne  t2, s1, fail
  ld   t4, 0(s0)
  bne  t4, s3, fail

  # Confirm the neighboring word in the same line was not disturbed.
  li   TESTNUM, 10
  ld   t4, 8(s0)
  li   t5, 0x7766554433221100
  bne  t4, t5, fail

  # -------------------------------------------------------------
  # Phase 7: disable crypto, then repeatedly thrash the data-line set and
  # backing counter-line set until the persisted counter becomes visible.
  # Successful modifying operations persisted by the backing counter on the
  # current RTL:
  #   2 plain stores
  #   1 counted successful SC before the failed-SC sequence
  #   5 AMOs
  # total = 8
  # -------------------------------------------------------------
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  fence rw, rw

  li   s11, 8
1:
  # Thrash the buf_amo set.
  la   t4, buf_amo
  mv   t0, t4
  andi t0, t0, -64
  li   t1, 0xffff
  and  t0, t0, t1
  la   t1, evict_buf
  add  t0, t0, t1
  li   t6, 4
2:
  li   t1, 16
  li   t2, 0x66
  li   t3, 0x10000
3:
  sd   t2, 0(t0)
  add  t0, t0, t3
  addi t1, t1, -1
  bnez t1, 3b
  li   t1, 0x100000
  sub  t0, t0, t1
  addi t6, t6, -1
  bnez t6, 2b
  fence rw, rw
  fence.i
  fence rw, rw

  # Thrash the backing counter-line set.
  la   t0, counter_store
  la   t1, buf_amo
  li   t2, 0x80000000
  sub  t1, t1, t2
  srli t1, t1, 6
  slli t1, t1, 3
  add  t0, t0, t1
  andi t0, t0, -64
  li   t1, 0xffff
  and  t0, t0, t1
  la   t1, evict_buf
  add  t0, t0, t1
  li   t6, 4
4:
  li   t1, 16
  li   t2, 0x77
  li   t3, 0x10000
5:
  sd   t2, 0(t0)
  add  t0, t0, t3
  addi t1, t1, -1
  bnez t1, 5b
  li   t1, 0x100000
  sub  t0, t0, t1
  addi t6, t6, -1
  bnez t6, 4b
  fence rw, rw
  fence.i
  fence rw, rw

  # Final check: counter slot backing buf_amo should be 8.
  la   t0, counter_store
  la   t1, buf_amo
  li   t2, 0x80000000
  sub  t1, t1, t2
  srli t1, t1, 6
  slli t1, t1, 3
  add  t0, t0, t1
  li   TESTNUM, 11
  ld   t3, 0(t0)
  li   t4, 8
  beq  t3, t4, pass_tohost
  addi s11, s11, -1
  bnez s11, 1b
  j    fail

cryptoexec_encrypt_limit:
pass_tohost:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 1
  sd   t1, 0(t0)
6:
  j    6b

fail:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 3
  sd   t1, 0(t0)
7:
  j    7b

RVTEST_CODE_END

  .data
RVTEST_DATA_BEGIN

  TEST_DATA

RVTEST_DATA_END

  .section .bss
  .align 6
buf_amo:
  .space 64

  .align 12
counter_store:
  .space 4096

  .align 16
evict_buf:
  .space 1048576
