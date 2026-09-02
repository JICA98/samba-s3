package com.zenithblue.sambas3.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Base64
import com.zenithblue.sambas3.BuildConfig
import com.zenithblue.sambas3.Digital2Flags
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.LogMonitor
import com.zenithblue.sambas3.PrecompilerService
import com.zenithblue.sambas3.PrecompilerServiceAction
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.gameconfig.SettingsBackendAudit
import com.zenithblue.sambas3.utils.FileUtil
import org.json.JSONObject

/**
 * Agent ADB bridge for controller injection — no coordinate taps.
 *
 * Register in MainActivity/RPCSXActivity onCreate; handles:
 *  - com.zenithblue.sambas3.DEBUG_PAD  with extras d1,d2,lx,ly,rx,ry (ints)
 *  - com.zenithblue.sambas3.DEBUG_PAD_CROSS (+ CIRCLE/SQUARE/TRIANGLE/START/SELECT/PS/L1/R1/L2/R2/UP/DOWN/LEFT/RIGHT/L3/R3)
 *    → press 120ms then release (deterministic for loop scripts).
 *
 * Example:
 *  adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_CROSS
 *  adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD --ei d2 64 --ei lx 127
 * Seen in LogMonitor as tag "DebugPad" → routed to BACKEND (via RPCSX-UI? actually uses Log.w).
 */
