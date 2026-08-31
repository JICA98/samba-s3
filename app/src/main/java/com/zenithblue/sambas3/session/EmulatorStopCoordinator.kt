package com.zenithblue.sambas3.session

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.PendingSavestateRecoveryStore
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

enum class EmulatorStopReason {
    HomeStop,
    InGameExit,
    CrashExit,
    LoadFailureCleanup,
    BootFailureCleanup,
    AppRecoveryCleanup,
}

sealed interface EmulatorStopState {
    data object Idle : EmulatorStopState

    data class Stopping(
        val requestId: Long,
        val reason: EmulatorStopReason,
        val gamePath: String?,
        val startedAtMs: Long,
        val nativeState: EmulatorState,
    ) : EmulatorStopState

    data class Completed(val requestId: Long) : EmulatorStopState

    data class Failed(
        val requestId: Long,
        val reason: EmulatorStopReason,
        val nativeState: EmulatorState?,
        val message: String,
    ) : EmulatorStopState
}

/**
 * Process-wide owner for terminal emulator shutdown. All callers share one
 * in-flight request and only the coordinator may publish terminal Stopped.
 */
object EmulatorStopCoordinator {
    private const val TAG = "S3STOP"
    private const val ACTIVE_TIMEOUT_MS = 15_000L
    private const val PASSIVE_TIMEOUT_MS = 45_000L
    private const val ACTIVE_POLL_MS = 100L
    private const val PASSIVE_POLL_MS = 500L

    private data class InFlight(val requestId: Long, val result: CompletableDeferred<Boolean>)

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextRequestId = AtomicLong(0L)
    private val lock = Any()
    /** Serializes terminal native operations, including pre-boot cleanup. */
    private val terminalMutex = Mutex()
    private var inFlight: InFlight? = null
    private val mutableState = MutableStateFlow<EmulatorStopState>(EmulatorStopState.Idle)
    val state: StateFlow<EmulatorStopState> = mutableState.asStateFlow()

    suspend fun stop(context: Context, reason: EmulatorStopReason): Boolean {
        val appContext = context.applicationContext
        return request(
            reason = reason,
            context = appContext,
            readState = { RPCSX.getState() },
            kill = { RPCSX.instance.kill() },
            host = EmulationHostRegistry.current(),
            cancelRecovery = { PendingSavestateRecoveryStore.cancelAutomaticRecovery(appContext, reason.name) },
            finalize = { requestId, host -> finalizeStopped(appContext, requestId, host, reason) },
        )
    }

    /** Shared strict native stop primitive for pre-boot cleanup paths. */
    suspend fun ensureNativeStopped(
        reason: EmulatorStopReason,
        readState: () -> EmulatorState,
        kill: () -> Unit,
        timeoutMs: Long = ACTIVE_TIMEOUT_MS,
        pollMs: Long = ACTIVE_POLL_MS,
        onLog: (String) -> Unit = {},
    ): StopResult = withContext(Dispatchers.IO) {
        terminalMutex.withLock {
            val before = runCatching { readState() }.getOrElse {
                onLog("reason=$reason state-read-failed=${it.message}")
                return@withContext StopResult.Failed
            }
            onLog("reason=$reason preflight state=$before")
            if (before == EmulatorState.Stopped) return@withContext StopResult.AlreadyStopped
            if (before != EmulatorState.Stopping) {
                runCatching { kill() }.onFailure {
                    onLog("reason=$reason kill-failed=${it.message}")
                    return@withContext StopResult.Failed
                }
            }
            val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
            while (System.nanoTime() < deadline) {
                val current = runCatching { readState() }.getOrElse {
                    onLog("reason=$reason state-read-failed=${it.message}")
                    return@withContext StopResult.Failed
                }
                if (current == EmulatorState.Stopped) {
                    onLog("reason=$reason stopped")
                    return@withContext StopResult.Stopped
                }
                delay(pollMs.coerceAtLeast(1L))
            }
            val finalState = runCatching { readState() }.getOrElse {
                onLog("reason=$reason state-read-failed=${it.message}")
                return@withContext StopResult.Failed
            }
            onLog("reason=$reason timeout finalState=$finalState")
            StopResult.TimedOut
        }
    }

