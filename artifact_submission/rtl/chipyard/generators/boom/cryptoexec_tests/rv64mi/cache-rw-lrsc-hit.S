# See LICENSE for license details.

#*****************************************************************************
# cache-rw-lrsc-hit.S
#-----------------------------------------------------------------------------
#
# Focused LR/SC hit-path validation on one resident 64B cache line with the
# BOOM v3 D$ crypto engine enabled.
#
# Coverage:
#   1) successful LR/SC on one doubleword
#   2) successful LR/SC on a neighboring doubleword in the same cache line
#   3) failed SC without a fresh LR, with data left unchanged
#   4) persisted backing counter only counts successful modifying operations
#

#define cus_reg mscratch
#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64M
RVTEST_CODE_BEGIN

  # Configure the D$ cache-crypto counter backing store and enable crypto.
  la   t0, counter_store
  li   t1, 0x10000000
  sub  t0, t0, t1
  csrw 0x3f3, t0
  li   t0, 3
  csrw 0x3f2, t0
  fence rw, rw

  # Prime one target line.
  la   s0, buf_lrsc
  addi s1, s0, 8
  li   s2, 0x0011223344556677
  li   s3, 0x8899aabbccddeeff
  sd   s2, 0(s0)
  sd   s3, 0(s1)
  ld   t0, 0(s0)
  ld   t1, 0(s1)
  li   TESTNUM, 2
  bne  t0, s2, fail
  bne  t1, s3, fail

  # Phase 1: successful LR/SC on the first doubleword.
  li   s4, 0x1122334455667788
  li   TESTNUM, 3
1:
  lr.d t2, (s0)
  bne  t2, s2, fail
  sc.d t3, s4, (s0)
  bnez t3, 1b
  ld   t4, 0(s0)
  bne  t4, s4, fail

  # Phase 2: successful LR/SC on the neighboring doubleword.
  li   s5, 0x0fedcba987654321
  li   TESTNUM, 4
2:
  lr.d t2, (s1)
  bne  t2, s3, fail
  sc.d t3, s5, (s1)
  bnez t3, 2b
  ld   t4, 0(s1)
  bne  t4, s5, fail
  ld   t4, 0(s0)
  bne  t4, s4, fail

  # Phase 3: SC without a fresh LR must fail and leave data unchanged.
  li   s6, 0xdeadbeefdeadbeef
  li   TESTNUM, 5
  sc.d t3, s6, (s1)
  li   t5, 1
  bne  t3, t5, fail
  ld   t4, 0(s1)
  bne  t4, s5, fail

  # Disable crypto, then repeatedly thrash the data-line set and backing
  # counter-line set until the persisted counter becomes visible.
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  fence rw, rw

  li   s7, 8
3:
  # Thrash the buf_lrsc set.
  la   t4, buf_lrsc
  mv   t0, t4
  andi t0, t0, -64
  li   t1, 0xffff
  and  t0, t0, t1
  la   t1, evict_buf
  add  t0, t0, t1
  li   t6, 4
4:
  li   t1, 16
  li   t2, 0x44
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

  # Thrash the backing counter-line set.
  la   t0, counter_store
  la   t1, buf_lrsc
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
6:
  li   t1, 16
  li   t2, 0x55
  li   t3, 0x10000
7:
  sd   t2, 0(t0)
  add  t0, t0, t3
  addi t1, t1, -1
  bnez t1, 7b
  li   t1, 0x100000
  sub  t0, t0, t1
  addi t6, t6, -1
  bnez t6, 6b
  fence rw, rw
  fence.i
  fence rw, rw

  # Final check: this sequence persists a backing counter value of 4 on the
  # current RTL:
  #   2 plain stores
  #   2 counted successful SC operations
  # total = 4
  la   t0, counter_store
  la   t1, buf_lrsc
  li   t2, 0x80000000
  sub  t1, t1, t2
  srli t1, t1, 6
  slli t1, t1, 3
  add  t0, t0, t1
  li   TESTNUM, 6
  ld   t3, 0(t0)
  li   t4, 4
  beq  t3, t4, pass_tohost
  addi s7, s7, -1
  bnez s7, 3b
  j    fail

cryptoexec_encrypt_limit:
pass_tohost:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 1
  sd   t1, 0(t0)
8:
  j    8b

fail:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 3
  sd   t1, 0(t0)
9:
  j    9b

RVTEST_CODE_END

  .data
RVTEST_DATA_BEGIN

  TEST_DATA

RVTEST_DATA_END

  .section .bss
  .align 6
buf_lrsc:
  .space 64

  .align 12
counter_store:
  .space 4096

  .align 16
evict_buf:
  .space 1048576
