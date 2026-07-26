package com.zenithblue.sambas3

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

@Serializable
data class Patch(
    val hash: String = "",
    val name: String = "",
    val author: String = "",
    val version: String = "",
    val notes: String = "",
    val serials: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
    val enabled: Boolean = false,
)

data class PatchGroup(
    val name: String,
    val author: String,
    val version: String,
    val notes: String,
    val serials: List<String>,
    val titles: List<String>,
    val hashes: List<String>,
    val enabled: Boolean,
)

sealed class PatchDownloadResult {
    data class Success(val updated: Boolean) : PatchDownloadResult()
    data class Error(val message: String) : PatchDownloadResult()
}

object PatchRepository {
    private const val TAG = "PatchRepository"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    private const val PATCH_API_URL =
        "https://rpcs3.net/compatibility?patch&api=v1&v=1.2"

    @Volatile
    private var cached: List<Patch>? = null

    fun invalidate() {
        cached = null
    }

    fun patchesDir(): File =
        File(RPCSX.rootDirectory + "config/patches/")

    fun list(): List<Patch> {
        cached?.let { return it }
        val raw = runCatching { RPCSX.instance.patchesList() }.getOrElse {
            Log.e(TAG, "patchesList() JNI call failed", it)
            return emptyList()
        }
        return runCatching {
            json.decodeFromString<List<Patch>>(raw)
        }.onSuccess { cached = it }.getOrElse {
            Log.e(TAG, "failed to parse patch list (len=${raw.length}): ${raw.take(200)}", it)
            emptyList()
        }
    }

    fun setEnabled(hash: String, name: String, enabled: Boolean): Boolean =
        runCatching {
            RPCSX.instance.patchSetEnabled(hash, name, enabled)
        }.getOrDefault(false).also { invalidate() }

    fun setEnabled(group: PatchGroup, enabled: Boolean): Boolean =
        group.hashes.map { hash ->
            runCatching {
                RPCSX.instance.patchSetEnabled(hash, group.name, enabled)
            }.getOrDefault(false)
        }.all { it }.also { invalidate() }

    fun group(patches: List<Patch>): List<PatchGroup> =
        patches.groupBy { listOf(it.name, it.author, it.version, it.notes) }
            .map { (_, ps) ->
                val f = ps.first()
                PatchGroup(
                    name = f.name,
                    author = f.author,
                    version = f.version,
                    notes = f.notes,
                    serials = ps.flatMap { it.serials }.distinct(),
                    titles = ps.flatMap { it.titles }.distinct(),
                    hashes = ps.map { it.hash }.distinct(),
                    enabled = ps.any { it.enabled },
                )
            }

    fun downloadOfficial(): PatchDownloadResult {
        return try {
            val version = runCatching {
                RPCSX.instance.patchEngineVersion()
            }.getOrDefault("1.2").ifEmpty { "1.2" }

            val url = "https://rpcs3.net/compatibility?patch&api=v1&v=$version"
            Log.i(TAG, "Downloading patches from $url")
            val request = Request.Builder().url(url)
                .header("User-Agent", "SambaS3").build()

            client.newCall(request).execute().use { resp ->
                Log.i(TAG, "Patch download response: ${resp.code}")
                if (!resp.isSuccessful)
                    return PatchDownloadResult.Error("HTTP ${resp.code}")

                val body = resp.body?.string().orEmpty()
                Log.i(TAG, "Patch response body length: ${body.length}")
                val obj = JSONObject(body)
                when (val rc = obj.optInt("return_code", -255)) {
                    0 -> Unit
                    1 -> return PatchDownloadResult.Success(updated = false)
                    -1 -> return PatchDownloadResult.Error(
                        "No patches found for version $version")
                    else -> return PatchDownloadResult.Error(
                        "Server error (code $rc)")
                }

                val content = obj.optString("patch")
                if (content.isEmpty())
                    return PatchDownloadResult.Error("Empty patch content")

                patchesDir().mkdirs()
                Log.i(TAG, "Writing patches to ${patchesDir().absolutePath}")
                File(patchesDir(), "patch.yml").writeText(content)
                invalidate()
                PatchDownloadResult.Success(updated = true)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Download failed", e)
            PatchDownloadResult.Error(e.message ?: "Download failed")
        }
    }

    fun importLocal(content: String): Boolean = runCatching {
        patchesDir().mkdirs()
        File(patchesDir(), "imported_patch.yml").writeText(content)
        invalidate()
        true
    }.getOrDefault(false)
}
