package com.zenithblue.sambas3.ui.games.launch

import android.content.Context
import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.SavestateThumbnailStore
import com.zenithblue.sambas3.ui.ingame.SaveSlot
import java.io.File

object GameSavestateRepository {
    fun slots(context: Context, game: Game): List<SaveSlot> {
        val title = GameIdentity.titleIdOrNull(game.info.path, game.info.name.value) ?: return emptyList()
        val root = File(if (RPCSX.rootDirectory.isNotBlank()) RPCSX.rootDirectory else context.getExternalFilesDir(null)?.path.orEmpty())
        return (0 until 5).map { slot ->
            val base = File(root, "config/savestates/$title/${title}_1_$slot.SAVESTAT")
            val file = sequenceOf(File(base.path + ".zst"), File(base.path + ".gz"), base).firstOrNull { it.isFile }
            val preview = file?.let { SavestateThumbnailStore.metadataForPath(it.path) }
            SaveSlot(
                slot = slot,
                exists = file?.isFile == true && file.length() > 0,
                label = "Slot $slot",
                path = file?.path ?: File(base.path + ".zst").path,
                mtimeMs = file?.lastModified() ?: 0L,
                sizeBytes = file?.length() ?: 0L,
                previewPath = preview?.path,
                previewMtimeMs = preview?.mtimeMs ?: 0L
            )
        }
    }
}
