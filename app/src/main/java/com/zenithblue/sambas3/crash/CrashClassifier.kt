package com.zenithblue.sambas3.crash

enum class CrashClassification { CONFIRMED_CRASH, RECOVERABLE_EMULATOR_FAILURE, UNEXPECTED_TERMINATION, CLEAN_STOP }

object CrashClassifier {
    private val fatal = Regex("SIGSEGV|SIGABRT|Fatal signal|Scudo|FATAL EXCEPTION|assertion failed|VK_ERROR_DEVICE_LOST|native renderer fatal|Access violation", RegexOption.IGNORE_CASE)

    fun classify(evidence: String, hadUnfinishedSession: Boolean, cleanStop: Boolean = false): CrashClassification = when {
        cleanStop -> CrashClassification.CLEAN_STOP
        fatal.containsMatchIn(evidence) -> CrashClassification.CONFIRMED_CRASH
        hadUnfinishedSession -> CrashClassification.UNEXPECTED_TERMINATION
        else -> CrashClassification.RECOVERABLE_EMULATOR_FAILURE
    }

    fun likelyCause(evidence: String): String = when {
        Regex("VK_ERROR_DEVICE_LOST|device lost|native renderer fatal|gpu fault|driver crash", RegexOption.IGNORE_CASE).containsMatchIn(evidence) -> "GPU / Vulkan / driver"
        Regex("SIG|Scudo|Access violation|assertion|FATAL", RegexOption.IGNORE_CASE).containsMatchIn(evidence) -> "Native emulator / backend"
        else -> "Emulator or application"
    }
}
