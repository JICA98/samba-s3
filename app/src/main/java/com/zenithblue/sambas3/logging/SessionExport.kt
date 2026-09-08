package com.zenithblue.sambas3.logging

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SessionExportResult(
    val zip: File,
    val sessionId: String,
    val revision: Long,
    val membership: List<String>,
    val hashes: Map<String, String>,
    val livePartial: Boolean,
    val failures: List<String>,
)

object SessionExport {
    const val MIME_ZIP = "application/zip"

    fun export(
        context: Context,
        sessionId: String,
        revision: Long? = null,
        appContextExport: Boolean = false,
    ): SessionExportResult? {
        val app = context.applicationContext
        val manifest = LogSessionStore.read(app, sessionId) ?: return null
        if (revision != null && revision != manifest.revision) {
            val current = LogSessionStore.read(app, sessionId) ?: manifest
            if (current.revision != revision && current.revision < (revision)) return null
        }
        LogSessionStore.pin(sessionId)
        try {
            runCatching { com.zenithblue.sambas3.LogMonitor.flushWriters() }
            val dir = LogSessionStore.sessionDir(app, sessionId)
            val exportDir = File(app.cacheDir, "session_exports").apply { mkdirs() }
            val zip = File(exportDir, "session-$sessionId-r${manifest.revision}.zip")
            val membership = LinkedHashSet<String>()
            val hashes = LinkedHashMap<String, String>()
            val failures = ArrayList<String>()
            val livePartial = manifest.captureState == CaptureState.RECORDING ||
                manifest.captureState == CaptureState.FINALIZING ||
                manifest.captureState == CaptureState.RECOVERED_PARTIAL
            ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                zos.setMethod(ZipOutputStream.DEFLATED)
                fun putFile(name: String, file: File, role: String = "raw") {
                    if (!file.isFile) {
                        failures += "$name missing"
                        return
                    }
                    val entryName = name.trimStart('/')
                    if (!membership.add(entryName)) return
                    val digest = MessageDigest.getInstance("SHA-256")
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            digest.update(buf, 0, n)
                            zos.write(buf, 0, n)
                        }
                    }
                    zos.closeEntry()
                    hashes[entryName] = digest.digest().joinToString("") { "%02x".format(it) }
                    hashes["$entryName#role"] = role
                }

                putFile("manifest.json", File(dir, "manifest.json"), "manifest")
                File(dir, "lifecycle.jsonl").takeIf { it.isFile }?.let { putFile("lifecycle.jsonl", it, "lifecycle") }
                File(dir, "capture-health.json").takeIf { it.isFile }?.let { putFile("capture-health.json", it, "health") }

                for (artifact in manifest.artifacts) {
                    val relative = artifact.relativePath
                    val source = when {
                        !relative.isNullOrBlank() -> File(dir, relative)
                        artifact.sealed && File(artifact.path).isFile -> File(artifact.path)
                        else -> File(artifact.path)
                    }
                    val name = relative?.replace(File.separatorChar, '/')
                        ?: "raw/${artifact.id}/${artifact.originalFilename.ifBlank { File(artifact.path).name }}"
                    val role = artifact.role.ifBlank { if (artifact.compressed) "raw" else "raw" }
                    if (source.isFile) {
                        putFile(name, source, role)
                    } else {
                        failures += "${artifact.id} missing path=${artifact.path}"
                    }
                }

                val summary = JSONObject().apply {
                    put("sessionId", manifest.sessionId)
                    put("revision", manifest.revision)
                    put("outcome", SessionDiagnostics.project(manifest).outcome.name)
                    put("rawStopReason", manifest.stopReason ?: "")
                    put("captureState", manifest.captureState.name)
                    put("livePartial", livePartial)
                    put("appContextExport", appContextExport)
                    put("failures", JSONArray(failures))
                    put("membership", JSONArray(membership.toList()))
                    put("hashes", JSONObject(hashes.filterKeys { !it.endsWith("#role") } as Map<*, *>))
                }
                val summaryBytes = summary.toString(2).toByteArray(Charsets.UTF_8)
                membership.add("export-summary.json")
                hashes["export-summary.json"] = sha256(summaryBytes)
                zos.putNextEntry(ZipEntry("export-summary.json"))
                zos.write(summaryBytes)
                zos.closeEntry()
            }
            return SessionExportResult(zip, sessionId, manifest.revision, membership.toList(), hashes, livePartial, failures)
        } finally {
            LogSessionStore.unpin(sessionId)
        }
    }

    fun share(context: Context, result: SessionExportResult, title: String = "Share session logs") {
        val authority = "${context.packageName}.provider"
        val uri = FileProvider.getUriForFile(context, authority, result.zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_ZIP
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "session-export", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, title))
        } catch (_: Exception) {
            Toast.makeText(context, "No app available to handle sharing", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareSession(context: Context, sessionId: String, revision: Long? = null): Boolean {
        val result = runCatching { export(context, sessionId, revision) }.getOrNull()
        if (result == null) {
            Toast.makeText(context, "No log files available to share", Toast.LENGTH_SHORT).show()
            return false
        }
        share(context, result)
        return true
    }

    fun copyBytes(source: File, dest: File): Boolean {
        if (!source.isFile) return false
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        source.inputStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (dest.exists()) dest.delete()
        return tmp.renameTo(dest)
    }

    fun sha256(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
