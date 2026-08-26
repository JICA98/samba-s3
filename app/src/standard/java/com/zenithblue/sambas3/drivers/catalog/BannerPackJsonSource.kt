package com.zenithblue.sambas3.drivers.catalog

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Arihany pack.json source — expected to be an array or object containing drivers.
 * Handles failure of one source gracefully; returns empty on any error.
 * Supports both legacy K11 and Arihany URLs.
 */
class BannerPackJsonSource(
    override val id: DriverSourceId = DriverSourceId.ARIHANY,
    private val urls: List<String> = listOf(
        "https://raw.githubusercontent.com/arihany/AdrenoToolsDrivers/refs/heads/main/pack.json",
        "https://raw.githubusercontent.com/K11MCH1/AdrenoToolsDrivers/main/pack.json",
        "https://raw.githubusercontent.com/arihany/AdrenoToolsDrivers/main/pack.json"
    ),
    private val client: OkHttpClient = defaultClient
) : DriverSource {

    @Serializable
    private data class PackEntry(
        val name: String? = null,
        val displayName: String? = null,
        val verName: String? = null,
        val version: String? = null,
        val downloadUrl: String? = null,
        val url: String? = null,
        val remoteUrl: String? = null,
        val sha256: String? = null,
        val experimental: Boolean? = null
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun fetch(): List<RemoteDriverPackage> {
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w("DriverSource", "ARIHANY $url http=${resp.code}")
                        return@use
                    }
                    val body = resp.body.string() ?: return@use
                    // Try to parse as list or object with "drivers" key
                    val packages = tryParse(body) ?: return@use
                    if (packages.isNotEmpty()) return packages
                }
            } catch (e: Exception) {
                Log.w("DriverSource", "ARIHANY $url exception ${e.message}")
            }
        }
        Log.w("DriverSource", "ARIHANY all URLs failed, returning empty (isolated failure)")
        return emptyList()
    }

    private fun tryParse(body: String): List<RemoteDriverPackage>? {
        return try {
            // Try list directly
            val list = json.decodeFromString<List<PackEntry>>(body)
            list.mapNotNull { e -> toPackage(e) }
        } catch (_: Exception) {
            try {
                // Try object with "drivers" or "packs"
                val obj = json.decodeFromString<Map<String, List<PackEntry>>>(body)
                val list = obj["drivers"] ?: obj["packs"] ?: obj["pack"] ?: emptyList()
                list.mapNotNull { e -> toPackage(e) }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun toPackage(e: PackEntry): RemoteDriverPackage? {
        val url = e.downloadUrl ?: e.url ?: e.remoteUrl ?: return null
        val name = e.displayName ?: e.verName ?: e.name ?: url.substringAfterLast("/")
        val idStr = "arihany_${name.hashCode().toString(16)}_${url.hashCode().toString(16)}"
        return RemoteDriverPackage(
            id = idStr,
            source = DriverSourceId.ARIHANY,
            displayName = name,
            version = e.version ?: e.verName,
            downloadUrl = url,
            sha256 = e.sha256,
            experimental = e.experimental ?: false,
            gpuHint = null
        )
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
