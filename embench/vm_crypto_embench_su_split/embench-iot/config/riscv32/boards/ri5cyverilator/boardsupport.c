/* Copyright (C) 2017 Embecosm Limited and University of Bristol

   Contributor Graham Markall <graham.markall@embecosm.com>

   This file is part of Embench and was formerly part of the Bristol/Embecosm
   Embedded Benchmark Suite.

   SPDX-License-Identifier: GPL-3.0-or-later */

#include <stdint.h>
#include <support.h>

#define ADDR_CONTROL_CSR              0x3f0
#define DATA_CONTROL_CSR              0x3f1
#define CACHE_CRYPTO_COUNTER_BASE_CSR 0x3f3

#define ADDR_CTRL_LOAD_ENABLE         (1u << 0)
#define ADDR_CTRL_STORE_ENABLE        (1u << 1)
#define ADDR_CTRL_FETCH_ENABLE        (1u << 2)
#define DATA_CTRL_DCACHE_LOAD_ENABLE  (1u << 0)
#define DATA_CTRL_DCACHE_STORE_ENABLE (1u << 1)
#define DATA_CTRL_ICACHE_ENABLE       (1u << 2)

#define MSTATUS_MPRV 0x00020000ULL
#define MSTATUS_MPP 0x00001800ULL
#define MSTATUS_MPP_U 0x00000000ULL
#define MSTATUS_MPP_S 0x00000800ULL

#define DRAM_BASE 0x80000000ULL
#define CACHE_CRYPTO_LINK_BASE 0x80000000ULL
#define VM_VIRT_BASE 0x40000000ULL
#define VM_VIRT_OFFSET (VM_VIRT_BASE - DRAM_BASE)
#define COUNTER_STORE_LINK_BASE 0x80200000ULL
#define TOHOST_LINK_BASE 0x80300000ULL
#define VM_PHYS_DELTA (CACHE_CRYPTO_LINK_BASE - VM_VIRT_BASE)
#define U2_IMAGE_PA_BASE 0x80400000ULL
#define U2_IMAGE_LIMIT_BYTES (4 * 1024 * 1024ULL)
#define U2_STACK_PA_BASE 0x80800000ULL
#define U2_STACK_LIMIT_BYTES (128 * 1024ULL)
#define U2_RUNTIME_BSS_PA_BASE 0x80820000ULL
#define U2_RUNTIME_BSS_LIMIT_BYTES (128 * 1024ULL)
#define CACHE_CRYPTO_COUNTER_BASE_BIAS(addr) ((addr) >> 3)
#define CACHE_CRYPTO_COUNTER_STORE_BYTES (64 * 1024)
#define VM_PROBE_ACTIVE_FLAG 1ULL
#define HTIF_DEV_SHIFT 56
#define HTIF_DEV_MASK 0xff
#define HTIF_CMD_SHIFT 48
#define HTIF_CMD_MASK 0xff
#define HTIF_PAYLOAD_MASK ((1ULL << HTIF_CMD_SHIFT) - 1)
#define HTIF_TOHOST(dev, cmd, payload) ( \
  (((uint64_t) (dev) & HTIF_DEV_MASK) << HTIF_DEV_SHIFT) | \
  (((uint64_t) (cmd) & HTIF_CMD_MASK) << HTIF_CMD_SHIFT) | \
  ((uint64_t) (payload) & HTIF_PAYLOAD_MASK))
#define VM_PROBE_MAP_LOG_CAP 256

#define RISCV_PGSHIFT 12
#define RISCV_PGSIZE (1UL << RISCV_PGSHIFT)
#define PTE_V 0x001
#define PTE_R 0x002
#define PTE_W 0x004
#define PTE_X 0x008
#define PTE_U 0x010
#define PTE_A 0x040
#define PTE_D 0x080
#define PTE_PPN_SHIFT 10

#define CAUSE_FETCH_PAGE_FAULT 0xc
#define CAUSE_LOAD_PAGE_FAULT 0xd
#define CAUSE_STORE_PAGE_FAULT 0xf
#define CAUSE_USER_ECALL 0x8
#define CAUSE_SUPERVISOR_ECALL 0x9

#define VM_EXIT_ECALL_ID 0x564d45584954ULL
#define VM_DEBUG_DIAG_MISMATCH 0x1
#define VM_MMODE_TEXT __attribute__ ((section (".text.mmode")))
#define VM_TRAP_TEXT __attribute__ ((section (".text.mmode"), noinline))
#define VM_MMODE_DATA __attribute__ ((section (".mmode_data")))
#define VM_MMODE_BSS __attribute__ ((section (".mmode_bss")))

#define VM_SU_STAGE_WAIT_U1 0ULL
#define VM_SU_STAGE_WAIT_U2 1ULL

static void __attribute__ ((noreturn)) vmembench_tohost_exit (uintptr_t code);
#define VM_PROCESS_1_ID 1ULL
#define VM_PROCESS_2_ID 2ULL
#define VM_TRAPFRAME_WORDS 69
#define VM_TRAPFRAME_SP_SLOT 2
#define VM_TRAPFRAME_GP_SLOT 3
#define VM_TRAPFRAME_TP_SLOT 4
#define VM_TRAPFRAME_MEPC_SLOT 32
#define VM_TRAPFRAME_MSTATUS_SLOT 35
#define VM_PROCESS_KEY_WORDS 4

extern volatile uint64_t tohost;
extern volatile uint64_t fromhost;
extern char __vm_runtime_phys_end[];
extern char __crypto_writable_pages_start[];
extern char __crypto_writable_pages_end[];
extern char __crypto_runtime_bss_start[];
extern char __crypto_runtime_bss_end[];
extern void embench_cryptoexec_main (void) __attribute__ ((noreturn));
extern uint64_t vm_root_page_table[512];
extern uint64_t vm_enc_root_page_table[512];
extern uint64_t vm_enc_page_table_pool[];
extern uint64_t vm_enc_page_table_pool_end[];
extern uint64_t vm_u2_root_page_table[512];
extern uint64_t vm_u2_page_table_pool[];
extern uint64_t vm_u2_page_table_pool_end[];
extern uint64_t vm_u2_enc_root_page_table[512];
extern uint64_t vm_u2_enc_page_table_pool[];
extern uint64_t vm_u2_enc_page_table_pool_end[];
extern void vm_resume_saved_u1_runtime (void)
  __attribute__ ((noreturn));
extern void vm_resume_saved_u2_runtime (void)
  __attribute__ ((noreturn));
extern void vm_u2_encrypt_runtime_image_asm (uintptr_t satp, uintptr_t runtime_start_va,
                                             uintptr_t runtime_end_va);
uintptr_t vm_probe_active VM_MMODE_BSS;
uintptr_t vm_probe_va VM_MMODE_BSS;
uintptr_t vm_probe_pa VM_MMODE_BSS;
uintptr_t vm_probe_flags VM_MMODE_BSS;
uintptr_t vm_probe_resume_pc VM_MMODE_BSS;
uintptr_t vm_probe_force_direct_return VM_MMODE_BSS;
uintptr_t vm_probe_watch_va VM_MMODE_BSS;
uintptr_t vm_probe_watch_tval VM_MMODE_BSS;
uintptr_t vm_probe_watch_enc_page VM_MMODE_BSS;
uintptr_t vm_probe_watch_hits VM_MMODE_BSS;