class DebugPadReceiver(private val onDebugFatal: (() -> Unit)? = null) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_FATAL) {
            if (BuildConfig.DEBUG) onDebugFatal?.invoke()
            else Log.w("DebugPad", "DEBUG_FATAL ignored in non-debug build")
            return
        }
        if (action == ACTION_LOG_MONITOR_START || action == ACTION_LOG_MONITOR_STOP) {
            if (!BuildConfig.DEBUG) {
                Log.w("DebugPad", "$action ignored in non-debug build")
                return
            }
            if (action == ACTION_LOG_MONITOR_START) LogMonitor.start(context ?: return)
            else LogMonitor.stop()
            Log.i("S3BENCH", "log_monitor=${if (action == ACTION_LOG_MONITOR_START) "on" else "off"}")
            return
        }
        if (action == ACTION_BENCH_START || action == ACTION_BENCH_STOP) {
            if (BuildConfig.DEBUG) {
                if (action == ACTION_BENCH_START) BenchmarkDebugController.start()
                else BenchmarkDebugController.stop()
            } else {
                Log.w("DebugPad", "$action ignored in non-debug build")
            }
            return
        }
        if (action.startsWith(ACTION_SETTINGS_PREFIX)) {
            handleSettingsProbe(intent)
            return
        }
        if (action == ACTION_REMOVE_GAME || action == ACTION_INSTALL_FILE) {
            handleLibraryProbe(context, intent)
            return
        }
        when {
            action == ACTION_PAD -> {
                val d1 = intent.getIntExtra("d1", 0)
                val d2 = intent.getIntExtra("d2", 0)
                val lx = intent.getIntExtra("lx", 127)
                val ly = intent.getIntExtra("ly", 127)
                val rx = intent.getIntExtra("rx", 127)
                val ry = intent.getIntExtra("ry", 127)
                Log.w("DebugPad", "PAD d1=$d1 d2=$d2 lx=$lx ly=$ly rx=$rx ry=$ry")
                RPCSX.instance.overlayPadData(d1, d2, lx, ly, rx, ry)
            }
            action.startsWith(PREFIX) -> {
                val suffix = action.removePrefix(PREFIX)
                val (d1, d2) = buttonToBits(suffix) ?: run {
                    Log.w("DebugPad", "unknown button $suffix")
                    return
                }
                Log.w("DebugPad", "BUTTON $suffix d1=$d1 d2=$d2 press 120ms")
                RPCSX.instance.overlayPadData(d1, d2, 127, 127, 127, 127)
                Handler(Looper.getMainLooper()).postDelayed({
                    RPCSX.instance.overlayPadData(0, 0, 127, 127, 127, 127)
                    Log.w("DebugPad", "BUTTON $suffix release")
                }, 120)
            }
        }
    }

    private fun handleLibraryProbe(context: Context?, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w("S3LIB_HARNESS", "ignored in non-debug build")
            return
        }
        val ctx = context?.applicationContext ?: return
        val action = intent.action ?: return
        when (action) {
            ACTION_REMOVE_GAME -> {
                val title = intent.getStringExtra("title")?.trim().orEmpty()
                if (title.isEmpty()) {
                    Log.w("S3LIB_HARNESS", "remove missing title=")
                    return
                }
                val game = GameRepository.list().firstOrNull { g ->
                    val path = g.info.path
                    path.substringAfterLast('/').equals(title, ignoreCase = true) ||
                        path.contains(title, ignoreCase = true)
                }
                if (game == null) {
                    Log.w("S3LIB_HARNESS", "remove title=$title not_found")
                    return
                }
                FileUtil.removeGame(ctx, game) { ok ->
                    Log.i("S3LIB_HARNESS", "remove title=$title ok=$ok path=${game.info.path}")
                }
            }
            ACTION_INSTALL_FILE -> {
                val uriExtra = intent.getStringExtra("uri")?.trim().orEmpty()
                val path = intent.getStringExtra("path")?.trim().orEmpty()
                val uri = when {
                    uriExtra.isNotEmpty() -> android.net.Uri.parse(uriExtra)
                    path.isNotEmpty() -> {
                        val file = java.io.File(path)
                        if (!file.isFile) {
                            Log.w("S3LIB_HARNESS", "install path=$path not_file")
                            return
                        }
                        android.net.Uri.fromFile(file)
                    }
                    else -> {
                        Log.w("S3LIB_HARNESS", "install missing path=/uri=")
                        return
                    }
                }
                PrecompilerService.start(ctx, PrecompilerServiceAction.Install, uri)
                Log.i("S3LIB_HARNESS", "install started uri=$uri")
            }
        }
    }

    private fun handleSettingsProbe(intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w("S3CFG_HARNESS", "ignored in non-debug build")
            return
        }
        val action = intent.action ?: return
        val path = intent.getStringExtra("path_b64")?.let {
            runCatching { String(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull()
        } ?: intent.getStringExtra("path") ?: "@@Video@@Frame limit"
        val title = intent.getStringExtra("title") ?: "BLUS31584"
        when (action) {
            ACTION_SETTINGS_READ_GLOBAL -> {
                Log.i("S3CFG_HARNESS", "global path=$path value=${RPCSX.instance.settingsGetGlobal(path)}")
            }
            ACTION_SETTINGS_READ_GLOBAL_TREE -> {
                val encoded = Base64.encodeToString(
                    RPCSX.instance.settingsGetGlobal("").toByteArray(),
                    Base64.NO_WRAP
                )
                val chunkSize = 2400
                encoded.chunked(chunkSize).forEachIndexed { index, chunk ->
                    Log.i("S3CFG_TREE", "chunk=$index total=${(encoded.length + chunkSize - 1) / chunkSize} data=$chunk")
                }
            }
            ACTION_SETTINGS_SCHEMA_AUDIT -> {
                val audit = SettingsBackendAudit.audit(
                    JSONObject(RPCSX.instance.settingsGetGlobal(""))
                )
                Log.i(
                    "S3CFG_HARNESS",
                    "schema ${SettingsBackendAudit.compactLog(audit)} valid=${audit.isValid}"
                )
            }
            ACTION_SETTINGS_READ_EFFECTIVE -> {
                Log.i(
                    "S3CFG_HARNESS",
                    "effective title=$title path=$path value=${RPCSX.instance.settingsGetEffective(title, path)}"
                )
            }
            ACTION_SETTINGS_READ_OVERRIDES -> {
                Log.i(
                    "S3CFG_HARNESS",
                    "overrides title=$title value=${RPCSX.instance.gameSettingsOverridesGet(title)}"
                )
            }
            ACTION_SETTINGS_WRITE_GLOBAL -> {
                val rawValue = intent.getStringExtra("value") ?: return
                val value = encodeProbeValue(path, rawValue)
                val ok = RPCSX.instance.settingsSetGlobalAndVerify(path, value)
                Log.i(
                    "S3CFG_HARNESS",
                    "write scope=global path=$path requested=$value ok=$ok readBack=${RPCSX.instance.settingsGetGlobal(path)}"
                )
            }
            ACTION_SETTINGS_WRITE_GAME -> {
                val rawValue = intent.getStringExtra("value") ?: return
                val value = encodeProbeValue(path, rawValue)
                val ok = RPCSX.instance.gameSettingsOverrideSet(title, path, value)
                Log.i(
                    "S3CFG_HARNESS",
                    "write scope=game title=$title path=$path requested=$value ok=$ok overrides=${RPCSX.instance.gameSettingsOverridesGet(title)}"
                )
            }
            ACTION_SETTINGS_CLEAR_GAME -> {
                val ok = RPCSX.instance.gameSettingsOverrideClear(title, path)
                Log.i(
                    "S3CFG_HARNESS",
                    "clear scope=game title=$title path=$path ok=$ok overrides=${RPCSX.instance.gameSettingsOverridesGet(title)}"
                )
            }
            ACTION_SETTINGS_CLEAR_ALL -> {
                val ok = RPCSX.instance.gameSettingsOverridesClear(title)
                Log.i("S3CFG_HARNESS", "clear-all scope=game title=$title ok=$ok")
            }
        }
    }

    private fun encodeProbeValue(path: String, rawValue: String): String {
        val type = runCatching {
            JSONObject(RPCSX.instance.settingsGetGlobal(path)).optString("type")
        }.getOrDefault("")
        return if (type == "enum" || type == "string") JSONObject.quote(rawValue) else rawValue
    }

    private fun buttonToBits(name: String): Pair<Int, Int>? = when (name.uppercase()) {
        "CROSS" -> 0 to Digital2Flags.CELL_PAD_CTRL_CROSS.bit
        "CIRCLE" -> 0 to Digital2Flags.CELL_PAD_CTRL_CIRCLE.bit
        "SQUARE" -> 0 to Digital2Flags.CELL_PAD_CTRL_SQUARE.bit
        "TRIANGLE" -> 0 to Digital2Flags.CELL_PAD_CTRL_TRIANGLE.bit
        "L1" -> 0 to Digital2Flags.CELL_PAD_CTRL_L1.bit
        "R1" -> 0 to Digital2Flags.CELL_PAD_CTRL_R1.bit
        "L2" -> 0 to Digital2Flags.CELL_PAD_CTRL_L2.bit
        "R2" -> 0 to Digital2Flags.CELL_PAD_CTRL_R2.bit
        "START" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_START.bit to 0
        "SELECT" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_SELECT.bit to 0
        "PS" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_PS.bit to 0
        "UP" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_UP.bit to 0
        "DOWN" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_DOWN.bit to 0
        "LEFT" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_LEFT.bit to 0
        "RIGHT" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_RIGHT.bit to 0
        "L3" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_L3.bit to 0
        "R3" -> com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_R3.bit to 0
        else -> null
    }

    companion object {
        const val ACTION_PAD = "com.zenithblue.sambas3.DEBUG_PAD"
        const val ACTION_FATAL = "com.zenithblue.sambas3.DEBUG_FATAL"
        const val ACTION_LOG_MONITOR_START = "com.zenithblue.sambas3.DEBUG_LOG_MONITOR_START"
        const val ACTION_LOG_MONITOR_STOP = "com.zenithblue.sambas3.DEBUG_LOG_MONITOR_STOP"
        const val ACTION_BENCH_START = "com.zenithblue.sambas3.DEBUG_BENCH_START"
        const val ACTION_BENCH_STOP = "com.zenithblue.sambas3.DEBUG_BENCH_STOP"
        const val PREFIX = "com.zenithblue.sambas3.DEBUG_PAD_"
        const val ACTION_SETTINGS_PREFIX = "com.zenithblue.sambas3.DEBUG_SETTINGS_"
        const val ACTION_SETTINGS_READ_GLOBAL = ACTION_SETTINGS_PREFIX + "READ_GLOBAL"
        const val ACTION_SETTINGS_READ_GLOBAL_TREE = ACTION_SETTINGS_PREFIX + "READ_GLOBAL_TREE"
        const val ACTION_SETTINGS_SCHEMA_AUDIT = ACTION_SETTINGS_PREFIX + "SCHEMA_AUDIT"
        const val ACTION_SETTINGS_READ_EFFECTIVE = ACTION_SETTINGS_PREFIX + "READ_EFFECTIVE"
        const val ACTION_SETTINGS_READ_OVERRIDES = ACTION_SETTINGS_PREFIX + "READ_OVERRIDES"
        const val ACTION_SETTINGS_WRITE_GLOBAL = ACTION_SETTINGS_PREFIX + "WRITE_GLOBAL"
        const val ACTION_SETTINGS_WRITE_GAME = ACTION_SETTINGS_PREFIX + "WRITE_GAME"
        const val ACTION_SETTINGS_CLEAR_GAME = ACTION_SETTINGS_PREFIX + "CLEAR_GAME"
        const val ACTION_SETTINGS_CLEAR_ALL = ACTION_SETTINGS_PREFIX + "CLEAR_ALL"
        /** Debug-only: remove an imported title via FileUtil.removeGame (same path as UI). */
        const val ACTION_REMOVE_GAME = "com.zenithblue.sambas3.DEBUG_REMOVE_GAME"
        /** Debug-only: start PrecompilerService Install for a filesystem ISO/folder path. */
        const val ACTION_INSTALL_FILE = "com.zenithblue.sambas3.DEBUG_INSTALL_FILE"

        fun register(context: Context, onDebugFatal: (() -> Unit)? = null): DebugPadReceiver {
            val r = DebugPadReceiver(onDebugFatal)
            val f = IntentFilter().apply {
                addAction(ACTION_PAD)
                addAction(ACTION_FATAL)
                addAction(ACTION_LOG_MONITOR_START)
                addAction(ACTION_LOG_MONITOR_STOP)
                addAction(ACTION_BENCH_START)
                addAction(ACTION_BENCH_STOP)
                addAction(ACTION_SETTINGS_READ_GLOBAL)
                addAction(ACTION_SETTINGS_READ_GLOBAL_TREE)
                addAction(ACTION_SETTINGS_SCHEMA_AUDIT)
                addAction(ACTION_SETTINGS_READ_EFFECTIVE)
                addAction(ACTION_SETTINGS_READ_OVERRIDES)
                addAction(ACTION_SETTINGS_WRITE_GLOBAL)
                addAction(ACTION_SETTINGS_WRITE_GAME)
                addAction(ACTION_SETTINGS_CLEAR_GAME)
                addAction(ACTION_SETTINGS_CLEAR_ALL)
                addAction(ACTION_REMOVE_GAME)
                addAction(ACTION_INSTALL_FILE)
                addAction(PREFIX + "CROSS")
                addAction(PREFIX + "CIRCLE")
                addAction(PREFIX + "SQUARE")
                addAction(PREFIX + "TRIANGLE")
                addAction(PREFIX + "L1")
                addAction(PREFIX + "R1")
                addAction(PREFIX + "L2")
                addAction(PREFIX + "R2")
                addAction(PREFIX + "START")
                addAction(PREFIX + "SELECT")
                addAction(PREFIX + "PS")
                addAction(PREFIX + "UP")
                addAction(PREFIX + "DOWN")
                addAction(PREFIX + "LEFT")
                addAction(PREFIX + "RIGHT")
                addAction(PREFIX + "L3")
                addAction(PREFIX + "R3")
            }
            context.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            Log.i("DebugPad", "registered")
            return r
        }
    }
}

