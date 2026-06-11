#define REGBYTES 8

#define MSTATUS_VS 0x00000600
#define MSTATUS_MPP 0x00001800
#define MSTATUS_MPP_U 0x00000000
#define MSTATUS_MPP_S 0x00000800
#define MSTATUS_FS 0x00006000
#define MSTATUS_XS 0x00018000
#define MSTATUS_MPRV 0x00020000
#define MSTATUS_MPRV_MPP_U MSTATUS_MPRV
#define MSTATUS_MPRV_MPP_S (MSTATUS_MPRV | MSTATUS_MPP_S)

#define MEDELEG_CSR 0x302
#define MIDELEG_CSR 0x303
#define ADDR_CONTROL_CSR 0x3f0
#define DATA_CONTROL_CSR 0x3f1
#define CACHE_CRYPTO_COUNTER_BASE_CSR 0x3f3
#define ADDR_CTRL_LOAD_ENABLE 0x1
#define ADDR_CTRL_STORE_ENABLE 0x2
#define ADDR_CTRL_FETCH_ENABLE 0x4
#define DATA_CTRL_DCACHE_LOAD_ENABLE 0x1
#define DATA_CTRL_DCACHE_STORE_ENABLE 0x2
#define DATA_CTRL_ICACHE_ENABLE 0x4
#define CAUSE_USER_ECALL 0x8
#define CAUSE_SUPERVISOR_ECALL 0x9
#define VM_CUSTOM_ECALL_ID 0x564d435553544f4d
#define VM_MODE_SWITCH_ECALL_ID 0x564d535749544348

#define PMP_R 0x01
#define PMP_W 0x02
#define PMP_X 0x04
#define PMP_NAPOT 0x18

#define DRAM_BASE 0x80000000
#define RISCV_PGSHIFT 12
#define RISCV_PGSIZE (1 << RISCV_PGSHIFT)
#define SATP_MODE_SV39 8

#define PTE_V 0x001
#define PTE_R 0x002
#define PTE_W 0x004
#define PTE_X 0x008
#define PTE_U 0x010
#define PTE_A 0x040
#define PTE_D 0x080
#define PTE_PPN_SHIFT 10

#define VM_VIRT_BASE 0x40000000
#define VM_VIRT_OFFSET (VM_VIRT_BASE - DRAM_BASE)
#define VM_ROOT_PTE_INDEX 1
#define VM_PHYS_ROOT_PTE_INDEX 2
#define VM_L1_RUNTIME_INDEX 0
#define VM_L1_IO_INDEX 1
#define COUNTER_STORE_LINK_BASE 0x80200000
#define TOHOST_LINK_BASE 0x80300000
#define CACHE_CRYPTO_COUNTER_STORE_BYTES (64 * 1024)
#define CACHE_CRYPTO_COUNTER_BASE_BIAS(addr) ((addr) >> 3)
#define CACHE_CRYPTO_COUNTER_STORE_PAGES (CACHE_CRYPTO_COUNTER_STORE_BYTES / RISCV_PGSIZE)
#define VM_ENC_PAGE_TABLE_POOL_PAGES 32
#define S_PLAIN_PAGE_TABLE_POOL_PAGES 32
#define VM_PTE_TABLE_FLAGS PTE_V
#define VM_PTE_RUNTIME_FLAGS (PTE_V | PTE_R | PTE_W | PTE_X | PTE_U | PTE_A | PTE_D)
#define VM_PTE_DATA_FLAGS (PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D)
#define VM_PROBE_ACTIVE_FLAG 1
#define VM_TRAPFRAME_GPR_WORDS 36
#define VM_TRAPFRAME_FPR_WORDS 32
#define VM_TRAPFRAME_F0_SLOT VM_TRAPFRAME_GPR_WORDS
#define VM_TRAPFRAME_FCSR_SLOT (VM_TRAPFRAME_F0_SLOT + VM_TRAPFRAME_FPR_WORDS)
#define VM_TRAPFRAME_WORDS (VM_TRAPFRAME_FCSR_SLOT + 1)
#define VM_TRAPFRAME_BYTES (VM_TRAPFRAME_WORDS * REGBYTES)

    .macro SREG reg, mem
    sd \reg, \mem
    .endm

    .macro LREG reg, mem
    ld \reg, \mem
    .endm

    .macro RESTORE_GLOBAL_TRAPFRAME_GPRS_EXCEPT_T0_SP
    LREG x1, 1*REGBYTES(sp)
    LREG x3, 3*REGBYTES(sp)
    LREG x4, 4*REGBYTES(sp)
    LREG x6, 6*REGBYTES(sp)
    LREG x7, 7*REGBYTES(sp)
    LREG x8, 8*REGBYTES(sp)
    LREG x9, 9*REGBYTES(sp)
    LREG x10, 10*REGBYTES(sp)
    LREG x11, 11*REGBYTES(sp)
    LREG x12, 12*REGBYTES(sp)
    LREG x13, 13*REGBYTES(sp)
    LREG x14, 14*REGBYTES(sp)
    LREG x15, 15*REGBYTES(sp)
    LREG x16, 16*REGBYTES(sp)
    LREG x17, 17*REGBYTES(sp)
    LREG x18, 18*REGBYTES(sp)
    LREG x19, 19*REGBYTES(sp)
    LREG x20, 20*REGBYTES(sp)
    LREG x21, 21*REGBYTES(sp)
    LREG x22, 22*REGBYTES(sp)
    LREG x23, 23*REGBYTES(sp)
    LREG x24, 24*REGBYTES(sp)
    LREG x25, 25*REGBYTES(sp)
    LREG x26, 26*REGBYTES(sp)
    LREG x27, 27*REGBYTES(sp)
    LREG x28, 28*REGBYTES(sp)
    LREG x29, 29*REGBYTES(sp)
    LREG x30, 30*REGBYTES(sp)
    LREG x31, 31*REGBYTES(sp)
    .endm

    .macro SAVE_TRAPFRAME_FPRS base
    fsd f0, (VM_TRAPFRAME_F0_SLOT + 0)*REGBYTES(\base)
    fsd f1, (VM_TRAPFRAME_F0_SLOT + 1)*REGBYTES(\base)
    fsd f2, (VM_TRAPFRAME_F0_SLOT + 2)*REGBYTES(\base)
    fsd f3, (VM_TRAPFRAME_F0_SLOT + 3)*REGBYTES(\base)
    fsd f4, (VM_TRAPFRAME_F0_SLOT + 4)*REGBYTES(\base)
    fsd f5, (VM_TRAPFRAME_F0_SLOT + 5)*REGBYTES(\base)
    fsd f6, (VM_TRAPFRAME_F0_SLOT + 6)*REGBYTES(\base)
    fsd f7, (VM_TRAPFRAME_F0_SLOT + 7)*REGBYTES(\base)
    fsd f8, (VM_TRAPFRAME_F0_SLOT + 8)*REGBYTES(\base)
    fsd f9, (VM_TRAPFRAME_F0_SLOT + 9)*REGBYTES(\base)
    fsd f10, (VM_TRAPFRAME_F0_SLOT + 10)*REGBYTES(\base)
    fsd f11, (VM_TRAPFRAME_F0_SLOT + 11)*REGBYTES(\base)
    fsd f12, (VM_TRAPFRAME_F0_SLOT + 12)*REGBYTES(\base)
    fsd f13, (VM_TRAPFRAME_F0_SLOT + 13)*REGBYTES(\base)
    fsd f14, (VM_TRAPFRAME_F0_SLOT + 14)*REGBYTES(\base)
    fsd f15, (VM_TRAPFRAME_F0_SLOT + 15)*REGBYTES(\base)
    fsd f16, (VM_TRAPFRAME_F0_SLOT + 16)*REGBYTES(\base)
    fsd f17, (VM_TRAPFRAME_F0_SLOT + 17)*REGBYTES(\base)
    fsd f18, (VM_TRAPFRAME_F0_SLOT + 18)*REGBYTES(\base)
    fsd f19, (VM_TRAPFRAME_F0_SLOT + 19)*REGBYTES(\base)
    fsd f20, (VM_TRAPFRAME_F0_SLOT + 20)*REGBYTES(\base)
    fsd f21, (VM_TRAPFRAME_F0_SLOT + 21)*REGBYTES(\base)
    fsd f22, (VM_TRAPFRAME_F0_SLOT + 22)*REGBYTES(\base)
    fsd f23, (VM_TRAPFRAME_F0_SLOT + 23)*REGBYTES(\base)
    fsd f24, (VM_TRAPFRAME_F0_SLOT + 24)*REGBYTES(\base)
    fsd f25, (VM_TRAPFRAME_F0_SLOT + 25)*REGBYTES(\base)
    fsd f26, (VM_TRAPFRAME_F0_SLOT + 26)*REGBYTES(\base)
    fsd f27, (VM_TRAPFRAME_F0_SLOT + 27)*REGBYTES(\base)
    fsd f28, (VM_TRAPFRAME_F0_SLOT + 28)*REGBYTES(\base)
    fsd f29, (VM_TRAPFRAME_F0_SLOT + 29)*REGBYTES(\base)
    fsd f30, (VM_TRAPFRAME_F0_SLOT + 30)*REGBYTES(\base)
    fsd f31, (VM_TRAPFRAME_F0_SLOT + 31)*REGBYTES(\base)
    csrr t0, fcsr
    SREG t0, VM_TRAPFRAME_FCSR_SLOT*REGBYTES(\base)
    .endm

    .macro RESTORE_TRAPFRAME_FPRS base
    fld f0, (VM_TRAPFRAME_F0_SLOT + 0)*REGBYTES(\base)
    fld f1, (VM_TRAPFRAME_F0_SLOT + 1)*REGBYTES(\base)
    fld f2, (VM_TRAPFRAME_F0_SLOT + 2)*REGBYTES(\base)
    fld f3, (VM_TRAPFRAME_F0_SLOT + 3)*REGBYTES(\base)
    fld f4, (VM_TRAPFRAME_F0_SLOT + 4)*REGBYTES(\base)
    fld f5, (VM_TRAPFRAME_F0_SLOT + 5)*REGBYTES(\base)
    fld f6, (VM_TRAPFRAME_F0_SLOT + 6)*REGBYTES(\base)
    fld f7, (VM_TRAPFRAME_F0_SLOT + 7)*REGBYTES(\base)
    fld f8, (VM_TRAPFRAME_F0_SLOT + 8)*REGBYTES(\base)
    fld f9, (VM_TRAPFRAME_F0_SLOT + 9)*REGBYTES(\base)
    fld f10, (VM_TRAPFRAME_F0_SLOT + 10)*REGBYTES(\base)
    fld f11, (VM_TRAPFRAME_F0_SLOT + 11)*REGBYTES(\base)
    fld f12, (VM_TRAPFRAME_F0_SLOT + 12)*REGBYTES(\base)
    fld f13, (VM_TRAPFRAME_F0_SLOT + 13)*REGBYTES(\base)
    fld f14, (VM_TRAPFRAME_F0_SLOT + 14)*REGBYTES(\base)
    fld f15, (VM_TRAPFRAME_F0_SLOT + 15)*REGBYTES(\base)
    fld f16, (VM_TRAPFRAME_F0_SLOT + 16)*REGBYTES(\base)
    fld f17, (VM_TRAPFRAME_F0_SLOT + 17)*REGBYTES(\base)
    fld f18, (VM_TRAPFRAME_F0_SLOT + 18)*REGBYTES(\base)
    fld f19, (VM_TRAPFRAME_F0_SLOT + 19)*REGBYTES(\base)
    fld f20, (VM_TRAPFRAME_F0_SLOT + 20)*REGBYTES(\base)
    fld f21, (VM_TRAPFRAME_F0_SLOT + 21)*REGBYTES(\base)
    fld f22, (VM_TRAPFRAME_F0_SLOT + 22)*REGBYTES(\base)
    fld f23, (VM_TRAPFRAME_F0_SLOT + 23)*REGBYTES(\base)
    fld f24, (VM_TRAPFRAME_F0_SLOT + 24)*REGBYTES(\base)
    fld f25, (VM_TRAPFRAME_F0_SLOT + 25)*REGBYTES(\base)
    fld f26, (VM_TRAPFRAME_F0_SLOT + 26)*REGBYTES(\base)
    fld f27, (VM_TRAPFRAME_F0_SLOT + 27)*REGBYTES(\base)
    fld f28, (VM_TRAPFRAME_F0_SLOT + 28)*REGBYTES(\base)
    fld f29, (VM_TRAPFRAME_F0_SLOT + 29)*REGBYTES(\base)
    fld f30, (VM_TRAPFRAME_F0_SLOT + 30)*REGBYTES(\base)
    fld f31, (VM_TRAPFRAME_F0_SLOT + 31)*REGBYTES(\base)
    LREG t0, VM_TRAPFRAME_FCSR_SLOT*REGBYTES(\base)
    csrw fcsr, t0
    .endm

    .macro COPY_CURRENT_TRAPFRAME dst
    mv t3, sp
    mv t4, \dst
    li t1, VM_TRAPFRAME_WORDS
