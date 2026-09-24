package com.zenithblue.sambas3

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.zenithblue.sambas3.logging.toLegacySource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

enum class LogLevel(val letter: Char, val priority: Int) {
    VERBOSE('V', 0),
    DEBUG('D', 1),
    INFO('I', 2),
    WARN('W', 3),
    ERROR('E', 4),
    FATAL('F', 5);

    companion object {
        fun fromChar(c: Char): LogLevel = entries.firstOrNull { it.letter == c } ?: DEBUG
    }
}

enum class LogSource(val label: String) {
    APP("App"),
    RPCSX("RPCSX"),
    VULKAN("Vulkan"),
    DRIVER("Driver"),
    KERNEL("Kernel"),
    CELL("Cell"),
    OTHER("Other");
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val source: LogSource,
)

// ---------------------------------------------------------------------------
// Log file routing — separate files per source category
// ---------------------------------------------------------------------------
enum class LogFileCategory(val filename: String, val maxBytes: Long) {
    BACKEND("rpcsx_backend.log", 25 * 1024 * 1024L),
    VULKAN("rpcsx_vulkan.log", 15 * 1024 * 1024L),
    APP("rpcsx_app.log", 10 * 1024 * 1024L),
}

// ---------------------------------------------------------------------------
// Tag classification tables
// ---------------------------------------------------------------------------
private val RPCSX_TAGS = setOf(
    "RPCS3", "RPCSX-UI", "ANDROID", "rpcsx_android",
    "ppu_log", "spu_log", "ppu_loader", "ppu_validator",
    "sys_log", "vm_log", "jit_log", "llvm_log",
    "self_log", "mself_log", "pkg_log", "edat_log",
    "key_vault_log", "ticket_log", "sig_log", "sign_log",
    "psf_log", "tar_log", "trp_log",
    "sys_crashdump", "sys_lv2dbg", "sys_libc",
    "GDB", "IPC", "patch_log", "perf_log", "profiler",
    "gui_log", "cfg_log", "input_log", "media_log",
    "vfs_log", "usb_vfs", "disc_log",
    "sceNp", "sceNp2", "sceNpTrophy", "sceNpTus",
    "sceNpClans", "sceNpCommerce2", "sceNpMatchingInt",
    "sceNpPlus", "sceNpSns", "sceNpUtil",
    "rpcn_log", "nph_log", "np_cache", "np_gui_cache", "np_mem_allocator",
    "sysPrxForUser", "screenshot_log", "debugbp_log",
    "static_hle", "log_cheat",
    "IPv6_log", "dnshook_log", "upnp_log", "upnp_cfg_log",
    "libnet",
)

private val KERNEL_TAGS = setOf(
    "sys_bdemu", "sys_btsetting", "sys_cond", "sys_config", "sys_console",
    "sys_crypto_engine", "sys_dbg", "sys_event", "sys_event_flag",
    "sys_fs", "sys_game", "sys_gamepad", "sys_gpio", "sys_hid",
    "sys_interrupt", "sys_io", "sys_lwcond", "sys_lwmutex",
    "sys_memory", "sys_mmapper", "sys_mutex", "sys_net", "sys_net_dump",
    "sys_overlay", "sys_ppu_thread", "sys_process", "sys_prx",
    "sys_rsx", "sys_rsxaudio", "sys_rwlock", "sys_semaphore",
    "sys_sm", "sys_spu", "sys_ss", "sys_storage",
    "sys_time", "sys_timer", "sys_trace", "sys_tty", "sys_uart",
    "sys_usbd", "sys_vm",
)

