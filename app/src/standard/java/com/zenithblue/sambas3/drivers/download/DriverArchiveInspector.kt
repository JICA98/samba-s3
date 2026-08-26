package com.zenithblue.sambas3.drivers.download

import android.util.Log
import com.zenithblue.sambas3.drivers.DriverBinaryValidator
import java.io.File

/**
 * Inspects extracted driver staging directory after archive extraction.
 * Locates Vulkan payload, validates ELF, and checks safety.
 */
object DriverArchiveInspector {

    private const val TAG = "DriverArchiveInspector"

    data class InspectionResult(
        val vulkanLib: File,
        val allSoFiles: List<File>,
        val metaFile: File?
    )

    fun inspect(stagingDir: File): Result<InspectionResult> {
        if (!stagingDir.isDirectory) return Result.failure(IllegalArgumentException("Not a directory"))
        // Find all .so files
        val soFiles = stagingDir.walkTopDown().filter { it.isFile && it.name.endsWith(".so", ignoreCase = true) }.toList()
        if (soFiles.isEmpty()) return Result.failure(IllegalStateException("No Vulkan library found"))
        // Prefer libvulkan_freedreno.so
        var vulkanLib = soFiles.find { it.name == "libvulkan_freedreno.so" }
        if (vulkanLib == null) {
            vulkanLib = soFiles.find { it.name.lowercase().contains("vulkan") && it.name.lowercase().endsWith(".so") }
        }
        if (vulkanLib == null) vulkanLib = soFiles.first()
        // Validate ELF via shared validator
        val driverDir = vulkanLib.parentFile ?: stagingDir
        val validation = DriverBinaryValidator.validate(driverDir, vulkanLib.name)
        if (!validation.ok) {
            return Result.failure(IllegalStateException("Invalid Vulkan ELF: ${validation.reason}"))
        }
        val meta = stagingDir.walkTopDown().firstOrNull { it.isFile && it.name == "meta.json" }
        Log.i(TAG, "Inspect ok lib=${vulkanLib.name} bytes=${vulkanLib.length()} soCount=${soFiles.size}")
        return Result.success(InspectionResult(vulkanLib, soFiles, meta))
    }

    fun isSafeEntry(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains("..")) return false
        if (name.contains("\\")) return false
        // Reject absolute and traversal
        return true
    }
}