1:
    ld t0, 0(t3)
    sd t0, 0(t4)
    addi t3, t3, REGBYTES
    addi t4, t4, REGBYTES
    addi t1, t1, -1
    bnez t1, 1b
    .endm

    /* Linker-script PROVIDE'd absolute symbols must not be relaxed into
       gp-relative addressing, or large images can fail to link. */
    .macro LA_ABS reg, sym
    .option push
    .option norelax
    la \reg, \sym
    .option pop
    .endm

    .macro WRITE_CONTROL_ADDR_LOAD reg
    csrr t4, ADDR_CONTROL_CSR
    li t3, ~ADDR_CTRL_LOAD_ENABLE
    and t4, t4, t3
    andi t3, \reg, ADDR_CTRL_LOAD_ENABLE
    or t4, t4, t3
    csrw ADDR_CONTROL_CSR, t4
    .endm

    .macro WRITE_CONTROL_ADDR_STORE reg
    csrr t4, ADDR_CONTROL_CSR
    li t3, ~ADDR_CTRL_STORE_ENABLE
    and t4, t4, t3
    slli t3, \reg, 1
    andi t3, t3, ADDR_CTRL_STORE_ENABLE
    or t4, t4, t3
    csrw ADDR_CONTROL_CSR, t4
    .endm

    .macro WRITE_CONTROL_ADDR_FETCH reg
    csrr t4, ADDR_CONTROL_CSR
    li t3, ~ADDR_CTRL_FETCH_ENABLE
    and t4, t4, t3
    slli t3, \reg, 2
    andi t3, t3, ADDR_CTRL_FETCH_ENABLE
    or t4, t4, t3
    csrw ADDR_CONTROL_CSR, t4
    .endm

    .macro WRITE_CONTROL_DCACHE reg
    csrr t4, DATA_CONTROL_CSR
    li t3, ~(DATA_CTRL_DCACHE_LOAD_ENABLE | DATA_CTRL_DCACHE_STORE_ENABLE)
    and t4, t4, t3
    andi t3, \reg, (DATA_CTRL_DCACHE_LOAD_ENABLE | DATA_CTRL_DCACHE_STORE_ENABLE)
    or t4, t4, t3
    csrw DATA_CONTROL_CSR, t4
    .endm

    .macro WRITE_CONTROL_ICACHE reg
    csrr t4, DATA_CONTROL_CSR
    li t3, ~DATA_CTRL_ICACHE_ENABLE
    and t4, t4, t3
    slli t3, \reg, 2
    andi t3, t3, DATA_CTRL_ICACHE_ENABLE
    or t4, t4, t3
    csrw DATA_CONTROL_CSR, t4
    .endm

    .macro CLEAR_ALL_CRYPTO_CSR
    csrwi ADDR_CONTROL_CSR, 0
    csrwi DATA_CONTROL_CSR, 0
    .endm

    .section .text.init
    .global _start
    .type _start, @function
