package com.zenithblue.sambas3.iso

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Manages the open ParcelFileDescriptor lifecycle for a running Direct ISO game session.
 * The descriptor remains open for the full duration of emulation so RPCSX can read sectors
 * continuously via /proc/self/fd/<fd> without copying the ISO into app-private storage.
 */
object DirectIsoSession {
    private const val TAG = "S3ISO"

    data class Session(
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
        val fd: Int,
        val procFdPath: String,
        val statSize: Long
    ) : AutoCloseable {
        override fun close() {
            runCatching { pfd.close() }
        }
    }

    @Volatile
    private var activeSession: Session? = null

    @Synchronized
    fun acquire(context: Context, uri: Uri): Session {
        release("new-session-request")
        Log.i(TAG, "source_selected uri=$uri")

        val pfd = try {
            val directFile = if (uri.scheme == "file" || uri.path?.startsWith("/") == true || uri.toString().startsWith("/")) {
                if (uri.scheme == "file") File(uri.path ?: "") else File(uri.toString())
            } else null

            if (directFile != null && directFile.exists() && directFile.canRead()) {
                ParcelFileDescriptor.open(directFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                val effectiveUri = resolveDocumentUri(context, uri) ?: uri
                context.contentResolver.openFileDescriptor(effectiveUri, "r")
            }
        } catch (e: Exception) {
            Log.e(TAG, "unavailable reason=${e.message}")
            throw e
        }

        if (pfd == null) {
            val msg = "ContentResolver returned null descriptor for $uri"
            Log.e(TAG, "unavailable reason=$msg")
            throw IllegalStateException(msg)
        }

        val fd = pfd.fd
        val size = pfd.statSize
        if (size <= 0) {
            runCatching { pfd.close() }
            val msg = "ISO source is empty or has unknown size (statSize=$size)"
            Log.e(TAG, "unavailable reason=$msg")
            throw IllegalStateException(msg)
        }
        try {
            android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_CUR)
        } catch (e: Exception) {
            runCatching { pfd.close() }
            val msg = "ISO source is not seekable/random-access (required for disc reads)"
            Log.e(TAG, "unavailable reason=$msg cause=${e.message}")
            throw IllegalStateException(msg, e)
        }
        val procFdPath = "/proc/self/fd/$fd"
        Log.i(TAG, "fd_opened fd=$fd seekable=true size=$size")

        val session = Session(
            uri = uri,
            pfd = pfd,
            fd = fd,
            procFdPath = procFdPath,
            statSize = size
        )
        activeSession = session
        Log.i(TAG, "fd_session_acquired uri=$uri fd=$fd procFd=$procFdPath")
        return session
    }

    @Synchronized
    fun current(): Session? = activeSession

    /**
     * A proc-FD path is only usable while its ParcelFileDescriptor is still
     * open. Validate at every native boot/restore boundary rather than
     * allowing a stale descriptor to reach the core.
     */
    @Synchronized
    fun currentLive(expectedUri: Uri? = null): Session? {
        val session = activeSession ?: return null
        if (expectedUri != null && session.uri != expectedUri) {
            Log.e(TAG, "fd_invalid expected_uri=$expectedUri active_uri=${session.uri} reason=session-mismatch")
            return null
        }
        val live = runCatching {
            Os.fstat(session.pfd.fileDescriptor)
            true
        }.getOrElse { error ->
            Log.e(TAG, "fd_invalid fd=${session.fd} procFd=${session.procFdPath} reason=${error.message}")
            false
        }
        if (live) Log.i(TAG, "fd_valid fd=${session.fd} procFd=${session.procFdPath}")
        return session.takeIf { live }
    }

    @Synchronized
    fun release(reason: String) {
        val session = activeSession ?: return
        Log.i(TAG, "fd_session_released reason=$reason uri=${session.uri} fd=${session.fd}")
        session.close()
        activeSession = null
    }

    fun resolveDocumentUri(context: Context, uriOrPath: Uri): Uri? {
        val pathStr = uriOrPath.path ?: uriOrPath.toString()

        // 1. Check LibraryCandidatesRepository
        runCatching {
            val candidate = com.zenithblue.sambas3.utils.LibraryCandidatesRepository.load(context).firstOrNull { cand ->
                cand.sourceUri?.toString() == uriOrPath.toString() ||
                    (cand.sourceUri != null && pathStr.endsWith(cand.folderName)) ||
                    (cand.titleId != null && pathStr.contains(cand.titleId, ignoreCase = true))
            }
            if (candidate?.sourceUri != null) {
                Log.i(TAG, "resolved_via_candidates uri=${candidate.sourceUri}")
                return candidate.sourceUri
            }
        }

        // 2. Check GameRepository
        runCatching {
            val game = com.zenithblue.sambas3.GameRepository.list().firstOrNull { g ->
                g.info.sourceUri.value != null && (
                    g.info.sourceUri.value == uriOrPath.toString() ||
                        pathStr.endsWith(g.info.path) ||
                        (g.info.name.value != null && pathStr.contains(g.info.name.value!!, ignoreCase = true))
                )
            }
            if (game?.info?.sourceUri?.value != null) {
                Log.i(TAG, "resolved_via_repository uri=${game.info.sourceUri.value}")
                return Uri.parse(game.info.sourceUri.value)
            }
        }

        // 3. Match against persisted tree permissions
        runCatching {
            val normalized = pathStr.replace("/storage/emulated/0/", "").replace("/sdcard/", "").trimStart('/')
            val docId = if (normalized.startsWith("primary:")) normalized else "primary:$normalized"
            for (perm in context.contentResolver.persistedUriPermissions) {
                if (perm.isReadPermission && android.provider.DocumentsContract.isTreeUri(perm.uri)) {
                    val candidateUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(perm.uri, docId)
                    val isReadable = runCatching {
                        context.contentResolver.openFileDescriptor(candidateUri, "r")?.use { true } ?: false
                    }.getOrElse { false }
                    if (isReadable) {
                        Log.i(TAG, "resolved_via_tree_perm uri=$candidateUri")
                        return candidateUri
                    }
                }
            }
        }

        return null
    }
}
