package com.zenithblue.sambas3.ui.drivers

import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.zenithblue.sambas3.ui.settings.components.core.DeletableListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.drivers.catalog.DriverCatalogRepository
import com.zenithblue.sambas3.drivers.catalog.RemoteDriverPackage
import com.zenithblue.sambas3.drivers.download.DriverDownloader
import com.zenithblue.sambas3.drivers.download.DriverPackageAdapter
import com.zenithblue.sambas3.utils.DefaultGpuDriverChannel
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GeneralSettings.string
import com.zenithblue.sambas3.utils.GitHub
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverSelection
import com.zenithblue.sambas3.utils.GpuDriverInstallResult
import java.io.File
import java.io.FileInputStream

import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpuDriversScreen(
    navigateBack: () -> Unit,
    isInSplitPane: Boolean = false
) {
    val context = LocalContext.current
    var drivers by remember { mutableStateOf(GpuDriverHelper.getInstalledDrivers(context)) }
    var selectedDriver by remember { mutableStateOf<String?>(null) }
    var isInstalling by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var showDriverDialog by remember { mutableStateOf(false) }
    var shouldFetchAndShowDrivers by remember { mutableStateOf(false) }
    var repoUrl by remember { mutableStateOf<String?>(null) }
    var driverToDownload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var shouldDownloadDriver by remember { mutableStateOf(false) }

    val driverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isInstalling = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val result = GpuDriverHelper.installDriver(context, stream)
                        if (result == GpuDriverInstallResult.Success) {
                            val updatedDrivers = GpuDriverHelper.getInstalledDrivers(context)
                            withContext(Dispatchers.Main) {
                                drivers = updatedDrivers
                            }
                        }
                        withContext(Dispatchers.Main) {
                            isInstalling = false
                            snackbarHostState.showSnackbar(
                                message = GpuDriverHelper.resolveInstallResultToString(result),
                                actionLabel = "Dismiss",
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GpuDriver", "Error installing driver: ${e.message}")
                }
            }
        }
    }

    selectedDriver = GeneralSettings["selected_gpu_driver"].string("Default")

    if (showDriverDialog) {
        DriverDialog(onDismiss = { showDriverDialog = false }, onInstallClick = {
            driverPickerLauncher.launch("application/zip")
        }, onImportClick = {
            repoUrl = GeneralSettings["gpu_driver_channel"].string(DefaultGpuDriverChannel)
            shouldFetchAndShowDrivers = true
        })
    }

    if (shouldFetchAndShowDrivers) {
        FetchAndShowDrivers(
            repoUrl = repoUrl!!,
            onDismiss = { shouldFetchAndShowDrivers = false },
            onDownloadDriver = { url, name ->
                driverToDownload = Pair(url, name)
                shouldDownloadDriver = true
            })
    }

    if (shouldDownloadDriver) {
        DownloadDriver(
            chosenUrl = driverToDownload!!.first,
            chosenName = driverToDownload!!.second,
            onDismiss = {
                shouldDownloadDriver = false
                coroutineScope.launch(Dispatchers.IO) {
                    val updatedDrivers = GpuDriverHelper.getInstalledDrivers(context)
                    withContext(Dispatchers.Main) {
                        drivers = updatedDrivers
                    }
                }
            })
    }

    @Composable
    fun DriversContent(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.select_driver),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(drivers.entries.toList(), key = { it.key }) { (file, metadata) ->
                    DeletableListItem(onDelete = if (metadata.name == "Default" || metadata.isBundled) null else ({
                        coroutineScope.launch(Dispatchers.IO) {
                            if (GpuDriverHelper.deleteDriver(context, file, metadata)) {
                                withContext(Dispatchers.Main) {
                                    drivers = GpuDriverHelper.getInstalledDrivers(context)
                                }
                            }
                        }
                    })) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    val isSystem = metadata.name == "Default"
                                    Log.w("Driver", "select ${metadata.label}, dir ${if (isSystem) "<system>" else file.path}")
                                    val ok = GpuDriverSelection.selectDriver(
                                        context = context,
                                        metadata = metadata,
                                        driverDir = if (isSystem) null else file,
                                        nativeLibraryDir = RPCSX.nativeLibDirectory,
                                        forceSysmem = false,
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
                                containerColor = if (metadata.label == selectedDriver) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                            shape = RoundedCornerShape(8.dp),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = metadata.uiTitle,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (metadata.label == selectedDriver) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = metadata.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (metadata.label == selectedDriver) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showDriverDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(56.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isInstalling) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        enabled = !isInstalling,
                        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 4.dp)
                    ) {
                        if (isInstalling) {
                            Text(stringResource(R.string.installing))
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_add),
                                contentDescription = "Install Driver"
                            )
                        }
                    }

                }
            }
        }
    }

    if (isInSplitPane) {
        Column(modifier = Modifier.fillMaxSize()) {
            DriversContent(modifier = Modifier.weight(1f))
        }
    } else {
        Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }, topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.custom_driver), fontWeight = FontWeight.Medium) },
                scrollBehavior = topBarScrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = navigateBack
                    ) {
                        Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), null)
                    }
                })
        }) { paddingValues ->
            DriversContent(modifier = Modifier.padding(paddingValues))
        }
    }
}