_start:
    .cfi_startproc

    li x1, 0
    li x2, 0
    li x3, 0
    li x4, 0
    li x5, 0
    li x6, 0
    li x7, 0
    li x8, 0
    li x9, 0
    li x10, 0
    li x11, 0
    li x12, 0
    li x13, 0
    li x14, 0
    li x15, 0
    li x16, 0
    li x17, 0
    li x18, 0
    li x19, 0
    li x20, 0
    li x21, 0
    li x22, 0
    li x23, 0
    li x24, 0
    li x25, 0
    li x26, 0
    li x27, 0
    li x28, 0
    li x29, 0
    li x30, 0
    li x31, 0

    li t0, (MSTATUS_FS | MSTATUS_XS | MSTATUS_VS)
    csrs mstatus, t0

#ifdef __riscv_flen
    la t0, 1f
    csrw mtvec, t0

    fscsr zero
    fmv.s.x f0,  zero
    fmv.s.x f1,  zero
    fmv.s.x f2,  zero
    fmv.s.x f3,  zero
    fmv.s.x f4,  zero
    fmv.s.x f5,  zero
    fmv.s.x f6,  zero
    fmv.s.x f7,  zero
    fmv.s.x f8,  zero
    fmv.s.x f9,  zero
    fmv.s.x f10, zero
    fmv.s.x f11, zero
    fmv.s.x f12, zero
    fmv.s.x f13, zero
    fmv.s.x f14, zero
    fmv.s.x f15, zero
    fmv.s.x f16, zero
    fmv.s.x f17, zero
    fmv.s.x f18, zero
    fmv.s.x f19, zero
    fmv.s.x f20, zero
    fmv.s.x f21, zero
    fmv.s.x f22, zero
    fmv.s.x f23, zero
    fmv.s.x f24, zero
    fmv.s.x f25, zero
    fmv.s.x f26, zero
    fmv.s.x f27, zero
    fmv.s.x f28, zero
    fmv.s.x f29, zero
    fmv.s.x f30, zero
    fmv.s.x f31, zero
1:
#endif

    .option push
    .option norelax
    la gp, __global_pointer$
    .option pop

    csrr s0, mhartid
    lui t0, %hi(__stack_shift)
    addi t0, t0, %lo(__stack_shift)
    la tp, __stack_start
    sll t0, s0, t0
    add tp, tp, t0

    lui t0, %hi(__stack_size)
    addi t0, t0, %lo(__stack_size)
    add sp, tp, t0

    lui t1, %hi(__m_trap_stack_shift)
    addi t1, t1, %lo(__m_trap_stack_shift)
    la t2, __m_trap_stack_start
    sll t1, s0, t1
    add t2, t2, t1
    lui t1, %hi(__m_trap_stack_size)
    addi t1, t1, %lo(__m_trap_stack_size)
    add t2, t2, t1
    csrw mscratch, t2

    la t0, trap_entry
    csrw mtvec, t0
    /* All traps are handled by the M-mode trap handler in this benchmark
       runtime. Do not delegate exceptions/interrupts to S-mode. */
    csrw MEDELEG_CSR, zero
    csrw MIDELEG_CSR, zero

    li t0, -1
    srli t0, t0, 10
    li t2, 0x1f1f1f1f1f1f1f1f
    csrw pmpaddr0, t0
    csrw pmpaddr1, t0
    csrw pmpaddr2, t0
    csrw pmpaddr3, t0
    csrw pmpaddr4, t0
    csrw pmpaddr5, t0
    csrw pmpaddr6, t0
    csrw pmpaddr7, t0
    csrw pmpcfg0, t2

    call __init_tls

    la t0, __boot_hart
    bne s0, t0, _start_secondary

    la t0, __bss_start
    la t1, __bss_end
    bgeu t0, t1, 2f
1:
    SREG zero, (t0)
    addi t0, t0, REGBYTES
    bltu t0, t1, 1b
2:

    la a0, __libc_fini_array
    call atexit
    call __libc_init_array

    call initialise_board
    call initialise_benchmark

    call vm_generate_and_store_u1_keys
    call vm_generate_and_store_u2_keys
    mv a0, tp
    mv a1, sp
    call vm_prepare_u2_encrypted_runtime
    call vm_load_u1_keys

    li a0, 0x11
    jal ra, vm_stage_emit_asm
    .word 0x10000013

    la t0, vm_root_page_table
    la t1, vm_l1_page_table
    srli t1, t1, RISCV_PGSHIFT
    slli t1, t1, PTE_PPN_SHIFT
    li t2, VM_PTE_TABLE_FLAGS
    or t1, t1, t2
    sd t1, (VM_ROOT_PTE_INDEX * REGBYTES)(t0)

    la t0, vm_l1_page_table
    la t1, vm_l0_runtime_page_table
    srli t1, t1, RISCV_PGSHIFT
    slli t1, t1, PTE_PPN_SHIFT
    li t2, VM_PTE_TABLE_FLAGS
    or t1, t1, t2
    sd t1, (VM_L1_RUNTIME_INDEX * REGBYTES)(t0)

    la t0, vm_l1_page_table
    la t1, vm_l0_io_page_table
    srli t1, t1, RISCV_PGSHIFT
    slli t1, t1, PTE_PPN_SHIFT
    li t2, VM_PTE_TABLE_FLAGS
    or t1, t1, t2
    sd t1, (VM_L1_IO_INDEX * REGBYTES)(t0)

    la a0, vm_l0_runtime_page_table
    li a1, VM_VIRT_BASE
    li a2, DRAM_BASE
    LA_ABS a3, __vm_runtime_phys_end
    li a4, VM_PTE_RUNTIME_FLAGS
    jal ra, vm_map_range

    la a0, vm_l0_runtime_page_table
    mv a1, tp
    li t0, VM_VIRT_OFFSET
    add a1, a1, t0
    mv a2, tp
    mv a3, sp
    li a4, VM_PTE_RUNTIME_FLAGS
    jal ra, vm_map_range

    la a0, vm_l0_io_page_table
    li a1, VM_VIRT_BASE + (COUNTER_STORE_LINK_BASE - DRAM_BASE)
    li a2, COUNTER_STORE_LINK_BASE
    li a3, COUNTER_STORE_LINK_BASE + CACHE_CRYPTO_COUNTER_STORE_BYTES
    li a4, VM_PTE_DATA_FLAGS
    jal ra, vm_map_range

    la t0, vm_root_page_table
    la t1, vm_phys_l1_page_table
    srli t1, t1, RISCV_PGSHIFT
    slli t1, t1, PTE_PPN_SHIFT
    li t2, VM_PTE_TABLE_FLAGS
    or t1, t1, t2
    sd t1, (VM_PHYS_ROOT_PTE_INDEX * REGBYTES)(t0)

    la t0, vm_phys_l1_page_table
    la t1, vm_phys_l0_runtime_page_table
    srli t1, t1, RISCV_PGSHIFT
    slli t1, t1, PTE_PPN_SHIFT
    li t2, VM_PTE_TABLE_FLAGS
    or t1, t1, t2
    sd t1, (VM_L1_RUNTIME_INDEX * REGBYTES)(t0)

    la t0, vm_phys_l1_page_table
    la a0, vm_phys_l0_runtime_page_table
    li a1, DRAM_BASE
    li a2, DRAM_BASE
    LA_ABS a3, __vm_runtime_phys_end
    li a4, VM_PTE_RUNTIME_FLAGS
    jal ra, vm_map_range

    la a0, vm_phys_l0_runtime_page_table
    mv a1, tp
    mv a2, tp
    mv a3, sp
    li a4, VM_PTE_RUNTIME_FLAGS
    jal ra, vm_map_range

    la t0, vm_phys_l1_page_table
    la t1, vm_phys_l0_io_page_table
    srli t1, t1, RISCV_PGSHIFT
    slli t1, t1, PTE_PPN_SHIFT
    li t2, VM_PTE_TABLE_FLAGS
    or t1, t1, t2
    sd t1, (VM_L1_IO_INDEX * REGBYTES)(t0)

    la a0, vm_phys_l0_io_page_table
    li a1, TOHOST_LINK_BASE
    li a2, TOHOST_LINK_BASE
    li a3, TOHOST_LINK_BASE + RISCV_PGSIZE
    li a4, VM_PTE_DATA_FLAGS
    jal ra, vm_map_range

    la a0, vm_l0_io_page_table
    li a1, VM_VIRT_BASE + (TOHOST_LINK_BASE - DRAM_BASE)
    li a2, TOHOST_LINK_BASE
    li a3, TOHOST_LINK_BASE + RISCV_PGSIZE
    li a4, VM_PTE_DATA_FLAGS
    jal ra, vm_map_range
    li a0, 0x12
    jal ra, vm_stage_emit_asm
    .word 0x11100013

    li t0, 0
    CLEAR_ALL_CRYPTO_CSR
    fence rw, rw

    li t0, -1
    csrw mcounteren, t0

    li t0, -1
    srli t0, t0, 10
    li t2, 0x1f1f1f1f1f1f1f1f
    csrw pmpaddr0, t0
    csrw pmpaddr1, t0
    csrw pmpaddr2, t0
    csrw pmpaddr3, t0
    csrw pmpaddr4, t0
    csrw pmpaddr5, t0
    csrw pmpaddr6, t0
    csrw pmpaddr7, t0
    csrw pmpcfg0, t2

    la t0, vm_root_page_table
    srli t0, t0, RISCV_PGSHIFT
    li t1, (SATP_MODE_SV39 << 60)
    or t0, t0, t1
    csrw satp, t0
    sfence.vma x0, x0

