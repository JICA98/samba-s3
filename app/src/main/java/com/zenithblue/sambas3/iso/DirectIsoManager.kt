package com.zenithblue.sambas3.iso

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.GameInfo
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.GameSourceMode
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RuntimePpuState
import com.zenithblue.sambas3.toStore
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Direct ISO inspection, metadata extraction, and library registration for debug builds.
 * Never copies the ISO into app-private storage.
 */
object DirectIsoManager {
    private const val TAG = "S3ISO"
    private const val SECTOR_SIZE = 2048

    data class DirectIsoMetadata(
        val titleId: String,
        val titleName: String,
        val iconPath: String?,
        val fileSize: Long
    )

    fun validateAndRegister(context: Context, uri: Uri): Game {
        val appCtx = context.applicationContext
        val effectiveUri = DirectIsoSession.resolveDocumentUri(context, uri) ?: uri
        val pfd = try {
            val directFile = if (effectiveUri.scheme == "file" || effectiveUri.path?.startsWith("/") == true || effectiveUri.toString().startsWith("/")) {
                if (effectiveUri.scheme == "file") File(effectiveUri.path ?: "") else File(effectiveUri.toString())
            } else null

            if (directFile != null && directFile.exists() && directFile.canRead()) {
                ParcelFileDescriptor.open(directFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                appCtx.contentResolver.openFileDescriptor(effectiveUri, "r")
            }
        } catch (e: Exception) {
            Log.e(TAG, "unavailable reason=${e.message}")
            throw e
        } ?: run {
            val msg = "Could not open descriptor for $effectiveUri"
            Log.e(TAG, "unavailable reason=$msg")
            throw IllegalStateException(msg)
        }

        pfd.use { descriptor ->
            val statSize = descriptor.statSize
            if (statSize <= 0) {
                val msg = "File is empty or not seekable (statSize=$statSize)"
                Log.e(TAG, "unavailable reason=$msg")
                throw IllegalArgumentException(msg)
            }
            // Stream-only providers cannot serve random disc reads. Reject here
            // rather than falling back to copying the full ISO.
            try {
                Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            } catch (e: Exception) {
                val msg = "ISO source is not seekable/random-access (required for disc reads)"
                Log.e(TAG, "unavailable reason=$msg cause=${e.message}")
                throw IllegalArgumentException(msg, e)
            }
            Log.i(TAG, "fd_opened fd=${descriptor.fd} seekable=true size=$statSize")

            val displayName = queryDisplayName(appCtx, uri)
            val parsedMeta = parseIso9660Metadata(descriptor)
            val titleId = parsedMeta?.first
                ?: extractTitleIdFromText(displayName)
                ?: "DISO${kotlin.math.abs(uri.toString().hashCode()).toString(16).uppercase().padStart(5, '0').takeLast(5)}"

            val titleName = parsedMeta?.second
                ?: displayName.removeSuffix(".iso").removeSuffix(".ISO")

            // Icon extraction via RPCSX.instance.extractIsoPreview
            val iconsDir = File(appCtx.filesDir, "direct_iso_icons").apply { if (!exists()) mkdirs() }
            val iconDest = File(iconsDir, "${titleId}.png")
            val previewResult = runCatching {
                RPCSX.instance.extractIsoPreview(descriptor.fd, iconDest.absolutePath)
            }.getOrDefault(-1)

            if (previewResult == 2) {
                val msg = "Core rejected file: not a supported PS3 ISO"
                Log.e(TAG, "unavailable reason=$msg")
                throw IllegalArgumentException(msg)
            }

            val iconPath = if (previewResult == 0 && iconDest.exists() && iconDest.length() > 0) {
                iconDest.absolutePath
            } else {
                null
            }

            val canonicalPath = "direct_iso/$titleId"
            val gameInfo = GameInfo(
                path = canonicalPath,
                name = titleName,
                iconPath = iconPath,
                gameFlags = 0,
                sourceUri = effectiveUri.toString(),
                sourceMode = GameSourceMode.DIRECT_ISO
            )

            // Setup PPU readiness so game is immediately playable in Launch Center
            PpuReadinessStore.setPreRuntimeState(appCtx, titleId, PreRuntimePpuState.READY)
            PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.NOT_STARTED)

            GameRepository.add(arrayOf(gameInfo), progressId = -1)
            Log.i(TAG, "registered titleId=$titleId mode=DIRECT_ISO path=$canonicalPath uri=$uri")

            return GameRepository.find(canonicalPath)
                ?: Game(toStore(gameInfo))
        }
    }

    fun registerFromFilePath(context: Context, file: File): Game {
        return validateAndRegister(context, Uri.fromFile(file))
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file" || uri.path?.startsWith("/") == true || uri.toString().startsWith("/")) {
            val f = if (uri.scheme == "file") File(uri.path ?: "") else File(uri.toString())
            return f.name
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "PS3_GAME.iso"
    }

    private fun extractTitleIdFromText(text: String): String? {
        val regex = Regex("(?<![A-Za-z0-9])([A-Za-z]{4}\\d{5})(?![A-Za-z0-9])")
        return regex.find(text)?.groupValues?.getOrNull(1)?.uppercase()
    }