typedef struct
{
  uintptr_t cause;
  uintptr_t probe_va;
  uintptr_t tval;
  uintptr_t enc_va_page;
  uintptr_t pa_page;
  uintptr_t flags;
  uintptr_t pte;
} vm_probe_map_log_t;

static vm_probe_map_log_t vm_probe_map_log[VM_PROBE_MAP_LOG_CAP] VM_MMODE_BSS;
static uintptr_t vm_probe_map_log_count VM_MMODE_BSS;
static uintptr_t vm_probe_map_log_dropped VM_MMODE_BSS;
static char vmembench_debug_buf[96] VM_MMODE_BSS;
static const char vmembench_hex_digits[] VM_MMODE_DATA = "0123456789abcdef";
static const char vmembench_dbg_mismatch_prefix[] VM_MMODE_DATA = "DBG mismatch ch=";
static const char vmembench_idx_prefix[] VM_MMODE_DATA = " idx=0x";
static const char vmembench_act_prefix[] VM_MMODE_DATA = " act=0x";
static const char vmembench_exp_prefix[] VM_MMODE_DATA = " exp=0x";
static const char vmembench_trap_prefix[] VM_MMODE_DATA = "TRAP1337 cause=0x";
static const char vmembench_mepc_prefix[] VM_MMODE_DATA = " mepc=0x";
static const char vmembench_mtval_prefix[] VM_MMODE_DATA = " mtval=0x";
static const char vmembench_a7_prefix[] VM_MMODE_DATA = " a7=0x";
static const char vmembench_proc_prefix[] VM_MMODE_DATA = " proc=0x";
static const char vmembench_stage_prefix[] VM_MMODE_DATA = " stage=0x";
static const char vmembench_probe_prefix[] VM_MMODE_DATA = "PROBE1337 cause=0x";
static const char vmembench_active_prefix[] VM_MMODE_DATA = " active=0x";
static const char vmembench_va_prefix[] VM_MMODE_DATA = " va=0x";
static const char vmembench_pa_prefix[] VM_MMODE_DATA = " pa=0x";
static const char vmembench_flags_prefix[] VM_MMODE_DATA = " flags=0x";
static const char vmembench_root_prefix[] VM_MMODE_DATA = " root=0x";
static const char vmembench_stage_emit_prefix[] VM_MMODE_DATA = "STAGE 0x";

uint8_t counter_store[CACHE_CRYPTO_COUNTER_STORE_BYTES]
  __attribute__ ((aligned (4096), used, section(".crypto_counter_store")));

static uint64_t *vm_enc_next_free_page_table;
static uint64_t *vm_u2_next_free_page_table VM_MMODE_BSS;
static uint64_t *vm_u2_enc_next_free_page_table VM_MMODE_BSS;
static uintptr_t vm_su_stage VM_MMODE_BSS;
static uintptr_t vm_su_u_status VM_MMODE_BSS;
static uintptr_t vm_su_u2_status VM_MMODE_BSS;
static uintptr_t vm_u2_tp_va VM_MMODE_BSS;
static uintptr_t vm_u2_sp_va VM_MMODE_BSS;
uintptr_t vm_u2_satp VM_MMODE_BSS;
static uintptr_t vm_u2_plain_satp VM_MMODE_BSS;
uintptr_t vm_u1_satp VM_MMODE_BSS;
uintptr_t vm_u1_trapframe[VM_TRAPFRAME_WORDS] VM_MMODE_BSS;
uintptr_t vm_u2_trapframe[VM_TRAPFRAME_WORDS] VM_MMODE_BSS;
uintptr_t vm_u1_trapframe_valid VM_MMODE_BSS;
uintptr_t vm_u2_trapframe_valid VM_MMODE_BSS;
uintptr_t vm_u1_trapframe_live VM_MMODE_BSS;
uintptr_t vm_u2_trapframe_live VM_MMODE_BSS;
uintptr_t vm_current_process VM_MMODE_BSS;
uintptr_t *vm_probe_target_root_page_table VM_MMODE_BSS;

typedef struct
{
  uintptr_t words[VM_PROCESS_KEY_WORDS];
} vm_process_keys_t;

vm_process_keys_t vm_u1_process_keys VM_MMODE_BSS;
vm_process_keys_t vm_u2_process_keys VM_MMODE_BSS;

void vm_load_u1_keys (void);
void vm_load_u2_keys (void);
void vm_stage_emit (uintptr_t stage);

static void VM_MMODE_TEXT
vm_clear_trapframe (uintptr_t *trapframe)
{
  volatile uintptr_t *cursor = (volatile uintptr_t *) trapframe;

  for (uintptr_t index = 0; index < VM_TRAPFRAME_WORDS; ++index)
    cursor[index] = 0;
}

static inline void
cache_crypto_fence (void)
{
  __asm__ volatile ("fence rw, rw" : : : "memory");
}

static inline void
cache_crypto_write_counter_base (uintptr_t value)
{
  __asm__ volatile ("csrw 0x3f3, %0" : : "r" (value) : "memory");
}

static inline uintptr_t
addr_control_csr_read (void)
{
  uintptr_t value;
  __asm__ volatile ("csrr %0, 0x3f0" : "=r" (value));
  return value;
}

static inline void
addr_control_csr_write (uintptr_t value)
{
  __asm__ volatile ("csrw 0x3f0, %0" : : "r" (value) : "memory");
}

static inline void
addr_control_csr_update (uintptr_t mask, uintptr_t value)
{
  uintptr_t current = addr_control_csr_read ();
  uintptr_t next = (current & ~mask) | (value & mask);
  addr_control_csr_write (next);
}

static inline uintptr_t
data_control_csr_read (void)
{
  uintptr_t value;
  __asm__ volatile ("csrr %0, 0x3f1" : "=r" (value));
  return value;
}

static inline void
data_control_csr_write (uintptr_t value)
{
  __asm__ volatile ("csrw 0x3f1, %0" : : "r" (value) : "memory");
}

static inline void
data_control_csr_update (uintptr_t mask, uintptr_t value)
{
  uintptr_t current = data_control_csr_read ();
  uintptr_t next = (current & ~mask) | (value & mask);
  data_control_csr_write (next);
}

static inline void
cache_crypto_write_enable (uintptr_t value)
{
  data_control_csr_update (DATA_CTRL_DCACHE_LOAD_ENABLE | DATA_CTRL_DCACHE_STORE_ENABLE,
                           value & 0x3u);
}

static inline void
clear_all_crypto_csr (void)
{
  __asm__ volatile ("csrwi 0x3f1, 0\n\t"
                    "csrwi 0x3f0, 0"
                    :
                    :
                    : "memory");
}

static inline void
icache_crypto_write_enable (uintptr_t value)
{
  data_control_csr_update (DATA_CTRL_ICACHE_ENABLE,
                            value ? DATA_CTRL_ICACHE_ENABLE : 0);
}

static inline void
addr_crypto_write_enable (uintptr_t value)
{
  addr_control_csr_update (ADDR_CTRL_STORE_ENABLE,
                           value ? ADDR_CTRL_STORE_ENABLE : 0);
}

