package com.zenithblue.sambas3.ui.onboarding

import android.os.Build
import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.AppTypography
import com.zenithblue.sambas3.FirmwareStatus
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.utils.GameFolderMatch

data class OnboardingDeviceInfo(
    val deviceName: String,
    val soc: String,
    val properSocName: String,
    val architecture: String,
    val gpu: String,
    val ram: String,
    val android: String,
    val socLogoRes: Int = R.drawable.hw_soc_fallback,
    val cpuLogoRes: Int = R.drawable.hw_cpu_fallback,
    val gpuLogoRes: Int = R.drawable.hw_gpu_fallback,
)

object HardwareInfoResolver {
    fun resolveProperSocName(rawSoc: String, gpu: String): String {
        val s = rawSoc.uppercase()
        val g = gpu.uppercase()
        return when {
            s.contains("SM8750") || s.contains("8 ELITE") -> "Snapdragon 8 Elite"
            s.contains("SM8650") || s.contains("PINEAPPLE") -> "Snapdragon 8 Gen 3"
            s.contains("SM8550") || s.contains("KALAMA") -> "Snapdragon 8 Gen 2"
            s.contains("SM8475") || s.contains("CAPE") -> "Snapdragon 8+ Gen 1"
            s.contains("SM8450") || s.contains("TARO") -> "Snapdragon 8 Gen 1"
            s.contains("SM8350") || s.contains("LAHAINA") -> "Snapdragon 888"
            s.contains("SM8250") || s.contains("KONA") -> "Snapdragon 865 / 870"
            s.contains("SM8150") -> "Snapdragon 855"
            s.contains("SDM845") -> "Snapdragon 845"
            s.contains("SM7675") -> "Snapdragon 7+ Gen 3"
            s.contains("SM7550") -> "Snapdragon 7 Gen 3"
            s.contains("SM7475") -> "Snapdragon 7+ Gen 2"
            s.contains("SM7325") -> "Snapdragon 778G"
            s.contains("SM7250") -> "Snapdragon 765G"
            s.contains("MT6991") || s.contains("DIMENSITY 9400") -> "MediaTek Dimensity 9400"
            s.contains("MT6989") || s.contains("DIMENSITY 9300") -> "MediaTek Dimensity 9300"
            s.contains("MT6985") || s.contains("DIMENSITY 9200") -> "MediaTek Dimensity 9200"
            s.contains("MT6983") || s.contains("DIMENSITY 9000") -> "MediaTek Dimensity 9000"
            s.contains("MT6897") || s.contains("DIMENSITY 8300") -> "MediaTek Dimensity 8300"
            s.contains("MT6895") || s.contains("DIMENSITY 8200") || s.contains("DIMENSITY 8100") -> "MediaTek Dimensity 8100/8200"
            s.contains("MT6893") -> "MediaTek Dimensity 1200"
            s.contains("MT6877") -> "MediaTek Dimensity 900/1080"
            s.contains("S5E9945") || s.contains("EXYNOS 2400") -> "Samsung Exynos 2400"
            s.contains("S5E9925") || s.contains("EXYNOS 2200") -> "Samsung Exynos 2200"
            s.contains("S5E9840") || s.contains("EXYNOS 2100") -> "Samsung Exynos 2100"
            s.contains("S5E9830") || s.contains("EXYNOS 990") -> "Samsung Exynos 990"
            s.contains("ZUMAPRO") || s.contains("TENSOR G4") -> "Google Tensor G4"
            s.contains("ZUMA") || s.contains("TENSOR G3") -> "Google Tensor G3"
            s.contains("GS201") || s.contains("TENSOR G2") -> "Google Tensor G2"
            s.contains("GS101") || s.contains("TENSOR G1") || s.contains("TENSOR") -> "Google Tensor G1"
            s.contains("QTI") || s.contains("QCOM") || s.contains("QUALCOMM") || g.contains("ADRENO") -> {
                val model = s.replace("QTI", "").replace("QUALCOMM", "").trim()
                if (model.isNotEmpty()) "Snapdragon ($model)" else "Qualcomm Snapdragon"
            }
            s.contains("MEDIATEK") || s.contains("MTK") -> "MediaTek Dimensity"
            s.contains("EXYNOS") || s.contains("S5E") -> "Samsung Exynos"
            else -> rawSoc
        }
    }

