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
 * BannerHub full GPU manifest: https://raw.githubusercontent.com/The412Banner/bannerhub-api/main/components/drivers_manifest
 * At review time reports 307 drivers. Parse data.components[] where type == 2.
 * Maps id, name, display_name, version, download_url, file_md5, file_size into RemoteDriverPackage.
 * Supports both .tzst and .zip, verifies MD5 transport.
 */
class BannerHubManifestDriverSource(
    override val id: DriverSourceId = DriverSourceId.BANNERHUB,
    private val url: String = "https://raw.githubusercontent.com/The412Banner/bannerhub-api/main/components/drivers_manifest",
    private val client: OkHttpClient = defaultClient
) : DriverSource {

    @Serializable
    private data class ManifestResponse(
        val code: Int? = null,
        val msg: String? = null,
        val data: ManifestData? = null
    )

    @Serializable
    private data class ManifestData(
        val type: Int? = null,
        val type_name: String? = null,
        val display_name: String? = null,
        val total: Int? = null,
        val components: List<Component> = emptyList()
    )

    @Serializable
    private data class Component(
        val id: Int? = null,
        val name: String? = null,
        val display_name: String? = null,
        val version: String? = null,
        val version_code: Int? = null,
        val download_url: String? = null,
        val file_md5: String? = null,
        val file_name: String? = null,
        val file_size: Long? = null,
        val type: Int? = null,
        val is_ui: Int? = null
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    override suspend fun fetch(): List<RemoteDriverPackage> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            try {
                if (!resp.isSuccessful) {
                    Log.w("DriverSource", "BANNERHUB http=${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body.string() ?: return@withContext emptyList()
                val manifest = json.decodeFromString<ManifestResponse>(body)
                val components = manifest.data?.components ?: emptyList()
                val filtered = components.filter { it.type == 2 }
                Log.i("DriverSource", "BANNERHUB fetched total=${manifest.data?.total} filtered=${filtered.size}")
                filtered.mapNotNull { c ->
                    val dl = c.download_url?.trim() ?: return@mapNotNull null
                    if (dl.isBlank()) return@mapNotNull null
                    val display = c.display_name?.trim() ?: c.name?.trim() ?: dl.substringAfterLast("/")
                    val name = c.name?.trim() ?: display
                    val idStr = "bannerhub_${c.id ?: name.hashCode()}_${dl.hashCode().toString(16)}"
                    val fmt = RemoteDriverPackage.inferFormat(dl)
                    val md5 = c.file_md5?.trim()?.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }
                    val checksum = md5?.let { RemoteChecksum(ChecksumAlgorithm.MD5, it.lowercase()) }
                    val experimental = display.lowercase().contains("experimental") || name.lowercase().contains("experimental")
                    val gpuHint = guessGpuHint(display + " " + name)
                    val variant = guessVariant(display + " " + name)
                    RemoteDriverPackage(
                        id = idStr,
                        source = DriverSourceId.BANNERHUB,
                        displayName = display,
                        version = c.version,
                        downloadUrl = dl,
                        sha256 = null,
                        experimental = experimental,
                        gpuHint = gpuHint,
                        checksum = checksum,
                        archiveFormat = fmt,
                        fileSize = c.file_size,
                        variant = variant
                    )
                }
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            Log.w("DriverSource", "BANNERHUB exception ${e.message}", e)
            emptyList()
        }
    }

    private fun guessGpuHint(name: String): String? {
        val n = name.lowercase()
        return when {
            n.contains("a8xx") || n.contains("adreno8") || n.contains("840") || n.contains("830") -> "adreno8xx"
            n.contains("a7xx") || n.contains("adreno7") || n.contains("740") || n.contains("730") -> "adreno7xx"
            n.contains("a6xx") || n.contains("adreno6") -> "adreno6xx"
            n.contains("qualcomm") || n.contains("adpkg") -> "qualcomm"
            else -> null
        }
    }

    private fun guessVariant(name: String): String? {
        val n = name.lowercase()
        return when {
            n.contains("gmem") -> "GMEM"
            n.contains("sysmem") -> "SYSMEM"
            n.contains("turnip") -> "Turnip"
            n.contains("qualcomm") -> "Qualcomm"
            else -> null
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