static inline void
addr_crypto_read_enable (uintptr_t value)
{
  addr_control_csr_update (ADDR_CTRL_LOAD_ENABLE,
                           value ? ADDR_CTRL_LOAD_ENABLE : 0);
}

static inline void
addr_crypto_fetch_enable (uintptr_t value)
{
  addr_control_csr_update (ADDR_CTRL_FETCH_ENABLE,
                           value ? ADDR_CTRL_FETCH_ENABLE : 0);
}

static inline uintptr_t
read_mstatus (void)
{
  uintptr_t value;
  __asm__ volatile ("csrr %0, mstatus" : "=r" (value));
  return value;
}

static inline uintptr_t
read_gp (void)
{
  uintptr_t value;
  __asm__ volatile ("mv %0, gp" : "=r" (value));
  return value;
}

static inline void
write_satp (uintptr_t value)
{
  __asm__ volatile ("csrw satp, %0" : : "r" (value) : "memory");
}

static inline void
sfence_vma_all (void)
{
  __asm__ volatile ("sfence.vma x0, x0" : : : "memory");
}

static inline void
clear_mstatus_mprv (void)
{
  __asm__ volatile ("csrc mstatus, %0" : : "r" ((uintptr_t) MSTATUS_MPRV) : "memory");
}

static inline void
set_mstatus_mprv_mpp_u (void)
{
  __asm__ volatile ("csrc mstatus, %0\n\t"
                    "csrs mstatus, %1"
                    :
                    : "r" ((uintptr_t) MSTATUS_MPP),
                      "r" ((uintptr_t) MSTATUS_MPRV)
                    : "memory");
}

static inline void
vm_gen_key_instruction (void)
{
  __asm__ volatile (".4byte 0x80000033" : : : "memory");
}

static inline void
vm_store_key_block (uintptr_t base)
{
  register uintptr_t a0_reg asm ("a0") = base;
  register uintptr_t a1_reg asm ("a1") = 0;
  (void) a0_reg;
  (void) a1_reg;
  __asm__ volatile (".4byte 0x00b57023"
                    :
                    : "r" (a0_reg), "r" (a1_reg)
                    : "memory");
}

static inline void
vm_load_key_block (uintptr_t base)
{
  register uintptr_t a0_reg asm ("a0") = base;
  register uintptr_t a1_reg asm ("a1");
  (void) a0_reg;
  (void) a1_reg;
  __asm__ volatile (".4byte 0x00057583" : "=r" (a1_reg) : "r" (a0_reg) : "memory");
}

static void VM_MMODE_TEXT
vm_delay_for_keygen (void)
{
  for (uintptr_t index = 0; index < 16; ++index)
    __asm__ volatile ("addi x0, x0, 0" : : : "memory");
}

static void VM_MMODE_TEXT
vm_generate_and_store_process_keys (vm_process_keys_t *keys)
{
  vm_gen_key_instruction ();
  vm_delay_for_keygen ();
  vm_store_key_block ((uintptr_t) keys);
  cache_crypto_fence ();
}

void VM_MMODE_TEXT
vm_generate_and_store_u1_keys (void)
{
  vm_generate_and_store_process_keys (&vm_u1_process_keys);
}

void VM_MMODE_TEXT
vm_generate_and_store_u2_keys (void)
{
  vm_generate_and_store_process_keys (&vm_u2_process_keys);
}

void VM_MMODE_TEXT
vm_load_u1_keys (void)
{
  vm_load_key_block ((uintptr_t) &vm_u1_process_keys);
  cache_crypto_fence ();
}

void VM_MMODE_TEXT
vm_load_u2_keys (void)
{
  vm_load_key_block ((uintptr_t) &vm_u2_process_keys);
  cache_crypto_fence ();
}

static void VM_MMODE_TEXT
vm_enter_u2_probe_context (uintptr_t satp_value)
{
  clear_all_crypto_csr ();
  clear_mstatus_mprv ();
  write_satp (satp_value);
  sfence_vma_all ();
  vm_load_u2_keys ();
}

void VM_MMODE_TEXT
vm_prepare_u1_initial_trapframe (uintptr_t stack_start, uintptr_t stack_end)
{
  (void) stack_start;

  vm_clear_trapframe (vm_u1_trapframe);

  vm_u1_satp = (8ULL << 60)
               | ((uintptr_t) vm_enc_root_page_table >> RISCV_PGSHIFT);
  vm_u1_trapframe[VM_TRAPFRAME_SP_SLOT] = stack_end + VM_VIRT_OFFSET;
  vm_u1_trapframe[VM_TRAPFRAME_GP_SLOT] = read_gp () + VM_VIRT_OFFSET;
  vm_u1_trapframe[VM_TRAPFRAME_TP_SLOT] = stack_start + VM_VIRT_OFFSET;
  vm_u1_trapframe[VM_TRAPFRAME_MEPC_SLOT] =
    ((uintptr_t) embench_cryptoexec_main - CACHE_CRYPTO_LINK_BASE)
    + VM_VIRT_BASE;
  vm_u1_trapframe[VM_TRAPFRAME_MSTATUS_SLOT] =
    (read_mstatus () & ~(MSTATUS_MPRV | MSTATUS_MPP)) | MSTATUS_MPP_U;
  vm_u1_trapframe_valid = 1;
  vm_u1_trapframe_live = 0;
}

static void VM_MMODE_TEXT
vm_probe_range_call (uintptr_t va, uintptr_t pa, uintptr_t end_pa,
                     uintptr_t flags)
{
  register uintptr_t a1_reg asm ("a1") = va;
  register uintptr_t a2_reg asm ("a2") = pa;
  register uintptr_t a3_reg asm ("a3") = end_pa;
  register uintptr_t a4_reg asm ("a4") = flags;

  __asm__ volatile ("call vm_probe_range"
                    : "+r" (a1_reg), "+r" (a2_reg),
                      "+r" (a3_reg), "+r" (a4_reg)
                    :
                    : "a0", "a5", "a6", "a7",
                      "t0", "t1", "t2", "t3", "t4", "t5", "t6",
                      "ra", "memory");
}

static void VM_MMODE_TEXT
vm_probe_range_store_call (uintptr_t va, uintptr_t pa, uintptr_t end_pa,
                           uintptr_t flags)
{
  register uintptr_t a1_reg asm ("a1") = va;
  register uintptr_t a2_reg asm ("a2") = pa;
  register uintptr_t a3_reg asm ("a3") = end_pa;
  register uintptr_t a4_reg asm ("a4") = flags;

  __asm__ volatile ("call vm_probe_range_store"
                    : "+r" (a1_reg), "+r" (a2_reg),
                      "+r" (a3_reg), "+r" (a4_reg)
                    :
                    : "a0", "a5", "a6", "a7",
                      "t0", "t1", "t2", "t3", "t4", "t5", "t6",
                      "ra", "memory");
}

static void VM_TRAP_TEXT
vmembench_do_tohost (uint64_t tohost_value)
{
  while (tohost)
    fromhost = 0;
  tohost = tohost_value;
}

static void VM_TRAP_TEXT
vmembench_prepare_tohost_store (void)
{
  clear_all_crypto_csr ();
  write_satp (0);
  sfence_vma_all ();
  clear_mstatus_mprv ();
  cache_crypto_write_enable (0);
  icache_crypto_write_enable (0);
  addr_crypto_fetch_enable (0);
  addr_crypto_write_enable (0);
  addr_crypto_read_enable (0);
  cache_crypto_fence ();
}

