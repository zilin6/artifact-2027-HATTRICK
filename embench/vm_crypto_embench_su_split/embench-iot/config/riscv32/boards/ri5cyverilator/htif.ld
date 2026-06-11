OUTPUT_ARCH ("riscv")
ENTRY (_start)

SECTIONS
{
    . = 0x80000000;

    /* Keep under a power of 2 to reserve space for TCB */
    PROVIDE (__stack_size_min = 24K);

    .text.init : {
        *(.text.init)
    }

    /* M-mode trap/exit helpers must stay outside the crypto runtime image.
       The trap handler may need to execute after the crypto/plain runtime
       code image has already been corrupted by another bug, so keep this
       section isolated ahead of __crypto_runtime_start. */
    .text.mmode : {
        *(.text.mmode .text.mmode.*)
    }

    .mmode_data : ALIGN (8) {
        *(.mmode_data .mmode_data.*)
    }

    .mmode_bss (NOLOAD) : ALIGN (8) {
        *(.mmode_bss .mmode_bss.*)
    }

    . = ALIGN(0x40);

    .text : {
        *(.text.unlikely .text.unlikely.*)
        *(.text.startup .text.startup.*)
        *(.text .text.*)
        *(.gnu.linkonce.t.*)
    }
    PROVIDE_HIDDEN (__crypto_exec_start = ADDR (.text));
    PROVIDE_HIDDEN (__crypto_runtime_start = ADDR (.text));
    PROVIDE (_etext = .);

    /* Static Thread Local Storage template */
    .tdata : {
        PROVIDE_HIDDEN (__tdata_start = .);
        *(.tdata .tdata.*)
        *(.gnu.linkonce.td.*)
        PROVIDE_HIDDEN (__tdata_end = .);
    }
    PROVIDE (__tdata_size = SIZEOF (.tdata));

    .tbss (NOLOAD) : {
        PROVIDE_HIDDEN (__tbss_start = .);
        PROVIDE_HIDDEN (__tbss_offset = ABSOLUTE (__tbss_start - __tdata_start));
        *(.tbss .tbss.*)
        *(.gnu.linkonce.tb.*)
        *(.tcommon)
        PROVIDE_HIDDEN (__tbss_end = .);
    }
    PROVIDE (__tbss_size = SIZEOF (.tbss));

    .rodata : {
        *(.rodata .rodata.*)
        *(.gnu.linkonce.r.*)
    }

    .preinit_array : {
        PROVIDE_HIDDEN (__preinit_array_start = .);
        KEEP (*(.preinit_array))
        PROVIDE_HIDDEN (__preinit_array_end = .);
    }

    .init_array : {
        PROVIDE_HIDDEN (__init_array_start = .);
        KEEP (*(SORT_BY_INIT_PRIORITY(.init_array.*) SORT_BY_INIT_PRIORITY(.ctors.*)))
        KEEP (*(.init_array EXCLUDE_FILE (*crtbegin.o *crtbegin?.o *crtend.o *crtend?.o ) .ctors))
        PROVIDE_HIDDEN (__init_array_end = .);
    }

    .fini_array : {
        PROVIDE_HIDDEN (__fini_array_start = .);
        KEEP (*(SORT_BY_INIT_PRIORITY(.fini_array.*) SORT_BY_INIT_PRIORITY(.dtors.*)))
        KEEP (*(.fini_array EXCLUDE_FILE (*crtbegin.o *crtbegin?.o *crtend?.o *crtend.o ) .dtors))
        PROVIDE_HIDDEN (__fini_array_end = .);
    }

    .ctors : {
        KEEP (*crtbegin.o(.ctors))
        KEEP (*crtbegin?.o(.ctors))
        KEEP (*(EXCLUDE_FILE (*crtend.o *crtend?.o) .ctors))
        KEEP (*(SORT(.ctors.*)))
        KEEP (*(.ctors))
    }

    .dtors : {
        KEEP (*crtbegin.o(.dtors))
        KEEP (*crtbegin?.o(.dtors))
        KEEP (*(EXCLUDE_FILE (*crtend.o *crtend?.o) .dtors))
        KEEP (*(SORT(.dtors.*)))
        KEEP (*(.dtors))
    }

    .data : ALIGN (8) {
        PROVIDE_HIDDEN (__crypto_writable_start = .);
        *(.data)
        *(.gnu.linkonce.d.*)
    }

    .sdata : {
        PROVIDE (__global_pointer$ = . + 0x800);
        *(.sdata .sdata.*)
        *(.gnu.linkonce.s.*)
        *(.srodata.cst16)
        *(.srodata.cst8)
        *(.srodata.cst4)
        *(.srodata.cst2)
        *(.srodata .srodata.*)
    }

    PROVIDE (_edata = .);

    .bss : ALIGN (8) {
        PROVIDE_HIDDEN (__bss_start = .);
        *(.sbss .sbss.*)
        *(.gnu.linkonce.sb.*)
        *(.bss .bss.*)
        *(.gnu.linkonce.b.*)
        *(COMMON)
        PROVIDE_HIDDEN (__bss_end = .);
    }

    /* Runtime image that must be rewritten into crypto form before switching
       the cache hierarchy into crypto mode. Round up to a full cache line so
       the startup rewrite covers every line touched by .bss globals. */
    PROVIDE_HIDDEN (__crypto_writable_end = __bss_end);
    PROVIDE_HIDDEN (__crypto_writable_pages_start = (__crypto_writable_start & ~(0x1000 - 1)));
    PROVIDE_HIDDEN (__crypto_writable_pages_end = ALIGN (__crypto_writable_end, 0x1000));
    PROVIDE_HIDDEN (__crypto_runtime_end = ALIGN (__bss_end, 0x40));

    . = ALIGN (8);
    PROVIDE (_end = .);

    /* Reserve heap space */
    PROVIDE(__heap_size = 128K);
    . += __heap_size;
    . = ALIGN (4K);
    PROVIDE (__heap_end = .);
    PROVIDE_HIDDEN (__vm_runtime_phys_end = ALIGN (__heap_end, 0x1000));

    . = __vm_runtime_phys_end;
    .vm_page_table (NOLOAD) : ALIGN (0x1000) {
        *(.vm_page_table)
    }

    /* Place Thread Control Block (TCB) at bottom of hart stack */
    PROVIDE (__tcb_size = __tbss_end - __tdata_start);
    PROVIDE (__stack_align = MAX (ALIGNOF (.tdata), 0x10));
    PROVIDE (__stack_start = ALIGN (__stack_align));
    PROVIDE (__stack_shift = LOG2CEIL( ALIGN (__stack_size_min + __tcb_size, __stack_align)));
    PROVIDE (__stack_size = 1 << __stack_shift);
    PROVIDE (__m_trap_stack_align = 0x10);
    PROVIDE (__m_trap_stack_size_min = 4K);
    PROVIDE (__m_trap_stack_shift = LOG2CEIL( ALIGN (__m_trap_stack_size_min, __m_trap_stack_align)));
    PROVIDE (__m_trap_stack_size = 1 << __m_trap_stack_shift);
    PROVIDE (__m_trap_stack_hart_count = 8);

    /* Cache-crypto backing stores and HTIF live far away from the benchmark
       image so they never share cache lines with benchmark code/data. */
    .crypto_counter_store 0x80200000 : ALIGN(0x1000) {
        *(.crypto_counter_store)
    }

    .crypto_runtime_bss 0x80210000 : ALIGN(0x40) {
        PROVIDE_HIDDEN (__crypto_runtime_bss_start = .);
        *(.crypto_runtime_bss)
        PROVIDE_HIDDEN (__crypto_runtime_bss_end = ALIGN(., 0x1000));
    }

    .m_trap_stack (NOLOAD) : ALIGN(0x1000) {
        PROVIDE_HIDDEN (__m_trap_stack_start = .);
        . += (__m_trap_stack_size * __m_trap_stack_hart_count);
        PROVIDE_HIDDEN (__m_trap_stack_end = .);
    }

    .htif 0x80300000 : ALIGN(0x40) {
        *(.htif)
    }

    /* Default boot hart */
    PROVIDE (__boot_hart = 0);
}