    fun resolveSocLogo(rawSoc: String, gpu: String, isAdreno: Boolean): Int {
        val s = rawSoc.lowercase()
        val g = gpu.lowercase()
        return when {
            s.contains("mediatek") || s.contains("mt6") || s.contains("dimensity") || s.contains("helio") -> R.drawable.hw_soc_mediatek
            s.contains("qti") || s.contains("qcom") || s.contains("qualcomm") || s.contains("snapdragon") || s.contains("sm8") || s.contains("sm7") || s.contains("sm6") || s.contains("sdm") || isAdreno -> R.drawable.hw_soc_snapdragon
            s.contains("samsung") || s.contains("exynos") || s.contains("s5e") -> R.drawable.hw_soc_exynos
            s.contains("google") || s.contains("tensor") || s.contains("gs101") || s.contains("gs201") || s.contains("zuma") -> R.drawable.hw_soc_tensor
            s.contains("powervr") || g.contains("powervr") || g.contains("img") || g.contains("imagination") -> R.drawable.hw_soc_powervr
            else -> R.drawable.hw_soc_fallback
        }
    }

    fun resolveCpuLogo(rawSoc: String): Int {
        val s = rawSoc.lowercase()
        return when {
            s.contains("sm8750") || s.contains("8 elite") || s.contains("oryon") -> R.drawable.hw_cpu_oryon
            Build.SUPPORTED_ABIS.any { it.contains("arm", ignoreCase = true) } -> R.drawable.hw_cpu_arm
            else -> R.drawable.hw_cpu_fallback
        }
    }

    fun resolveGpuLogo(rawSoc: String, gpu: String, isAdreno: Boolean): Int {
        val s = rawSoc.lowercase()
        val g = gpu.lowercase()
        if (s.contains("mediatek") || s.contains("mt6") || s.contains("dimensity") || s.contains("helio")) {
            return R.drawable.hw_gpu_mali
        }
        return when {
            isAdreno || g.contains("adreno") -> R.drawable.hw_gpu_adreno
            g.contains("mali") || g.contains("immortalis") -> R.drawable.hw_gpu_mali
            g.contains("xclipse") || g.contains("radeon") || g.contains("rdna") -> R.drawable.hw_gpu_xclipse
            g.contains("powervr") || g.contains("imagination") || g.contains("img") -> R.drawable.hw_gpu_imagine
            else -> R.drawable.hw_gpu_fallback
        }
    }
}

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

private data class OnboardingHeroMeta(
    val iconRes: Int,
    val title: String,
    val subtitle: String,
    val stepLabel: String,
)