static uintptr_t VM_TRAP_TEXT
vmembench_append_str (char *buf, uintptr_t pos, const char *str)
{
  while (*str)
    buf[pos++] = *str++;
  return pos;
}

static void VM_TRAP_TEXT
vmembench_tohost_write_chars (const char *buf, uintptr_t len)
{
  for (uintptr_t i = 0; i < len; ++i)
    vmembench_do_tohost (HTIF_TOHOST (1, 1, (unsigned char) buf[i]));
}

static uintptr_t VM_TRAP_TEXT
vmembench_append_hex (char *buf, uintptr_t pos, uintptr_t value, uintptr_t digits)
{
  for (uintptr_t i = 0; i < digits; ++i)
    {
      uintptr_t shift = (digits - 1 - i) * 4;
      buf[pos++] = vmembench_hex_digits[(value >> shift) & 0xf];
    }
  return pos;
}

static uintptr_t VM_TRAP_TEXT
vmembench_append_u64_dec (char *buf, uintptr_t pos, uint64_t value)
{
  char digits[20];
  uintptr_t count = 0;

  if (value == 0)
    {
      buf[pos++] = '0';
      return pos;
    }

  while (value != 0)
    {
      digits[count++] = (char) ('0' + (value % 10));
      value /= 10;
    }

  while (count != 0)
    buf[pos++] = digits[--count];

  return pos;
}

static void VM_TRAP_TEXT
vmembench_debug_emit (uintptr_t packed)
{
  uintptr_t opcode = (packed >> 56) & 0xff;
  uintptr_t channel = (packed >> 48) & 0xff;
  uintptr_t index = (packed >> 32) & 0xffff;
  uintptr_t actual = (packed >> 16) & 0xffff;
  uintptr_t expected = packed & 0xffff;
  uintptr_t pos = 0;

  if (opcode != VM_DEBUG_DIAG_MISMATCH)
    return;

  pos = vmembench_append_str (vmembench_debug_buf, pos,
                              vmembench_dbg_mismatch_prefix);
  vmembench_debug_buf[pos++] = (char) channel;
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_idx_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, index, 4);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_act_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, actual, 2);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_exp_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, expected, 2);
  vmembench_debug_buf[pos++] = '\n';

  vmembench_prepare_tohost_store ();
  vmembench_tohost_write_chars (vmembench_debug_buf, pos);
}

static void VM_TRAP_TEXT
vmembench_trap_diag_emit (uintptr_t cause, uintptr_t mepc,
                          uintptr_t tval, uintptr_t a7)
{
  uintptr_t pos = 0;

  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_trap_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, cause, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_mepc_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, mepc, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_mtval_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, tval, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_a7_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, a7, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_proc_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, vm_current_process, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_stage_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, vm_su_stage, 16);
  vmembench_debug_buf[pos++] = '\n';

  vmembench_prepare_tohost_store ();
  vmembench_tohost_write_chars (vmembench_debug_buf, pos);
}

static void VM_TRAP_TEXT
vmembench_probe_fault_diag_emit (uintptr_t cause, uintptr_t epc,
                                 uintptr_t tval)
{
  uintptr_t pos = 0;

  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_probe_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, cause, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_mepc_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, epc, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_mtval_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, tval, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_active_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, vm_probe_active, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_va_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, vm_probe_va, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_pa_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, vm_probe_pa, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_flags_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, vm_probe_flags, 16);
  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_root_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos,
                              (uintptr_t) vm_probe_target_root_page_table, 16);
  vmembench_debug_buf[pos++] = '\n';

  vmembench_prepare_tohost_store ();
  vmembench_tohost_write_chars (vmembench_debug_buf, pos);
}

void VM_MMODE_TEXT
vm_stage_emit (uintptr_t stage)
{
  uintptr_t pos = 0;

  pos = vmembench_append_str (vmembench_debug_buf, pos, vmembench_stage_emit_prefix);
  pos = vmembench_append_hex (vmembench_debug_buf, pos, stage, 16);
  vmembench_debug_buf[pos++] = '\n';

  vmembench_prepare_tohost_store ();
  vmembench_tohost_write_chars (vmembench_debug_buf, pos);
}

static void __attribute__ ((noreturn)) VM_MMODE_TEXT
vmembench_tohost_exit (uintptr_t code)
{
  uint64_t tohost_value = ((uint64_t) code << 1) | 1;
  vmembench_prepare_tohost_store ();
  vmembench_do_tohost (tohost_value);
  cache_crypto_fence ();
  while (1)
    ;
}

static void VM_TRAP_TEXT
vmembench_leave_vm_mode (void)
{
  cache_crypto_write_enable (0);
  addr_crypto_fetch_enable (0);
  addr_crypto_write_enable (0);
  addr_crypto_read_enable (0);
  __asm__ volatile ("csrc mstatus, %0" : : "r" ((uintptr_t) MSTATUS_MPRV) : "memory");
  cache_crypto_fence ();
}

static uintptr_t VM_MMODE_TEXT
align_down (uintptr_t value, uintptr_t align)
{
  return value & ~(align - 1);
}

static uintptr_t VM_MMODE_TEXT
align_up (uintptr_t value, uintptr_t align)
{
  return (value + align - 1) & ~(align - 1);
}

static void VM_MMODE_TEXT
vmembench_copy_bytes (uintptr_t dst, uintptr_t src, uintptr_t len)
{
  volatile uint64_t *dst_words = (volatile uint64_t *) dst;
  volatile const uint64_t *src_words = (volatile const uint64_t *) src;

  for (uintptr_t i = 0; i < len / sizeof (uint64_t); ++i)
    dst_words[i] = src_words[i];
}

static void VM_MMODE_TEXT
vmembench_zero_bytes (uintptr_t dst, uintptr_t len)
{
  volatile uint64_t *dst_words = (volatile uint64_t *) dst;

  for (uintptr_t i = 0; i < len / sizeof (uint64_t); ++i)
    dst_words[i] = 0;
}

static uint64_t VM_MMODE_TEXT
vm_u2_make_table_pte (uint64_t *table)
{
  return ((((uintptr_t) table) >> RISCV_PGSHIFT) << PTE_PPN_SHIFT) | PTE_V;
}

static uint64_t VM_MMODE_TEXT
vm_u2_make_leaf_pte (uintptr_t pa, uintptr_t flags)
{
  return ((pa >> RISCV_PGSHIFT) << PTE_PPN_SHIFT) | flags;
}

static uint64_t * VM_MMODE_TEXT
vm_u2_alloc_page_table (void)
{
  uint64_t *table = vm_u2_next_free_page_table;

  if (table >= vm_u2_page_table_pool_end)
    return 0;

  vm_u2_next_free_page_table += RISCV_PGSIZE / sizeof (uint64_t);
  return table;
}

static uint64_t * VM_MMODE_TEXT
vm_u2_alloc_enc_page_table (void)
{
  uint64_t *table = vm_u2_enc_next_free_page_table;

  if (table >= vm_u2_enc_page_table_pool_end)
    return 0;

  vm_u2_enc_next_free_page_table += RISCV_PGSIZE / sizeof (uint64_t);
  return table;
}