#ifdef VM_PLAIN_NO_ADDR_CRYPTO
vm_probe_range_last_return:
    li a0, 0x13
    jal ra, vm_stage_emit_asm
    .word 0x11200013

    la t0, counter_store
    li t2, CACHE_CRYPTO_COUNTER_BASE_BIAS(VM_VIRT_BASE)
    sub t0, t0, t2
    csrw CACHE_CRYPTO_COUNTER_BASE_CSR, t0
    fence rw, rw
    li a0, 0x143
    jal ra, vm_stage_emit_asm

    li a0, 0x15
    jal ra, vm_stage_emit_asm

    la t0, vm_root_page_table
    srli t0, t0, RISCV_PGSHIFT
    li t1, (SATP_MODE_SV39 << 60)
    or t0, t0, t1
    csrw satp, t0
    sfence.vma x0, x0

    li t0, MSTATUS_MPP
    csrc mstatus, t0
    li t0, MSTATUS_MPRV_MPP_U
    csrs mstatus, t0

    li t0, 2
    WRITE_CONTROL_DCACHE t0
    fence rw, rw
    .word 0x11400013

    LA_ABS t0, __crypto_runtime_start
    li t1, VM_VIRT_OFFSET
    add t0, t0, t1
    LA_ABS t2, __crypto_runtime_end
    add t2, t2, t1
    li t5, -64
    and t0, t0, t5
    and t2, t2, t5
    addi t4, t0, 64
    beq t0, t2, 7f
6:
    ld a0,  0(t0)
    ld a1,  8(t0)
    ld a2, 16(t0)
    ld a3, 24(t0)
    ld a4, 32(t0)
    ld a5, 40(t0)
    ld a6, 48(t0)
    ld a7, 56(t0)
    sd a0,  0(t0)
    sd a1,  8(t0)
    sd a2, 16(t0)
    sd a3, 24(t0)
    sd a4, 32(t0)
    sd a5, 40(t0)
    sd a6, 48(t0)
    sd a7, 56(t0)
    addi t0, t0, 64
    bne t0, t4, 9f
    mv s1, t0
    mv s2, t2
    li t6, 0
    WRITE_CONTROL_DCACHE t6
    li t6, MSTATUS_MPRV
    csrc mstatus, t6
    fence rw, rw
    li a0, 0x151
    jal ra, vm_stage_emit_asm
    la t6, vm_root_page_table
    srli t6, t6, RISCV_PGSHIFT
    li t5, (SATP_MODE_SV39 << 60)
    or t6, t6, t5
    csrw satp, t6
    sfence.vma x0, x0
    li t6, MSTATUS_MPRV_MPP_U
    csrs mstatus, t6
    li t6, 2
    WRITE_CONTROL_DCACHE t6
    fence rw, rw
    mv t0, s1
    mv t2, s2
9:
    bne t0, t2, 6b
    mv s1, t0
    mv s2, t2
    li t6, 0
    WRITE_CONTROL_DCACHE t6
    li t6, MSTATUS_MPRV
    csrc mstatus, t6
    fence rw, rw
    li a0, 0x152
    jal ra, vm_stage_emit_asm
    li a0, 0x153
    jal ra, vm_stage_emit_asm
7:
    fence rw, rw
    li t6, 0
    WRITE_CONTROL_DCACHE t6
    li t6, MSTATUS_MPRV
    csrc mstatus, t6
    fence rw, rw
    li a0, 0x16
    jal ra, vm_stage_emit_asm
    .word 0x11500013

    li t0, MSTATUS_MPRV
    csrc mstatus, t0

    la t0, vm_root_page_table
    srli t0, t0, RISCV_PGSHIFT
    li t1, (SATP_MODE_SV39 << 60)
    or t0, t0, t1
    csrw satp, t0
    sfence.vma x0, x0
    li a0, 0x161
    jal ra, vm_stage_emit_asm

    li t6, 0
    CLEAR_ALL_CRYPTO_CSR
    fence rw, rw
    li a0, 0x165
    jal ra, vm_stage_emit_asm

    li t0, VM_VIRT_OFFSET
    add gp, gp, t0
    add tp, tp, t0
    add sp, sp, t0

    li t0, VM_VIRT_OFFSET
    la t1, embench_cryptoexec_main
    add t1, t1, t0
    csrw mepc, t1
    li t1, MSTATUS_MPP
    csrc mstatus, t1
    li t1, MSTATUS_MPP_U
    csrs mstatus, t1

    li t6, 0
    CLEAR_ALL_CRYPTO_CSR
    fence rw, rw
    li a0, 0x181
    jal ra, vm_stage_emit_asm

    li t6, 3
    WRITE_CONTROL_DCACHE t6
    li t6, 1
    WRITE_CONTROL_ICACHE t6
    li t6, 0
    WRITE_CONTROL_ADDR_STORE t6
    WRITE_CONTROL_ADDR_LOAD t6
    WRITE_CONTROL_ADDR_FETCH t6
    fence rw, rw
    mret
