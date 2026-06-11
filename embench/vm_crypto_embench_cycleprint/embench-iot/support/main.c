/* Common main.c for the benchmarks

   Copyright (C) 2014 Embecosm Limited and University of Bristol
   Copyright (C) 2018-2019 Embecosm Limited

   Contributor: James Pallister <james.pallister@bristol.ac.uk>
   Contributor: Jeremy Bennett <jeremy.bennett@embecosm.com>

   This file is part of Embench and was formerly part of the Bristol/Embecosm
   Embedded Benchmark Suite.

   SPDX-License-Identifier: GPL-3.0-or-later */

#include "support.h"
extern void _exit (int status) __attribute__ ((noreturn));

void __attribute__ ((noreturn, noinline, externally_visible))
embench_cryptoexec_main (void)
{
  volatile int result;
  int correct;
  __asm__ volatile (".4byte 0x11700013" : : : "memory");
  embench_marker_startup_end ();
  warm_caches (WARMUP_HEAT);

  start_trigger ();
  result = benchmark ();
  stop_trigger ();

  correct = verify_benchmark (result);
  _exit (!correct);
}


int __attribute__ ((used))
main (int argc __attribute__ ((unused)),
      char *argv[] __attribute__ ((unused)))
{
  initialise_board ();
  initialise_benchmark ();
  embench_cryptoexec_main ();
}				/* main () */


/*
   Local Variables:
   mode: C
   c-file-style: "gnu"
   End:
*/
