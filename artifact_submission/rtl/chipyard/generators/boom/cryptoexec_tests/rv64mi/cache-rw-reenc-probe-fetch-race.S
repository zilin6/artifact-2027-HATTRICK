# See LICENSE for license details.

#*****************************************************************************
# cache-rw-reenc-probe-fetch-race.S
#-----------------------------------------------------------------------------
#
# Stress the suspected race between DCache re-encrypt and ProbeUnit eviction.
# This test is intentionally log-driven:
# 1) Enable cache crypto and, for each 64B executable data line in probe_lines,
#    perform 255 stores of the "old" entry chunk followed by a 256th store of
#    the "new" entry chunk. The 256th store rolls the low 8b counter from
#    0xff -> 0x100 and launches line re-encrypt.
# 2) Immediately jalr into that same physical line so ICache/L2 is forced to
#    fetch the line while the DCache background re-encrypt may still be active.
# 3) The line body is just "addi t0, 0 ; ret". We ignore the returned value
#    here; the regression script inspects the verbose log and fails if it sees
#    ProbeUnit issue wb_req for a line whose re-encrypt has started but not yet
#    completed.
#

#define cus_reg mscratch
#include "riscv_test.h"
#include "test_macros.h"

#define TRIAL_LINES 32

RVTEST_RV64M
RVTEST_CODE_BEGIN

  la   t0, counter_store
  li   t1, 0x10000000
  sub  t0, t0, t1
  csrw 0x3f3, t0
  fence rw, rw

  la   t0, counter_store
  la   t1, probe_lines
  li   t2, 0x80000000
  sub  t1, t1, t2
  srli t1, t1, 6
  slli t1, t1, 3
  add  s6, t0, t1
  fence rw, rw

  li   t0, 3
  csrw 0x3f2, t0
  fence rw, rw

  la   s0, probe_lines
  la   s1, tmpl_old_chunk0
  ld   s2, 0(s1)
  la   s3, tmpl_new_chunk0
  ld   s4, 0(s3)
  li   s5, TRIAL_LINES

1:
  mv   t0, s0
  li   t1, 255
2:
  sd   s2, 0(t0)
  addi t1, t1, -1
  bnez t1, 2b

  sd   s4, 0(t0)
  jalr ra, t0, 0

  addi s0, s0, 64
  addi s5, s5, -1
  bnez s5, 1b

  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  fence rw, rw
cryptoexec_encrypt_limit:
pass_tohost:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 1
  sd   t1, 0(t0)
3:
  j    3b

fail:
  fence rw, rw
  li   t0, 0
  csrw 0x3f2, t0
  la   t0, tohost
  li   t1, 3
  sd   t1, 0(t0)
4:
  j    4b

RVTEST_CODE_END

  .data
RVTEST_DATA_BEGIN
  TEST_DATA
RVTEST_DATA_END

  .align 3
tmpl_old_chunk0:
  .dword 0x0000806700000293

  .align 3
tmpl_new_chunk0:
  .dword 0x0000806700000293

  .align 6
probe_lines:
  .rept TRIAL_LINES
    .dword 0x0000806700000293
    .rept 7
      .dword 0x0000001300000013
    .endr
  .endr

  .section .bss
  .align 12
counter_store:
  .space 4096