#else
    mv a0, tp
    mv a1, sp
    jal ra, vm_init_encrypted_page_tables
    li a0, 0x13
    jal ra, vm_stage_emit_asm
    .word 0x11200013

    la t0, vm_enc_root_page_table
    srli t0, t0, RISCV_PGSHIFT
    li t1, (SATP_MODE_SV39 << 60)
    or t0, t0, t1
    csrw satp, t0
    sfence.vma x0, x0

    la t0, embench_cryptoexec_main
    li t1, VM_VIRT_OFFSET
    add t0, t0, t1
    li t1, -4096
    and t0, t0, t1
    la t1, vm_probe_watch_va
    sd t0, 0(t1)

    li a1, VM_VIRT_BASE
    li a2, DRAM_BASE
    LA_ABS a3, __vm_runtime_phys_end
    li a4, VM_PTE_RUNTIME_FLAGS
    jal ra, vm_probe_range

    mv a1, tp
    li t0, VM_VIRT_OFFSET
    add a1, a1, t0
    mv a2, tp
    mv a3, sp
    li a4, VM_PTE_RUNTIME_FLAGS
    jal ra, vm_probe_range

    mv a2, tp
    li t0, VM_VIRT_OFFSET
    add a1, a2, t0
    mv a3, sp
    li a4, VM_PTE_RUNTIME_FLAGS
    jal ra, vm_probe_range_store

    li a1, VM_VIRT_BASE + (COUNTER_STORE_LINK_BASE - DRAM_BASE)
    li a2, COUNTER_STORE_LINK_BASE
    li a3, COUNTER_STORE_LINK_BASE + CACHE_CRYPTO_COUNTER_STORE_BYTES
    li a4, VM_PTE_DATA_FLAGS
    jal ra, vm_probe_range

    li a1, VM_VIRT_BASE + (TOHOST_LINK_BASE - DRAM_BASE)
    li a2, TOHOST_LINK_BASE
    li a3, TOHOST_LINK_BASE + RISCV_PGSIZE
    li a4, VM_PTE_DATA_FLAGS
    jal ra, vm_probe_range

    LA_ABS a2, __crypto_writable_pages_start
    li t0, VM_VIRT_OFFSET
    add a1, a2, t0
    LA_ABS a3, __crypto_writable_pages_end
    li a4, VM_PTE_DATA_FLAGS
    jal ra, vm_probe_range_store

    LA_ABS a2, __crypto_runtime_bss_start
    li t0, VM_VIRT_OFFSET
    add a1, a2, t0
    LA_ABS a3, __crypto_runtime_bss_end
    li a4, VM_PTE_DATA_FLAGS
    jal ra, vm_probe_range_store

    li a1, DRAM_BASE
    li a2, DRAM_BASE
    LA_ABS a3, __vm_runtime_phys_end
    li a4, VM_PTE_RUNTIME_FLAGS
    jal ra, vm_probe_range

    mv a1, tp
    mv a2, tp
    mv a3, sp
    li a4, VM_PTE_RUNTIME_FLAGS
    jal ra, vm_probe_range

    li a1, TOHOST_LINK_BASE
    li a2, TOHOST_LINK_BASE
    li a3, TOHOST_LINK_BASE + RISCV_PGSIZE
    li a4, VM_PTE_DATA_FLAGS
    la t0, vm_probe_force_direct_return
    li t1, 1
    sd t1, 0(t0)
    jal ra, vm_probe_range

vm_probe_range_last_return:

    jal ra, vmembench_debug_dump_probe_watch

    li a0, 0x14
    jal ra, vm_stage_emit_asm
    .word 0x11300013

    li t0, 0
    CLEAR_ALL_CRYPTO_CSR
    li t0, MSTATUS_MPRV
    csrc mstatus, t0
    fence rw, rw
    li a0, 0x141
    jal ra, vm_stage_emit_asm

    la t0, vm_root_page_table
    srli t0, t0, RISCV_PGSHIFT
    li t1, (SATP_MODE_SV39 << 60)
    or t0, t0, t1
    csrw satp, t0
    sfence.vma x0, x0
    li a0, 0x142
    jal ra, vm_stage_emit_asm

    la t0, counter_store
    li t2, CACHE_CRYPTO_COUNTER_BASE_BIAS(VM_VIRT_BASE)
    sub t0, t0, t2
    csrw CACHE_CRYPTO_COUNTER_BASE_CSR, t0
    fence rw, rw
    li a0, 0x143
    jal ra, vm_stage_emit_asm

    li a0, 0x15
    jal ra, vm_stage_emit_asm

    la t0, vm_root_page_table
    srli t0, t0, RISCV_PGSHIFT
    li t1, (SATP_MODE_SV39 << 60)
    or t0, t0, t1
    csrw satp, t0
    sfence.vma x0, x0

    li t0, MSTATUS_MPP
    csrc mstatus, t0
    li t0, MSTATUS_MPRV_MPP_U
    csrs mstatus, t0

    li t0, 2
    WRITE_CONTROL_DCACHE t0
    fence rw, rw
    .word 0x11400013

    LA_ABS t0, __crypto_runtime_start
    li t1, VM_VIRT_OFFSET
    add t0, t0, t1
    LA_ABS t2, __crypto_runtime_end
    add t2, t2, t1
    li t5, -64
    and t0, t0, t5
    and t2, t2, t5
    addi t4, t0, 64
    beq t0, t2, 5f
4:
    ld a0,  0(t0)
    ld a1,  8(t0)
    ld a2, 16(t0)
    ld a3, 24(t0)
    ld a4, 32(t0)
    ld a5, 40(t0)
    ld a6, 48(t0)
    ld a7, 56(t0)
    sd a0,  0(t0)
    sd a1,  8(t0)
    sd a2, 16(t0)
    sd a3, 24(t0)
    sd a4, 32(t0)
    sd a5, 40(t0)
    sd a6, 48(t0)
    sd a7, 56(t0)
    addi t0, t0, 64
    bne t0, t4, 8f
    mv s1, t0
    mv s2, t2
    li t6, 0
    WRITE_CONTROL_DCACHE t6
    li t6, MSTATUS_MPRV
    csrc mstatus, t6
    fence rw, rw
    li a0, 0x151
    jal ra, vm_stage_emit_asm
    la t6, vm_root_page_table
    srli t6, t6, RISCV_PGSHIFT
    li t5, (SATP_MODE_SV39 << 60)
    or t6, t6, t5
    csrw satp, t6
    sfence.vma x0, x0
    li t6, MSTATUS_MPRV_MPP_U
    csrs mstatus, t6
    li t6, 2
    WRITE_CONTROL_DCACHE t6
    fence rw, rw
    mv t0, s1
    mv t2, s2
8:
    bne t0, t2, 4b
    mv s1, t0
    mv s2, t2
    li t6, 0
    WRITE_CONTROL_DCACHE t6
    li t6, MSTATUS_MPRV
    csrc mstatus, t6
    fence rw, rw
    li a0, 0x152
    jal ra, vm_stage_emit_asm
    li a0, 0x153
    jal ra, vm_stage_emit_asm
5:
    fence rw, rw
    li t6, 0
    WRITE_CONTROL_DCACHE t6
    li t6, MSTATUS_MPRV
    csrc mstatus, t6
    fence rw, rw
    li a0, 0x16
    jal ra, vm_stage_emit_asm
    .word 0x11500013

    li t0, MSTATUS_MPRV
    csrc mstatus, t0

    la t0, vm_enc_root_page_table
    srli t0, t0, RISCV_PGSHIFT
    li t1, (SATP_MODE_SV39 << 60)
    or t0, t0, t1
    csrw satp, t0
    sfence.vma x0, x0
    /* Original startup stress block, intentionally disabled:
       li a0, 0x161
       jal ra, vm_stage_emit_asm

       li t0, 3
       WRITE_CONTROL_DCACHE t0
       fence rw, rw
       li t6, 0
       WRITE_CONTROL_DCACHE t6
       fence rw, rw
       li a0, 0x162
       jal ra, vm_stage_emit_asm
       li t6, 3
       WRITE_CONTROL_DCACHE t6
       fence rw, rw

       li t0, 1
       WRITE_CONTROL_ICACHE t0
       li t6, 0
       WRITE_CONTROL_DCACHE t6
       fence rw, rw
       li a0, 0x163
       jal ra, vm_stage_emit_asm
       li t6, 3
       WRITE_CONTROL_DCACHE t6
       fence rw, rw

       li t6, 0
       WRITE_CONTROL_DCACHE t6
       fence rw, rw
       li a0, 0x164
       jal ra, vm_stage_emit_asm
       li t6, 3
       WRITE_CONTROL_DCACHE t6
       fence rw, rw

       li t0, 1
       WRITE_CONTROL_ADDR_STORE t0
       WRITE_CONTROL_ADDR_LOAD t0
       li t6, 0
       WRITE_CONTROL_DCACHE t6
       li t6, 0
       WRITE_CONTROL_ADDR_STORE t6
       WRITE_CONTROL_ADDR_LOAD t6
       fence rw, rw
    */
    li a0, 0x165
    jal ra, vm_stage_emit_asm
    CLEAR_ALL_CRYPTO_CSR
    fence rw, rw

    mv a0, tp
    mv a1, sp
    call vm_prepare_u1_initial_trapframe
    CLEAR_ALL_CRYPTO_CSR
    fence rw, rw
    j vm_resume_saved_u1_runtime
