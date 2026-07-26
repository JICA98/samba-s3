package com.zenithblue.sambas3.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

const val DefaultGpuDriverChannel = "https://github.com/K11MCH1/AdrenoToolsDrivers"

object GitHub {
    private const val apiServer = "https://api.github.com/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Release(
        val name: String,
        val assets: List<Asset> = emptyList()
    )

    @Serializable
    data class Asset(
        val name: String,
        val browser_download_url: String?
    )

    sealed class DownloadStatus {
        data object Success : DownloadStatus()
        data class Error(val message: String?) : DownloadStatus()
    }

    sealed class FetchResult {
        data class Success<T>(val content: T) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            response.body.string()
        } catch (e: IOException) {
            null
        }
    }

    suspend fun fetchReleases(repoUrl: String): FetchResult = withContext(Dispatchers.IO) {
        val repoPath = repoUrl.removePrefix("https://github.com/")
        val apiUrl = "${apiServer}repos/$repoPath/releases"

        when (val body = get(apiUrl)) {
            null -> FetchResult.Error("Failed to fetch releases")
            else -> {
                try {
                    val releases: List<Release> = json.decodeFromString(ListSerializer(Release.serializer()), body)
                    val drivers = releases.map { release ->
                        val assetUrl = release.assets.firstOrNull()?.browser_download_url
                        release.name to assetUrl
                    }
                    FetchResult.Success(drivers)
                } catch (e: Exception) {
                    FetchResult.Error("Parsing error: ${e.message}")
                }
            }
        }
    }

    suspend fun downloadAsset(
        assetUrl: String,
        destinationFile: File,
        progressCallback: (Long, Long) -> Unit,
        threadCount: Int = 4
    ): DownloadStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(assetUrl).head().build()
            val response = client.newCall(request).execute()
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: return@withContext DownloadStatus.Error("Unable to get file size")
            val supportsRange = response.header("Accept-Ranges") == "bytes"
            if (!supportsRange) return@withContext DownloadStatus.Error("Server does not support range requests")

            RandomAccessFile(destinationFile, "rw").setLength(contentLength)

            val chunkSize = contentLength / threadCount
            val totalBytesRead = AtomicLong(0)
            val deferredList = (0 until threadCount).map { i ->
                val start = i * chunkSize
                val end = if (i == threadCount - 1) contentLength - 1 else (start + chunkSize - 1)

                async(Dispatchers.IO) {
                    val partRequest = Request.Builder()
                        .url(assetUrl)
                        .addHeader("Range", "bytes=$start-$end")
                        .build()

                    client.newCall(partRequest).execute().use { partResponse ->
                        if (!partResponse.isSuccessful) {
                            throw IOException("Part $i failed")
                        }

                        val inputStream = partResponse.body.byteStream()
                        val raf = RandomAccessFile(destinationFile, "rw")
                        raf.seek(start)

                        val buffer = ByteArray(32 * 1024)
                        var read: Int

                        while (inputStream.read(buffer).also { read = it } != -1) {
                            raf.write(buffer, 0, read)
                            val bytesReadNow = totalBytesRead.addAndGet(read.toLong())
                            progressCallback(bytesReadNow, contentLength)
                        }

                        raf.close()
                    }
                }
            }

            deferredList.awaitAll()
            return@withContext DownloadStatus.Success
        } catch (e: Exception) {
            Log.e("GitHub", "Parallel download failed", e)
            return@withContext DownloadStatus.Error(e.message ?: "Unknown error")
        }
    }
}
