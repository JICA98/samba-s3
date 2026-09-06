package com.zenithblue.sambas3.ui.games.launch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.games.preview.GamePreviewModel
import com.zenithblue.sambas3.ui.games.preview.GamePreviewRepository
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
            shape = RoundedCornerShape(16.dp),
            color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth(.92f)
                .fillMaxHeight(.90f)
                .widthIn(max = 920.dp)
                .padding(4.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // LEFT PANE: Game Card, Title, ID, and Secondary Action Buttons
                Column(
                    modifier = Modifier
                        .width(260.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Game Artwork Card (matching home screen GameCard style)
                        val rawIconPath = snapshot.game.info.iconPath.value
                        val installedPreview = remember(rawIconPath) {
                            GamePreviewRepository.resolveInstalledPreview(rawIconPath)
                        }
                        val coilModel: Any? = when (installedPreview) {
                            is GamePreviewModel.LocalFile -> installedPreview.file
                            is GamePreviewModel.ContentUri -> installedPreview.uri
                            is GamePreviewModel.None -> null
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = RPCSXColors.surface,
                            border = BorderStroke(1.dp, RPCSXColors.surfaceOverlay),
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(10.dp)),
                        ) {
                            if (coilModel != null) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // Blurred ambient background
                                    AsyncImage(
                                        model = coilModel,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .scale(1.3f)
                                            .blur(radius = 16.dp)
                                            .alpha(0.45f),
                                    )
                                    // Dark contrast overlay
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.25f)),
                                    )
                                    // Crisp foreground artwork
                                    AsyncImage(
                                        model = coilModel,
                                        contentDescription = "Game cover",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(6.dp),
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(RPCSXColors.surfaceOverlay),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.gamepad),
                                        contentDescription = null,
                                        tint = RPCSXColors.textSecondary,
                                        modifier = Modifier.size(44.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            GameIdentity.displayName(
                                snapshot.game.info.path,
                                snapshot.game.info.name.value,
                            ).uppercase(),
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            snapshot.titleId ?: snapshot.game.info.path.substringAfterLast('/'),
                            color = RPCSXColors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    // Quick action buttons at bottom of left pane
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = onConfigure,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text("CONFIG", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = onDriver,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text("DRIVER", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = onPatches,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text("PATCHES", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = onAchievements,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text("TROPHIES", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }

                // VERTICAL DIVIDER
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                )

                // RIGHT PANE: Header, Settings (2-col), PPU, Saves, and Footer Actions
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    // Top Header Row
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "LAUNCH PROFILE",
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("CLOSE", color = RPCSXColors.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    HorizontalDivider(
                        Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    // Scrollable Middle Body
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Settings split into 2 compact columns
                        val allSettings = buildList {
                            addAll(snapshot.settings)
                            add(
                                LaunchSetting(
                                    label = "GPU driver",
                                    value = snapshot.selectedDriver + if (snapshot.driverSysmem) " (SYSMEM)" else "",
                                    source = "",
                                )
                            )
                        }
                        val mid = (allSettings.size + 1) / 2
                        val col1 = allSettings.take(mid)
                        val col2 = allSettings.drop(mid)

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                col1.forEach { setting -> SettingRow(setting) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                col2.forEach { setting -> SettingRow(setting) }
                            }
                        }

                        // PPU Preparation
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "PPU PREPARATION",
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        PpuPhaseRow(ppuUi.installPpu)
                        PpuPhaseRow(ppuUi.runtimePpu)

                        // Saves
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "SAVES",
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (existingSaves.isEmpty()) {
                            Text(
                                "No saved states yet",
                                color = RPCSXColors.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                existingSaves.forEach { slot ->
                                    LaunchSaveCard(
                                        slot,
                                        enabled = snapshot.canLoadSave,
                                        onClick = { onLoad(slot) },
                                    )
                                }
                            }
                        }
                    }

                    // Fixed Footer
                    HorizontalDivider(
                        Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val footerStatus = snapshot.blockReason ?: ppuUi.statusLine
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        ) {
                            if (footerStatus != null) {
                                Text(
                                    footerStatus.uppercase(),
                                    color = if (snapshot.blockReason != null) RPCSXColors.errorColor else RPCSXColors.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            when (ppuUi.prepareAction) {
                                PrepareAction.Prepare -> {
                                    if (onPrepare != null) {
                                        OutlinedButton(
                                            onClick = onPrepare,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(8.dp),
                                        ) { Text("PREPARE PPU", style = MaterialTheme.typography.labelMedium) }
                                    }
                                }
                                PrepareAction.PreparingInstall -> {
                                    OutlinedButton(
                                        onClick = {},
                                        enabled = false,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) { Text("PREPARING PPU…", style = MaterialTheme.typography.labelMedium) }
                                }
                                PrepareAction.PreparingRuntime -> {
                                    OutlinedButton(
                                        onClick = {},
                                        enabled = false,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) { Text("PREPARING RUNTIME PPU…", style = MaterialTheme.typography.labelMedium) }
                                }
                                null -> Unit
                            }
                            snapshot.latestSave?.let { slot ->
                                if (existingSaves.isNotEmpty()) {
                                    Button(
                                        onClick = { onContinue(slot) },
                                        enabled = snapshot.canLoadSave,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = RPCSXColors.surfaceOverlay,
                                            contentColor = RPCSXColors.textPrimary,
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("CONTINUE ${slot.slot}", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            Button(
                                onClick = onFreshPlay,
                                enabled = ppuUi.startEnabled && snapshot.canPlayFresh,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RPCSXColors.primary,
                                    contentColor = Color.Black,
                                    disabledContainerColor = RPCSXColors.primary.copy(alpha = 0.35f),
                                    disabledContentColor = Color.Black.copy(alpha = 0.35f),
                                ),
                                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    "START",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(setting: LaunchSetting) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            setting.label,
            color = RPCSXColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                setting.value,
                color = RPCSXColors.textPrimary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (setting.source.isNotBlank()) {
                Text(
                    setting.source,
                    color = if (setting.source == "GAME") RPCSXColors.primary else RPCSXColors.textSecondary.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    maxLines = 1,
                )
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
    val statusText = LaunchPpuPresentation.phaseStatusText(phase)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(phase.label, color = RPCSXColors.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(statusText, color = color, style = MaterialTheme.typography.bodySmall)
        }
        if (phase.state == PpuPhaseState.Compiling && phase.progress != null && phase.progress > 0) {
            val pct = phase.progress
            LinearProgressIndicator(
                progress = { (pct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = RPCSXColors.primary,
                trackColor = RPCSXColors.surfaceOverlay,
            )
        }
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
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(72.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(4.dp))
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
            Spacer(Modifier.width(6.dp))
            Text("SLOT ${slot.slot}", color = RPCSXColors.textPrimary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
