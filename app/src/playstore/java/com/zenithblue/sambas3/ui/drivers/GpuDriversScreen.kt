package com.zenithblue.sambas3.ui.drivers

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.common.SambaScreenScaffold
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.ui.drivers.ComingSoonDriversSection
import com.zenithblue.sambas3.ui.drivers.DriverBrandRow
import com.zenithblue.sambas3.ui.drivers.DriverStickFocusNav
import com.zenithblue.sambas3.ui.drivers.gamepadActivate
import com.zenithblue.sambas3.ui.drivers.isMesaDriver
import com.zenithblue.sambas3.ui.drivers.systemVendorChip
import com.zenithblue.sambas3.ui.drivers.vendorLogoRes
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
        var gpuInfo by remember { mutableStateOf<com.zenithblue.sambas3.utils.AdrenoGpuInfo?>(null) }
        LaunchedEffect(Unit) {
            gpuInfo = withContext(Dispatchers.IO) { AdrenoGpuDetector.detect() }
        }
        val vendorChip = remember(gpuInfo) {
            gpuInfo?.let { systemVendorChip(it) } ?: "SYSTEM"
        }
        val vendorLogo = remember(gpuInfo) {
            gpuInfo?.let { vendorLogoRes(it) } ?: R.drawable.hw_gpu_fallback
        }
        DriverStickFocusNav()
        val firstCardKey = drivers.entries.firstOrNull()?.key?.path
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

            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(drivers.entries.toList(), key = { it.key.path }) { (file, metadata) ->
                    val isSystem = metadata.name == "Default"
                    val selected = metadata.label == selectedDriver || (isSystem && selectedDriver == "Default")
                    val focusRequester = remember { FocusRequester() }
                    var cardFocused by remember { mutableStateOf(false) }
                    fun selectDriverAction() {
                        if (!RPCSX.instance.supportsCustomDriverLoading() && !isSystem) {
                            AlertDialogQueue.showDialog(
                                context.getString(R.string.custom_driver_not_supported),
                                context.getString(R.string.custom_driver_not_supported_description),
                            )
                            return
                        }
                        if (metadata.experimental) {
                            pendingExperimental = file to metadata
                        } else {
                            applySelection(context, file, metadata) { ok, label ->
                                if (ok) selectedDriver = label
                            }
                        }
                    }
                    if (file.path == firstCardKey) {
                        LaunchedEffect(focusRequester) {
                            kotlinx.coroutines.delay(300)
                            runCatching { focusRequester.requestFocus() }
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .onFocusChanged { cardFocused = it.isFocused }
                            .focusRequester(focusRequester)
                            .focusable()
                            .clickable { selectDriverAction() }
                            .gamepadActivate { selectDriverAction() },
                        border = BorderStroke(
                            if (selected || cardFocused) 2.dp else 1.dp,
                            when {
                                cardFocused -> RPCSXColors.primary
                                selected -> RPCSXColors.primary
                                else -> RPCSXColors.outlineVariant
                            },
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected || cardFocused) {
                                RPCSXColors.surfaceElevated
                            } else {
                                RPCSXColors.surface
                            },
                            contentColor = RPCSXColors.textPrimary,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp).heightIn(min = 160.dp)) {
                            DriverBrandRow(metadata = metadata, systemVendor = vendorChip, vendorLogo = vendorLogo)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                if (selected) "SELECTED" else "SELECT DRIVER",
                                color = RPCSXColors.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
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
                            if (isMesaDriver(metadata)) {
                                Text(
                                    text = "Mesa open-source Vulkan driver",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
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
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    ComingSoonDriversSection()
                }
            }
        }
    }

    if (isInSplitPane) {
        SambaScreenScaffold(
            title = stringResource(R.string.custom_driver),
            iconRes = R.drawable.memory,
            onBack = navigateBack,
            compact = true,
            showHints = false,
            onGamepadKey = { code ->
                if (code == KeyEvent.KEYCODE_BUTTON_B) {
                    navigateBack()
                    true
                } else {
                    false
                }
            },
        ) {
            DriversContent(modifier = Modifier.fillMaxSize())
        }
    } else {
        SambaScreenScaffold(
            title = stringResource(R.string.custom_driver),
            iconRes = R.drawable.memory,
            onBack = navigateBack,
            hints = listOf(
                R.drawable.cross to "Select",
                R.drawable.circle to "Back"
            ),
            onGamepadKey = { code ->
                if (code == KeyEvent.KEYCODE_BUTTON_B) {
                    navigateBack()
                    true
                } else {
                    false
                }
            },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SnackbarHost(hostState = snackbarHostState)
                DriversContent(modifier = Modifier.weight(1f))
            }
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
