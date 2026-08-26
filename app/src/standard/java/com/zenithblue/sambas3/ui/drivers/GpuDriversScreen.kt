package com.zenithblue.sambas3.ui.drivers

import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.drivers.catalog.DriverCatalogRepository
import com.zenithblue.sambas3.drivers.catalog.DriverCatalogSnapshot
import com.zenithblue.sambas3.drivers.catalog.DriverGpuFilter
import com.zenithblue.sambas3.drivers.catalog.DriverSourceId
import com.zenithblue.sambas3.drivers.catalog.DriverVariantFilter
import com.zenithblue.sambas3.drivers.catalog.RemoteDriverPackage
import com.zenithblue.sambas3.drivers.download.DriverDownloader
import com.zenithblue.sambas3.drivers.download.DriverPackageAdapter
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GeneralSettings.string
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverInstallResult
import com.zenithblue.sambas3.utils.GpuDriverSelection
import java.io.File
import java.io.FileInputStream

private enum class DriverTab { Installed, Browse }

private sealed class DownloadUiState {
    object Idle : DownloadUiState()
    data class Downloading(val bytesRead: Long, val total: Long?, val statusText: String, val progress: Float?) : DownloadUiState()
    object Verifying : DownloadUiState()
    object Extracting : DownloadUiState()
    object Installing : DownloadUiState()
    object Installed : DownloadUiState()
    data class Failed(val message: String) : DownloadUiState()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GpuDriversScreen(
    navigateBack: () -> Unit,
    isInSplitPane: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var drivers by remember { mutableStateOf(GpuDriverHelper.getInstalledDrivers(context)) }
    var selectedDriver by remember { mutableStateOf(GeneralSettings["selected_gpu_driver"].string("Default")) }
    var selectedTab by remember { mutableStateOf(DriverTab.Installed) }
    var isInstalling by remember { mutableStateOf(false) }

    // Browse state
    var snapshot by remember { mutableStateOf<DriverCatalogSnapshot?>(null) }
    var isLoadingCatalog by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<DriverSourceId?>(null) }
    var selectedGpu by remember { mutableStateOf(DriverGpuFilter.ALL) }
    var selectedVariant by remember { mutableStateOf(DriverVariantFilter.ALL) }
    var hideExperimental by remember { mutableStateOf(false) }
    var latestOnly by remember { mutableStateOf(false) }
    var downloadStates by remember { mutableStateOf<Map<String, DownloadUiState>>(emptyMap()) }

    // Keep selectedDriver in sync when selection changes externally
    LaunchedEffect(Unit) {
        // Initial refresh of installed after bundled sync if needed
        drivers = GpuDriverHelper.getInstalledDrivers(context)
        selectedDriver = GeneralSettings["selected_gpu_driver"].string("Default")
    }

    fun refreshInstalled() {
        scope.launch(Dispatchers.IO) {
            val updated = GpuDriverHelper.getInstalledDrivers(context)
            val sel = GeneralSettings["selected_gpu_driver"].string("Default")
            withContext(Dispatchers.Main) {
                drivers = updated
                selectedDriver = sel
            }
        }
    }

    // Catalog loader
    fun loadCatalog() {
        if (isLoadingCatalog) return
        isLoadingCatalog = true
        fetchError = null
        scope.launch(Dispatchers.IO) {
            try {
                val snap = DriverCatalogRepository.refresh()
                withContext(Dispatchers.Main) {
                    snapshot = snap
                    if (snap.packages.isEmpty()) {
                        fetchError = "No drivers found. Check network or try again."
                    } else {
                        for (s in snap.sources) {
                            Log.i("DriverCatalog", "UI source ${s.source} count=${s.packages.size} err=${s.error}")
                        }
                    }
                    isLoadingCatalog = false
                }
            } catch (e: Exception) {
                Log.e("DriverCatalog", "refresh failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    fetchError = e.message ?: "Failed to fetch drivers"
                    isLoadingCatalog = false
                }
            }
        }
    }

    // Auto-load when Browse first selected
    LaunchedEffect(selectedTab) {
        if (selectedTab == DriverTab.Browse && snapshot == null && !isLoadingCatalog) {
            loadCatalog()
        }
    }

