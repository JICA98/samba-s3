package com.zenithblue.sambas3.crash

import com.zenithblue.sambas3.session.EmulationSessionRecord
import com.zenithblue.sambas3.session.EmulationSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitClassifierTest {

    private fun dummySession(stopReason: String? = null, cleanTermination: Boolean = false) = EmulationSessionRecord(
        sessionId = "test-session",
        gamePath = "/game/path",
        titleId = "BCUS98123",
        gameName = "God of War III",
        startedAtMs = 1000L,
        lastHeartbeatMs = 2000L,
        state = if (cleanTermination) EmulationSessionState.CLEAN_STOP else EmulationSessionState.FAILED,
        activityInstanceId = 1L,
        surfaceGeneration = 1L,
        driverLabel = null,
        cleanTermination = cleanTermination,
        pidAtSessionStart = 5454,
        stopReason = stopReason,
    )

    @Test
    fun classifiesNativeCrashAndExtractsPcAndBacktrace() {
        val tombstone = """
            *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
            Build fingerprint: 'OnePlus/CPH2691IN/OP5D3BL1:16'
            pid: 4475, tid: 4475, name: com.zenithblue  >>> com.zenithblue.sambas3 <<<
            signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xdeadbeef
            Abort message: 'assertion failed in pputhread.cpp:123'
                r0  0000000000000000  r1  000000000000117b
                pc  0000007e0c4b2b2c  /apex/com.android.runtime/lib64/bionic/libc.so (abort+168)
            backtrace:
                  #00 pc 000000000004fb2c  /apex/com.android.runtime/lib64/bionic/libc.so (abort+168)
                  #01 pc 00000000005a3b20  /data/app/~~lib/arm64/librpcsx-android.so (PPUThread::step+44)
                  #02 pc 00000000005a4110  /data/app/~~lib/arm64/librpcsx-android.so
        """.trimIndent()

        val record = ProcessExitRecord(
            pid = 4475,
            processName = "com.zenithblue.sambas3",
            reason = ProcessExitRecord.REASON_CRASH_NATIVE,
            reasonName = "CRASH_NATIVE",
            status = 11,
            trace = tombstone,
        )

        val diagnosis = ProcessExitClassifier.classify(record)
        assertEquals(ExitClassification.NATIVE_CRASH, diagnosis.classification)
        assertEquals(11, diagnosis.signal)
        assertEquals("SIGSEGV", diagnosis.signalName)
        assertEquals("0000007e0c4b2b2c", diagnosis.pc)
        assertEquals(3, diagnosis.backtrace.size)
        assertTrue(diagnosis.backtrace[0].contains("#00 pc"))
        assertTrue(diagnosis.backtrace[1].contains("librpcsx-android.so"))
    }

    @Test
    fun classifiesJavaExceptionAndExtractsStackTrace() {
        val javaTrace = """
            FATAL EXCEPTION: main
            Process: com.zenithblue.sambas3, PID: 1234
            java.lang.NullPointerException: Attempt to invoke virtual method on a null object reference
            	at com.zenithblue.sambas3.RPCSXActivity.onCreate(RPCSXActivity.kt:123)
            	at android.app.Activity.performCreate(Activity.java:8051)
            	at android.app.Instrumentation.callActivityOnCreate(Instrumentation.java:1330)
        """.trimIndent()

        val record = ProcessExitRecord(
            pid = 1234,
            processName = "com.zenithblue.sambas3",
            reason = ProcessExitRecord.REASON_CRASH,
            reasonName = "CRASH",
            trace = javaTrace,
        )

        val diagnosis = ProcessExitClassifier.classify(record)
        val stackTrace = diagnosis.stackTrace
        assertNotNull(stackTrace)
        assertTrue(stackTrace!!.contains("java.lang.NullPointerException"))
        assertTrue(stackTrace.contains("RPCSXActivity.onCreate"))
    }

    @Test
    fun classifiesDirectLowMemoryAsMemoryPressureKill() {
        val record = ProcessExitRecord(
            pid = 4189,
            processName = "com.zenithblue.sambas3",
            reason = ProcessExitRecord.REASON_LOW_MEMORY,
            reasonName = "LOW_MEMORY",
            rssBytes = 2500L * 1024 * 1024,
        )

        val diagnosis = ProcessExitClassifier.classify(record)
        assertEquals(ExitClassification.MEMORY_PRESSURE_KILL, diagnosis.classification)
        assertTrue(diagnosis.memoryPressureEvidence)
    }

    @Test
    fun classifiesSigkillWithLmkdEvidenceAsMemoryPressureKill() {
        val record = ProcessExitRecord(
            pid = 32654,
            processName = "com.zenithblue.sambas3",
            reason = ProcessExitRecord.REASON_SIGNALED,
            reasonName = "SIGNALED",
            status = 9,
            rssBytes = 3200L * 1024 * 1024,
        )
        val logs = "09-24 23:23:09.001 800 800 I lmkd: kill 'com.zenithblue.sambas3' (32654) to free 3355443kB"

        val diagnosis = ProcessExitClassifier.classify(record, logs = logs)
        assertEquals(ExitClassification.MEMORY_PRESSURE_KILL, diagnosis.classification)
        assertTrue(diagnosis.memoryPressureEvidence)
    }

    @Test
    fun doesNotTreatSigkillWithoutMemoryPressureAsLmkd() {
        // Plain SIGKILL without any memory pressure evidence in logs or exit description
        val record = ProcessExitRecord(
            pid = 5454,
            processName = "com.zenithblue.sambas3",
            reason = ProcessExitRecord.REASON_SIGNALED,
            reasonName = "SIGNALED",
            status = 9,
        )

        val diagnosis = ProcessExitClassifier.classify(record)
        assertEquals(
            "SIGKILL without memory pressure must NOT be classified as MEMORY_PRESSURE_KILL",
            ExitClassification.UNKNOWN,
            diagnosis.classification,
        )
        assertFalse(diagnosis.memoryPressureEvidence)
    }

    @Test
    fun classifiesAnr() {
        val record = ProcessExitRecord(
            pid = 7890,
            processName = "com.zenithblue.sambas3",
            reason = ProcessExitRecord.REASON_ANR,
            reasonName = "ANR",
        )

        val diagnosis = ProcessExitClassifier.classify(record)
        assertEquals(ExitClassification.ANR, diagnosis.classification)
    }

    @Test
    fun classifiesDeliberateStop() {
        // 1. Clean stop via DEBUG_STOP_GAME
        val session1 = dummySession(stopReason = "DEBUG_STOP_GAME")
        val d1 = ProcessExitClassifier.classify(null, session = session1)
        assertEquals(ExitClassification.DELIBERATE_STOP, d1.classification)

        // 2. User requested remove task
        val recordUserReq = ProcessExitRecord(
            pid = 6927,
            processName = "com.zenithblue.sambas3",
            reason = ProcessExitRecord.REASON_USER_REQUESTED,
            reasonName = "USER_REQUESTED",
            subReason = 22,
            subReasonName = "REMOVE_TASK",
        )
        val d2 = ProcessExitClassifier.classify(recordUserReq)
        assertEquals(ExitClassification.DELIBERATE_STOP, d2.classification)

        // 3. Normal PPU compile worker recycling
        val recordPpuRecycle = ProcessExitRecord(
            pid = 5477,
            processName = "com.zenithblue.sambas3:ppu_compile",
            reason = ProcessExitRecord.REASON_SIGNALED,
            reasonName = "SIGNALED",
            status = 9,
        )
        val d3 = ProcessExitClassifier.classify(recordPpuRecycle)
        assertEquals(ExitClassification.DELIBERATE_STOP, d3.classification)
        assertTrue(d3.isPpuCompileProcess)
    }

    @Test
    fun classifiesVulkanDeviceLossOverridingNativeSignal() {
        // Vulkan device loss often triggers subsequent abort or kill
        val record = ProcessExitRecord(
            pid = 4475,
            processName = "com.zenithblue.sambas3",
            reason = ProcessExitRecord.REASON_SIGNALED,
            reasonName = "SIGNALED",
            status = 6,
        )
        val evidence = "E/RPCSX: VK_ERROR_DEVICE_LOST: Device lost during queue submit"

        val diagnosis = ProcessExitClassifier.classify(record, evidenceHint = evidence)
        assertEquals(ExitClassification.VULKAN_DEVICE_LOSS, diagnosis.classification)
    }

    @Test
    fun classifiesSystemServerKillDueToPackageUpdate() {
        val record = ProcessExitRecord(
            pid = 10950,
            processName = "com.zenithblue.sambas3",
            reason = ProcessExitRecord.REASON_PACKAGE_UPDATED,
            reasonName = "PACKAGE_UPDATED",
            description = "stop com.zenithblue.sambas3 due to installPackageLI",
        )

        val diagnosis = ProcessExitClassifier.classify(record)
        assertEquals(ExitClassification.SYSTEM_SERVER_KILL, diagnosis.classification)
    }

    @Test
    fun classifiesInconclusiveEvidenceAsUnknown() {
        val diagnosis = ProcessExitClassifier.classify(null, evidenceHint = "unknown termination")
        assertEquals(ExitClassification.UNKNOWN, diagnosis.classification)
    }
}
