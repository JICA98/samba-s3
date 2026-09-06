package com.zenithblue.sambas3.logging

import java.io.File

data class DiscoveredArtifact(
    val file: File,
    val kind: LogSourceKind,
    val compressed: Boolean,
)

object LogArtifactDiscovery {
    private val NAME_KINDS = listOf(
        Regex("(?i)^RPCSX\\.log.*") to LogSourceKind.RPCSX_BACKEND,
        Regex("(?i)^RPCS3\\.log.*") to LogSourceKind.RPCS3_CORE,
        Regex("(?i)^TTY\\.log.*") to LogSourceKind.RPCSX_BACKEND,
        Regex("(?i)vulkan") to LogSourceKind.VULKAN,
        Regex("(?i)(mesa|turnip|freedreno|gallium)") to LogSourceKind.TURNIP_GPU,
        Regex("(?i)crash") to LogSourceKind.CRASH,
        Regex("(?i)\\.log(\\.\\d+)?$") to LogSourceKind.OTHER,
        Regex("(?i)\\.log\\.gz$") to LogSourceKind.OTHER,
    )

    fun scan(
        roots: List<File>,
        sinceMs: Long? = null,
        maxFiles: Int = 40,
        maxDepth: Int = 3,
    ): List<DiscoveredArtifact> {
        val found = ArrayList<DiscoveredArtifact>(16)
        val seen = HashSet<String>()
        for (root in roots) {
            if (!root.exists()) continue
            walk(root, 0, maxDepth, sinceMs, found, seen, maxFiles)
            if (found.size >= maxFiles) break
        }
        return found
    }

    fun classify(file: File): LogSourceKind {
        val path = file.path
        val name = file.name
        return when {
            path.contains("${File.separator}shaderlog${File.separator}", ignoreCase = true) ||
                name.contains("shader", ignoreCase = true) -> LogSourceKind.SHADER
            path.contains("${File.separator}crash${File.separator}", ignoreCase = true) -> LogSourceKind.CRASH
            else -> NAME_KINDS.firstOrNull { it.first.containsMatchIn(name) }?.second ?: LogSourceKind.OTHER
        }
    }

    private fun walk(
        dir: File,
        depth: Int,
        maxDepth: Int,
        sinceMs: Long?,
        out: MutableList<DiscoveredArtifact>,
        seen: MutableSet<String>,
        maxFiles: Int,
    ) {
        if (out.size >= maxFiles || depth > maxDepth || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (out.size >= maxFiles) return
            if (child.isDirectory) {
                val interesting = child.name.equals("log", true) ||
                    child.name.equals("logs", true) ||
                    child.name.equals("cache", true) ||
                    child.name.equals("shaderlog", true) ||
                    child.name.equals("crash", true)
                if (interesting || depth == 0) {
                    walk(child, depth + 1, maxDepth, sinceMs, out, seen, maxFiles)
                }
                continue
            }
            if (!isCandidate(child)) continue
            if (sinceMs != null && child.lastModified() + 2_000L < sinceMs) continue
            val path = child.absolutePath
            if (!seen.add(path)) continue
            val compressed = child.name.endsWith(".gz", true)
            out.add(DiscoveredArtifact(child, classify(child), compressed))
        }
    }

    private fun isCandidate(file: File): Boolean {
        val name = file.name
        val path = file.absolutePath
        if (name.startsWith(".")) return false
        if (name.startsWith("rpcsx_app") || name.startsWith("rpcsx_backend") || name.startsWith("rpcsx_vulkan")) return false
        if (path.contains("/files/logs/") || path.endsWith("/files/logs")) return false
        if (name.endsWith(".so") || name.endsWith(".obj") || name.endsWith(".spv")) return false
        if (name.endsWith(".log", true) || name.endsWith(".log.gz", true)) return true
        if (Regex("(?i)\\.log\\.\\d+$").containsMatchIn(name)) return true
        return name.equals("RPCSX.log", true) || name.equals("RPCS3.log", true) || name.equals("TTY.log", true)
    }
}
