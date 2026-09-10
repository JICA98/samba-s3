package com.zenithblue.sambas3.ui.drivers

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.ui.common.SambaScreenScaffold
import com.zenithblue.sambas3.ui.drivers.ComingSoonDriversSection
import com.zenithblue.sambas3.ui.drivers.DriverBrandRow
import com.zenithblue.sambas3.ui.drivers.DriverStickFocusNav
import com.zenithblue.sambas3.ui.drivers.gamepadActivate
import com.zenithblue.sambas3.ui.drivers.isMesaDriver
import com.zenithblue.sambas3.ui.drivers.systemVendorChip
import com.zenithblue.sambas3.ui.drivers.vendorLogoRes
import com.zenithblue.sambas3.utils.AdrenoGpuDetector
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GeneralSettings.string
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var pendingDelete by remember { mutableStateOf<Pair<java.io.File, com.zenithblue.sambas3.utils.GpuDriverMetadata>?>(null) }

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



    @Composable
    fun InstalledTabContent() {
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(360.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(drivers.entries.toList(), key = { it.key.path }) { (file, metadata) ->
                val isSystem = metadata.name == "Default"
                val isSelected = metadata.label == selectedDriver || (isSystem && selectedDriver == "Default")
                val canDelete = metadata.name != "Default" && !metadata.isBundled
                val canSelect = isSystem || RPCSX.instance.supportsCustomDriverLoading()
                val focusRequester = remember { FocusRequester() }
                var cardFocused by remember { mutableStateOf(false) }
                fun selectDriverAction(): Boolean {
                    if (!canSelect) {
                        AlertDialogQueue.showDialog(
                            context.getString(R.string.custom_driver_not_supported),
                            context.getString(R.string.custom_driver_not_supported_description)
                        )
                        return false
                    }
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
                    return ok
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
                        .padding(vertical = 6.dp)
                        .onFocusChanged { cardFocused = it.isFocused }
                        .focusRequester(focusRequester)
                        .focusable()
                        .clickable { selectDriverAction() }
                        .gamepadActivate { selectDriverAction() },
                    border = if (cardFocused) BorderStroke(2.dp, RPCSXColors.primary) else null,
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp).heightIn(min = 160.dp)) {
                        DriverBrandRow(metadata = metadata, systemVendor = vendorChip, vendorLogo = vendorLogo)
                        Spacer(modifier = Modifier.height(8.dp))
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
                                if (isMesaDriver(metadata)) {
                                    Text(
                                        text = "Mesa open-source Vulkan driver",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (isSelected) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Text(
                                        "SELECTED",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                        if (canDelete) {
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    onClick = { pendingDelete = file to metadata }
                                ) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    ComingSoonDriversSection()
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }


    SambaScreenScaffold(
        title = stringResource(R.string.custom_driver),
        iconRes = R.drawable.memory,
        onBack = navigateBack,
        compact = isInSplitPane,
        showHints = !isInSplitPane,
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
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SnackbarHost(hostState = snackbarHostState)
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                InstalledTabContent()
            }
        }
    }

    pendingDelete?.let { (file, metadata) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Uninstall GPU driver?") },
            text = {
                Text(
                    if (metadata.label == selectedDriver) {
                        "${metadata.uiTitle} is selected. Samba S3 will switch to the system driver before uninstalling it."
                    } else {
                        "Remove ${metadata.uiTitle} from Samba S3's private driver storage?"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch(Dispatchers.IO) {
                        if (metadata.label == selectedDriver) GpuDriverHelper.resetToSystemDriver(context)
                        val deleted = GpuDriverHelper.deleteDriver(context, file, metadata)
                        val updated = GpuDriverHelper.getInstalledDrivers(context)
                        val sel = GeneralSettings["selected_gpu_driver"].string("Default")
                        withContext(Dispatchers.Main) {
                            drivers = updated
                            selectedDriver = sel
                            snackbarHostState.showSnackbar(if (deleted) "GPU driver uninstalled" else "GPU driver could not be uninstalled")
                        }
                    }
                }) { Text("UNINSTALL") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("CANCEL") } },
        )
    }
}

