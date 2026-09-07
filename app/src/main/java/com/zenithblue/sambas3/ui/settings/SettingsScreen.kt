package com.zenithblue.sambas3.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.zenithblue.sambas3.ui.settings.components.safeCombinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateListOf
import com.zenithblue.sambas3.ui.user.UsersScreen
import com.zenithblue.sambas3.ui.drivers.GpuDriversScreen
import com.zenithblue.sambas3.ui.settings.LogMonitorScreen
import com.zenithblue.sambas3.ui.crash.CrashLogsHistoryScreen
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceHeader
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceIcon
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceTitle
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceValue
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceSubtitle
import com.zenithblue.sambas3.ui.settings.components.preference.HomePreference
import com.zenithblue.sambas3.ui.settings.components.preference.RegularPreference
import com.zenithblue.sambas3.ui.settings.components.preference.SingleSelectionDialog
import com.zenithblue.sambas3.ui.settings.components.preference.SliderPreference
import com.zenithblue.sambas3.ui.settings.components.preference.SwitchPreference
import com.zenithblue.sambas3.ui.onboarding.ONBOARDING_ROUTE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zenithblue.sambas3.BuildConfig
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.UserRepository
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.provider.AppDataDocumentProvider
import com.zenithblue.sambas3.ui.common.ComposePreview
import com.zenithblue.sambas3.utils.FileUtil
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.InputBindingPrefs
import com.zenithblue.sambas3.ui.monitoring.MonitoringSettingsScreen
import com.zenithblue.sambas3.ui.controller.ControllerSettingsScreen
import com.zenithblue.sambas3.gameconfig.SettingsBackendAudit
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

@Composable
fun PulsingDot(modifier: Modifier = Modifier) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Box(
        modifier = modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(color = com.zenithblue.sambas3.RPCSXColors.primary, shape = CircleShape)
    )
}

