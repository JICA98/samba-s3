package com.zenithblue.sambas3

import com.zenithblue.sambas3.crash.CrashClassification
import com.zenithblue.sambas3.crash.CrashClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashClassifierTest {
    @Test fun fatalEvidenceIsConfirmedCrash() = assertEquals(CrashClassification.CONFIRMED_CRASH, CrashClassifier.classify("Fatal signal 11 SIGSEGV", true))
    @Test fun unfinishedWithoutFatalEvidenceIsUnexpected() = assertEquals(CrashClassification.UNEXPECTED_TERMINATION, CrashClassifier.classify("process ended", true))
    @Test fun normalStopWinsOverEvidence() = assertEquals(CrashClassification.CLEAN_STOP, CrashClassifier.classify("SIGABRT in old rotated log", true, cleanStop = true))
    @Test fun gpuCauseIsIdentified() = assertEquals("GPU / Vulkan / driver", CrashClassifier.likelyCause("VK_ERROR_DEVICE_LOST"))
}
