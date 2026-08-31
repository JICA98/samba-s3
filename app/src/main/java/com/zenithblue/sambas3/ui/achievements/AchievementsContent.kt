package com.zenithblue.sambas3.ui.achievements

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import java.io.File

enum class AchievementFilter { ALL, UNLOCKED, LOCKED, BRONZE, SILVER, GOLD, PLATINUM }
enum class AchievementSort { DEFAULT, RECENT, GRADE, UNLOCKED_FIRST, LOCKED_FIRST }

object AchievementPresentation {
    fun filter(trophies: List<TrophyEntry>, filter: AchievementFilter, showHidden: Boolean): List<TrophyEntry> = trophies.filter { trophy ->
        (showHidden || !trophy.hidden) && when (filter) {
            AchievementFilter.ALL -> true
            AchievementFilter.UNLOCKED -> trophy.unlocked
            AchievementFilter.LOCKED -> !trophy.unlocked
            AchievementFilter.BRONZE -> trophy.grade == "bronze"
            AchievementFilter.SILVER -> trophy.grade == "silver"
            AchievementFilter.GOLD -> trophy.grade == "gold"
            AchievementFilter.PLATINUM -> trophy.grade == "platinum"
        }
    }

    fun sort(trophies: List<TrophyEntry>, sort: AchievementSort): List<TrophyEntry> = when (sort) {
        AchievementSort.DEFAULT -> trophies.sortedBy { it.id }
        AchievementSort.RECENT -> trophies.sortedWith(compareByDescending<TrophyEntry> { it.unlockTimestamp ?: 0L }.thenBy { it.id })
        AchievementSort.GRADE -> trophies.sortedWith(compareBy<TrophyEntry> { gradeOrder(it.grade) }.thenBy { it.id })
        AchievementSort.UNLOCKED_FIRST -> trophies.sortedWith(compareByDescending<TrophyEntry> { it.unlocked }.thenBy { it.id })
        AchievementSort.LOCKED_FIRST -> trophies.sortedWith(compareBy<TrophyEntry> { it.unlocked }.thenBy { it.id })
    }

    private fun gradeOrder(grade: String): Int = when (grade.lowercase()) {
        "platinum" -> 0
        "gold" -> 1
        "silver" -> 2
        "bronze" -> 3
        else -> 4
    }
}

@Composable
fun AchievementsContent(snapshot: TrophySnapshot?, loading: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf(AchievementFilter.ALL) }
    var sort by remember { mutableStateOf(AchievementSort.DEFAULT) }
    var showHidden by remember { mutableStateOf(false) }
    var selectedId by remember(snapshot?.titleId) { mutableStateOf<Int?>(null) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(snapshot?.gameName?.ifBlank { "ACHIEVEMENTS" }?.uppercase() ?: "ACHIEVEMENTS", color = RPCSXColors.primary, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                snapshot?.let { Text("${it.unlocked}/${it.total} unlocked · ${it.percent}%", color = RPCSXColors.textSecondary, fontSize = 12.sp) }
            }
            TextButton(onClick = onClose) { Text("CLOSE") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RPCSXColors.primary) }
            snapshot == null -> EmptyAchievements("Trophy data could not be read")
            snapshot.state != TrophySnapshotState.READY || snapshot.trophies.isEmpty() -> EmptyAchievements(snapshotStatus(snapshot))
            else -> {
                AchievementSummary(snapshot)
                FilterBar(filter, showHidden, sort, snapshot.trophies.any { it.unlockTimestamp != null }, { filter = it }, { showHidden = !showHidden }, { sort = it })
                val visible = AchievementPresentation.sort(AchievementPresentation.filter(snapshot.trophies, filter, showHidden), sort)
                if (visible.isEmpty()) EmptyAchievements("No trophies match this filter")
                else BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth >= 700.dp) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TrophyList(visible, selectedId, Modifier.widthIn(min = 280.dp, max = 420.dp).fillMaxHeight()) { selectedId = it.id }
                            Surface(Modifier.weight(1f).fillMaxHeight(), color = RPCSXColors.surface, shape = RoundedCornerShape(14.dp)) {
                                TrophyDetail(visible.firstOrNull { it.id == selectedId } ?: visible.first(), Modifier.fillMaxSize().padding(16.dp))
                            }
                        }
                    } else TrophyList(visible, selectedId, Modifier.fillMaxSize()) { selectedId = it.id }
                }
            }
        }
    }

    if (snapshot?.state == TrophySnapshotState.READY && snapshot.trophies.isNotEmpty() && selectedId != null) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < 700.dp) snapshot.trophies.firstOrNull { it.id == selectedId }?.let { selected ->
                AlertDialog(onDismissRequest = { selectedId = null }, title = { Text(selected.name) }, text = { TrophyDetail(selected, Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = { selectedId = null }) { Text("CLOSE") } })
            }
        }
    }
}

