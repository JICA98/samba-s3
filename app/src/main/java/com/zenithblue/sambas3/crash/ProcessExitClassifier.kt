package com.zenithblue.sambas3.crash

import com.zenithblue.sambas3.session.EmulationSessionRecord
import com.zenithblue.sambas3.session.EmulationSessionState

/**
 * Robust classifier for process exits and failure diagnosis.
 * Conforms to SambaS3 Ticket C02 and WORKER.md Section 6.
 */
object ProcessExitClassifier {

    private val NATIVE_FATAL_SIGNALS = setOf(4, 5, 6, 7, 8, 11) // SIGILL, SIGTRAP, SIGABRT, SIGBUS, SIGFPE, SIGSEGV

    private val GPU_FAULT_REGEX = Regex(
        "VK_ERROR_DEVICE_LOST|device lost|gpu reset|native renderer fatal|gpu fault|kgsl.*fault|ringbuffer hang",
        RegexOption.IGNORE_CASE
    )

    private val MEMORY_PRESSURE_REGEX = Regex(
        "lmkd|lowmemorykiller|am_low_memory|low_memory|Out of memory|OOM killer|Scudo OOM|std::bad_alloc",
        RegexOption.IGNORE_CASE
    )

    private val DELIBERATE_STOP_REASONS = setOf(
        "DEBUG_STOP_GAME",
        "InGameExit",
        "HomeStop",
        "AppRecoveryCleanup",
        "user-exit",
        "clean",
        "activity finish",
    )

