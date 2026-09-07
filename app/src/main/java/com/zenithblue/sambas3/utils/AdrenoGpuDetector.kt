package com.zenithblue.sambas3.utils

import android.os.Build
import android.util.Log
import java.io.File
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10

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

        val eglRenderer = queryGpuRendererFromEgl()

        val raw = eglRenderer
            ?: readGpuModel()
            ?: listOf(
                Build.HARDWARE,
                Build.BOARD,
                Build.SOC_MODEL.takeIf { Build.VERSION.SDK_INT >= 31 },
                System.getProperty("ro.hardware.vulkan"),
                System.getProperty("ro.chipname"),
            ).filterNotNull().joinToString(" ").ifBlank { null }

        val gpuId = extractGpuId(raw) ?: extractGpuId(readGpuModel())
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

    private fun queryGpuRendererFromEgl(): String? {
        return try {
            val egl = EGLContext.getEGL() as? EGL10 ?: return null
            val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (display == EGL10.EGL_NO_DISPLAY) return null
            if (!egl.eglInitialize(display, IntArray(2))) return null

            val configAttributes = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 0x4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_NONE,
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!egl.eglChooseConfig(display, configAttributes, configs, 1, numConfig) || numConfig[0] == 0) {
                egl.eglTerminate(display)
                return null
            }
            val config = configs[0] ?: run {
                egl.eglTerminate(display)
                return null
            }

            val contextAttributes = intArrayOf(0x3098, 2, EGL10.EGL_NONE) // EGL_CONTEXT_CLIENT_VERSION = 2
            val context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttributes)
            if (context == EGL10.EGL_NO_CONTEXT) {
                egl.eglTerminate(display)
                return null
            }

            val surface = egl.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE),
            )
            if (surface == EGL10.EGL_NO_SURFACE) {
                egl.eglDestroyContext(display, context)
                egl.eglTerminate(display)
                return null
            }

            egl.eglMakeCurrent(display, surface, surface, context)
            val gl = context.gl as GL10
            val renderer = gl.glGetString(GL10.GL_RENDERER)?.trim()

            egl.eglMakeCurrent(
                display,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_CONTEXT,
            )
            egl.eglDestroySurface(display, surface)
            egl.eglDestroyContext(display, context)
            egl.eglTerminate(display)

            renderer?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to query GPU renderer from EGL", t)
            null
        }
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
