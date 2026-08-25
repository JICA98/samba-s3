package com.zenithblue.sambas3.ui.onboarding

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zenithblue.sambas3.FirmwareRepository
import com.zenithblue.sambas3.FirmwareStatus
import com.zenithblue.sambas3.BuildConfig
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.PrecompilerService
import com.zenithblue.sambas3.PrecompilerServiceAction
import com.zenithblue.sambas3.ProgressRepository
import com.zenithblue.sambas3.Permission
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.utils.AdrenoGpuDetector
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverMetadata
import com.zenithblue.sambas3.utils.GeneralSettings.string
import com.zenithblue.sambas3.utils.GpuFamily
import com.zenithblue.sambas3.utils.BundledDriverVisibility
import com.zenithblue.sambas3.utils.GpuDriverSelection
import com.zenithblue.sambas3.utils.FileUtil
import com.zenithblue.sambas3.utils.GameFolderMatch
import com.zenithblue.sambas3.utils.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun OnboardingDestination(
    entry: OnboardingEntry,
    onFinished: () -> Unit,
    onExitAtFirstPage: (() -> Unit)?,
) {
    val context = LocalContext.current
    val rpcsxLibrary by remember { RPCSX.activeLibrary }
    val runtimeAvailable = rpcsxLibrary != null
    val notificationsRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notificationsGranted by remember {
        mutableStateOf(
            !notificationsRequired || Permission.PostNotifications.checkPermission(context)
        )
    }
    val detectedGpu = remember { AdrenoGpuDetector.detect() }
    val deviceInfo = remember(detectedGpu) { buildDeviceInfo(context, detectedGpu) }

    val firmwareVersion by remember { FirmwareRepository.version }
    val firmwareStatus by remember { FirmwareRepository.status }
    val firmwareProgressId by remember { FirmwareRepository.progressChannel }
    val firmwareProgressEntry = ProgressRepository.getItem(firmwareProgressId)?.value
    val firmwareProgressValue = firmwareProgressEntry?.value?.longValue ?: 0L
    val firmwareProgressMax = firmwareProgressEntry?.max?.longValue ?: 0L
    val firmwareProgress = firmwareProgressMax.takeIf { it > 0L }?.let {
        (firmwareProgressValue.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    }
    val firmwareProgressMessage = firmwareProgressEntry?.message?.value
    val firmwareInstalling = firmwareProgressId != null
    val gameCount = GameRepository.list().count { it.info.path != "$" }
    var scannedGames by remember { mutableStateOf<List<GameFolderMatch>?>(null) }
    var scanningGames by remember { mutableStateOf(false) }

    var driverInfo by remember {
        mutableStateOf(defaultDriverInfo(context, detectedGpu))
    }
    var driverTargets by remember {
        mutableStateOf<Map<String, DriverTarget>>(emptyMap())
    }
    val driverScope = rememberCoroutineScope()
    LaunchedEffect(runtimeAvailable, detectedGpu) {
        val loaded = withContext(Dispatchers.IO) {
            loadDriverInfo(context, detectedGpu, runtimeAvailable)
        }
        driverInfo = loaded.info
        driverTargets = loaded.targets
    }

    val installFirmwareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null && runtimeAvailable) {
            PrecompilerService.start(
                context,
                PrecompilerServiceAction.InstallFirmware,
                uri,
            )
        }
    }

    val gameFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null && runtimeAvailable) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Providers may not offer persistable permissions; the selected URI
                // is still handed to the same import path used by GamesDestination.
            }
            scanningGames = true
            driverScope.launch(Dispatchers.IO) {
                val matches = FileUtil.scanGameFolder(context, uri)
                withContext(Dispatchers.Main) {
                    scannedGames = matches
                    scanningGames = false
                }
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted || !notificationsRequired
    }

    val selectDriver: (String) -> Unit = { key ->
        val target = driverTargets[key]
        if (target != null && runtimeAvailable) {
            val isSystem = target.metadata.name == "Default"
            val forceSysmem = !isSystem &&
                GpuDriverSelection.shouldForceSysmemForSelection(context, target.metadata)
            val loaded = GpuDriverSelection.selectDriver(
                context = context,
                metadata = target.metadata,
                driverDir = if (isSystem) null else target.file,
                nativeLibraryDir = RPCSX.nativeLibDirectory,
                forceSysmem = forceSysmem,
            )
            if (!loaded) {
                AlertDialogQueue.showDialog(
                    context.getString(R.string.error),
                    context.getString(R.string.failed_to_load_selected_driver),
                )
            } else {
                // Re-read the catalog and persisted selection so the page reflects
                // the same state as the existing driver manager.
                driverScope.launch {
                    val state = withContext(Dispatchers.IO) {
                        loadDriverInfo(context, detectedGpu, runtimeAvailable)
                    }
                    driverInfo = state.info
                    driverTargets = state.targets
                }
            }
        }
    }

    OnboardingScreen(
        entry = entry,
        deviceInfo = deviceInfo,
        driverInfo = driverInfo,
        permissionInfo = OnboardingPermissionInfo(
            notificationsGranted = notificationsGranted,
            notificationsRequired = notificationsRequired,
        ),
        firmwareVersion = firmwareVersion,
        firmwareStatus = firmwareStatus,
        firmwareInstalling = firmwareInstalling,
        firmwareProgress = firmwareProgress,
        firmwareProgressMessage = firmwareProgressMessage,
        gameCount = gameCount,
        scannedGames = scannedGames,
        scanningGames = scanningGames,
        runtimeAvailable = runtimeAvailable,
        firmwareActionEnabled = runtimeAvailable && !firmwareInstalling,
        gameFolderActionEnabled = runtimeAvailable,
        onInstallFirmware = { installFirmwareLauncher.launch("*/*") },
        onSelectGameFolder = { gameFolderPickerLauncher.launch(null) },
        onRequestNotifications = {
            if (notificationsRequired && !notificationsGranted) {
                notificationPermissionLauncher.launch(Permission.PostNotifications.key)
            }
        },
        onSelectDriver = selectDriver,
        onFinished = onFinished,
        onExitAtFirstPage = onExitAtFirstPage,
    )
}

