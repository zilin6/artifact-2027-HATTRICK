/* Support header for BEEBS.

   Copyright (C) 2014 Embecosm Limited and the University of Bristol
   Copyright (C) 2019 Embecosm Limited

   Contributor James Pallister <james.pallister@bristol.ac.uk>

   Contributor Jeremy Bennett <jeremy.bennett@embecosm.com>

   This file is part of Embench and was formerly part of the Bristol/Embecosm
   Embedded Benchmark Suite.

   SPDX-License-Identifier: GPL-3.0-or-later */

#ifndef SUPPORT_H
#define SUPPORT_H

#ifdef HAVE_CONFIG_H
#include "config.h"
#endif

/* Include board support header if we have one. The Embench build in this tree
   passes the board directory on the include path, but does not always define
   HAVE_BOARDSUPPORT_H. */

#if defined (HAVE_BOARDSUPPORT_H)
#include "boardsupport.h"
#elif defined (__has_include)
#if __has_include ("boardsupport.h")
#include "boardsupport.h"
#endif
#endif

/* Benchmarks must implement verify_benchmark, which must return -1 if no
   verification is done. */

int verify_benchmark (int result);

/* Standard functions implemented for each board */

void initialise_board (void);
void start_trigger (void);
void stop_trigger (void);

/* Every benchmark implements this for one-off data initialization.  This is
   only used for initialization that is independent of how often benchmark ()
   is called. */

void initialise_benchmark (void);

/* Every benchmark implements this for cache warm up, typically calling
   benchmark several times. The argument controls how much warming up is
   done, with 0 meaning no warming. */

void warm_caches (int temperature);

/* Every benchmark implements this as its entry point. Don't allow it to be
   inlined! */

int benchmark (void) __attribute__ ((noinline));

/* Every benchmark must implement this to validate the result of the
   benchmark. */

int verify_benchmark (int res);

/* Local simplified versions of library functions */

#include "beebsc.h"
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VM_DEBUG_ECALL_ID 0x564d444247ULL
#define VM_CUSTOM_ECALL_ID 0x564d435553544f4dULL
#define VM_MODE_SWITCH_ECALL_ID 0x564d535749544348ULL

/* Distinct no-op markers used to identify phase boundaries in verbose logs. */
static inline void
embench_marker_orig_startup_end (void)
{
  __asm__ volatile (".4byte 0x10000013" : : : "memory");
}

static inline void
embench_marker_startup_end (void)
{
  __asm__ volatile (".4byte 0x10100013" : : : "memory");
}

static inline void
embench_marker_measure_start (void)
{
  __asm__ volatile (".4byte 0x10200013" : : : "memory");
}

static inline void
embench_marker_verify_start (void)
{
  __asm__ volatile (".4byte 0x10300013" : : : "memory");
}

static inline void
embench_custom_trap_roundtrip (void)
{
  register uintptr_t a7_reg asm ("a7") = (uintptr_t) VM_CUSTOM_ECALL_ID;

  __asm__ volatile ("ecall" : : "r" (a7_reg) : "memory");
}

static inline void
embench_mode_switch_ecall (void)
{
  register uintptr_t a7_reg asm ("a7") = (uintptr_t) VM_MODE_SWITCH_ECALL_ID;

  __asm__ volatile ("ecall" : : "r" (a7_reg) : "memory");
}

static inline uint64_t
embench_read_cycle (void)
{
  uint64_t cycle;
  __asm__ volatile ("csrr %0, mcycle" : "=r" (cycle));
  return cycle;
}

static inline void
embench_debug_mismatch (unsigned channel, unsigned index,
                        unsigned actual, unsigned expected)
{
  register uintptr_t a0_reg asm ("a0") =
    (((uintptr_t) 1U) << 56)
    | (((uintptr_t) channel & 0xffU) << 48)
    | (((uintptr_t) index & 0xffffU) << 32)
    | (((uintptr_t) actual & 0xffffU) << 16)
    | ((uintptr_t) expected & 0xffffU);
  register uintptr_t a7_reg asm ("a7") = (uintptr_t) VM_DEBUG_ECALL_ID;

  __asm__ volatile ("ecall" : : "r" (a0_reg), "r" (a7_reg) : "memory");
}

#ifdef __cplusplus
}
#endif

#endif /* SUPPORT_H */

/*
   Local Variables:
   mode: C
   c-file-style: "gnu"
   End:
*/
