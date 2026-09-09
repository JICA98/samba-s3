package com.zenithblue.sambas3.ppu

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGP 9 does not expose merged resources/manifests to Robolectric 4.11, so
 * these source contracts protect the Android FGS requirements. The runtime
 * service promotion is additionally checked with dumpsys in the device loop.
 */
class PpuBatchWorkerForegroundServiceTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .first { it.isFile }
        .readText()

    @Test
    fun connection_starts_worker_as_foreground_before_binding() {
        val text = source("app/src/main/java/com/zenithblue/sambas3/ppu/PpuBatchWorkerConnection.kt")
        val start = text.indexOf("ContextCompat.startForegroundService(context, intent)")
        val bind = text.indexOf("context.bindService(intent, this, Context.BIND_AUTO_CREATE)")
        assertTrue(start >= 0)
        assertTrue(bind > start)
        assertTrue(text.contains("context.stopService(Intent(context, PpuBatchWorkerService::class.java))"))
    }

    @Test
    fun worker_promotes_with_special_use_and_cleans_foreground() {
        val service = source("app/src/main/java/com/zenithblue/sambas3/ppu/PpuBatchWorkerService.kt")
        assertTrue(service.contains("ServiceCompat.startForeground("))
        assertTrue(service.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
        assertTrue(service.contains("ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)"))

        val manifest = source("app/src/main/AndroidManifest.xml")
        val declaration = manifest.substringAfter("android:name=\".ppu.PpuBatchWorkerService\"").substringBefore("</service>")
        assertTrue(declaration.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(declaration.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
    }
}