/**
 * Debug-only sampler for reproducible performance runs. The native frame
 * counter remains gated inside the core; this reads one already-aggregated
 * snapshot per second and emits no overlay or Compose work.
 */
private object BenchmarkDebugController {
    private const val INTERVAL_MS = 1_000L
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private val sample = object : Runnable {
        override fun run() {
            if (!active) return
            logSnapshot()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (active) return
        active = true
        runCatching { RPCSX.instance.setPerfMetricsEnabled(true, INTERVAL_MS.toInt()) }
            .onFailure { Log.w("S3BENCH", "start failed: ${it.message}") }
        Log.i("S3BENCH", "started source=emu_flip interval_ms=$INTERVAL_MS")
        handler.post(sample)
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(sample)
        runCatching { RPCSX.instance.setPerfMetricsEnabled(false, INTERVAL_MS.toInt()) }
        Log.i("S3BENCH", "stopped elapsed_ms=${SystemClock.elapsedRealtime()}")
    }

    private fun logSnapshot() {
        val raw = runCatching { RPCSX.instance.getPerfMetricsJson() }.getOrNull()
        if (raw.isNullOrBlank()) {
            Log.i("S3BENCH", "elapsed_ms=${SystemClock.elapsedRealtime()} state=unavailable")
            return
        }
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json == null) {
            Log.i("S3BENCH", "elapsed_ms=${SystemClock.elapsedRealtime()} state=malformed")
            return
        }
        fun value(name: String): String = if (json.has(name) && !json.isNull(name)) {
            json.opt(name)?.toString() ?: "null"
        } else "null"
        Log.i(
            "S3BENCH",
            "elapsed_ms=${SystemClock.elapsedRealtime()} state=ready " +
                "fps=${value("fps")} frametime_ms=${value("frametimeMs")} " +
                "presented=${value("presentedFrameCount")} vblank_delta=${value("vblankDelta")} " +
                "host_cpu=${value("hostCpu")} ppu_cpu=${value("ppuCpu")} " +
                "spu_cpu=${value("spuCpu")} rsx_cpu=${value("rsxCpu")} rsx_load=${value("rsxLoad")}"
        )
    }
}
