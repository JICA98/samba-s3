package com.zenithblue.sambas3.ui.games.launch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.ingame.TrophiesData
import java.io.File

@Composable
fun StoppedTrophiesDialog(data: TrophiesData?, loading: Boolean, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .78f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(.92f).fillMaxSize(.86f)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            data?.gameName?.ifBlank { "ACHIEVEMENTS" }?.uppercase() ?: "ACHIEVEMENTS",
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (data?.available == true) {
                            Text("${data.unlocked}/${data.total} unlocked · ${data.percent}%", color = RPCSXColors.textSecondary, fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("CLOSE") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RPCSXColors.primary)
                    }
                    data?.available != true -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when (data?.status) {
                                "parse_failed" -> "Failed to parse the installed trophy set"
                                "unsupported" -> "This runtime does not expose trophy data"
                                else -> "No installed trophy set for this title"
                            },
                            color = RPCSXColors.textSecondary
                        )
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                        items(data.trophies, key = { it.id }) { trophy ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                    .background(
                                        if (trophy.unlocked) RPCSXColors.surface else RPCSXColors.surface.copy(alpha = .5f),
                                        RoundedCornerShape(12.dp)
                                    ).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                                    val path = trophy.iconPath?.takeIf { File(it).isFile }
                                    if (path != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current).data(File(path)).build(),
                                            contentDescription = trophy.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = RPCSXColors.primary)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(trophy.name, color = if (trophy.unlocked) RPCSXColors.textPrimary else RPCSXColors.textSecondary, style = MaterialTheme.typography.titleSmall)
                                    Text("${trophy.grade.uppercase()}${if (trophy.unlocked) " · UNLOCKED" else " · LOCKED"}", color = RPCSXColors.primary, fontSize = 10.sp)
                                    Text(trophy.description, color = RPCSXColors.textSecondary, fontSize = 12.sp, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