    /**
     * Reads Sector 16 (Primary Volume Descriptor) and traverses root directory
     * to find PS3_GAME/PARAM.SFO, then parses TITLE_ID and TITLE.
     */
    private fun parseIso9660Metadata(pfd: ParcelFileDescriptor): Pair<String, String>? {
        return try {
            val fis = FileInputStream(pfd.fileDescriptor)
            val channel = fis.channel

            // Read PVD at sector 16 (32768)
            val pvdBuffer = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            channel.position(16L * SECTOR_SIZE)
            if (channel.read(pvdBuffer) < SECTOR_SIZE) return null
            pvdBuffer.flip()

            val pvdBytes = pvdBuffer.array()
            // Check magic CD001
            if (pvdBytes[1] != 'C'.code.toByte() ||
                pvdBytes[2] != 'D'.code.toByte() ||
                pvdBytes[3] != '0'.code.toByte() ||
                pvdBytes[4] != '0'.code.toByte() ||
                pvdBytes[5] != '1'.code.toByte()
            ) {
                return null
            }

            // Root directory record is at offset 156 in PVD
            val rootRecordOffset = 156
            val rootLba = pvdBuffer.getInt(rootRecordOffset + 2)
            val rootLength = pvdBuffer.getInt(rootRecordOffset + 10)
            if (rootLba <= 0 || rootLength <= 0) return null

            // Read root directory extent
            val rootDirBytes = ByteArray(min(rootLength, 65536))
            channel.position(rootLba.toLong() * SECTOR_SIZE)
            channel.read(ByteBuffer.wrap(rootDirBytes))

            val ps3GameEntry = findDirectoryRecord(rootDirBytes, "PS3_GAME") ?: return null
            val ps3GameLba = ps3GameEntry.first
            val ps3GameLength = ps3GameEntry.second

            // Read PS3_GAME directory extent
            val ps3GameBytes = ByteArray(min(ps3GameLength, 65536))
            channel.position(ps3GameLba.toLong() * SECTOR_SIZE)
            channel.read(ByteBuffer.wrap(ps3GameBytes))

            val sfoEntry = findDirectoryRecord(ps3GameBytes, "PARAM.SFO") ?: return null
            val sfoLba = sfoEntry.first
            val sfoLength = sfoEntry.second

            // Read PARAM.SFO
            val sfoBytes = ByteArray(min(sfoLength, 65536))
            channel.position(sfoLba.toLong() * SECTOR_SIZE)
            channel.read(ByteBuffer.wrap(sfoBytes))

            parseParamSfo(sfoBytes)
        } catch (e: Exception) {
            Log.w(TAG, "parseIso9660Metadata failed: ${e.message}")
            null
        }
    }

    private fun findDirectoryRecord(data: ByteArray, targetName: String): Pair<Int, Int>? {
        var offset = 0
        while (offset < data.size) {
            val recordLen = data[offset].toInt() and 0xFF
            if (recordLen == 0) {
                offset = (offset + SECTOR_SIZE) and (SECTOR_SIZE - 1).inv()
                continue
            }
            if (offset + recordLen > data.size) break

            val nameLen = data[offset + 32].toInt() and 0xFF
            if (offset + 33 + nameLen <= data.size) {
                val rawName = String(data, offset + 33, nameLen, Charsets.US_ASCII)
                val cleanName = rawName.substringBefore(';')
                if (cleanName.equals(targetName, ignoreCase = true)) {
                    val bb = ByteBuffer.wrap(data, offset, recordLen).order(ByteOrder.LITTLE_ENDIAN)
                    val lba = bb.getInt(2)
                    val len = bb.getInt(10)
                    return lba to len
                }
            }
            offset += recordLen
        }
        return null
    }

    private fun parseParamSfo(data: ByteArray): Pair<String, String>? {
        if (data.size < 20) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        // Magic \0PSF
        if (data[0] != 0.toByte() || data[1] != 'P'.code.toByte() || data[2] != 'S'.code.toByte() || data[3] != 'F'.code.toByte()) {
            return null
        }
        val keyTableStart = bb.getInt(8)
        val dataTableStart = bb.getInt(12)
        val entryCount = bb.getInt(16)

        var titleId: String? = null
        var titleName: String? = null

        for (i in 0 until entryCount) {
            val entryOff = 20 + i * 16
            if (entryOff + 16 > data.size) break

            val keyOffset = bb.getShort(entryOff).toInt() and 0xFFFF
            val dataFormat = bb.getShort(entryOff + 2).toInt() and 0xFFFF
            val dataLen = bb.getInt(entryOff + 4)
            val dataOffset = bb.getInt(entryOff + 12)

            val keyPos = keyTableStart + keyOffset
            if (keyPos >= data.size) continue
            val keyEnd = (keyPos until data.size).firstOrNull { data[it] == 0.toByte() } ?: data.size
            val key = String(data, keyPos, keyEnd - keyPos, Charsets.US_ASCII)

            val valPos = dataTableStart + dataOffset
            if (valPos + dataLen <= data.size) {
                if (dataFormat == 0x0204) { // UTF-8 string
                    val strVal = String(data, valPos, dataLen, Charsets.UTF_8).trimEnd('\u0000')
                    if (key == "TITLE_ID") titleId = strVal
                    else if (key == "TITLE") titleName = strVal
                }
            }
        }

        if (titleId != null && titleName != null) {
            return titleId to titleName
        }
        if (titleId != null) {
            return titleId to titleId
        }
        return null
    }
}