private val CELL_TAGS = setOf(
    "cellAdec", "cellAtrac", "cellAtracMulti", "cellAtracXdec",
    "cellAudio", "cellAvconfExt", "cellBGDL", "cellCamera",
    "cellCelp8Enc", "cellCelpEnc", "cellCrossController",
    "cellDaisy", "cellDmux", "cellDmuxPamf", "cellDtcpIpUtility",
    "cellFiber", "cellFont", "cellFontFT", "cellFs",
    "cellGame", "cellGameExec", "cellGcmSys", "cellGem",
    "cellGifDec", "cellHttp", "cellHttpUtil", "cellImeJp",
    "cellJpgDec", "cellJpgEnc", "cellKb", "cellKey2char",
    "cellL10n", "cellLibprof", "cellMic", "cellMouse",
    "cellMusic", "cellMusicDecode", "cellMusicExport",
    "cellMusicSelectionContext", "cellNetAoi", "cellNetCtl",
    "cellOskDialog", "cellOvis", "cellPad", "cellPamf",
    "cellPesmUtility", "cellPhotoDecode", "cellPhotoExport",
    "cellPhotoImportUtil", "cellPngDec", "cellPngEnc",
    "cellPrint", "cellRec", "cellRemotePlay", "cellResc",
    "cellRtc", "cellRtcAlarm", "cellRudp", "cellSail",
    "cellSailRec", "cellSaveData", "cellScreenshot",
    "cellSearch", "cellSheap", "cellSpudll", "cellSpurs",
    "cellSpursJq", "cellSsl", "cellSubDisplay", "cellSync",
    "cellSync2", "cellSysconf", "cellSysmodule", "cellSysutil",
    "cellSysutilAp", "cellSysutilAvc2", "cellSysutilAvcExt",
    "cellSysutilMisc", "cellSysutilNpEula", "cellUsbPspcm",
    "cellUsbd", "cellUserInfo", "cellVdec", "cellVideoExport",
    "cellVideoPlayerUtility", "cellVideoUpload", "cellVoice",
    "cellVpost", "cell_FreeType2",
    "libad_async", "libad_core", "libfs_utility_init",
    "libmedi", "libmixer", "libsnd3", "libsynth2",
    "dec_log", "osk", "overlays",
)

private val VULKAN_TAGS = setOf(
    "vulkan", "libvulkan", "VKDBG", "VkLayer", "VkLayerValidation",
    "VulkanLoader", "vkloader", "vk_swapchain",
)

private val DRIVER_TAGS = setOf(
    "amdgpu", "Mesa", "mesa", "freedreno", "turnip", "radv",
    "hook_impl", "adrenotools", "qtimapper-shim",
    "Cubeb", "XAudio", "FAudio_", "cubeb_dev_enum",
    "xaudio_dev_enum", "faudio_dev_enum",
    "ds3_log", "ds4_log", "dualsense_log", "hid_log",
    "evdev_log", "sdl_log", "move_log", "ps_move",
    "ghltar_log", "guncon3_log", "skateboard_log",
    "skylander_log", "infinity_log", "turntable_log",
    "rb3_midi_drums_log", "rb3_midi_guitar_log", "rb3_midi_keyboard_log",
    "topshotelite_log", "topshotfearmaster_log",
    "buzz_log", "gametablet_log", "usio_log",
    "camera_log", "dimensions_log", "CameraService",
)

private val APP_TAGS = setOf(
    "SambaS3", "Main", "RPCSX State", "GameRepository",
    "FirmwareRepository", "UserRepository", "PrecompilerService",
    "ProgressRepository", "LogMonitor",
)

private fun classifyTag(tag: String): LogSource = when {
    tag in APP_TAGS -> LogSource.APP
    tag in VULKAN_TAGS -> LogSource.VULKAN
    tag in DRIVER_TAGS -> LogSource.DRIVER
    tag in KERNEL_TAGS -> LogSource.KERNEL
    tag in CELL_TAGS -> LogSource.CELL
    tag in RPCSX_TAGS -> LogSource.RPCSX
    else -> LogSource.OTHER
}

fun LogSource.fileCategory(): LogFileCategory = when (this) {
    LogSource.RPCSX, LogSource.KERNEL, LogSource.CELL -> LogFileCategory.BACKEND
    LogSource.VULKAN, LogSource.DRIVER -> LogFileCategory.VULKAN
    LogSource.APP, LogSource.OTHER -> LogFileCategory.APP
}

