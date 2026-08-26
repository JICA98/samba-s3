package com.zenithblue.sambas3.ui.drivers

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.drivers.catalog.DriverCatalogSnapshot
import com.zenithblue.sambas3.drivers.catalog.DriverGpuFilter
import com.zenithblue.sambas3.drivers.catalog.DriverSourceId
import com.zenithblue.sambas3.drivers.catalog.DriverVariantFilter
import com.zenithblue.sambas3.drivers.download.DriverDownloadRegistry
import com.zenithblue.sambas3.drivers.download.DriverDownloadState
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GeneralSettings.string
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverInstallResult
import com.zenithblue.sambas3.utils.GpuDriverSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DriverTab { Installed, Browse }

@OptIn(ExperimentalMaterial3Api::class)
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

    var snapshot by remember { mutableStateOf<DriverCatalogSnapshot?>(null) }
    var isLoadingCatalog by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    val downloadRegistry =
        remember(context, scope) {
            DriverDownloadRegistry(
                context = context.applicationContext,
                scope = scope,
            )
        }

    val installedIndex =
        remember(drivers) {
            InstalledDriverIndex.from(drivers)
        }

    val browseController = remember(scope) { DriverBrowseController(scope) }
    val filtered by browseController.filtered.collectAsState()
    val query by browseController.query.collectAsState()
    val selectedSource by browseController.source.collectAsState()
    val selectedGpu by browseController.gpu.collectAsState()
    val selectedVariant by browseController.variant.collectAsState()
    val hideExperimental by browseController.hideExperimental.collectAsState()
    val latestOnly by browseController.latestOnly.collectAsState()
    // browseController.snapshot is updated when snapshot loads

    // Keep selectedDriver in sync when selection changes externally
    LaunchedEffect(Unit) {
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

    fun loadCatalog() {
        if (isLoadingCatalog) return
        isLoadingCatalog = true
        fetchError = null
        scope.launch(Dispatchers.IO) {
            try {
                val snap = com.zenithblue.sambas3.drivers.catalog.DriverCatalogRepository.refresh()
                withContext(Dispatchers.Main) {
                    snapshot = snap
                    browseController.snapshot.value = snap
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
                    shape = RoundedCornerShape(12.dp),
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

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { browseController.query.value = it },
                    label = { Text("Search drivers...") },
                    placeholder = { Text("name, version, source, GPU") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("Source", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        item(key = "source-all") {
                            FilterChip(
                                selected = selectedSource == null,
                                onClick = { browseController.source.value = null },
                                label = { Text("All") }
                            )
                        }
                        items(
                            items = DriverSourceId.entries,
                            key = { it.name }
                        ) { source ->
                            FilterChip(
                                selected = selectedSource == source,
                                onClick = { browseController.source.value = source },
                                label = { Text(source.name) }
                            )
                        }
                    }
                    Text("GPU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        items(
                            items = DriverGpuFilter.entries,
                            key = { it.name }
                        ) { g ->
                            FilterChip(selected = selectedGpu == g, onClick = { browseController.gpu.value = g }, label = { Text(g.name, fontSize = 11.sp) })
                        }
                    }
                    Text("Variant", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        items(
                            items = DriverVariantFilter.entries,
                            key = { it.name }
                        ) { v ->
                            FilterChip(selected = selectedVariant == v, onClick = { browseController.variant.value = v }, label = { Text(v.name, fontSize = 11.sp) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = hideExperimental, onCheckedChange = { browseController.hideExperimental.value = it })
                            Text("Hide experimental", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = latestOnly, onCheckedChange = { browseController.latestOnly.value = it })
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
                items(
                    items = filtered,
                    key = { it.id },
                    contentType = { "remote-driver" },
                ) { pkg ->
                    val installed = installedIndex.find(pkg)
                    DriverBrowseRow(
                        pkg = pkg,
                        installed = installed,
                        selectedDriver = selectedDriver,
                        registry = downloadRegistry,
                        onInstalled = {
                            refreshInstalled()
                        },
                        onSelect = { ref ->
                            val ok =
                                GpuDriverSelection.selectDriver(
                                    context = context,
                                    metadata = ref.metadata,
                                    driverDir = ref.directory,
                                    nativeLibraryDir = RPCSX.nativeLibDirectory,
                                    forceSysmem = false,
                                )
                            if (ok) {
                                selectedDriver = ref.metadata.label
                            }
                        }
                    )
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

@Composable
private fun DriverBrowseRow(
    pkg: com.zenithblue.sambas3.drivers.catalog.RemoteDriverPackage,
    installed: InstalledDriverRef?,
    selectedDriver: String?,
    registry: DriverDownloadRegistry,
    onInstalled: suspend () -> Unit,
    onSelect: (InstalledDriverRef) -> Unit,
) {
    val downloadState by
        registry.stateFor(pkg.id)
            .collectAsState()

    val selected =
        installed != null &&
            installed.metadata.label ==
                selectedDriver

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        shape =
            RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier =
                Modifier.padding(12.dp)
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                Column(
                    modifier =
                        Modifier.weight(1f)
                ) {
                    Text(
                        text =
                            pkg.displayName,
                        style =
                            MaterialTheme
                                .typography
                                .titleSmall,
                        fontWeight =
                            FontWeight.SemiBold,
                        maxLines = 2,
                        overflow =
                            TextOverflow.Ellipsis,
                    )

                    Text(
                        text =
                            buildString {
                                append(
                                    pkg.source.name
                                )
                                append(" • ")
                                append(
                                    pkg.archiveFormat.name
                                )

                                pkg.gpuHint?.let {
                                    append(" • ")
                                    append(it)
                                }

                                pkg.variant?.let {
                                    append(" • ")
                                    append(it)
                                }
                            },
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                        maxLines = 2,
                        overflow =
                            TextOverflow.Ellipsis,
                    )

                    pkg.version?.let {
                        Text(
                            text = it,
                            style =
                                MaterialTheme
                                    .typography
                                    .labelSmall,
                        )
                    }
                }

                Spacer(
                    Modifier.width(8.dp)
                )

                when {
                    selected -> {
                        Button(
                            enabled = false,
                            onClick = {},
                        ) {
                            Text("SELECTED")
                        }
                    }

                    installed != null -> {
                        Button(
                            onClick = {
                                onSelect(installed)
                            }
                        ) {
                            Text("SELECT")
                        }
                    }

                    downloadState is
                        DriverDownloadState.Downloading -> {
                        TextButton(
                            onClick = {
                                registry.cancel(
                                    pkg.id
                                )
                            }
                        ) {
                            Text("CANCEL")
                        }
                    }

                    downloadState is
                        DriverDownloadState.Failed -> {
                        Button(
                            onClick = {
                                registry.retry(
                                    pkg,
                                    onInstalled
                                )
                            }
                        ) {
                            Text("RETRY")
                        }
                    }

                    downloadState ==
                        DriverDownloadState.Verifying ||
                    downloadState ==
                        DriverDownloadState.Installing -> {
                        CircularProgressIndicator(
                            modifier =
                                Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }

                    else -> {
                        Button(
                            onClick = {
                                registry.start(
                                    pkg,
                                    onInstalled
                                )
                            }
                        ) {
                            Text("DOWNLOAD")
                        }
                    }
                }
            }

            when (
                val state =
                    downloadState
            ) {
                is DriverDownloadState.Downloading -> {
                    Spacer(
                        Modifier.height(6.dp)
                    )

                    val total =
                        state.totalBytes

                    if (
                        total != null &&
                        total > 0L
                    ) {
                        LinearProgressIndicator(
                            progress = {
                                (
                                    state.bytesRead
                                        .toFloat() /
                                    total.toFloat()
                                ).coerceIn(
                                    0f,
                                    1f
                                )
                            },
                            modifier =
                                Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier =
                                Modifier.fillMaxWidth()
                        )
                    }

                    Text(
                        text =
                            if (
                                total != null &&
                                total > 0
                            ) {
                                "${state.bytesRead / 1024} KiB / " +
                                    "${total / 1024} KiB"
                            } else {
                                "${state.bytesRead / 1024} KiB"
                            },
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                    )
                }

                DriverDownloadState.Verifying ->
                    DriverStageText(
                        "Verifying…"
                    )

                DriverDownloadState.Installing ->
                    DriverStageText(
                        "Installing…"
                    )

                DriverDownloadState.Installed ->
                    DriverStageText(
                        "Installed"
                    )

                is DriverDownloadState.Failed ->
                    Text(
                        text =
                            state.message,
                        color =
                            MaterialTheme
                                .colorScheme
                                .error,
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                    )

                DriverDownloadState.Idle ->
                    Unit
            }
        }
    }
}

@Composable
private fun DriverStageText(
    text: String
) {
    Spacer(
        Modifier.height(4.dp)
    )

    Text(
        text = text,
        style =
            MaterialTheme
                .typography
                .labelSmall,
    )
}
