package com.zenithblue.sambas3

/** Explicit launch intent contract. Nullable savestate extras are not a boot mode. */
enum class EmulatorBootMode {
    FreshGame,
    UserSelectedSavestate,
    DurableRecovery
}