    fun classify(
        exitRecord: ProcessExitRecord?,
        session: EmulationSessionRecord? = null,
        evidenceHint: String = "",
        logs: String = "",
    ): ProcessExitDiagnosis {
        val combinedEvidence = buildCombinedEvidence(exitRecord, session, evidenceHint, logs)

        // 1. Check for Vulkan Device Loss / GPU Reset first (can trigger subsequent native abort or kill)
        if (GPU_FAULT_REGEX.containsMatchIn(combinedEvidence)) {
            val nativeInfo = extractNativeCrashDetails(exitRecord, combinedEvidence)
            return ProcessExitDiagnosis(
                classification = ExitClassification.VULKAN_DEVICE_LOSS,
                pid = exitRecord?.pid ?: session?.pidAtSessionStart,
                processName = exitRecord?.processName,
                isPpuCompileProcess = exitRecord?.isPpuCompile == true,
                signal = nativeInfo.signal ?: exitRecord?.status,
                signalName = nativeInfo.signalName ?: exitRecord?.signalName,
                status = exitRecord?.status,
                pc = nativeInfo.pc,
                backtrace = nativeInfo.backtrace,
                reasonCode = exitRecord?.reason,
                reasonName = exitRecord?.reasonName,
                subReasonCode = exitRecord?.subReason,
                subReasonName = exitRecord?.subReasonName,
                importance = exitRecord?.importance,
                timestamp = exitRecord?.timestamp,
                pssBytes = exitRecord?.pssBytes,
                rssBytes = exitRecord?.rssBytes,
                description = exitRecord?.description,
                summary = "Vulkan Device Loss / GPU Reset",
                rawTrace = exitRecord?.trace,
            )
        }

        // 2. Check for Deliberate Stop
        if (isDeliberateStop(exitRecord, session, evidenceHint)) {
            return ProcessExitDiagnosis(
                classification = ExitClassification.DELIBERATE_STOP,
                pid = exitRecord?.pid ?: session?.pidAtSessionStart,
                processName = exitRecord?.processName,
                isPpuCompileProcess = exitRecord?.isPpuCompile == true,
                signal = if (exitRecord?.reason == ProcessExitRecord.REASON_SIGNALED) exitRecord.status else null,
                signalName = exitRecord?.signalName,
                status = exitRecord?.status,
                reasonCode = exitRecord?.reason,
                reasonName = exitRecord?.reasonName,
                subReasonCode = exitRecord?.subReason,
                subReasonName = exitRecord?.subReasonName,
                importance = exitRecord?.importance,
                timestamp = exitRecord?.timestamp,
                pssBytes = exitRecord?.pssBytes,
                rssBytes = exitRecord?.rssBytes,
                description = exitRecord?.description,
                summary = if (exitRecord?.isPpuCompile == true) "PPU Worker Normal Recycling" else "Deliberate Stop (${session?.stopReason ?: evidenceHint.ifBlank { exitRecord?.reasonName ?: "Clean" }})",
                rawTrace = exitRecord?.trace,
            )
        }

        // 3. Check for Unhandled Java Exception
        val isJavaCrash = exitRecord?.reason == ProcessExitRecord.REASON_CRASH ||
            combinedEvidence.contains("FATAL EXCEPTION:", ignoreCase = true)
        if (isJavaCrash) {
            val stackTrace = extractJavaStackTrace(exitRecord?.trace, combinedEvidence)
            return ProcessExitDiagnosis(
                classification = ExitClassification.JAVA_EXCEPTION,
                pid = exitRecord?.pid ?: session?.pidAtSessionStart,
                processName = exitRecord?.processName,
                isPpuCompileProcess = exitRecord?.isPpuCompile == true,
                status = exitRecord?.status,
                stackTrace = stackTrace,
                reasonCode = exitRecord?.reason,
                reasonName = exitRecord?.reasonName,
                subReasonCode = exitRecord?.subReason,
                subReasonName = exitRecord?.subReasonName,
                importance = exitRecord?.importance,
                timestamp = exitRecord?.timestamp,
                pssBytes = exitRecord?.pssBytes,
                rssBytes = exitRecord?.rssBytes,
                description = exitRecord?.description,
                summary = "Java Exception: " + (stackTrace?.lineSequence()?.firstOrNull() ?: "Unhandled exception"),
                rawTrace = exitRecord?.trace,
            )
        }

        // 4. Check for Native Crash (Signal 4, 5, 6, 7, 8, 11, or tombstone/backtrace)
        val hasNativeFatalSignal = exitRecord?.reason == ProcessExitRecord.REASON_CRASH_NATIVE ||
            (exitRecord?.reason == ProcessExitRecord.REASON_SIGNALED && exitRecord.status in NATIVE_FATAL_SIGNALS)
        val hasTombstoneTrace = combinedEvidence.contains("backtrace:", ignoreCase = true) ||
            combinedEvidence.contains("Fatal signal", ignoreCase = true) ||
            combinedEvidence.contains("*** *** *** ***")

        if (hasNativeFatalSignal || hasTombstoneTrace) {
            val nativeInfo = extractNativeCrashDetails(exitRecord, combinedEvidence)
            val signalName = nativeInfo.signalName ?: exitRecord?.signalName ?: "NATIVE_CRASH"
            return ProcessExitDiagnosis(
                classification = ExitClassification.NATIVE_CRASH,
                pid = exitRecord?.pid ?: session?.pidAtSessionStart,
                processName = exitRecord?.processName,
                isPpuCompileProcess = exitRecord?.isPpuCompile == true,
                signal = nativeInfo.signal ?: (if (exitRecord?.reason == ProcessExitRecord.REASON_SIGNALED) exitRecord.status else null),
                signalName = signalName,
                status = exitRecord?.status,
                pc = nativeInfo.pc,
                backtrace = nativeInfo.backtrace,
                reasonCode = exitRecord?.reason,
                reasonName = exitRecord?.reasonName,
                subReasonCode = exitRecord?.subReason,
                subReasonName = exitRecord?.subReasonName,
                importance = exitRecord?.importance,
                timestamp = exitRecord?.timestamp,
                pssBytes = exitRecord?.pssBytes,
                rssBytes = exitRecord?.rssBytes,
                description = exitRecord?.description,
                summary = "Native Crash ($signalName" + (if (nativeInfo.pc != null) " at pc ${nativeInfo.pc}" else "") + ")",
                rawTrace = exitRecord?.trace,
            )
        }

        // 5. Check for ANR (Application Not Responding)
        val isAnr = exitRecord?.reason == ProcessExitRecord.REASON_ANR ||
            combinedEvidence.contains("Application Not Responding", ignoreCase = true) ||
            combinedEvidence.contains("ANR in com.zenithblue.sambas3", ignoreCase = true)
        if (isAnr) {
            return ProcessExitDiagnosis(
                classification = ExitClassification.ANR,
                pid = exitRecord?.pid ?: session?.pidAtSessionStart,
                processName = exitRecord?.processName,
                isPpuCompileProcess = exitRecord?.isPpuCompile == true,
                status = exitRecord?.status,
                reasonCode = exitRecord?.reason,
                reasonName = exitRecord?.reasonName,
                subReasonCode = exitRecord?.subReason,
                subReasonName = exitRecord?.subReasonName,
                importance = exitRecord?.importance,
                timestamp = exitRecord?.timestamp,
                pssBytes = exitRecord?.pssBytes,
                rssBytes = exitRecord?.rssBytes,
                description = exitRecord?.description,
                summary = "Application Not Responding (ANR)",
                rawTrace = exitRecord?.trace,
            )
        }

        // 6. Check for Memory Pressure Kill (LMKD / low memory)
        val hasLowMemoryReason = exitRecord?.reason == ProcessExitRecord.REASON_LOW_MEMORY ||
            exitRecord?.subReasonName?.contains("LOW_MEMORY", ignoreCase = true) == true
        val hasMemoryPressureEvidence = MEMORY_PRESSURE_REGEX.containsMatchIn(combinedEvidence)

        if (hasLowMemoryReason || (exitRecord?.status == 9 && hasMemoryPressureEvidence)) {
            val record = exitRecord
            return ProcessExitDiagnosis(
                classification = ExitClassification.MEMORY_PRESSURE_KILL,
                pid = record.pid,
                processName = record.processName,
                isPpuCompileProcess = record.isPpuCompile,
                signal = if (record.reason == ProcessExitRecord.REASON_SIGNALED) record.status else null,
                signalName = record.signalName,
                status = record.status,
                reasonCode = record.reason,
                reasonName = record.reasonName,
                subReasonCode = record.subReason,
                subReasonName = record.subReasonName,
                importance = record.importance,
                timestamp = record.timestamp,
                pssBytes = record.pssBytes,
                rssBytes = record.rssBytes,
                description = record.description,
                summary = "Low Memory Killer (LMKD kill / system out of memory)",
                rawTrace = record.trace,
                memoryPressureEvidence = true,
            )
        }

        // 7. Check for System Server Kill (Package updated, permission change, etc.)
        val isSystemServerKill = exitRecord?.reason in setOf(
            ProcessExitRecord.REASON_PACKAGE_UPDATED,
            ProcessExitRecord.REASON_PACKAGE_STATE_CHANGE,
            ProcessExitRecord.REASON_PERMISSION_CHANGE,
            ProcessExitRecord.REASON_INITIALIZATION_FAILURE,
            ProcessExitRecord.REASON_OTHER,
        ) || (exitRecord?.description?.contains("installPackageLI") == true)

        if (isSystemServerKill) {
            return ProcessExitDiagnosis(
                classification = ExitClassification.SYSTEM_SERVER_KILL,
                pid = exitRecord?.pid ?: session?.pidAtSessionStart,
                processName = exitRecord?.processName,
                isPpuCompileProcess = exitRecord?.isPpuCompile == true,
                status = exitRecord?.status,
                reasonCode = exitRecord?.reason,
                reasonName = exitRecord?.reasonName,
                subReasonCode = exitRecord?.subReason,
                subReasonName = exitRecord?.subReasonName,
                importance = exitRecord?.importance,
                timestamp = exitRecord?.timestamp,
                pssBytes = exitRecord?.pssBytes,
                rssBytes = exitRecord?.rssBytes,
                description = exitRecord?.description,
                summary = "System Server Termination (${exitRecord?.reasonName ?: "OS kill"}: ${exitRecord?.description ?: "no description"})",
                rawTrace = exitRecord?.trace,
            )
        }

        // 8. SIGKILL (signal 9) without memory pressure evidence is NOT treated as LMKD!
        if (exitRecord?.reason == ProcessExitRecord.REASON_SIGNALED && exitRecord.status == 9) {
            return ProcessExitDiagnosis(
                classification = ExitClassification.UNKNOWN,
                pid = exitRecord.pid,
                processName = exitRecord.processName,
                isPpuCompileProcess = exitRecord.isPpuCompile,
                signal = 9,
                signalName = "SIGKILL",
                status = 9,
                reasonCode = exitRecord.reason,
                reasonName = exitRecord.reasonName,
                subReasonCode = exitRecord.subReason,
                subReasonName = exitRecord.subReasonName,
                importance = exitRecord.importance,
                timestamp = exitRecord.timestamp,
                pssBytes = exitRecord.pssBytes,
                rssBytes = exitRecord.rssBytes,
                description = exitRecord.description,
                summary = "SIGKILL received without memory pressure evidence (unverified cause)",
                rawTrace = exitRecord.trace,
                memoryPressureEvidence = false,
            )
        }

        // 9. Unknown / Unverified termination
        return ProcessExitDiagnosis(
            classification = ExitClassification.UNKNOWN,
            pid = exitRecord?.pid ?: session?.pidAtSessionStart,
            processName = exitRecord?.processName,
            isPpuCompileProcess = exitRecord?.isPpuCompile == true,
            status = exitRecord?.status,
            reasonCode = exitRecord?.reason,
            reasonName = exitRecord?.reasonName,
            subReasonCode = exitRecord?.subReason,
            subReasonName = exitRecord?.subReasonName,
            importance = exitRecord?.importance,
            timestamp = exitRecord?.timestamp,
            pssBytes = exitRecord?.pssBytes,
            rssBytes = exitRecord?.rssBytes,
            description = exitRecord?.description,
            summary = "Unknown Termination (available evidence cannot distinguish root cause)",
            rawTrace = exitRecord?.trace,
        )
    }

