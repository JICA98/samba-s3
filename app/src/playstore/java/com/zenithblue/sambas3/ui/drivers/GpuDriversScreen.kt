package com.zenithblue.sambas3.ui.drivers

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.utils.AdrenoGpuDetector
import com.zenithblue.sambas3.utils.BundledDriverSyncResult
import com.zenithblue.sambas3.utils.BundledDriverVisibility
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GeneralSettings.string
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverMetadata
import com.zenithblue.sambas3.utils.GpuDriverSelection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Play Store GPU driver screen: system driver + offline bundled Turnip packages only.
 * No import, download, file picker, or external acquisition paths.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpuDriversScreen(
    navigateBack: () -> Unit,
    isInSplitPane: Boolean = false,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    var drivers by remember { mutableStateOf(emptyMap<File, GpuDriverMetadata>()) }
    var selectedDriver by remember {
        mutableStateOf(GeneralSettings["selected_gpu_driver"].string("Default"))
    }
    var isSyncing by remember { mutableStateOf(true) }
    var pendingExperimental by remember { mutableStateOf<Pair<File, GpuDriverMetadata>?>(null) }
    var showResetMessage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val sync = GpuDriverHelper.syncBundledDrivers(context)
            Log.i("GpuDriver", "Bundled sync: $sync")
            val reset = GpuDriverHelper.ensureValidSelection(context)
            val info = AdrenoGpuDetector.detect()
            val installed = GpuDriverHelper.getInstalledDrivers(context)
            val catalogEntries = GpuDriverHelper.loadBundledCatalog(context)?.drivers.orEmpty()
            val supportsCustom = try {
                RPCSX.instance.supportsCustomDriverLoading()
            } catch (_: Exception) {
                false
            }
            val filtered = BundledDriverVisibility.filterForDevice(
                installed = installed,
                info = info,
                catalogEntries = catalogEntries,
                supportsCustomDriverLoading = supportsCustom,
            )
            withContext(Dispatchers.Main) {
                isSyncing = false
                drivers = filtered
                selectedDriver = GeneralSettings["selected_gpu_driver"].string("Default")
                showResetMessage = reset
                if (sync is BundledDriverSyncResult.Failed) {
                    snackbarHostState.showSnackbar(
                        message = sync.message,
                        actionLabel = "Dismiss",
                        duration = SnackbarDuration.Long,
                    )
                }
            }
        }
    }

    LaunchedEffect(showResetMessage) {
        if (showResetMessage) {
            snackbarHostState.showSnackbar(
                message = "Previously selected GPU driver is unavailable. Using system driver.",
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long,
            )
            showResetMessage = false
        }
    }

    pendingExperimental?.let { (file, metadata) ->
        AlertDialog(
            onDismissRequest = { pendingExperimental = null },
            title = { Text("Experimental driver") },
            text = {
                Text(
                    "Turnip A8XX is experimental. It targets Adreno 8xx GPUs and may be unstable. " +
                        "It will not be selected automatically. Continue?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingExperimental = null
                    applySelection(context, file, metadata) { ok, label ->
                        if (ok) selectedDriver = label
                    }
                }) { Text("Use experimental") }
            },
            dismissButton = {
                TextButton(onClick = { pendingExperimental = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
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
                modifier = Modifier.padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (isSyncing) {
                    "Preparing included drivers…"
                } else {
                    "Drivers included with Samba S3. Works offline. No downloads."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(drivers.entries.toList(), key = { it.key.path }) { (file, metadata) ->
                    val isSystem = metadata.name == "Default"
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                if (!RPCSX.instance.supportsCustomDriverLoading() && !isSystem) {
                                    AlertDialogQueue.showDialog(
                                        context.getString(R.string.custom_driver_not_supported),
                                        context.getString(R.string.custom_driver_not_supported_description),
                                    )
                                    return@clickable
                                }
                                if (metadata.experimental) {
                                    pendingExperimental = file to metadata
                                } else {
                                    applySelection(context, file, metadata) { ok, label ->
                                        if (ok) selectedDriver = label
                                    }
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (
                                metadata.label == selectedDriver ||
                                (isSystem && selectedDriver == "Default")
                            ) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = metadata.uiTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (metadata.isBundled) {
                                Text(
                                    text = "Included with Samba S3",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (metadata.role != null) {
                                Text(
                                    text = roleLabel(metadata),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            if (metadata.driverVersion.isNotEmpty()) {
                                Text(
                                    text = "Version ${metadata.driverVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                text = metadata.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (isInSplitPane) {
        Column(modifier = Modifier.fillMaxSize()) {
            DriversContent(modifier = Modifier.weight(1f))
        }
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.custom_driver),
                            fontWeight = FontWeight.Medium,
                        )
                    },
                    scrollBehavior = topBarScrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            DriversContent(modifier = Modifier.padding(paddingValues))
        }
    }
}

private fun roleLabel(metadata: GpuDriverMetadata): String = when (metadata.role?.lowercase()) {
    "recommended" -> "Recommended"
    "compatibility" -> "Compatibility"
    "experimental" -> "Experimental"
    else -> metadata.role.orEmpty()
}

private fun applySelection(
    context: Context,
    file: File,
    metadata: GpuDriverMetadata,
    onResult: (Boolean, String) -> Unit,
) {
    val isSystem = metadata.name == "Default"
    val forceSysmem = !isSystem && GpuDriverSelection.shouldForceSysmemForSelection(context, metadata)
    val ok = GpuDriverSelection.selectDriver(
        context = context,
        metadata = metadata,
        driverDir = if (isSystem) null else file,
        nativeLibraryDir = RPCSX.nativeLibDirectory,
        forceSysmem = forceSysmem,
    )
    if (!ok) {
        AlertDialogQueue.showDialog(
            context.getString(R.string.error),
            context.getString(R.string.failed_to_load_selected_driver),
        )
        onResult(false, metadata.label)
    } else {
        onResult(true, if (isSystem) "Default" else metadata.label)
        if (forceSysmem) {
            Toast.makeText(context, "Adreno 830: enabling Turnip SYSMEM rendering", Toast.LENGTH_SHORT).show()
        }
    }
}