    private suspend fun request(
        reason: EmulatorStopReason,
        context: Context?,
        readState: () -> EmulatorState,
        kill: () -> Unit,
        host: EmulationHost?,
        cancelRecovery: () -> Unit,
        finalize: suspend (Long, EmulationHost?) -> Boolean,
        activeTimeoutMs: Long = ACTIVE_TIMEOUT_MS,
        passiveTimeoutMs: Long = PASSIVE_TIMEOUT_MS,
        pollMs: Long = ACTIVE_POLL_MS,
    ): Boolean {
        val request: InFlight
        val owner: Boolean
        synchronized(lock) {
            val existing = inFlight
            if (existing != null) {
                request = existing
                owner = false
            } else {
                request = InFlight(nextRequestId.incrementAndGet(), CompletableDeferred())
                inFlight = request
                owner = true
            }
        }
        if (!owner) {
            Log.i(TAG, "join id=${request.requestId} reason=$reason")
            return request.result.await()
        }

        Log.i(TAG, "request id=${request.requestId} reason=$reason process=${ProcessInstance.id} activity=${host?.activityInstanceId} activeGame=${RPCSX.activeGame.value} nativeState=reading surface=${host?.currentSurfaceGeneration} journal=${context?.let { EmulationSessionJournal.read(it)?.state }} recovery=${context?.let { PendingSavestateRecoveryStore.read(it)?.state }}")
        processScope.launch {
            val success = terminalMutex.withLock {
                runCatching {
                    cancelRecovery()
                    Log.i(TAG, "id=${request.requestId} recovery-cancelled")
                    val before = readStateStrict(readState, request.requestId, reason) ?: return@runCatching false
                    mutableState.value = EmulatorStopState.Stopping(
                        request.requestId,
                        reason,
                        RPCSX.activeGame.value,
                        System.currentTimeMillis(),
                        before,
                    )
                    if (before == EmulatorState.Stopped) {
                        return@runCatching finalize(request.requestId, host)
                    }
                    if (host != null) withContext(Dispatchers.Main.immediate) {
                        host.let {
                            Log.i(TAG, "id=${request.requestId} host-prepare activity=${it.activityInstanceId} surface=${it.currentSurfaceGeneration}")
                            it.prepareForExternalStop(reason)
                        }
                    }
                    if (before != EmulatorState.Stopping) {
                        kill()
                        Log.i(TAG, "id=${request.requestId} kill-requested native=$before")
                    } else {
                        Log.i(TAG, "id=${request.requestId} kill-skipped native=Stopping")
                    }
                    val stopped = waitForStopped(readState, request.requestId, reason, activeTimeoutMs, pollMs)
                        ?: return@runCatching false
                    if (!stopped) {
                        Log.w(TAG, "id=${request.requestId} active-timeout passive-reconcile")
                        if (waitForStopped(readState, request.requestId, reason, passiveTimeoutMs, PASSIVE_POLL_MS) != true) {
                            mutableState.value = EmulatorStopState.Failed(request.requestId, reason, safeReadState(readState), "native emulator did not reach Stopped")
                            return@runCatching false
                        }
                    }
                    finalize(request.requestId, host)
                }
            }.getOrElse { error ->
                val nativeState = safeReadState(readState)
                Log.e(TAG, "id=${request.requestId} failed reason=${error.message} native=$nativeState", error)
                mutableState.value = EmulatorStopState.Failed(request.requestId, reason, nativeState, error.message ?: "stop failed")
                false
            }
            request.result.complete(success)
            synchronized(lock) {
                if (inFlight?.requestId == request.requestId) inFlight = null
            }
        }
        return request.result.await()
    }

    private suspend fun waitForStopped(
        readState: () -> EmulatorState,
        requestId: Long,
        reason: EmulatorStopReason,
        timeoutMs: Long,
        pollMs: Long,
    ): Boolean? {
        val started = System.currentTimeMillis()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val state = runCatching { readState() }.getOrElse {
                mutableState.value = EmulatorStopState.Failed(requestId, reason, null, "state-read-failed: ${it.message}")
                Log.e(TAG, "id=$requestId state-read-failed error=${it.message}")
                return null
            }
            if (state == EmulatorState.Stopped) {
                Log.i(TAG, "id=$requestId state=Stopped elapsed=${System.currentTimeMillis() - started}")
                return true
            }
            delay(pollMs)
        }
        return false
    }

    private suspend fun finalizeStopped(context: Context, requestId: Long, host: EmulationHost?, reason: EmulatorStopReason = EmulatorStopReason.HomeStop): Boolean {
        val proven = runCatching { RPCSX.getState() }.getOrElse {
            mutableState.value = EmulatorStopState.Failed(requestId, reason, null, "final state-read-failed: ${it.message}")
            Log.e(TAG, "id=$requestId final state-read-failed error=${it.message}")
            return false
        }
        if (proven != EmulatorState.Stopped) {
            mutableState.value = EmulatorStopState.Failed(requestId, reason, proven, "final native state was $proven")
            return false
        }
        withContext(Dispatchers.Main.immediate) {
            RPCSX.state.value = EmulatorState.Stopped
            RPCSX.activeGame.value = null
            EmulationSessionJournal.markCleanStop(context)
            Log.i(TAG, "id=$requestId finalize native=Stopped published=Stopped activeGame=null journal=CLEAN_STOP")
            host?.let {
                Log.i(TAG, "id=$requestId host-finish activity=${it.activityInstanceId}")
                it.finishAfterExternalStop(requestId)
            }
        }
        mutableState.value = EmulatorStopState.Completed(requestId)
        Log.i(TAG, "id=$requestId complete")
        return true
    }

    private fun readStateStrict(readState: () -> EmulatorState, requestId: Long, reason: EmulatorStopReason): EmulatorState? =
        runCatching { readState() }.getOrElse {
            mutableState.value = EmulatorStopState.Failed(requestId, reason, null, "state-read-failed: ${it.message}")
            Log.e(TAG, "id=$requestId state-read-failed error=${it.message}")
            null
        }

    private fun safeReadState(readState: () -> EmulatorState): EmulatorState? = runCatching { readState() }.getOrNull()

    internal suspend fun stopForTest(
        reason: EmulatorStopReason = EmulatorStopReason.HomeStop,
        readState: () -> EmulatorState,
        kill: () -> Unit,
        activeTimeoutMs: Long = 15_000L,
        passiveTimeoutMs: Long = 45_000L,
        pollMs: Long = 10L,
    ): Boolean {
        // Test hook exercises the same single-flight request and strict waits;
        // no Android bookkeeping or host callbacks are touched.
        return request(
            reason,
            context = null,
            readState,
            kill,
            host = null,
            cancelRecovery = {},
            finalize = { id, _ ->
                if (runCatching { readState() }.getOrNull() == EmulatorState.Stopped) {
                    mutableState.value = EmulatorStopState.Completed(id)
                    true
                } else {
                    false
                }
            },
            activeTimeoutMs = activeTimeoutMs,
            passiveTimeoutMs = passiveTimeoutMs,
            pollMs = pollMs,
        )
    }

    internal fun clearForTest() {
        synchronized(lock) { inFlight = null }
        mutableState.value = EmulatorStopState.Idle
    }
}