    private fun isDeliberateStop(
        exitRecord: ProcessExitRecord?,
        session: EmulationSessionRecord?,
        evidenceHint: String,
    ): Boolean {
        if (session?.cleanTermination == true || session?.state == EmulationSessionState.CLEAN_STOP) {
            return true
        }
        val stopReason = session?.stopReason ?: evidenceHint
        if (DELIBERATE_STOP_REASONS.any { stopReason.contains(it, ignoreCase = true) }) {
            return true
        }
        if (exitRecord != null) {
            if (exitRecord.reason == ProcessExitRecord.REASON_EXIT_SELF) return true
            if (exitRecord.reason == ProcessExitRecord.REASON_USER_REQUESTED ||
                exitRecord.reason == ProcessExitRecord.REASON_USER_STOPPED
            ) {
                return true
            }
            if (exitRecord.isPpuCompile && (exitRecord.status == 0 || exitRecord.status == 9)) {
                return true
            }
        }
        return false
    }

    private fun buildCombinedEvidence(
        exitRecord: ProcessExitRecord?,
        session: EmulationSessionRecord?,
        evidenceHint: String,
        logs: String,
    ): String {
        val sb = StringBuilder()
        if (evidenceHint.isNotBlank()) sb.append(evidenceHint).append('\n')
        session?.stopReason?.let { sb.append(it).append('\n') }
        exitRecord?.description?.let { sb.append(it).append('\n') }
        exitRecord?.trace?.let { sb.append(it).append('\n') }
        if (logs.isNotBlank()) sb.append(logs).append('\n')
        return sb.toString()
    }