@Composable
private fun AchievementSummary(snapshot: TrophySnapshot) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("PROGRESS", color = RPCSXColors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(snapshot.titleId, color = RPCSXColors.textSecondary, fontSize = 11.sp) }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(RPCSXColors.surfaceOverlay)) { Box(Modifier.fillMaxWidth(snapshot.percent / 100f).fillMaxHeight().background(RPCSXColors.primary)) }
        Text("BRONZE ${snapshot.gradeCount("bronze", true)}  ·  SILVER ${snapshot.gradeCount("silver", true)}  ·  GOLD ${snapshot.gradeCount("gold", true)}  ·  PLATINUM ${snapshot.gradeCount("platinum", true)}", color = RPCSXColors.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun FilterBar(filter: AchievementFilter, showHidden: Boolean, sort: AchievementSort, hasTimestamps: Boolean, onFilter: (AchievementFilter) -> Unit, onHidden: () -> Unit, onSort: (AchievementSort) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(AchievementFilter.ALL, AchievementFilter.UNLOCKED, AchievementFilter.LOCKED, AchievementFilter.BRONZE, AchievementFilter.SILVER, AchievementFilter.GOLD, AchievementFilter.PLATINUM).forEach { candidate -> FilterChip(candidate.name, filter == candidate) { onFilter(candidate) } }
        FilterChip(if (showHidden) "HIDDEN ON" else "SHOW HIDDEN", showHidden, onHidden)
        val next = when (sort) {
            AchievementSort.DEFAULT -> AchievementSort.UNLOCKED_FIRST
            AchievementSort.UNLOCKED_FIRST -> AchievementSort.LOCKED_FIRST
            AchievementSort.LOCKED_FIRST -> if (hasTimestamps) AchievementSort.RECENT else AchievementSort.GRADE
            AchievementSort.RECENT -> AchievementSort.GRADE
            AchievementSort.GRADE -> AchievementSort.DEFAULT
        }
        FilterChip("SORT ${sort.name}", false) { onSort(next) }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(color = if (selected) RPCSXColors.primary.copy(alpha = .22f) else RPCSXColors.surface, shape = RoundedCornerShape(50), modifier = Modifier.clickable(onClick = onClick)) {
        Text(label, color = if (selected) RPCSXColors.primary else RPCSXColors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

@Composable
private fun TrophyList(trophies: List<TrophyEntry>, selectedId: Int?, modifier: Modifier, onSelected: (TrophyEntry) -> Unit) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { items(trophies, key = { it.id }) { trophy -> TrophyRow(trophy, selectedId == trophy.id) { onSelected(trophy) } } }
}

@Composable
private fun TrophyRow(trophy: TrophyEntry, selected: Boolean, onClick: () -> Unit) {
    val state = if (trophy.unlocked) "unlocked" else "locked"
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) RPCSXColors.primary.copy(alpha = .14f) else RPCSXColors.surface).clickable(onClick = onClick).padding(8.dp).semantics { contentDescription = "${trophy.name}, ${trophy.grade}, $state" }, verticalAlignment = Alignment.CenterVertically) {
        TrophyIcon(trophy, 56.dp); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(trophy.name, color = if (trophy.unlocked) RPCSXColors.textPrimary else RPCSXColors.textSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2); Text("${trophy.grade.uppercase()} · ${state.uppercase()}", color = if (trophy.unlocked) RPCSXColors.primary else RPCSXColors.textSecondary, fontSize = 10.sp) }
    }
}

@Composable
private fun TrophyDetail(trophy: TrophyEntry, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) { TrophyIcon(trophy, 82.dp); Text(trophy.name, color = RPCSXColors.textPrimary, style = MaterialTheme.typography.titleMedium); Text("${trophy.grade.uppercase()} · ${if (trophy.unlocked) "UNLOCKED" else "LOCKED"}", color = if (trophy.unlocked) RPCSXColors.primary else RPCSXColors.textSecondary, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text(trophy.description, color = RPCSXColors.textSecondary); if (trophy.hidden) Text("HIDDEN TROPHY", color = RPCSXColors.textSecondary, fontSize = 10.sp) }
}

@Composable
private fun TrophyIcon(trophy: TrophyEntry, size: Dp) {
    val context = LocalContext.current
    val path = trophy.iconPath?.takeIf { File(it).isFile }
    Box(Modifier.size(size).clip(RoundedCornerShape(9.dp)).background(Color.DarkGray).alpha(if (trophy.unlocked) 1f else .62f), contentAlignment = Alignment.Center) {
        if (path != null) AsyncImage(ImageRequest.Builder(context).data(File(path)).build(), contentDescription = "${trophy.name} icon", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = RPCSXColors.primary)
    }
}

@Composable
private fun EmptyAchievements(message: String) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message, color = RPCSXColors.textSecondary, modifier = Modifier.padding(20.dp)) } }

private fun snapshotStatus(snapshot: TrophySnapshot): String = when (snapshot.state) {
    TrophySnapshotState.INITIALIZING -> "Trophy data is still initializing"
    TrophySnapshotState.PARSE_ERROR -> "Failed to parse the installed trophy set"
    TrophySnapshotState.UNSUPPORTED -> "This runtime does not expose trophy data"
    TrophySnapshotState.EMPTY, TrophySnapshotState.NO_TROPHY_SET -> "No installed trophy set for this title"
    TrophySnapshotState.READY -> "No trophies available"
}
