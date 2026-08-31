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
    val cleanTermination: Boolean,
    val processInstanceId: String = ProcessInstance.id,
    val processStartedElapsedRealtimeMs: Long = ProcessInstance.startedElapsedRealtimeMs,
    val pidAtSessionStart: Int = ProcessInstance.pid,
    val stopRequestId: Long? = null,
    val stopReason: String? = null,
    val finishReason: String? = null,
    val fatalEventId: String? = null,
    val failureAtMs: Long? = null,
    val stoppedAtMs: Long? = null,
)

data class EmulationTerminalRecord(
    val sessionId: String,
    val state: EmulationSessionState,
    val stopRequestId: Long?,
    val stopReason: String?,
    val finishReason: String?,
    val finishedAtMs: Long,
    val clean: Boolean,
    val fatalEventId: String?,
)

/** Small durable marker. It is deliberately independent of the emulator core. */
object EmulationSessionJournal {
    private const val TAG = "S3SESSION"
    private const val PREFS = "emulation_session_journal"
    private const val ACTIVE = "active"
    private const val TERMINAL = "terminal"

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
            cleanTermination = false,
            processInstanceId = ProcessInstance.id,
            processStartedElapsedRealtimeMs = ProcessInstance.startedElapsedRealtimeMs,
            pidAtSessionStart = ProcessInstance.pid
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

    fun markStopping(context: Context, requestId: Long, reason: String, finishReason: String? = null) {
        val current = read(context) ?: return
        val now = System.currentTimeMillis()
        write(context, current.copy(
            state = EmulationSessionState.STOPPING,
            lastHeartbeatMs = now,
            stopRequestId = requestId,
            stopReason = reason,
            finishReason = finishReason,
            cleanTermination = false,
        ))
        Log.i("S3EXIT", "requested session=${current.sessionId} stopRequestId=$requestId stopReason=$reason finishReason=${finishReason ?: "unknown"} journal=STOPPING timestamp=$now")
    }

    fun markFailure(context: Context, fatalEventId: String) {
        val current = read(context) ?: return
        val now = System.currentTimeMillis()
        write(context, current.copy(
            state = EmulationSessionState.FAILED,
            lastHeartbeatMs = now,
            fatalEventId = fatalEventId,
            failureAtMs = now,
            cleanTermination = false,
        ))
        Log.i("S3EXIT", "event=fatal sessionId=${current.sessionId} activityInstanceId=${current.activityInstanceId} fatalEventId=$fatalEventId timestamp=$now")
    }

