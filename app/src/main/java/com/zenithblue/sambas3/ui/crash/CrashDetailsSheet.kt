package com.zenithblue.sambas3.ui.crash

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.crash.CrashEvidenceCollector
import com.zenithblue.sambas3.crash.CrashReport
import com.zenithblue.sambas3.session.EmulationSessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CrashDetailsSheet(
    session: EmulationSessionRecord?,
    initialReport: CrashReport?,
    loadFailure: String? = null,
    onChooseSave: (() -> Unit)? = null,
    onSafeRetry: (() -> Unit)? = null,
    onViewLogs: (() -> Unit)? = null,
    onOpenAllCrashLogs: (() -> Unit)? = null,
    onExportReport: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var report by remember(initialReport?.sessionId, initialReport?.revision, session?.sessionId) {
        mutableStateOf(initialReport)
    }
    LaunchedEffect(initialReport?.sessionId, initialReport?.revision, session?.sessionId) {
        if (report != null) return@LaunchedEffect
        val sessionId = session?.sessionId ?: return@LaunchedEffect
        report = withContext(Dispatchers.IO) {
            val manifest = com.zenithblue.sambas3.logging.LogSessionStore.read(context, sessionId)
            manifest?.let { CrashEvidenceCollector.reportForManifest(context, it) }
                ?: CrashEvidenceCollector.collectSummary(context, session)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .60f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(380.dp)
                .offset(x = (-40).dp, y = (-30).dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            RPCSXColors.primary.copy(alpha = 0.18f),
                            Color.Transparent,
                        )
                    ),
                    shape = CircleShape,
                )
        )
        Box(
            modifier = Modifier
                .size(400.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 60.dp, y = 40.dp)
                .blur(90.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0x301A3660),
                            Color.Transparent,
                        )
                    ),
                    shape = CircleShape,
                )
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, Color(0x38FFFFFF)),
            modifier = Modifier
                .fillMaxWidth(.95f)
                .fillMaxHeight(.94f)
                .padding(4.dp)
                .navigationBarsPadding(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xF20E1626),
                                    Color(0xF8080C14),
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val rawTitle = report?.gameTitle
                            ?: session?.gameName
                            ?: session?.gamePath?.substringAfterLast('/')
                            ?: "GAME"
                        val headerId = report?.titleId ?: session?.titleId
                        val displayTitle = if (rawTitle.isNotBlank()) rawTitle else headerId ?: "GAME"
                        val showId = !headerId.isNullOrBlank() && !headerId.equals(displayTitle, ignoreCase = true)

                        Column(Modifier.weight(1f)) {
                            Text(
                                "RECOVERY DETAILS",
                                color = RPCSXColors.primary,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                    fontSize = 11.sp,
                                ),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    displayTitle.uppercase(),
                                    color = RPCSXColors.textPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (showId) {
                                    Text(
                                        "($headerId)",
                                        color = RPCSXColors.textSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (onViewLogs != null) {
                                OutlinedButton(
                                    onClick = onViewLogs,
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("VIEW LOGS", fontSize = 10.sp) }
                            }
                            if (onExportReport != null) {
                                OutlinedButton(
                                    onClick = onExportReport,
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("EXPORT", fontSize = 10.sp) }
                            }
                            if (onOpenAllCrashLogs != null) {
                                OutlinedButton(
                                    onClick = onOpenAllCrashLogs,
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) { Text("ALL LOGS", fontSize = 10.sp) }
                            }
                            Surface(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0x22FFFFFF),
                                border = BorderStroke(1.dp, Color(0x35FFFFFF)),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription = "Close",
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        "CLOSE",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        color = Color(0x18FFFFFF),
                    )

                    if (loadFailure != null) {
                        Text(
                            loadFailure,
                            color = RPCSXColors.errorColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    val attached = report
                    if (attached != null && attached.sources.isEmpty()) {
                        Text(
                            "No log artifacts were attached to this session.",
                            color = RPCSXColors.errorColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }

                    // Main log pane with Left Sidebar taking all available space
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        report?.let { CrashLogPane(it, 0, Modifier.fillMaxSize()) }
                            ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Collecting diagnostics...", color = RPCSXColors.textSecondary)
                            }
                    }
                }
            }
        }
    }
}

