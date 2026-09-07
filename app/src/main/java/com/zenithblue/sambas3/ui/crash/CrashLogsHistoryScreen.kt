package com.zenithblue.sambas3.ui.crash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.zenithblue.sambas3.LogMonitor
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.crash.CrashClassification
import com.zenithblue.sambas3.crash.CrashReport
import com.zenithblue.sambas3.logging.LogSessionManifest
import com.zenithblue.sambas3.logging.LogSessionStore
import com.zenithblue.sambas3.logging.LogSessionTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CrashLogsHistoryScreen(
    navigateBack: () -> Unit,
    isInSplitPane: Boolean = false,
) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<LogSessionManifest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var filterCrashesOnly by remember { mutableStateOf(true) }
    var showClearConfirm by remember { mutableStateOf(false) }

    suspend fun loadSessions() {
        withContext(Dispatchers.IO) {
            val list = LogSessionStore.list(context)
            sessions = list
            if (selectedSessionId == null) {
                val firstMatch = if (filterCrashesOnly) {
                    list.firstOrNull { isCrashSession(it) }?.sessionId
                } else {
                    list.firstOrNull()?.sessionId
                }
                selectedSessionId = firstMatch
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadSessions()
    }

    val displaySessions = remember(sessions, filterCrashesOnly) {
        if (filterCrashesOnly) sessions.filter { isCrashSession(it) } else sessions
    }

    val selectedManifest = remember(sessions, selectedSessionId) {
        sessions.firstOrNull { it.sessionId == selectedSessionId }
    }

    val selectedReport = remember(selectedManifest) {
        selectedManifest?.let { reportForManifest(context, it) }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = RPCSXColors.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(RPCSXColors.surfaceElevated)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!isInSplitPane) {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_keyboard_arrow_left),
                                contentDescription = "Back",
                                tint = RPCSXColors.primary,
                            )
                        }
                    }
                    Text(
                        "CRASH LOGS & HISTORY",
                        color = RPCSXColors.primary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filterCrashesOnly,
                        onClick = {
                            filterCrashesOnly = !filterCrashesOnly
                            if (filterCrashesOnly && selectedManifest != null && !isCrashSession(selectedManifest)) {
                                selectedSessionId = sessions.firstOrNull { isCrashSession(it) }?.sessionId
                            }
                        },
                        label = {
                            Text(
                                if (filterCrashesOnly) "CRASHES ONLY" else "ALL SESSIONS",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RPCSXColors.primary.copy(alpha = 0.2f),
                            selectedLabelColor = RPCSXColors.primary,
                        ),
                    )

                    if (sessions.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = "Clear All Sessions",
                                tint = RPCSXColors.textSecondary,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RPCSXColors.primary)
                }
            } else if (displaySessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = null,
                            tint = RPCSXColors.textSecondary,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            if (filterCrashesOnly) "No crashed game sessions recorded." else "No log sessions found.",
                            color = RPCSXColors.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (filterCrashesOnly && sessions.isNotEmpty()) {
                            OutlinedButton(onClick = { filterCrashesOnly = false }) {
                                Text("SHOW ALL ${sessions.size} SESSIONS")
                            }
                        }
                    }
                }
            } else {
                // Two-Pane Master-Detail Layout (proportional for widescreen and constrained layouts)
                Row(Modifier.fillMaxSize()) {
                    // Left Pane: Session List (38% width)
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.38f)
                            .fillMaxHeight()
                            .background(RPCSXColors.surfaceElevated.copy(alpha = 0.5f))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(displaySessions, key = { it.sessionId }) { manifest ->
                            val isSelected = manifest.sessionId == selectedSessionId
                            CrashSessionCard(
                                manifest = manifest,
                                isSelected = isSelected,
                                onClick = { selectedSessionId = manifest.sessionId },
                            )
                        }
                    }

                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )

                    // Right Pane: Detail View / CrashLogPane (62% width)
                    Box(
                        modifier = Modifier
                            .weight(0.62f)
                            .fillMaxHeight()
                            .padding(12.dp),
                    ) {
                        if (selectedReport != null && selectedManifest != null) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f, fill = false)) {
                                        Text(
                                            selectedManifest.gameTitleSnapshot.uppercase(),
                                            color = RPCSXColors.primary,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            listOfNotNull(
                                                selectedManifest.titleId,
                                                formatDate(selectedManifest.startedAtMs),
                                                selectedManifest.terminalState.name,
                                            ).joinToString("  ·  "),
                                            color = RPCSXColors.textSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { shareSessionFiles(context, selectedManifest) },
                                            modifier = Modifier.height(32.dp),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_share),
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("SHARE", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                CrashLogPane(
                                    report = selectedReport,
                                    initialTab = 0,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Select a session to view logs", color = RPCSXColors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All Log Sessions?") },
            text = { Text("This will permanently remove saved session logs and crash reports.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        LogSessionStore.sessionsRoot(context).deleteRecursively()
                        sessions = emptyList()
                        selectedSessionId = null
                    }
                ) { Text("CLEAR ALL", color = RPCSXColors.errorColor) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("CANCEL") }
            },
        )
    }
}

