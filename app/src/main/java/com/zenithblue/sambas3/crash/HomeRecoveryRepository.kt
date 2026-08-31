package com.zenithblue.sambas3.crash

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.PendingSavestateRecoveryStore
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.session.EmulationSessionJournal
import com.zenithblue.sambas3.session.EmulationSessionRecord
import com.zenithblue.sambas3.session.EmulationSessionState
import com.zenithblue.sambas3.session.ProcessInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Home owns recovery presentation. This repository only keeps compact,
 * durable decisions; diagnostic logs remain in LogMonitor/crash_reports.
 */
object HomeRecoveryRepository {
    private const val TAG = "S3RECOVERY"
    private const val PREFS = "home_recovery"
    private const val LOAD_FAILURE = "load_failure"
    private const val ACK_SESSION = "ack_session"

    private val mutableState = MutableStateFlow<HomeRecoveryState>(HomeRecoveryState.None)
    val state: StateFlow<HomeRecoveryState> = mutableState

    suspend fun refresh(context: Context) {
        val appContext = context.applicationContext
        val persistedLoadFailure = readLoadFailure(appContext)
        if (persistedLoadFailure != null) {
            mutableState.value = persistedLoadFailure
            return
        }

        val session = withContext(Dispatchers.IO) { EmulationSessionJournal.unfinished(appContext) }
        val terminal = withContext(Dispatchers.IO) { EmulationSessionJournal.terminal(appContext) }
        Log.i(TAG, "refresh session=${session?.sessionId ?: "none"} state=${session?.state ?: "none"} terminal=${terminal?.sessionId ?: "none"}/${terminal?.clean ?: false} process=${session?.processInstanceId ?: "none"}/${ProcessInstance.id}")
        if (terminal?.clean == true && (session == null || terminal.sessionId == session.sessionId)) {
            Log.i(TAG, "classification=None reason=CLEAN_STOP session=${terminal.sessionId}")
            mutableState.value = HomeRecoveryState.None
            return
        }
        if (terminal?.clean == true && session != null && terminal.sessionId != session.sessionId) {
            Log.i(TAG, "ignoring stale CLEAN_STOP session=${terminal.sessionId} activeSession=${session.sessionId}")
        }

        if (session == null || isAcknowledged(appContext, session)) {
            mutableState.value = HomeRecoveryState.None
            return
        }

        val sameProcessLiveSession = session.processInstanceId == ProcessInstance.id &&
            runCatching { RPCSX.getState() }.getOrNull() in
            setOf(EmulatorState.Running, EmulatorState.Paused)
        if (sameProcessLiveSession) {
            Log.i(TAG, "same-process live session retained; no Home recovery card")
            mutableState.value = HomeRecoveryState.None
            return
        }

        val report = withContext(Dispatchers.IO) {
            runCatching { CrashEvidenceCollector.collectSummary(appContext, session) }.getOrNull()
        }
        val decision = HomeRecoveryDecision.decide(
            session.state,
            session.stopReason,
            session.processInstanceId == ProcessInstance.id,
            report?.classification,
        )
        Log.i(TAG, "decision=${decision.name} session=${session.sessionId} report=${report?.classification?.name ?: "none"} cause=${report?.cause ?: "none"}")
        if (decision == RecoveryDecision.NONE) {
            Log.i(TAG, "classification=None reason=EXPECTED_STOP session=${session.sessionId}")
            mutableState.value = HomeRecoveryState.None
            return
        }
        mutableState.value = if (decision == RecoveryDecision.CONFIRMED_CRASH && report != null) {
            HomeRecoveryState.ConfirmedCrash(session, report)
        } else {
            HomeRecoveryState.Interrupted(session, report = report)
        }
    }

    fun recordLoadFailure(
        context: Context,
        gamePath: String,
        savestatePath: String?,
        slot: Int?,
        reason: String,
        report: CrashReport? = null,
    ) {
        val json = JSONObject().apply {
            put("gamePath", gamePath)
            put("savestatePath", savestatePath ?: "")
            put("slot", slot ?: -1)
            put("reason", reason)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(LOAD_FAILURE, json.toString()).apply()
        mutableState.value = HomeRecoveryState.LoadFailure(gamePath, savestatePath, slot, reason, report)
        Log.e(TAG, "load failure persisted game=$gamePath slot=$slot reason=$reason")
    }

    fun recordCrashFailure(context: Context, session: EmulationSessionRecord?, report: CrashReport?) {
        if (session == null) return
        if (report?.classification == CrashClassification.CONFIRMED_CRASH) {
            mutableState.value = HomeRecoveryState.ConfirmedCrash(session, report)
        } else {
            mutableState.value = HomeRecoveryState.Interrupted(session)
        }
    }

    fun markActionRunning(action: RecoveryAction) {
        mutableState.value = HomeRecoveryState.ActionRunning(action)
    }

    fun markActionFailed(context: Context, session: EmulationSessionRecord?, message: String) {
        mutableState.value = HomeRecoveryState.ActionFailed(session, message)
    }

    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        val session = EmulationSessionJournal.read(appContext)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(ACK_SESSION, session?.sessionId ?: "")
            .remove(LOAD_FAILURE)
            .apply()
        PendingSavestateRecoveryStore.clear(appContext)
        // Clearing the marker archives the presentation only; LogMonitor files
        // and any exported crash report directory are intentionally untouched.
        if (session != null) EmulationSessionJournal.clear(appContext)
        mutableState.value = HomeRecoveryState.None
    }

    fun clearAfterSuccessfulRecovery(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(LOAD_FAILURE).apply()
        mutableState.value = HomeRecoveryState.None
    }

    private fun isAcknowledged(context: Context, session: EmulationSessionRecord): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ACK_SESSION, null) == session.sessionId

    private fun readLoadFailure(context: Context): HomeRecoveryState.LoadFailure? = runCatching {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LOAD_FAILURE, null)?.let(::JSONObject) ?: return null
        HomeRecoveryState.LoadFailure(
            gamePath = json.optString("gamePath"),
            savestatePath = json.optString("savestatePath").ifBlank { null },
            slot = json.optInt("slot", -1).takeIf { it >= 0 },
            reason = json.optString("reason", "Unknown load failure"),
        )
    }.getOrNull()
}

enum class RecoveryDecision { NONE, CONFIRMED_CRASH, INTERRUPTED }

/** Pure recovery precedence used by Home and regression tests. */
object HomeRecoveryDecision {
    fun decide(
        state: EmulationSessionState,
        stopReason: String?,
        sameProcess: Boolean,
        classification: CrashClassification?,
    ): RecoveryDecision = when {
        state == EmulationSessionState.STOPPING && stopReason in setOf("InGameExit", "HomeStop") && sameProcess -> RecoveryDecision.NONE
        classification == CrashClassification.CONFIRMED_CRASH -> RecoveryDecision.CONFIRMED_CRASH
        else -> RecoveryDecision.INTERRUPTED
    }
}
