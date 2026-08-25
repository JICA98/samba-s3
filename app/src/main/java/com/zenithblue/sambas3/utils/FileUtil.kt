package com.zenithblue.sambas3.utils

import android.net.Uri
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.AssetFileDescriptor
import android.content.ActivityNotFoundException
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zenithblue.sambas3.GameInfo
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.PrecompilerService
import com.zenithblue.sambas3.PrecompilerServiceAction
import com.zenithblue.sambas3.ProgressRepository
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.provider.AppDataDocumentProvider
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import kotlin.concurrent.thread

private data class InstallableFolder(
    val uri: Uri, val targetPath: String
)

data class GameFolderMatch(
    val folderName: String,
    val titleId: String?,
)

object FileUtil {
    /**
     * Self-healing: fixes historical nested path bug where an absolute install
     * path was appended as a relative child (e.g. config/games/storage/emulated/.../BLUS31584).
     * Detects and moves any title-ID-shaped dirs under the spurious storage tree back to config/games/.
     * Safe to call on every startup; no-op when no nested data exists.
     */
    fun fixNestedGameDirs(rootDir: String) {
        try {
            val gamesRoot = File(rootDir + "config/games")
            val nestedRoot = File(gamesRoot, "storage")
            if (!nestedRoot.exists() || !nestedRoot.isDirectory) return
            val titleIdRegex = Regex("^[A-Z]{4}[0-9]{5}$")
            // Walk the nested tree and collect dirs that look like game title IDs and contain PS3_GAME
            val candidates = mutableListOf<File>()
            fun walk(dir: File) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (child.isDirectory) {
                        if (titleIdRegex.matches(child.name) && File(child, "PS3_GAME/PARAM.SFO").exists()) {
                            candidates += child
                        } else {
                            walk(child)
                        }
                    }
                }
            }
            walk(nestedRoot)
            for (src in candidates) {
                val dest = File(gamesRoot, src.name)
                if (dest.exists()) continue  // don't overwrite existing correct install
                // Ensure parent exists (gamesRoot already does)
                val moved = src.renameTo(dest)
                if (!moved) {
                    // Fallback: copy via rename failed (cross-filesystem) — try manual move
                    src.copyRecursively(dest, overwrite = false)
                    if (dest.exists()) src.deleteRecursively()
                }
            }
            // Clean up the spurious tree if now empty
            if (nestedRoot.listFiles()?.isEmpty() == true) {
                nestedRoot.deleteRecursively()
            } else {
                // Remove any remaining empty ancestor chain storage/emulated/0/... that is now empty
                var cur: File? = nestedRoot
                while (cur != null && cur != gamesRoot && cur.listFiles()?.isEmpty() == true) {
                    val parent = cur.parentFile
                    cur.delete()
                    cur = parent
                }
            }
            // Also fix games.json entries that still point inside the nested tree
            try {
                val gamesFile = File(rootDir + "games.json")
                if (gamesFile.exists()) {
                    val raw = gamesFile.readText()
                    if (raw.contains("config/games/storage/")) {
                        val fixed = raw.replace(Regex(Regex.escape("config/games/storage/emulated/0/Android/data/com.zenithblue.sambas3/files/config/games/")), "config/games/")
                        if (fixed != raw) gamesFile.writeText(fixed)
                    }
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /**
     * Finds every PS3 game directory below a selected SAF tree without copying
     * or indexing anything. The result is used as a preview before the caller
     * decides whether to import it.
     */
    fun scanGameFolder(context: Context, rootFolderUri: Uri): List<GameFolderMatch> {
        return try {
            val rootName = DocumentFile.fromTreeUri(context, rootFolderUri)?.name
                ?: context.getString(R.string.onboarding_selected_folder)
            val workList = ArrayDeque<Pair<Uri, String>>()
            val matches = LinkedHashMap<String, GameFolderMatch>()
            workList.add(rootFolderUri to rootName)

            while (workList.isNotEmpty()) {
                val (folderUri, folderName) = workList.removeFirst()
                val hasParam = uriOpenFile(context, folderUri, "PS3_GAME/PARAM.SFO")?.use { true }
                    ?: uriOpenFile(context, folderUri, "PARAM.SFO")?.use { true }
                    ?: false
                if (hasParam) {
                    val titleId = Regex("(?i)([A-Z]{4}[0-9]{5})")
                        .find(folderName)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.uppercase()
                    val key = titleId ?: folderName.lowercase()
                    matches[key] = GameFolderMatch(folderName, titleId)
                    continue
                }

                listFilesStrict(folderUri, context)
                    .filter { it.isDirectory }
                    .forEach { child -> workList.add(child.uri to child.filename) }
            }
            matches.values.toList()
        } catch (e: Exception) {
            Log.e("FileUtil", "Cannot scan selected game folder $rootFolderUri", e)
            emptyList()
        }
    }

    fun installPackages(context: Context, rootFolderUri: Uri) {
        thread {
            try {
                val workList = mutableListOf(rootFolderUri)
                val batchFiles = mutableListOf<Uri>()
                val batchDirs = mutableListOf<InstallableFolder>()

                while (workList.isNotEmpty()) {
                    val currentFolderUri = workList.removeAt(0)
                    val paramSfo =
                        uriOpenFile(context, currentFolderUri, "PS3_GAME/PARAM.SFO")
                            ?: uriOpenFile(context, currentFolderUri, "PARAM.SFO")

                    if (paramSfo != null) {
                        val installDirRaw = paramSfo.use {
                            RPCSX.instance.getDirInstallPath(it.parcelFileDescriptor.fd)
                        } ?: throw IOException("Cannot determine the selected game's install path")

                        // Defensive: strip historical nested absolute (config/games/storage/...).
                        val installDir = if (installDirRaw.contains("config/games/storage/")) {
                            val titleId = Regex("[A-Z]{4}[0-9]{5}").find(installDirRaw)?.value
                            if (titleId != null) {
                                RPCSX.rootDirectory + "config/games/" + titleId
                            } else {
                                installDirRaw
                            }
                        } else {
                            installDirRaw
                        }
                        batchDirs += InstallableFolder(currentFolderUri, installDir)
                        continue
                    }

                    listFilesStrict(currentFolderUri, context).forEach { item ->
                        if (item.isDirectory) {
                            workList.add(item.uri)
                        } else {
                            batchFiles += item.uri
                        }
                    }
                }

                if (batchFiles.isNotEmpty()) {
                    PrecompilerService.start(
                        context,
                        PrecompilerServiceAction.Install,
                        ArrayList(batchFiles),
                    )
                }

                batchDirs.forEach {
                    if (GameRepository.find(it.targetPath) != null) {
                        return@forEach
                    }

                    val progress = ProgressRepository.create(
                        context,
                        context.getString(R.string.installing_dir),
                    )
                    GameRepository.activeInstallProgress.value = progress
                    GameRepository.add(arrayOf(GameInfo("$")), progress)
                    try {
                        val completionStep = copyDirUriToInternalStorage(
                            context,
                            it.uri,
                            it.targetPath,
                            progress,
                        )
                        if (!RPCSX.instance.collectGameInfo(it.targetPath, -1L)) {
                            throw IOException("The imported game could not be indexed")
                        }
                        ProgressRepository.onProgressEvent(
                            progress,
                            completionStep,
                            completionStep,
                        )
                    } catch (e: Exception) {
                        Log.e("FileUtil", "Game directory import failed: ${it.targetPath}", e)
                        val detail = e.message ?: context.getString(R.string.unexpected_error)
                        ProgressRepository.onProgressEvent(
                            progress,
                            -1,
                            0,
                            context.getString(R.string.game_import_failed, detail),
                        )
                    }
                }

                if (batchDirs.isNotEmpty()) {
                    GameRepository.activeInstallProgress.value = null
                }
            } catch (e: Exception) {
                Log.e("FileUtil", "Cannot discover importable content at $rootFolderUri", e)
                GameRepository.activeInstallProgress.value = null
                val detail = e.message ?: context.getString(R.string.unexpected_error)
                context.mainExecutor.execute {
                    AlertDialogQueue.showDialog(
                        context.getString(R.string.installing_dir),
                        context.getString(R.string.game_import_failed, detail),
                    )
                }
            }
        }
    }

    fun saveGameFolderUri(prefs: SharedPreferences, uri: Uri) {
        prefs.edit { putString("selected_game_folder", uri.toString()) }
    }

    fun copyDirUriToInternalStorage(
        context: Context, rootFolderUri: Uri, path: String, progressId: Long
    ): Long {
        val workList = mutableListOf<Pair<Uri, String>>()
        workList.add(Pair(rootFolderUri, path))
        val fileList = mutableListOf<Pair<SimpleDocument, String>>()

        while (workList.isNotEmpty()) {
            val currentFolderUriTarget = workList.removeAt(0)
            val currentFolderUri = currentFolderUriTarget.first
            val currentFolderTarget = currentFolderUriTarget.second

            listFilesStrict(currentFolderUri, context).forEach { item ->
                val file = File(currentFolderTarget, item.filename)
                if (item.isDirectory) {
                    if (!file.exists() && !file.mkdirs()) {
                        throw IOException("Cannot create import directory: ${file.path}")
                    }
                    if (!file.isDirectory) {
                        throw IOException("Import path is not a directory: ${file.path}")
                    }
                    workList.add(Pair(item.uri, file.path))
                } else {
                    fileList.add(Pair(item, file.path))
                }
            }
        }

        if (fileList.isEmpty()) {
            throw IOException("The selected game directory contains no files")
        }

        // Reserve the final progress step for collectGameInfo(), so a native
        // indexing failure cannot arrive after the progress entry was completed.
        val completionStep = fileList.size.toLong() + 1L
        ProgressRepository.onProgressEvent(progressId, 0, completionStep)
        var processed = 0L

        fileList.forEach { file ->
            saveFile(context, file.first, file.second)
            ProgressRepository.onProgressEvent(progressId, ++processed, completionStep)
        }
        return completionStep
    }

    private fun saveFile(context: Context, source: SimpleDocument, target: String): Long {
        val input = context.contentResolver.openInputStream(source.uri)
            ?: throw IOException("Cannot open source file: ${source.filename}")

        return try {
            input.use {
                AtomicFileCopier.copy(it, File(target), source.size)
            }
        } catch (e: Exception) {
            throw IOException("Failed to import ${source.filename}: ${e.message}", e)
        }
    }

    fun uriChild(context: Context, rootUri: Uri, path: String): SimpleDocument? {
        val pathDirectories = path.split("/").toMutableList()
        var uri = rootUri
        val filename = pathDirectories.removeAt(pathDirectories.size - 1)

        while (pathDirectories.isNotEmpty()) {
            val dirName = pathDirectories.removeAt(0)
            val entry = listFiles(uri, context).find { it.filename == dirName }
            if (entry == null || !entry.isDirectory) {
                return null
            }

            uri = entry.uri
        }

        return listFiles(uri, context).find { it.filename == filename }
    }

    fun uriOpenFile(context: Context, rootUri: Uri, path: String): AssetFileDescriptor? {
        val entry = uriChild(context, rootUri, path)

        if (entry == null || entry.isDirectory) {
            return null
        }

        return context.contentResolver.openAssetFileDescriptor(entry.uri, "r")
    }

    fun listFiles(uri: Uri, context: Context): Array<SimpleDocument> {
        return try {
            listFilesStrict(uri, context)
        } catch (e: Exception) {
            Log.e("FileUtil", "Cannot list files at $uri", e)
            emptyArray()
        }
    }

    @Throws(IOException::class)
    private fun listFilesStrict(uri: Uri, context: Context): Array<SimpleDocument> {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val results: MutableList<SimpleDocument> = ArrayList()
        val docId = if (isRootTreeUri(uri)) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
        val cursor = context.contentResolver.query(childrenUri, columns, null, null, null)
            ?: throw IOException("Document provider returned no directory listing for $uri")

        cursor.use {
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (it.moveToNext()) {
                val documentId = it.getString(0)
                val documentName = it.getString(1)
                val documentMimeType = it.getString(2)
                val documentSize = if (sizeColumn < 0 || it.isNull(sizeColumn)) {
                    null
                } else {
                    it.getLong(sizeColumn).takeIf { size -> size >= 0L }
                }
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                val document = SimpleDocument(
                    documentName,
                    documentMimeType,
                    documentUri,
                    documentSize,
                )
                results.add(document)
            }
        }
        return results.toTypedArray<SimpleDocument>()
    }

    fun isRootTreeUri(uri: Uri): Boolean {
        val paths = uri.pathSegments
        return paths.size == 2 && "tree" == paths[0]
    }

    fun deleteCache(ctx: Context, gameId: String, onComplete: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = File(ctx.getExternalFilesDir(null)!!, "cache/cache/$gameId").deleteRecursively()
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    /**
     * Removes an imported title and its generated PPU/cache data. Only title
     * directories owned by the app are accepted; an arbitrary external game
     * path is never deleted from this action.
     */
    fun removeGame(context: Context, game: com.zenithblue.sambas3.Game, onComplete: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                val root = File(RPCSX.rootDirectory).canonicalFile
                val gameRoot = File(game.info.path).canonicalFile
                val managedRoots = listOf(
                    File(root, "config/games").canonicalFile,
                    File(root, "config/dev_hdd0/game").canonicalFile,
                )
                val titleId = gameRoot.name.takeIf { TITLE_ID_PATTERN.matches(it) }
                    ?: throw IOException("The game title ID could not be determined")
                val isManaged = managedRoots.any { managedRoot ->
                    gameRoot.parentFile == managedRoot
                }
                if (!isManaged) {
                    throw IOException("Only imported games can be removed from the library")
                }
                if (gameRoot.exists() && !gameRoot.deleteRecursively()) {
                    throw IOException("The game files could not be removed")
                }
                File(root, "cache/cache/$titleId").deleteRecursively()
                File(root, "cache/cache/ppu_manifest/$titleId.json").delete()
                removeNativeGameIndexEntry(root, titleId)
                GameRepository.remove(game)
                true
            }.getOrElse {
                Log.e("FileUtil", "Game removal failed: ${game.info.path}", it)
                false
            }
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    fun importConfig(ctx: Context, uri: Uri): Boolean {
        return try {
            val docFile = DocumentFile.fromSingleUri(ctx, uri)
            if (docFile == null || (docFile.name?.endsWith(".yml", true) != true)) return false
            val inputStream: InputStream = ctx.contentResolver.openInputStream(uri) ?: return false
            val outputFile: File = ctx.getExternalFilesDir(null)?.resolve("config")?.resolve("config.yml") ?: return false
            val outputStream: OutputStream = outputFile.outputStream()
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun exportConfig(ctx: Context, uri: Uri): Boolean {
        return try {
            val inputFile = ctx.getExternalFilesDir(null)?.resolve("config")?.resolve("config.yml") ?: return false
            val inputStream: InputStream = inputFile.inputStream()
            val outputStream: OutputStream = ctx.contentResolver.openOutputStream(uri) ?: return false
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
           true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private val TITLE_ID_PATTERN = Regex("[A-Za-z]{4}\\d{5}")

    private fun removeNativeGameIndexEntry(root: File, titleId: String) {
        val gamesIndex = File(root, "config/games.yml")
        if (!gamesIndex.isFile) return

        val linePattern = Regex("(?m)^${Regex.escape(titleId)}:[^\\r\\n]*(?:\\r?\\n|$)")
        val contents = gamesIndex.readText()
        val updated = contents.replace(linePattern, "")
        if (updated != contents) {
            gamesIndex.writeText(updated)
        }
    }

    fun launchInternalDir(ctx: Context): Boolean {
        if (!ctx.launchBrowseIntent(Intent.ACTION_VIEW)) {
            if (!ctx.launchBrowseIntent()) {
                if (!ctx.launchBrowseIntent(Intent.ACTION_OPEN_DOCUMENT_TREE)) {
                    return false
                }
            }
        }
        return true
    }

    private fun Context.launchBrowseIntent(
        action: String = "android.provider.action.BROWSE"
    ): Boolean {
        return try {
            val intent = Intent(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                data = DocumentsContract.buildRootUri(
                    AppDataDocumentProvider.AUTHORITY, AppDataDocumentProvider.ROOT_ID
                )
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            println("No activity found to handle $action intent")
            false
        }
    } 
}

class SimpleDocument(
    val filename: String,
    val mimeType: String,
    val uri: Uri,
    val size: Long? = null,
) {
    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}
