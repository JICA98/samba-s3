package com.zenithblue.sambas3

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val enabledSerials: List<String> = emptyList(),
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

object PatchRepository {
    private const val TAG = "PatchRepository"
    private val json = Json { ignoreUnknownKeys = true }

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

    fun setEnabled(group: PatchGroup, enabled: Boolean, titleId: String? = null): Boolean =
        group.hashes.map { hash ->
            runCatching {
                if (titleId == null) RPCSX.instance.patchSetEnabled(hash, group.name, enabled)
                else RPCSX.instance.patchSetEnabledForTitle(hash, group.name, titleId, enabled)
            }.getOrDefault(false)
        }.all { it }.also { invalidate() }

    fun forTitle(patches: List<Patch>, titleId: String): List<Patch> =
        patches.filter { patch ->
            patch.serials.any { it.equals(titleId, ignoreCase = true) } ||
                patch.titles.any { it.equals(titleId, ignoreCase = true) }
        }.map { patch ->
            patch.copy(enabled = patch.enabledSerials.any { it.equals(titleId, ignoreCase = true) })
        }

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

    fun importLocal(content: String): Boolean = runCatching {
        patchesDir().mkdirs()
        File(patchesDir(), "imported_patch.yml").writeText(content)
        invalidate()
        true
    }.getOrDefault(false)
}