@Composable
fun ControllerHintStrip(
    modifier: Modifier = Modifier,
    hints: List<Pair<Int, String>>
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(Color(0xEE090C16))
            .drawBehind {
                drawLine(
                    color = Color(0x20C9A84C),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        hints.forEachIndexed { index, (drawableId, label) ->
            if (index > 0) {
                Spacer(modifier = Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.45f)
                        .background(Color(0x30FFFFFF))
                )
                Spacer(modifier = Modifier.width(14.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Image(
                    painter = painterResource(id = drawableId),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label.uppercase(),
                    color = if (drawableId == R.drawable.cross) com.zenithblue.sambas3.RPCSXColors.primary else com.zenithblue.sambas3.RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun AmbientSettingsBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF080B14),
                        Color(0xFF0B101E),
                        Color(0xFF060810)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .size(380.dp)
                .offset(x = (-60).dp, y = (-40).dp)
                .blur(70.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            com.zenithblue.sambas3.RPCSXColors.primary.copy(alpha = 0.20f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(420.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 80.dp, y = 60.dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x281A3660),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
    }
}

private data class SettingDetailInfo(
    val title: String,
    val category: String,
    val description: String,
    val iconRes: Int,
    val status: String,
    val backend: String,
    val subsystem: String,
    val target: String,
    val actionLabel: String,
    val actionIconRes: Int = R.drawable.ic_keyboard_arrow_right
)

@Composable
private fun DetailMetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color = com.zenithblue.sambas3.RPCSXColors.textPrimary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0x350F1526),
        border = BorderStroke(1.dp, Color(0x18FFFFFF))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(18.dp)
                    .background(com.zenithblue.sambas3.RPCSXColors.primary, RoundedCornerShape(1.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = label,
                    color = com.zenithblue.sambas3.RPCSXColors.textSecondary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = value,
                    color = valueColor,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    lineHeight = 12.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun SettingsDetailPane(
    focusedKey: String,
    activeUser: String,
    isUltraWide: Boolean = true,
    onAction: () -> Unit = {}
) {
    val detail = when (focusedKey) {
        "internal_directory" -> SettingDetailInfo(
            title = "Storage Directory",
            category = "FILE SYSTEM & CACHE",
            description = "Access the emulator's internal storage directory. Manage cache, config files, shaders, firmware, and installed game packages directly on disk.",
            iconRes = R.drawable.ic_folder,
            status = "ACTIVE",
            backend = "LOCAL STORAGE",
            subsystem = "APP DATA VFS",
            target = "FILES & SHADERS",
            actionLabel = "OPEN FILE MANAGER",
            actionIconRes = R.drawable.ic_folder
        )
        "users" -> {
            val username = UserRepository.getUsername(activeUser) ?: ""
            SettingDetailInfo(
                title = "User Profiles",
                category = "ACCOUNT & SAVES",
                description = "Manage local console profiles, save data folders, and user accounts. Active profile is currently set to '$username'.",
                iconRes = R.drawable.ic_person,
                status = "ACTIVE",
                backend = "PROFILE SYSTEM",
                subsystem = "DEV_HDD0/HOME",
                target = username.uppercase(),
                actionLabel = "MANAGE PROFILES",
                actionIconRes = R.drawable.ic_person
            )
        }
        "onboarding" -> SettingDetailInfo(
            title = stringResource(R.string.onboarding_replay_title),
            category = "SETUP WIZARD",
            description = stringResource(R.string.onboarding_replay_description),
            iconRes = R.drawable.ic_refresh,
            status = stringResource(R.string.onboarding_ready),
            backend = stringResource(R.string.onboarding_setup_guide),
            subsystem = "FIRST LAUNCH",
            target = "LIBRARIES & FW",
            actionLabel = "RUN SETUP WIZARD",
            actionIconRes = R.drawable.ic_refresh
        )
        "advanced_settings" -> SettingDetailInfo(
            title = "Advanced Config",
            category = "RPCSX CORE ENGINE",
            description = "Configure core emulation parameters, CPU instruction set compilers (PPU/SPU LLVM), system variables, audio buffers, and file system path mappings.",
            iconRes = R.drawable.tune,
            status = "CONFIGURED",
            backend = "RPCSX SYSTEM",
            subsystem = "YAML CONFIG",
            target = "CORE / GPU / AUDIO",
            actionLabel = "OPEN ADVANCED SETTINGS",
            actionIconRes = R.drawable.tune
        )
        "custom_driver" -> SettingDetailInfo(
            title = "GPU Drivers",
            category = "GRAPHICS ACCELERATION",
            description = if (BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS) {
                "Select the Android system driver or offline Turnip packages included with Samba S3."
            } else {
                "Load custom graphics drivers (like Turnip or custom Vulkan/Adreno drivers) to optimize rendering performance, fix graphical glitches, and improve stability."
            },
            iconRes = R.drawable.memory,
            status = if (RPCSX.instance.supportsCustomDriverLoading()) "SUPPORTED" else "UNSUPPORTED",
            backend = "VULKAN 1.3",
            subsystem = "DRIVER LOADER",
            target = "ADRENO / TURNIP",
            actionLabel = "MANAGE GPU DRIVERS",
            actionIconRes = R.drawable.memory
        )
        "controls" -> SettingDetailInfo(
            title = "Controller Bindings",
            category = "INPUT INTERFACE",
            description = "Configure physical gamepad mappings, D-pad sensitivity, touch-screen overlays, haptic feedback, and input profiles.",
            iconRes = R.drawable.gamepad,
            status = "CONNECTED",
            backend = "INPUT INTERFACE",
            subsystem = "PAD SUBSYSTEM",
            target = "KEYBOARD & PAD",
            actionLabel = "CONFIGURE CONTROLLER",
            actionIconRes = R.drawable.gamepad
        )
        "share_logs" -> SettingDetailInfo(
            title = "System Logs",
            category = "DIAGNOSTICS & SHARING",
            description = "Export and share the emulator execution logs. Helpful for debugging crashes, verifying compatibility, and reporting bugs to the developers.",
            iconRes = R.drawable.ic_share,
            status = "READY",
            backend = "TEXT/PLAIN",
            subsystem = "LOG EXPORTER",
            target = "SYSTEM SHARE",
            actionLabel = "EXPORT LOG FILE",
            actionIconRes = R.drawable.ic_share
        )
        "logs" -> SettingDetailInfo(
            title = "Log Monitor",
            category = "REAL-TIME TELEMETRY",
            description = "Live streaming log viewer capturing RPCSX backend, kernel syscalls, Cell modules, Vulkan, GPU driver, and Android app logs in real-time.",
            iconRes = R.drawable.ic_terminal,
            status = "LIVE",
            backend = "LOGCAT STREAM",
            subsystem = "ASYNC LOG BUFFER",
            target = "KERNEL & ENGINE",
            actionLabel = "OPEN LOG MONITOR",
            actionIconRes = R.drawable.ic_terminal
        )
        "crash_logs" -> SettingDetailInfo(
            title = "Crash Logs History",
            category = "CRASH DIAGNOSTICS",
            description = "View diagnostics, backtraces, and logs for all crashed game sessions to investigate crashes and verify stability.",
            iconRes = R.drawable.ic_restore,
            status = "RECORDING",
            backend = "CRASH RECOVERY",
            subsystem = "SESSION JOURNAL",
            target = "TOMBSTONES & TRACES",
            actionLabel = "VIEW CRASH LOGS",
            actionIconRes = R.drawable.ic_restore
        )
        "monitoring" -> SettingDetailInfo(
            title = "Performance Monitor",
            category = "IN-GAME OVERLAY",
            description = "Configure the in-game FPS, frametime, RPCSX CPU, Android system, memory, thermal and battery telemetry overlay.",
            iconRes = R.drawable.ic_video,
            status = "ACTIVE",
            backend = "COMPOSE / RPCSX",
            subsystem = "HARDWARE SENSORS",
            target = "HEADS-UP DISPLAY",
            actionLabel = "CONFIGURE OVERLAY",
            actionIconRes = R.drawable.ic_video
        )
        "patches" -> SettingDetailInfo(
            title = stringResource(R.string.patch_manager),
            category = "COMMUNITY ENHANCEMENTS",
            description = stringResource(R.string.patch_manager_description),
            iconRes = R.drawable.tune,
            status = "AVAILABLE",
            backend = "PATCH REPOSITORY",
            subsystem = "RUNTIME PATCHES",
            target = "TITLE ID DATABASE",
            actionLabel = "OPEN PATCH MANAGER",
            actionIconRes = R.drawable.tune
        )
        else -> SettingDetailInfo(
            title = "SambaS3 Core",
            category = "EMULATION ENGINE",
            description = "Configure the heartbeat of your gaming experience. Adjust core frequency, cycle accuracy, and bios paths to optimize performance.",
            iconRes = R.drawable.tune,
            status = "OPTIMIZED",
            backend = "VULKAN 1.3",
            subsystem = "CORE SYSTEM",
            target = "RPCSX ENGINE",
            actionLabel = "OPEN SETTINGS",
            actionIconRes = R.drawable.tune
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    com.zenithblue.sambas3.RPCSXColors.primary.copy(alpha = 0.25f),
                                    Color(0x20141C30)
                                )
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .drawBehind {
                            drawRoundRect(
                                color = com.zenithblue.sambas3.RPCSXColors.primary.copy(alpha = 0.5f),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                                style = Stroke(width = 1.dp.toPx())
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = detail.iconRes),
                        contentDescription = null,
                        tint = com.zenithblue.sambas3.RPCSXColors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(com.zenithblue.sambas3.RPCSXColors.primary, CircleShape)
                        )
                        Text(
                            text = detail.category,
                            color = com.zenithblue.sambas3.RPCSXColors.primaryDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                    }
                    Text(
                        text = detail.title,
                        color = com.zenithblue.sambas3.RPCSXColors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.5.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = com.zenithblue.sambas3.RPCSXColors.primary.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, com.zenithblue.sambas3.RPCSXColors.primary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(com.zenithblue.sambas3.RPCSXColors.primary, CircleShape)
                        )
                        Text(
                            text = detail.status,
                            color = com.zenithblue.sambas3.RPCSXColors.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0x600A0E18),
                border = BorderStroke(1.dp, Color(0x18FFFFFF))
            ) {
                Text(
                    text = detail.description,
                    color = com.zenithblue.sambas3.RPCSXColors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DetailMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "STATUS",
                    value = detail.status,
                    valueColor = com.zenithblue.sambas3.RPCSXColors.primary
                )
                DetailMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "BACKEND",
                    value = detail.backend,
                    valueColor = com.zenithblue.sambas3.RPCSXColors.textPrimary
                )
                DetailMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "SUBSYSTEM",
                    value = detail.subsystem,
                    valueColor = com.zenithblue.sambas3.RPCSXColors.textPrimary
                )
                DetailMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "TARGET",
                    value = detail.target,
                    valueColor = com.zenithblue.sambas3.RPCSXColors.textPrimary
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clickable(onClick = onAction),
                shape = RoundedCornerShape(8.dp),
                color = com.zenithblue.sambas3.RPCSXColors.primary,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = detail.actionIconRes),
                        contentDescription = null,
                        tint = com.zenithblue.sambas3.RPCSXColors.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = detail.actionLabel,
                        color = com.zenithblue.sambas3.RPCSXColors.onPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x400A0E18), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "DEVICE: ${android.os.Build.MANUFACTURER.uppercase()} ${android.os.Build.MODEL.uppercase()}",
                    color = com.zenithblue.sambas3.RPCSXColors.textSecondary.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "ARCH: ${android.os.Build.SUPPORTED_ABIS.firstOrNull()?.uppercase() ?: "ARM64"}",
                    color = com.zenithblue.sambas3.RPCSXColors.textSecondary.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (isUltraWide) "DISPLAY: 21:9 WIDESCREEN" else "DISPLAY: WIDESCREEN",
                    color = com.zenithblue.sambas3.RPCSXColors.primaryDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Pick a drawable for an advanced-settings key using exact top-level names first,
 * then keyword match (path hint disambiguates e.g. Audio/Video "Renderer").
 */
@androidx.annotation.DrawableRes
fun advancedSettingIconRes(name: String, pathHint: String = ""): Int {
    val n = name.lowercase()
    val ctx = "$pathHint/$name".lowercase()

    when (n) {
        "core" -> return R.drawable.memory
        "video" -> return R.drawable.ic_video
        "audio" -> return R.drawable.ic_audio
        "vfs" -> return R.drawable.hard_drive
        "input/output", "input", "output" -> return R.drawable.gamepad
        "system" -> return R.drawable.perm_device_information
        "net", "network" -> return R.drawable.ic_wifi
        "savestate" -> return R.drawable.ic_save
        "miscellaneous", "misc" -> return R.drawable.ic_settings
        "log" -> return R.drawable.ic_terminal
        "vulkan" -> return R.drawable.ic_video
        "performance overlay" -> return R.drawable.ic_video
        "shader loading dialog" -> return R.drawable.ic_video
        "affinity" -> return R.drawable.memory
        "custom driver" -> return R.drawable.memory
        "workarounds" -> return R.drawable.ic_build
    }

    return when {
        n.contains("audio") || n.contains("volume") || n.contains("sound") ||
            n.contains("microphone") || n.contains("cubeb") || n.contains("avport") ||
            n.contains("time stretch") || n.contains("channel layout") ||
            n.contains("master volume") || n.contains("buffer duration") ||
            n.contains("sampling") ||
            (n.contains("renderer") && ctx.contains("audio")) -> R.drawable.ic_audio

        n.contains("video") || n.contains("vulkan") || n.contains("shader") ||
            n.contains("resolution") || n.contains("msaa") || n.contains("anisotropic") ||
            n.contains("vsync") || n.contains("rsx") || n.contains("frame") ||
            n.contains("gpu") || n.contains("texture") || n.contains("aspect") ||
            n.contains("vblank") || n.contains("stereo") || n.contains("overlay") ||
            n.contains("fidelityfx") || n.contains("rcas") || n.contains("vram") ||
            n.contains("antialiasing") || n.contains("display") ||
            (n.contains("renderer") && (ctx.contains("video") || !ctx.contains("audio"))) ->
            R.drawable.ic_video

        n.contains("ppu") || n.contains("spu") || n.contains("llvm") || n.contains("cpu") ||
            n.contains("thread") || n.contains("tsx") || n.contains("affinity") ||
            n.contains("core") || n.contains("mfc") || n.contains("preempt") ||
            n.contains("xfloat") || n.contains("reservation") -> R.drawable.memory

        n.contains("vfs") || n.contains("disk") || n.contains("hdd") || n.contains("cache") ||
            n.contains("directory") || n.contains("host_root") || n.contains("path") ||
            n.contains("dev_hdd") -> R.drawable.hard_drive

        n.contains("input") || n.contains("pad") || n.contains("controller") ||
            n.contains("mouse") || n.contains("keyboard") || n.contains("camera") ||
            n.contains("move") || n.contains("gun") || n.contains("button") ||
            n.contains("pressure") || n.contains("analog") -> R.drawable.gamepad

        n.contains("net") || n.contains("psn") || n.contains("dns") || n.contains("upnp") ||
            n.contains("internet") || n.contains("bind address") || n.contains("ip address") ||
            n.contains("ip swap") || n.contains("country") || n.startsWith("ip ") ->
            R.drawable.ic_wifi

        n.contains("save") || n.contains("state") || n.contains("suspend") -> R.drawable.ic_save

        n.contains("system") || n.contains("console") || n.contains("language") ||
            n.contains("license") || n.contains("psid") || n.contains("time offset") ||
            n.contains("enter button") -> R.drawable.perm_device_information

        n.contains("log") || n.contains("debug") || n.contains("gdb") ||
            n.contains("profiler") || n.contains("silence") -> R.drawable.ic_terminal

        n.contains("trophy") || n.contains("popup") || n.contains("hint") ||
            n.contains("fullscreen") || n.contains("autostart") || n.contains("autoexit") ||
            n.contains("autopause") || n.contains("home menu") || n.contains("window title") ->
            R.drawable.ic_settings

        n.contains("lock") -> R.drawable.ic_lock
        n.contains("info") -> R.drawable.ic_info
        n.contains("work") || n.contains("hack") || n.contains("compatibility") ||
            n.contains("libraries") -> R.drawable.ic_build

        else -> R.drawable.tune
    }
}

@Composable
private fun SettingLeadingIcon(name: String, pathHint: String = "") {
    PreferenceIcon(icon = painterResource(id = advancedSettingIconRes(name, pathHint)))
}

@Composable
private fun SettingApplyHint(path: String, inGame: Boolean, type: String) {
    PreferenceSubtitle(
        text = SettingsBackendAudit.applyHint(path, inGame = inGame, actualType = type)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    navigateTo: (path: String) -> Unit,
    settings: JSONObject,
    path: String = "",
    isInSplitPane: Boolean = false,
    isInGameSettings: Boolean = false,
    onValueCommitted: ((path: String, value: String) -> Unit)? = null,
    settingsSetter: ((path: String, value: String) -> Boolean)? = null
) {
    val context = LocalContext.current
    val setter: (String, String) -> Boolean =
        settingsSetter ?: RPCSX.instance::settingsSetGlobalAndVerify
    val settingValue = remember(settings) { mutableStateOf(settings) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // The native tree is the source of truth. Emit one compact audit whenever
    // the root is entered so stale paths/types are visible in Log Monitor and
    // in the tester's S3CFG capture.
    if (path.isEmpty()) {
        LaunchedEffect(settings) {
            val audit = SettingsBackendAudit.audit(settings)
            Log.i("S3CFG", "schema ${SettingsBackendAudit.compactLog(audit)} valid=${audit.isValid}")
        }
    }

    val filteredKeys = remember(searchQuery, settings, isSearching, path) {
        if (!isSearching || searchQuery.isBlank()) {
            settings.keys().asSequence().mapNotNull { key ->
                val obj = settingValue.value[key] as? JSONObject
                val itemPath = if (path.isEmpty()) "@@$key" else "$path@@$key"
                if (obj != null) itemPath to obj else null
            }.toList()
        } else {
            buildList {
                fun search(obj: JSONObject, basePath: String) {
                    obj.keys().forEach { key ->
                        val child = obj[key] as? JSONObject ?: return@forEach
                        val childPath = if (basePath.isEmpty()) "@@$key" else "$basePath@@$key"
                        val nameMatches = key.contains(searchQuery, ignoreCase = true)
                        if (isSettingsFolder(child)) {
                            if (nameMatches) add(childPath to child)
                            search(child, childPath)
                        } else if (nameMatches) {
                            add(childPath to child)
                        }
                    }
                }
                search(settings, path)
            }
        }
    }

    @Composable
    fun AdvancedSettingsContent(
        keys: List<Pair<String, JSONObject>>,
        modifier: Modifier = Modifier,
        contentPadding: PaddingValues = PaddingValues(0.dp)
    ) {
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(contentPadding),
        ) {
            items(keys, key = { it.first }) { (itemPath, itemObject) ->
                val key = itemPath.substringAfterLast("@@")
                if (itemObject != null) {
                    when (val type =
                        if (itemObject.has("type")) itemObject.getString("type") else null) {
                        null -> {
                            RegularPreference(
                                title = { PreferenceTitle(title = key) },
                                leadingIcon = { SettingLeadingIcon(key, itemPath) },
                                trailingContent = {
                                    PreferenceIcon(
                                        icon = painterResource(id = R.drawable.ic_keyboard_arrow_right)
                                    )
                                },
                                onClick = {
                                    Log.e(
                                        "Main",
                                        "Navigate to settings$itemPath, object $itemObject"
                                    )
                                    navigateTo("settings$itemPath")
                                }
                            )
                        }

                        "bool" -> {
                            var itemValue by remember(itemObject) { mutableStateOf(itemObject.getBoolean("value")) }
                            val def = itemObject.getBoolean("default")
                            SwitchPreference(
                                checked = itemValue,
                                title = {
                                    PreferenceTitle(
                                        title = key + if (itemValue == def) "" else " *"
                                    )
                                },
                                subtitle = { SettingApplyHint(itemPath, isInGameSettings, type) },
                                leadingIcon = { SettingLeadingIcon(key, itemPath) },
                                onClick = { value ->
                                    if (!setter(
                                            itemPath, if (value) "true" else "false"
                                        )
                                    ) {
                                        AlertDialogQueue.showDialog(
                                            context.getString(R.string.error),
                                            context.getString(
                                                R.string.failed_to_assign_value,
                                                value.toString(),
                                                itemPath
                                            )
                                        )
                                    } else {
                                        itemObject.put("value", value)
                                        itemValue = value
                                        onValueCommitted?.invoke(
                                            itemPath, if (value) "true" else "false"
                                        )
                                    }
                                },
                                onLongClick = {
                                    AlertDialogQueue.showDialog(
                                        title = context.getString(R.string.reset_setting),
                                        message = context.getString(R.string.ask_if_reset_key, key),
                                        onConfirm = {
                                            if (setter(
                                                    itemPath, def.toString()
                                                )
                                            ) {
                                                itemObject.put("value", def)
                                                itemValue = def
                                                onValueCommitted?.invoke(itemPath, def.toString())
                                            } else {
                                                AlertDialogQueue.showDialog(
                                                    context.getString(R.string.error),
                                                    context.getString(
                                                        R.string.failed_to_reset_key,
                                                        key
                                                    )
                                                )
                                            }
                                        })
                                })
                        }

                        "enum" -> {
                            var itemValue by remember(itemObject) { mutableStateOf(itemObject.getString("value")) }
                            val def = itemObject.getString("default")
                            val variantsJson = itemObject.getJSONArray("variants")
                            val variants = ArrayList<String>()
                            for (i in 0..<variantsJson.length()) {
                                variants.add(variantsJson.getString(i))
                            }

                            SingleSelectionDialog(
                                currentValue = if (itemValue in variants) itemValue else variants[0],
                                values = variants,
                                icon = { SettingLeadingIcon(key, itemPath) },
                                title = {
                                    PreferenceTitle(
                                        title = key + if (itemValue == def) "" else " *"
                                    )
                                },
                                subtitle = { SettingApplyHint(itemPath, isInGameSettings, type) },
                                onValueChange = { value ->
                                    if (!setter(
                                            itemPath, "\"" + value + "\""
                                        )
                                    ) {
                                        AlertDialogQueue.showDialog(
                                            context.getString(R.string.error),
                                            context.getString(
                                                R.string.failed_to_assign_value,
                                                value,
                                                itemPath
                                            )
                                        )
                                    } else {
                                        itemObject.put("value", value)
                                        itemValue = value
                                        onValueCommitted?.invoke(itemPath, "\"" + value + "\"")
                                    }
                                },
                                onLongClick = {
                                    AlertDialogQueue.showDialog(
                                        title = context.getString(R.string.reset_setting),
                                        message = context.getString(R.string.ask_if_reset_key, key),
                                        onConfirm = {
                                            if (setter(
                                                    itemPath, "\"" + def + "\""
                                                )
                                            ) {
                                                itemObject.put("value", def)
                                                itemValue = def
                                                onValueCommitted?.invoke(
                                                    itemPath, "\"" + def + "\""
                                                )
                                            } else {
                                                AlertDialogQueue.showDialog(
                                                    context.getString(R.string.error),
                                                    context.getString(
                                                        R.string.failed_to_reset_key,
                                                        key
                                                    )
                                                )
                                            }
                                        })
                                })
                        }

                        "uint", "int" -> {
                            var max = 0L
                            var min = 0L
                            var initialItemValue = 0L
                            var def = 0L
                            try {
                                initialItemValue = itemObject.getString("value").toLong()
                                max = itemObject.getString("max").toLong()
                                min = itemObject.getString("min").toLong()
                                def = itemObject.getString("default").toLong()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            var itemValue by remember(itemObject) { mutableLongStateOf(initialItemValue) }
                            if (min < max) {
                                SliderPreference(
                                    value = itemValue.toFloat(),
                                    valueRange = min.toFloat()..max.toFloat(),
                                    title = key + if (itemValue == def) "" else " *",
                                    leadingIcon = { SettingLeadingIcon(key, itemPath) },
                                    subtitle = SettingsBackendAudit.applyHint(
                                        itemPath, isInGameSettings, type
                                    ),
                                    steps = (max - min).toInt() - 1,
                                    onValueChange = { value ->
                                        if (!setter(
                                                itemPath, value.toLong().toString()
                                            )
                                        ) {
                                            AlertDialogQueue.showDialog(
                                                context.getString(R.string.error),
                                                context.getString(
                                                    R.string.failed_to_assign_value,
                                                    value.toString(),
                                                    itemPath
                                                )
                                            )
                                        } else {
                                            itemObject.put(
                                                "value", value.toLong().toString()
                                            )
                                            itemValue = value.toLong()
                                            onValueCommitted?.invoke(
                                                itemPath, value.toLong().toString()
                                            )
                                        }
                                    },
                                    valueContent = { PreferenceValue(text = itemValue.toString()) },
                                    onLongClick = {
                                        AlertDialogQueue.showDialog(
                                            title = context.getString(R.string.reset_setting),
                                            message = context.getString(
                                                R.string.ask_if_reset_key,
                                                key
                                            ),
                                            onConfirm = {
                                                if (setter(
                                                        itemPath, def.toString()
                                                    )
                                                ) {
                                                    itemObject.put("value", def)
                                                    itemValue = def
                                                    onValueCommitted?.invoke(itemPath, def.toString())
                                                } else {
                                                    AlertDialogQueue.showDialog(
                                                        context.getString(R.string.error),
                                                        context.getString(
                                                            R.string.failed_to_reset_key,
                                                            key
                                                        )
                                                    )
                                                }
                                            })
                                    })
                            }
                        }

                        "float" -> {
                            var itemValue by remember(itemObject) {
                                mutableDoubleStateOf(
                                    itemObject.getString(
                                        "value"
                                    ).toDouble()
                                )
                            }
                            val max = if (itemObject.has("max")) itemObject.getString("max")
                                .toDouble() else 0.0
                            val min = if (itemObject.has("min")) itemObject.getString("min")
                                .toDouble() else 0.0
                            val def =
                                if (itemObject.has("default")) itemObject.getString("default")
                                    .toDouble() else 0.0

                            if (min < max) {
                                SliderPreference(
                                    value = itemValue.toFloat(),
                                    valueRange = min.toFloat()..max.toFloat(),
                                    title = key + if (itemValue == def) "" else " *",
                                    leadingIcon = { SettingLeadingIcon(key, itemPath) },
                                    subtitle = SettingsBackendAudit.applyHint(
                                        itemPath, isInGameSettings, type
                                    ),
                                    steps = ceil(max - min).toInt() - 1,
                                    onValueChange = { value ->
                                        if (!setter(
                                                itemPath, value.toString()
                                            )
                                        ) {
                                            AlertDialogQueue.showDialog(
                                                context.getString(R.string.error),
                                                context.getString(
                                                    R.string.failed_to_assign_value,
                                                    value.toString(),
                                                    itemPath
                                                )
                                            )
                                        } else {
                                            itemObject.put("value", value.toDouble().toString())
                                            itemValue = value.toDouble()
                                            onValueCommitted?.invoke(
                                                itemPath, value.toString()
                                            )
                                        }
                                    },
                                    valueContent = { PreferenceValue(text = itemValue.toString()) },
                                    onLongClick = {
                                        AlertDialogQueue.showDialog(
                                            title = context.getString(R.string.reset_setting),
                                            message = context.getString(
                                                R.string.ask_if_reset_key,
                                                key
                                            ),
                                            onConfirm = {
                                                if (setter(
                                                        itemPath, def.toString()
                                                    )
                                                ) {
                                                    itemObject.put("value", def)
                                                    itemValue = def
                                                    onValueCommitted?.invoke(itemPath, def.toString())
                                                } else {
                                                    AlertDialogQueue.showDialog(
                                                        context.getString(R.string.error),
                                                        context.getString(
                                                            R.string.failed_to_reset_key,
                                                            key
                                                        )
                                                    )
                                                }
                                            })
                                    })
                            }
                        }

                        else -> {
                            Log.e("Main", "Unimplemented setting type $type")
                        }
                    }
                }
            }

            if (path.isEmpty()) {
            }
        }
    
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600
    val displayTitle = path.replace("@@", " / ").removePrefix(" / ")
        .ifEmpty { stringResource(R.string.advanced_settings) }

    @Composable
    fun AdvancedTopBar(compact: Boolean = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 48.dp else 54.dp)
                .background(Color(0xEE090C16))
                .drawBehind {
                    drawLine(
                        color = Color(0x20C9A84C),
                        start = androidx.compose.ui.geometry.Offset(0f, size.height),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = if (compact) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0x20C9A84C),
                border = BorderStroke(1.dp, Color(0x35C9A84C)),
                modifier = Modifier.size(if (compact) 30.dp else 34.dp)
            ) {
                IconButton(
                    onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                        } else {
                            navigateBack()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                        contentDescription = "Back",
                        tint = com.zenithblue.sambas3.RPCSXColors.primary,
                        modifier = Modifier.size(if (compact) 18.dp else 20.dp)
                    )
                }
            }

            if (isSearching) {
                Spacer(modifier = Modifier.width(8.dp))
                var expanded by remember { mutableStateOf(false) }
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
                ) {
                    SearchBar(
                        expanded = expanded,
                        onExpandedChange = {},
                        modifier = Modifier
                            .weight(1f)
                            .animateContentSize(),
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                onSearch = { expanded = false },
                                placeholder = { Text(stringResource(R.string.search)) },
                                leadingIcon = {
                                    Icon(painter = painterResource(id = R.drawable.ic_search), null)
                                },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        if (searchQuery.isNotEmpty()) {
                                            searchQuery = ""
                                        } else {
                                            isSearching = false
                                        }
                                    }) {
                                        Icon(painter = painterResource(id = R.drawable.ic_close), null)
                                    }
                                },
                                expanded = expanded,
                                onExpandedChange = {}
                            )
                        }
                    ) {}
                }
            } else {
                if (!compact) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.tune),
                        contentDescription = null,
                        tint = com.zenithblue.sambas3.RPCSXColors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = displayTitle.uppercase(),
                    color = com.zenithblue.sambas3.RPCSXColors.primary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 14.sp else 17.sp,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { isSearching = true }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search),
                        contentDescription = "Search",
                        tint = com.zenithblue.sambas3.RPCSXColors.primary
                    )
                }
            }
        }
    }

    @Composable
    fun WideAdvancedBody(contentPadding: PaddingValues) {
        val categories = remember(settings) {
            settings.keys().asSequence().filter { key ->
                isSettingsFolder(settings.optJSONObject(key))
            }.toList()
        }
        var selectedCategoryKey by remember(path) { mutableStateOf("") }
        LaunchedEffect(settings, categories) {
            if (selectedCategoryKey !in categories) {
                selectedCategoryKey = categories.firstOrNull() ?: ""
            }
        }
        val categoryObj = remember(settings, selectedCategoryKey) {
            settings.optJSONObject(selectedCategoryKey) ?: JSONObject()
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC0E1424),
                border = BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color(0x33C9A84C),
                            Color(0x15FFFFFF),
                            Color(0x06FFFFFF)
                        )
                    )
                )
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        SettingsCategoryHeader("CORE CATEGORIES")
                    }
                    items(categories) { category ->
                        SettingsNavCard(
                            title = category,
                            subtitle = "",
                            iconRes = advancedSettingIconRes(category),
                            isSelected = selectedCategoryKey == category,
                            onClick = { selectedCategoryKey = category },
                            onFocusChanged = { if (it) selectedCategoryKey = category }
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xD90E1424),
                border = BorderStroke(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(
                            Color(0x40C9A84C),
                            Color(0x18FFFFFF),
                            Color(0x08FFFFFF)
                        )
                    )
                )
            ) {
                val categoryPath =
                    if (path.isEmpty()) "@@$selectedCategoryKey" else "$path@@$selectedCategoryKey"
                val filteredKeysForCategory = remember(categoryObj, categoryPath) {
                    categoryObj.keys().asSequence().mapNotNull { key ->
                        val obj = categoryObj[key] as? JSONObject
                        if (obj != null) "$categoryPath@@$key" to obj else null
                    }.toList()
                }

                AdvancedSettingsContent(
                    keys = filteredKeysForCategory,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        }
    }

    if (isInSplitPane) {
        Column(modifier = Modifier.fillMaxSize()) {
            AdvancedTopBar(compact = true)
            AdvancedSettingsContent(keys = filteredKeys, contentPadding = PaddingValues(0.dp))
        }
    } else {
        Scaffold(
            modifier = modifier,
            topBar = { AdvancedTopBar() },
            bottomBar = {
                ControllerHintStrip(
                    hints = listOf(
                        R.drawable.cross to "Select",
                        R.drawable.circle to "Back"
                    )
                )
            }
        ) { contentPadding ->
            val folderCount = remember(settings) {
                settings.keys().asSequence().count { key ->
                    isSettingsFolder(settings.optJSONObject(key))
                }
            }
            // Two-pane only when there are nested folders. Pure leaf pages use the list.
            if (isWideScreen && !isSearching && folderCount > 0) {
                WideAdvancedBody(contentPadding = contentPadding)
            } else {
                AdvancedSettingsContent(keys = filteredKeys, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun SettingsCategoryHeader(text: String) {
    Text(
        text = text,
        color = com.zenithblue.sambas3.RPCSXColors.primaryDim,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun ControllerHintBadge(
    modifier: Modifier = Modifier,
    glyph: String,
    label: String,
    isPrimary: Boolean = false,
    isAccent: Boolean = false
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = when {
                isPrimary -> com.zenithblue.sambas3.RPCSXColors.primary.copy(alpha = 0.22f)
                isAccent -> Color(0x354CC9A8)
                else -> com.zenithblue.sambas3.RPCSXColors.textSecondary.copy(alpha = 0.15f)
            },
            border = BorderStroke(
                1.dp,
                when {
                    isPrimary -> com.zenithblue.sambas3.RPCSXColors.primary
                    isAccent -> Color(0x804CC9A8)
                    else -> com.zenithblue.sambas3.RPCSXColors.textSecondary.copy(alpha = 0.6f)
                }
            )
        ) {
            Text(
                text = glyph,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = when {
                    isPrimary -> com.zenithblue.sambas3.RPCSXColors.primary
                    isAccent -> Color(0xFF4CC9A8)
                    else -> com.zenithblue.sambas3.RPCSXColors.textSecondary
                }
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = if (isPrimary) com.zenithblue.sambas3.RPCSXColors.primary else com.zenithblue.sambas3.RPCSXColors.textSecondary
        )
    }
}

@Composable
private fun SettingsBottomBar(
    onYamlClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Color(0xEE090C16))
            .drawBehind {
                drawLine(
                    color = Color(0x20C9A84C),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControllerHintBadge(glyph = "D-PAD", label = "NAVIGATE")
            ControllerHintBadge(glyph = "✕", label = "SELECT", isPrimary = true)
            ControllerHintBadge(glyph = "○", label = "BACK")
            ControllerHintBadge(
                glyph = "▲",
                label = "YAML CONFIG",
                isAccent = true,
                modifier = Modifier.clickable(onClick = onYamlClick)
            )
        }
    }
}

@Composable
private fun SettingsNavCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    extraBadge: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false }
            .safeCombinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0x35C9A84C) else Color(0x1D141D2E),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) com.zenithblue.sambas3.RPCSXColors.primary else Color(0x18FFFFFF)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) com.zenithblue.sambas3.RPCSXColors.primary.copy(alpha = 0.25f) else Color(0x20FFFFFF),
                border = BorderStroke(1.dp, if (isSelected) com.zenithblue.sambas3.RPCSXColors.primary.copy(alpha = 0.6f) else Color(0x10FFFFFF)),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        tint = if (isSelected) com.zenithblue.sambas3.RPCSXColors.primary else com.zenithblue.sambas3.RPCSXColors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (isSelected) com.zenithblue.sambas3.RPCSXColors.primary else com.zenithblue.sambas3.RPCSXColors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = com.zenithblue.sambas3.RPCSXColors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.SansSerif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (extraBadge != null) {
                Spacer(modifier = Modifier.width(6.dp))
                extraBadge()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    navigateTo: (path: String) -> Unit,
    settings: JSONObject,
    onRefresh: () -> Unit,
    focusedKey: String,
    activeSettingKey: String?,
    onFocusedKeyChanged: (String) -> Unit,
    onActiveSettingKeyChanged: (String?) -> Unit,
) {
    val activeUser by remember { UserRepository.activeUser }
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    val configPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                if (FileUtil.importConfig(context, it))
                    onRefresh()
            }
        }
    )

    val configExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-yaml"),
        onResult = { uri: Uri? ->
            uri?.let { FileUtil.exportConfig(context, it) }
        }
    )

    fun openYamlManager() {
        AlertDialogQueue.showDialog(
            title = context.getString(R.string.manage_settings),
            confirmText = context.getString(R.string.export),
            dismissText = context.getString(R.string.import_),
            onDismiss = { configPicker.launch(arrayOf("*/*")) },
            onConfirm = { configExporter.launch("config.yml") }
        )
    }

    fun executeAction(key: String) {
        when (key) {
            "internal_directory" -> {
                if (!FileUtil.launchInternalDir(context)) {
                    AlertDialogQueue.showDialog(
                        context.getString(R.string.failed_to_view_internal_dir),
                        context.getString(R.string.no_activity_to_handle_action)
                    )
                }
            }
            "users" -> navigateTo("users")
            "onboarding" -> navigateTo(ONBOARDING_ROUTE)
            "advanced_settings" -> navigateTo("settings@@$")
            "custom_driver" -> {
                if (RPCSX.instance.supportsCustomDriverLoading()) {
                    navigateTo("drivers")
                } else {
                    AlertDialogQueue.showDialog(
                        title = context.getString(R.string.custom_driver_not_supported),
                        message = context.getString(R.string.custom_driver_not_supported_description),
                        confirmText = context.getString(R.string.close),
                        dismissText = ""
                    )
                }
            }
            "controls" -> navigateTo("controls")
            "monitoring" -> navigateTo("monitoring")
            "logs" -> navigateTo("logs")
            "crash_logs" -> navigateTo("crash_logs")
            "share_logs" -> {
                val file = DocumentFile.fromSingleUri(
                    context, DocumentsContract.buildDocumentUri(
                        AppDataDocumentProvider.AUTHORITY,
                        "${AppDataDocumentProvider.ROOT_ID}/cache/RPCSX${if (RPCSX.lastPlayedGame.isNotEmpty()) "" else ".old"}.log"
                    )
                )
                if (file != null && file.exists() && file.length() != 0L) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        setDataAndType(file.uri, "text/plain")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_STREAM, file.uri)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_log)))
                } else {
                    Toast.makeText(context, context.getString(R.string.log_not_found), Toast.LENGTH_SHORT).show()
                }
            }
            "patches" -> navigateTo("patches")
        }
    }

    val settingKeys = remember {
        listOfNotNull(
            "internal_directory",
            "users",
            "advanced_settings",
            "onboarding",
            "custom_driver",
            "controls",
            "monitoring",
            "logs",
            "crash_logs",
            "share_logs",
            if (!BuildConfig.IS_PLAYSTORE_BUILD) "patches" else null
        )
    }

    val totalItems = settingKeys.size
    val configuration = LocalConfiguration.current
    val numColumns = if (configuration.screenWidthDp >= 800) 3 else if (configuration.screenWidthDp >= 500) 2 else 1
    val gridState = rememberLazyGridState()
    val rootFocusRequester = remember { FocusRequester() }

    var focusedIndex by remember {
        mutableIntStateOf(settingKeys.indexOf(focusedKey).coerceAtLeast(0))
    }

    LaunchedEffect(Unit) {
        try { rootFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    fun navigateGrid(deltaX: Int, deltaY: Int) {
        val current = focusedIndex
        val target = when {
            deltaX > 0 -> {
                when (current) {
                    0 -> 1
                    1 -> 2
                    2 -> 2
                    3 -> 3
                    4 -> 5
                    5 -> 6
                    6 -> 6
                    7 -> 8
                    8 -> 9
                    9 -> 9
                    10 -> 10
                    else -> current
                }
            }
            deltaX < 0 -> {
                when (current) {
                    0 -> 0
                    1 -> 0
                    2 -> 1
                    3 -> 3
                    4 -> 4
                    5 -> 4
                    6 -> 5
                    7 -> 7
                    8 -> 7
                    9 -> 8
                    10 -> 10
                    else -> current
                }
            }
            deltaY > 0 -> {
                when (current) {
                    0 -> 3
                    1 -> 5
                    2 -> 6
                    3 -> 4
                    4 -> 7
                    5 -> 8
                    6 -> 9
                    7 -> 10
                    8 -> 10
                    9 -> 10
                    10 -> 10
                    else -> current
                }
            }
            deltaY < 0 -> {
                when (current) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    3 -> 0
                    4 -> 3
                    5 -> 1
                    6 -> 2
                    7 -> 4
                    8 -> 5
                    9 -> 6
                    10 -> 7
                    else -> current
                }
            }
            else -> current
        }

        if (target != focusedIndex && target in 0 until totalItems) {
            focusedIndex = target
            onFocusedKeyChanged(settingKeys[target])
        }
    }

    LaunchedEffect(focusedIndex) {
        val scrollTarget = when {
            focusedIndex < 4 -> 0
            focusedIndex < 7 -> 4
            else -> 8
        }
        gridState.animateScrollToItem(scrollTarget)
    }

    var stickArmedX by remember { mutableStateOf(true) }
    var stickArmedY by remember { mutableStateOf(true) }
    var stickHoldStartTimeX by remember { mutableLongStateOf(0L) }
    var stickHoldStartTimeY by remember { mutableLongStateOf(0L) }
    var lastStickStepTimeX by remember { mutableLongStateOf(0L) }
    var lastStickStepTimeY by remember { mutableLongStateOf(0L) }
    var lastKeyRepeatTime by remember { mutableLongStateOf(0L) }

    val currentView = LocalView.current
    DisposableEffect(currentView) {
        val motionListener = View.OnGenericMotionListener { _, event ->
            val source = event.source
            val isGamepadOrJoystick = (source and InputDevice.SOURCE_GAMEPAD != 0) ||
                (source and InputDevice.SOURCE_JOYSTICK != 0)
            if (!isGamepadOrJoystick) return@OnGenericMotionListener false

            val rawX = event.getAxisValue(MotionEvent.AXIS_X)
            val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val now = android.os.SystemClock.uptimeMillis()

            val effectiveX = if (abs(rawX) > 0.45f) rawX else hatX
            val effectiveY = if (abs(rawY) > 0.45f) rawY else hatY

            if (effectiveX < -0.55f) {
                if (stickArmedX) {
                    stickArmedX = false
                    stickHoldStartTimeX = now
                    lastStickStepTimeX = now
                    navigateGrid(deltaX = -1, deltaY = 0)
                } else if (now - stickHoldStartTimeX > 350L && now - lastStickStepTimeX > 180L) {
                    lastStickStepTimeX = now
                    navigateGrid(deltaX = -1, deltaY = 0)
                }
            } else if (effectiveX > 0.55f) {
                if (stickArmedX) {
                    stickArmedX = false
                    stickHoldStartTimeX = now
                    lastStickStepTimeX = now
                    navigateGrid(deltaX = 1, deltaY = 0)
                } else if (now - stickHoldStartTimeX > 350L && now - lastStickStepTimeX > 180L) {
                    lastStickStepTimeX = now
                    navigateGrid(deltaX = 1, deltaY = 0)
                }
            } else if (abs(effectiveX) < 0.20f) {
                stickArmedX = true
            }

            if (effectiveY < -0.55f) {
                if (stickArmedY) {
                    stickArmedY = false
                    stickHoldStartTimeY = now
                    lastStickStepTimeY = now
                    navigateGrid(deltaX = 0, deltaY = -1)
                } else if (now - stickHoldStartTimeY > 350L && now - lastStickStepTimeY > 180L) {
                    lastStickStepTimeY = now
                    navigateGrid(deltaX = 0, deltaY = -1)
                }
            } else if (effectiveY > 0.55f) {
                if (stickArmedY) {
                    stickArmedY = false
                    stickHoldStartTimeY = now
                    lastStickStepTimeY = now
                    navigateGrid(deltaX = 0, deltaY = 1)
                } else if (now - stickHoldStartTimeY > 350L && now - lastStickStepTimeY > 180L) {
                    lastStickStepTimeY = now
                    navigateGrid(deltaX = 0, deltaY = 1)
                }
            } else if (abs(effectiveY) < 0.20f) {
                stickArmedY = true
            }

            true
        }

        val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (event.repeatCount > 0) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastKeyRepeatTime < 180L) return@OnKeyListener true
                lastKeyRepeatTime = now
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> {
                    navigateGrid(deltaX = -1, deltaY = 0)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> {
                    navigateGrid(deltaX = 1, deltaY = 0)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    navigateGrid(deltaX = 0, deltaY = -1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    navigateGrid(deltaX = 0, deltaY = 1)
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val key = settingKeys.getOrNull(focusedIndex) ?: settingKeys[0]
                    executeAction(key)
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    navigateBack()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    openYamlManager()
                    true
                }
                else -> false
            }
        }

        currentView.setOnGenericMotionListener(motionListener)
        currentView.setOnKeyListener(keyListener)
        onDispose {
            currentView.setOnGenericMotionListener(null)
            currentView.setOnKeyListener(null)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = keyEvent.nativeKeyEvent.keyCode
                if (keyEvent.nativeKeyEvent.repeatCount > 0) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastKeyRepeatTime < 180L) return@onPreviewKeyEvent true
                    lastKeyRepeatTime = now
                }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> {
                        navigateGrid(deltaX = -1, deltaY = 0)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> {
                        navigateGrid(deltaX = 1, deltaY = 0)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        navigateGrid(deltaX = 0, deltaY = -1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        navigateGrid(deltaX = 0, deltaY = 1)
                        true
                    }
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val key = settingKeys.getOrNull(focusedIndex) ?: settingKeys[0]
                        executeAction(key)
                        true
                    }
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                        navigateBack()
                        true
                    }
                    KeyEvent.KEYCODE_BUTTON_Y -> {
                        openYamlManager()
                        true
                    }
                    else -> false
                }
            }
    ) {
        AmbientSettingsBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Color(0xEE090C16))
                    .drawBehind {
                        drawLine(
                            color = Color(0x20C9A84C),
                            start = androidx.compose.ui.geometry.Offset(0f, size.height),
                            end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0x20C9A84C),
                        border = BorderStroke(1.dp, Color(0x35C9A84C)),
                        modifier = Modifier
                            .size(34.dp)
                            .focusProperties { canFocus = false }
                    ) {
                        IconButton(
                            onClick = navigateBack,
                            modifier = Modifier
                                .fillMaxSize()
                                .focusProperties { canFocus = false }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                                contentDescription = "Back",
                                tint = com.zenithblue.sambas3.RPCSXColors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Small controller hint badge for Back: [ ○ ]
                    Surface(
                        shape = RoundedCornerShape(3.dp),
                        color = com.zenithblue.sambas3.RPCSXColors.textSecondary.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, com.zenithblue.sambas3.RPCSXColors.textSecondary.copy(alpha = 0.6f)),
                        modifier = Modifier.focusProperties { canFocus = false }
                    ) {
                        Text(
                            text = "○",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = com.zenithblue.sambas3.RPCSXColors.textSecondary
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Icon(
                        painter = painterResource(id = R.drawable.gamepad),
                        contentDescription = null,
                        tint = com.zenithblue.sambas3.RPCSXColors.primary,
                        modifier = Modifier.size(22.dp)
                    )

                    Text(
                        text = stringResource(R.string.settings).uppercase(),
                        color = com.zenithblue.sambas3.RPCSXColors.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = 2.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "v" + com.zenithblue.sambas3.BuildConfig.VERSION_NAME,
                        color = com.zenithblue.sambas3.RPCSXColors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    PulsingDot()
                }
            }

            // Grid of Settings Cards
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(numColumns),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Category 1: SYSTEM & STORAGE
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SettingsCategoryHeader("SYSTEM & STORAGE")
                }
                item(key = "internal_directory") {
                    SettingsNavCard(
                        title = stringResource(R.string.view_internal_dir),
                        subtitle = stringResource(R.string.view_internal_dir_description),
                        iconRes = R.drawable.ic_folder,
                        isSelected = focusedIndex == 0,
                        onClick = {
                            focusedIndex = 0
                            onFocusedKeyChanged("internal_directory")
                            executeAction("internal_directory")
                        }
                    )
                }
                item(key = "users") {
                    SettingsNavCard(
                        title = stringResource(R.string.users),
                        subtitle = "${stringResource(R.string.active_user)}: ${UserRepository.getUsername(activeUser)}",
                        iconRes = R.drawable.ic_person,
                        isSelected = focusedIndex == 1,
                        onClick = {
                            focusedIndex = 1
                            onFocusedKeyChanged("users")
                            executeAction("users")
                        }
                    )
                }
                item(key = "advanced_settings") {
                    SettingsNavCard(
                        title = stringResource(R.string.advanced_settings),
                        subtitle = stringResource(R.string.advanced_settings_description),
                        iconRes = R.drawable.tune,
                        isSelected = focusedIndex == 2,
                        onClick = {
                            focusedIndex = 2
                            onFocusedKeyChanged("advanced_settings")
                            executeAction("advanced_settings")
                        },
                        onLongClick = { openYamlManager() },
                        extraBadge = {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = Color(0x304CC9A8),
                                border = BorderStroke(1.dp, Color(0x804CC9A8))
                            ) {
                                Text(
                                    text = "▲ YAML",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = Color(0xFF4CC9A8)
                                )
                            }
                        }
                    )
                }
                item(key = "onboarding") {
                    SettingsNavCard(
                        title = stringResource(R.string.onboarding_replay_title),
                        subtitle = stringResource(R.string.onboarding_replay_description),
                        iconRes = R.drawable.ic_refresh,
                        isSelected = focusedIndex == 3,
                        onClick = {
                            focusedIndex = 3
                            onFocusedKeyChanged("onboarding")
                            executeAction("onboarding")
                        }
                    )
                }

                // Category 2: GRAPHICS & INPUT
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(4.dp))
                    SettingsCategoryHeader("GRAPHICS & INPUT")
                }
                item(key = "custom_driver") {
                    SettingsNavCard(
                        title = stringResource(R.string.custom_driver),
                        subtitle = stringResource(R.string.custom_driver_description),
                        iconRes = R.drawable.memory,
                        isSelected = focusedIndex == 4,
                        onClick = {
                            focusedIndex = 4
                            onFocusedKeyChanged("custom_driver")
                            executeAction("custom_driver")
                        }
                    )
                }
                item(key = "controls") {
                    SettingsNavCard(
                        title = stringResource(R.string.controls),
                        subtitle = stringResource(R.string.controls_description),
                        iconRes = R.drawable.gamepad,
                        isSelected = focusedIndex == 5,
                        onClick = {
                            focusedIndex = 5
                            onFocusedKeyChanged("controls")
                            executeAction("controls")
                        }
                    )
                }
                item(key = "monitoring") {
                    SettingsNavCard(
                        title = "Performance Monitor",
                        subtitle = "In-game FPS, telemetry & battery overlay",
                        iconRes = R.drawable.ic_video,
                        isSelected = focusedIndex == 6,
                        onClick = {
                            focusedIndex = 6
                            onFocusedKeyChanged("monitoring")
                            executeAction("monitoring")
                        }
                    )
                }

                // Category 3: DIAGNOSTICS & TOOLS
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(4.dp))
                    SettingsCategoryHeader("DIAGNOSTICS & TOOLS")
                }
                item(key = "logs") {
                    SettingsNavCard(
                        title = stringResource(R.string.log_monitor),
                        subtitle = stringResource(R.string.log_monitor_description),
                        iconRes = R.drawable.ic_terminal,
                        isSelected = focusedIndex == 7,
                        onClick = {
                            focusedIndex = 7
                            onFocusedKeyChanged("logs")
                            executeAction("logs")
                        }
                    )
                }
                item(key = "crash_logs") {
                    SettingsNavCard(
                        title = "Crash Logs History",
                        subtitle = "Diagnostics, backtraces & session logs",
                        iconRes = R.drawable.ic_restore,
                        isSelected = focusedIndex == 8,
                        onClick = {
                            focusedIndex = 8
                            onFocusedKeyChanged("crash_logs")
                            executeAction("crash_logs")
                        }
                    )
                }
                item(key = "share_logs") {
                    SettingsNavCard(
                        title = stringResource(R.string.share_log),
                        subtitle = stringResource(R.string.share_log_description),
                        iconRes = R.drawable.ic_share,
                        isSelected = focusedIndex == 9,
                        onClick = {
                            focusedIndex = 9
                            onFocusedKeyChanged("share_logs")
                            executeAction("share_logs")
                        }
                    )
                }
                if (!BuildConfig.IS_PLAYSTORE_BUILD) {
                    item(key = "patches") {
                        SettingsNavCard(
                            title = stringResource(R.string.patch_manager),
                            subtitle = stringResource(R.string.patch_manager_description),
                            iconRes = R.drawable.ic_build,
                            isSelected = focusedIndex == 10,
                            onClick = {
                                focusedIndex = 10
                                onFocusedKeyChanged("patches")
                                executeAction("patches")
                            }
                        )
                    }
                }
            }

            // Bottom Controller Hint Bar (matching Home Screen style)
            SettingsBottomBar(onYamlClick = { openYamlManager() })
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerSettings(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    isInSplitPane: Boolean = false
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    @Composable
    fun ControllerContent(contentPadding: PaddingValues) {
        
        //val context = LocalContext.current
        val inputBindings = remember {
            mutableStateMapOf<Int, Pair<Int, Int>>().apply {
                putAll(InputBindingPrefs.loadBindings())
            }
        }

        var showDialog by remember { mutableStateOf(false) }
        var currentInput by remember { mutableStateOf(-1) }
        var currentInputName by remember { mutableStateOf("") }
        val requester = remember { FocusRequester() }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                PreferenceHeader(stringResource(R.string.gamepad_overlay))
            }

            item {
                var itemValue by remember {
                    mutableStateOf(
                        GeneralSettings["haptic_feedback"] as Boolean? ?: true
                    )
                }
                val def = true
                SwitchPreference(
                    checked = itemValue,
                    title = stringResource(R.string.enable_haptic_feedback) + if (itemValue == def) "" else " *",
                    leadingIcon = null,
                    onClick = { value ->
                        GeneralSettings.setValue("haptic_feedback", value)
                        itemValue = value
                    }
                )
            }

            item {
                HorizontalDivider()
            }

            item {
                PreferenceHeader(stringResource(R.string.key_mappings))
            }

            inputBindings.toList()
                .sortedBy { (_, value) ->
                    val name = InputBindingPrefs.rpcsxKeyCodeToString(value.first, value.second)
                    InputBindingPrefs.defaultBindings.values.indexOfFirst { defValue ->
                        InputBindingPrefs.rpcsxKeyCodeToString(
                            defValue.first,
                            defValue.second
                        ) == name
                    }
                }
                .forEach { binding ->
                    item {
                        RegularPreference(
                            title = InputBindingPrefs.rpcsxKeyCodeToString(
                                binding.second.first,
                                binding.second.second
                            ),
                            value = {
                                PreferenceValue(
                                    if (binding.first.toString().length > 4) stringResource(R.string.none)
                                    else KeyEvent.keyCodeToString(binding.first)
                                )
                            },
                            onClick = {
                                currentInput = binding.first
                                currentInputName = InputBindingPrefs.rpcsxKeyCodeToString(
                                    binding.second.first,
                                    binding.second.second
                                )
                                showDialog = true
                            }
                        )
                    }
                }
        }

        if (showDialog) {
            InputBindingDialog(
                onReset = {
                    InputBindingPrefs.defaultBindings.forEach {
                        if (InputBindingPrefs.rpcsxKeyCodeToString(
                                it.value.first,
                                it.value.second
                            ) == currentInputName
                        ) {
                            inputBindings[currentInput]?.let { value ->
                                inputBindings.remove(currentInput)
                                inputBindings[it.key] = value
                            }
                            InputBindingPrefs.saveBindings(inputBindings.toMap())
                        }
                    }
                },
                onDismissRequest = { showDialog = false },
                modifier = Modifier
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            if (showDialog) {
                                if (inputBindings.containsKey(keyEvent.nativeKeyEvent.keyCode)) {
                                    inputBindings[keyEvent.nativeKeyEvent.keyCode]?.let { value ->
                                        inputBindings.remove(keyEvent.nativeKeyEvent.keyCode)
                                        inputBindings[(10000..99999).random()] = value
                                    }
                                }
                                inputBindings[currentInput]?.let { value ->
                                    inputBindings.remove(currentInput)
                                    inputBindings[keyEvent.nativeKeyEvent.keyCode] = value
                                }
                                InputBindingPrefs.saveBindings(inputBindings.toMap())
                                showDialog = false
                                true
                            } else false
                        } else false
                    }
                    .focusRequester(requester)
                    .focusable()

            )

            LaunchedEffect(showDialog) {
                requester.requestFocus()
            }
        }
    
    }

    if (isInSplitPane) {
        // Settings left list already shows selection; no nested back/title chrome.
        ControllerContent(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp))
    } else {
        Scaffold(
            modifier = Modifier
                .nestedScroll(topBarScrollBehavior.nestedScrollConnection)
                .then(modifier),
            topBar = {
                LargeTopAppBar(
                    title = { Text(text = stringResource(R.string.controls), fontWeight = FontWeight.Medium) },
                    scrollBehavior = topBarScrollBehavior,
                    navigationIcon = {
                        IconButton(
                            onClick = navigateBack
                        ) {
                            Icon(painter = painterResource(id = R.drawable.ic_keyboard_arrow_left), null)
                        }
                    }
                )
            },
            bottomBar = {
                ControllerHintStrip(
                    hints = listOf(
                        R.drawable.cross to "Select",
                        R.drawable.circle to "Back"
                    )
                )
            }
        ) { contentPadding ->
            ControllerContent(contentPadding = contentPadding)
        }
    }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputBindingDialog(
    modifier: Modifier = Modifier,
    onReset: () -> Unit = {},
    onDismissRequest: () -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.perform_input),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(75.dp)
            ) {
                ButtonMappingAnim()
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onReset,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.reset))
            }
        }
    }
}

@Composable
fun ButtonMappingAnim() {
    val infiniteTransition = rememberInfiniteTransition()

    val scaleX by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 568),
            repeatMode = RepeatMode.Reverse
        )
    )

    val scaleY by infiniteTransition.animateFloat(
        initialValue = 1.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 568),
            repeatMode = RepeatMode.Reverse
        )
    )

    Image(
        painter = painterResource(id = R.drawable.button_mapping),
        contentDescription = null,
        modifier = Modifier
            .graphicsLayer(
                scaleX = scaleX,
                scaleY = scaleY
            )
            .fillMaxSize()
    )
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    ComposePreview {
//        SettingsScreen {}
    }
}
