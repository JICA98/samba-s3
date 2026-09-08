package com.zenithblue.sambas3.ui.logging

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.LogLevel
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.logging.LiveLogOverlaySettings
import com.zenithblue.sambas3.logging.LiveLogOverlaySettingsData
import com.zenithblue.sambas3.logging.LogBroker
import com.zenithblue.sambas3.logging.LogSourceKind
import kotlin.math.roundToInt

@Composable
fun LiveLogOverlay(
    settings: LiveLogOverlaySettingsData,
    menuOpen: Boolean,
    onSettingsChange: (LiveLogOverlaySettingsData) -> Unit,
) {
    if (!settings.enabled || (settings.hideWithMenu && menuOpen)) return
    DisposableEffect(Unit) {
        LogBroker.retainStream()
        onDispose {
            LogBroker.releaseStream()
        }
    }
    val snapshot by LogBroker.snapshot.collectAsStateWithLifecycle()
    val status by LogBroker.status.collectAsStateWithLifecycle()
    val minLevel = remember(settings.minLevel) {
        runCatching { LogLevel.valueOf(settings.minLevel) }.getOrDefault(LogLevel.INFO)
    }
    val source = remember(settings.sourceFilter) {
        runCatching { LogSourceKind.valueOf(settings.sourceFilter) }.getOrNull()
    }
    val sessionId = LogBroker.currentSessionId
    val visible = remember(snapshot, minLevel, source, settings.maxLines, sessionId) {
        snapshot.asReversed().asSequence()
            .filter { sessionId == null || it.sessionId == sessionId || it.sessionId == null }
            .filter { it.level.priority >= minLevel.priority }
            .filter { source == null || settings.sourceFilter == "ALL" || it.source == source }
            .take(settings.maxLines)
            .toList()
            .asReversed()
    }
    val dropped = LogBroker.engine.droppedCount()
    val persistDropped = LogBroker.engine.persistDroppedCount()
    val listState = rememberLazyListState()
    val lastEventId = visible.lastOrNull()?.sequence
    LaunchedEffect(lastEventId, settings.autoscroll) {
        if (settings.autoscroll && visible.isNotEmpty()) {
            listState.scrollToItem(visible.lastIndex)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val clamped = settings.reclampForWindow(constraints.maxWidth, constraints.maxHeight)
        val panelWidth = with(density) { (widthPx * clamped.widthFraction).toDp() }
        val panelHeight = with(density) { (heightPx * clamped.heightFraction).toDp() }
        val xPx = widthPx * clamped.xFraction
        val yPx = heightPx * clamped.yFraction
        Column(
            Modifier
                .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
                .width(panelWidth)
                .height(panelHeight)
                .alpha(clamped.opacity)
                .background(RPCSXColors.background.copy(alpha = 0.82f), RoundedCornerShape(6.dp))
                .border(1.dp, RPCSXColors.outlineVariant, RoundedCornerShape(6.dp))
                .padding(6.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (clamped.editMode) Modifier.pointerInput(clamped) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / widthPx
                                val dy = drag.y / heightPx
                                onSettingsChange(
                                    clamped.copy(
                                        xFraction = clamped.xFraction + dx,
                                        yFraction = clamped.yFraction + dy,
                                        locked = false,
                                    ).reclampForWindow(widthPx.toInt(), heightPx.toInt())
                                )
                            }
                        } else Modifier
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("LIVE LOGS")
                        if (dropped > 0) append(" · display $dropped")
                        if (persistDropped > 0) append(" · persist $persistDropped")
                    },
                    color = RPCSXColors.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (10 * clamped.fontScale).sp,
                )
                Text(
                    if (clamped.editMode) "EDIT" else if (clamped.locked) "LOCKED" else "",
                    color = RPCSXColors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (9 * clamped.fontScale).sp,
                )
            }
            val error = status.values.firstOrNull {
                it is com.zenithblue.sambas3.logging.LogSourceStatus.Unavailable ||
                    it is com.zenithblue.sambas3.logging.LogSourceStatus.ParseError
            }
            if (error is com.zenithblue.sambas3.logging.LogSourceStatus.Unavailable) {
                Text(error.reason, color = RPCSXColors.errorColor, fontFamily = FontFamily.Monospace, fontSize = (9 * clamped.fontScale).sp, maxLines = 1)
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(visible, key = { it.sequence }) { entry ->
                    Text(
                        "${entry.tag ?: entry.source.name}: ${entry.message}",
                        color = RPCSXColors.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = (9 * clamped.fontScale).sp,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
fun LiveLogOverlayHost(menuOpen: Boolean) {
    val context = LocalContext.current
    val settings by LiveLogOverlaySettings.state(context).collectAsStateWithLifecycle()
    LiveLogOverlay(
        settings = settings,
        menuOpen = menuOpen,
        onSettingsChange = { LiveLogOverlaySettings.write(context, it) },
    )
}