fun Modifier.gamepadClickable(onClick: () -> Unit): Modifier = this.onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown &&
        (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
         event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
         event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
    ) {
        onClick()
        true
    } else {
        false
    }
}

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
    val hero = when (page) {
        0 -> OnboardingHeroMeta(
            iconRes = R.mipmap.ic_sambas3_foreground,
            title = stringResource(R.string.onboarding_page_welcome),
            subtitle = "Prepare SambaS3 to run PlayStation 3 games.",
            stepLabel = "STEP 1 OF 7",
        )
        1 -> OnboardingHeroMeta(
            iconRes = R.drawable.ic_info,
            title = stringResource(R.string.onboarding_page_permissions),
            subtitle = "Notification access for background compilation and updates.",
            stepLabel = "STEP 2 OF 7",
        )
        2 -> OnboardingHeroMeta(
            iconRes = deviceInfo.socLogoRes,
            title = stringResource(R.string.onboarding_page_device),
            subtitle = "${deviceInfo.properSocName} • ${deviceInfo.gpu}",
            stepLabel = "STEP 3 OF 7",
        )
        3 -> OnboardingHeroMeta(
            iconRes = R.drawable.hw_firmware_icon,
            title = stringResource(R.string.onboarding_page_firmware),
            subtitle = "Official PS3 system software package.",
            stepLabel = "STEP 4 OF 7",
        )
        4 -> OnboardingHeroMeta(
            iconRes = deviceInfo.gpuLogoRes,
            title = stringResource(R.string.onboarding_page_driver),
            subtitle = "${deviceInfo.gpu} • ${driverInfo.selectedDriver}",
            stepLabel = "STEP 5 OF 7",
        )
        5 -> OnboardingHeroMeta(
            iconRes = R.drawable.hw_game_folder,
            title = stringResource(R.string.onboarding_page_games),
            subtitle = "Locate and scan your PS3 game directory.",
            stepLabel = "STEP 6 OF 7",
        )
        else -> OnboardingHeroMeta(
            iconRes = R.drawable.ic_onboarding_complete,
            title = stringResource(R.string.onboarding_page_complete),
            subtitle = "Setup is complete. Ready to play PS3 games.",
            stepLabel = "STEP 7 OF 7",
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWidescreen = maxWidth >= 540.dp
        if (isWidescreen) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroShowcase(
                    hero = hero,
                    page = page,
                    deviceInfo = deviceInfo,
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight(),
                )
                GlassContentCard(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight(),
                ) {
                    RenderPageBody(
                        page = page,
                        deviceInfo = deviceInfo,
                        driverInfo = driverInfo,
                        permissionInfo = permissionInfo,
                        firmwareVersion = firmwareVersion,
                        firmwareStatus = firmwareStatus,
                        firmwareInstalling = firmwareInstalling,
                        firmwareProgress = firmwareProgress,
                        firmwareProgressMessage = firmwareProgressMessage,
                        gameCount = gameCount,
                        scannedGames = scannedGames,
                        scanningGames = scanningGames,
                        runtimeAvailable = runtimeAvailable,
                        firmwareActionEnabled = firmwareActionEnabled,
                        gameFolderActionEnabled = gameFolderActionEnabled,
                        onInstallFirmware = onInstallFirmware,
                        onSelectGameFolder = onSelectGameFolder,
                        onRequestNotifications = onRequestNotifications,
                        onSelectDriver = onSelectDriver,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactHeroHeader(hero = hero, page = page, deviceInfo = deviceInfo)
                GlassContentCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    RenderPageBody(
                        page = page,
                        deviceInfo = deviceInfo,
                        driverInfo = driverInfo,
                        permissionInfo = permissionInfo,
                        firmwareVersion = firmwareVersion,
                        firmwareStatus = firmwareStatus,
                        firmwareInstalling = firmwareInstalling,
                        firmwareProgress = firmwareProgress,
                        firmwareProgressMessage = firmwareProgressMessage,
                        gameCount = gameCount,
                        scannedGames = scannedGames,
                        scanningGames = scanningGames,
                        runtimeAvailable = runtimeAvailable,
                        firmwareActionEnabled = firmwareActionEnabled,
                        gameFolderActionEnabled = gameFolderActionEnabled,
                        onInstallFirmware = onInstallFirmware,
                        onSelectGameFolder = onSelectGameFolder,
                        onRequestNotifications = onRequestNotifications,
                        onSelectDriver = onSelectDriver,
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderPageBody(
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
private fun HeroShowcase(
    hero: OnboardingHeroMeta,
    page: Int,
    deviceInfo: OnboardingDeviceInfo,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xCC0E131E),
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0x33E5A93C), Color(0x15FFFFFF)))),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Ambient radiant glow behind artwork
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                if (page == 1) Color(0x30388BFD) else RPCSXColors.primary.copy(alpha = 0.26f),
                                Color.Transparent,
                            ),
                        ),
                        CircleShape,
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (page == 0) {
                    // Page 1: SambaS3 App Logo badge
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xCC0D111A),
                        border = BorderStroke(1.5.dp, Brush.verticalGradient(listOf(RPCSXColors.primary, Color(0x33E5A93C)))),
                        modifier = Modifier.size(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Image(
                                painter = painterResource(R.mipmap.ic_sambas3_foreground),
                                contentDescription = stringResource(R.string.app_name),
                                modifier = Modifier.size(76.dp),
                            )
                        }
                    }
                } else if (page == 1) {
                    // Page 2: Normal info icon, not SVG
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xCC0D111A),
                        border = BorderStroke(1.5.dp, Brush.verticalGradient(listOf(Color(0xFF58A6FF), Color(0x33388BFD)))),
                        modifier = Modifier.size(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                tint = Color(0xFF58A6FF),
                                modifier = Modifier.size(46.dp),
                            )
                        }
                    }
                } else if (page == 2) {
                    // Page 3: Detected SoC Vendor Logo (Snapdragon, MediaTek, etc.)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF0F4F8),
                        border = BorderStroke(1.5.dp, Brush.verticalGradient(listOf(RPCSXColors.primary, Color(0x44E5A93C)))),
                        modifier = Modifier.size(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                            Image(
                                painter = painterResource(deviceInfo.socLogoRes),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                } else if (page == 3) {
                    // Page 4: PS3 Firmware Icon
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xCC0D111A),
                        border = BorderStroke(1.5.dp, Brush.verticalGradient(listOf(RPCSXColors.primary, Color(0x33E5A93C)))),
                        modifier = Modifier.size(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                            Image(
                                painter = painterResource(R.drawable.hw_firmware_icon),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                } else if (page == 4) {
                    // Page 5: Detected GPU Vendor Logo (Adreno, Mali, Xclipse, etc.)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF0F4F8),
                        border = BorderStroke(1.5.dp, Brush.verticalGradient(listOf(RPCSXColors.primary, Color(0x44E5A93C)))),
                        modifier = Modifier.size(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                            Image(
                                painter = painterResource(deviceInfo.gpuLogoRes),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                } else if (page == 5) {
                    // Page 6: Game Library Folder Icon
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xCC0D111A),
                        border = BorderStroke(1.5.dp, Brush.verticalGradient(listOf(RPCSXColors.primary, Color(0x33E5A93C)))),
                        modifier = Modifier.size(92.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(10.dp)) {
                            Image(
                                painter = painterResource(R.drawable.hw_game_folder),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                } else {
                    Image(
                        painter = painterResource(hero.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (page == 1) Color(0x22388BFD) else RPCSXColors.primaryMuted,
                ) {
                    Text(
                        text = hero.stepLabel,
                        style = AppTypography.labelSmall.copy(letterSpacing = 1.2.sp, fontSize = 10.sp),
                        color = if (page == 1) Color(0xFF58A6FF) else RPCSXColors.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hero.title,
                    style = AppTypography.headlineMedium.copy(fontSize = 18.sp),
                    color = RPCSXColors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hero.subtitle,
                    style = AppTypography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = RPCSXColors.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CompactHeroHeader(hero: OnboardingHeroMeta, page: Int, deviceInfo: OnboardingDeviceInfo) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xCC0E131E),
        border = BorderStroke(1.dp, Color(0x22E5A93C)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page == 0) {
                Image(
                    painter = painterResource(R.mipmap.ic_sambas3_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
            } else if (page == 1) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = Color(0xFF58A6FF),
                    modifier = Modifier.size(36.dp),
                )
            } else if (page == 2) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF0F4F8),
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(deviceInfo.socLogoRes),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            } else if (page == 3) {
                Image(
                    painter = painterResource(R.drawable.hw_firmware_icon),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
            } else if (page == 4) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF0F4F8),
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(deviceInfo.gpuLogoRes),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            } else if (page == 5) {
                Image(
                    painter = painterResource(R.drawable.hw_game_folder),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
            } else {
                Image(
                    painter = painterResource(hero.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hero.title,
                    style = AppTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = RPCSXColors.textPrimary,
                )
                Text(
                    text = hero.subtitle,
                    style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                    color = RPCSXColors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GlassContentCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xD90D111A),
        border = BorderStroke(1.dp, Color(0x3330363D)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SetupStepBadge(
            num = "1",
            title = "Permissions",
            desc = "Notification access for compilation and task status",
        )
        SetupStepBadge(
            num = "2",
            title = "Device Check",
            desc = "System SoC, GPU, and RAM compatibility",
        )
        SetupStepBadge(
            num = "3",
            title = "PS3 Firmware",
            desc = "System software installation via PS3UPDAT.PUP",
        )
        SetupStepBadge(
            num = "4",
            title = "Graphics & Games",
            desc = "Vulkan driver profile & PS3 game directory scan",
        )
        InlineNotice("You can review or modify any setting later in the launcher.")
    }
}

@Composable
private fun SetupStepBadge(
    num: String,
    title: String,
    desc: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = RPCSXColors.surfaceElevated.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0x2230363D)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(RPCSXColors.primaryMuted, CircleShape)
                    .border(1.dp, RPCSXColors.primaryDim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = num,
                    style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    color = RPCSXColors.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                    color = RPCSXColors.textPrimary,
                )
                Text(
                    text = desc,
                    style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                    color = RPCSXColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun PermissionsPage(
    info: OnboardingPermissionInfo,
    onRequestNotifications: () -> Unit,
) {
    val isGranted = info.notificationsGranted
    val isRequired = info.notificationsRequired
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isGranted || !isRequired) {
            // Permission ALREADY granted or not required: Full rich display!
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0x2056D364),
                border = BorderStroke(1.dp, Color(0x4D56D364)),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_circle),
                        contentDescription = null,
                        tint = Color(0xFF56D364),
                        modifier = Modifier.size(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isGranted) "Notifications Enabled" else "Notifications Not Required",
                            style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                            color = Color(0xFF56D364),
                        )
                        Text(
                            text = if (isGranted) "SambaS3 has access to show background task updates." else "Your Android version does not require explicit notification permission.",
                            style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                            color = RPCSXColors.textSecondary,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0x3356D364),
                    ) {
                        Text(
                            text = "READY",
                            style = AppTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = Color(0xFF56D364),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Text(
                text = "BACKGROUND TASKS ENABLED",
                style = AppTypography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
                color = RPCSXColors.textSecondary,
            )

            PermissionFeatureRow(
                iconRes = R.drawable.ic_folder,
                title = "Firmware Installation",
                description = "Live progress while unpacking and installing official PS3UPDAT.PUP.",
            )
            PermissionFeatureRow(
                iconRes = R.drawable.ic_build,
                title = "PPU & Shader Precompilation",
                description = "Alerts when LLVM shader caches and PPU modules are ready for gameplay.",
            )
            PermissionFeatureRow(
                iconRes = R.drawable.ic_save,
                title = "Game Library Indexing",
                description = "Status notifications when scanning game folders or importing titles.",
            )

            InlineNotice("All permissions configured. Press Continue to proceed to Device Check.")
        } else {
            // Permission NEEDED: Clear call to action!
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = RPCSXColors.surfaceElevated.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, Color(0x3330363D)),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        tint = RPCSXColors.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Notification Access",
                            style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                            color = RPCSXColors.textPrimary,
                        )
                        Text(
                            text = "Required on Android 13+ to show install and compilation progress.",
                            style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                            color = RPCSXColors.textSecondary,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0x33E5A93C),
                    ) {
                        Text(
                            text = "ACTION NEEDED",
                            style = AppTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = RPCSXColors.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Text(
                text = "WHY NOTIFICATIONS ARE NEEDED",
                style = AppTypography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
                color = RPCSXColors.textSecondary,
            )

            PermissionFeatureRow(
                iconRes = R.drawable.ic_folder,
                title = "Firmware Updates",
                description = "Shows progress while installing official PlayStation 3 firmware in the background.",
            )
            PermissionFeatureRow(
                iconRes = R.drawable.ic_build,
                title = "Shader & PPU Compilation",
                description = "Informs you when games have finished compiling caches so you can play without stutter.",
            )

            Surface(
                onClick = onRequestNotifications,
                shape = RoundedCornerShape(8.dp),
                color = if (isFocused) Color(0xFFFFCC00) else Color(0xFFFFB800),
                border = if (isFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0x60FFFFFF)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .onFocusChanged { isFocused = it.isFocused }
                    .gamepadClickable(onRequestNotifications),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_allow_notifications),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionFeatureRow(
    iconRes: Int,
    title: String,
    description: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = RPCSXColors.surfaceElevated.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0x2230363D)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = RPCSXColors.primary,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = RPCSXColors.textPrimary,
                )
                Text(
                    text = description,
                    style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                    color = RPCSXColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun DeviceCheckPage(info: OnboardingDeviceInfo) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. Processor / SoC with Vendor Logo
        HardwareSpecRow(
            logoRes = info.socLogoRes,
            title = "PROCESSOR / SOC",
            primaryValue = info.properSocName,
            secondaryValue = if (info.properSocName != info.soc) info.soc else null,
        )

        // 2. CPU Architecture with Logo
        HardwareSpecRow(
            logoRes = info.cpuLogoRes,
            title = "CPU ARCHITECTURE",
            primaryValue = info.architecture,
            secondaryValue = if (info.cpuLogoRes == R.drawable.hw_cpu_oryon) "Qualcomm Oryon™ 64-bit Core" else "ARM 64-bit Architecture",
        )

        // 3. GPU Graphics with Logo
        HardwareSpecRow(
            logoRes = info.gpuLogoRes,
            title = "GRAPHICS ACCELERATOR",
            primaryValue = info.gpu,
            secondaryValue = "Vulkan API Hardware Accelerated",
        )

        // 4. Device Model, Memory & OS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactSpecCard(
                title = stringResource(R.string.onboarding_device),
                value = info.deviceName,
                modifier = Modifier.weight(1f),
            )
            CompactSpecCard(
                title = stringResource(R.string.onboarding_memory),
                value = info.ram,
                modifier = Modifier.weight(0.6f),
            )
            CompactSpecCard(
                title = stringResource(R.string.onboarding_android),
                value = info.android,
                modifier = Modifier.weight(0.9f),
            )
        }
    }
}

@Composable
private fun HardwareSpecRow(
    logoRes: Int,
    title: String,
    primaryValue: String,
    secondaryValue: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = RPCSXColors.surfaceElevated.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, Color(0x3330363D)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // White badge container for the logo so dark/colored text on transparent pops with maximum clarity!
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFF0F4F8),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                modifier = Modifier.size(width = 50.dp, height = 38.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(logoRes),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = AppTypography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                    color = RPCSXColors.textSecondary,
                )
                Text(
                    text = primaryValue,
                    style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                    color = RPCSXColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondaryValue != null) {
                    Text(
                        text = secondaryValue,
                        style = AppTypography.bodySmall.copy(fontSize = 10.sp),
                        color = RPCSXColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSpecCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = RPCSXColors.surfaceElevated.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, Color(0x2A30363D)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = AppTypography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                color = RPCSXColors.textSecondary,
            )
            Text(
                text = value,
                style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                color = RPCSXColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    val isInstalled = version != null
    val statusText = when {
        installing -> stringResource(R.string.onboarding_firmware_installing)
        version != null && status == FirmwareStatus.Compiled -> stringResource(R.string.onboarding_firmware_compiled, version)
        version != null -> stringResource(R.string.onboarding_firmware_installed, version)
        else -> stringResource(R.string.onboarding_firmware_missing)
    }
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = RPCSXColors.surfaceElevated.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, Color(0x3330363D)),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_firmware_status).uppercase(),
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                        color = RPCSXColors.textSecondary,
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (isInstalled) Color(0x2656D364) else Color(0x33E5A93C),
                    ) {
                        Text(
                            text = if (isInstalled) "INSTALLED" else "REQUIRED",
                            style = AppTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = if (isInstalled) Color(0xFF56D364) else RPCSXColors.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = statusText,
                    style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = RPCSXColors.textPrimary,
                )
                Text(
                    text = progressMessage ?: "Select official PS3UPDAT.PUP from Sony to install core firmware.",
                    style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                    color = RPCSXColors.textSecondary,
                )
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp)),
                        color = RPCSXColors.primary,
                        trackColor = RPCSXColors.surfaceElevated,
                    )
                }
            }
        }

        if (!runtimeAvailable) {
            InlineNotice(stringResource(R.string.onboarding_runtime_unavailable_action))
        }

        Surface(
            onClick = onInstall,
            enabled = actionEnabled,
            shape = RoundedCornerShape(8.dp),
            color = if (isFocused) Color(0xFFFFCC00) else Color(0xFFFFB800),
            border = if (isFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0x60FFFFFF)),
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .gamepadClickable { if (actionEnabled) onInstall() },
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = if (actionEnabled) Color.Black else RPCSXColors.textDisabled,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_install_firmware),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (actionEnabled) Color.Black else RPCSXColors.textDisabled,
                )
            }
        }
    }
}

