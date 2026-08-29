package com.zenithblue.sambas3.ui.crash

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zenithblue.sambas3.crash.CrashLogReader
import com.zenithblue.sambas3.crash.CrashReport
import com.zenithblue.sambas3.RPCSXColors
import java.io.File

@Composable
fun GameCrashScreen(
    report: CrashReport,
    onRetry: () -> Unit,
    onSafeRetry: () -> Unit,
    onContinue: () -> Unit,
    onChooseSave: () -> Unit,
    onConfigure: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val names = listOf("SUMMARY", "BACKEND", "VULKAN/GPU", "APP", "SYSTEM", "DEVICE")
    Surface(Modifier.fillMaxSize(), color = RPCSXColors.background) {
        Column(Modifier.fillMaxSize().padding(20.dp).widthIn(max = 960.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GAME STOPPED UNEXPECTEDLY", color = RPCSXColors.errorColor, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            Text(report.summary, color = Color.White)
            Text("Likely cause: ${report.cause}", color = RPCSXColors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("RETRY") }
                OutlinedButton(onClick = onSafeRetry) { Text("SAFE RETRY") }
                OutlinedButton(onClick = onContinue) { Text("CONTINUE SAVE") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChooseSave) { Text("CHOOSE SAVE") }
                OutlinedButton(onClick = onConfigure) { Text("CONFIGURE") }
                OutlinedButton(onClick = onExit) { Text("EXIT") }
                OutlinedButton(onClick = { shareCrashReport(context, report) }) { Text("EXPORT LOGS") }
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                names.forEachIndexed { index, name -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(name) }) }
            }
            CrashLogPane(report, tab, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun CrashLogPane(report: CrashReport, tab: Int, modifier: Modifier) {
    val file = when (tab) {
        1 -> report.sources.entries.firstOrNull { it.key.contains("backend") }?.value
        2 -> report.sources.entries.firstOrNull { it.key.contains("vulkan") }?.value
        3 -> report.sources.entries.firstOrNull { it.key.contains("app") }?.value
        4 -> report.sources.entries.firstOrNull { it.key.contains("system") }?.value
        5 -> report.sources["metadata.json"]
        else -> java.io.File(report.directory, "summary.txt")
    }
    var query by remember(file) { mutableStateOf("") }
    var offset by remember(file) { mutableStateOf(0L) }
    var text by remember(file) { mutableStateOf("Loading evidence...") }
    LaunchedEffect(file, query) {
        val reader = file?.let(::CrashLogReader)
        offset = if (reader != null && query.isNotBlank()) reader.find(query) ?: 0L else 0L
        text = reader?.read(offset, 256 * 1024) ?: "No evidence captured."
    }
    Column(modifier.background(RPCSXColors.surface).padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search this source") },
            modifier = Modifier.fillMaxWidth()
        )
        Text("Offset $offset bytes · showing up to 256 KB", color = RPCSXColors.textSecondary, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(Modifier.weight(1f)) {
            item { Text(text, color = RPCSXColors.textPrimary) }
        }
    }
}

private fun shareCrashReport(context: Context, report: CrashReport) {
    val files = report.sources.values + File(report.directory, "summary.txt") + File(report.directory, "metadata.json")
    val uris = files.filter { it.isFile }.mapNotNull { runCatching { FileProvider.getUriForFile(context, "${context.packageName}.provider", it) }.getOrNull() }
    if (uris.isEmpty()) return
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, "Export diagnostic report"))
}
