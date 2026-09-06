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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.crash.CrashEvidenceCollector
import com.zenithblue.sambas3.crash.CrashLogReader
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
    var report by remember(initialReport, session?.sessionId) { mutableStateOf(initialReport) }
    LaunchedEffect(session?.sessionId, initialReport) {
        if (session != null) {
            report = withContext(Dispatchers.IO) {
                runCatching { CrashEvidenceCollector.collect(context, session) }.getOrNull()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .78f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth(.92f)
                .fillMaxHeight(.90f)
                .widthIn(max = 920.dp)
                .padding(4.dp)
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
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
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
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
                    TextButton(onClick = onDismiss) {
                        Text("CLOSE", color = RPCSXColors.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }

                HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
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

                // Action buttons row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    onChooseSave?.let {
                        OutlinedButton(
                            onClick = it,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                        ) { Text("CHOOSE SAVE", style = MaterialTheme.typography.labelSmall) }
                    }
                    onSafeRetry?.let {
                        OutlinedButton(
                            onClick = it,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                        ) { Text("SAFE RETRY", style = MaterialTheme.typography.labelSmall) }
                    }
                    onOpenAllCrashLogs?.let {
                        OutlinedButton(
                            onClick = it,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                        ) { Text("ALL CRASH LOGS", style = MaterialTheme.typography.labelSmall) }
                    }
                    onExportReport?.let {
                        OutlinedButton(
                            onClick = it,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                        ) { Text("EXPORT REPORT", style = MaterialTheme.typography.labelSmall) }
                    }
                }

                // Log pane taking all available space
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

                // Footer
                HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RPCSXColors.primary,
                            contentColor = Color.Black,
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "CLOSE",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CrashLogPane(report: CrashReport, initialTab: Int, modifier: Modifier = Modifier) {
    val names = listOf("SUMMARY", "BACKEND", "VULKAN/GPU", "APP", "SYSTEM", "DEVICE")
    var tab by remember(report.directory.absolutePath) { mutableIntStateOf(initialTab) }
    var query by remember(report.directory.absolutePath) { mutableStateOf("") }
    var offset by remember(report.directory.absolutePath, tab) { mutableStateOf(0L) }
    var totalBytes by remember(report.directory.absolutePath, tab) { mutableStateOf(0L) }
    var text by remember(report.directory.absolutePath, tab) { mutableStateOf("Loading log evidence…") }
    var isLoading by remember(report.directory.absolutePath, tab) { mutableStateOf(true) }

    LaunchedEffect(report.directory.absolutePath, tab, query) {
        isLoading = true
        Log.i("CrashLogUI", "Category switched: tab=$tab (${names.getOrElse(tab) { "UNKNOWN" }}), dir=${report.directory.name}, query='$query'")
        val (foundOffset, fileBytes, content) = resolveCategoryContent(report, tab, query)
        offset = foundOffset
        totalBytes = fileBytes
        text = content
        isLoading = false
        Log.i("CrashLogUI", "Category loaded: tab=$tab (${names.getOrElse(tab) { "UNKNOWN" }}), offset=$offset, bytes=$totalBytes, chars=${content.length}")
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 0.dp,
            containerColor = Color.Transparent,
            contentColor = RPCSXColors.primary,
            indicator = { tabPositions ->
                if (tab in tabPositions.indices) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[tab]),
                        color = RPCSXColors.primary,
                    )
                }
            },
            divider = {},
        ) {
            names.forEachIndexed { index, name ->
                Tab(
                    selected = tab == index,
                    onClick = {
                        if (tab != index) {
                            Log.i("CrashLogUI", "Tab clicked: switching from $tab to $index ($name)")
                            tab = index
                        }
                    },
                    text = {
                        Text(
                            name,
                            color = if (tab == index) RPCSXColors.primary else RPCSXColors.textSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search this category…", style = MaterialTheme.typography.bodySmall) },
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RPCSXColors.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (totalBytes > 0L) "Offset %,d B · %,d KB total".format(offset, (totalBytes + 1023) / 1024)
                else "Showing category info",
                color = RPCSXColors.textSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            )
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = RPCSXColors.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
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
                        .padding(8.dp),
                ) {
                    item {
                        SelectionContainer {
                            Text(
                                text = text,
                                color = RPCSXColors.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun resolveCategoryContent(
    report: CrashReport,
    tabIndex: Int,
    query: String,
): Triple<Long, Long, String> = withContext(Dispatchers.IO) {
    val names = listOf("SUMMARY", "BACKEND", "VULKAN/GPU", "APP", "SYSTEM", "DEVICE")
    val tabName = names.getOrElse(tabIndex) { "UNKNOWN" }
    Log.d("CrashLogUI", "resolveCategoryContent started for tab=$tabIndex ($tabName), dir=${report.directory.name}, query='$query'")

    when (tabIndex) {
        0 -> { // SUMMARY
            val summaryFile = report.sources["summary.txt"]
            val rawText = if (summaryFile != null && summaryFile.isFile && summaryFile.length() > 0) {
                val reader = CrashLogReader(summaryFile)
                val off = if (query.isNotBlank()) reader.find(query).coerceAtLeast(0L) else 0L
                reader.read(off, 256 * 1024)
            } else ""

            val cleanSummary = if (rawText.isNotBlank()) {
                rawText.lines().filter { line ->
                    !line.contains("PNG") &&
                    !line.contains("IHDR") &&
                    !line.contains("IDAT") &&
                    line.none { it.code in 0..8 || it.code in 14..31 }
                }.joinToString("\n").trim()
            } else ""

            val synthesized = buildString {
                appendLine("══════════════════════════════════════════════════════════════")
                appendLine("                 SAMBAS3 SESSION DIAGNOSTICS                  ")
                appendLine("══════════════════════════════════════════════════════════════")
                appendLine()
                appendLine("Game:            ${report.gameTitle ?: "Unknown"}")
                appendLine("Title ID:        ${report.titleId ?: "Unknown"}")
                appendLine("Classification:  ${report.classification.name.replace('_', ' ')}")
                appendLine("Status Summary:  ${report.summary}")
                appendLine("Likely Cause:    ${report.cause}")
                appendLine("Session Folder:  ${report.directory.name}")
                appendLine()
                if (cleanSummary.isNotBlank()) {
                    appendLine("--------------------------------------------------------------")
                    appendLine("INCIDENT SUMMARY")
                    appendLine("--------------------------------------------------------------")
                    appendLine(cleanSummary)
                    appendLine()
                }
                appendLine("--------------------------------------------------------------")
                appendLine("CAPTURED LOG ARTIFACTS (${report.sources.size})")
                appendLine("--------------------------------------------------------------")
                if (report.sources.isEmpty()) {
                    appendLine("No log artifacts were captured for this session.")
                } else {
                    report.sources.forEach { (name, file) ->
                        val sizeStr = if (file.isFile) "%,d bytes (%.1f KB)".format(file.length(), file.length() / 1024.0) else "Missing"
                        appendLine("  • %-26s : %s".format(name, sizeStr))
                    }
                }
                appendLine()
                appendLine("--------------------------------------------------------------")
                appendLine("SUBSYSTEM NAVIGATION")
                appendLine("--------------------------------------------------------------")
                appendLine("Select the BACKEND, APP, or DEVICE tabs above to view individual")
                appendLine("subsystem logs and telemetry.")
            }
            Log.i("CrashLogUI", "SUMMARY synthesized diagnostic overview (${report.sources.size} artifacts)")
            Triple(0L, synthesized.toByteArray().size.toLong(), synthesized)
        }
        1 -> { // BACKEND
            val file = report.sources.entries.firstOrNull {
                it.key.contains("backend", ignoreCase = true) ||
                it.key.equals("RPCSX.log", ignoreCase = true) ||
                it.key.equals("RPCS3.log", ignoreCase = true) ||
                it.key.contains("tty", ignoreCase = true)
            }?.value ?: report.sources.values.firstOrNull { it.isFile && it.name.endsWith(".log") && !it.name.contains("app", ignoreCase = true) }

            if (file != null && file.isFile) {
                val reader = CrashLogReader(file)
                val off = if (query.isNotBlank()) reader.find(query).coerceAtLeast(0L) else 0L
                val text = reader.read(off, 256 * 1024)
                Log.i("CrashLogUI", "BACKEND matched file='${file.name}' size=${file.length()} off=$off")
                Triple(off, file.length(), text.ifEmpty { "[BACKEND LOG EMPTY]\n\nFile is 0 bytes." })
            } else {
                Log.w("CrashLogUI", "BACKEND: no backend log file found in sources=${report.sources.keys}")
                val msg = "[BACKEND / ENGINE SUBSYSTEM]\n\nNo RPCSX engine log was captured for this session.\n\nCaptured files: ${report.sources.keys.joinToString(", ")}"
                Triple(0L, 0L, msg)
            }
        }
        2 -> { // VULKAN/GPU
            val file = report.sources.entries.firstOrNull {
                it.key.contains("vulkan", ignoreCase = true) ||
                it.key.contains("gpu", ignoreCase = true) ||
                it.key.contains("turnip", ignoreCase = true) ||
                it.key.contains("mesa", ignoreCase = true) ||
                it.key.contains("freedreno", ignoreCase = true) ||
                it.key.contains("adreno", ignoreCase = true)
            }?.value

            if (file != null && file.isFile) {
                val reader = CrashLogReader(file)
                val off = if (query.isNotBlank()) reader.find(query).coerceAtLeast(0L) else 0L
                val text = reader.read(off, 256 * 1024)
                Log.i("CrashLogUI", "VULKAN/GPU matched file='${file.name}' size=${file.length()}")
                Triple(off, file.length(), text.ifEmpty { "[VULKAN LOG EMPTY]" })
            } else {
                Log.i("CrashLogUI", "VULKAN/GPU: no dedicated file in sources=${report.sources.keys}; showing diagnostic note")
                val msg = buildString {
                    appendLine("[VULKAN / GPU SUBSYSTEM]")
                    appendLine("--------------------------------------------------------------")
                    appendLine("No standalone GPU driver log file was captured for this session.")
                    appendLine()
                    appendLine("Subsystem Notes:")
                    appendLine("• Vulkan instance initialization, swapchain, and pipeline compile")
                    appendLine("  events are logged directly into the BACKEND log.")
                    appendLine("• Dedicated Turnip / Adreno driver logs appear when custom driver")
                    appendLine("  logging or validation layers are enabled.")
                    appendLine("• Switch to the BACKEND tab to inspect graphics engine events.")
                }
                Triple(0L, 0L, msg)
            }
        }
        3 -> { // APP
            val file = report.sources.entries.firstOrNull {
                it.key.contains("app", ignoreCase = true) ||
                it.key.contains("sambas3", ignoreCase = true) ||
                it.key.contains("android", ignoreCase = true) ||
                it.key.contains("ui", ignoreCase = true)
            }?.value

            if (file != null && file.isFile) {
                val reader = CrashLogReader(file)
                val off = if (query.isNotBlank()) reader.find(query).coerceAtLeast(0L) else 0L
                val text = reader.read(off, 256 * 1024)
                Log.i("CrashLogUI", "APP matched file='${file.name}' size=${file.length()}")
                Triple(off, file.length(), text.ifEmpty { "[APP LOG EMPTY]" })
            } else {
                Log.w("CrashLogUI", "APP: no app log file in sources=${report.sources.keys}")
                val msg = "[APP / FRONTEND SUBSYSTEM]\n\nNo Android UI logs recorded for this session."
                Triple(0L, 0L, msg)
            }
        }
        4 -> { // SYSTEM
            val file = report.sources.entries.firstOrNull {
                it.key.contains("system", ignoreCase = true) ||
                it.key.contains("broker-ring-tail", ignoreCase = true) ||
                it.key.contains("logcat", ignoreCase = true) ||
                it.key.contains("ring", ignoreCase = true) ||
                it.key.contains("tombstone", ignoreCase = true)
            }?.value

            if (file != null && file.isFile) {
                val reader = CrashLogReader(file)
                val off = if (query.isNotBlank()) reader.find(query).coerceAtLeast(0L) else 0L
                val text = reader.read(off, 256 * 1024)
                Log.i("CrashLogUI", "SYSTEM matched file='${file.name}' size=${file.length()}")
                Triple(off, file.length(), text.ifEmpty { "[SYSTEM LOG EMPTY]" })
            } else {
                Log.i("CrashLogUI", "SYSTEM: no system dump file in sources=${report.sources.keys}")
                val msg = buildString {
                    appendLine("[SYSTEM / OS SUBSYSTEM]")
                    appendLine("--------------------------------------------------------------")
                    appendLine("No system logcat or ring tail dump was recorded.")
                    appendLine()
                    appendLine("Subsystem Notes:")
                    appendLine("• Process exit dumps are captured on unexpected native termination.")
                    appendLine("• Real-time application log stream is available under Settings > Logs.")
                }
                Triple(0L, 0L, msg)
            }
        }
        5 -> { // DEVICE
            val file = report.sources.entries.firstOrNull {
                it.key.equals("metadata.json", ignoreCase = true) ||
                it.key.equals("manifest.json", ignoreCase = true) ||
                it.key.contains("metadata", ignoreCase = true) ||
                it.key.contains("manifest", ignoreCase = true)
            }?.value

            if (file != null && file.isFile) {
                val text = runCatching { file.readText() }.getOrDefault("")
                Log.i("CrashLogUI", "DEVICE matched file='${file.name}' size=${file.length()}")
                Triple(0L, file.length(), text.ifEmpty { "[METADATA EMPTY]" })
            } else {
                Log.i("CrashLogUI", "DEVICE: no metadata.json in sources; showing device telemetry")
                val msg = buildString {
                    appendLine("[DEVICE & ENVIRONMENT TELEMETRY]")
                    appendLine("--------------------------------------------------------------")
                    appendLine("Device Model   : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
                    appendLine("Android OS     : Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("Board / SoC    : ${android.os.Build.BOARD} / ${android.os.Build.HARDWARE}")
                    appendLine("Supported ABIs : ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
                    appendLine("App Version    : ${com.zenithblue.sambas3.BuildConfig.VERSION_NAME} (${com.zenithblue.sambas3.BuildConfig.VERSION_CODE})")
                    appendLine("Flavor         : ${com.zenithblue.sambas3.BuildConfig.FLAVOR}")
                    appendLine("Session Folder : ${report.directory.name}")
                }
                Triple(0L, 0L, msg)
            }
        }
        else -> {
            Triple(0L, 0L, "Unknown category")
        }
    }
}
