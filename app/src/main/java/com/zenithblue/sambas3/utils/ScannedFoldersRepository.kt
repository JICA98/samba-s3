package com.zenithblue.sambas3.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.zenithblue.sambas3.iso.DirectIsoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ScannedFolder(
    val treeUri: String,
    val displayName: String,
    val addedAtMs: Long = 0L,
)

/**
 * Persists the set of ISO folders the user asked SambaS3 to watch.
 * Refresh re-scans every folder recursively and indexes new ISOs in place.
 */
object ScannedFoldersRepository {
    private const val TAG = "ScannedFolders"
    private const val PREF_NAME = "app_prefs"
    private const val PREF_KEY = "scanned_iso_folders_v1"
    private const val LEGACY_TREE_URI = "selected_game_folder_tree"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _folders = MutableStateFlow<List<ScannedFolder>>(emptyList())
    val foldersFlow: StateFlow<List<ScannedFolder>> = _folders

    fun load(context: Context): List<ScannedFolder> {
        return try {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(PREF_KEY, null)
            val decoded = if (!raw.isNullOrEmpty()) {
                json.decodeFromString<List<ScannedFolder>>(raw)
            } else {
                emptyList()
            }.distinctBy { it.treeUri }
            val migrated = migrateLegacyTree(context, decoded)
            _folders.value = migrated
            migrated
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            _folders.value = emptyList()
            emptyList()
        }
    }

    fun add(context: Context, treeUri: Uri, displayName: String): ScannedFolder {
        persistTreeRead(context, treeUri)
        val folder = ScannedFolder(
            treeUri = treeUri.toString(),
            displayName = displayName.ifBlank { folderName(context, treeUri) },
            addedAtMs = System.currentTimeMillis(),
        )
        val next = (_folders.value.ifEmpty { load(context) })
            .filterNot { it.treeUri == folder.treeUri } + folder
        persist(context, next)
        return folder
    }

    fun remove(context: Context, treeUri: String) {
        val next = (_folders.value.ifEmpty { load(context) }).filterNot { it.treeUri == treeUri }
        persist(context, next)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun folderName(context: Context, treeUri: Uri): String {
        return DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: context.getString(com.zenithblue.sambas3.R.string.onboarding_selected_folder)
    }

    fun addAndImport(context: Context, treeUri: Uri): DirectIsoManager.IsoFolderImportResult {
        add(context, treeUri, folderName(context, treeUri))
        val matches = FileUtil.scanGameFolder(context, treeUri)
        return importMatches(context, matches)
    }

    fun refreshAll(context: Context): DirectIsoManager.IsoFolderImportResult {
        val folders = _folders.value.ifEmpty { load(context) }
        val merged = LinkedHashMap<String, GameFolderMatch>()
        for (folder in folders) {
            val uri = runCatching { Uri.parse(folder.treeUri) }.getOrNull() ?: continue
            val matches = FileUtil.scanGameFolder(context, uri)
            for (match in matches) {
                val key = match.titleId?.uppercase()
                    ?: match.sourceUri?.toString()
                    ?: match.folderName.lowercase()
                if (!merged.containsKey(key)) merged[key] = match
            }
        }
        return importMatches(context, merged.values.toList())
    }

    private fun importMatches(
        context: Context,
        matches: List<GameFolderMatch>,
    ): DirectIsoManager.IsoFolderImportResult {
        return if (com.zenithblue.sambas3.BuildConfig.DIRECT_ISO_LOADING) {
            DirectIsoManager.importIsoMatches(context, matches)
        } else {
            DirectIsoManager.IsoFolderImportResult(
                entries = matches.filter { it.sourceKind == GameSourceKind.ISO }.map { match ->
                    DirectIsoManager.IsoImportEntry(
                        displayName = match.folderName,
                        titleId = match.titleId,
                        uri = match.sourceUri?.toString().orEmpty(),
                        status = DirectIsoManager.IsoImportStatus.FAILED,
                        message = context.getString(com.zenithblue.sambas3.R.string.iso_direct_required),
                    )
                },
                skippedDirectories = matches.count { it.sourceKind == GameSourceKind.DIRECTORY },
            )
        }
    }

    private fun persistTreeRead(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }

    private fun persist(context: Context, folders: List<ScannedFolder>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY, json.encodeToString(folders)).apply()
        _folders.value = folders
    }

    private fun migrateLegacyTree(context: Context, current: List<ScannedFolder>): List<ScannedFolder> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val legacy = prefs.getString(LEGACY_TREE_URI, null)?.takeIf { it.isNotBlank() } ?: return current
        if (current.any { it.treeUri == legacy }) return current
        val uri = runCatching { Uri.parse(legacy) }.getOrNull() ?: return current
        val migrated = current + ScannedFolder(
            treeUri = legacy,
            displayName = folderName(context, uri),
            addedAtMs = 0L,
        )
        persist(context, migrated)
        return migrated
    }
}