@Composable
private fun DriverPage(
    info: OnboardingDriverInfo,
    onSelectDriver: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactSpecCard(
                title = stringResource(R.string.onboarding_detected_gpu),
                value = info.gpu,
                modifier = Modifier.weight(1f),
            )
            CompactSpecCard(
                title = stringResource(R.string.onboarding_selected_driver),
                value = info.selectedDriver,
                modifier = Modifier.weight(1f),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = RPCSXColors.surfaceElevated.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, Color(0x3330363D)),
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.onboarding_driver_guidance).uppercase(),
                    style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                    color = RPCSXColors.textSecondary,
                )
                Text(
                    text = info.guidance,
                    style = AppTypography.bodySmall.copy(fontSize = 12.sp),
                    color = RPCSXColors.textPrimary,
                )
                Text(
                    text = stringResource(R.string.onboarding_driver_settings_hint),
                    style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                    color = RPCSXColors.textSecondary,
                )
            }
        }

        if (info.options.isNotEmpty()) {
            Text(
                text = stringResource(R.string.onboarding_driver_options).uppercase(),
                style = AppTypography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
                color = RPCSXColors.textSecondary,
            )
            info.options.forEach { option ->
                DriverOptionRow(
                    option = option,
                    onSelect = { onSelectDriver(option.key) },
                )
            }
        }
    }
}

