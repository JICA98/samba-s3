package com.zenithblue.sambas3.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.FirmwareStatus
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.AppTypography
import com.zenithblue.sambas3.utils.GameFolderMatch

data class OnboardingDeviceInfo(
    val deviceName: String,
    val soc: String,
    val architecture: String,
    val gpu: String,
    val ram: String,
    val android: String,
)

data class OnboardingDriverInfo(
    val gpu: String,
    val selectedDriver: String,
    val guidance: String,
    val options: List<OnboardingDriverOption> = emptyList(),
)

data class OnboardingDriverOption(
    val key: String,
    val title: String,
    val description: String,
    val selected: Boolean,
)

data class OnboardingPermissionInfo(
    val notificationsGranted: Boolean,
    val notificationsRequired: Boolean,
)

@Composable
fun OnboardingPageContent(
    page: Int,
    deviceInfo: OnboardingDeviceInfo,
    driverInfo: OnboardingDriverInfo,
    permissionInfo: OnboardingPermissionInfo,
    firmwareVersion: String?,
    firmwareStatus: FirmwareStatus,
    firmwareInstalling: Boolean,
    firmwareProgress: Float?,
    firmwareProgressMessage: String?,
    gameCount: Int,
    scannedGames: List<GameFolderMatch>?,
    scanningGames: Boolean,
    runtimeAvailable: Boolean,
    firmwareActionEnabled: Boolean,
    gameFolderActionEnabled: Boolean,
    onInstallFirmware: () -> Unit,
    onSelectGameFolder: () -> Unit,
    onRequestNotifications: () -> Unit,
    onSelectDriver: (String) -> Unit,
) {
    when (page) {
        0 -> WelcomePage()
        1 -> PermissionsPage(permissionInfo, onRequestNotifications)
        2 -> DeviceCheckPage(deviceInfo)
        3 -> FirmwarePage(
            version = firmwareVersion,
            status = firmwareStatus,
            installing = firmwareInstalling,
            progress = firmwareProgress,
            progressMessage = firmwareProgressMessage,
            runtimeAvailable = runtimeAvailable,
            actionEnabled = firmwareActionEnabled,
            onInstall = onInstallFirmware,
        )
        4 -> DriverPage(driverInfo, onSelectDriver)
        5 -> GameLibraryPage(
            gameCount = gameCount,
            scannedGames = scannedGames,
            scanningGames = scanningGames,
            runtimeAvailable = runtimeAvailable,
            actionEnabled = gameFolderActionEnabled,
            onSelectFolder = onSelectGameFolder,
        )
        else -> CompletePage(
            deviceInfo = deviceInfo,
            firmwareVersion = firmwareVersion,
            firmwareStatus = firmwareStatus,
            driverInfo = driverInfo,
            gameCount = gameCount,
        )
    }
}

@Composable
private fun PermissionsPage(
    info: OnboardingPermissionInfo,
    onRequestNotifications: () -> Unit,
) {
    val status = when {
        info.notificationsGranted -> stringResource(R.string.onboarding_permission_allowed)
        info.notificationsRequired -> stringResource(R.string.onboarding_permission_not_allowed)
        else -> stringResource(R.string.onboarding_permission_not_required)
    }
    PageColumn {
        Text(
            text = stringResource(R.string.onboarding_permissions_intro),
            style = AppTypography.bodyLarge,
            color = RPCSXColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        InfoCard(
            title = stringResource(R.string.onboarding_notifications),
            value = status,
            description = stringResource(R.string.onboarding_notifications_description),
        )
        if (!info.notificationsRequired) {
            InlineNotice(stringResource(R.string.onboarding_permission_not_required))
        }
        Button(
            onClick = onRequestNotifications,
            enabled = info.notificationsRequired && !info.notificationsGranted,
            colors = ButtonDefaults.buttonColors(
                containerColor = RPCSXColors.primary,
                contentColor = RPCSXColors.onPrimary,
                disabledContainerColor = RPCSXColors.surfaceElevated,
                disabledContentColor = RPCSXColors.textDisabled,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (info.notificationsGranted) {
                        R.string.onboarding_notifications_allowed_action
                    } else {
                        R.string.onboarding_allow_notifications
                    }
                )
            )
        }
    }
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@Composable
private fun WelcomePage() {
    PageColumn {
        Image(
            painter = painterResource(R.mipmap.ic_sambas3_foreground),
            contentDescription = null,
            modifier = Modifier.size(84.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_heading),
            style = AppTypography.displayLarge.copy(letterSpacing = 4.sp),
            color = RPCSXColors.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = AppTypography.bodyLarge,
            color = RPCSXColors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.85f),
        )
        InfoCard(
            title = stringResource(R.string.onboarding_sequence_title),
            value = stringResource(R.string.onboarding_sequence_value),
            description = stringResource(R.string.onboarding_sequence_description),
        )
    }
}