// ---------------------------------------------------------------------------
// LogMonitor singleton
// ---------------------------------------------------------------------------
object LogMonitor {
    private const val TAG = "LogMonitor"
    const val MAX_UI_ENTRIES = 1000
    const val CHANNEL_CAPACITY = 4096
    const val BATCH_SIZE = 32
    const val FLUSH_INTERVAL_MS = 2_000L
    const val DISPLAY_CADENCE_MS = 300L
    const val NORMAL_EOF_BACKOFF_MS = 1_000L
    const val ERROR_BACKOFF_MS = 2_000L

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _backendLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val backendLogs: StateFlow<List<LogEntry>> = _backendLogs.asStateFlow()

    private val _vulkanLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val vulkanLogs: StateFlow<List<LogEntry>> = _vulkanLogs.asStateFlow()

    private val _appLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val appLogs: StateFlow<List<LogEntry>> = _appLogs.asStateFlow()

    private val bufferLock = Any()
    private val allBuf = ArrayDeque<LogEntry>(MAX_UI_ENTRIES + 1)
    private val backBuf = ArrayDeque<LogEntry>(MAX_UI_ENTRIES + 1)
    private val vulkBuf = ArrayDeque<LogEntry>(MAX_UI_ENTRIES + 1)
    private val appBuf = ArrayDeque<LogEntry>(MAX_UI_ENTRIES + 1)

    private val droppedDebugCount = AtomicLong(0L)
    fun droppedCount(): Long = droppedDebugCount.get()

    private var scope: CoroutineScope? = null
    private var channel: Channel<LogEntry>? = null
    private var logcatProcess: Process? = null
    private val running = AtomicBoolean(false)
    private var logDir: File? = null

    private val writers = mutableMapOf<LogFileCategory, BufferedWriter>()
    private val writeSizes = mutableMapOf<LogFileCategory, Long>()
    private val lastFlushAtMs = mutableMapOf<LogFileCategory, Long>()
    private var entryIdCounter = 0L
    @Volatile private var uiDirty = false

    private fun <T> ArrayDeque<T>.addCapped(item: T) {
        addLast(item)
        if (size > MAX_UI_ENTRIES) removeFirst()
    }