static int VM_MMODE_TEXT
vm_u2_map_page_4k_with_alloc (uint64_t *root, uintptr_t va, uintptr_t pa,
                              uintptr_t flags,
                              uint64_t *(*alloc_page_table) (void))
{
  const uintptr_t vpn2 = (va >> 30) & 0x1ff;
  const uintptr_t vpn1 = (va >> 21) & 0x1ff;
  const uintptr_t vpn0 = (va >> 12) & 0x1ff;

  uint64_t pte2 = root[vpn2];
  uint64_t *l1;
  if (!(pte2 & PTE_V))
    {
      l1 = alloc_page_table ();
      if (!l1)
        return -1;
      root[vpn2] = vm_u2_make_table_pte (l1);
    }
  else
    {
      l1 = (uint64_t *) (((pte2 >> PTE_PPN_SHIFT) << RISCV_PGSHIFT));
    }

  uint64_t pte1 = l1[vpn1];
  uint64_t *l0;
  if (!(pte1 & PTE_V))
    {
      l0 = alloc_page_table ();
      if (!l0)
        return -1;
      l1[vpn1] = vm_u2_make_table_pte (l0);
    }
  else
    {
      l0 = (uint64_t *) (((pte1 >> PTE_PPN_SHIFT) << RISCV_PGSHIFT));
    }

  l0[vpn0] = vm_u2_make_leaf_pte (pa, flags);
  return 0;
}

static void VM_MMODE_TEXT
vm_u2_map_range_4k_with_alloc (uint64_t *root, uintptr_t va, uintptr_t pa,
                               uintptr_t end_pa, uintptr_t flags,
                               uint64_t *(*alloc_page_table) (void))
{
  for (uintptr_t page = align_down (pa, RISCV_PGSIZE); page < end_pa;
       page += RISCV_PGSIZE, va += RISCV_PGSIZE)
    {
      if (vm_u2_map_page_4k_with_alloc (root, va, page, flags,
                                        alloc_page_table) != 0)
        vmembench_tohost_exit (1341);
    }
}

static uintptr_t VM_MMODE_TEXT
vm_walk_leaf_pte (uint64_t *root, uintptr_t va)
{
  const uintptr_t vpn2 = (va >> 30) & 0x1ff;
  const uintptr_t vpn1 = (va >> 21) & 0x1ff;
  const uintptr_t vpn0 = (va >> 12) & 0x1ff;
  uint64_t pte2 = root[vpn2];

  if (!(pte2 & PTE_V))
    return 0;

  uint64_t *l1 = (uint64_t *) (((pte2 >> PTE_PPN_SHIFT) << RISCV_PGSHIFT));
  uint64_t pte1 = l1[vpn1];

  if (!(pte1 & PTE_V))
    return 0;

  uint64_t *l0 = (uint64_t *) (((pte1 >> PTE_PPN_SHIFT) << RISCV_PGSHIFT));
  return l0[vpn0];
}

static void VM_MMODE_TEXT
vm_validate_u2_plain_mapping (uintptr_t va, uintptr_t expected_pa,
                              uintptr_t required_flags, uintptr_t exit_code)
{
  uintptr_t pte = vm_walk_leaf_pte (vm_u2_root_page_table, va);
  uintptr_t actual_pa;

  if ((pte & PTE_V) == 0)
    vmembench_tohost_exit (exit_code);

  actual_pa = ((pte >> PTE_PPN_SHIFT) << RISCV_PGSHIFT);

  if (actual_pa != (expected_pa & ~(uintptr_t) (RISCV_PGSIZE - 1)))
    vmembench_tohost_exit (exit_code);

  if ((pte & required_flags) != required_flags)
    vmembench_tohost_exit (exit_code);
}

static void VM_MMODE_TEXT
vm_u2_encrypt_runtime_image (uintptr_t runtime_start_va, uintptr_t runtime_end_va)
{
  uintptr_t start_va = align_down (runtime_start_va, 64);
  uintptr_t end_va = align_down (runtime_end_va, 64);
  uintptr_t counter_base;

  if (start_va == end_va)
    return;

  counter_base =
    (uintptr_t) counter_store
    - (uintptr_t) CACHE_CRYPTO_COUNTER_BASE_BIAS (VM_VIRT_BASE);

  vm_load_u2_keys ();
  clear_all_crypto_csr ();
  clear_mstatus_mprv ();
  write_satp (vm_u2_plain_satp);
  sfence_vma_all ();
  cache_crypto_write_counter_base (counter_base);
  cache_crypto_fence ();

  for (uintptr_t cursor = start_va; cursor != end_va; cursor += 64)
    {
      volatile uint64_t *line = (volatile uint64_t *) cursor;
      uint64_t word0;
      uint64_t word1;
      uint64_t word2;
      uint64_t word3;
      uint64_t word4;
      uint64_t word5;
      uint64_t word6;
      uint64_t word7;

      set_mstatus_mprv_mpp_u ();
      cache_crypto_write_enable (2);
      cache_crypto_fence ();

      word0 = line[0];
      word1 = line[1];
      word2 = line[2];
      word3 = line[3];
      word4 = line[4];
      word5 = line[5];
      word6 = line[6];
      word7 = line[7];

      line[0] = word0;
      line[1] = word1;
      line[2] = word2;
      line[3] = word3;
      line[4] = word4;
      line[5] = word5;
      line[6] = word6;
      line[7] = word7;

      cache_crypto_write_enable (0);
      clear_mstatus_mprv ();
      cache_crypto_fence ();
    }
}

static void VM_MMODE_TEXT
vm_u2_finalize_cache_state (void)
{
  write_satp (vm_u2_satp);
  sfence_vma_all ();

  cache_crypto_write_enable (3);
  cache_crypto_fence ();
  cache_crypto_write_enable (0);
  cache_crypto_fence ();
  cache_crypto_write_enable (3);
  cache_crypto_fence ();

  icache_crypto_write_enable (1);
  cache_crypto_write_enable (0);
  cache_crypto_fence ();
  cache_crypto_write_enable (3);
  cache_crypto_fence ();

  cache_crypto_write_enable (0);
  cache_crypto_fence ();
  cache_crypto_write_enable (3);
  cache_crypto_fence ();

  addr_crypto_write_enable (1);
  addr_crypto_read_enable (1);
  cache_crypto_write_enable (0);
  addr_crypto_write_enable (0);
  addr_crypto_read_enable (0);
  cache_crypto_fence ();
  cache_crypto_write_enable (3);
  addr_crypto_write_enable (1);
  addr_crypto_read_enable (1);
  cache_crypto_fence ();

  clear_all_crypto_csr ();
  clear_mstatus_mprv ();
  write_satp (0);
  sfence_vma_all ();
  cache_crypto_fence ();
}

