package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.RPCSXColors

@Composable
fun InGameSaveStatePage(
    capabilities: SaveStateCapabilities?,
    onBack: () -> Unit,
    onSave: (Int) -> Unit,
    onLoad: (Int) -> Unit
) {
    var showSaveConfirm by remember { mutableStateOf(false) }
    var pendingSlot by remember { mutableStateOf<Int?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.70f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(0.92f).heightIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("SAVE STATE", color = RPCSXColors.primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
                    TextButton(onClick = onBack) { Text("Back") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val slots = capabilities?.slots ?: emptyList()
                val suspendMode = capabilities?.suspendMode == true
                val canSave = capabilities?.canSave != false
                // Save button
                TextButton(
                    onClick = {
                        pendingSlot = 0
                        showSaveConfirm = true
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text((if (suspendMode) "SAVE STATE AND EXIT" else "SAVE STATE").uppercase(), color = if (canSave) RPCSXColors.primary else Color.Gray)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                // Load slots
                if (slots.isEmpty() || slots.none { it.exists }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No saved states", color = RPCSXColors.textSecondary)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(slots.filter { it.exists }) { slot ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(slot.label, color = RPCSXColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("Slot ${slot.slot}", color = RPCSXColors.textSecondary, fontSize = 12.sp)
                                }
                                TextButton(onClick = { onLoad(slot.slot) }) { Text("LOAD") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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
            text = { Text(if (capabilities?.suspendMode == true) "Save and exit the game?" else "Save current emulation state? Note: will restart.") },
            confirmButton = {
                TextButton(onClick = {
                    showSaveConfirm = false
                    pendingSlot?.let { onSave(it) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveConfirm = false }) { Text("Cancel") } }
        )
    }
}