#endif

    .section .text.init
    .global _start_secondary
    .type _start_secondary, @function
_start_secondary:
6:
    wfi
    j 6b

vm_map_range:
    beq a2, a3, 2f
    li t2, RISCV_PGSIZE
1:
    srli t0, a1, RISCV_PGSHIFT
    andi t0, t0, 0x1ff
    slli t0, t0, 3
    srli t1, a2, RISCV_PGSHIFT
    slli t1, t1, PTE_PPN_SHIFT
    or t1, t1, a4
    add t0, a0, t0
    sd t1, 0(t0)
    add a1, a1, t2
    add a2, a2, t2
    bltu a2, a3, 1b
2:
    ret

    .global vm_probe_range
    .type vm_probe_range, @function
vm_probe_range:
    beq a2, a3, 2f
    li t2, RISCV_PGSIZE
1:
    la t0, vm_probe_active
    li t1, VM_PROBE_ACTIVE_FLAG
    sd t1, 0(t0)
    la t0, vm_probe_va
    sd a1, 0(t0)
    la t0, vm_probe_pa
    sd a2, 0(t0)
    la t0, vm_probe_flags
    sd a4, 0(t0)
    la t0, 3f
    la t1, vm_probe_resume_pc
    sd t0, 0(t1)
    la t0, probe_trap_entry
    csrw mtvec, t0

    li t0, MSTATUS_MPP
    csrc mstatus, t0
    li t0, MSTATUS_MPRV_MPP_U
    csrs mstatus, t0
    li t0, 1
    WRITE_CONTROL_ADDR_LOAD t0
    fence rw, rw
    lbu zero, 0(a1)
3:
    la t0, trap_entry
    csrw mtvec, t0
    li t0, 0
    CLEAR_ALL_CRYPTO_CSR
    li t0, MSTATUS_MPRV
    csrc mstatus, t0
    la t0, vm_probe_active
    sd zero, 0(t0)
    add a1, a1, t2
    add a2, a2, t2
    bltu a2, a3, 1b
2:
    la t0, vm_probe_force_direct_return
    ld t1, 0(t0)
    beqz t1, 4f
    sd zero, 0(t0)
    j vm_probe_range_last_return
4:
    ret

    .global vm_probe_range_store
    .type vm_probe_range_store, @function
vm_probe_range_store:
    beq a2, a3, 2f
    li t2, RISCV_PGSIZE
1:
    la t0, vm_probe_active
    li t1, VM_PROBE_ACTIVE_FLAG
    sd t1, 0(t0)
    la t0, vm_probe_va
    sd a1, 0(t0)
    la t0, vm_probe_pa
    sd a2, 0(t0)
    la t0, vm_probe_flags
    sd a4, 0(t0)
    la t0, 3f
    la t1, vm_probe_resume_pc
    sd t0, 0(t1)
    la t0, probe_trap_entry
    csrw mtvec, t0

    li t0, MSTATUS_MPP
    csrc mstatus, t0
    li t0, MSTATUS_MPRV_MPP_U
    csrs mstatus, t0
    li t0, 1
    WRITE_CONTROL_ADDR_STORE t0
    fence rw, rw
    /* Probe the store-address path without mutating memory: an SC without
       a matching LR must fail, but it still exercises store translation. */
    sc.d zero, zero, 0(a1)
3:
    la t0, trap_entry
    csrw mtvec, t0
    CLEAR_ALL_CRYPTO_CSR
    li t0, MSTATUS_MPRV
    csrc mstatus, t0
    la t0, vm_probe_active
    sd zero, 0(t0)
    add a1, a1, t2
    add a2, a2, t2
    bltu a2, a3, 1b
2:
    ret

    .align 2
    .global vm_u2_encrypt_runtime_image_asm
    .type vm_u2_encrypt_runtime_image_asm, @function
vm_u2_encrypt_runtime_image_asm:
    li t0, 0
    CLEAR_ALL_CRYPTO_CSR
    li t0, MSTATUS_MPRV
    csrc mstatus, t0

    csrw satp, a0
    sfence.vma x0, x0

    la t0, counter_store
    li t2, CACHE_CRYPTO_COUNTER_BASE_BIAS(VM_VIRT_BASE)
    sub t0, t0, t2
    csrw CACHE_CRYPTO_COUNTER_BASE_CSR, t0
    fence rw, rw

    li t5, -64
    and a1, a1, t5
    and a2, a2, t5
    beq a1, a2, 3f

    li t0, MSTATUS_MPP
    csrc mstatus, t0
    li t0, MSTATUS_MPRV_MPP_U
    csrs mstatus, t0

    li t0, 2
    WRITE_CONTROL_DCACHE t0
    fence rw, rw

    addi t4, a1, 64
1:
    ld a0,  0(a1)
    ld a3,  8(a1)
    ld a4, 16(a1)
    ld a5, 24(a1)
    ld a6, 32(a1)
    ld a7, 40(a1)
    ld t1, 48(a1)
    ld t3, 56(a1)
    sd a0,  0(a1)
    sd a3,  8(a1)
    sd a4, 16(a1)
    sd a5, 24(a1)
    sd a6, 32(a1)
    sd a7, 40(a1)
    sd t1, 48(a1)
    sd t3, 56(a1)
    addi a1, a1, 64
    bne a1, t4, 2f
    mv t3, a1
    mv t6, a2
    li t0, 0
    WRITE_CONTROL_DCACHE t0
    li t0, MSTATUS_MPRV
    csrc mstatus, t0
    fence rw, rw
    li t0, MSTATUS_MPRV_MPP_U
    csrs mstatus, t0
    li t0, 2
    WRITE_CONTROL_DCACHE t0
    fence rw, rw
    mv a1, t3
    mv a2, t6
    addi t4, t4, 64
2:
    bne a1, a2, 1b

3:
    fence rw, rw
    li t0, 0
    WRITE_CONTROL_DCACHE t0
    li t0, MSTATUS_MPRV
    csrc mstatus, t0
    fence rw, rw
    ret

    .align 2
    .extern tohost
    .extern fromhost
    .extern vm_stage_emit
    .global vm_stage_emit_asm
    .type vm_stage_emit_asm, @function
vm_stage_emit_asm:
    addi sp, sp, -16
    sd ra, 8(sp)
    call vm_stage_emit
    ld ra, 8(sp)
    addi sp, sp, 16
    ret

    .align 2
