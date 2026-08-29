package com.zenithblue.sambas3.session

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.UUID

enum class EmulationSessionState { STARTING, BOOTING, RUNNING, SAVING, LOADING, STOPPING, CLEAN_STOP, FAILED }

data class EmulationSessionRecord(
    val sessionId: String,
    val gamePath: String,
    val titleId: String?,
    val gameName: String?,
    val startedAtMs: Long,
    val lastHeartbeatMs: Long,
    val state: EmulationSessionState,
    val activityInstanceId: Long,
    val surfaceGeneration: Long,
    val driverLabel: String?,
    val cleanTermination: Boolean
)

/** Small durable marker. It is deliberately independent of the emulator core. */
object EmulationSessionJournal {
    private const val TAG = "S3SESSION"
    private const val PREFS = "emulation_session_journal"
    private const val ACTIVE = "active"

    @Volatile private var cached: EmulationSessionRecord? = null

    fun begin(context: Context, gamePath: String, titleId: String?, gameName: String?, activityInstanceId: Long, surfaceGeneration: Long): EmulationSessionRecord {
        val now = System.currentTimeMillis()
        return EmulationSessionRecord(
            sessionId = "${now}-${UUID.randomUUID().toString().take(8)}",
            gamePath = gamePath,
            titleId = titleId,
            gameName = gameName,
            startedAtMs = now,
            lastHeartbeatMs = now,
            state = EmulationSessionState.STARTING,
            activityInstanceId = activityInstanceId,
            surfaceGeneration = surfaceGeneration,
            driverLabel = null,
            cleanTermination = false
        ).also { write(context, it) }
    }

    fun update(context: Context, state: EmulationSessionState, surfaceGeneration: Long? = null, cleanTermination: Boolean? = null) {
        val current = read(context) ?: cached ?: return
        write(context, current.copy(
            state = state,
            lastHeartbeatMs = System.currentTimeMillis(),
            surfaceGeneration = surfaceGeneration ?: current.surfaceGeneration,
            cleanTermination = cleanTermination ?: current.cleanTermination
        ))
    }

    fun heartbeat(context: Context, state: EmulationSessionState, surfaceGeneration: Long) =
        update(context, state, surfaceGeneration)

    fun markCleanStop(context: Context) {
        val current = read(context) ?: return
        write(context, current.copy(state = EmulationSessionState.CLEAN_STOP, lastHeartbeatMs = System.currentTimeMillis(), cleanTermination = true))
        clear(context)
    }

    fun read(context: Context): EmulationSessionRecord? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, null) ?: return cached
        return runCatching {
            JSONObject(json).let { j ->
                EmulationSessionRecord(
                    sessionId = j.optString("sessionId"),
                    gamePath = j.optString("gamePath"),
                    titleId = j.optString("titleId").ifBlank { null },
                    gameName = j.optString("gameName").ifBlank { null },
                    startedAtMs = j.optLong("startedAtMs"),
                    lastHeartbeatMs = j.optLong("lastHeartbeatMs"),
                    state = runCatching { EmulationSessionState.valueOf(j.optString("state")) }.getOrDefault(EmulationSessionState.FAILED),
                    activityInstanceId = j.optLong("activityInstanceId"),
                    surfaceGeneration = j.optLong("surfaceGeneration"),
                    driverLabel = j.optString("driverLabel").ifBlank { null },
                    cleanTermination = j.optBoolean("cleanTermination")
                )
            }
        }.onSuccess { cached = it }.getOrNull()
    }

    fun unfinished(context: Context): EmulationSessionRecord? = read(context)?.takeIf { !it.cleanTermination && it.state != EmulationSessionState.CLEAN_STOP }

    fun clear(context: Context) {
        cached = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ACTIVE).apply()
    }

    private fun write(context: Context, record: EmulationSessionRecord) {
        cached = record
        val j = JSONObject().apply {
            put("sessionId", record.sessionId)
            put("gamePath", record.gamePath)
            put("titleId", record.titleId ?: "")
            put("gameName", record.gameName ?: "")
            put("startedAtMs", record.startedAtMs)
            put("lastHeartbeatMs", record.lastHeartbeatMs)
            put("state", record.state.name)
            put("activityInstanceId", record.activityInstanceId)
            put("surfaceGeneration", record.surfaceGeneration)
            put("driverLabel", record.driverLabel ?: "")
            put("cleanTermination", record.cleanTermination)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ACTIVE, j.toString()).apply()
        Log.i(TAG, "journal state=${record.state} session=${record.sessionId} game=${record.gamePath} clean=${record.cleanTermination}")
    }
}
