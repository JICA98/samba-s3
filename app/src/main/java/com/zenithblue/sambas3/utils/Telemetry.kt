package com.zenithblue.sambas3.utils

import android.os.Build
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

object Telemetry {
    private const val TAG_S3LIFE = "S3LIFE"
    private const val TAG_S3LIB = "S3LIB"
    private const val TAG_S3PPU = "S3PPU"
    private const val TAG_S3UI = "S3UI"
    private const val TAG_S3DRV = "S3DRV"
    private const val TAG_S3PERF = "S3PERF"
    private const val MAX_JSONL_BYTES = 5 * 1024 * 1024L

    // Session S3-<epoch_ms>-<4hex>
    val sessionId: String by lazy {
        val ts = System.currentTimeMillis()
        val rnd = SecureRandom().nextInt(0x10000)
        String.format("S3-%d-%04x", ts, rnd)
    }

    private val jsonlFile: File? get() {
        if (!isEnabled) return null
        return try {
            val base = com.zenithblue.sambas3.RPCSX.rootDirectory.let { File(it).parentFile } ?: return null
            // Use external files perf dir if available, fallback to root/perf
            val dir = File(base, "perf")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "$sessionId.jsonl")
        } catch (_: Exception) { null }
    }

    private var jsonlBytes = AtomicLong(0)

    val isEnabled: Boolean
        get() = try {
            val debug = try { com.zenithblue.sambas3.BuildConfig.DEBUG } catch (_: Exception) { false }
            if (debug) return true
            (GeneralSettings[GeneralSettings.ENABLE_PERF_CAPTURE] as? Boolean) ?: false
        } catch (_: Exception) { false }

    private fun log(tag: String, msg: String) {
        if (!isEnabled) return
        Log.i(tag, msg)
        appendJsonl(tag, msg)
    }

    private fun appendJsonl(tag: String, msg: String) {
        if (!isEnabled) return
        try {
            val f = jsonlFile ?: return
            if (jsonlBytes.get() >= MAX_JSONL_BYTES) return
            // Simple key=value to JSON conversion: store raw msg as event string
            // Parse k=v pairs into json
            val map = mutableMapOf<String, String>()
            map["ts_ms"] = System.currentTimeMillis().toString()
            map["tag"] = tag
            // naive parse key=value
            val parts = msg.split(" ")
            for (p in parts) {
                val kv = p.split("=", limit = 2)
                if (kv.size == 2) map[kv[0]] = kv[1]
            }
            map["raw"] = msg
            val json = buildString {
                append("{")
                var first = true
                for ((k, v) in map) {
                    if (!first) append(",")
                    first = false
                    append("\"").append(k).append("\":\"").append(v.replace("\"", "\\\"")).append("\"")
                }
                append("}\n")
            }
            f.appendText(json)
            jsonlBytes.addAndGet(json.toByteArray().size.toLong())
        } catch (_: Exception) {
            // never crash emulator
        }
    }

    fun logS3Life(msg: String) = log(TAG_S3LIFE, msg)
    fun logS3Lib(msg: String) = log(TAG_S3LIB, msg)
    fun logS3Ppu(msg: String) = log(TAG_S3PPU, msg)
    fun logS3Ui(msg: String) = log(TAG_S3UI, msg)
    fun logS3Drv(msg: String) = log(TAG_S3DRV, msg)
    fun logS3Perf(msg: String) = log(TAG_S3PERF, msg)

    // Convenience helpers matching spec
    fun emitScanStart(treeUriHash: String) = logS3Lib("event=scan_start tree_uri_hash=$treeUriHash session=$sessionId")
    fun emitScanEnd(dirsSeen: Int, filesSeen: Int, isoSeen: Int, candidates: Int, elapsedMs: Long) =
        logS3Lib("event=scan_end dirs_seen=$dirsSeen files_seen=$filesSeen iso_seen=$isoSeen candidates=$candidates elapsed_ms=$elapsedMs session=$sessionId")

    fun emitIsoProbeStart(size: Long?) = logS3Lib("event=iso_probe_start size=${size ?: -1} session=$sessionId")
    fun emitIsoProbeEnd(titleId: String?, result: String, bytesRead: Long, elapsedMs: Long) =
        logS3Lib("event=iso_probe_end title_id=${titleId ?: "unknown"} result=$result bytes_read=$bytesRead elapsed_ms=$elapsedMs session=$sessionId")

    fun emitProgressAttach(progressId: Long, gameKey: String, cardKind: String) =
        logS3Ui("event=progress_attach progress_id=$progressId game_key=$gameKey card_kind=$cardKind session=$sessionId")
    fun emitIdentityMerge(fromKey: String, toKey: String, preservedProgress: Long) =
        logS3Ui("event=identity_merge from_key=$fromKey to_key=$toKey preserved_progress=$preservedProgress session=$sessionId")
    fun emitProgressDetach(progressId: Long, gameKey: String, reason: String) =
        logS3Ui("event=progress_detach progress_id=$progressId game_key=$gameKey reason=$reason session=$sessionId")
    fun emitPlaceholderCreated(progressId: Long) = logS3Ui("event=placeholder_created progress_id=$progressId session=$sessionId")
    fun emitDuplicateCardError(key: String, count: Int) = logS3Ui("event=duplicate_card_error key=$key count=$count session=$sessionId")

    fun emitPpuStateChange(titleId: String, plane: String, from: String, to: String) =
        logS3Ppu("event=state_change title_id=$titleId plane=$plane from=$from to=$to session=$sessionId")
    fun emitPpuInvalidate(titleId: String, reason: String) = logS3Ppu("event=invalidate title_id=$titleId reason=$reason session=$sessionId")
    fun emitFirmwarePpuUnexpected() = logS3Life("event=firmware_ppu_unexpected session=$sessionId")
}
