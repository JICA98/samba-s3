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
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (totalBytes > 0L) "Offset %,d B · %,d KB total".format(offset, (totalBytes + 1023) / 1024)
                    else "Category overview",
                    color = RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                )
            }

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
