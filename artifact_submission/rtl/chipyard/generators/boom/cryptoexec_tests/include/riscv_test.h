// Local override for crypto-exec wrapped riscv-tests assembly tests.

#ifndef _BOOM_CRYPTOEXEC_RISCV_TEST_H
#define _BOOM_CRYPTOEXEC_RISCV_TEST_H

#include "../../../../toolchains/riscv-tools/riscv-tests/env/p/riscv_test.h"

#ifndef CRYPTOEXEC_CODE_COUNTER_INIT
#define CRYPTOEXEC_CODE_COUNTER_INIT 0x400
#endif

#ifndef CRYPTOEXEC_ENTRY_DCACHE_ENABLE
#define CRYPTOEXEC_ENTRY_DCACHE_ENABLE 0
#endif

#ifndef CRYPTOEXEC_PRE_ENCRYPT_ENTRY_CODE
#define CRYPTOEXEC_PRE_ENCRYPT_ENTRY_CODE 1
#endif

#ifndef CRYPTOEXEC_AUTO_ENABLE_ICACHE_CRYPTO
#define CRYPTOEXEC_AUTO_ENABLE_ICACHE_CRYPTO 1
#endif

#if CRYPTOEXEC_PRE_ENCRYPT_ENTRY_CODE
#define CRYPTOEXEC_PRE_ENCRYPT_ENTRY_CODE_SEQ                              \
        li s2, -64;                                                     \
        la s0, cryptoexec_test_entry;                                   \
        la s1, cryptoexec_encrypt_limit;                                \
        addi s1, s1, 63;                                                \
        and s0, s0, s2;                                                 \
        and s1, s1, s2;                                                 \
3:      beq s0, s1, 4f;                                                 \
        csrwi 0x3f2, 0;                                                 \
        ld a0,  0(s0);                                                  \
        ld a1,  8(s0);                                                  \
        ld a2, 16(s0);                                                  \
        ld a3, 24(s0);                                                  \
        ld a4, 32(s0);                                                  \
        ld a5, 40(s0);                                                  \
        ld a6, 48(s0);                                                  \
        ld a7, 56(s0);                                                  \
        csrwi 0x3f2, 2;                                                 \
        sd a0,  0(s0);                                                  \
        sd a1,  8(s0);                                                  \
        sd a2, 16(s0);                                                  \
        sd a3, 24(s0);                                                  \
        sd a4, 32(s0);                                                  \
        sd a5, 40(s0);                                                  \
        sd a6, 48(s0);                                                  \
        sd a7, 56(s0);                                                  \
        csrwi 0x3f2, 0;                                                 \
        addi s0, s0, 64;                                                \
        j 3b;                                                           \
4:      fence rw, rw;                                                   \
        fence.i;
#else
#define CRYPTOEXEC_PRE_ENCRYPT_ENTRY_CODE_SEQ
#endif

#if CRYPTOEXEC_AUTO_ENABLE_ICACHE_CRYPTO
#define CRYPTOEXEC_AUTO_ENABLE_ICACHE_CRYPTO_SEQ                          \
        csrwi 0x3f4, 1;
#else
#define CRYPTOEXEC_AUTO_ENABLE_ICACHE_CRYPTO_SEQ
#endif

#undef RVTEST_PASS
#define RVTEST_PASS                                                     \
        fence;                                                          \
        li TESTNUM, 1;                                                  \
        li a7, 93;                                                      \
        li a0, 0;                                                       \
        ecall

#undef RVTEST_FAIL
#define RVTEST_FAIL                                                     \
        fence;                                                          \
1:      beqz TESTNUM, 1b;                                               \
        sll TESTNUM, TESTNUM, 1;                                        \
        or TESTNUM, TESTNUM, 1;                                         \
        li a7, 93;                                                      \
        addi a0, TESTNUM, 0;                                            \
        ecall

#undef RVTEST_CODE_BEGIN
#define RVTEST_CODE_BEGIN                                               \
        .section .text.init;                                            \
        .align  6;                                                      \
        .weak stvec_handler;                                            \
        .weak mtvec_handler;                                            \
        .globl _start;                                                  \
_start:                                                                 \
        j cryptoexec_plain_reset;                                       \
        .align 6;                                                       \
cryptoexec_plain_trap_vector:                                           \
        csrr t5, mcause;                                                \
        li t6, CAUSE_USER_ECALL;                                        \
        beq t5, t6, cryptoexec_plain_write_tohost;                      \
        li t6, CAUSE_SUPERVISOR_ECALL;                                  \
        beq t5, t6, cryptoexec_plain_write_tohost;                      \
        li t6, CAUSE_MACHINE_ECALL;                                     \
        beq t5, t6, cryptoexec_plain_write_tohost;                      \
        la t5, mtvec_handler;                                           \
        beqz t5, 1f;                                                    \
        jr t5;                                                          \
1:      csrr t5, mcause;                                                \
        bgez t5, cryptoexec_plain_other_exception;                      \
        j cryptoexec_plain_other_exception;                             \
cryptoexec_plain_other_exception:                                       \
        ori TESTNUM, TESTNUM, 0x555;                                    \
cryptoexec_plain_write_tohost:                                          \
        csrwi 0x3f4, 0;                                                 \
        csrwi 0x3f2, 0;                                                 \
        sw TESTNUM, tohost, t5;                                         \
        sw zero, tohost + 4, t5;                                        \
        j cryptoexec_plain_write_tohost;                                \