void VM_MMODE_TEXT
vm_prepare_u2_encrypted_runtime (uintptr_t stack_start, uintptr_t stack_end)
{
  const uintptr_t runtime_bytes =
    align_up ((uintptr_t) __vm_runtime_phys_end - CACHE_CRYPTO_LINK_BASE,
              RISCV_PGSIZE);
  const uintptr_t stack_base = align_down (stack_start, RISCV_PGSIZE);
  const uintptr_t stack_bytes = align_up (stack_end - stack_base, RISCV_PGSIZE);
  const uintptr_t runtime_bss_bytes =
    align_up ((uintptr_t) __crypto_runtime_bss_end
              - (uintptr_t) __crypto_runtime_bss_start,
              RISCV_PGSIZE);
  const uintptr_t writable_pages_va =
    ((uintptr_t) __crypto_writable_pages_start - CACHE_CRYPTO_LINK_BASE)
    + VM_VIRT_BASE;
  const uintptr_t writable_pages_pa =
    U2_IMAGE_PA_BASE + ((uintptr_t) __crypto_writable_pages_start
                        - CACHE_CRYPTO_LINK_BASE);
  const uintptr_t writable_pages_end_pa =
    U2_IMAGE_PA_BASE + ((uintptr_t) __crypto_writable_pages_end
                        - CACHE_CRYPTO_LINK_BASE);
  const uintptr_t runtime_bss_va =
    ((uintptr_t) __crypto_runtime_bss_start - CACHE_CRYPTO_LINK_BASE)
    + VM_VIRT_BASE;
  const uintptr_t runtime_va_end = VM_VIRT_BASE + runtime_bytes;

  if (runtime_bytes > U2_IMAGE_LIMIT_BYTES
      || stack_bytes > U2_STACK_LIMIT_BYTES
      || runtime_bss_bytes > U2_RUNTIME_BSS_LIMIT_BYTES)
    vmembench_tohost_exit (1340);

  vm_stage_emit (0x200);
  vmembench_copy_bytes (U2_IMAGE_PA_BASE, CACHE_CRYPTO_LINK_BASE, runtime_bytes);
  vmembench_copy_bytes (U2_STACK_PA_BASE, stack_base, stack_bytes);
  vmembench_copy_bytes (U2_RUNTIME_BSS_PA_BASE,
                        (uintptr_t) __crypto_runtime_bss_start,
                        runtime_bss_bytes);

  vm_u2_next_free_page_table = vm_u2_page_table_pool;
  vm_u2_enc_next_free_page_table = vm_u2_enc_page_table_pool;

  vmembench_zero_bytes ((uintptr_t) vm_u2_root_page_table,
                        (uintptr_t) vm_u2_page_table_pool_end
                        - (uintptr_t) vm_u2_root_page_table);
  vmembench_zero_bytes ((uintptr_t) vm_u2_enc_root_page_table,
                        (uintptr_t) vm_u2_enc_page_table_pool_end
                        - (uintptr_t) vm_u2_enc_root_page_table);
  vm_stage_emit (0x201);

  vm_u2_map_range_4k_with_alloc (vm_u2_root_page_table, VM_VIRT_BASE,
                                 U2_IMAGE_PA_BASE, U2_IMAGE_PA_BASE + runtime_bytes,
                                 PTE_V | PTE_R | PTE_W | PTE_X | PTE_U | PTE_A | PTE_D,
                                 vm_u2_alloc_page_table);

  vm_u2_tp_va = (stack_base - CACHE_CRYPTO_LINK_BASE) + VM_VIRT_BASE;
  vm_u2_sp_va = (stack_end - CACHE_CRYPTO_LINK_BASE) + VM_VIRT_BASE;
  vm_u2_map_range_4k_with_alloc (vm_u2_root_page_table, vm_u2_tp_va,
                                 U2_STACK_PA_BASE, U2_STACK_PA_BASE + stack_bytes,
                                 PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D,
                                 vm_u2_alloc_page_table);

  vm_u2_map_range_4k_with_alloc (vm_u2_root_page_table, runtime_bss_va,
                                 U2_RUNTIME_BSS_PA_BASE,
                                 U2_RUNTIME_BSS_PA_BASE + runtime_bss_bytes,
                                 PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D,
                                 vm_u2_alloc_page_table);

  vm_u2_map_range_4k_with_alloc (vm_u2_root_page_table, VM_VIRT_BASE
                                 + (COUNTER_STORE_LINK_BASE - CACHE_CRYPTO_LINK_BASE),
                                 COUNTER_STORE_LINK_BASE,
                                 COUNTER_STORE_LINK_BASE
                                 + CACHE_CRYPTO_COUNTER_STORE_BYTES,
                                 PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D,
                                 vm_u2_alloc_page_table);

  vm_u2_map_page_4k_with_alloc (vm_u2_root_page_table,
                                VM_VIRT_BASE + (TOHOST_LINK_BASE
                                                - CACHE_CRYPTO_LINK_BASE),
                                TOHOST_LINK_BASE,
                                PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D,
                                vm_u2_alloc_page_table);

  vm_u2_map_range_4k_with_alloc (vm_u2_root_page_table, U2_IMAGE_PA_BASE,
                                 U2_IMAGE_PA_BASE, U2_IMAGE_PA_BASE + runtime_bytes,
                                 PTE_V | PTE_R | PTE_W | PTE_X | PTE_U | PTE_A | PTE_D,
                                 vm_u2_alloc_page_table);
  vm_u2_map_range_4k_with_alloc (vm_u2_root_page_table, U2_STACK_PA_BASE,
                                 U2_STACK_PA_BASE, U2_STACK_PA_BASE + stack_bytes,
                                 PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D,
                                 vm_u2_alloc_page_table);
  vm_u2_map_page_4k_with_alloc (vm_u2_root_page_table, TOHOST_LINK_BASE,
                                TOHOST_LINK_BASE,
                                PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D,
                                vm_u2_alloc_page_table);

  vm_u2_plain_satp = (8ULL << 60)
                     | ((uintptr_t) vm_u2_root_page_table >> RISCV_PGSHIFT);
  vm_u2_satp = (8ULL << 60)
               | ((uintptr_t) vm_u2_enc_root_page_table >> RISCV_PGSHIFT);

  vm_stage_emit (0x202);
  vm_probe_target_root_page_table = vm_u2_enc_root_page_table;
  vm_stage_emit (0x210);
  vm_enter_u2_probe_context (vm_u2_satp);
  vm_stage_emit (0x2101);
  vm_enter_u2_probe_context (vm_u2_satp);

  vm_probe_range_call (VM_VIRT_BASE, U2_IMAGE_PA_BASE,
                       U2_IMAGE_PA_BASE + runtime_bytes,
                       PTE_V | PTE_R | PTE_W | PTE_X | PTE_U | PTE_A | PTE_D);
  vm_stage_emit (0x2102);
  vm_enter_u2_probe_context (vm_u2_satp);
  vm_probe_range_call (vm_u2_tp_va, U2_STACK_PA_BASE,
                       U2_STACK_PA_BASE + stack_bytes,
                       PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);
  vm_stage_emit (0x2103);
  vm_enter_u2_probe_context (vm_u2_satp);
  vm_probe_range_store_call (vm_u2_tp_va, U2_STACK_PA_BASE,
                             U2_STACK_PA_BASE + stack_bytes,
                             PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);
  vm_probe_range_call (VM_VIRT_BASE + (COUNTER_STORE_LINK_BASE - DRAM_BASE),
                       COUNTER_STORE_LINK_BASE,
                       COUNTER_STORE_LINK_BASE + CACHE_CRYPTO_COUNTER_STORE_BYTES,
                       PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);
  vm_probe_range_call (VM_VIRT_BASE + (TOHOST_LINK_BASE - DRAM_BASE),
                       TOHOST_LINK_BASE, TOHOST_LINK_BASE + RISCV_PGSIZE,
                       PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);
  vm_probe_range_store_call (writable_pages_va, writable_pages_pa,
                             writable_pages_end_pa,
                             PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);
  vm_probe_range_store_call (runtime_bss_va, U2_RUNTIME_BSS_PA_BASE,
                             U2_RUNTIME_BSS_PA_BASE + runtime_bss_bytes,
                             PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);
  vm_probe_range_call (U2_IMAGE_PA_BASE, U2_IMAGE_PA_BASE,
                       U2_IMAGE_PA_BASE + runtime_bytes,
                       PTE_V | PTE_R | PTE_W | PTE_X | PTE_U | PTE_A | PTE_D);
  vm_probe_range_call (U2_STACK_PA_BASE, U2_STACK_PA_BASE,
                       U2_STACK_PA_BASE + stack_bytes,
                       PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);
  vm_probe_range_call (TOHOST_LINK_BASE, TOHOST_LINK_BASE,
                       TOHOST_LINK_BASE + RISCV_PGSIZE,
                       PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);

  vm_validate_u2_plain_mapping (VM_VIRT_BASE, U2_IMAGE_PA_BASE,
                                PTE_V | PTE_R | PTE_W | PTE_X | PTE_U,
                                1342);
  vm_validate_u2_plain_mapping (vm_u2_tp_va, U2_STACK_PA_BASE,
                                PTE_V | PTE_R | PTE_W | PTE_U,
                                1343);
  vm_validate_u2_plain_mapping (VM_VIRT_BASE
                                + (TOHOST_LINK_BASE - CACHE_CRYPTO_LINK_BASE),
                                TOHOST_LINK_BASE,
                                PTE_V | PTE_R | PTE_W | PTE_U,
                                1344);

  vm_probe_target_root_page_table = vm_u2_root_page_table;
  vm_stage_emit (0x211);
  vm_enter_u2_probe_context (vm_u2_plain_satp);
  vm_probe_range_call (VM_VIRT_BASE, U2_IMAGE_PA_BASE,
                       U2_IMAGE_PA_BASE + runtime_bytes,
                       PTE_V | PTE_R | PTE_W | PTE_X | PTE_U | PTE_A | PTE_D);
  vm_probe_range_call (vm_u2_tp_va, U2_STACK_PA_BASE,
                       U2_STACK_PA_BASE + stack_bytes,
                       PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);
  vm_probe_range_call (VM_VIRT_BASE + (TOHOST_LINK_BASE - DRAM_BASE),
                       TOHOST_LINK_BASE, TOHOST_LINK_BASE + RISCV_PGSIZE,
                       PTE_V | PTE_R | PTE_W | PTE_U | PTE_A | PTE_D);
  vm_probe_target_root_page_table = vm_u2_enc_root_page_table;

  vm_stage_emit (0x212);
  vm_u2_encrypt_runtime_image (VM_VIRT_BASE, runtime_va_end);
  vm_stage_emit (0x213);
  vm_u2_finalize_cache_state ();
  vm_stage_emit (0x214);

  vm_clear_trapframe (vm_u2_trapframe);

  vm_u2_trapframe[VM_TRAPFRAME_SP_SLOT] = vm_u2_sp_va;
  vm_u2_trapframe[VM_TRAPFRAME_GP_SLOT] = read_gp () + VM_VIRT_OFFSET;
  vm_u2_trapframe[VM_TRAPFRAME_TP_SLOT] = vm_u2_tp_va;
  vm_u2_trapframe[VM_TRAPFRAME_MEPC_SLOT] =
    ((uintptr_t) embench_cryptoexec_main - CACHE_CRYPTO_LINK_BASE)
    + VM_VIRT_BASE;
  vm_u2_trapframe[VM_TRAPFRAME_MSTATUS_SLOT] =
    (read_mstatus () & ~(MSTATUS_MPRV | MSTATUS_MPP)) | MSTATUS_MPP_U;

  vm_u1_trapframe_valid = 0;
  vm_u2_trapframe_valid = 1;
  vm_u1_trapframe_live = 0;
  vm_u2_trapframe_live = 0;
  vm_current_process = 0;
  vm_probe_target_root_page_table = 0;
  vm_su_stage = VM_SU_STAGE_WAIT_U1;
  vm_su_u_status = 0;
  vm_su_u2_status = 0;
}