@Composable
private fun DeviceCheckPage(info: OnboardingDeviceInfo) {
    PageColumn {
        Text(
            text = stringResource(R.string.onboarding_device_check_intro),
            style = AppTypography.bodyLarge,
            color = RPCSXColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        InfoCard(stringResource(R.string.onboarding_device), info.deviceName)
        InfoCard(stringResource(R.string.onboarding_cpu_soc), info.soc)
        InfoCard(stringResource(R.string.onboarding_architecture), info.architecture)
        InfoCard(stringResource(R.string.onboarding_gpu), info.gpu)
        InfoCard(stringResource(R.string.onboarding_memory), info.ram)
        InfoCard(stringResource(R.string.onboarding_android), info.android)
    }
}

@Composable
private fun FirmwarePage(
    version: String?,
    status: FirmwareStatus,
    installing: Boolean,
    progress: Float?,
    progressMessage: String?,
    runtimeAvailable: Boolean,
    actionEnabled: Boolean,
    onInstall: () -> Unit,
) {
    val statusText = when {
        installing -> stringResource(R.string.onboarding_firmware_installing)
        version != null && status == FirmwareStatus.Compiled ->
            stringResource(R.string.onboarding_firmware_compiled, version)
        version != null -> stringResource(R.string.onboarding_firmware_installed, version)
        else -> stringResource(R.string.onboarding_firmware_missing)
    }

    PageColumn {
        Text(
            text = stringResource(R.string.onboarding_firmware_intro),
            style = AppTypography.bodyLarge,
            color = RPCSXColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        InfoCard(
            title = stringResource(R.string.onboarding_firmware_status),
            value = statusText,
            description = progressMessage ?: stringResource(R.string.onboarding_firmware_status_description),
        )
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(0.8f),
                color = RPCSXColors.primary,
                trackColor = RPCSXColors.surfaceElevated,
            )
        }
        if (!runtimeAvailable) {
            InlineNotice(stringResource(R.string.onboarding_runtime_unavailable_action))
        }
        Button(
            onClick = onInstall,
            enabled = actionEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = RPCSXColors.primary,
                contentColor = RPCSXColors.onPrimary,
                disabledContainerColor = RPCSXColors.surfaceElevated,
                disabledContentColor = RPCSXColors.textDisabled,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onboarding_install_firmware))
        }
    }
}