@Composable
fun CrashLogPane(report: CrashReport, initialTab: Int, modifier: Modifier = Modifier) {
    val names = listOf("SUMMARY", "BACKEND", "VULKAN/GPU", "APP", "SYSTEM", "DEVICE")
    val viewKey = "${report.sessionId.orEmpty()}:${report.revision}:${report.directory.absolutePath}"
    var tab by remember(viewKey) { mutableIntStateOf(initialTab) }
    var query by remember(viewKey) { mutableStateOf("") }
    var offset by remember(viewKey, tab) { mutableStateOf(0L) }
    var totalBytes by remember(viewKey, tab) { mutableStateOf(0L) }
    var text by remember(viewKey, tab) { mutableStateOf("Loading log evidence…") }
    var isLoading by remember(viewKey, tab) { mutableStateOf(true) }
    var noMatch by remember(viewKey, tab) { mutableStateOf(false) }
    var selectedArtifact by remember(viewKey, tab) { mutableStateOf<String?>(null) }
    var searchToken by remember(viewKey, tab) { mutableIntStateOf(0) }

    LaunchedEffect(viewKey, tab, query, selectedArtifact, searchToken) {
        isLoading = true
        noMatch = false
        val resolved = resolveCategoryContent(report, tab, query, selectedArtifact, offset.takeIf { searchToken > 0 })
        offset = resolved.offset
        totalBytes = resolved.totalBytes
        text = resolved.text
        noMatch = resolved.noMatch
        isLoading = false
    }

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left Sidebar: Categories (minimal footprint)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0x18FFFFFF),
            border = BorderStroke(1.dp, Color(0x1EFFFFFF)),
            modifier = Modifier
                .width(132.dp)
                .fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "CATEGORIES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                    color = RPCSXColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )

                names.forEachIndexed { index, name ->
                    val isSelected = tab == index
                    Surface(
                        onClick = {
                            if (tab != index) {
                                tab = index
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) Color(0x28E5A93C) else Color.Transparent,
                        border = if (isSelected) BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.8f)) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = name,
                                color = if (isSelected) RPCSXColors.primary else RPCSXColors.textSecondary,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Right Main Area: Search + Complete Tall Log View
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            "Search ${names.getOrElse(tab) { "" }}…",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RPCSXColors.primary,
                        unfocusedBorderColor = Color(0x28FFFFFF),
                        focusedContainerColor = Color(0x18000000),
                        unfocusedContainerColor = Color(0x10000000),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        offset = 0L
                        searchToken++
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("BEGIN", fontSize = 10.sp) }
                OutlinedButton(
                    onClick = {
                        offset = (totalBytes - 256L * 1024L).coerceAtLeast(0L)
                        searchToken++
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("END", fontSize = 10.sp) }
                OutlinedButton(
                    onClick = {
                        offset = (offset - 256L * 1024L).coerceAtLeast(0L)
                        searchToken++
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("PREV", fontSize = 10.sp) }
                OutlinedButton(
                    onClick = {
                        offset = (offset + 256L * 1024L).coerceAtMost(totalBytes)
                        searchToken++
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("NEXT", fontSize = 10.sp) }
            }
            Text(
                text = buildString {
                    if (noMatch) append("No matches  ·  ")
                    if (totalBytes > 0L) append("Offset %,d B · %,d KB total".format(offset, (totalBytes + 1023) / 1024))
                    else append("Category overview")
                    report.diagnostics?.let { append("  ·  ${it.outcome.name}") }
                    report.rawStopReason?.let { append("  ·  reason=$it") }
                },
                color = if (noMatch) RPCSXColors.errorColor else RPCSXColors.textSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            )

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0x35000000),
                border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                key(report.directory.absolutePath, tab) {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                    ) {
                        item {
                            SelectionContainer {
                                Text(
                                    text = text,
                                    color = RPCSXColors.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