cryptoexec_plain_reset:                                                 \
        INIT_XREG;                                                      \
        RISCV_MULTICORE_DISABLE;                                        \
        la t0, cryptoexec_plain_trap_vector;                            \
        csrw mtvec, t0;                                                 \
        INIT_RNMI;                                                      \
        INIT_SATP;                                                      \
        INIT_PMP;                                                       \
        DELEGATE_NO_TRAPS;                                              \
        la t0, cryptoexec_plain_trap_vector;                            \
        csrw mtvec, t0;                                                 \
        li TESTNUM, 0;                                                  \
        CHECK_XLEN;                                                     \
        csrwi mstatus, 0;                                               \
        init;                                                           \
        EXTRA_INIT;                                                     \
        EXTRA_INIT_TIMER;                                               \
        la t0, cryptoexec_counter_store;                                \
        li t1, 0x10000000;                                              \
        sub t0, t0, t1;                                                 \
        csrw 0x3f3, t0;                                                 \
        csrwi 0x3f4, 0;                                                 \
        csrwi 0x3f2, 0;                                                 \
        csrr t0, mstatus;                                               \
        li t1, MSTATUS_MPP;                                             \
        and t0, t0, t1;                                                 \
        li t1, MSTATUS_MPP;                                             \
        beq t0, t1, 6f;                                                 \
        la t0, cryptoexec_root_pt;                                      \
        li t1, DRAM_BASE;                                               \
        srli t2, t1, 30;                                                \
        slli t2, t2, 3;                                                 \
        add t0, t0, t2;                                                 \
        srli t1, t1, RISCV_PGSHIFT;                                     \
        slli t1, t1, PTE_PPN_SHIFT;                                     \
        li t2, PTE_V | PTE_R | PTE_W | PTE_X | PTE_U | PTE_A | PTE_D;   \
        or t1, t1, t2;                                                  \
        sd t1, 0(t0);                                                   \
        la s3, cryptoexec_counter_store;                                \
        li s4, 0x80000000;                                              \
        li s2, -64;                                                     \
        li s6, CRYPTOEXEC_CODE_COUNTER_INIT;                            \
        la s0, cryptoexec_test_entry;                                   \
        la s1, cryptoexec_encrypt_limit;                                \
        addi s1, s1, 63;                                                \
        and s0, s0, s2;                                                 \
        and s1, s1, s2;                                                 \
        sub s5, s0, s4;                                                 \
        srli s5, s5, 6;                                                 \
        slli s5, s5, 3;                                                 \
        add s3, s3, s5;                                                 \
1:      beq s0, s1, 2f;                                                 \
        sd s6, 0(s3);                                                   \
        addi s0, s0, 64;                                                \
        addi s3, s3, 8;                                                 \
        j 1b;                                                           \
2:      fence rw, rw;                                                   \
        fence.i;                                                        \
        fence rw, rw;                                                   \
        CRYPTOEXEC_PRE_ENCRYPT_ENTRY_CODE_SEQ                            \
        la t0, cryptoexec_root_pt;                                      \
        srli t0, t0, RISCV_PGSHIFT;                                     \
        li t1, SATP_MODE_SV39;                                          \
        slli t1, t1, 60;                                                \
        or t0, t0, t1;                                                  \
        csrw satp, t0;                                                  \
        sfence.vma x0, x0;                                              \
        li t0, CRYPTOEXEC_ENTRY_DCACHE_ENABLE;                          \
        csrw 0x3f2, t0;                                                 \
        la t0, stvec_handler;                                           \
        beqz t0, 5f;                                                    \
        csrw stvec, t0;                                                 \
        li t0, (1 << CAUSE_LOAD_PAGE_FAULT) |                           \
               (1 << CAUSE_STORE_PAGE_FAULT) |                          \
               (1 << CAUSE_FETCH_PAGE_FAULT) |                          \
               (1 << CAUSE_MISALIGNED_FETCH) |                          \
               (1 << CAUSE_USER_ECALL) |                                \
               (1 << CAUSE_BREAKPOINT);                                 \
        csrw medeleg, t0;                                               \
5:      la t0, cryptoexec_plain_trap_vector;                            \
        csrw mtvec, t0;                                                 \
        CRYPTOEXEC_AUTO_ENABLE_ICACHE_CRYPTO_SEQ                         \
        la t0, cryptoexec_test_entry;                                   \
        csrw mepc, t0;                                                  \
        csrr a0, mhartid;                                               \
        mret;                                                           \
6:                                                                      \
        li t0, CRYPTOEXEC_ENTRY_DCACHE_ENABLE;                          \
        csrw 0x3f2, t0;                                                 \
        j cryptoexec_test_entry;                                        \
        .align 6;                                                       \
cryptoexec_test_entry:

#undef RVTEST_CODE_END
#define RVTEST_CODE_END                                                 \
cryptoexec_text_end:                                                    \
        unimp

#undef RVTEST_DATA_BEGIN
#define RVTEST_DATA_BEGIN                                               \
        EXTRA_DATA                                                      \
        .pushsection .tohost,"aw",@progbits;                            \
        .align 6; .global tohost; tohost: .dword 0; .size tohost, 8;    \
        .align 6; .global fromhost; fromhost: .dword 0; .size fromhost, 8; \
        .popsection;                                                    \
        .pushsection .bss,"aw",@nobits;                                 \
        .align 12; .global cryptoexec_counter_store;                    \
cryptoexec_counter_store:                                               \
        .space 65536;                                                   \
        .align 12; .global cryptoexec_root_pt;                          \
cryptoexec_root_pt:                                                     \
        .space 4096;                                                    \
        .popsection;                                                    \
        .align 4; .global begin_signature; begin_signature:

#undef RVTEST_DATA_END
#define RVTEST_DATA_END .align 4; .global end_signature; end_signature:

#endif
