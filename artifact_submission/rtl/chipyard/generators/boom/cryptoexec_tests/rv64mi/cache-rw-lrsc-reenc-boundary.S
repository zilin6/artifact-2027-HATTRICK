# See LICENSE for license details.

#*****************************************************************************
# cache-rw-lrsc-reenc-boundary.S
#-----------------------------------------------------------------------------
#
# Re-encrypt boundary validation using LR/SC on one resident cache line:
#   1) 255 successful LR/SC updates drive the line counter to 0xff
#   2) the 256th successful SC triggers counter rollover / re-encrypt
#   3) the 257th successful SC verifies forward progress after rollover
#   4) the persisted backing counter matches the number of successful SC ops
#

#define cus_reg mscratch
#include "riscv_test.h"
#include "test_macros.h"

RVTEST_RV64M
RVTEST_CODE_BEGIN

  # Configure the D$ cache-crypto counter backing store.
  la   t0, counter_store
  li   t1, 0x10000000
  sub  t0, t0, t1
  csrw 0x3f3, t0
  fence rw, rw

  la   s0, buf_reenc_lrsc
  li   s1, 0                    # expected committed value

  # Enable cache crypto.
  li   t0, 3
  csrw 0x3f2, t0
  fence rw, rw

  # Seed the resident line with encrypted zero before the LR/SC updates.
  sd   zero, 0(s0)
  fence rw, rw

  # Phase 1: 255 successful LR/SC updates.
  li   s2, 255
  li   TESTNUM, 2
1:
2:
  lr.d t0, (s0)
  bne  t0, s1, fail_255
  addi t1, s1, 1
  sc.d t2, t1, (s0)
  bnez t2, 2b
  mv   s1, t1
  addi s2, s2, -1
  bnez s2, 1b
  fence rw, rw
  ld   t0, 0(s0)
  li   t1, 255
  bne  t0, t1, fail_255

  # Phase 2: store #256 triggers re-encrypt.
  li   TESTNUM, 3
3:
  lr.d t0, (s0)
  bne  t0, s1, fail_256
  addi t1, s1, 1
  sc.d t2, t1, (s0)
  bnez t2, 3b
  mv   s1, t1
  fence rw, rw
  ld   t0, 0(s0)
  li   t1, 256
  bne  t0, t1, fail_256

  # Phase 3: one more LR/SC update after rollover.
  li   TESTNUM, 4
4:
  lr.d t0, (s0)
  bne  t0, s1, fail_257
  addi t1, s1, 1
  sc.d t2, t1, (s0)
  bnez t2, 4b
  mv   s1, t1
  fence rw, rw
  ld   t0, 0(s0)
  li   t1, 257
  bne  t0, t1, fail_257

  # Disable crypto, then repeatedly thrash the data-line set and backing
  # counter-line set until the persisted counter becomes visible.
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  fence rw, rw

  li   s3, 8
5:
  # Thrash the buf_reenc_lrsc set.
  la   t4, buf_reenc_lrsc
  mv   t0, t4
  andi t0, t0, -64
  li   t1, 0xffff
  and  t0, t0, t1
  la   t1, evict_buf
  add  t0, t0, t1
  li   t6, 4
6:
  li   t1, 16
  li   t2, 0x66
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

  # Thrash the backing counter-line set.
  la   t0, counter_store
  la   t1, buf_reenc_lrsc
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
8:
  li   t1, 16
  li   t2, 0x77
  li   t3, 0x10000
9:
  sd   t2, 0(t0)
  add  t0, t0, t3
  addi t1, t1, -1
  bnez t1, 9b
  li   t1, 0x100000
  sub  t0, t0, t1
  addi t6, t6, -1
  bnez t6, 8b
  fence rw, rw
  fence.i
  fence rw, rw

  # Final check: one encrypted seed store plus 257 successful SC operations
  # should persist as 258.
  la   t0, counter_store
  la   t1, buf_reenc_lrsc
  li   t2, 0x80000000
  sub  t1, t1, t2
  srli t1, t1, 6
  slli t1, t1, 3
  add  t0, t0, t1
  li   TESTNUM, 5
  ld   t3, 0(t0)
  li   t4, 258
  beq  t3, t4, pass_tohost
  addi s3, s3, -1
  bnez s3, 5b
  j    fail

cryptoexec_encrypt_limit:
pass_tohost:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 1
  sd   t1, 0(t0)
10:
  j    10b

fail:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 3
  sd   t1, 0(t0)
11:
  j    11b

fail_255:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 5
  sd   t1, 0(t0)
12:
  j    12b

fail_256:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 7
  sd   t1, 0(t0)
13:
  j    13b

fail_257:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 9
  sd   t1, 0(t0)
14:
  j    14b

RVTEST_CODE_END

  .data
RVTEST_DATA_BEGIN

  TEST_DATA

RVTEST_DATA_END

  .section .bss
  .align 6
buf_reenc_lrsc:
  .space 64

  .align 12
counter_store:
  .space 4096

  .align 16
evict_buf:
  .space 1048576
