package com.zenithblue.sambas3

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.logging.toLegacySource
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val MAX_UI_ENTRIES = 1000
    private const val CHANNEL_CAPACITY = 8192
    private const val BATCH_SIZE = 20

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _backendLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val backendLogs: StateFlow<List<LogEntry>> = _backendLogs.asStateFlow()

    private val _vulkanLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val vulkanLogs: StateFlow<List<LogEntry>> = _vulkanLogs.asStateFlow()

    private val _appLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val appLogs: StateFlow<List<LogEntry>> = _appLogs.asStateFlow()

    private var scope: CoroutineScope? = null
    private var logcatProcess: Process? = null
    private val running = AtomicBoolean(false)
    private var logDir: File? = null

    private val writers = mutableMapOf<LogFileCategory, BufferedWriter>()
    private val writeSizes = mutableMapOf<LogFileCategory, Long>()
    private var entryIdCounter = 0L

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
            engine.addSink { ingest(it) }
            Log.i(TAG, "LogMonitor attached to broker — log dir: ${logDir?.absolutePath}")
        }
    }

    /**
     * Collection is process-lifetime. Stop only flushes so Settings/Logs
     * leaving the composition cannot empty the next crash report.
     */
    fun stop() {
        flushWriters()
    }

    fun clearLogs() {
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

    /** Flush open writers so share/export sees latest lines. */
    fun flushWriters() {
        writers.values.forEach { runCatching { it.flush() } }
    }

    private fun ingest(entry: com.zenithblue.sambas3.logging.UnifiedLogEntry) {
        val mapped = LogEntry(
            id = entry.sequence,
            timestamp = entry.timestampText ?: "",
            level = entry.level,
            tag = entry.tag ?: entry.source.name,
            message = entry.message,
            source = entry.source.toLegacySource(),
        )
        val cat = mapped.source.fileCategory()
        rotateIfNeeded(cat)
        writers[cat]?.let { w ->
            val line = "[${mapped.timestamp}] ${mapped.level.letter}/${mapped.tag}: ${mapped.message}\n"
            runCatching {
                w.write(line)
                writeSizes[cat] = (writeSizes[cat] ?: 0L) + line.length
                if (mapped.id % 16L == 0L) w.flush()
            }
        }
        val all = _logs.value
        val nextAll = if (all.size >= MAX_UI_ENTRIES) all.drop(all.size - MAX_UI_ENTRIES + 1) + mapped else all + mapped
        _logs.value = nextAll
        when (cat) {
            LogFileCategory.BACKEND -> {
                val cur = _backendLogs.value
                _backendLogs.value = if (cur.size >= MAX_UI_ENTRIES) cur.drop(1) + mapped else cur + mapped
            }
            LogFileCategory.VULKAN -> {
                val cur = _vulkanLogs.value
                _vulkanLogs.value = if (cur.size >= MAX_UI_ENTRIES) cur.drop(1) + mapped else cur + mapped
            }
            LogFileCategory.APP -> {
                val cur = _appLogs.value
                _appLogs.value = if (cur.size >= MAX_UI_ENTRIES) cur.drop(1) + mapped else cur + mapped
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
            _logs.value = hydrated.takeLast(MAX_UI_ENTRIES)
            _backendLogs.value = hydrated.filter { it.source.fileCategory() == LogFileCategory.BACKEND }.takeLast(MAX_UI_ENTRIES)
            _vulkanLogs.value = hydrated.filter { it.source.fileCategory() == LogFileCategory.VULKAN }.takeLast(MAX_UI_ENTRIES)
            _appLogs.value = hydrated.filter { it.source.fileCategory() == LogFileCategory.APP }.takeLast(MAX_UI_ENTRIES)
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

    private suspend fun runLogcatReader(channel: Channel<LogEntry>) {
        // threadtime format: MM-DD HH:MM:SS.mmm  PID  TID  LEVEL TAG : MSG
        val linePattern = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:\s*(.*)$"""
        )

        while (running.get() && currentCoroutineContext().isActive) {
            try {
                val proc = ProcessBuilder(
                    "logcat", "-v", "threadtime", "-b", "main,crash,system"
                ).redirectErrorStream(true).start()
                logcatProcess = proc

                proc.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null && running.get() && currentCoroutineContext().isActive) {
                        val l = line ?: continue
                        val match = linePattern.matchEntire(l) ?: continue
                        val (ts, lvl, tag, msg) = match.destructured
                        val entry = LogEntry(
                            id = ++entryIdCounter,
                            timestamp = ts,
                            level = LogLevel.fromChar(lvl[0]),
                            tag = tag.trim(),
                            message = msg,
                            source = classifyTag(tag.trim()),
                        )
                        channel.trySend(entry)
                    }
                }
                proc.destroy()
            } catch (e: Exception) {
                if (currentCoroutineContext().isActive && running.get()) {
                    Log.w(TAG, "logcat reader restarting: ${e.message}")
                    delay(2000)
                }
            }
        }
        channel.close()
    }

    private suspend fun runConsumer(channel: Channel<LogEntry>) {
        val allBuf = ArrayDeque<LogEntry>(MAX_UI_ENTRIES + 1)
        val backBuf = ArrayDeque<LogEntry>(MAX_UI_ENTRIES + 1)
        val vulkBuf = ArrayDeque<LogEntry>(MAX_UI_ENTRIES + 1)
        val appBuf = ArrayDeque<LogEntry>(MAX_UI_ENTRIES + 1)

        fun <T> ArrayDeque<T>.addCapped(item: T) {
            addLast(item)
            if (size > MAX_UI_ENTRIES) removeAt(0)
        }

        var batchCount = 0

        for (entry in channel) {
            allBuf.addCapped(entry)
            when (entry.source.fileCategory()) {
                LogFileCategory.BACKEND -> backBuf.addCapped(entry)
                LogFileCategory.VULKAN -> vulkBuf.addCapped(entry)
                LogFileCategory.APP -> appBuf.addCapped(entry)
            }

            val cat = entry.source.fileCategory()
            rotateIfNeeded(cat)
            writers[cat]?.let { w ->
                val line = "[${entry.timestamp}] ${entry.level.letter}/${entry.tag}: ${entry.message}\n"
                runCatching {
                    w.write(line)
                    writeSizes[cat] = (writeSizes[cat] ?: 0L) + line.length
                }
            }

            batchCount++
            if (batchCount >= BATCH_SIZE || channel.isEmpty) {
                writers.values.forEach { runCatching { it.flush() } }
                _logs.value = allBuf.toList()
                _backendLogs.value = backBuf.toList()
                _vulkanLogs.value = vulkBuf.toList()
                _appLogs.value = appBuf.toList()
                batchCount = 0
                yield()
            }
        }
    }
}