@Composable
private fun CrashSessionCard(
    manifest: LogSessionManifest,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val isCrash = isCrashSession(manifest)
    val stateColor = when (manifest.terminalState) {
        LogSessionTerminal.CRASHED, LogSessionTerminal.FAILED -> RPCSXColors.errorColor
        LogSessionTerminal.INTERRUPTED -> RPCSXColors.primary
        LogSessionTerminal.RUNNING -> RPCSXColors.primaryMuted
        else -> RPCSXColors.textSecondary
    }

    val stateLabel = when (manifest.terminalState) {
        LogSessionTerminal.CRASHED -> "CRASHED"
        LogSessionTerminal.FAILED -> "FAILED"
        LogSessionTerminal.INTERRUPTED -> "STOPPED UNEXPECTEDLY"
        LogSessionTerminal.CLEAN_STOP -> "CLEAN STOP"
        LogSessionTerminal.RUNNING -> "RUNNING"
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) RPCSXColors.surfaceElevated else RPCSXColors.surface,
        ),
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) RPCSXColors.focusRing else if (isCrash) stateColor.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(RPCSXColors.surfaceOverlay),
                contentAlignment = Alignment.Center,
            ) {
                if (!manifest.gameIconSnapshotPath.isNullOrBlank() && File(manifest.gameIconSnapshotPath).isFile) {
                    AsyncImage(
                        model = File(manifest.gameIconSnapshotPath),
                        contentDescription = manifest.gameTitleSnapshot,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.gamepad),
                        contentDescription = null,
                        tint = RPCSXColors.textSecondary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        manifest.gameTitleSnapshot.uppercase(),
                        color = if (isSelected) RPCSXColors.primary else RPCSXColors.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stateLabel,
                        color = stateColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        fontWeight = FontWeight.Bold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        manifest.titleId ?: manifest.gamePath.substringAfterLast('/'),
                        color = RPCSXColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    )
                    Text(
                        formatDate(manifest.startedAtMs),
                        color = RPCSXColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    )
                }

                if (!manifest.stopReason.isNullOrBlank() && manifest.stopReason != "null") {
                    Text(
                        "Reason: ${manifest.stopReason}",
                        color = RPCSXColors.textSecondary.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun isCrashSession(manifest: LogSessionManifest): Boolean {
    return manifest.terminalState == LogSessionTerminal.CRASHED ||
        manifest.terminalState == LogSessionTerminal.FAILED ||
        manifest.terminalState == LogSessionTerminal.INTERRUPTED ||
        (manifest.stopReason != null && manifest.stopReason != "user-exit" && manifest.stopReason != "clean")
}

private fun formatDate(ms: Long): String {
    if (ms <= 0L) return "Unknown"
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(ms))
}

private fun reportForManifest(context: Context, manifest: LogSessionManifest): CrashReport {
    val dir = LogSessionStore.sessionDir(context, manifest.sessionId)
    val sources = mutableMapOf<String, File>()

    manifest.artifacts.forEach { artifact ->
        val file = File(artifact.path)
        if (file.isFile && file.length() > 0) {
            sources[artifact.id.ifBlank { file.name }] = file
        }
    }

    File(dir, "manifest.json").takeIf { it.isFile && it.length() > 0 }?.let {
        sources["manifest.json"] = it
    }

    dir.listFiles()?.forEach { file ->
        if (file.isFile && file.name != "game_icon.bin") {
            if (!sources.values.any { it.absolutePath == file.absolutePath }) {
                sources[file.name] = file
            }
        }
    }

    val crashDir = File(context.filesDir, "crash_reports/${manifest.sessionId}")
    if (crashDir.isDirectory) {
        crashDir.listFiles()?.forEach { file ->
            if (file.isFile && !sources.values.any { it.absolutePath == file.absolutePath }) {
                sources[file.name] = file
            }
        }
    }

    if (sources.isEmpty()) {
        LogMonitor.getAllLogFiles().forEach { file ->
            if (file.isFile && file.length() > 0) {
                sources[file.name] = file
            }
        }
    }

    val statusText = manifest.terminalState.name
    return CrashReport(
        directory = dir,
        classification = CrashClassification.UNEXPECTED_TERMINATION,
        summary = manifest.stopReason ?: statusText,
        cause = manifest.stopReason ?: "Session terminated ($statusText)",
        sources = sources,
        gameTitle = manifest.gameTitleSnapshot,
        titleId = manifest.titleId,
        gameIconPath = manifest.gameIconSnapshotPath,
    )
}

private fun shareSessionFiles(context: Context, manifest: LogSessionManifest) {
    val report = reportForManifest(context, manifest)
    val files = report.sources.values.filter { it.isFile && it.length() > 0 }
    if (files.isEmpty()) {
        Toast.makeText(context, "No log files available to share", Toast.LENGTH_SHORT).show()
        return
    }

    val authority = "${context.packageName}.provider"
    val uris = ArrayList<Uri>(files.size)
    for (file in files) {
        try {
            uris.add(FileProvider.getUriForFile(context, authority, file))
        } catch (_: Exception) {}
    }

    if (uris.isEmpty()) {
        Toast.makeText(context, "Cannot prepare log files for sharing", Toast.LENGTH_SHORT).show()
        return
    }

    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        context.startActivity(Intent.createChooser(intent, "Share Crash Logs"))
    } catch (_: Exception) {
        Toast.makeText(context, "No app available to handle sharing", Toast.LENGTH_SHORT).show()
    }
}
