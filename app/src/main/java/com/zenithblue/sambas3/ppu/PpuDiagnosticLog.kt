package com.zenithblue.sambas3.ppu

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/** Structured PPU events. Schema 2. Never logs decrypted game code, keys, or whole personal paths. */
object PpuDiagnosticLog {
    const val TAG = "S3PPUEVENT"
    private const val SCHEMA = 2

    @Volatile
    private var sequence: Long = 0L

    fun emit(
        event: String,
        titleId: String? = null,
        sessionId: String? = null,
        attemptId: String? = null,
        phase: String? = null,
        batchIndex: Int? = null,
        workerInstanceId: String? = null,
        pid: Int? = null,
        manifestId: String? = null,
        objectKey: String? = null,
        inventorySealed: Boolean? = null,
        requiredObjects: Long? = null,
        validatedObjects: Long? = null,
        newlyCommittedObjects: Long? = null,
        reason: String? = null,
        extras: Map<String, Any?> = emptyMap(),
    ) {
        val seq = synchronized(this) { ++sequence }
        val json = JSONObject()
        json.put("schema", SCHEMA)
        json.put("event", event)
        json.put("sequence", seq)
        json.put("monotonicMs", SystemClock.elapsedRealtime())
        titleId?.let { json.put("titleId", it) }
        sessionId?.let { json.put("sessionId", it) }
        attemptId?.let { json.put("attemptId", it) }
        phase?.let { json.put("phase", it) }
        batchIndex?.let { json.put("batchIndex", it) }
        workerInstanceId?.let { json.put("workerInstanceId", it) }
        pid?.let { json.put("pid", it) }
        manifestId?.let { json.put("manifestId", it) }
        objectKey?.let { json.put("objectKey", it) }
        inventorySealed?.let { json.put("inventorySealed", it) }
        requiredObjects?.let { json.put("requiredObjects", it) }
        validatedObjects?.let { json.put("validatedObjects", it) }
        newlyCommittedObjects?.let { json.put("newlyCommittedObjects", it) }
        reason?.let { json.put("reason", it) }
        extras.forEach { (k, v) ->
            if (v != null) json.put(k, v)
        }
        Log.i(TAG, json.toString())
    }
}