void __attribute__ ((noreturn))
_exit (int status)
{
  register uintptr_t a0_reg asm ("a0") = (uintptr_t) status;
  register uintptr_t a7_reg asm ("a7") = (uintptr_t) VM_EXIT_ECALL_ID;

  __asm__ volatile ("ecall" : : "r" (a0_reg), "r" (a7_reg) : "memory");

  while (1)
    ;
}

void
vmembench_debug_dump_probe_watch (void)
{
  /* Keep the debug hook callable from assembly, but leave it silent so the
     encrypted runtime image contains no printable diagnostics. */
  (void) vm_probe_map_log;
  (void) vm_probe_map_log_count;
  (void) vm_probe_map_log_dropped;
  (void) vm_probe_watch_va;
  (void) vm_probe_watch_tval;
  (void) vm_probe_watch_enc_page;
  (void) vm_probe_watch_hits;
}

static inline uint64_t
make_table_pte (uint64_t *table)
{
  return ((((uintptr_t) table) >> RISCV_PGSHIFT) << PTE_PPN_SHIFT) | PTE_V;
}

static inline uint64_t
make_leaf_pte (uintptr_t pa, uintptr_t flags)
{
  return ((pa >> RISCV_PGSHIFT) << PTE_PPN_SHIFT) | flags;
}

static uint64_t *
vm_alloc_page_table (void)
{
  uint64_t *table = vm_enc_next_free_page_table;

  if (table >= vm_enc_page_table_pool_end)
    return 0;

  vm_enc_next_free_page_table += RISCV_PGSIZE / sizeof (uint64_t);
  return table;
}

static int
vm_map_page_4k (uint64_t *root, uintptr_t va, uintptr_t pa, uintptr_t flags)
{
  const uintptr_t vpn2 = (va >> 30) & 0x1ff;
  const uintptr_t vpn1 = (va >> 21) & 0x1ff;
  const uintptr_t vpn0 = (va >> 12) & 0x1ff;

  uint64_t pte2 = root[vpn2];
  uint64_t *l1;
  if (!(pte2 & PTE_V))
    {
      l1 = vm_alloc_page_table ();
      if (!l1)
        return -1;
      root[vpn2] = make_table_pte (l1);
    }
  else
    {
      l1 = (uint64_t *) (((pte2 >> PTE_PPN_SHIFT) << RISCV_PGSHIFT));
    }

  uint64_t pte1 = l1[vpn1];
  uint64_t *l0;
  if (!(pte1 & PTE_V))
    {
      l0 = vm_alloc_page_table ();
      if (!l0)
        return -1;
      l1[vpn1] = make_table_pte (l0);
    }
  else
    {
      l0 = (uint64_t *) (((pte1 >> PTE_PPN_SHIFT) << RISCV_PGSHIFT));
    }

  l0[vpn0] = make_leaf_pte (pa, flags);
  return 0;
}

