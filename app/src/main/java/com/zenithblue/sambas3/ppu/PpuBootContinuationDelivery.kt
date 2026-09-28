package com.zenithblue.sambas3.ppu

import java.util.concurrent.atomic.AtomicBoolean

/** One Activity generation may consume the accepted boot request only once. */
internal class PpuBootContinuationDelivery {
    private val consumed = AtomicBoolean(false)

    fun consume(): Boolean = consumed.compareAndSet(false, true)
}