private fun buildDeviceInfo(context: Context, gpu: com.zenithblue.sambas3.utils.AdrenoGpuInfo): OnboardingDeviceInfo {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val totalRam = activityManager?.let {
        ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem
    } ?: 0L
    val ram = if (totalRam > 0L) {
        context.getString(R.string.onboarding_ram_format, totalRam / (1024.0 * 1024.0 * 1024.0))
    } else {
        context.getString(R.string.onboarding_unknown)
    }
    val manufacturer = Build.MANUFACTURER.trim().takeIf { it.isNotEmpty() }
    val model = Build.MODEL.trim().takeIf { it.isNotEmpty() }
    val deviceName = listOfNotNull(manufacturer, model)
        .distinct()
        .joinToString(" ")
        .ifBlank { context.getString(R.string.onboarding_unknown) }
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOfNotNull(
            Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() },
            Build.SOC_MODEL.takeIf { it.isNotBlank() },
        ).joinToString(" ")
    } else {
        ""
    }.ifBlank {
        listOf(Build.HARDWARE, Build.BOARD)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }.ifBlank { context.getString(R.string.onboarding_unknown) }
    val architecture = Build.SUPPORTED_ABIS.firstOrNull()
        ?: context.getString(R.string.onboarding_unknown)
    val android = context.getString(
        R.string.onboarding_android_format,
        Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        Build.VERSION.SDK_INT,
    )

    return OnboardingDeviceInfo(
        deviceName = deviceName,
        soc = soc,
        architecture = architecture,
        gpu = gpuLabel(context, gpu),
        ram = ram,
        android = android,
    )
}

private fun defaultDriverInfo(
    context: Context,
    gpu: com.zenithblue.sambas3.utils.AdrenoGpuInfo,
) = OnboardingDriverInfo(
    gpu = gpuLabel(context, gpu),
    selectedDriver = context.getString(R.string.onboarding_system_vulkan),
    guidance = context.getString(R.string.onboarding_driver_system_fallback),
)

private data class DriverTarget(
    val file: File,
    val metadata: GpuDriverMetadata,
)

private data class LoadedDriverInfo(
    val info: OnboardingDriverInfo,
    val targets: Map<String, DriverTarget>,
)

