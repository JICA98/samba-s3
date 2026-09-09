package com.zenithblue.sambas3.logging

import android.content.Context
import android.os.Process
import android.util.Log
import com.zenithblue.sambas3.BuildConfig
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.LogEntry
import com.zenithblue.sambas3.LogLevel
import com.zenithblue.sambas3.LogMonitor
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.session.ProcessInstance
import com.zenithblue.sambas3.utils.GeneralSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** In-process merge/buffer. Display pause never stops collection. */
class LogBrokerEngine(
    private val ringCapacity: Int = 3_000,
    private val nowNs: () -> Long = { System.nanoTime() },
) {
    private val lock = Any()
    private val ring = ArrayDeque<UnifiedLogEntry>(ringCapacity + 1)
    private val _snapshot = MutableStateFlow<List<UnifiedLogEntry>>(emptyList())
    val snapshot: StateFlow<List<UnifiedLogEntry>> = _snapshot.asStateFlow()
    private val _status = MutableStateFlow<Map<LogSourceKind, LogSourceStatus>>(emptyMap())
    val status: StateFlow<Map<LogSourceKind, LogSourceStatus>> = _status.asStateFlow()
    private val sequence = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val persistDropped = AtomicLong(0L)
    @Volatile var displayPaused: Boolean = false
    private val sinks = CopyOnWriteArrayList<(UnifiedLogEntry) -> Unit>()

    fun addSink(sink: (UnifiedLogEntry) -> Unit) {
        sinks += sink
    }

    fun emit(
        message: String,
        source: LogSourceKind,
        level: LogLevel = LogLevel.INFO,
        tag: String? = null,
        sessionId: String? = null,
        timestampMs: Long? = null,
        timestampText: String? = null,
        artifactId: String? = null,
        raw: Boolean = false,
        boundSessionId: String? = sessionId,
    ): UnifiedLogEntry {
        val entry = UnifiedLogEntry(
            sequence = sequence.incrementAndGet(),
            sessionId = boundSessionId ?: sessionId,
            timestampMs = timestampMs,
            timestampText = timestampText,
            monotonicNs = nowNs(),
            level = level,
            tag = tag,
            message = message,
            source = source,
            artifactId = artifactId,
            raw = raw,
        )
        synchronized(lock) {
            ring.addLast(entry)
            while (ring.size > ringCapacity) {
                ring.removeFirst()
                dropped.incrementAndGet()
            }
            if (!displayPaused) {
                _snapshot.value = ring.toList()
            }
        }
        sinks.forEach { runCatching { it(entry) } }
        return entry
    }

    fun markDropped(count: Long = 1L) {
        dropped.addAndGet(count)
    }

    fun markPersistDropped(count: Long = 1L) {
        persistDropped.addAndGet(count)
    }

    fun droppedCount(): Long = dropped.get()

    fun persistDroppedCount(): Long = persistDropped.get()

    fun setStatus(kind: LogSourceKind, status: LogSourceStatus) {
        _status.value = _status.value + (kind to status)
    }

    fun hydrate(entries: List<UnifiedLogEntry>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            for (entry in entries) {
                val numbered = if (entry.sequence == 0L) entry.copy(sequence = sequence.incrementAndGet()) else entry
                ring.addLast(numbered)
            }
            while (ring.size > ringCapacity) ring.removeFirst()
            _snapshot.value = ring.toList()
        }
    }

    fun clearView() {
        synchronized(lock) {
            ring.clear()
            _snapshot.value = emptyList()
        }
    }

    fun currentRing(): List<UnifiedLogEntry> = synchronized(lock) { ring.toList() }

    fun publish() {
        synchronized(lock) { _snapshot.value = ring.toList() }
    }
}

object LogBroker {
    private const val TAG = "LogBroker"
    val engine = LogBrokerEngine()

    private val started = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private val activeStreams = AtomicInteger(0)
    private var scope: CoroutineScope? = null
    private var logcatJob: Job? = null
    private var tailerJob: Job? = null
    @Volatile var currentSessionId: String? = null
        private set
    @Volatile var currentManifest: LogSessionManifest? = null
        private set
    @Volatile var bootstrapSessionId: String? = null
        private set
    @Volatile private var wakeGeneration = 0L
    private val sessionLock = Any()

    val snapshot: StateFlow<List<UnifiedLogEntry>> = engine.snapshot
    val status: StateFlow<Map<LogSourceKind, LogSourceStatus>> = engine.status

    val isStreamingActive: Boolean
        get() = activeStreams.get() > 0 || currentSessionId != null

    fun retainStream() {
        activeStreams.incrementAndGet()
        wakeSources()
    }

