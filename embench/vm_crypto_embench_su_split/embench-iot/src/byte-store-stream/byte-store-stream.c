/*
 * Minimal VM byte-store stream benchmark.
 *
 * This is intentionally shaped like a simple memset-style byte loop:
 * repeatedly sweep a multi-page buffer with consecutive byte stores.
 * The goal is to isolate VM + address-crypto + D$ store-path behavior
 * without mixing in unrelated algorithmic work.
 */

#include <stdint.h>

#include "support.h"

#define LOCAL_SCALE_FACTOR 1
#define WORK_BYTES (3 * 4096)

static volatile uint8_t byte_store_buf[WORK_BYTES] __attribute__ ((aligned (4096)));

void
initialise_benchmark (void)
{
}

static int benchmark_body (int rpt);

void
warm_caches (int heat)
{
  benchmark_body (heat);
}

int
benchmark (void)
{
  return benchmark_body (LOCAL_SCALE_FACTOR * CPU_MHZ);
}

static int __attribute__ ((noinline))
benchmark_body (int rpt)
{
  uint8_t value = 0;

  while (rpt-- > 0)
    {
      embench_mode_switch_ecall ();
      volatile uint8_t *p = byte_store_buf;
      volatile uint8_t *end = byte_store_buf + WORK_BYTES;

      while (p != end)
        {
          *p++ = value;
        }

      value += 1;
    }

  return (int) ((uint8_t) (value - 1));
}

int
verify_benchmark (int result)
{
  const uint8_t expected = (uint8_t) result;

  for (int i = 0; i < WORK_BYTES; ++i)
    {
      if (byte_store_buf[i] != expected)
        return 0;
    }

  return 1;
}
