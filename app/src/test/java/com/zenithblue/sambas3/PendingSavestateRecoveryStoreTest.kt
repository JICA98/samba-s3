package com.zenithblue.sambas3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PendingSavestateRecoveryStoreTest {
    private lateinit var context: Context
    private lateinit var stateFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PendingSavestateRecoveryStore.clear(context)
        stateFile = File(context.cacheDir, "recovery-${System.nanoTime()}.SAVESTAT.zst")
    }

    @After
    fun tearDown() {
        PendingSavestateRecoveryStore.clear(context)
        stateFile.delete()
    }

    @Test
    fun requested_file_promotes_to_committed_and_preserves_exact_slot() {
        val requestId = PendingSavestateRecoveryStore.request(
            context, 4, "/games/BLUS31584", 0L, 0L, stateFile.path, "BLUS31584"
        )
        stateFile.writeBytes(ByteArray(8) { 7 })

        val record = PendingSavestateRecoveryStore.validForLaunch(context)
        assertNotNull(record)
        assertEquals(requestId, record!!.requestId)
        assertEquals(4, record.slot)
        assertEquals(stateFile.path, record.savestatePath)
        assertEquals("BLUS31584", record.titleId)
        assertEquals("COMMITTED", record.state)
    }

    @Test
    fun requested_unchanged_file_is_not_promoted() {
        stateFile.writeBytes(ByteArray(8) { 3 })
        val requestId = PendingSavestateRecoveryStore.request(
            context,
            0,
            "/games/BLUS31584",
            stateFile.lastModified(),
            stateFile.length(),
            stateFile.path,
            "BLUS31584"
        )

        assertTrue(requestId > 0L)
        assertNull(PendingSavestateRecoveryStore.validForLaunch(context))
        assertEquals("REQUESTED", PendingSavestateRecoveryStore.read(context)?.state)
    }

    @Test
    fun commit_rejects_wrong_slot_and_accepts_durable_event() {
        val requestId = PendingSavestateRecoveryStore.request(
            context, 2, "/games/original", 0L, 0L, stateFile.path
        )
        stateFile.writeBytes(ByteArray(4) { 1 })
        assertNull(PendingSavestateRecoveryStore.commit(
            context,
            JSONObject().put("slot", 3).put("path", stateFile.path).toString()
        ))
        val record = PendingSavestateRecoveryStore.commit(
            context,
            JSONObject().put("slot", 2).put("path", stateFile.path).toString()
        )
        assertNotNull(record)
        assertEquals(requestId, record!!.requestId)
        assertEquals("COMMITTED", record.state)
    }

    @Test
    fun boot_retries_are_bounded_without_deleting_slot() {
        PendingSavestateRecoveryStore.request(context, 1, "/games/original", 0L, 0L, stateFile.path)
        stateFile.writeBytes(ByteArray(4) { 2 })
        assertTrue(PendingSavestateRecoveryStore.markBooting(context))
        PendingSavestateRecoveryStore.markFailure(context, "renderer")
        assertTrue(PendingSavestateRecoveryStore.markBooting(context))
        PendingSavestateRecoveryStore.markFailure(context, "renderer-again")
        assertNull(PendingSavestateRecoveryStore.validForLaunch(context))
        assertTrue(stateFile.isFile)
    }
}
