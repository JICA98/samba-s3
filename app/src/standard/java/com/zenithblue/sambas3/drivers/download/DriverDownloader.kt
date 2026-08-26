package com.zenithblue.sambas3.drivers.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Robust streaming GET downloader.
 * - No mandatory HEAD
 * - No mandatory Content-Length
 * - No mandatory Accept-Ranges
 * - Uses .part file + atomic rename
 * - Verifies SHA256 when provided
 * - Supports cancellation
 */
object DriverDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    sealed class Result {
        data class Success(val file: File) : Result()
        data class Error(val message: String, val cause: Throwable? = null) : Result()
        data object Canceled : Result()
    }

    suspend fun download(
        url: String,
        destFile: File,
        expectedSha256: String? = null,
        progress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        val partFile = File(destFile.parentFile, destFile.name + ".part")
        try {
            // Ensure parent exists
            destFile.parentFile?.mkdirs()
            partFile.delete()

            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (!isActive) {
                    partFile.delete()
                    return@withContext Result.Canceled
                }
                if (!resp.isSuccessful) {
                    return@withContext Result.Error("HTTP ${resp.code} ${resp.message}")
                }
                val body = resp.body ?: return@withContext Result.Error("Empty body")
                val total = resp.header("Content-Length")?.toLongOrNull() // optional
                val input = body.byteStream()
                val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
                var bytesRead = 0L
                FileOutputStream(partFile).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (!isActive) {
                            input.close()
                            out.close()
                            partFile.delete()
                            throw CancellationException("Download canceled")
                        }
                        out.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        bytesRead += read
                        try { progress?.invoke(bytesRead, total) } catch (_: Exception) {}
                    }
                }
                input.close()

                // Verify SHA if provided
                if (expectedSha256 != null && digest != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        partFile.delete()
                        return@withContext Result.Error("SHA256 mismatch expected=$expectedSha256 actual=$actual")
                    }
                }

                // Atomic rename
                if (destFile.exists()) destFile.delete()
                val renamed = partFile.renameTo(destFile)
                if (!renamed) {
                    // Fallback copy
                    try {
                        partFile.copyTo(destFile, overwrite = true)
                        partFile.delete()
                    } catch (e: Exception) {
                        partFile.delete()
                        return@withContext Result.Error("Atomic rename failed: ${e.message}", e)
                    }
                }
                Log.i("DriverDownloader", "Download success $url -> ${destFile.path} bytes=$bytesRead total=$total")
                Result.Success(destFile)
            }
        } catch (e: CancellationException) {
            partFile.delete()
            Result.Canceled
        } catch (e: Exception) {
            Log.e("DriverDownloader", "Download failed $url: ${e.message}", e)
            try { partFile.delete() } catch (_: Exception) {}
            Result.Error(e.message ?: "Unknown error", e)
        }
    }
}
