package com.zenithblue.sambas3.ui.crash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.crash.CrashEvidenceCollector
import com.zenithblue.sambas3.crash.CrashLogReader
import com.zenithblue.sambas3.crash.CrashReport
import com.zenithblue.sambas3.session.EmulationSessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashDetailsSheet(
    session: EmulationSessionRecord?,
    initialReport: CrashReport?,
    loadFailure: String? = null,
    onChooseSave: (() -> Unit)? = null,
    onViewLogs: (() -> Unit)? = null,
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 680.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("RECOVERY DETAILS", color = RPCSXColors.primary)
            if (loadFailure != null) Text(loadFailure, color = RPCSXColors.errorColor)
            report?.let { CrashLogPane(it, 0, Modifier.weight(1f)) }
                ?: Text("Collecting diagnostics...", color = RPCSXColors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onChooseSave?.let { OutlinedButton(onClick = it) { Text("CHOOSE SAVE") } }
                onViewLogs?.let { OutlinedButton(onClick = it) { Text("VIEW LOGS") } }
            }
            Button(onClick = onDismiss) { Text("CLOSE") }
        }
    }
}

@Composable
fun CrashLogPane(report: CrashReport, initialTab: Int, modifier: Modifier = Modifier) {
    val names = listOf("SUMMARY", "BACKEND", "VULKAN/GPU", "APP", "SYSTEM", "DEVICE")
    var tab by remember(report.directory) { mutableIntStateOf(initialTab) }
    var query by remember(report.directory) { mutableStateOf("") }
    var offset by remember(report.directory) { mutableStateOf(0L) }
    var text by remember(report.directory, tab) { mutableStateOf("Loading evidence...") }
    val file = when (tab) {
        1 -> report.sources.entries.firstOrNull { it.key.contains("backend", true) }?.value
        2 -> report.sources.entries.firstOrNull { it.key.contains("vulkan", true) }?.value
        3 -> report.sources.entries.firstOrNull { it.key.contains("app", true) }?.value
        4 -> report.sources.entries.firstOrNull { it.key.contains("system", true) }?.value
        5 -> report.sources["metadata.json"]
        else -> report.sources["summary.txt"] ?: report.sources.values.firstOrNull()
    }
    LaunchedEffect(file, query) {
        val reader = file?.let(::CrashLogReader)
        offset = if (reader != null && query.isNotBlank()) reader.find(query) ?: 0L else 0L
        text = reader?.read(offset, 256 * 1024) ?: "No evidence captured."
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            names.forEachIndexed { index, name ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(name) })
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search this source") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Offset $offset bytes · showing up to 256 KB", color = RPCSXColors.textSecondary)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
            item { Text(text, color = RPCSXColors.textPrimary) }
        }
    }
}
