package com.zenithblue.sambas3.ui.games.launch

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .78f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(22.dp), color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(.95f).widthIn(max = 960.dp).padding(12.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text((snapshot.game.info.name.value ?: "Unknown game").uppercase(), color = RPCSXColors.primary, style = MaterialTheme.typography.headlineSmall)
                        Text(snapshot.titleId ?: snapshot.game.info.path.substringAfterLast('/'), color = RPCSXColors.textSecondary)
                    }
                    TextButton(onClick = onDismiss) { Text("CLOSE") }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("LAUNCH PROFILE", color = RPCSXColors.primary, style = MaterialTheme.typography.titleMedium)
                snapshot.settings.forEach { setting ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(setting.label, color = RPCSXColors.textSecondary)
                        Text("${setting.value}  ${setting.source}", color = RPCSXColors.textPrimary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("GPU driver", color = RPCSXColors.textSecondary)
                    Text(snapshot.selectedDriver + if (snapshot.driverSysmem) "  SYSMEM" else "", color = RPCSXColors.textPrimary)
                }
                OutlinedButton(onClick = onAchievements, modifier = Modifier.padding(top = 8.dp)) { Text("ACHIEVEMENTS") }
                Text("PPU readiness: ${snapshot.ppuStatus}", color = if (snapshot.ppuStatus == "Ready") RPCSXColors.primary else RPCSXColors.textSecondary, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp))
                Text("SAVES", color = RPCSXColors.primary, style = MaterialTheme.typography.titleMedium)
                LazyVerticalGrid(columns = GridCells.Adaptive(220.dp), contentPadding = PaddingValues(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(180.dp)) {
                    items(snapshot.saveSlots.filter { it.exists }, key = { it.slot }) { slot ->
                        LaunchSaveCard(slot, enabled = snapshot.canLoadSave, onClick = { onLoad(slot) })
                    }
                }
                if (snapshot.saveSlots.none { it.exists }) Text("No saved states yet", color = RPCSXColors.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onConfigure) { Text("CONFIGURE") }
                    OutlinedButton(onClick = onDriver) { Text("DRIVER") }
                    OutlinedButton(onClick = onPatches) { Text("PATCHES") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    snapshot.latestSave?.let { Button(onClick = { onContinue(it) }, enabled = snapshot.canLoadSave) { Text("CONTINUE SLOT ${it.slot}") } }
                    Button(onClick = onFreshPlay, enabled = snapshot.canPlayFresh) { Text("PLAY FRESH") }
                }
                snapshot.blockReason?.let { Text(it, color = RPCSXColors.errorColor, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

@Composable
private fun LaunchSaveCard(slot: SaveSlot, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(onClick = onClick, enabled = enabled, colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(86.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(RPCSXColors.surfaceOverlay), contentAlignment = Alignment.Center) {
                if (slot.previewPath != null) AsyncImage(model = ImageRequest.Builder(context).data(File(slot.previewPath)).memoryCacheKey("${slot.previewPath}:${slot.previewMtimeMs}").build(), contentDescription = "Saved game preview for Slot ${slot.slot}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else Icon(painterResource(R.drawable.ic_save), "Slot ${slot.slot} placeholder", tint = RPCSXColors.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Text("SLOT ${slot.slot}", color = RPCSXColors.textPrimary)
        }
    }
}
