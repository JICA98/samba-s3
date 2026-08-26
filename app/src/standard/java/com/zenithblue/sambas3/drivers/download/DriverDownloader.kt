package com.zenithblue.sambas3.drivers.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException

object DriverDownloader {

    private const val TAG = "DriverDownloader"

    // UI does not need one Compose update for every network buffer.
    private const val PROGRESS_INTERVAL_NS = 120_000_000L // ~8.3 Hz
    private const val PROGRESS_BYTES_STEP = 256L * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    sealed class Result {
        data class Success(val file: File) : Result()
        data class Error(
            val message: String,
            val cause: Throwable? = null
        ) : Result()
        data object Canceled : Result()
    }

    suspend fun download(
        url: String,
        destFile: File,
        expectedSha256: String? = null,
        progress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null,
        onCallCreated: ((Call) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {

        val partFile =
            File(destFile.parentFile, destFile.name + ".part")

        try {
            destFile.parentFile?.mkdirs()
            partFile.delete()

            val request =
                Request.Builder()
                    .url(url)
                    .get()
                    .build()

            val call = client.newCall(request)
            onCallCreated?.invoke(call)

            call.execute().use { response ->
                coroutineContext.ensureActive()

                if (!response.isSuccessful) {
                    return@withContext Result.Error(
                        "HTTP ${response.code} ${response.message}"
                    )
                }

                val body =
                    response.body
                        ?: return@withContext Result.Error("Empty body")

                val total =
                    response.header("Content-Length")
                        ?.toLongOrNull()

                val digest =
                    expectedSha256?.let {
                        MessageDigest.getInstance("SHA-256")
                    }

                var bytesRead = 0L
                var lastProgressBytes = 0L
                var lastProgressNs = 0L

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->

                        // Larger IO buffer reduces callback/loop overhead.
                        val buffer = ByteArray(256 * 1024)

                        while (true) {
                            coroutineContext.ensureActive()

                            val read = input.read(buffer)
                            if (read < 0) break

                            output.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)

                            bytesRead += read

                            val now = System.nanoTime()
                            val byteStepReached =
                                bytesRead - lastProgressBytes >=
                                    PROGRESS_BYTES_STEP

                            val timeStepReached =
                                now - lastProgressNs >=
                                    PROGRESS_INTERVAL_NS

                            val finalChunk =
                                total != null &&
                                    bytesRead >= total

                            if (
                                byteStepReached ||
                                timeStepReached ||
                                finalChunk
                            ) {
                                progress?.invoke(bytesRead, total)
                                lastProgressBytes = bytesRead
                                lastProgressNs = now
                            }
                        }

                        output.fd.sync()
                    }
                }

                if (
                    bytesRead !=
                    lastProgressBytes ||
                    total == null
                ) {
                    progress?.invoke(
                        bytesRead,
                        total ?: bytesRead
                    )
                }

                if (
                    expectedSha256 != null &&
                    digest != null
                ) {
                    val actual =
                        digest.digest()
                            .joinToString("") {
                                "%02x".format(it)
                            }

                    if (
                        !actual.equals(
                            expectedSha256,
                            ignoreCase = true
                        )
                    ) {
                        partFile.delete()

                        return@withContext Result.Error(
                            "SHA256 mismatch " +
                                "expected=$expectedSha256 " +
                                "actual=$actual"
                        )
                    }
                }

                if (destFile.exists()) {
                    destFile.delete()
                }

                if (!partFile.renameTo(destFile)) {
                    try {
                        partFile.copyTo(
                            destFile,
                            overwrite = true
                        )
                        partFile.delete()
                    } catch (e: Exception) {
                        partFile.delete()

                        return@withContext Result.Error(
                            "Atomic rename failed: ${e.message}",
                            e
                        )
                    }
                }

                Log.i(
                    TAG,
                    "success url=$url " +
                        "bytes=$bytesRead " +
                        "dest=${destFile.path}"
                )

                Result.Success(destFile)
            }
        } catch (e: CancellationException) {
            partFile.delete()
            Result.Canceled
        } catch (e: Exception) {
            Log.e(
                TAG,
                "failed url=$url: ${e.message}",
                e
            )

            runCatching { partFile.delete() }

            Result.Error(
                e.message ?: "Unknown error",
                e
            )
        }
    }
}
