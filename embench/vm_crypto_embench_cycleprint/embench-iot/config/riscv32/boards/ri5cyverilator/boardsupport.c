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

#define CACHE_CRYPTO_LINK_BASE 0x80000000ULL
#define VM_VIRT_BASE 0x40000000ULL
#define VM_PHYS_DELTA (CACHE_CRYPTO_LINK_BASE - VM_VIRT_BASE)
#define CACHE_CRYPTO_COUNTER_BASE_BIAS (((CACHE_CRYPTO_LINK_BASE >> 6) << 3))
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

extern volatile uint64_t tohost;
extern volatile uint64_t fromhost;
extern uint64_t vm_enc_root_page_table[512];
extern uint64_t vm_enc_page_table_pool[];
extern uint64_t vm_enc_page_table_pool_end[];
uintptr_t vm_probe_active;
uintptr_t vm_probe_va;
uintptr_t vm_probe_pa;
uintptr_t vm_probe_flags;
uintptr_t vm_probe_resume_pc;
uintptr_t vm_probe_force_direct_return;
uintptr_t vm_probe_watch_va;
uintptr_t vm_probe_watch_tval;
uintptr_t vm_probe_watch_enc_page;
uintptr_t vm_probe_watch_hits;

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

static vm_probe_map_log_t vm_probe_map_log[VM_PROBE_MAP_LOG_CAP];
static uintptr_t vm_probe_map_log_count;
static uintptr_t vm_probe_map_log_dropped;
static char vmembench_debug_buf[96];

uint8_t counter_store[CACHE_CRYPTO_COUNTER_STORE_BYTES]
  __attribute__ ((aligned (4096), used, section(".crypto_counter_store")));

static uint64_t *vm_enc_next_free_page_table;

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
  /* Force the final HTIF traffic onto a plain path that matches riscv-tests:
     disable both load/store data crypto and both load/store address crypto
     before touching tohost/fromhost.  */
  cache_crypto_write_enable (0);
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
  static const char hex[] = "0123456789abcdef";
  for (uintptr_t i = 0; i < digits; ++i)
    {
      uintptr_t shift = (digits - 1 - i) * 4;
      buf[pos++] = hex[(value >> shift) & 0xf];
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
                              "DBG mismatch ch=");
  vmembench_debug_buf[pos++] = (char) channel;
  pos = vmembench_append_str (vmembench_debug_buf, pos, " idx=0x");
  pos = vmembench_append_hex (vmembench_debug_buf, pos, index, 4);
  pos = vmembench_append_str (vmembench_debug_buf, pos, " act=0x");
  pos = vmembench_append_hex (vmembench_debug_buf, pos, actual, 2);
  pos = vmembench_append_str (vmembench_debug_buf, pos, " exp=0x");
  pos = vmembench_append_hex (vmembench_debug_buf, pos, expected, 2);
  vmembench_debug_buf[pos++] = '\n';

  vmembench_prepare_tohost_store ();
  vmembench_tohost_write_chars (vmembench_debug_buf, pos);
}

static void __attribute__ ((noreturn)) VM_MMODE_TEXT
vmembench_tohost_exit (uintptr_t code)
{
  volatile uint32_t *tohost_mmio = (volatile uint32_t *) &tohost;
  uint64_t tohost_value = ((uint64_t) code << 1) | 1;
  vmembench_prepare_tohost_store ();
  tohost_mmio[0] = (uint32_t) tohost_value;
  tohost_mmio[1] = (uint32_t) (tohost_value >> 32);
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

      if (vm_map_page_4k (vm_enc_root_page_table, enc_va_page, pa_page,
                          vm_probe_flags) != 0)
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

  if ((cause == CAUSE_SUPERVISOR_ECALL || cause == CAUSE_USER_ECALL)
      && trapped_a7 == (uintptr_t) VM_EXIT_ECALL_ID)
    {
      uintptr_t code = trapped_a0;

      vmembench_leave_vm_mode ();
      vmembench_tohost_exit (code);
    }

  if ((cause == CAUSE_SUPERVISOR_ECALL || cause == CAUSE_USER_ECALL)
      && trapped_a7 == (uintptr_t) VM_DEBUG_ECALL_ID)
    {
      vmembench_leave_vm_mode ();
      vmembench_debug_emit (trapped_a0);
      vmembench_tohost_exit (1);
    }

  vmembench_leave_vm_mode ();
  vmembench_tohost_exit (1337);
}

void
initialise_board ()
{
  uintptr_t counter_base =
    (uintptr_t) counter_store - (uintptr_t) CACHE_CRYPTO_COUNTER_BASE_BIAS;

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
}

void __attribute__ ((noinline)) __attribute__ ((externally_visible))
stop_trigger ()
{
  embench_marker_verify_start ();
  cache_crypto_fence ();
  /* Keep cache-crypto and address-crypto enabled so verify_benchmark() and
     _exit() execute in the same virtual encrypted runtime mode. */
}
