package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.components.DialogBackgroundBlur
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun InGameSaveStatePage(
    capabilities: SaveStateCapabilities?,
    selectedIndex: Int,
    confirmSlot: Int?,
    loadUnavailableSlot: Int?,
    onBack: () -> Unit,
    onReportCount: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onRequestSave: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onLoad: (Int) -> Unit,
    onDismissConfirm: () -> Unit,
    onDismissLoadUnavailable: () -> Unit
) {
    val slots = capabilities?.slots ?: emptyList()
    val suspendMode = capabilities?.suspendMode == true
    val canSave = capabilities?.canSave != false
    val itemCount = if (suspendMode) 1 else slots.size

    LaunchedEffect(itemCount) { onReportCount(itemCount) }

    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    )
    LaunchedEffect(selectedIndex) {
        if (!suspendMode && selectedIndex in slots.indices) {
            runCatching { gridState.animateScrollToItem(selectedIndex) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "SAVE STATE",
                    color = RPCSXColors.primary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    "Instant slot snapshot for current emulation session",
                    color = RPCSXColors.textSecondary,
                    fontSize = 12.sp
                )
            }
            Surface(
                onClick = onBack,
                shape = RoundedCornerShape(8.dp),
                color = RPCSXColors.primary.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.4f)),
                modifier = Modifier.height(32.dp)
            ) {
                Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        "BACK",
                        color = RPCSXColors.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = Color(0x22FFFFFF)
        )

        if (suspendMode) {
            Surface(
                onClick = { onRequestSave(0) },
                enabled = canSave,
                shape = RoundedCornerShape(8.dp),
                color = if (selectedIndex == 0) RPCSXColors.primary.copy(alpha = 0.25f) else Color.Transparent,
                border = if (selectedIndex == 0) {
                    BorderStroke(2.dp, RPCSXColors.focusRing)
                } else {
                    BorderStroke(1.dp, Color.Transparent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "SAVE STATE AND EXIT",
                        color = if (canSave) RPCSXColors.primary else Color.Gray,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                state = gridState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(slots, key = { _, slot -> slot.slot }) { index, slot ->
                    SaveSlotCard(
                        slot = slot,
                        canSave = canSave,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        onSave = { onRequestSave(slot.slot) },
                        onLoad = { onLoad(slot.slot) }
                    )
                }
            }
        }

        SaveStateHintFooter(
            suspendMode = suspendMode,
            canSave = canSave,
            canLoad = !suspendMode
        )
    }

    if (confirmSlot != null) {
        val replacingExisting = slots.firstOrNull { it.slot == confirmSlot }?.exists == true
        SaveStateNoticeDialog(
            title = if (suspendMode) "SAVE & EXIT" else "SAVE TO SLOT $confirmSlot",
            message = if (suspendMode) {
                "Capture the current state and close this game session?"
            } else {
                if (replacingExisting) {
                    "Replace the state currently stored in slot $confirmSlot? The game stays paused while the snapshot is written."
                } else {
                    "Capture the current game in slot $confirmSlot? The game stays paused while the snapshot is written."
                }
            },
            iconRes = R.drawable.ic_save,
            actionText = "SAVE",
            onAction = { onSave(confirmSlot) },
            onDismiss = onDismissConfirm
        )
    }

    if (loadUnavailableSlot != null) {
        SaveStateNoticeDialog(
            title = "NO SAVE IN SLOT $loadUnavailableSlot",
            message = "This slot is empty. Save the game to this slot before trying to load it.",
            iconRes = R.drawable.ic_info,
            actionText = "OK",
            onAction = onDismissLoadUnavailable,
            onDismiss = onDismissLoadUnavailable,
            showCancel = false
        )
    }
}

@Composable
private fun SaveStateNoticeDialog(
    title: String,
    message: String,
    iconRes: Int,
    actionText: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    showCancel: Boolean = true
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DialogBackgroundBlur(36)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xF20D0A12),
            border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.42f)),
            shadowElevation = 24.dp,
            modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth(0.88f)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = RPCSXColors.primary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.35f)),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                tint = RPCSXColors.primary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "save states",
                            color = RPCSXColors.primary,
                            fontSize = 12.sp
                        )
                        Text(
                            title,
                            color = RPCSXColors.textPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
                    }
                }
                HorizontalDivider(color = Color(0x20FFFFFF))
                Text(
                    message,
                    color = RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showCancel) {
                        Surface(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent
                        ) {
                            Text(
                                "CANCEL",
                                color = RPCSXColors.textSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                    Surface(
                        onClick = onAction,
                        shape = RoundedCornerShape(8.dp),
                        color = RPCSXColors.primary,
                        border = BorderStroke(1.dp, RPCSXColors.focusRing)
                    ) {
                        Text(
                            actionText,
                            color = Color(0xFF0A0D1A),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Button hints mirroring the router's menu mapping for this page. */
@Composable
private fun SaveStateHintFooter(
    suspendMode: Boolean,
    canSave: Boolean,
    canLoad: Boolean
) {
    val hints = buildList {
        if (canSave) add(R.drawable.cross to if (suspendMode) "SAVE AND EXIT" else "SAVE")
        if (canLoad) add(R.drawable.square to "LOAD")
        add(R.drawable.circle to "BACK")
    }
    Column {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = Color(0x18FFFFFF)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            hints.forEach { (glyphRes, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Image(
                        painter = painterResource(glyphRes),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        label,
                        color = if (glyphRes == R.drawable.cross) {
                            RPCSXColors.primary
                        } else {
                            RPCSXColors.textSecondary
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveSlotCard(
    slot: SaveSlot,
    canSave: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) RPCSXColors.primary.copy(alpha = 0.12f) else Color(0x28FFFFFF),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) RPCSXColors.focusRing else Color(0x25FFFFFF)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SaveSlotPreview(slot, Modifier.width(140.dp).aspectRatio(16f / 9f))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SLOT " + slot.slot,
                    color = RPCSXColors.primary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (slot.exists) formatSlotTime(slot.mtimeMs) else "Empty Slot",
                    color = if (slot.exists) RPCSXColors.textPrimary else RPCSXColors.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
                if (slot.exists) {
                    Text(formatSize(slot.sizeBytes), color = RPCSXColors.textSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = onSave,
                        enabled = canSave,
                        shape = RoundedCornerShape(6.dp),
                        color = if (canSave) RPCSXColors.primary.copy(alpha = 0.20f) else Color(0x10FFFFFF),
                        border = BorderStroke(0.5.dp, if (canSave) RPCSXColors.primary.copy(alpha = 0.5f) else Color.Transparent)
                    ) {
                        Text(
                            "SAVE",
                            color = if (canSave) RPCSXColors.primary else Color.Gray,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        onClick = onLoad,
                        shape = RoundedCornerShape(6.dp),
                        color = if (slot.exists) Color(0x25FFFFFF) else Color(0x10FFFFFF),
                        border = BorderStroke(0.5.dp, if (slot.exists) Color(0x35FFFFFF) else Color.Transparent)
                    ) {
                        Text(
                            "LOAD",
                            color = if (slot.exists) RPCSXColors.textPrimary else Color.Gray,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
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
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x30000000))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(8.dp)),
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
                tint = RPCSXColors.textSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
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