    data class NativeCrashDetails(
        val signal: Int? = null,
        val signalName: String? = null,
        val pc: String? = null,
        val backtrace: List<String> = emptyList(),
    )

    fun extractNativeCrashDetails(exitRecord: ProcessExitRecord?, evidence: String): NativeCrashDetails {
        var sigNum: Int? = if (exitRecord?.reason == ProcessExitRecord.REASON_SIGNALED) exitRecord.status else null
        var sigName: String? = sigNum?.let { ProcessExitRecord.signalNumberToName(it) }

        // Parse signal from text e.g. "Fatal signal 11 (SIGSEGV)" or "signal 6 (SIGABRT)"
        val signalMatch = Regex("(?:Fatal signal|signal)\\s+(\\d+)\\s*\\((SIG\\w+)\\)", RegexOption.IGNORE_CASE)
            .find(evidence)
        if (signalMatch != null) {
            sigNum = signalMatch.groupValues[1].toIntOrNull() ?: sigNum
            sigName = signalMatch.groupValues[2].uppercase()
        } else if (sigName == null) {
            val fallbackMatch = Regex("\\b(SIGSEGV|SIGABRT|SIGBUS|SIGFPE|SIGILL|SIGTRAP)\\b").find(evidence)
            if (fallbackMatch != null) {
                sigName = fallbackMatch.groupValues[1]
                sigNum = ProcessExitRecord.signalNameToNumber(sigName)
            }
        }

        // Extract Program Counter (pc)
        val pcMatch = Regex("\\bpc\\s+([0-9a-fA-F]{6,16})\\b").find(evidence)
        val pc = pcMatch?.groupValues?.get(1)

        // Extract backtrace lines
        val backtrace = ArrayList<String>()
        val frameRegex = Regex("^\\s*#\\d+\\s+pc\\s+[0-9a-fA-F]+.*$")
        evidence.lineSequence().forEach { line ->
            if (frameRegex.matches(line)) {
                backtrace.add(line.trim())
            }
        }

        return NativeCrashDetails(
            signal = sigNum,
            signalName = sigName,
            pc = pc,
            backtrace = backtrace.take(40),
        )
    }

