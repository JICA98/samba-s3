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
                if (suspendMode) {
                    // Suspend mode: single save-and-exit action, no slot grid.
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
                    // Multi-slot grid: SAVE + LOAD per slot (backend renames the
                    // fresh id-0 state into the requested slot after saving).
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(slots) { slot ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(slot.label, color = RPCSXColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (slot.exists) "Tap LOAD to restore" else "Empty",
                                        color = RPCSXColors.textSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        pendingSlot = slot.slot
                                        showSaveConfirm = true
                                    },
                                    enabled = canSave
                                ) {
                                    Text("SAVE", color = if (canSave) RPCSXColors.primary else Color.Gray)
                                }
                                TextButton(onClick = { onLoad(slot.slot) }, enabled = slot.exists) {
                                    Text("LOAD", color = if (slot.exists) RPCSXColors.textPrimary else Color.Gray)
                                }
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
            text = { Text(if (capabilities?.suspendMode == true) "Save and exit the game?" else "Save current emulation state to slot ${pendingSlot ?: 0}? The game will briefly pause while the state is saved.") },
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
