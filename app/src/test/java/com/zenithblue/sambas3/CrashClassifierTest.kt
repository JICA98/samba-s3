package com.zenithblue.sambas3

import com.zenithblue.sambas3.crash.CrashClassification
import com.zenithblue.sambas3.crash.CrashClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashClassifierTest {
    @Test
    fun typedFatalIsConfirmedCrash() = assertEquals(
        CrashClassification.CONFIRMED_CRASH,
        CrashClassifier.classify("Fatal signal 11 SIGSEGV", true, fatalEventId = "fatal-1"),
    )

    @Test
    fun textOnlyFatalIsNotProcessDeath() = assertEquals(
        CrashClassification.UNEXPECTED_TERMINATION,
        CrashClassifier.classify("Fatal signal 11 SIGSEGV", true),
    )

    @Test
    fun unfinishedWithoutFatalEvidenceIsUnexpected() = assertEquals(
        CrashClassification.UNEXPECTED_TERMINATION,
        CrashClassifier.classify("process ended", true),
    )

    @Test
    fun cleanFrontendReasonWinsOverStaleText() = assertEquals(
        CrashClassification.CLEAN_STOP,
        CrashClassifier.classify("SIGABRT in old rotated log", true, cleanStop = true, frontendReason = "InGameExit"),
    )

    @Test
    fun typedFatalWinsOverCleanup() = assertEquals(
        CrashClassification.CONFIRMED_CRASH,
        CrashClassifier.classify("cleanup after crash", true, cleanStop = true, fatalEventId = "fatal-2", frontendReason = "HomeStop"),
    )

    @Test
    fun gpuCauseIsIdentified() = assertEquals("GPU / Vulkan / driver", CrashClassifier.likelyCause("VK_ERROR_DEVICE_LOST"))

    @Test
    fun frameTimeoutCauseIsIdentified() = assertEquals(
        "Frame timeout",
        CrashClassifier.likelyCause("frame-timeout no-produced-frame-for=120001ms"),
    )

    @Test
    fun ordinaryVulkanStartupLineIsNotAConfirmedGpuCrash() = assertEquals(
        "Emulator or application",
        CrashClassifier.likelyCause("Vulkan renderer initialized with driver"),
    )
}