@Composable
fun DriverDialog(
    onDismiss: () -> Unit, onInstallClick: () -> Unit, onImportClick: () -> Unit
) {
    var selectedItemIndex by remember { mutableIntStateOf(0) }

    AlertDialog(onDismissRequest = onDismiss, title = {
        Text(stringResource(R.string.choose))
    }, text = {
        Column {
            listOf(
                stringResource(R.string.download),
                stringResource(R.string.install),
            ).forEachIndexed { index, text ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedItemIndex = index }
                        .padding(8.dp)) {
                    RadioButton(
                        selected = selectedItemIndex == index,
                        onClick = { selectedItemIndex = index })
                    Text(text = text, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (selectedItemIndex == 1) {
                onInstallClick()
            } else {
                onImportClick()
            }
            onDismiss()
        }) {
            Text(text = stringResource(android.R.string.ok))
        }
    }, dismissButton = {
        TextButton(onClick = onDismiss) {
            Text(text = stringResource(android.R.string.cancel))
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FetchAndShowDrivers(
    repoUrl: String,
    onDismiss: () -> Unit,
    onDownloadDriver: (String, String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetchedDrivers by remember { mutableStateOf<List<RemoteDriverPackage>>(emptyList()) }
    var chosenIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val hasScrolled = remember { derivedStateOf { scrollState.value > 0 } }

    LaunchedEffect(Unit) {
        try {
            val all = DriverCatalogRepository.fetchAll()
            fetchedDrivers = all
            if (all.isEmpty()) {
                fetchError = "No drivers found. Check network or try again."
            }
        } catch (e: Exception) {
            Log.e("DriverCatalog", "fetchAll failed: ${e.message}", e)
            fetchError = e.message ?: "Failed to fetch drivers"
            // Fallback to legacy GitHub single repo if catalog fails completely
            try {
                val fallback = GitHub.fetchReleases(repoUrl)
                if (fallback is GitHub.FetchResult.Success<*>) {
                    val legacy = (fallback.content as List<Pair<String, String?>>).mapNotNull { (name, url) ->
                        if (url == null) null else RemoteDriverPackage(
                            id = "legacy_${name.hashCode()}",
                            source = com.zenithblue.sambas3.drivers.catalog.DriverSourceId.ARIHANY,
                            displayName = name,
                            version = null,
                            downloadUrl = url
                        )
                    }
                    if (legacy.isNotEmpty()) {
                        fetchedDrivers = legacy
                        fetchError = null
                    }
                }
            } catch (_: Exception) {}
        }
        isLoading = false
    }

    fetchError?.let { msg ->
        // Only show error if no drivers loaded at all
        if (fetchedDrivers.isEmpty()) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.error)) },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                })
            return
        }
    }

    if (isLoading) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.fetching)) }, text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.please_wait))
                Text("Aggregating community catalogs...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = {})
        return
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxHeight = if (isLandscape) 168.dp else 300.dp

    val filtered = remember(fetchedDrivers, searchQuery) {
        if (searchQuery.isBlank()) fetchedDrivers else fetchedDrivers.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.source.name.contains(searchQuery, ignoreCase = true) ||
            (it.version?.contains(searchQuery, ignoreCase = true) == true)
        }
    }
    // Keep chosenIndex in bounds after filter
    LaunchedEffect(filtered.size) {
        if (chosenIndex >= filtered.size) chosenIndex = 0
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss, content = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    Text(
                        text = stringResource(R.string.drivers),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = "${filtered.size} drivers (of ${fetchedDrivers.size} total) • search to filter 100+ entries",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search") },
                        placeholder = { Text("e.g. Turnip 26.3, a7xx, Kimchi") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    if (hasScrolled.value) {
                        HorizontalDivider()
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .verticalScroll(scrollState)
                    ) {
                        filtered.forEachIndexed { index, driver ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { chosenIndex = index }
                                    .padding(vertical = 4.dp, horizontal = 16.dp)) {
                                RadioButton(
                                    selected = chosenIndex == index,
                                    onClick = { chosenIndex = index })
                                Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                                    Text(text = driver.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${driver.source.name} ${if (driver.experimental) "• Experimental" else ""} ${driver.gpuHint ?: ""}".trim(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (filtered.isEmpty()) {
                            Text("No matches for \"$searchQuery\"", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    if (hasScrolled.value) {
                        HorizontalDivider()
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(text = stringResource(android.R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            enabled = filtered.isNotEmpty(),
                            onClick = {
                                val chosenDriver = filtered[chosenIndex]
                                onDownloadDriver(chosenDriver.downloadUrl, chosenDriver.displayName)
                                onDismiss()
                            }, modifier = Modifier.padding(end = 16.dp)
                        ) {
                            Text(stringResource(R.string.download))
                        }
                    }
                }
            }
        })
}

@Composable
fun DownloadDriver(
    chosenUrl: String, chosenName: String, onDismiss: () -> Unit
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var isIndeterminate by remember { mutableStateOf(true) }
    var downloadCompleted by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Downloading...") }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val safeName = chosenName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
            val cacheDir = File(context.cacheDir, "driver_downloads").apply { mkdirs() }
            val driverFile = File(cacheDir, "$safeName.zip")
            if (driverFile.exists()) driverFile.delete()
            val tmpAdapted = File(cacheDir, "$safeName.adapted.zip")

            // Robust streaming GET without HEAD/Content-Length/Range requirements
            val dlResult = DriverDownloader.download(
                url = chosenUrl,
                destFile = driverFile,
                expectedSha256 = null, // catalog may provide sha, but we don't have it here; verification after adapt
                progress = { bytesRead, total ->
                    if (total != null && total > 0) {
                        isIndeterminate = false
                        progress = (bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        isIndeterminate = true
                    }
                    statusText = if (total != null && total > 0) "${bytesRead / 1024} KiB / ${total / 1024} KiB" else "${bytesRead / 1024} KiB"
                }
            )

            when (dlResult) {
                is DriverDownloader.Result.Success -> {
                    statusText = "Adapting package..."
                    // Adapt community ZIP layout without mutating .so bytes
                    val adaptResult = DriverPackageAdapter.adapt(driverFile, tmpAdapted)
                    val fileToInstall = when (adaptResult) {
                        is DriverPackageAdapter.Result.Success -> {
                            Log.i("DriverDownload", "Adapt success $chosenName")
                            adaptResult.adaptedFile
                        }
                        is DriverPackageAdapter.Result.Error -> {
                            Log.w("DriverDownload", "Adapt failed for $chosenName: ${adaptResult.message}, trying original")
                            // If adapt fails due to already valid Samba format, try original
                            driverFile
                        }
                    }
                    withContext(Dispatchers.Main) {
                        val installResult = withContext(Dispatchers.IO) {
                            GpuDriverHelper.installDriver(context, FileInputStream(fileToInstall))
                        }
                        // Validation already runs inside installDriver via DriverBinaryValidator
                        Toast.makeText(
                            context,
                            GpuDriverHelper.resolveInstallResultToString(installResult),
                            Toast.LENGTH_LONG
                        ).show()
                        downloadCompleted = true
                        if (installResult == GpuDriverInstallResult.Success) {
                            onDismiss()
                        } else {
                            statusText = GpuDriverHelper.resolveInstallResultToString(installResult)
                        }
                    }
                    try { driverFile.delete() } catch (_: Exception) {}
                    try { tmpAdapted.delete() } catch (_: Exception) {}
                }
                is DriverDownloader.Result.Error -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.error_with_msg, dlResult.message),
                            Toast.LENGTH_SHORT
                        ).show()
                        statusText = dlResult.message ?: "Download failed"
                        onDismiss()
                    }
                    try { driverFile.delete() } catch (_: Exception) {}
                    try { tmpAdapted.delete() } catch (_: Exception) {}
                }
                is DriverDownloader.Result.Canceled -> {
                    withContext(Dispatchers.Main) { onDismiss() }
                    try { driverFile.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isIndeterminate || downloadCompleted) onDismiss() },
        title = { Text(stringResource(R.string.downloading)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isIndeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (!isIndeterminate) Text(text = "${(progress * 100).toInt()}%")
                Text(text = statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = chosenName, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
        },
        confirmButton = {
            if (downloadCompleted) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        })
}
