package com.zenithblue.sambas3

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.utils.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class PreRuntimePpuState { NOT_DONE, IN_PROGRESS, READY, INVALIDATED, FAILED }
/**
 * Runtime PPU readiness.
 * IDLE_AFTER_COMPILE alone is not validated readiness — only a real RPCSXActivity
 * boot that reaches a Runtime PPU terminal (when needed) plus stable first-frame
 * proof may set [PpuStateEntry.validatedByRealBootFrame].
 * Legacy headless IDLE entries load with validatedByRealBootFrame=false.
 */
enum class RuntimePpuState { NOT_STARTED, COMPILING, IDLE_AFTER_COMPILE, FAILED }

@Serializable
data class PpuStateEntry(
    val key: String,
    val preRuntime: String, // enum name
    val runtime: String,
    val fingerprint: String?,
    val validatedByRealBootFrame: Boolean = false,
    val readinessVersion: Int = 1,
    val updatedMs: Long = System.currentTimeMillis()
)

@Serializable
data class PpuStateFile(
    val version: Int = 2,
    val entries: Map<String, PpuStateEntry> = emptyMap()
)

object PpuReadinessStore {
    private const val FILE_NAME = "ppu_state.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private var cache: MutableMap<String, PpuStateEntry> = mutableMapOf()
    private var loaded = false

    /** Bumps when any readiness entry changes so Compose can recompose without polling. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun bumpRevision() {
        _revision.value = _revision.value + 1L
    }

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
            val key = try { RPCSX.instance.getPpuManifestKey(titleId ?: "") } catch (_: Throwable) { null }
            if (!key.isNullOrEmpty() && key != "unknown") return key
            val abi = "v7-kusa"
            val llvmCpu = try { RPCSX.instance.settingsGet("Core llvm_cpu") } catch (_: Throwable) { "unknown" }
            "$abi|$llvmCpu|${titleId ?: "unknown"}"
        } catch (_: Throwable) { null }
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

    /** Legacy IDLE_AFTER_COMPILE without this marker is not validated Runtime ready. */
    @Synchronized
    fun isRuntimeValidated(context: Context, key: String): Boolean {
        ensureLoaded(context)
        val entry = cache[key] ?: return false
        if (!entry.validatedByRealBootFrame) return false
        return try {
            RuntimePpuState.valueOf(entry.runtime) == RuntimePpuState.IDLE_AFTER_COMPILE
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun setPreRuntimeState(context: Context, key: String, state: PreRuntimePpuState, fingerprint: String? = null) {
        ensureLoaded(context)
        val prev = cache[key]?.preRuntime ?: PreRuntimePpuState.NOT_DONE.name
        val fp = fingerprint ?: fingerprint(context, key)
        val entry = cache[key]
        // Changing install phase clears any prior runtime validation.
        val clearValidation = state != PreRuntimePpuState.READY
        val newEntry = PpuStateEntry(
            key = key,
            preRuntime = state.name,
            runtime = if (clearValidation) RuntimePpuState.NOT_STARTED.name else (entry?.runtime ?: RuntimePpuState.NOT_STARTED.name),
            fingerprint = fp,
            validatedByRealBootFrame = if (clearValidation) false else (entry?.validatedByRealBootFrame ?: false),
            readinessVersion = entry?.readinessVersion ?: 1,
            updatedMs = System.currentTimeMillis()
        )
        cache[key] = newEntry
        save(context)
        bumpRevision()
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
        // Only markRuntimeValidatedByRealBoot may set validation true.
        val keepValidation = state == RuntimePpuState.IDLE_AFTER_COMPILE &&
            (entry?.validatedByRealBootFrame == true)
        val newEntry = PpuStateEntry(
            key = key,
            preRuntime = entry?.preRuntime ?: PreRuntimePpuState.NOT_DONE.name,
            runtime = state.name,
            fingerprint = entry?.fingerprint,
            validatedByRealBootFrame = keepValidation,
            readinessVersion = entry?.readinessVersion ?: 1,
            updatedMs = System.currentTimeMillis()
        )
        cache[key] = newEntry
        save(context)
        bumpRevision()
        if (Telemetry.isEnabled) Telemetry.emitPpuStateChange(key, "runtime", prev, state.name)
    }

    /**
     * Persist validated Runtime ready after real Activity boot + stable first frame.
     * Must not be called from headless prepare or the compile watchdog.
     */
    @Synchronized
    fun markRuntimeValidatedByRealBoot(context: Context, key: String) {
        ensureLoaded(context)
        val entry = cache[key]
        val newEntry = PpuStateEntry(
            key = key,
            preRuntime = entry?.preRuntime ?: PreRuntimePpuState.READY.name,
            runtime = RuntimePpuState.IDLE_AFTER_COMPILE.name,
            fingerprint = entry?.fingerprint ?: fingerprint(context, key),
            validatedByRealBootFrame = true,
            readinessVersion = 2,
            updatedMs = System.currentTimeMillis()
        )
        cache[key] = newEntry
        save(context)
        bumpRevision()
        if (Telemetry.isEnabled) {
            Telemetry.emitPpuStateChange(key, "runtime", entry?.runtime ?: "?", RuntimePpuState.IDLE_AFTER_COMPILE.name)
        }
        Log.i("PpuReadinessStore", "runtime validated by real boot frame title=$key")
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

    /**
     * BLOCKER F3: remove entry without JNI/fingerprint call.
     * Used by transactional removal — deleting a title must not call
     * getPpuManifestKey (global emulator state) for the removed title.
     */
    @Synchronized
    fun removeEntry(context: Context, key: String): Boolean {
        ensureLoaded(context)
        val existed = cache.remove(key) != null
        if (existed) {
            save(context)
            bumpRevision()
            if (Telemetry.isEnabled) Telemetry.emitPpuStateChange(key, "preruntime", "REMOVED", "REMOVED")
        }
        return existed
    }

    @Synchronized
    fun allEntries(context: Context): Map<String, PpuStateEntry> {
        ensureLoaded(context)
        return cache.toMap()
    }

    @Synchronized
    fun recoverInterruptedRuntimePreparations(
        context: Context
    ): List<String> {
        ensureLoaded(context)

        val recovered =
            mutableListOf<String>()

        val updated =
            cache.mapValues {
                    (key, entry) ->

                val runtime =
                    runCatching {
                        RuntimePpuState.valueOf(
                            entry.runtime
                        )
                    }.getOrDefault(
                        RuntimePpuState.NOT_STARTED
                    )

                if (
                    runtime ==
                    RuntimePpuState.COMPILING
                ) {
                    recovered += key

                    entry.copy(
                        runtime =
                            RuntimePpuState
                                .FAILED
                                .name,
                        updatedMs =
                            System.currentTimeMillis()
                    )
                } else {
                    entry
                }
            }.toMutableMap()

        if (
            recovered.isNotEmpty()
        ) {
            cache = updated

            save(context)
            bumpRevision()

            Log.w(
                "PpuReadinessStore",
                "Recovered interrupted runtime PPU " +
                    "preparations: $recovered"
            )
        }

        return recovered
    }
}