void
vm_init_encrypted_page_tables (uintptr_t stack_start, uintptr_t stack_end)
{
  vm_enc_next_free_page_table = vm_enc_page_table_pool;

  for (uintptr_t page = stack_start & ~(uintptr_t) (RISCV_PGSIZE - 1);
      page < stack_end;
       page += RISCV_PGSIZE)
    {
      if (vm_map_page_4k (vm_enc_root_page_table, page, page,
                          PTE_V | PTE_R | PTE_W | PTE_X | PTE_U | PTE_A | PTE_D) != 0)
        {
          vmembench_tohost_exit (1338);
        }
    }
}

uintptr_t VM_MMODE_TEXT
handle_vm_probe_fault (uintptr_t epc, uintptr_t cause, uintptr_t tval,
                       uintptr_t *regs)
{
  (void) epc;
  (void) regs;

  if (vm_probe_active == VM_PROBE_ACTIVE_FLAG
      && (cause == CAUSE_FETCH_PAGE_FAULT
          || cause == CAUSE_LOAD_PAGE_FAULT
          || cause == CAUSE_STORE_PAGE_FAULT))
    {
      uintptr_t enc_va_page = tval & ~(uintptr_t) (RISCV_PGSIZE - 1);
      uintptr_t pa_page = vm_probe_pa & ~(uintptr_t) (RISCV_PGSIZE - 1);

      if ((vm_probe_va & ~(uintptr_t) (RISCV_PGSIZE - 1))
          == (vm_probe_watch_va & ~(uintptr_t) (RISCV_PGSIZE - 1)))
        {
          vm_probe_watch_tval = tval;
          vm_probe_watch_enc_page = enc_va_page;
          vm_probe_watch_hits += 1;
        }

      uintptr_t *target_root = vm_probe_target_root_page_table;
      int map_result;

      if (target_root == 0 || target_root == (uintptr_t *) vm_enc_root_page_table)
        {
          map_result = vm_map_page_4k (vm_enc_root_page_table, enc_va_page,
                                       pa_page, vm_probe_flags);
        }
      else if (target_root == (uintptr_t *) vm_u2_enc_root_page_table)
        {
          map_result =
            vm_u2_map_page_4k_with_alloc (vm_u2_enc_root_page_table,
                                          enc_va_page, pa_page, vm_probe_flags,
                                          vm_u2_alloc_enc_page_table);
          if (map_result == 0)
            {
              uintptr_t pte = vm_walk_leaf_pte (vm_u2_enc_root_page_table,
                                                enc_va_page);
              uintptr_t mapped_pa =
                ((pte >> PTE_PPN_SHIFT) << RISCV_PGSHIFT);

              if ((pte & PTE_V) == 0)
                vmembench_tohost_exit (1353);
              if (mapped_pa != pa_page)
                vmembench_tohost_exit (1354);
              if ((pte & vm_probe_flags) != vm_probe_flags)
                vmembench_tohost_exit (1355);
            }
        }
      else if (target_root == (uintptr_t *) vm_u2_root_page_table)
        {
          map_result =
            vm_u2_map_page_4k_with_alloc (vm_u2_root_page_table,
                                          enc_va_page, pa_page, vm_probe_flags,
                                          vm_u2_alloc_page_table);
        }
      else
        {
          map_result = -1;
        }

      if (map_result != 0)
        {
          vmembench_tohost_exit (1339);
        }

      if (vm_probe_map_log_count < VM_PROBE_MAP_LOG_CAP)
        {
          vm_probe_map_log_t *log = &vm_probe_map_log[vm_probe_map_log_count++];
          log->cause = cause;
          log->probe_va = vm_probe_va;
          log->tval = tval;
          log->enc_va_page = enc_va_page;
          log->pa_page = pa_page;
          log->flags = vm_probe_flags;
          log->pte = make_leaf_pte (pa_page, vm_probe_flags);
        }
      else
        {
          vm_probe_map_log_dropped += 1;
        }

      __asm__ volatile ("sfence.vma x0, x0" : : : "memory");
      vm_probe_active = 0;
      return vm_probe_resume_pc;
    }

  vmembench_leave_vm_mode ();
  vmembench_probe_fault_diag_emit (cause, epc, tval);
  vmembench_tohost_exit (1337);
}

void __attribute__ ((noreturn)) VM_MMODE_TEXT
handle_trap (uintptr_t epc, uintptr_t cause, uintptr_t tval,
             uintptr_t trapped_a0, uintptr_t trapped_a7)
{
  uintptr_t trap_mepc;
  uintptr_t trap_mtval;

  __asm__ volatile ("csrr %0, mepc\n\t"
                    "csrr %1, mtval"
                    : "=r" (trap_mepc), "=r" (trap_mtval));

  (void) trap_mepc;
  (void) trap_mtval;
  (void) epc;
  (void) cause;
  (void) tval;

  if (cause == CAUSE_USER_ECALL
      && trapped_a7 == (uintptr_t) VM_EXIT_ECALL_ID)
    {
      uintptr_t code = trapped_a0;

      vmembench_leave_vm_mode ();

      if (vm_current_process == VM_PROCESS_1_ID
          && vm_su_stage == VM_SU_STAGE_WAIT_U1)
        {
          vm_su_u_status = code;
          vm_su_stage = VM_SU_STAGE_WAIT_U2;
          vm_resume_saved_u2_runtime ();
        }

      if (vm_current_process == VM_PROCESS_2_ID
          && vm_su_stage == VM_SU_STAGE_WAIT_U2)
        {
          vm_su_u2_status = code;
          vmembench_tohost_exit (vm_su_u_status | vm_su_u2_status);
        }

      vmembench_tohost_exit (code);
    }

  if (cause == CAUSE_USER_ECALL
      && trapped_a7 == (uintptr_t) VM_DEBUG_ECALL_ID)
    {
      vmembench_leave_vm_mode ();
      vmembench_debug_emit (trapped_a0);
      vmembench_tohost_exit (1);
    }

  vmembench_leave_vm_mode ();
  vmembench_trap_diag_emit (cause, trap_mepc, trap_mtval, trapped_a7);
  vmembench_tohost_exit (1337);
}

void
initialise_board ()
{
  uintptr_t counter_base =
    (uintptr_t) counter_store - (uintptr_t) CACHE_CRYPTO_COUNTER_BASE_BIAS (VM_VIRT_BASE);

  icache_crypto_write_enable (0);
  cache_crypto_write_enable (0);
  addr_crypto_fetch_enable (0);
  addr_crypto_write_enable (0);
  addr_crypto_read_enable (0);
  cache_crypto_fence ();
  cache_crypto_write_counter_base (counter_base);
  cache_crypto_fence ();
}

void __attribute__ ((noinline)) __attribute__ ((externally_visible))
start_trigger ()
{
  embench_marker_measure_start ();
  cache_crypto_fence ();
  embench_custom_trap_roundtrip ();
}

void __attribute__ ((noinline)) __attribute__ ((externally_visible))
stop_trigger ()
{
  embench_marker_verify_start ();
  cache_crypto_fence ();
  /* Keep cache-crypto and address-crypto enabled so verify_benchmark() and
     _exit() execute in the same virtual encrypted runtime mode. */
}
