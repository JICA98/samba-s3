package com.zenithblue.sambas3.drivers.catalog

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Banners-Turnip GitHub releases source.
 * Fetches https://api.github.com/repos/The412Banner/Banners-Turnip/releases and extracts assets.
 */
class GitHubReleaseDriverSource(
    override val id: DriverSourceId = DriverSourceId.BANNERS_TURNIP,
    private val repo: String = "The412Banner/Banners-Turnip",
    private val client: OkHttpClient = defaultClient
) : DriverSource {

    @Serializable
    private data class Release(
        val name: String? = null,
        val tag_name: String? = null,
        val assets: List<Asset> = emptyList()
    )

    @Serializable
    private data class Asset(
        val name: String? = null,
        val browser_download_url: String? = null,
        val size: Long? = null
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun fetch(): List<RemoteDriverPackage> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$repo/releases?per_page=20"
            val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json").get().build()
            val resp = client.newCall(req).execute()
            try {
                if (!resp.isSuccessful) {
                    Log.w("DriverSource", "BANNERS_TURNIP http=${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body.string() ?: return@withContext emptyList()
                val releases = json.decodeFromString<List<Release>>(body)
                val out = mutableListOf<RemoteDriverPackage>()
                for (rel in releases) {
                    val tag = rel.tag_name ?: rel.name ?: continue
                    for (asset in rel.assets) {
                        val dl = asset.browser_download_url ?: continue
                        val name = asset.name ?: dl.substringAfterLast("/")
                        if (!name.endsWith(".zip", ignoreCase = true)) continue
                        val idStr = "banners_${tag}_${name}".replace(Regex("[^A-Za-z0-9._-]"), "_")
                        out.add(
                            RemoteDriverPackage(
                                id = idStr,
                                source = DriverSourceId.BANNERS_TURNIP,
                                displayName = "Banners $tag — $name",
                                version = tag,
                                downloadUrl = dl,
                                sha256 = null,
                                experimental = name.contains("a8xx", ignoreCase = true) || name.contains("experimental", ignoreCase = true),
                                gpuHint = if (name.contains("a8xx", ignoreCase = true)) "adreno8xx" else "adreno6xx",
                                checksum = null,
                                variant = if (name.lowercase().contains("turnip")) "Turnip" else null
                            )
                        )
                    }
                }
                return@withContext out
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            Log.w("DriverSource", "BANNERS_TURNIP exception ${e.message}")
            emptyList()
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
