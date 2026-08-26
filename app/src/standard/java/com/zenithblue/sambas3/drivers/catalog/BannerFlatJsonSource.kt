package com.zenithblue.sambas3.drivers.catalog

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Generic flat JSON source for Banner nightlies: array of {type, verName, verCode, remoteUrl}
 * Used for Kimchi, StevenMXZ, MTR, Whitebelyash, Nightlies (with type filter).
 */
class BannerFlatJsonSource(
    override val id: DriverSourceId,
    private val url: String,
    private val filterGpuOnly: Boolean = false,
    private val client: OkHttpClient = defaultClient
) : DriverSource {

    @Serializable
    private data class Entry(
        val type: String? = null,
        val verName: String? = null,
        val verCode: String? = null,
        val remoteUrl: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun fetch(): List<RemoteDriverPackage> {
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("DriverSource", "$id fetch failed http=${resp.code}")
                    return emptyList()
                }
                val body = resp.body.string() ?: return emptyList()
                val entries = json.decodeFromString<List<Entry>>(body)
                entries.mapNotNull { e ->
                    if (e.remoteUrl.isNullOrBlank() || e.verName.isNullOrBlank()) return@mapNotNull null
                    if (filterGpuOnly && e.type != "GpuDriver") return@mapNotNull null
                    // Filter out non-GpuDriver for nightlies, but for other sources type may be GpuDriver anyway
                    val name = e.verName!!.trim()
                    val url = e.remoteUrl!!.trim()
                    // Create stable id from source + name + url hash
                    val idStr = "${id.name.lowercase()}_${name.hashCode().toString(16)}_${url.hashCode().toString(16)}"
                    RemoteDriverPackage(
                        id = idStr,
                        source = id,
                        displayName = name,
                        version = extractVersion(name),
                        downloadUrl = url,
                        sha256 = null,
                        experimental = isExperimental(name),
                        gpuHint = guessGpuHint(name)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("DriverSource", "$id fetch exception: ${e.message}")
            emptyList()
        }
    }

    private fun extractVersion(name: String): String? {
        // Try to extract version like v26.3 etc
        val m = Regex("""v?([0-9]+\.[0-9.]+(?:-rc[0-9]+)?)""", RegexOption.IGNORE_CASE).find(name)
        return m?.groupValues?.get(1)
    }

    private fun isExperimental(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("experimental") || n.contains("a8xx") && n.contains("830") || n.contains("sync") && n.contains("a8xx")
    }

    private fun guessGpuHint(name: String): String? {
        val n = name.lowercase()
        return when {
            n.contains("a8xx") || n.contains("840") || n.contains("830") -> "adreno8xx"
            n.contains("a7xx") || n.contains("740") || n.contains("730") -> "adreno7xx"
            n.contains("a6xx") -> "adreno6xx"
            else -> null
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
