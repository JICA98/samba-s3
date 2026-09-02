package com.zenithblue.sambas3.ui.games.launch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.ingame.SaveSlot
import java.io.File

@Composable
fun GameLaunchCenter(
    snapshot: GameLaunchSnapshot,
    onDismiss: () -> Unit,
    onFreshPlay: () -> Unit,
    onContinue: (SaveSlot) -> Unit,
    onLoad: (SaveSlot) -> Unit,
    onConfigure: () -> Unit,
    onDriver: () -> Unit,
    onPatches: () -> Unit,
    onAchievements: () -> Unit,
    onPrepare: (() -> Unit)? = null,
) {
    val ppuUi = snapshot.ppuUi
    val existingSaves = snapshot.saveSlots.filter { it.exists }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .78f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth(.95f)
                .widthIn(max = 960.dp)
                .padding(12.dp)
                .navigationBarsPadding(),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                // Fixed header
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (snapshot.game.info.name.value ?: "Unknown game").uppercase(),
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            snapshot.titleId ?: snapshot.game.info.path.substringAfterLast('/'),
                            color = RPCSXColors.textSecondary,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("CLOSE") }
                }
                HorizontalDivider(
                    Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                // Scrollable body
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "LAUNCH PROFILE",
                        color = RPCSXColors.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    snapshot.settings.forEach { setting ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(setting.label, color = RPCSXColors.textSecondary)
                            Text("${setting.value}  ${setting.source}", color = RPCSXColors.textPrimary)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("GPU driver", color = RPCSXColors.textSecondary)
                        Text(
                            snapshot.selectedDriver + if (snapshot.driverSysmem) "  SYSMEM" else "",
                            color = RPCSXColors.textPrimary,
                        )
                    }
                    OutlinedButton(onClick = onAchievements, modifier = Modifier.padding(top = 8.dp)) {
                        Text("ACHIEVEMENTS")
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "PPU PREPARATION",
                        color = RPCSXColors.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    PpuPhaseRow(ppuUi.installPpu)
                    PpuPhaseRow(ppuUi.runtimePpu)

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "SAVES",
                        color = RPCSXColors.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (existingSaves.isEmpty()) {
                        Text(
                            "No saved states yet",
                            color = RPCSXColors.textSecondary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(220.dp),
                            contentPadding = PaddingValues(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.height(180.dp),
                            userScrollEnabled = false,
                        ) {
                            items(existingSaves, key = { it.slot }) { slot ->
                                LaunchSaveCard(
                                    slot,
                                    enabled = snapshot.canLoadSave,
                                    onClick = { onLoad(slot) },
                                )
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onConfigure) { Text("CONFIGURE") }
                        OutlinedButton(onClick = onDriver) { Text("DRIVER") }
                        OutlinedButton(onClick = onPatches) { Text("PATCHES") }
                    }
                }

                // Fixed primary-action footer — START always visible
                HorizontalDivider(
                    Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (ppuUi.prepareAction) {
                        PrepareAction.ReimportOrRebuild -> {
                            if (onPrepare != null) {
                                OutlinedButton(onClick = onPrepare) { Text("RE-IMPORT") }
                            }
                        }
                        PrepareAction.PreparingInstall -> {
                            OutlinedButton(onClick = {}, enabled = false) { Text("PREPARING PPU…") }
                        }
                        PrepareAction.PreparingRuntime -> {
                            OutlinedButton(onClick = {}, enabled = false) { Text("PREPARING RUNTIME PPU…") }
                        }
                        null -> Unit
                    }
                    snapshot.latestSave?.let { slot ->
                        if (existingSaves.isNotEmpty()) {
                            Button(
                                onClick = { onContinue(slot) },
                                enabled = snapshot.canLoadSave,
                            ) { Text("CONTINUE SLOT ${slot.slot}") }
                        }
                    }
                    val startLabel = when (ppuUi.primaryStartLabel) {
                        PrimaryStartLabel.StartAndPrepare -> "START & PREPARE"
                        PrimaryStartLabel.RetryOnStart -> "RETRY ON START"
                        PrimaryStartLabel.Start -> "START"
                    }
                    Button(
                        onClick = onFreshPlay,
                        enabled = ppuUi.startEnabled && snapshot.canPlayFresh,
                    ) { Text(startLabel) }
                }
                val footerStatus = snapshot.blockReason ?: ppuUi.statusLine
                if (footerStatus != null) {
                    Text(
                        footerStatus.uppercase(),
                        color = RPCSXColors.errorColor,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PpuPhaseRow(phase: PpuPhaseUi) {
    val color = when (phase.state) {
        PpuPhaseState.Ready -> RPCSXColors.primary
        PpuPhaseState.Failed -> RPCSXColors.errorColor
        PpuPhaseState.Compiling, PpuPhaseState.Preparing, PpuPhaseState.Finalizing -> RPCSXColors.textPrimary
        else -> RPCSXColors.textSecondary
    }
    val statusText = when {
        phase.state == PpuPhaseState.Compiling && phase.progress != null ->
            "Compiling  ${phase.progress}%"
        phase.detail != null -> phase.detail
        else -> phase.state.name
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(phase.label, color = RPCSXColors.textSecondary)
        Text(statusText, color = color)
    }
}

@Composable
private fun LaunchSaveCard(slot: SaveSlot, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(86.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(RPCSXColors.surfaceOverlay),
                contentAlignment = Alignment.Center,
            ) {
                if (slot.previewPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(slot.previewPath))
                            .memoryCacheKey("${slot.previewPath}:${slot.previewMtimeMs}")
                            .build(),
                        contentDescription = "Saved game preview for Slot ${slot.slot}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_save),
                        "Slot ${slot.slot} placeholder",
                        tint = RPCSXColors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("SLOT ${slot.slot}", color = RPCSXColors.textPrimary)
        }
    }
}
