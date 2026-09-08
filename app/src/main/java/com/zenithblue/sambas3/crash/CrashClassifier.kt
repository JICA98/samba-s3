package com.zenithblue.sambas3.crash

enum class CrashClassification { CONFIRMED_CRASH, RECOVERABLE_EMULATOR_FAILURE, UNEXPECTED_TERMINATION, CLEAN_STOP }

object CrashClassifier {
    private val fatal = Regex("SIGSEGV|SIGABRT|Fatal signal|Scudo|FATAL EXCEPTION|assertion failed|VK_ERROR_DEVICE_LOST|native renderer fatal|Access violation", RegexOption.IGNORE_CASE)

    fun classify(
        evidence: String,
        hadUnfinishedSession: Boolean,
        cleanStop: Boolean = false,
        fatalEventId: String? = null,
        frontendReason: String? = null,
    ): CrashClassification {
        val typedFatal = !fatalEventId.isNullOrBlank()
        if (typedFatal) return CrashClassification.CONFIRMED_CRASH
        if (cleanStop && !typedFatal) return CrashClassification.CLEAN_STOP
        if (com.zenithblue.sambas3.logging.SessionDiagnostics.isCleanFrontendReason(frontendReason) && !typedFatal) {
            return CrashClassification.CLEAN_STOP
        }
        if (fatal.containsMatchIn(evidence) && typedFatal) return CrashClassification.CONFIRMED_CRASH
        if (hadUnfinishedSession) return CrashClassification.UNEXPECTED_TERMINATION
        return CrashClassification.RECOVERABLE_EMULATOR_FAILURE
    }

    fun likelyCause(evidence: String): String = when {
        Regex("VK_ERROR_DEVICE_LOST|device lost|native renderer fatal|gpu fault|driver crash", RegexOption.IGNORE_CASE).containsMatchIn(evidence) -> "GPU / Vulkan / driver"
        Regex("SIG|Scudo|Access violation|assertion|FATAL", RegexOption.IGNORE_CASE).containsMatchIn(evidence) -> "Native emulator / backend"
        else -> "Emulator or application"
    }
}
