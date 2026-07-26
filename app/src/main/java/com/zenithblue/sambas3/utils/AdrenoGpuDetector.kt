package com.zenithblue.sambas3.utils

import android.os.Build
import android.util.Log
import java.io.File

/**
 * Best-effort Adreno GPU family / model detection for filtering bundled drivers.
 * Never throws; uncertain results keep the system driver as the safe default.
 */
object AdrenoGpuDetector {
    private const val TAG = "AdrenoGpuDetector"

    private val GPU_MODEL_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_model",
        "/sys/devices/platform/kgsl-3d0.0/kgsl/kgsl-3d0/gpu_model",
        "/sys/devices/soc*/kgsl/kgsl-3d0/gpu_model",
    )

    fun detect(): AdrenoGpuInfo {
        val isArm64 = Build.SUPPORTED_ABIS.any {
            it.equals("arm64-v8a", ignoreCase = true) || it.equals("aarch64", ignoreCase = true)
        }

        val raw = readGpuModel()
            ?: listOf(
                Build.HARDWARE,
                Build.BOARD,
                Build.SOC_MODEL.takeIf { Build.VERSION.SDK_INT >= 31 },
                System.getProperty("ro.hardware.vulkan"),
                System.getProperty("ro.chipname"),
            ).filterNotNull().joinToString(" ").ifBlank { null }

        val gpuId = extractGpuId(raw)
        val family = familyFromGpuId(gpuId) ?: familyFromText(raw)
        val isAdreno = family != GpuFamily.UNKNOWN ||
            (raw?.contains("adreno", ignoreCase = true) == true) ||
            (raw?.contains("kgsl", ignoreCase = true) == true) ||
            File("/dev/kgsl-3d0").exists()

        Log.i(TAG, "GPU detect raw=$raw id=$gpuId family=$family adreno=$isAdreno arm64=$isArm64")

        return AdrenoGpuInfo(
            gpuId = gpuId,
            family = family,
            rawModel = raw,
            isAdreno = isAdreno,
            isArm64 = isArm64,
        )
    }

    fun isCompatible(entry: BundledGpuDriverEntry, info: AdrenoGpuInfo): Boolean {
        if (!info.isArm64) return false
        if (!info.isAdreno) return false

        val byId = entry.supportedGpuIds
        if (byId.isNotEmpty()) {
            val id = info.gpuId ?: return false
            return byId.any { it.equals(id, ignoreCase = true) }
        }

        val families = entry.supportedGpuFamilies.map { it.lowercase() }
        if (families.isEmpty()) return true
        return when (info.family) {
            GpuFamily.ADRENO_6XX -> families.any { it.contains("6") || it == "adreno6xx" }
            GpuFamily.ADRENO_7XX -> families.any { it.contains("7") || it == "adreno7xx" }
            GpuFamily.ADRENO_8XX -> families.any { it.contains("8") || it == "adreno8xx" }
            GpuFamily.UNKNOWN -> false
        }
    }

    fun shouldForceSysmem(entry: BundledGpuDriverEntry, info: AdrenoGpuInfo): Boolean {
        val id = info.gpuId ?: return false
        return entry.forceSysmemGpuIds.any { it.equals(id, ignoreCase = true) }
    }

    private fun readGpuModel(): String? {
        for (path in GPU_MODEL_PATHS) {
            if (path.contains("*")) {
                // Glob-style: walk parent if needed
                val parent = File(path.substringBeforeLast("/")).parentFile ?: continue
                parent.walkTopDown().maxDepth(4).forEach { f ->
                    if (f.name == "gpu_model" && f.isFile) {
                        runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
                    }
                }
                continue
            }
            val f = File(path)
            if (f.isFile) {
                runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    internal fun extractGpuId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Adreno (TM) 740, Adreno740, A740, GPUID: 43050a01 style — prefer 3-digit model
        val patterns = listOf(
            Regex("""(?i)adreno\s*\(?\s*TM\s*\)?\s*([0-9]{3,4})"""),
            Regex("""(?i)adreno\s*([0-9]{3,4})"""),
            Regex("""(?i)\bA([0-9]{3,4})\b"""),
            Regex("""(?i)gpu[_ ]?model\s*[:=]?\s*adreno\s*([0-9]{3,4})"""),
        )
        for (p in patterns) {
            val m = p.find(raw)
            if (m != null) return m.groupValues[1]
        }
        // Bare 3-digit when string is essentially the model
        val bare = Regex("""\b([6-8][0-9]{2})\b""").find(raw)
        return bare?.groupValues?.get(1)
    }

    internal fun familyFromGpuId(gpuId: String?): GpuFamily? {
        val id = gpuId?.toIntOrNull() ?: return null
        return when (id) {
            in 600..699 -> GpuFamily.ADRENO_6XX
            in 700..799 -> GpuFamily.ADRENO_7XX
            in 800..899 -> GpuFamily.ADRENO_8XX
            else -> null
        }
    }

    private fun familyFromText(raw: String?): GpuFamily {
        if (raw.isNullOrBlank()) return GpuFamily.UNKNOWN
        val lower = raw.lowercase()
        return when {
            lower.contains("adreno") && Regex("""\b8[0-9]{2}\b""").containsMatchIn(lower) -> GpuFamily.ADRENO_8XX
            lower.contains("adreno") && Regex("""\b7[0-9]{2}\b""").containsMatchIn(lower) -> GpuFamily.ADRENO_7XX
            lower.contains("adreno") && Regex("""\b6[0-9]{2}\b""").containsMatchIn(lower) -> GpuFamily.ADRENO_6XX
            else -> GpuFamily.UNKNOWN
        }
    }
}
