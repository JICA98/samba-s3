package com.zenithblue.sambas3.ui.games.preview

import android.net.Uri
import java.io.File

/**
 * Preview artwork model for Home cards.
 * Keeps raw SAF URIs and cached files separate; never copies full games.
 */
sealed interface GamePreviewModel {
    data class ContentUri(val uri: Uri) : GamePreviewModel
    data class LocalFile(val file: File) : GamePreviewModel
    data object None : GamePreviewModel
}