probe_trap_entry:
    csrrw sp, mscratch, sp
    addi sp, sp, -272

    /* Disable all custom crypto CSRs before the first trap-stack store. */
    CLEAR_ALL_CRYPTO_CSR

    SREG x5, 5*REGBYTES(sp)
    li x5, MSTATUS_MPRV
    csrc mstatus, x5

    SREG x1, 1*REGBYTES(sp)
    csrr t0, mscratch
    SREG t0, 2*REGBYTES(sp)
    SREG x3, 3*REGBYTES(sp)
    SREG x4, 4*REGBYTES(sp)
    SREG x6, 6*REGBYTES(sp)
    SREG x7, 7*REGBYTES(sp)
    SREG x8, 8*REGBYTES(sp)
    SREG x9, 9*REGBYTES(sp)
    SREG x10, 10*REGBYTES(sp)
    SREG x11, 11*REGBYTES(sp)
    SREG x12, 12*REGBYTES(sp)
    SREG x13, 13*REGBYTES(sp)
    SREG x14, 14*REGBYTES(sp)
    SREG x15, 15*REGBYTES(sp)
    SREG x16, 16*REGBYTES(sp)
    SREG x17, 17*REGBYTES(sp)
    SREG x18, 18*REGBYTES(sp)
    SREG x19, 19*REGBYTES(sp)
    SREG x20, 20*REGBYTES(sp)
    SREG x21, 21*REGBYTES(sp)
    SREG x22, 22*REGBYTES(sp)
    SREG x23, 23*REGBYTES(sp)
    SREG x24, 24*REGBYTES(sp)
    SREG x25, 25*REGBYTES(sp)
    SREG x26, 26*REGBYTES(sp)
    SREG x27, 27*REGBYTES(sp)
    SREG x28, 28*REGBYTES(sp)
    SREG x29, 29*REGBYTES(sp)
    SREG x30, 30*REGBYTES(sp)
    SREG x31, 31*REGBYTES(sp)

    li t0, DRAM_BASE
    bgeu gp, t0, 1f
    li t0, VM_VIRT_OFFSET
    sub gp, gp, t0
1:

    csrr a0, mepc
    csrr a1, mcause
    csrr a2, mtval
    mv a3, sp
    call handle_vm_probe_fault
    csrw mepc, a0

    LREG x1, 1*REGBYTES(sp)
    LREG x3, 3*REGBYTES(sp)
    LREG x4, 4*REGBYTES(sp)
    LREG x5, 5*REGBYTES(sp)
    LREG x6, 6*REGBYTES(sp)
    LREG x7, 7*REGBYTES(sp)
    LREG x8, 8*REGBYTES(sp)
    LREG x9, 9*REGBYTES(sp)
    LREG x10, 10*REGBYTES(sp)
    LREG x11, 11*REGBYTES(sp)
    LREG x12, 12*REGBYTES(sp)
    LREG x13, 13*REGBYTES(sp)
    LREG x14, 14*REGBYTES(sp)
    LREG x15, 15*REGBYTES(sp)
    LREG x16, 16*REGBYTES(sp)
    LREG x17, 17*REGBYTES(sp)
    LREG x18, 18*REGBYTES(sp)
    LREG x19, 19*REGBYTES(sp)
    LREG x20, 20*REGBYTES(sp)
    LREG x21, 21*REGBYTES(sp)
    LREG x22, 22*REGBYTES(sp)
    LREG x23, 23*REGBYTES(sp)
    LREG x24, 24*REGBYTES(sp)
    LREG x25, 25*REGBYTES(sp)
    LREG x26, 26*REGBYTES(sp)
    LREG x27, 27*REGBYTES(sp)
    LREG x28, 28*REGBYTES(sp)
    LREG x29, 29*REGBYTES(sp)
    LREG x30, 30*REGBYTES(sp)
    LREG x31, 31*REGBYTES(sp)

    addi sp, sp, 272
    csrrw sp, mscratch, sp
    mret

    .align 2
trap_entry:
    /* Global benchmark traps are handled only by M-mode.  The first operation
       must disable custom crypto CSRs, before touching any stack or saving any
       trapframe state. */
    CLEAR_ALL_CRYPTO_CSR

    /* Switch to the M-mode scratch stack and save the complete trapped GPR
       frame before calling any C code.  mscratch is restored to the M stack
       top immediately after x2 has been captured in the trapframe. */
    csrrw sp, mscratch, sp
    addi sp, sp, -VM_TRAPFRAME_BYTES

    SREG x1, 1*REGBYTES(sp)
    SREG x5, 5*REGBYTES(sp)
    csrr t0, mscratch
    SREG t0, 2*REGBYTES(sp)
    addi t0, sp, VM_TRAPFRAME_BYTES
    csrw mscratch, t0
    SREG x3, 3*REGBYTES(sp)
    SREG x4, 4*REGBYTES(sp)
    SREG x6, 6*REGBYTES(sp)
    SREG x7, 7*REGBYTES(sp)
    SREG x8, 8*REGBYTES(sp)
    SREG x9, 9*REGBYTES(sp)
    SREG x10, 10*REGBYTES(sp)
    SREG x11, 11*REGBYTES(sp)
    SREG x12, 12*REGBYTES(sp)
    SREG x13, 13*REGBYTES(sp)
    SREG x14, 14*REGBYTES(sp)
    SREG x15, 15*REGBYTES(sp)
    SREG x16, 16*REGBYTES(sp)
    SREG x17, 17*REGBYTES(sp)
    SREG x18, 18*REGBYTES(sp)
    SREG x19, 19*REGBYTES(sp)
    SREG x20, 20*REGBYTES(sp)
    SREG x21, 21*REGBYTES(sp)
    SREG x22, 22*REGBYTES(sp)
    SREG x23, 23*REGBYTES(sp)
    SREG x24, 24*REGBYTES(sp)
    SREG x25, 25*REGBYTES(sp)
    SREG x26, 26*REGBYTES(sp)
    SREG x27, 27*REGBYTES(sp)
    SREG x28, 28*REGBYTES(sp)
    SREG x29, 29*REGBYTES(sp)
    SREG x30, 30*REGBYTES(sp)
    SREG x31, 31*REGBYTES(sp)

    csrr t0, mepc
    SREG t0, 32*REGBYTES(sp)
    csrr t0, mcause
    SREG t0, 33*REGBYTES(sp)
    csrr t0, mtval
    SREG t0, 34*REGBYTES(sp)
    csrr t0, mstatus
    SREG t0, 35*REGBYTES(sp)
    SAVE_TRAPFRAME_FPRS sp

    li t0, MSTATUS_MPRV
    csrc mstatus, t0
    LA_ABS gp, __global_pointer$

    LREG a3, 10*REGBYTES(sp)
    LREG a4, 17*REGBYTES(sp)

    LREG t2, 33*REGBYTES(sp)
    li t1, CAUSE_USER_ECALL
    beq t2, t1, 2f
    li t1, CAUSE_SUPERVISOR_ECALL
    bne t2, t1, 3f
2:
    LREG t0, 17*REGBYTES(sp)
    li t1, VM_MODE_SWITCH_ECALL_ID
    beq t0, t1, 4f
    li t1, VM_CUSTOM_ECALL_ID
    bne t0, t1, 3f
    li t1, CAUSE_USER_ECALL
    beq t2, t1, trap_return_custom_user_ecall
    j trap_return_custom_plain_ecall
4:
    LA_ABS t0, vm_current_process
    ld t1, 0(t0)
    li t2, 1
    beq t1, t2, trap_switch_to_u2
    li t2, 2
    beq t1, t2, trap_switch_to_u1
    j trap_return_custom_user_ecall
3:

    csrr a0, mepc
    csrr a1, mcause
    csrr a2, mtval
    call handle_trap
1:
    j 1b

trap_switch_to_u2:
    LA_ABS t6, vm_u1_trapframe
    COPY_CURRENT_TRAPFRAME t6
    LREG t0, 32*REGBYTES(t6)
    addi t0, t0, 4
    SREG t0, 32*REGBYTES(t6)
    LA_ABS t0, vm_u1_trapframe_valid
    li t1, 1
    sd t1, 0(t0)
    LA_ABS t0, vm_u1_trapframe_live
    sd t1, 0(t0)

    LA_ABS t0, vm_u2_trapframe_valid
    ld t1, 0(t0)
    beqz t1, trap_return_custom_user_ecall
    j vm_resume_saved_u2_runtime