private suspend fun loadDriverInfo(
    context: Context,
    gpu: com.zenithblue.sambas3.utils.AdrenoGpuInfo,
    runtimeAvailable: Boolean,
): LoadedDriverInfo {
    val selected = GeneralSettings["selected_gpu_driver"].string("Default")
    if (BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS) {
        try {
            if (Telemetry.isEnabled) Telemetry.logS3Drv("event=catalog_load result=attempt session=${Telemetry.sessionId}")
            GpuDriverHelper.syncBundledDrivers(context)
            if (Telemetry.isEnabled) Telemetry.logS3Drv("event=bundled_sync result=ok session=${Telemetry.sessionId}")
        } catch (e: Exception) {
            if (Telemetry.isEnabled) Telemetry.logS3Drv("event=bundled_sync result=failed error=${e.message} session=${Telemetry.sessionId}")
            // The existing Play Store driver screen remains authoritative if
            // bundled synchronization is temporarily unavailable.
        }
    }
    val installed = runCatching { GpuDriverHelper.getInstalledDrivers(context) }.getOrDefault(emptyMap())
    val metadata = installed.entries.firstOrNull { (file, value) ->
        value.label == selected || value.uiTitle == selected || file.name == selected
    }?.value
    val selectedLabel = if (selected == "Default" || metadata?.name == "Default") {
        context.getString(R.string.onboarding_system_vulkan)
    } else {
        metadata?.uiTitle ?: selected
    }
    val customSupported = runtimeAvailable && runCatching {
        RPCSX.instance.supportsCustomDriverLoading()
    }.getOrDefault(false)
    val compatible = if (customSupported) {
        runCatching { GpuDriverHelper.compatibleBundledEntries(context, gpu) }.getOrDefault(emptyList())
    } else {
        emptyList()
    }
    val guidance = when {
        !customSupported -> context.getString(R.string.onboarding_driver_system_fallback)
        compatible.isNotEmpty() -> context.getString(
            R.string.onboarding_driver_compatible,
            compatible.joinToString { it.displayName },
        )
        !gpu.isAdreno || !gpu.isArm64 || gpu.family == GpuFamily.UNKNOWN ->
            context.getString(R.string.onboarding_driver_uncertain)
        else -> context.getString(R.string.onboarding_driver_system_fallback)
    }
    val selectableDrivers = if (gpu.isAdreno && gpu.isArm64 && customSupported) {
        val visible = if (BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS) {
            val catalog = GpuDriverHelper.loadBundledCatalog(context)?.drivers.orEmpty()
            BundledDriverVisibility.filterForDevice(
                installed = installed,
                info = gpu,
                catalogEntries = catalog,
                supportsCustomDriverLoading = customSupported,
            )
        } else {
            installed
        }
        visible.entries
            .filterNot { (_, metadata) -> metadata.experimental }
            .map { (file, metadata) ->
                OnboardingDriverOption(
                    key = if (metadata.name == "Default") "Default" else metadata.label,
                    title = if (metadata.name == "Default") {
                        context.getString(R.string.onboarding_system_vulkan)
                    } else {
                        metadata.uiTitle
                    },
                    description = metadata.description,
                    selected = (metadata.name == "Default" && selected == "Default") ||
                        metadata.label == selected,
                ) to (if (metadata.name == "Default") null else DriverTarget(file, metadata))
            }
    } else {
        emptyList()
    }
    val targets = selectableDrivers.mapNotNull { (option, target) ->
        target?.let { option.key to it }
    }.toMap().toMutableMap().apply {
        selectableDrivers.firstOrNull { it.first.key == "Default" }?.let { option ->
            put(option.first.key, DriverTarget(File("/system/vendor"), GpuDriverHelper.getSystemDriverMetadata()))
        }
    }
    return LoadedDriverInfo(
        info = OnboardingDriverInfo(
        gpu = gpuLabel(context, gpu),
        selectedDriver = selectedLabel,
        guidance = guidance,
            options = selectableDrivers.map { it.first },
        ),
        targets = targets,
    )
}

private fun gpuLabel(
    context: Context,
    gpu: com.zenithblue.sambas3.utils.AdrenoGpuInfo,
): String {
    val raw = gpu.rawModel?.trim().orEmpty()
    if (raw.isNotEmpty()) return raw
    if (gpu.isAdreno && gpu.gpuId != null) {
        return context.getString(R.string.onboarding_adreno_format, gpu.gpuId)
    }
    return context.getString(R.string.onboarding_gpu_unknown)
}
