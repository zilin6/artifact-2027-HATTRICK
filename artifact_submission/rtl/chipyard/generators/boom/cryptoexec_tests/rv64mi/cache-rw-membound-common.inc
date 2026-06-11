#define cus_reg mscratch
#include "riscv_test.h"
#include "test_macros.h"

#ifndef ENABLE_DCACHE_CRYPTO
#define ENABLE_DCACHE_CRYPTO 1
#endif

#ifndef MEM_TEST_KIND
#error "MEM_TEST_KIND must be defined"
#endif

#ifndef WORK_QWORDS
#error "WORK_QWORDS must be defined"
#endif

#ifndef WORK_BYTES
#error "WORK_BYTES must be defined"
#endif

#ifndef WORK_ROUNDS
#error "WORK_ROUNDS must be defined"
#endif

RVTEST_RV64M
RVTEST_CODE_BEGIN

#if ENABLE_DCACHE_CRYPTO
  la   t0, counter_store
  li   t1, 0x10000000
  sub  t0, t0, t1
  csrw 0x3f3, t0
  li   t0, 3
  csrw 0x3f2, t0
#endif
  fence rw, rw

#if MEM_TEST_KIND == 0
  # load-hot: initialize buf0 with 1 and accumulate over many rounds.
  la   s0, buf0
  li   s1, WORK_QWORDS
  li   s2, 1
1:
  sd   s2, 0(s0)
  addi s0, s0, 8
  addi s1, s1, -1
  bnez s1, 1b
  fence rw, rw

  li   s6, 0
  li   s7, WORK_ROUNDS
2:
  la   s0, buf0
  li   s1, WORK_QWORDS
3:
  ld   t0, 0(s0)
  add  s6, s6, t0
  addi s0, s0, 8
  addi s1, s1, -1
  bnez s1, 3b
  addi s7, s7, -1
  bnez s7, 2b
  fence rw, rw

  li   TESTNUM, 2
  li   t0, (WORK_QWORDS * WORK_ROUNDS)
  bne  s6, t0, fail
#elif MEM_TEST_KIND == 1
  # store-hot: repeatedly overwrite the same hot buffer.
  la   s0, buf0
  li   s1, WORK_QWORDS
  li   s2, 0
1:
  sd   s2, 0(s0)
  addi s0, s0, 8
  addi s1, s1, -1
  bnez s1, 1b
  fence rw, rw

  li   s6, 1
  li   s7, WORK_ROUNDS
2:
  la   s0, buf0
  li   s1, WORK_QWORDS
3:
  sd   s6, 0(s0)
  addi s0, s0, 8
  addi s1, s1, -1
  bnez s1, 3b
  addi s6, s6, 1
  addi s7, s7, -1
  bnez s7, 2b
  fence rw, rw

  la   s0, buf0
  li   s1, WORK_QWORDS
  li   t0, WORK_ROUNDS
  li   TESTNUM, 2
4:
  ld   t1, 0(s0)
  bne  t1, t0, fail
  addi s0, s0, 8
  addi s1, s1, -1
  bnez s1, 4b
#elif MEM_TEST_KIND == 2
  # copy: initialize src with 1, repeatedly copy src -> dst, then checksum dst.
  la   s0, buf0
  la   s1, buf1
  li   s2, WORK_QWORDS
  li   s3, 1
  li   s4, 0
1:
  sd   s3, 0(s0)
  sd   s4, 0(s1)
  addi s0, s0, 8
  addi s1, s1, 8
  addi s2, s2, -1
  bnez s2, 1b
  fence rw, rw

  li   s7, WORK_ROUNDS
2:
  la   s0, buf0
  la   s1, buf1
  li   s2, WORK_QWORDS
3:
  ld   t0, 0(s0)
  sd   t0, 0(s1)
  addi s0, s0, 8
  addi s1, s1, 8
  addi s2, s2, -1
  bnez s2, 3b
  addi s7, s7, -1
  bnez s7, 2b
  fence rw, rw

  la   s1, buf1
  li   s2, WORK_QWORDS
  li   s6, 0
  li   TESTNUM, 2
4:
  ld   t0, 0(s1)
  add  s6, s6, t0
  addi s1, s1, 8
  addi s2, s2, -1
  bnez s2, 4b
  li   t0, WORK_QWORDS
  bne  s6, t0, fail
#else
#error "Unsupported MEM_TEST_KIND"
#endif

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
  .space WORK_BYTES

#if MEM_TEST_KIND == 2
  .align 6
buf1:
  .space WORK_BYTES
#endif