@Composable
private fun DriverPage(
    info: OnboardingDriverInfo,
    onSelectDriver: (String) -> Unit,
) {
    PageColumn {
        Text(
            text = stringResource(R.string.onboarding_driver_intro),
            style = AppTypography.bodyLarge,
            color = RPCSXColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        InfoCard(stringResource(R.string.onboarding_detected_gpu), info.gpu)
        InfoCard(stringResource(R.string.onboarding_selected_driver), info.selectedDriver)
        InfoCard(
            title = stringResource(R.string.onboarding_driver_guidance),
            value = info.guidance,
            description = stringResource(R.string.onboarding_driver_settings_hint),
        )
        if (info.options.isNotEmpty()) {
            Text(
                text = stringResource(R.string.onboarding_driver_options),
                style = AppTypography.labelSmall,
                color = RPCSXColors.textSecondary,
            )
            info.options.forEach { option ->
                OutlinedButton(
                    onClick = { onSelectDriver(option.key) },
                    enabled = !option.selected,
                    modifier = Modifier.fillMaxWidth(0.9f),
                    border = BorderStroke(
                        1.dp,
                        if (option.selected) RPCSXColors.primary else RPCSXColors.outlineVariant,
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = RPCSXColors.primary,
                        disabledContentColor = RPCSXColors.primary,
                    ),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(option.title)
                        if (option.selected) {
                            Text(
                                text = stringResource(R.string.onboarding_driver_selected),
                                style = AppTypography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameLibraryPage(
    gameCount: Int,
    scannedGames: List<GameFolderMatch>?,
    scanningGames: Boolean,
    runtimeAvailable: Boolean,
    actionEnabled: Boolean,
    onSelectFolder: () -> Unit,
) {
    PageColumn {
        Text(
            text = stringResource(R.string.onboarding_game_library_intro),
            style = AppTypography.bodyLarge,
            color = RPCSXColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        InfoCard(
            title = stringResource(R.string.onboarding_games_found),
            value = stringResource(R.string.onboarding_games_count, gameCount),
            description = stringResource(R.string.onboarding_games_status_description),
        )
        scannedGames?.let { matches ->
            InfoCard(
                title = stringResource(R.string.onboarding_folder_scan_result),
                value = stringResource(R.string.onboarding_games_count, matches.size),
                description = if (matches.isEmpty()) {
                    stringResource(R.string.onboarding_no_games_found)
                } else {
                    stringResource(R.string.onboarding_games_preview_description)
                },
            )
            matches.forEach { match ->
                InfoCard(
                    title = match.titleId ?: stringResource(R.string.onboarding_found_game),
                    value = match.folderName,
                )
            }
        }
        if (!runtimeAvailable) {
            InlineNotice(stringResource(R.string.onboarding_runtime_unavailable_action))
        }
        OutlinedButton(
            onClick = onSelectFolder,
            enabled = actionEnabled && !scanningGames,
            border = BorderStroke(
                1.dp,
                if (actionEnabled) RPCSXColors.primary else RPCSXColors.textDisabled,
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = RPCSXColors.primary,
                disabledContentColor = RPCSXColors.textDisabled,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (scanningGames) R.string.onboarding_scanning_games
                    else R.string.onboarding_scan_game_folder
                )
            )
        }
    }
}

@Composable
private fun CompletePage(
    deviceInfo: OnboardingDeviceInfo,
    firmwareVersion: String?,
    firmwareStatus: FirmwareStatus,
    driverInfo: OnboardingDriverInfo,
    gameCount: Int,
) {
    val firmwareSummary = when {
        firmwareVersion == null -> stringResource(R.string.onboarding_firmware_missing)
        firmwareStatus == FirmwareStatus.Compiled -> stringResource(R.string.onboarding_firmware_compiled, firmwareVersion)
        else -> stringResource(R.string.onboarding_firmware_installed, firmwareVersion)
    }
    PageColumn {
        Text(
            text = stringResource(R.string.onboarding_complete_heading),
            style = AppTypography.headlineMedium,
            color = RPCSXColors.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_complete_body),
            style = AppTypography.bodyLarge,
            color = RPCSXColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        InfoCard(stringResource(R.string.onboarding_device), deviceInfo.deviceName)
        InfoCard(stringResource(R.string.onboarding_firmware_status), firmwareSummary)
        InfoCard(stringResource(R.string.onboarding_selected_driver), driverInfo.selectedDriver)
        InfoCard(stringResource(R.string.onboarding_games_found), stringResource(R.string.onboarding_games_count, gameCount))
    }
}

@Composable
private fun InfoCard(
    title: String,
    value: String,
    description: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.9f),
        shape = RoundedCornerShape(8.dp),
        color = RPCSXColors.surface,
        border = BorderStroke(1.dp, RPCSXColors.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = AppTypography.labelSmall,
                color = RPCSXColors.textSecondary,
            )
            Text(
                text = value,
                style = AppTypography.bodyLarge,
                color = RPCSXColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = AppTypography.labelMedium,
                    color = RPCSXColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun InlineNotice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.9f),
        shape = RoundedCornerShape(6.dp),
        color = RPCSXColors.primaryMuted,
        border = BorderStroke(1.dp, RPCSXColors.primaryDim),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = AppTypography.labelMedium,
            color = RPCSXColors.primary,
            textAlign = TextAlign.Center,
        )
    }
}
