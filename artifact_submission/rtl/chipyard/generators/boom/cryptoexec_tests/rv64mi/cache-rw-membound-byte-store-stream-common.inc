#define cus_reg mscratch
#include "riscv_test.h"
#include "test_macros.h"

#ifndef ENABLE_DCACHE_CRYPTO
#define ENABLE_DCACHE_CRYPTO 1
#endif

#ifndef WORK_LINES
#error "WORK_LINES must be defined"
#endif

#ifndef WORK_ROUNDS
#error "WORK_ROUNDS must be defined"
#endif

#define LINE_BYTES 64
#define TOTAL_BYTES (WORK_LINES * LINE_BYTES)
#define FINAL_ROUND_VALUE (WORK_ROUNDS - 1)

RVTEST_RV64M
RVTEST_CODE_BEGIN

  # Zero the working region before enabling crypto so all line counters start at 0.
  la   s0, buf0
  li   s1, (TOTAL_BYTES / 8)
1:
  sd   x0, 0(s0)
  addi s0, s0, 8
  addi s1, s1, -1
  bnez s1, 1b
  fence rw, rw

#if ENABLE_DCACHE_CRYPTO
  la   t0, counter_store
  li   t1, 0x10000000
  sub  t0, t0, t1
  csrw 0x3f3, t0
  li   t0, 3
  csrw 0x3f2, t0
#endif
  fence rw, rw

  # Main body:
  # For each round, sweep WORK_LINES cache lines.
  # Within each line, execute 64 consecutive sb stores to mirror the memset loop.
  li   s6, 0                    # round index
2:
  la   s0, buf0
  li   s1, WORK_LINES           # remaining lines
  li   s2, 0                    # line index
3:
  add  s3, s2, s6
  andi s3, s3, 0xff             # byte payload for this line in this round
  mv   s4, s0
  li   s5, LINE_BYTES
4:
  sb   s3, 0(s4)
  addi s4, s4, 1
  addi s5, s5, -1
  bnez s5, 4b

  addi s0, s0, LINE_BYTES
  addi s2, s2, 1
  addi s1, s1, -1
  bnez s1, 3b

  addi s6, s6, 1
  li   t0, WORK_ROUNDS
  blt  s6, t0, 2b
  fence rw, rw

  # Verify final plaintext for every byte.
  la   s0, buf0
  li   s1, WORK_LINES
  li   s2, 0
  li   TESTNUM, 2
5:
  li   t0, FINAL_ROUND_VALUE
  add  t0, t0, s2
  andi t0, t0, 0xff
  mv   s4, s0
  li   s5, LINE_BYTES
6:
  lbu  t1, 0(s4)
  bne  t1, t0, fail
  addi s4, s4, 1
  addi s5, s5, -1
  bnez s5, 6b

  addi s0, s0, LINE_BYTES
  addi s2, s2, 1
  addi s1, s1, -1
  bnez s1, 5b

cryptoexec_encrypt_limit:
#if ENABLE_DCACHE_CRYPTO
  li   t0, 0
  csrw 0x3f2, t0
#endif
  RVTEST_PASS

fail:
#if ENABLE_DCACHE_CRYPTO
  li   t0, 0
  csrw 0x3f2, t0
#endif
  RVTEST_FAIL

RVTEST_CODE_END

  .data
RVTEST_DATA_BEGIN
  TEST_DATA
RVTEST_DATA_END

  .section .bss
  .align 12
counter_store:
  .space 4096

  .align 6
buf0:
  .space TOTAL_BYTES
