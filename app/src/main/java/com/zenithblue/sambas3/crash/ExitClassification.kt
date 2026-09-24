package com.zenithblue.sambas3.crash

/**
 * Robust typed classification of process exits per SambaS3 Ticket C02.
 */
enum class ExitClassification {
    /** Native signal, abort, segmentation fault, bus error, or Scudo failure. */
    NATIVE_CRASH,

    /** Unhandled Java / Kotlin exception or CheckJNI abort. */
    JAVA_EXCEPTION,

    /** Terminated by LMKD (low memory killer daemon) or Android low memory killer. */
    MEMORY_PRESSURE_KILL,

    /** Application Not Responding timeout. */
    ANR,

    /** Deliberate stop: user exited, DEBUG_STOP_GAME, activity finish, or normal worker recycling. */
    DELIBERATE_STOP,

    /** GPU reset, VK_ERROR_DEVICE_LOST, or driver hang. */
    VULKAN_DEVICE_LOSS,

    /** System server termination: package update, permission change, or force-stop by OS. */
    SYSTEM_SERVER_KILL,

    /** Evidence is inconclusive or does not distinguish the root cause. */
    UNKNOWN;

    /** Compatibility mapping to legacy 4-state CrashClassification. */
    val legacyClassification: CrashClassification
        get() = when (this) {
            NATIVE_CRASH, JAVA_EXCEPTION, MEMORY_PRESSURE_KILL, ANR, VULKAN_DEVICE_LOSS ->
                CrashClassification.CONFIRMED_CRASH
            DELIBERATE_STOP ->
                CrashClassification.CLEAN_STOP
            SYSTEM_SERVER_KILL, UNKNOWN ->
                CrashClassification.UNEXPECTED_TERMINATION
        }
}

/**
 * Structured diagnostic result from exit analysis.
 */
data class ProcessExitDiagnosis(
    val classification: ExitClassification,
    val pid: Int? = null,
    val processName: String? = null,
    val isPpuCompileProcess: Boolean = false,
    val signal: Int? = null,
    val signalName: String? = null,
    val status: Int? = null,
    val pc: String? = null,
    val backtrace: List<String> = emptyList(),
    val stackTrace: String? = null,
    val reasonCode: Int? = null,
    val reasonName: String? = null,
    val subReasonCode: Int? = null,
    val subReasonName: String? = null,
    val importance: Int? = null,
    val timestamp: Long? = null,
    val pssBytes: Long? = null,
    val rssBytes: Long? = null,
    val description: String? = null,
    val summary: String = "",
    val rawTrace: String? = null,
    val memoryPressureEvidence: Boolean = false,
)
