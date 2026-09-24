package com.zenithblue.sambas3.crash

/**
 * Normalized record of a process exit, representing Android's ApplicationExitInfo
 * or parsed dumpsys activity exit-info output.
 */
data class ProcessExitRecord(
    val pid: Int,
    val processName: String,
    val reason: Int,
    val reasonName: String,
    val subReason: Int = 0,
    val subReasonName: String = "UNKNOWN",
    val status: Int = 0,
    val importance: Int = 0,
    val timestamp: Long = 0L,
    val pssBytes: Long = 0L,
    val rssBytes: Long = 0L,
    val description: String? = null,
    val trace: String? = null,
) {
    /** True if this exit record belongs to the secondary :ppu_compile process. */
    val isPpuCompile: Boolean
        get() = processName.endsWith(":ppu_compile")

    /** True if this exit record belongs to the primary emulator process. */
    val isPrimaryProcess: Boolean
        get() = !processName.contains(":")

    val signalName: String?
        get() = if (reason == REASON_SIGNALED) signalNumberToName(status) else null

    companion object {
        const val REASON_UNKNOWN = 0
        const val REASON_EXIT_SELF = 1
        const val REASON_SIGNALED = 2
        const val REASON_LOW_MEMORY = 3
        const val REASON_CRASH = 4
        const val REASON_CRASH_NATIVE = 5
        const val REASON_ANR = 6
        const val REASON_INITIALIZATION_FAILURE = 7
        const val REASON_PERMISSION_CHANGE = 8
        const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
        const val REASON_USER_REQUESTED = 10
        const val REASON_USER_STOPPED = 11
        const val REASON_DEPENDENCY_DIED = 12
        const val REASON_OTHER = 13
        const val REASON_FREEZER = 14
        const val REASON_PACKAGE_STATE_CHANGE = 15
        const val REASON_PACKAGE_UPDATED = 16

        fun reasonToString(reason: Int): String = when (reason) {
            REASON_EXIT_SELF -> "EXIT_SELF"
            REASON_SIGNALED -> "SIGNALED"
            REASON_LOW_MEMORY -> "LOW_MEMORY"
            REASON_CRASH -> "CRASH"
            REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            REASON_ANR -> "ANR"
            REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            REASON_USER_REQUESTED -> "USER_REQUESTED"
            REASON_USER_STOPPED -> "USER_STOPPED"
            REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            REASON_OTHER -> "OTHER"
            REASON_FREEZER -> "FREEZER"
            REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            else -> "UNKNOWN"
        }

        fun signalNumberToName(sig: Int): String = when (sig) {
            1 -> "SIGHUP"
            2 -> "SIGINT"
            3 -> "SIGQUIT"
            4 -> "SIGILL"
            5 -> "SIGTRAP"
            6 -> "SIGABRT"
            7 -> "SIGBUS"
            8 -> "SIGFPE"
            9 -> "SIGKILL"
            10 -> "SIGUSR1"
            11 -> "SIGSEGV"
            12 -> "SIGUSR2"
            13 -> "SIGPIPE"
            14 -> "SIGALRM"
            15 -> "SIGTERM"
            else -> "SIGNAL_$sig"
        }

        fun signalNameToNumber(name: String): Int = when (name.uppercase()) {
            "SIGHUP" -> 1
            "SIGINT" -> 2
            "SIGQUIT" -> 3
            "SIGILL" -> 4
            "SIGTRAP" -> 5
            "SIGABRT" -> 6
            "SIGBUS" -> 7
            "SIGFPE" -> 8
            "SIGKILL" -> 9
            "SIGUSR1" -> 10
            "SIGSEGV" -> 11
            "SIGUSR2" -> 12
            "SIGPIPE" -> 13
            "SIGALRM" -> 14
            "SIGTERM" -> 15
            else -> 0
        }
    }
}