    fun releaseStream() {
        val next = activeStreams.decrementAndGet()
        if (next <= 0) {
            activeStreams.set(0)
            if (currentSessionId == null) {
                AppLogcatSource.stopActiveProcess()
            }
        }
    }

    fun wakeSources() {
        wakeGeneration++
        AppLogcatSource.wake()
        BackendFileSource.wake()
    }

    fun waitUntilReady(timeoutMs: Long = 5_000L): Boolean {
        if (ready.isCompleted) return true
        return runBlocking {
            withTimeoutOrNull(timeoutMs) { ready.await() } != null
        }
    }

    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        snapshotNativeGenerations(app)
        LogSessionStore.reconcileInterrupted(
            app,
            liveSessionId = currentSessionId,
            liveProcessInstanceId = ProcessInstance.id,
        )
        beginBootstrapSession(app)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            LogMonitor.attachBroker(app, engine)
            logcatJob = launch { AppLogcatSource.run(engine, { currentSessionId }, { isStreamingActive }) }
            tailerJob = launch { BackendFileSource.run(app, engine, { currentSessionId }, { isStreamingActive }) }
            Log.i(TAG, "started pid=${Process.myPid()}")
            if (!ready.isCompleted) ready.complete(Unit)
        }
    }

    fun beginGameSession(
        context: Context,
        sessionId: String,
        gamePath: String,
        gameName: String?,
        iconPath: String?,
        bootMode: String?,
    ): LogSessionManifest {
        ensureStarted(context)
        val app = context.applicationContext
        return synchronized(sessionLock) {
                val title = GameIdentity.displayName(gamePath, gameName)
                val titleId = GameIdentity.titleIdOrNull(gamePath, gameName)
                val storedDriver = runCatching {
                    GeneralSettings["selected_gpu_driver"].let { it as? String } ?: "Default"
                }.getOrDefault("Default")
                val appliedDriver = runCatching { storedDriver }.getOrDefault(storedDriver)
                val coreId = runCatching { RPCSX.instance.getCoreBuildId() }.getOrNull()
                    ?: runCatching { BuildConfig.BUILD_TYPE }.getOrNull()
                val manifest = LogSessionStore.begin(
                    context = app,
                    sessionId = sessionId,
                    gamePath = gamePath,
                    titleId = titleId,
                    gameTitle = title,
                    iconPath = iconPath,
                    bootMode = bootMode,
                    driverLabel = storedDriver,
                    appVersion = BuildConfig.VERSION_NAME,
                    coreBuildId = coreId,
                    pid = Process.myPid(),
                    processInstanceId = ProcessInstance.id,
                    producerEpoch = "${ProcessInstance.id}-$sessionId",
                    appliedDriverLabel = appliedDriver,
                )
                currentSessionId = sessionId
                currentManifest = manifest
                engine.setStatus(LogSourceKind.APP_ANDROID, LogSourceStatus.Waiting)
                engine.setStatus(LogSourceKind.RPCSX_BACKEND, LogSourceStatus.Waiting)
                wakeSources()
                Log.i(TAG, "session begin id=$sessionId title=$title titleId=$titleId core=$coreId")
                manifest
        }
    }

    fun finalizeGameSession(
        context: Context,
        sessionId: String?,
        terminal: LogSessionTerminal,
        reason: String?,
    ) {
        val id = sessionId ?: currentSessionId ?: return
        val app = context.applicationContext
        val extras = synchronized(sessionLock) { collectSessionOutputs(app, id) }
        val drainError = try {
            runBlocking { drainSources(timeoutMs = 1_500L) }
        } catch (_: Exception) {
            "drain-failed"
        }
        synchronized(sessionLock) {
            val more = collectSessionOutputs(app, id)
            val captureState = if (drainError != null) CaptureState.RECOVERED_PARTIAL else CaptureState.SEALED
            val finalized = LogSessionStore.finalize(
                context = app,
                sessionId = id,
                terminal = terminal,
                reason = reason,
                droppedLines = engine.droppedCount(),
                extraArtifacts = extras + more,
                captureState = captureState,
                captureError = drainError,
                displayDropped = engine.droppedCount(),
                persistenceDropped = engine.persistDroppedCount(),
            )
            currentManifest = finalized
            if (currentSessionId == id) {
                currentSessionId = null
                if (activeStreams.get() <= 0) {
                    AppLogcatSource.stopActiveProcess()
                }
            }
            Log.i(TAG, "session finalize id=$id terminal=$terminal artifacts=${finalized?.artifacts?.size ?: 0} capture=$captureState")
        }
    }

    fun hydrateSession(context: Context, sessionId: String): List<UnifiedLogEntry> {
        val manifest = LogSessionStore.read(context, sessionId) ?: return emptyList()
        val entries = ArrayList<UnifiedLogEntry>()
        for (artifact in manifest.artifacts) {
            val file = artifactFile(context, manifest.sessionId, artifact) ?: continue
            if (!file.isFile) continue
            val tailer = LogFileTailer(file, startAtEnd = false)
            val lines = tailer.drain().takeLast(800)
            for (line in lines) {
                entries += UnifiedLogEntry(
                    sequence = entries.size.toLong() + 1L,
                    sessionId = sessionId,
                    timestampMs = null,
                    timestampText = null,
                    monotonicNs = null,
                    level = LogLevel.INFO,
                    tag = artifact.source.name,
                    message = line,
                    source = artifact.source,
                    artifactId = artifact.id,
                )
            }
        }
        return entries
    }

    fun displayPaused(paused: Boolean) {
        engine.displayPaused = paused
        if (!paused) engine.publish()
    }

    fun clearView() = engine.clearView()

    fun toLogEntry(entry: UnifiedLogEntry): LogEntry = LogEntry(
        id = entry.sequence,
        timestamp = entry.timestampText ?: "",
        level = entry.level,
        tag = entry.tag ?: entry.source.name,
        message = entry.message,
        source = entry.source.toLegacySource(),
    )

    fun snapshotNativeBeforeReopen(context: Context) {
        snapshotNativeGenerations(context.applicationContext)
    }

    private fun beginBootstrapSession(context: Context) {
        if (currentSessionId != null) return
        val id = "bootstrap-${ProcessInstance.id.take(8)}"
        bootstrapSessionId = id
        val existing = LogSessionStore.read(context, id)
        if (existing != null) return
        runCatching {
            LogSessionStore.begin(
                context = context,
                sessionId = id,
                gamePath = "",
                titleId = null,
                gameTitle = "App startup",
                iconPath = null,
                bootMode = "Bootstrap",
                driverLabel = null,
                appVersion = BuildConfig.VERSION_NAME,
                coreBuildId = runCatching { RPCSX.instance.getCoreBuildId() }.getOrNull(),
                pid = Process.myPid(),
                processInstanceId = ProcessInstance.id,
                producerEpoch = "bootstrap-${ProcessInstance.id}",
            )
        }
    }

    private suspend fun drainSources(timeoutMs: Long): String? {
        val ok = withTimeoutOrNull(timeoutMs) {
            BackendFileSource.drainOnce()
            AppLogcatSource.drainHint()
            true
        }
        return if (ok == null) "drain-timeout" else null
    }

    private fun snapshotNativeGenerations(context: Context) {
        val live = currentSessionId
        if (live != null) {
            collectNativeSnapshots(context, live, sealed = true)
        } else {
            val last = LogSessionStore.latest(context) ?: return
            if (last.terminalState == LogSessionTerminal.RUNNING) {
                collectNativeSnapshots(context, last.sessionId, sealed = true)
            }
        }
    }

    private fun collectNativeSnapshots(context: Context, sessionId: String, sealed: Boolean): List<LogArtifact> {
        val out = ArrayList<LogArtifact>()
        val generation = "${System.currentTimeMillis()}"
        val roots = BackendFileSource.roots(context)
        for (item in LogArtifactDiscovery.scan(roots, sinceMs = null, maxFiles = 24)) {
            val artifact = LogSessionStore.registerOutput(
                context,
                sessionId,
                item.file,
                item.kind,
                producer = "native",
                generation = generation,
                sealed = sealed,
            ) ?: continue
            out += artifact
            LogSessionStore.attachArtifact(context, sessionId, artifact)
        }
        return out
    }

    private fun collectSessionOutputs(context: Context, sessionId: String): List<LogArtifact> {
        val out = ArrayList<LogArtifact>()
        val generation = "final-${System.currentTimeMillis()}"
        LogMonitor.flushWriters()
        for (file in LogMonitor.getAllLogFiles()) {
            val kind = when {
                file.name.contains("backend") -> LogSourceKind.RPCSX_BACKEND
                file.name.contains("vulkan") -> LogSourceKind.VULKAN
                else -> LogSourceKind.APP_ANDROID
            }
            val artifact = LogSessionStore.registerOutput(
                context,
                sessionId,
                file,
                kind,
                producer = "monitor",
                generation = generation,
                sealed = true,
            ) ?: continue
            out += artifact
        }
        out += collectNativeSnapshots(context, sessionId, sealed = true)
        return out
    }

    fun artifactFile(context: Context, sessionId: String, artifact: LogArtifact): File? {
        val dir = LogSessionStore.sessionDir(context, sessionId)
        val relative = artifact.relativePath
        if (!relative.isNullOrBlank()) {
            val sealed = File(dir, relative)
            if (sealed.isFile) return sealed
        }
        val live = File(artifact.path)
        return live.takeIf { it.isFile }
    }
}