@Composable
private fun DriverOptionRow(
    option: OnboardingDriverOption,
    onSelect: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val isMesa = option.title.contains("Turnip", ignoreCase = true) ||
        option.title.contains("Mesa", ignoreCase = true) ||
        option.key.contains("turnip", ignoreCase = true)
    val driverLogoRes = if (isMesa) R.drawable.hw_driver_mesa else R.drawable.hw_driver_vulkan

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) RPCSXColors.primary else if (option.selected) RPCSXColors.primaryDim else Color(0x2230363D),
                shape = RoundedCornerShape(8.dp),
            )
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !option.selected, onClick = onSelect)
            .gamepadClickable { if (!option.selected) onSelect() },
        color = if (option.selected) Color(0x22E5A93C) else RPCSXColors.surfaceElevated.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFF0F4F8),
                modifier = Modifier.size(width = 46.dp, height = 34.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(3.dp)) {
                    Image(
                        painter = painterResource(driverLogoRes),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.title,
                    style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = if (option.selected) RPCSXColors.primary else RPCSXColors.textPrimary,
                )
                if (option.description.isNotEmpty()) {
                    Text(
                        text = option.description,
                        style = AppTypography.bodySmall.copy(fontSize = 10.sp),
                        color = RPCSXColors.textSecondary,
                    )
                }
            }
            if (option.selected) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0x33E5A93C),
                ) {
                    Text(
                        text = "ACTIVE",
                        style = AppTypography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = RPCSXColors.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
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
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = RPCSXColors.surfaceElevated.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, Color(0x3330363D)),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.onboarding_games_found).uppercase(),
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                        color = RPCSXColors.textSecondary,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_games_count, gameCount),
                        style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                        color = RPCSXColors.textPrimary,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (gameCount > 0) Color(0x2656D364) else Color(0x2230363D),
                ) {
                    Text(
                        text = if (gameCount > 0) "$gameCount INDEXED" else "NO GAMES",
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = if (gameCount > 0) Color(0xFF56D364) else RPCSXColors.textSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        scannedGames?.let { matches ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = RPCSXColors.surfaceElevated.copy(alpha = 0.7f),
                border = BorderStroke(1.dp, Color(0x3330363D)),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "SCANNED DIRECTORIES (${matches.size})",
                        style = AppTypography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                        color = RPCSXColors.textSecondary,
                    )
                    if (matches.isEmpty()) {
                        Text(
                            text = stringResource(R.string.onboarding_no_games_found),
                            style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                            color = RPCSXColors.textSecondary,
                        )
                    } else {
                        matches.take(3).forEach { match ->
                            Text(
                                text = "• ${match.titleId ?: "Game"}: ${match.folderName}",
                                style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                                color = RPCSXColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (!runtimeAvailable) {
            InlineNotice(stringResource(R.string.onboarding_runtime_unavailable_action))
        }

        Surface(
            onClick = onSelectFolder,
            enabled = actionEnabled && !scanningGames,
            shape = RoundedCornerShape(8.dp),
            color = if (isFocused) Color(0xFFFFCC00) else Color(0xFFFFB800),
            border = if (isFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0x60FFFFFF)),
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .gamepadClickable { if (actionEnabled && !scanningGames) onSelectFolder() },
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = if (actionEnabled && !scanningGames) Color.Black else RPCSXColors.textDisabled,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (scanningGames) R.string.onboarding_scanning_games else R.string.onboarding_scan_game_folder
                    ),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (actionEnabled && !scanningGames) Color.Black else RPCSXColors.textDisabled,
                )
            }
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
    val fwSummary = when {
        firmwareVersion == null -> stringResource(R.string.onboarding_firmware_missing)
        firmwareStatus == FirmwareStatus.Compiled -> stringResource(R.string.onboarding_firmware_compiled, firmwareVersion)
        else -> stringResource(R.string.onboarding_firmware_installed, firmwareVersion)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChecklistRow(title = "Device", value = "${deviceInfo.deviceName} (${deviceInfo.properSocName})", verified = true)
        ChecklistRow(title = "Firmware", value = fwSummary, verified = firmwareVersion != null)
        ChecklistRow(title = "Driver", value = driverInfo.selectedDriver, verified = true)
        ChecklistRow(title = "Games", value = "$gameCount game(s) available", verified = gameCount > 0)
        InlineNotice("Setup complete! You can start games from the main library.")
    }
}

@Composable
private fun ChecklistRow(
    title: String,
    value: String,
    verified: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = RPCSXColors.surfaceElevated.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0x2230363D)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(if (verified) R.drawable.ic_check_circle else R.drawable.circle),
                contentDescription = null,
                tint = if (verified) Color.Unspecified else RPCSXColors.primary,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.uppercase(),
                    style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                    color = RPCSXColors.textSecondary,
                )
                Text(
                    text = value,
                    style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = RPCSXColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InlineNotice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = RPCSXColors.primaryMuted.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, RPCSXColors.primaryDim),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = AppTypography.labelSmall.copy(fontSize = 11.sp),
            color = RPCSXColors.primary,
            textAlign = TextAlign.Center,
        )
    }
}