    val driverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isInstalling = true
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val result = GpuDriverHelper.installDriver(context, stream)
                        if (result == GpuDriverInstallResult.Success) {
                            val updated = GpuDriverHelper.getInstalledDrivers(context)
                            withContext(Dispatchers.Main) {
                                drivers = updated
                                selectedDriver = GeneralSettings["selected_gpu_driver"].string("Default")
                            }
                        }
                        withContext(Dispatchers.Main) {
                            isInstalling = false
                            snackbarHostState.showSnackbar(
                                message = GpuDriverHelper.resolveInstallResultToString(result)
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GpuDriver", "Error installing driver: ${e.message}")
                    withContext(Dispatchers.Main) { isInstalling = false }
                }
            }
        }
    }

    // Download helper
    fun startDownload(pkg: RemoteDriverPackage) {
        if (downloadStates[pkg.id] is DownloadUiState.Downloading) return
        downloadStates = downloadStates + (pkg.id to DownloadUiState.Downloading(0, null, "Downloading...", null))
        scope.launch(Dispatchers.IO) {
            val safeName = pkg.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
            val ext = when (pkg.archiveFormat) {
                com.zenithblue.sambas3.drivers.catalog.DriverArchiveFormat.TZST -> ".tzst"
                else -> ".zip"
            }
            val cacheDir = File(context.cacheDir, "driver_downloads").apply { mkdirs() }
            val driverFile = File(cacheDir, "$safeName$ext")
            if (driverFile.exists()) driverFile.delete()
            val tmpAdapted = File(cacheDir, "$safeName.adapted.zip")

            // Update inline progress
            val dlResult = DriverDownloader.download(
                url = pkg.downloadUrl,
                destFile = driverFile,
                expectedSha256 = pkg.checksum?.takeIf { it.algorithm == com.zenithblue.sambas3.drivers.catalog.ChecksumAlgorithm.SHA256 }?.value ?: pkg.sha256,
                progress = { bytesRead, total ->
                    val prog = if (total != null && total > 0) (bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                    val status = if (total != null && total > 0) "${bytesRead / 1024} KiB / ${total / 1024} KiB" else "${bytesRead / 1024} KiB"
                    downloadStates = downloadStates + (pkg.id to DownloadUiState.Downloading(bytesRead, total, status, prog))
                }
            )

            when (dlResult) {
                is DriverDownloader.Result.Success -> {
                    withContext(Dispatchers.Main) { downloadStates = downloadStates + (pkg.id to DownloadUiState.Verifying) }
                    // Adapt (handles TZST -> ZIP)
                    val adaptResult = DriverPackageAdapter.adapt(driverFile, tmpAdapted, pkg.checksum)
                    val fileToInstall = when (adaptResult) {
                        is DriverPackageAdapter.Result.Success -> {
                            Log.i("DriverDownload", "Adapt success ${pkg.displayName} format=${pkg.archiveFormat}")
                            adaptResult.adaptedFile
                        }
                        is DriverPackageAdapter.Result.Error -> {
                            Log.w("DriverDownload", "Adapt failed for ${pkg.displayName}: ${adaptResult.message}")
                            if (pkg.archiveFormat == com.zenithblue.sambas3.drivers.catalog.DriverArchiveFormat.ZIP) driverFile else null
                        }
                    }
                    if (fileToInstall == null) {
                        val msg = (adaptResult as? DriverPackageAdapter.Result.Error)?.message ?: "Unsupported package layout"
                        withContext(Dispatchers.Main) {
                            downloadStates = downloadStates + (pkg.id to DownloadUiState.Failed(msg))
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) { downloadStates = downloadStates + (pkg.id to DownloadUiState.Installing) }
                        val installResult = withContext(Dispatchers.IO) {
                            try { FileInputStream(fileToInstall).use { ins -> GpuDriverHelper.installDriver(context, ins) } }
                            catch (e: Exception) { GpuDriverInstallResult.InvalidArchive }
                        }
                        withContext(Dispatchers.Main) {
                            if (installResult == GpuDriverInstallResult.Success) {
                                downloadStates = downloadStates + (pkg.id to DownloadUiState.Installed)
                                Toast.makeText(context, GpuDriverHelper.resolveInstallResultToString(installResult), Toast.LENGTH_SHORT).show()
                                // Refresh installed
                                scope.launch(Dispatchers.IO) {
                                    val updated = GpuDriverHelper.getInstalledDrivers(context)
                                    val sel = GeneralSettings["selected_gpu_driver"].string("Default")
                                    withContext(Dispatchers.Main) {
                                        drivers = updated
                                        selectedDriver = sel
                                    }
                                }
                                // After 2s reset to idle so DOWNLOAD becomes SELECT
                                scope.launch {
                                    kotlinx.coroutines.delay(1500)
                                    downloadStates = downloadStates - pkg.id
                                }
                            } else {
                                val msg = GpuDriverHelper.resolveInstallResultToString(installResult)
                                downloadStates = downloadStates + (pkg.id to DownloadUiState.Failed(msg))
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    try { driverFile.delete() } catch (_: Exception) {}
                    try { tmpAdapted.delete() } catch (_: Exception) {}
                }
                is DriverDownloader.Result.Error -> {
                    withContext(Dispatchers.Main) {
                        val msg = dlResult.message ?: "Download failed"
                        downloadStates = downloadStates + (pkg.id to DownloadUiState.Failed(msg))
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                    try { driverFile.delete() } catch (_: Exception) {}
                }
                is DriverDownloader.Result.Canceled -> {
                    withContext(Dispatchers.Main) { downloadStates = downloadStates - pkg.id }
                    try { driverFile.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    fun isPackageInstalled(pkg: RemoteDriverPackage): Boolean {
        // Heuristic: installed label/name contains pkg displayName or id, or file size matches? Keep simple.
        return drivers.values.any { meta ->
            meta.label.equals(pkg.id, ignoreCase = true) ||
                meta.label.equals(pkg.displayName, ignoreCase = true) ||
                meta.name.equals(pkg.displayName, ignoreCase = true) ||
                meta.uiTitle.equals(pkg.displayName, ignoreCase = true) ||
                (pkg.displayName.length > 5 && meta.label.contains(pkg.displayName.take(12), ignoreCase = true))
        }
    }

    fun installedLabelFor(pkg: RemoteDriverPackage): String? {
        val match = drivers.entries.firstOrNull { (_, meta) ->
            meta.label.equals(pkg.id, ignoreCase = true) ||
                meta.label.equals(pkg.displayName, ignoreCase = true) ||
                meta.name.equals(pkg.displayName, ignoreCase = true) ||
                meta.uiTitle.equals(pkg.displayName, ignoreCase = true) ||
                (pkg.displayName.length > 5 && meta.label.contains(pkg.displayName.take(12), ignoreCase = true))
        }
        return match?.value?.label
    }

    @Composable
    fun InstalledTabContent() {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(drivers.entries.toList(), key = { it.key.path }) { (file, metadata) ->
                val isSystem = metadata.name == "Default"
                val isSelected = metadata.label == selectedDriver || (isSystem && selectedDriver == "Default")
                val canDelete = metadata.name != "Default" && !metadata.isBundled
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable {
                            // Card tap also selects for convenience, but explicit button is primary (spec)
                            val ok = GpuDriverSelection.selectDriver(
                                context = context,
                                metadata = metadata,
                                driverDir = if (isSystem) null else file,
                                nativeLibraryDir = RPCSX.nativeLibDirectory,
                                forceSysmem = false
                            )
                            if (!ok) {
                                AlertDialogQueue.showDialog(
                                    context.getString(R.string.error),
                                    context.getString(R.string.failed_to_load_selected_driver)
                                )
                            } else {
                                selectedDriver = if (isSystem) "Default" else metadata.label
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = metadata.uiTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = metadata.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (metadata.driverVersion.isNotEmpty()) {
                                    Text(
                                        text = "Version ${metadata.driverVersion}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (metadata.isBundled) {
                                    Text(
                                        text = "Included with Samba S3",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // Explicit SELECT button (required)
                            if (isSelected) {
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    colors = ButtonDefaults.buttonColors(
                                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                                        disabledContentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) { Text("SELECTED", fontSize = 12.sp) }
                            } else {
                                Button(
                                    onClick = {
                                        val ok = GpuDriverSelection.selectDriver(
                                            context = context,
                                            metadata = metadata,
                                            driverDir = if (isSystem) null else file,
                                            nativeLibraryDir = RPCSX.nativeLibDirectory,
                                            forceSysmem = false
                                        )
                                        if (!ok) {
                                            AlertDialogQueue.showDialog(
                                                context.getString(R.string.error),
                                                context.getString(R.string.failed_to_load_selected_driver)
                                            )
                                        } else {
                                            selectedDriver = if (isSystem) "Default" else metadata.label
                                        }
                                    }
                                ) { Text("SELECT", fontSize = 12.sp) }
                            }
                        }
                        if (canDelete) {
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            if (GpuDriverHelper.deleteDriver(context, file, metadata)) {
                                                val updated = GpuDriverHelper.getInstalledDrivers(context)
                                                val sel = GeneralSettings["selected_gpu_driver"].string("Default")
                                                withContext(Dispatchers.Main) {
                                                    drivers = updated
                                                    selectedDriver = sel
                                                    // If deleted was selected, fallback to system already handled via ensureValidSelection?
                                                }
                                            }
                                        }
                                    }
                                ) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { driverPickerLauncher.launch("application/zip") },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !isInstalling
                ) {
                    if (isInstalling) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.installing))
                    } else {
                        Icon(painter = painterResource(id = R.drawable.ic_add), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("IMPORT DRIVER")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Import a Samba ZIP driver package from storage. Standard builds only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    fun BrowseTabContent() {
        val allPackages = snapshot?.packages ?: emptyList()
        val filtered = remember(allPackages, searchQuery, selectedSource, selectedGpu, selectedVariant, hideExperimental, latestOnly) {
            DriverCatalogRepository.filterDrivers(allPackages, searchQuery, selectedSource, selectedGpu, selectedVariant, hideExperimental, latestOnly)
        }

        // One scroll container: header + list together — avoids nested weight issues
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search drivers...") },
                    placeholder = { Text("name, version, source, GPU") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("Source", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val sources = listOf(null to "All") + DriverSourceId.entries.map { it to it.name }
                        for ((src, label) in sources) {
                            FilterChip(selected = selectedSource == src, onClick = { selectedSource = src }, label = { Text(label, fontSize = 11.sp) })
                        }
                    }
                    Text("GPU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (g in DriverGpuFilter.entries) {
                            FilterChip(selected = selectedGpu == g, onClick = { selectedGpu = g }, label = { Text(g.name, fontSize = 11.sp) })
                        }
                    }
                    Text("Variant", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in DriverVariantFilter.entries) {
                            FilterChip(selected = selectedVariant == v, onClick = { selectedVariant = v }, label = { Text(v.name, fontSize = 11.sp) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = hideExperimental, onCheckedChange = { hideExperimental = it })
                            Text("Hide experimental", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = latestOnly, onCheckedChange = { latestOnly = it })
                            Text("Latest only", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        snapshot?.let { s ->
                            val countsText = s.sources.joinToString(" • ") { "${it.source.name} ${it.packages.size}" + (if (it.error != null) " err" else "") }
                            Text(countsText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (s.sources.any { it.error != null }) {
                                Text(s.sources.filter { it.error != null }.joinToString("; ") { "${it.source.name}: ${it.error}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text("${filtered.size} of ${allPackages.size} • filtered", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { loadCatalog() }) { Text("Refresh") }
                }
                HorizontalDivider()
            }

            if (isLoadingCatalog && snapshot == null) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Fetching drivers…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (filtered.isEmpty() && !isLoadingCatalog) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No drivers match filters", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(filtered.take(50), key = { it.id }) { pkg ->
                        val dlState = downloadStates[pkg.id] ?: DownloadUiState.Idle
                        val installed = isPackageInstalled(pkg)
                        val installedLabel = installedLabelFor(pkg)
                        val isSelected = installedLabel != null && installedLabel == selectedDriver

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pkg.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${pkg.source.name} • ${pkg.archiveFormat.name} ${if (pkg.experimental) "• Experimental" else ""} ${pkg.gpuHint ?: ""} ${pkg.variant ?: ""}".trim(),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "${pkg.version ?: ""} ${pkg.fileSize?.let { "• ${it / 1024} KiB" } ?: ""}".trim(),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (installed) {
                                            Text("Installed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Direct row actions per spec: DOWNLOAD / SELECT / SELECTED / progress
                                    when (dlState) {
                                        is DownloadUiState.Downloading -> {
                                            Column(horizontalAlignment = Alignment.End) {
                                                Button(onClick = { /* TODO cancel not yet supported */ }, enabled = false) { Text("CANCEL", fontSize = 11.sp) }
                                            }
                                        }
                                        is DownloadUiState.Failed -> {
                                            Button(onClick = { startDownload(pkg) }) { Text("RETRY", fontSize = 11.sp) }
                                        }
                                        is DownloadUiState.Installed -> {
                                            if (isSelected) {
                                                Button(onClick = {}, enabled = false) { Text("SELECTED", fontSize = 11.sp) }
                                            } else {
                                                Button(onClick = {
                                                    val entry = drivers.entries.firstOrNull { it.value.label == installedLabel }
                                                    if (entry != null) {
                                                        val ok = GpuDriverSelection.selectDriver(context, entry.value, entry.key, RPCSX.nativeLibDirectory, false)
                                                        if (ok) selectedDriver = entry.value.label
                                                    }
                                                }) { Text("SELECT", fontSize = 11.sp) }
                                            }
                                        }
                                        is DownloadUiState.Verifying, is DownloadUiState.Installing, is DownloadUiState.Extracting -> {
                                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                                        }
                                        else -> {
                                            if (installed) {
                                                if (isSelected) {
                                                    Button(onClick = {}, enabled = false,
                                                        colors = ButtonDefaults.buttonColors(disabledContainerColor = MaterialTheme.colorScheme.primary, disabledContentColor = MaterialTheme.colorScheme.onPrimary)
                                                    ) { Text("SELECTED", fontSize = 11.sp) }
                                                } else {
                                                    Button(onClick = {
                                                        val entry = drivers.entries.firstOrNull { it.value.label == installedLabel }
                                                        if (entry != null) {
                                                            val ok = GpuDriverSelection.selectDriver(context, entry.value, entry.key, RPCSX.nativeLibDirectory, false)
                                                            if (ok) selectedDriver = entry.value.label
                                                        } else {
                                                            // fallback: refresh already
                                                        }
                                                    }) { Text("SELECT", fontSize = 11.sp) }
                                                }
                                            } else {
                                                Button(onClick = { startDownload(pkg) }) { Text("DOWNLOAD", fontSize = 11.sp) }
                                            }
                                        }
                                    }
                                }

                                // Inline progress / status
                                when (dlState) {
                                    is DownloadUiState.Downloading -> {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val prog = dlState.progress
                                        if (prog != null) LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth())
                                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Text(dlState.statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Downloading ${pkg.displayName}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    is DownloadUiState.Verifying -> {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Text("Verifying...", style = MaterialTheme.typography.labelSmall)
                                    }
                                    is DownloadUiState.Installing -> {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Text("Installing...", style = MaterialTheme.typography.labelSmall)
                                    }
                                    is DownloadUiState.Failed -> {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(dlState.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_driver), fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), contentDescription = null)
                    }
                },
                actions = {
                    // Explicit IMPORT DRIVER action in top bar for Standard (spec)
                    IconButton(onClick = { driverPickerLauncher.launch("application/zip") }) {
                        Icon(painter = painterResource(id = R.drawable.ic_add), contentDescription = "Import Driver")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(selected = selectedTab == DriverTab.Installed, onClick = { selectedTab = DriverTab.Installed }, text = { Text("Installed") })
                Tab(selected = selectedTab == DriverTab.Browse, onClick = { selectedTab = DriverTab.Browse }, text = { Text("Browse Drivers") })
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    DriverTab.Installed -> InstalledTabContent()
                    DriverTab.Browse -> BrowseTabContent()
                }
            }
        }
    }
}
