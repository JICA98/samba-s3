package com.zenithblue.sambas3

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

data class PendingSavestateRecovery(
    val requestId: Long,
    val slot: Int,
    val originalGamePath: String,
    val savestatePath: String,
    val state: String,
    val preSaveMtimeMs: Long,
    val preSaveSizeBytes: Long,
    val requestedAtMs: Long,
    val committedAtMs: Long,
    val retryCount: Int,
    val lastFailure: String = "",
    val titleId: String = ""
)

/** Durable one-shot recovery record for a save committed before a crash. */
object PendingSavestateRecoveryStore {
    private const val PREFS = "savestate_recovery"
    private const val KEY_RECORD = "record"
    private const val REQUESTED = "REQUESTED"
    private const val COMMITTED = "COMMITTED"
    private const val BOOTING = "BOOTING"
    private const val FAILED = "FAILED"
    private const val MAX_RETRIES = 2

    fun request(
        context: Context,
        slot: Int,
        originalGamePath: String,
        preSaveMtimeMs: Long = 0L,
        preSaveSizeBytes: Long = 0L,
        savestatePath: String? = null,
        titleId: String = ""
    ): Long {
        val id = System.currentTimeMillis()
        write(context, JSONObject().apply {
            put("requestId", id)
            put("slot", slot)
            put("originalGamePath", originalGamePath)
            put("savestatePath", savestatePath ?: "")
            put("state", REQUESTED)
            put("preSaveMtimeMs", preSaveMtimeMs)
            put("preSaveSizeBytes", preSaveSizeBytes)
            put("requestedAtMs", id)
            put("committedAtMs", 0L)
            put("retryCount", 0)
            put("titleId", titleId)
        })
        Log.i(TAG, "S3SAVE pending state=REQUESTED requestId=$id slot=$slot")
        return id
    }

    fun commit(context: Context, payload: String): PendingSavestateRecovery? {
        return runCatching {
            val event = JSONObject(payload)
            val old = read(context)
            if (old == null || old.state != REQUESTED) return@runCatching null
            val eventSlot = event.optInt("slot", old.slot)
            if (eventSlot != old.slot) {
                Log.w(TAG, "S3SAVE completion rejected slot=$eventSlot expected=${old.slot}")
                return@runCatching null
            }
            val path = event.optString("path", old.savestatePath)
            // Completion is asynchronous. Reject a stale event for the same
            // slot if it names a different file; only the path captured when
            // this request was accepted may be restored.
            if (old.savestatePath.isNotBlank() &&
                normalizedPath(path) != normalizedPath(old.savestatePath)
            ) {
                Log.w(
                    TAG,
                    "S3SAVE completion rejected path=$path expected=${old.savestatePath}"
                )
                return@runCatching null
            }
            val file = File(path)
            if (path.isBlank() || !file.isFile || file.length() <= 0L) {
                Log.e(TAG, "S3SAVE completion rejected: invalid durable file path=$path")
                return@runCatching null
            }
            val record = JSONObject().apply {
                // Native IDs are process-local; retain the durable Kotlin ID.
                put("requestId", old.requestId)
                put("slot", old.slot)
                put("originalGamePath", old.originalGamePath)
                put("savestatePath", path)
                put("state", COMMITTED)
                put("preSaveMtimeMs", old.preSaveMtimeMs)
                put("preSaveSizeBytes", old.preSaveSizeBytes)
                put("requestedAtMs", old.requestedAtMs)
                put("committedAtMs", System.currentTimeMillis())
                put("retryCount", old.retryCount)
                put("titleId", old.titleId)
            }
            write(context, record)
            read(context)
        }.getOrNull()
    }

    fun read(context: Context): PendingSavestateRecovery? = runCatching {
        val j = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORD, null)?.let(::JSONObject) ?: return null
        PendingSavestateRecovery(
            requestId = j.optLong("requestId"),
            slot = j.optInt("slot"),
            originalGamePath = j.optString("originalGamePath"),
            savestatePath = j.optString("savestatePath"),
            state = j.optString("state"),
            preSaveMtimeMs = j.optLong("preSaveMtimeMs"),
            preSaveSizeBytes = j.optLong("preSaveSizeBytes"),
            requestedAtMs = j.optLong("requestedAtMs"),
            committedAtMs = j.optLong("committedAtMs"),
            retryCount = j.optInt("retryCount"),
            lastFailure = j.optString("lastFailure"),
            titleId = j.optString("titleId")
        )
    }.getOrNull()

    fun validForLaunch(context: Context): PendingSavestateRecovery? {
        val record = read(context) ?: return null
        if (record.state == REQUESTED) {
            // A process can die after the pending file is renamed but before
            // Kotlin receives the completion event. Promote only if this
            // request produced a demonstrably changed, non-empty file.
            val file = File(record.savestatePath)
            val changed = file.isFile && file.length() > 0L && (
                file.lastModified() > record.preSaveMtimeMs ||
                    (record.preSaveSizeBytes == 0L && file.length() > 0L) ||
                    (record.preSaveSizeBytes > 0L && file.length() != record.preSaveSizeBytes)
                )
            if (!changed) return null
            update(context) {
                it.put("state", COMMITTED)
                    .put("committedAtMs", System.currentTimeMillis())
            }
            return read(context)
        }
        if (record.state != COMMITTED && record.state != BOOTING) return null
        if (record.retryCount >= MAX_RETRIES) return null
        val file = File(record.savestatePath)
        if (!file.isFile || file.length() <= 0L) return null
        return record
    }

    /** A bounded recovery failure is surfaced to the launcher while the slot remains intact. */
    fun exhausted(context: Context): PendingSavestateRecovery? {
        val record = read(context) ?: return null
        return record.takeIf {
            it.retryCount >= MAX_RETRIES &&
                (it.state == COMMITTED || it.state == BOOTING || it.state == FAILED) &&
                File(it.savestatePath).isFile && File(it.savestatePath).length() > 0L
        }
    }

    fun markBooting(context: Context): Boolean {
        val record = read(context) ?: return false
        if (record.retryCount >= MAX_RETRIES) {
            Log.e(TAG, "S3SAVE recovery exhausted requestId=${record.requestId}")
            return false
        }
        update(context) { it.put("state", BOOTING).put("retryCount", record.retryCount + 1) }
        return true
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_RECORD).apply()
        Log.i(TAG, "S3SAVE pending recovery cleared")
    }

    fun markFailure(context: Context, reason: String) {
        // A boot/surface failure must remain recoverable until the bounded
        // retry count is exhausted. The slot is intentionally never deleted.
        update(context) {
            val retries = it.optInt("retryCount", 0)
            it.put("lastFailure", reason)
                .put("state", if (retries >= MAX_RETRIES) FAILED else COMMITTED)
        }
        Log.e(TAG, "S3SAVE recovery failure reason=$reason")
    }

    fun markRequestFailure(context: Context, reason: String) {
        update(context) { it.put("lastFailure", reason).put("state", FAILED) }
        Log.e(TAG, "S3SAVE request failure reason=$reason")
    }

    private fun update(context: Context, block: (JSONObject) -> JSONObject) {
        val old = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORD, null)?.let(::JSONObject) ?: return
        write(context, block(old))
    }

    private fun write(context: Context, value: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECORD, value.toString()).commit()
    }

    private fun normalizedPath(path: String): String = runCatching {
        File(path).canonicalFile.path
    }.getOrDefault(File(path).absolutePath)

    private const val TAG = "S3SAVE"
}