    internal fun isFatalOrCrash(entry: LogEntry): Boolean {
        if (entry.level == LogLevel.FATAL || entry.level == LogLevel.ERROR) return true
        val tagLower = entry.tag.lowercase()
        val msgLower = entry.message.lowercase()
        return tagLower.contains("crash") || msgLower.contains("crash") ||
            tagLower.contains("fatal") || msgLower.contains("fatal") ||
            msgLower.contains("sigsegv") || msgLower.contains("sigbus") ||
            msgLower.contains("abort") || tagLower.contains("sys_crashdump")
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun start(context: Context) {
        com.zenithblue.sambas3.logging.LogBroker.ensureStarted(context)
    }

    fun attachBroker(context: Context, engine: com.zenithblue.sambas3.logging.LogBrokerEngine) {
        logDir = context.getExternalFilesDir("logs")?.also { it.mkdirs() }
        if (writers.isEmpty()) openWriters()
        if (running.compareAndSet(false, true)) {
            hydrateUiFromFiles()
            startConsumer()
            engine.addSink { ingest(it) }
            Log.i(TAG, "LogMonitor attached to broker — log dir: ${logDir?.absolutePath}")
        }
    }

    private fun startConsumer() {
        val ch = Channel<LogEntry>(CHANNEL_CAPACITY)
        channel = ch
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { runConsumer(ch) }
    }

    /**
     * Stop flushes writers and publishes final UI updates, then cancels background consumer.
     */
    fun stop() {
        running.set(false)
        scope?.cancel()
        scope = null
        channel?.close()
        channel = null
        logcatProcess?.destroy()
        logcatProcess = null
        flushWriters()
        publishUi(force = true)
    }

    fun clearLogs() {
        synchronized(bufferLock) {
            allBuf.clear()
            backBuf.clear()
            vulkBuf.clear()
            appBuf.clear()
            uiDirty = false
        }
        _logs.value = emptyList()
        _backendLogs.value = emptyList()
        _vulkanLogs.value = emptyList()
        _appLogs.value = emptyList()
        com.zenithblue.sambas3.logging.LogBroker.clearView()
    }

    fun getLogFile(category: LogFileCategory = LogFileCategory.APP): File? =
        logDir?.resolve(category.filename)

    fun getAllLogFiles(): List<File> =
        LogFileCategory.entries.flatMap { category ->
            val current = getLogFile(category) ?: return@flatMap emptyList()
            listOf(current, File("${current.path}.1"), File("${current.path}.2"))
                .filter(File::exists)
        }

    /** Periodic buffered file flush. If force is true, flushes all writers immediately. */
    fun flushPendingWriters(force: Boolean = false) {
        val now = System.currentTimeMillis()
        writers.forEach { (cat, w) ->
            val last = lastFlushAtMs[cat] ?: 0L
            if (force || now - last >= FLUSH_INTERVAL_MS) {
                runCatching { w.flush() }
                lastFlushAtMs[cat] = now
            }
        }
    }

    /** Flush open writers so share/export sees latest lines. */
    fun flushWriters() {
        flushPendingWriters(force = true)
    }

    fun publishUi(force: Boolean = true) {
        synchronized(bufferLock) {
            publishUiIfNeeded(force)
        }
    }

    private fun publishUiIfNeeded(force: Boolean = false) {
        if (!uiDirty && !force) return
        val logsSubscribers = _logs.subscriptionCount.value > 0
        val backendSubscribers = _backendLogs.subscriptionCount.value > 0
        val vulkanSubscribers = _vulkanLogs.subscriptionCount.value > 0
        val appSubscribers = _appLogs.subscriptionCount.value > 0
        val anySubscribers = logsSubscribers || backendSubscribers || vulkanSubscribers || appSubscribers

        if (force || anySubscribers) {
            if (force || logsSubscribers) _logs.value = allBuf.toList()
            if (force || backendSubscribers) _backendLogs.value = backBuf.toList()
            if (force || vulkanSubscribers) _vulkanLogs.value = vulkBuf.toList()
            if (force || appSubscribers) _appLogs.value = appBuf.toList()
            uiDirty = false
        } else {
            // Update _logs so direct .value readers see state, but avoid copying 3 category lists
            _logs.value = allBuf.toList()
            uiDirty = false
        }
    }

    internal fun enqueue(entry: LogEntry) {
        val ch = channel
        val isCritical = isFatalOrCrash(entry)
        if (ch != null) {
            val result = ch.trySend(entry)
            if (result.isSuccess) {
                return
            }
            if (isCritical) {
                // Guaranteed delivery for FATAL/ERROR/crash under extreme pressure
                synchronized(bufferLock) {
                    processEntry(entry)
                    publishUiIfNeeded(force = true)
                }
            } else if (!result.isClosed) {
                // Drop low-priority debug under extreme pressure and count
                droppedDebugCount.incrementAndGet()
            }
        } else {
            // Direct/fallback processing when consumer channel is not running
            synchronized(bufferLock) {
                processEntry(entry)
                if (isCritical) {
                    publishUiIfNeeded(force = true)
                }
            }
        }
    }

    fun ingest(entry: com.zenithblue.sambas3.logging.UnifiedLogEntry) {
        val mapped = LogEntry(
            id = entry.sequence,
            timestamp = entry.timestampText ?: "",
            level = entry.level,
            tag = entry.tag ?: entry.source.name,
            message = entry.message,
            source = entry.source.toLegacySource(),
        )
        enqueue(mapped)
    }

    private fun processEntry(entry: LogEntry) {
        allBuf.addCapped(entry)
        when (entry.source.fileCategory()) {
            LogFileCategory.BACKEND -> backBuf.addCapped(entry)
            LogFileCategory.VULKAN -> vulkBuf.addCapped(entry)
            LogFileCategory.APP -> appBuf.addCapped(entry)
        }
        uiDirty = true
        writeEntryToFile(entry)
    }

    internal fun writeEntryToFile(entry: LogEntry) {
        val cat = entry.source.fileCategory()
        rotateIfNeeded(cat)
        val w = writers[cat] ?: return
        val line = "[${entry.timestamp}] ${entry.level.letter}/${entry.tag}: ${entry.message}\n"
        runCatching {
            w.write(line)
            writeSizes[cat] = (writeSizes[cat] ?: 0L) + line.length
            val now = System.currentTimeMillis()
            if (isFatalOrCrash(entry)) {
                w.flush()
                lastFlushAtMs[cat] = now
            } else {
                val last = lastFlushAtMs[cat] ?: 0L
                if (now - last >= FLUSH_INTERVAL_MS) {
                    w.flush()
                    lastFlushAtMs[cat] = now
                }
            }
        }
    }

    private fun hydrateUiFromFiles() {
        if (_logs.value.isNotEmpty()) return
        val hydrated = ArrayList<LogEntry>(400)
        getAllLogFiles().forEach { file ->
            if (!file.isFile || file.length() == 0L) return@forEach
            val len = file.length()
            val startOffset = (len - 64 * 1024L).coerceAtLeast(0L)
            val tailer = com.zenithblue.sambas3.logging.LogFileTailer(file, initialOffset = startOffset)
            tailer.drain().takeLast(200).forEach { line ->
                hydrated += LogEntry(
                    id = ++entryIdCounter,
                    timestamp = "",
                    level = LogLevel.INFO,
                    tag = file.name,
                    message = line,
                    source = when {
                        file.name.contains("backend") -> LogSource.RPCSX
                        file.name.contains("vulkan") -> LogSource.VULKAN
                        else -> LogSource.APP
                    },
                )
            }
        }
        if (hydrated.isNotEmpty()) {
            synchronized(bufferLock) {
                hydrated.takeLast(MAX_UI_ENTRIES).forEach { allBuf.addCapped(it) }
                hydrated.filter { it.source.fileCategory() == LogFileCategory.BACKEND }.takeLast(MAX_UI_ENTRIES).forEach { backBuf.addCapped(it) }
                hydrated.filter { it.source.fileCategory() == LogFileCategory.VULKAN }.takeLast(MAX_UI_ENTRIES).forEach { vulkBuf.addCapped(it) }
                hydrated.filter { it.source.fileCategory() == LogFileCategory.APP }.takeLast(MAX_UI_ENTRIES).forEach { appBuf.addCapped(it) }
                uiDirty = true
                publishUiIfNeeded(force = true)
            }
        }
    }

    // ------------------------------------------------------------------
    // Writers
    // ------------------------------------------------------------------

    private fun openWriters() {
        val dir = logDir ?: return
        LogFileCategory.entries.forEach { cat ->
            runCatching {
                val file = dir.resolve(cat.filename)
                val w = BufferedWriter(FileWriter(file, true), 32 * 1024)
                writers[cat] = w
                writeSizes[cat] = if (file.exists()) file.length() else 0L
            }.onFailure { Log.e(TAG, "Failed to open writer for ${cat.filename}", it) }
        }
    }

    private fun rotateIfNeeded(cat: LogFileCategory) {
        val dir = logDir ?: return
        if ((writeSizes[cat] ?: 0L) < cat.maxBytes) return

        writers[cat]?.runCatching { close() }
        writers.remove(cat)

        val file = dir.resolve(cat.filename)
        val old2 = dir.resolve("${cat.filename}.2")
        val old1 = dir.resolve("${cat.filename}.1")
        old2.delete()
        if (old1.exists()) old1.renameTo(old2)
        if (file.exists()) file.renameTo(old1)

        runCatching {
            val w = BufferedWriter(FileWriter(file, false), 32 * 1024)
            writers[cat] = w
            writeSizes[cat] = 0L
        }.onFailure { Log.e(TAG, "Rotation re-open failed for ${cat.filename}", it) }
    }

    // ------------------------------------------------------------------
    // Coroutines
    // ------------------------------------------------------------------

    internal suspend fun runLogcatReader(
        channel: Channel<LogEntry>,
        processProvider: (suspend () -> Process)? = null,
        eofBackoffMs: Long = NORMAL_EOF_BACKOFF_MS,
        errorBackoffMs: Long = ERROR_BACKOFF_MS,
    ) {
        val linePattern = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:\s*(.*)$"""
        )

        try {
            while (running.get() && currentCoroutineContext().isActive) {
                var proc: Process? = null
                try {
                    proc = processProvider?.invoke() ?: ProcessBuilder(
                        "logcat", "-v", "threadtime", "-b", "main,crash,system"
                    ).redirectErrorStream(true).start()
                    logcatProcess = proc

                    proc.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (currentCoroutineContext().isActive && running.get()) {
                            line = reader.readLine()
                            if (line == null) break // Normal EOF
                            val match = linePattern.matchEntire(line) ?: continue
                            val (ts, lvl, tag, msg) = match.destructured
                            val entry = LogEntry(
                                id = ++entryIdCounter,
                                timestamp = ts,
                                level = LogLevel.fromChar(lvl[0]),
                                tag = tag.trim(),
                                message = msg,
                                source = classifyTag(tag.trim()),
                            )
                            enqueue(entry)
                        }
                    }
                    proc.destroy()
                    if (logcatProcess == proc) logcatProcess = null

                    // Normal EOF bounded backoff to prevent tight spin loops
                    if (currentCoroutineContext().isActive && running.get()) {
                        delay(eofBackoffMs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (currentCoroutineContext().isActive && running.get()) {
                        Log.w(TAG, "logcat reader error; waiting ${errorBackoffMs}ms: ${e.message}")
                        delay(errorBackoffMs)
                    }
                } finally {
                    proc?.destroy()
                    if (logcatProcess == proc) logcatProcess = null
                }
            }
        } finally {
            logcatProcess?.destroy()
            logcatProcess = null
            channel.close()
        }
    }

    internal suspend fun runConsumer(
        channel: Channel<LogEntry>,
        displayCadenceMs: Long = DISPLAY_CADENCE_MS,
        flushIntervalMs: Long = FLUSH_INTERVAL_MS,
    ) = coroutineScope {
        val uiTicker = launch {
            while (isActive) {
                delay(displayCadenceMs)
                synchronized(bufferLock) {
                    publishUiIfNeeded(force = false)
                }
            }
        }

        val flushTicker = launch {
            while (isActive) {
                delay(flushIntervalMs)
                flushPendingWriters(force = false)
            }
        }

        try {
            for (entry in channel) {
                synchronized(bufferLock) {
                    processEntry(entry)
                    var drained = 0
                    while (drained < BATCH_SIZE) {
                        val next = channel.tryReceive().getOrNull() ?: break
                        processEntry(next)
                        drained++
                    }
                }
                if (!running.get()) break
            }
        } catch (_: CancellationException) {
            // normal cancellation
        } finally {
            uiTicker.cancel()
            flushTicker.cancel()
            synchronized(bufferLock) {
                while (true) {
                    val next = channel.tryReceive().getOrNull() ?: break
                    processEntry(next)
                }
                publishUiIfNeeded(force = true)
            }
            flushWriters()
        }
    }

    @VisibleForTesting
    fun resetForTest(testLogDir: File? = null) {
        stop()
        synchronized(bufferLock) {
            allBuf.clear()
            backBuf.clear()
            vulkBuf.clear()
            appBuf.clear()
            uiDirty = false
        }
        _logs.value = emptyList()
        _backendLogs.value = emptyList()
        _vulkanLogs.value = emptyList()
        _appLogs.value = emptyList()
        writers.values.forEach { runCatching { it.close() } }
        writers.clear()
        writeSizes.clear()
        lastFlushAtMs.clear()
        droppedDebugCount.set(0L)
        entryIdCounter = 0L
        logDir = testLogDir
        if (testLogDir != null) {
            openWriters()
        }
        running.set(true)
    }

    @VisibleForTesting
    fun setConsumerChannelForTest(ch: Channel<LogEntry>) {
        channel = ch
        running.set(true)
    }
}