    /**
     * Terminalize synchronously: the terminal record is committed before the
     * active marker is removed. Home can therefore distinguish a clean exit
     * even if the Activity is destroyed between those two writes.
     */
    fun markCleanStop(context: Context, requestId: Long? = null, reason: String? = null, finishReason: String? = null) {
        val current = read(context) ?: return
        val now = System.currentTimeMillis()
        val terminal = EmulationTerminalRecord(
            sessionId = current.sessionId,
            state = EmulationSessionState.CLEAN_STOP,
            stopRequestId = requestId ?: current.stopRequestId,
            stopReason = reason ?: current.stopReason,
            finishReason = finishReason ?: current.finishReason,
            finishedAtMs = now,
            clean = true,
            fatalEventId = current.fatalEventId,
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(TERMINAL, terminalJson(terminal)).commit()
        prefs.edit().remove(ACTIVE).commit()
        cached = null
        Log.i("S3EXIT", "journal=CLEAN_STOP session=${terminal.sessionId} stopRequestId=${terminal.stopRequestId ?: 0L} stopReason=${terminal.stopReason ?: "unknown"} finishReason=${terminal.finishReason ?: "unknown"} timestamp=$now")
    }

    /** Preserve an unfinished marker for Home recovery after crash/load cleanup. */
    fun markFailedStop(context: Context, requestId: Long? = null, reason: String? = null, fatalEventId: String? = null) {
        val current = read(context) ?: return
        val now = System.currentTimeMillis()
        val failed = current.copy(
            state = EmulationSessionState.FAILED,
            lastHeartbeatMs = now,
            stopRequestId = requestId ?: current.stopRequestId,
            stopReason = reason ?: current.stopReason,
            fatalEventId = fatalEventId ?: current.fatalEventId,
            failureAtMs = current.failureAtMs ?: now,
            stoppedAtMs = now,
            cleanTermination = false,
        )
        write(context, failed)
        val terminal = EmulationTerminalRecord(failed.sessionId, EmulationSessionState.FAILED, failed.stopRequestId, failed.stopReason, failed.finishReason, now, false, failed.fatalEventId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TERMINAL, terminalJson(terminal)).commit()
        Log.i("S3EXIT", "journal=FAILED session=${failed.sessionId} stopRequestId=${failed.stopRequestId ?: 0L} stopReason=${failed.stopReason ?: "unknown"} fatalEventId=${failed.fatalEventId ?: "none"} timestamp=$now")
    }

    fun read(context: Context): EmulationSessionRecord? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, null)
        if (json == null) {
            // The durable marker is authoritative. Never resurrect an old
            // process-local record after the marker has been cleared.
            cached = null
            return null
        }
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
                    cleanTermination = j.optBoolean("cleanTermination"),
                    processInstanceId = j.optString("processInstanceId").ifBlank { "legacy" },
                    processStartedElapsedRealtimeMs = j.optLong("processStartedElapsedRealtimeMs"),
                    pidAtSessionStart = j.optInt("pidAtSessionStart", -1),
                    stopRequestId = j.optLong("stopRequestId", 0L).takeIf { it != 0L },
                    stopReason = j.optString("stopReason").ifBlank { null },
                    finishReason = j.optString("finishReason").ifBlank { null },
                    fatalEventId = j.optString("fatalEventId").ifBlank { null },
                    failureAtMs = j.optLong("failureAtMs", 0L).takeIf { it != 0L },
                    stoppedAtMs = j.optLong("stoppedAtMs", 0L).takeIf { it != 0L },
                )
            }
        }.onSuccess { cached = it }.getOrNull()
    }

    fun terminal(context: Context): EmulationTerminalRecord? = runCatching {
        val j = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TERMINAL, null)?.let(::JSONObject) ?: return null
        EmulationTerminalRecord(
            sessionId = j.optString("sessionId"),
            state = runCatching { EmulationSessionState.valueOf(j.optString("state")) }.getOrDefault(EmulationSessionState.FAILED),
            stopRequestId = j.optLong("stopRequestId", 0L).takeIf { it != 0L },
            stopReason = j.optString("stopReason").ifBlank { null },
            finishReason = j.optString("finishReason").ifBlank { null },
            finishedAtMs = j.optLong("finishedAtMs"),
            clean = j.optBoolean("clean"),
            fatalEventId = j.optString("fatalEventId").ifBlank { null },
        )
    }.getOrNull()

    fun unfinished(context: Context): EmulationSessionRecord? = read(context)?.takeIf {
        !it.cleanTermination && it.state != EmulationSessionState.CLEAN_STOP &&
            terminal(context)?.let { terminal -> terminal.sessionId == it.sessionId && terminal.clean } != true
    }

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
            put("processInstanceId", record.processInstanceId)
            put("processStartedElapsedRealtimeMs", record.processStartedElapsedRealtimeMs)
            put("pidAtSessionStart", record.pidAtSessionStart)
            put("stopRequestId", record.stopRequestId ?: 0L)
            put("stopReason", record.stopReason ?: "")
            put("finishReason", record.finishReason ?: "")
            put("fatalEventId", record.fatalEventId ?: "")
            put("failureAtMs", record.failureAtMs ?: 0L)
            put("stoppedAtMs", record.stoppedAtMs ?: 0L)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ACTIVE, j.toString()).apply()
        Log.i(TAG, "journal state=${record.state} session=${record.sessionId} game=${record.gamePath} clean=${record.cleanTermination}")
    }

    private fun terminalJson(record: EmulationTerminalRecord): String = JSONObject().apply {
        put("sessionId", record.sessionId)
        put("state", record.state.name)
        put("stopRequestId", record.stopRequestId ?: 0L)
        put("stopReason", record.stopReason ?: "")
        put("finishReason", record.finishReason ?: "")
        put("finishedAtMs", record.finishedAtMs)
        put("clean", record.clean)
        put("fatalEventId", record.fatalEventId ?: "")
    }.toString()
}
