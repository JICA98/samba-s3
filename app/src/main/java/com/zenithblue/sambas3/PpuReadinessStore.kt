package com.zenithblue.sambas3

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.utils.Telemetry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class PreRuntimePpuState { NOT_DONE, IN_PROGRESS, READY, INVALIDATED, FAILED }
enum class RuntimePpuState { NOT_STARTED, COMPILING, IDLE_AFTER_COMPILE, FAILED }

@Serializable
data class PpuStateEntry(
    val key: String,
    val preRuntime: String, // enum name
    val runtime: String,
    val fingerprint: String?,
    val updatedMs: Long = System.currentTimeMillis()
)

@Serializable
data class PpuStateFile(
    val version: Int = 1,
    val entries: Map<String, PpuStateEntry> = emptyMap()
)

object PpuReadinessStore {
    private const val FILE_NAME = "ppu_state.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private var cache: MutableMap<String, PpuStateEntry> = mutableMapOf()
    private var loaded = false

    private fun file(context: Context): File {
        val dir = File(RPCSX.rootDirectory, "config/prefs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    @Synchronized
    fun load(context: Context) {
        try {
            val f = file(context)
            if (!f.exists()) {
                cache = mutableMapOf()
                loaded = true
                return
            }
            val data = json.decodeFromString<PpuStateFile>(f.readText())
            cache = data.entries.toMutableMap()
            loaded = true
        } catch (e: Exception) {
            Log.e("PpuReadinessStore", "load failed", e)
            cache = mutableMapOf()
            loaded = true
        }
    }

    @Synchronized
    fun save(context: Context) {
        try {
            val f = file(context)
            val data = PpuStateFile(entries = cache.toMap())
            f.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            Log.e("PpuReadinessStore", "save failed", e)
        }
    }

    private fun ensureLoaded(context: Context) {
        if (!loaded) load(context)
    }

    private fun fingerprint(context: Context, titleId: String?): String? {
        return try {
            val key = try { RPCSX.instance.getPpuManifestKey(titleId ?: "") } catch (_: Exception) { null }
            if (!key.isNullOrEmpty() && key != "unknown") return key
            val abi = "v7-kusa"
            val llvmCpu = try { RPCSX.instance.settingsGet("Core llvm_cpu") } catch (_: Exception) { "unknown" }
            "$abi|$llvmCpu|${titleId ?: "unknown"}"
        } catch (_: Exception) { null }
    }

    @Synchronized
    fun getPreRuntimeState(context: Context, key: String): PreRuntimePpuState {
        ensureLoaded(context)
        val entry = cache[key] ?: return PreRuntimePpuState.NOT_DONE
        return try { PreRuntimePpuState.valueOf(entry.preRuntime) } catch (_: Exception) { PreRuntimePpuState.NOT_DONE }
    }

    @Synchronized
    fun getRuntimeState(context: Context, key: String): RuntimePpuState {
        ensureLoaded(context)
        val entry = cache[key] ?: return RuntimePpuState.NOT_STARTED
        return try { RuntimePpuState.valueOf(entry.runtime) } catch (_: Exception) { RuntimePpuState.NOT_STARTED }
    }

    @Synchronized
    fun setPreRuntimeState(context: Context, key: String, state: PreRuntimePpuState, fingerprint: String? = null) {
        ensureLoaded(context)
        val prev = cache[key]?.preRuntime ?: PreRuntimePpuState.NOT_DONE.name
        val fp = fingerprint ?: fingerprint(context, key)
        val entry = cache[key]
        val newEntry = PpuStateEntry(
            key = key,
            preRuntime = state.name,
            runtime = entry?.runtime ?: RuntimePpuState.NOT_STARTED.name,
            fingerprint = fp,
            updatedMs = System.currentTimeMillis()
        )
        cache[key] = newEntry
        save(context)
        if (Telemetry.isEnabled) Telemetry.emitPpuStateChange(key, "preruntime", prev, state.name)
        if (state == PreRuntimePpuState.INVALIDATED && Telemetry.isEnabled) {
            Telemetry.emitPpuInvalidate(key, "fingerprint_mismatch")
        }
    }

    @Synchronized
    fun setRuntimeState(context: Context, key: String, state: RuntimePpuState) {
        ensureLoaded(context)
        val prev = cache[key]?.runtime ?: RuntimePpuState.NOT_STARTED.name
        val entry = cache[key]
        val newEntry = PpuStateEntry(
            key = key,
            preRuntime = entry?.preRuntime ?: PreRuntimePpuState.NOT_DONE.name,
            runtime = state.name,
            fingerprint = entry?.fingerprint,
            updatedMs = System.currentTimeMillis()
        )
        cache[key] = newEntry
        save(context)
        if (Telemetry.isEnabled) Telemetry.emitPpuStateChange(key, "runtime", prev, state.name)
    }

    @Synchronized
    fun invalidateIfFingerprintChanged(context: Context, key: String, currentFingerprint: String?): Boolean {
        ensureLoaded(context)
        val entry = cache[key] ?: return false
        val stored = entry.fingerprint
        if (stored != null && currentFingerprint != null && stored != currentFingerprint) {
            setPreRuntimeState(context, key, PreRuntimePpuState.INVALIDATED, currentFingerprint)
            return true
        }
        return false
    }

    @Synchronized
    fun markReadyIfNeeded(context: Context, key: String) {
        setPreRuntimeState(context, key, PreRuntimePpuState.READY)
    }

    @Synchronized
    fun allEntries(context: Context): Map<String, PpuStateEntry> {
        ensureLoaded(context)
        return cache.toMap()
    }
}
