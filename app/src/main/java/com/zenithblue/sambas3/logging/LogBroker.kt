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
import com.zenithblue.sambas3.utils.GeneralSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    ): UnifiedLogEntry {
        val entry = UnifiedLogEntry(
            sequence = sequence.incrementAndGet(),
            sessionId = sessionId,
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

    fun droppedCount(): Long = dropped.get()

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
    private val activeStreams = AtomicInteger(0)
    private var scope: CoroutineScope? = null
    private var logcatJob: Job? = null
    private var tailerJob: Job? = null
    @Volatile var currentSessionId: String? = null
        private set
    @Volatile var currentManifest: LogSessionManifest? = null
        private set

    val snapshot: StateFlow<List<UnifiedLogEntry>> = engine.snapshot
    val status: StateFlow<Map<LogSourceKind, LogSourceStatus>> = engine.status

    val isStreamingActive: Boolean
        get() = activeStreams.get() > 0 || currentSessionId != null

    fun retainStream() {
        activeStreams.incrementAndGet()
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

    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            LogSessionStore.reconcileInterrupted(app)
            LogMonitor.attachBroker(app, engine)
            hydrateLegacyFiles(app)
            logcatJob = launch { AppLogcatSource.run(engine, { currentSessionId }, { isStreamingActive }) }
            tailerJob = launch { BackendFileSource.run(app, engine, { currentSessionId }, { isStreamingActive }) }
            Log.i(TAG, "started pid=${Process.myPid()}")
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
        val title = GameIdentity.displayName(gamePath, gameName)
        val titleId = GameIdentity.titleIdOrNull(gamePath, gameName)
        val driver = runCatching {
            GeneralSettings["selected_gpu_driver"].let { it as? String } ?: "Default"
        }.getOrDefault("Default")
        val manifest = LogSessionStore.begin(
            context = app,
            sessionId = sessionId,
            gamePath = gamePath,
            titleId = titleId,
            gameTitle = title,
            iconPath = iconPath,
            bootMode = bootMode,
            driverLabel = driver,
            appVersion = BuildConfig.VERSION_NAME,
            coreBuildId = runCatching { BuildConfig.BUILD_TYPE }.getOrNull(),
            pid = Process.myPid(),
        )
        currentSessionId = sessionId
        currentManifest = manifest
        engine.setStatus(LogSourceKind.APP_ANDROID, LogSourceStatus.Waiting)
        engine.setStatus(LogSourceKind.RPCSX_BACKEND, LogSourceStatus.Waiting)
        Log.i(TAG, "session begin id=$sessionId title=$title titleId=$titleId")
        return manifest
    }

    fun finalizeGameSession(
        context: Context,
        sessionId: String?,
        terminal: LogSessionTerminal,
        reason: String?,
    ) {
        val id = sessionId ?: currentSessionId ?: return
        val app = context.applicationContext
        val extras = discoverSessionArtifacts(app, id)
        val finalized = LogSessionStore.finalize(
            context = app,
            sessionId = id,
            terminal = terminal,
            reason = reason,
            droppedLines = engine.droppedCount(),
            extraArtifacts = extras,
        )
        currentManifest = finalized
        if (currentSessionId == id) {
            currentSessionId = null
            if (activeStreams.get() <= 0) {
                AppLogcatSource.stopActiveProcess()
            }
        }
        Log.i(TAG, "session finalize id=$id terminal=$terminal artifacts=${finalized?.artifacts?.size ?: 0}")
    }

    fun hydrateSession(context: Context, sessionId: String) {
        val manifest = LogSessionStore.read(context, sessionId) ?: return
        currentManifest = manifest
        val entries = ArrayList<UnifiedLogEntry>()
        for (artifact in manifest.artifacts) {
            val file = File(artifact.path)
            if (!file.isFile) continue
            val tailer = LogFileTailer(file, startAtEnd = false)
            val lines = tailer.drain().takeLast(800)
            for (line in lines) {
                entries += UnifiedLogEntry(
                    sequence = 0L,
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
        if (entries.isNotEmpty()) engine.hydrate(entries)
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

    private fun hydrateLegacyFiles(context: Context) {
        val files = LogMonitor.getAllLogFiles()
        val entries = ArrayList<UnifiedLogEntry>()
        for (file in files) {
            if (!file.isFile || file.length() == 0L) continue
            val kind = LogArtifactDiscovery.classify(file)
            val len = file.length()
            val startOffset = (len - 128 * 1024L).coerceAtLeast(0L)
            val tailer = LogFileTailer(file, initialOffset = startOffset)
            val lines = tailer.drain().takeLast(400)
            for (line in lines) {
                entries += UnifiedLogEntry(
                    sequence = 0L,
                    sessionId = null,
                    timestampMs = null,
                    timestampText = null,
                    monotonicNs = null,
                    level = LogLevel.INFO,
                    tag = file.name,
                    message = line,
                    source = kind,
                    artifactId = file.name,
                )
            }
        }
        if (entries.isNotEmpty()) engine.hydrate(entries)
        if (files.none { it.isFile && it.length() > 0L }) {
            engine.setStatus(LogSourceKind.LEGACY, LogSourceStatus.Quiet(0))
        }
    }

    private fun discoverSessionArtifacts(context: Context, sessionId: String): List<LogArtifact> {
        val roots = buildList {
            add(File(RPCSX.rootDirectory).takeIf { RPCSX.rootDirectory.isNotBlank() })
            add(context.filesDir)
            add(context.cacheDir)
            add(context.getExternalFilesDir(null))
            add(context.getExternalFilesDir("logs"))
            add(File(context.filesDir, "crash_reports"))
        }.filterNotNull()
        val now = System.currentTimeMillis()
        val session = LogSessionStore.read(context, sessionId)
        val since = session?.startedAtMs
        return LogArtifactDiscovery.scan(roots, sinceMs = since).map { discovered ->
            LogArtifact(
                id = discovered.file.name,
                source = discovered.kind,
                path = discovered.file.absolutePath,
                detectedAtMs = now,
                bytes = discovered.file.length(),
                compressed = discovered.compressed,
                liveTailSupported = !discovered.compressed,
                finalStatus = if (discovered.file.length() > 0L) "present" else "empty",
            )
        }
    }
}