trap_switch_to_u1:
    LA_ABS t6, vm_u2_trapframe
    COPY_CURRENT_TRAPFRAME t6
    LREG t0, 32*REGBYTES(t6)
    addi t0, t0, 4
    SREG t0, 32*REGBYTES(t6)
    LA_ABS t0, vm_u2_trapframe_valid
    li t1, 1
    sd t1, 0(t0)
    LA_ABS t0, vm_u2_trapframe_live
    sd t1, 0(t0)

    LA_ABS t0, vm_u1_trapframe_valid
    ld t1, 0(t0)
    beqz t1, trap_return_custom_user_ecall
    j vm_resume_saved_u1_runtime

trap_return_custom_plain_ecall:
    la t1, trap_entry
    csrw mtvec, t1
    LREG t0, 32*REGBYTES(sp)
    addi t0, t0, 4
    csrw mepc, t0
    LREG t0, 35*REGBYTES(sp)
    li t1, MSTATUS_MPRV
    csrc mstatus, t1
    csrw mstatus, t0
    RESTORE_TRAPFRAME_FPRS sp
    RESTORE_GLOBAL_TRAPFRAME_GPRS_EXCEPT_T0_SP
    addi t0, sp, VM_TRAPFRAME_BYTES
    csrw mscratch, t0
    LREG x5, 5*REGBYTES(sp)
    LREG x2, 2*REGBYTES(sp)
    mret

trap_return_custom_user_ecall:
    la t1, trap_entry
    csrw mtvec, t1
    LREG t0, 32*REGBYTES(sp)
    addi t0, t0, 4
    csrw mepc, t0
    LREG t0, 35*REGBYTES(sp)
    li t1, MSTATUS_MPRV
    csrc mstatus, t1
    csrw mstatus, t0
    RESTORE_TRAPFRAME_FPRS sp
    RESTORE_GLOBAL_TRAPFRAME_GPRS_EXCEPT_T0_SP
    addi t0, sp, VM_TRAPFRAME_BYTES
    csrw mscratch, t0
    LREG x5, 5*REGBYTES(sp)
    LREG x2, 2*REGBYTES(sp)
    csrwi ADDR_CONTROL_CSR, (ADDR_CTRL_LOAD_ENABLE | ADDR_CTRL_STORE_ENABLE | ADDR_CTRL_FETCH_ENABLE)
    csrwi DATA_CONTROL_CSR, (DATA_CTRL_DCACHE_LOAD_ENABLE | DATA_CTRL_DCACHE_STORE_ENABLE | DATA_CTRL_ICACHE_ENABLE)
    mret

    .align 2
    .global vm_resume_saved_u2_runtime
    .type vm_resume_saved_u2_runtime, @function
vm_resume_saved_u2_runtime:
    la t1, trap_entry
    csrw mtvec, t1
    li t1, MSTATUS_MPRV
    csrc mstatus, t1
    call vm_load_u2_keys
    LA_ABS t0, vm_current_process
    li t1, 2
    sd t1, 0(t0)
    LA_ABS t0, vm_u2_satp
    ld t0, 0(t0)
    csrw satp, t0
    sfence.vma x0, x0
    LA_ABS sp, vm_u2_trapframe
    LREG t0, 32*REGBYTES(sp)
    csrw mepc, t0
    LREG t0, 35*REGBYTES(sp)
    li t1, MSTATUS_MPRV
    csrc mstatus, t1
    csrw mstatus, t0
    RESTORE_TRAPFRAME_FPRS sp
    RESTORE_GLOBAL_TRAPFRAME_GPRS_EXCEPT_T0_SP
    LREG x5, 5*REGBYTES(sp)
    LREG x2, 2*REGBYTES(sp)
    csrwi ADDR_CONTROL_CSR, (ADDR_CTRL_LOAD_ENABLE | ADDR_CTRL_STORE_ENABLE | ADDR_CTRL_FETCH_ENABLE)
    csrwi DATA_CONTROL_CSR, (DATA_CTRL_DCACHE_LOAD_ENABLE | DATA_CTRL_DCACHE_STORE_ENABLE | DATA_CTRL_ICACHE_ENABLE)
    mret

    .align 2
    .global vm_resume_saved_u1_runtime
    .type vm_resume_saved_u1_runtime, @function
vm_resume_saved_u1_runtime:
    la t1, trap_entry
    csrw mtvec, t1
    li t1, MSTATUS_MPRV
    csrc mstatus, t1
    call vm_load_u1_keys
    LA_ABS t0, vm_current_process
    li t1, 1
    sd t1, 0(t0)
    LA_ABS t0, vm_u1_satp
    ld t0, 0(t0)
    csrw satp, t0
    sfence.vma x0, x0
    LA_ABS sp, vm_u1_trapframe
    LREG t0, 32*REGBYTES(sp)
    csrw mepc, t0
    LREG t0, 35*REGBYTES(sp)
    li t1, MSTATUS_MPRV
    csrc mstatus, t1
    csrw mstatus, t0
    RESTORE_TRAPFRAME_FPRS sp
    RESTORE_GLOBAL_TRAPFRAME_GPRS_EXCEPT_T0_SP
    LREG x5, 5*REGBYTES(sp)
    LREG x2, 2*REGBYTES(sp)
    csrwi ADDR_CONTROL_CSR, (ADDR_CTRL_LOAD_ENABLE | ADDR_CTRL_STORE_ENABLE | ADDR_CTRL_FETCH_ENABLE)
    csrwi DATA_CONTROL_CSR, (DATA_CTRL_DCACHE_LOAD_ENABLE | DATA_CTRL_DCACHE_STORE_ENABLE | DATA_CTRL_ICACHE_ENABLE)
    mret

    .cfi_endproc

    .section .text.mmode
    .align 2
    .section ".vm_page_table","aw",@nobits
    .align 12
    .globl vm_root_page_table
vm_root_page_table:
    .zero 4096
    .globl vm_l1_page_table
vm_l1_page_table:
    .zero 4096
    .globl vm_l0_runtime_page_table
vm_l0_runtime_page_table:
    .zero 4096
    .globl vm_l0_io_page_table
vm_l0_io_page_table:
    .zero 4096
    .globl vm_phys_l1_page_table
vm_phys_l1_page_table:
    .zero 4096
    .globl vm_phys_l0_runtime_page_table
vm_phys_l0_runtime_page_table:
    .zero 4096
    .globl vm_phys_l0_io_page_table
vm_phys_l0_io_page_table:
    .zero 4096
    .globl vm_enc_root_page_table
vm_enc_root_page_table:
    .zero 4096
    .globl vm_enc_page_table_pool
vm_enc_page_table_pool:
    .zero (VM_ENC_PAGE_TABLE_POOL_PAGES * 4096)
    .globl vm_enc_page_table_pool_end
vm_enc_page_table_pool_end:
    .globl vm_u2_root_page_table
vm_u2_root_page_table:
    .zero 4096
    .globl vm_u2_page_table_pool
vm_u2_page_table_pool:
    .zero (S_PLAIN_PAGE_TABLE_POOL_PAGES * 4096)
    .globl vm_u2_page_table_pool_end
vm_u2_page_table_pool_end:
    .globl vm_u2_enc_root_page_table
vm_u2_enc_root_page_table:
    .zero 4096
    .globl vm_u2_enc_page_table_pool
vm_u2_enc_page_table_pool:
    .zero (VM_ENC_PAGE_TABLE_POOL_PAGES * 4096)
    .globl vm_u2_enc_page_table_pool_end
vm_u2_enc_page_table_pool_end:
    .globl vm_page_table_end
vm_page_table_end:

    .section ".htif","aw",@progbits
    .align 6
vm_stage_magic:
    .zero 128