    fun extractJavaStackTrace(trace: String?, logs: String): String? {
        val source = if (!trace.isNullOrBlank()) trace else logs
        if (source.isBlank()) return null

        val lines = source.lines()
        val startIndex = lines.indexOfFirst {
            it.contains("FATAL EXCEPTION:", ignoreCase = true) ||
                it.contains("Exception:", ignoreCase = true) ||
                it.contains("Error:", ignoreCase = true)
        }
        if (startIndex == -1) return null

        val stack = StringBuilder()
        var foundFramesOrException = false
        for (i in startIndex until minOf(lines.size, startIndex + 50)) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (foundFramesOrException) break else continue
            }
            val isHeaderOrFrame = trimmed.startsWith("FATAL EXCEPTION", ignoreCase = true) ||
                trimmed.startsWith("Process:", ignoreCase = true) ||
                trimmed.startsWith("PID:", ignoreCase = true) ||
                trimmed.contains("Exception") ||
                trimmed.contains("Error") ||
                trimmed.startsWith("at ") ||
                trimmed.startsWith("Caused by:") ||
                trimmed.startsWith("...")
            if (isHeaderOrFrame) {
                if (trimmed.startsWith("at ") || trimmed.contains("Exception") || trimmed.contains("Error")) {
                    foundFramesOrException = true
                }
                stack.appendLine(line)
            } else if (foundFramesOrException) {
                break
            }
        }
        return stack.toString().trimEnd().ifBlank { null }
    }
}
