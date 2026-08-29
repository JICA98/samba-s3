package com.zenithblue.sambas3.ui.emulation

sealed interface SavestateOperationUiState {
    data object Hidden : SavestateOperationUiState
    data class Saving(val slot: Int, val stage: String) : SavestateOperationUiState
    data class Loading(val slot: Int, val previewPath: String?, val stage: String) : SavestateOperationUiState
    data class Failed(val operation: String, val slot: Int, val message: String) : SavestateOperationUiState
}
