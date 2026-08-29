package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun InGameSaveStatePage(
    capabilities: SaveStateCapabilities?,
    onBack: () -> Unit,
    onSave: (Int) -> Unit,
    onLoad: (Int) -> Unit
) {
    var showSaveConfirm by remember { mutableStateOf(false) }
    var pendingSlot by remember { mutableStateOf<Int?>(null) }
    val slots = capabilities?.slots ?: emptyList()
    val suspendMode = capabilities?.suspendMode == true
    val canSave = capabilities?.canSave != false

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.70f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxWidth(0.94f)
                .heightIn(max = 720.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "SAVE STATE",
                        color = RPCSXColors.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp
                    )
                    TextButton(onClick = onBack) { Text("Back") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (suspendMode) {
                    TextButton(
                        onClick = {
                            pendingSlot = 0
                            showSaveConfirm = true
                        },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text("SAVE STATE AND EXIT", color = if (canSave) RPCSXColors.primary else Color.Gray)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(slots, key = { it.slot }) { slot ->
                            SaveSlotCard(
                                slot = slot,
                                canSave = canSave,
                                onSave = {
                                    pendingSlot = slot.slot
                                    showSaveConfirm = true
                                },
                                onLoad = { onLoad(slot.slot) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            title = { Text("Save State?") },
            text = {
                Text(
                    if (suspendMode) {
                        "Save and exit the game?"
                    } else {
                        "Save current emulation state to slot " + (pendingSlot ?: 0) +
                            "? The game will briefly pause while the state is saved."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSaveConfirm = false
                    pendingSlot?.let(onSave)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SaveSlotCard(
    slot: SaveSlot,
    canSave: Boolean,
    onSave: () -> Unit,
    onLoad: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SaveSlotPreview(slot, Modifier.width(148.dp).aspectRatio(16f / 9f))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SLOT " + slot.slot,
                    color = RPCSXColors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (slot.exists) formatSlotTime(slot.mtimeMs) else "Empty",
                    color = RPCSXColors.textSecondary,
                    fontSize = 12.sp
                )
                if (slot.exists) {
                    Text(formatSize(slot.sizeBytes), color = RPCSXColors.textSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = onSave, enabled = canSave) { Text("SAVE") }
                    TextButton(onClick = onLoad, enabled = slot.exists) { Text("LOAD") }
                }
            }
        }
    }
}

@Composable
private fun SaveSlotPreview(slot: SaveSlot, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(slot.previewPath, slot.previewMtimeMs) {
        slot.previewPath?.let { path ->
            ImageRequest.Builder(context)
                .data(File(path))
                .memoryCacheKey(path + ":" + slot.previewMtimeMs)
                .build()
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(RPCSXColors.surfaceOverlay)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = "Saved game preview for Slot " + slot.slot,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_save),
                contentDescription = "Slot " + slot.slot + " has no saved state",
                tint = RPCSXColors.textSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

private fun formatSlotTime(mtimeMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(mtimeMs))

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024L * 1024L) {
        String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
    } else {
        String.format(java.util.Locale.getDefault(), "%d KB", (bytes / 1024L).coerceAtLeast(1L))
    }
