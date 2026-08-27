package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RPCSXColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun InGameTrophiesPage(onBack: () -> Unit) {
    var data by remember { mutableStateOf<TrophiesData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showHidden by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        data = withContext(Dispatchers.IO) {
            TrophiesData.fromJson(try { RPCSX.instance.getCurrentTrophies() } catch (_: Exception) { null })
        }
        loading = false
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.70f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(0.96f).fillMaxHeight(0.92f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = data?.gameName?.takeIf { it.isNotBlank() }?.uppercase() ?: "TROPHIES",
                        color = RPCSXColors.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (data?.available == true) {
                        Text(
                            text = "${data!!.unlocked}/${data!!.total} ${data!!.percent}%",
                            color = RPCSXColors.textPrimary,
                            fontSize = 12.sp
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RPCSXColors.primary)
                    }
                } else if (data?.available != true) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No trophies available", color = RPCSXColors.textSecondary)
                    }
                } else {
                    // Hidden toggle
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { showHidden = !showHidden }) {
                            Text(if (showHidden) "Hide Hidden" else "Show Hidden")
                        }
                        TextButton(onClick = onBack) { Text("Back") }
                    }
                    val filtered = if (showHidden) data!!.trophies else data!!.trophies.filter { !it.hidden }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(filtered) { trophy ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).background(
                                    if (trophy.unlocked) RPCSXColors.surface else RPCSXColors.surface.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp)
                                ).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(painter = painterResource(id = R.drawable.ic_star), contentDescription = null, tint = RPCSXColors.primary)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = trophy.name,
                                        color = if (trophy.unlocked) RPCSXColors.textPrimary else RPCSXColors.textSecondary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 2
                                    )
                                    Text(
                                        text = "${trophy.grade.uppercase()} ${if (trophy.platinumRelevant) "• Platinum relevant" else ""}",
                                        color = RPCSXColors.textSecondary,
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = trophy.description, color = RPCSXColors.textSecondary, fontSize = 12.sp, maxLines = 3)
                                    if (trophy.unlocked) {
                                        Text("UNLOCKED", color = RPCSXColors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Text("LOCKED", color = Color.Gray, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
