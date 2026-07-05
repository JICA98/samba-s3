package com.zenithblue.sambas3.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.zenithblue.sambas3.LogEntry
import com.zenithblue.sambas3.LogFileCategory
import com.zenithblue.sambas3.LogLevel
import com.zenithblue.sambas3.LogMonitor
import com.zenithblue.sambas3.LogSource
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors

// ---------------------------------------------------------------------------
// Level / source color helpers
// ---------------------------------------------------------------------------
private fun LogLevel.color(): Color = when (this) {
    LogLevel.VERBOSE -> Color(0xFF607D8B)
    LogLevel.DEBUG -> Color(0xFF78909C)
    LogLevel.INFO -> Color(0xFF80CBC4)
    LogLevel.WARN -> Color(0xFFFFB74D)
    LogLevel.ERROR -> Color(0xFFEF5350)
    LogLevel.FATAL -> Color(0xFFFF1744)
}

private fun LogLevel.bgColor(): Color = when (this) {
    LogLevel.WARN -> Color(0xFF1A1200)
    LogLevel.ERROR -> Color(0xFF1A0000)
    LogLevel.FATAL -> Color(0xFF1A0000)
    else -> Color.Transparent
}

private fun LogSource.color(): Color = when (this) {
    LogSource.APP -> Color(0xFFC9A84C)
    LogSource.RPCSX -> Color(0xFF4FC3F7)
    LogSource.VULKAN -> Color(0xFFCE93D8)
    LogSource.DRIVER -> Color(0xFF80CBC4)
    LogSource.KERNEL -> Color(0xFFA5D6A7)
    LogSource.CELL -> Color(0xFFFFCC80)
    LogSource.OTHER -> Color(0xFF78909C)
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogMonitorScreen(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    isInSplitPane: Boolean = false,
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    var selectedSource by remember { mutableStateOf<LogSource?>(null) }
    var autoScroll by remember { mutableStateOf(true) }

    val allLogs by LogMonitor.logs.collectAsState()
    val filtered by remember(allLogs, selectedLevel, selectedSource) {
        derivedStateOf {
            allLogs.filter { e ->
                (selectedLevel == null || e.level == selectedLevel) &&
                        (selectedSource == null || e.source == selectedSource)
            }
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.size - 1)
        }
    }

    @Composable
    fun LogContent(contentPadding: PaddingValues) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(RPCSXColors.background)
        ) {
            // Level filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LevelFilterChip(
                    label = stringResource(R.string.log_level_all),
                    selected = selectedLevel == null
                ) { selectedLevel = null }
                LogLevel.entries.forEach { level ->
                    LevelFilterChip(
                        label = level.name,
                        selected = selectedLevel == level,
                        color = level.color()
                    ) { selectedLevel = if (selectedLevel == level) null else level }
                }
            }

            // Source filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LevelFilterChip(
                    label = stringResource(R.string.log_level_all),
                    selected = selectedSource == null
                ) { selectedSource = null }
                LogSource.entries.forEach { src ->
                    LevelFilterChip(
                        label = src.label,
                        selected = selectedSource == src,
                        color = src.color()
                    ) { selectedSource = if (selectedSource == src) null else src }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().height(1.dp).background(RPCSXColors.outlineVariant)) {}
            Text(
                text = "${filtered.size} entries",
                color = RPCSXColors.textDisabled,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = null,
                            tint = RPCSXColors.textDisabled,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.log_no_entries),
                            color = RPCSXColors.textDisabled,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(filtered, key = { _, e -> e.id }) { index, entry ->
                        LogEntryRow(entry = entry, index = index)
                    }
                }
            }

            LogActionBar(
                autoScroll = autoScroll,
                onAutoScrollToggle = { autoScroll = !autoScroll },
                onClear = { LogMonitor.clearLogs() },
            )
        }
    }

    @Composable
    fun TopBar(compact: Boolean = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(RPCSXColors.background)
                .drawBehind {
                    drawLine(
                        color = RPCSXColors.outlineVariant,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = navigateBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard_arrow_left),
                    contentDescription = null,
                    tint = RPCSXColors.primary
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_terminal),
                contentDescription = null,
                tint = RPCSXColors.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.log_monitor).uppercase(),
                color = RPCSXColors.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 15.sp else 18.sp,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.weight(1f))
            LiveIndicator()
            Spacer(Modifier.width(12.dp))
        }
    }

    if (isInSplitPane) {
        Column(modifier = modifier.fillMaxSize()) {
            TopBar(compact = true)
            LogContent(contentPadding = PaddingValues(0.dp))
        }
    } else {
        Scaffold(
            modifier = modifier.nestedScroll(topBarScrollBehavior.nestedScrollConnection),
            topBar = { TopBar() },
            bottomBar = {
                ControllerHintStrip(hints = listOf(R.drawable.circle to "Back"))
            }
        ) { padding ->
            LogContent(contentPadding = padding)
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun LiveIndicator() {
    val inf = rememberInfiniteTransition(label = "live")
    val alpha by inf.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .size(7.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(Color(0xFF4CAF50), CircleShape)
        )
        Text(
            text = "LIVE",
            color = Color(0xFF4CAF50).copy(alpha = alpha),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelFilterChip(
    label: String,
    selected: Boolean,
    color: Color = RPCSXColors.primary,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.15f),
            selectedLabelColor = color,
            containerColor = RPCSXColors.surfaceElevated,
            labelColor = RPCSXColors.textSecondary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            selectedBorderColor = color.copy(alpha = 0.6f),
            borderColor = RPCSXColors.outlineVariant,
            borderWidth = 0.5.dp,
            selectedBorderWidth = 1.dp
        ),
        modifier = Modifier.height(28.dp)
    )
}

@Composable
private fun LogEntryRow(entry: LogEntry, index: Int) {
    val bg = if (index % 2 == 0) Color.Transparent else RPCSXColors.surface.copy(alpha = 0.3f)
    val lvlBg = entry.level.bgColor()
    val rowBg = if (lvlBg != Color.Transparent) lvlBg else bg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.width(18.dp).wrapContentHeight().padding(top = 1.dp), Alignment.Center) {
            Text(
                entry.level.letter.toString(),
                color = entry.level.color(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            entry.timestamp,
            color = RPCSXColors.textDisabled,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 1,
            modifier = Modifier.width(110.dp).padding(top = 1.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            entry.source.name.take(4),
            color = entry.source.color(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            modifier = Modifier.width(32.dp).padding(top = 1.dp)
        )
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.tag,
                color = RPCSXColors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.message,
                color = RPCSXColors.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun LogActionBar(
    autoScroll: Boolean,
    onAutoScrollToggle: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(RPCSXColors.surfaceContainerHigh)
            .drawBehind {
                drawLine(
                    color = RPCSXColors.outlineVariant,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(
            onClick = onClear,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_delete),
                null,
                tint = RPCSXColors.textSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.log_clear).uppercase(),
                color = RPCSXColors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (autoScroll) RPCSXColors.primaryMuted else Color.Transparent)
                .border(
                    0.5.dp,
                    if (autoScroll) RPCSXColors.primary.copy(0.5f) else RPCSXColors.outlineVariant,
                    RoundedCornerShape(4.dp)
                )
                .clickable(onClick = onAutoScrollToggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_keyboard_arrow_down),
                null,
                tint = if (autoScroll) RPCSXColors.primary else RPCSXColors.textSecondary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                stringResource(R.string.log_auto_scroll).uppercase(),
                color = if (autoScroll) RPCSXColors.primary else RPCSXColors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (autoScroll) FontWeight.Bold else FontWeight.Normal,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.width(4.dp))

        IconButton(
            onClick = {
                val files = LogMonitor.getAllLogFiles()
                if (files.isEmpty()) return@IconButton
                val uris = ArrayList<Uri>(
                    files.map { file ->
                        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    }
                )
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/plain"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.log_share_all))
                )
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painterResource(R.drawable.ic_share),
                stringResource(R.string.log_share_all),
                tint = RPCSXColors.textSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
