package com.zenithblue.sambas3.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateListOf
import com.zenithblue.sambas3.ui.user.UsersScreen
import com.zenithblue.sambas3.ui.drivers.GpuDriversScreen
import com.zenithblue.sambas3.ui.settings.LogMonitorScreen
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
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
            .height(36.dp)
            .background(com.zenithblue.sambas3.RPCSXColors.surfaceContainerHigh)
            .drawBehind {
                drawLine(
                    color = com.zenithblue.sambas3.RPCSXColors.outlineVariant,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        hints.forEachIndexed { index, (drawableId, label) ->
            if (index > 0) {
                Spacer(modifier = Modifier.width(16.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.5f)
                        .background(com.zenithblue.sambas3.RPCSXColors.textDisabled)
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    painter = painterResource(id = drawableId),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = label.uppercase(),
                    color = if (drawableId == R.drawable.cross) com.zenithblue.sambas3.RPCSXColors.primary else com.zenithblue.sambas3.RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SettingsDetailPane(
    focusedKey: String,
    activeUser: String
) {
    val title: String
    val description: String
    val status: String
    val backend: String

    when (focusedKey) {
        "internal_directory" -> {
            title = "Storage Directory"
            description = "Access the emulator's internal storage directory. Here you can manage cache, config files, shaders, and installed packages directly."
            status = "ACTIVE"
            backend = "LOCAL STORAGE"
        }
        "users" -> {
            title = "User Profiles"
            val username = com.zenithblue.sambas3.UserRepository.getUsername(activeUser)
            description = "Manage local user accounts, game saves, and save data directories. Current active user is $username."
            status = "ACTIVE"
            backend = "PROFILE SYSTEM"
        }
        "advanced_settings" -> {
            title = "Advanced Config"
            description = "Configure core emulation parameters, CPU instruction set compilers, system variables, and file system path mappings."
            status = "CONFIGURED"
            backend = "RPCSX SYSTEM"
        }
        "custom_driver" -> {
            title = "GPU Drivers"
            description = if (BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS) {
                "Select the Android system driver or offline Turnip packages included with Samba S3."
            } else {
                "Load custom graphics drivers (like Turnip or custom Vulkan/Adreno drivers) to optimize rendering performance, fix graphical glitches, and improve stability."
            }
            status = if (com.zenithblue.sambas3.RPCSX.instance.supportsCustomDriverLoading()) "SUPPORTED" else "UNSUPPORTED"
            backend = "VULKAN 1.3"
        }
        "onboarding" -> {
            title = stringResource(R.string.onboarding_replay_title)
            description = stringResource(R.string.onboarding_replay_description)
            status = stringResource(R.string.onboarding_ready)
            backend = stringResource(R.string.onboarding_setup_guide)
        }
        "controls" -> {
            title = "Controller Bindings"
            description = "Configure physical gamepad mappings, D-pad sensitivity, touch-screen overlays, haptic feedback, and input profiles."
            status = "CONNECTED"
            backend = "INPUT INTERFACE"
        }
        "share_logs" -> {
            title = "System Logs"
            description = "Export and share the emulator execution logs. Helpful for debugging crashes, verifying compatibility, and reporting bugs to the developers."
            status = "READY"
            backend = "TEXT/PLAIN"
        }
        "logs" -> {
            title = "Log Monitor"
            description = "Live streaming log viewer capturing RPCSX backend, kernel syscalls, Cell modules, Vulkan, GPU driver, and Android app logs in real-time. Logs are saved to separate files per category."
            status = "LIVE"
            backend = "LOGCAT"
        }
        "monitoring" -> {
            title = "Performance Monitor"
            description = "Configure the in-game FPS, frametime, RPCSX CPU, Android system, memory, thermal and battery overlay. Values unavailable on this device remain hidden."
            status = "OPTIONAL"
            backend = "COMPOSE / RPCSX"
        }
        "debug_controller" -> {
            title = "Debug Controller"
            description = "Agent ADB bridge for pad injection (DEBUG_PAD broadcasts) + coordinate 1632,873 calibration for Y5WWBMJVOZSK4HU8. Test controller without EULA timing, verify overlayPadData, and copy adb loops for per-game fixes."
            status = "READY"
            backend = "ADB/BROADCAST"
        }
        else -> {
            title = "SambaS3 Core"
            description = "Configure the heartbeat of your gaming experience. Adjust core frequency, cycle accuracy, and bios paths to optimize performance for seventh-generation console emulation."
            status = "OPTIMIZED"
            backend = "VULKAN 1.3"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.zenithblue.sambas3.RPCSXColors.surfaceOverlay.copy(alpha = 0.2f))
            .drawBehind {
                drawRect(
                    color = com.zenithblue.sambas3.RPCSXColors.surfaceOverlay,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = size,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                )
            }
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                color = com.zenithblue.sambas3.RPCSXColors.primary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 1.sp
            )

            Text(
                text = description,
                color = com.zenithblue.sambas3.RPCSXColors.textPrimary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .background(com.zenithblue.sambas3.RPCSXColors.surface)
                        .drawBehind {
                            drawLine(
                                color = com.zenithblue.sambas3.RPCSXColors.primary,
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end = androidx.compose.ui.geometry.Offset(0f, size.height),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "STATUS",
                            color = com.zenithblue.sambas3.RPCSXColors.textSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = status,
                            color = com.zenithblue.sambas3.RPCSXColors.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .background(com.zenithblue.sambas3.RPCSXColors.surface)
                        .drawBehind {
                            drawLine(
                                color = com.zenithblue.sambas3.RPCSXColors.primaryDim,
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end = androidx.compose.ui.geometry.Offset(0f, size.height),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BACKEND",
                            color = com.zenithblue.sambas3.RPCSXColors.textSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = backend,
                            color = com.zenithblue.sambas3.RPCSXColors.textPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
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
                .height(if (compact) 48.dp else 56.dp)
                .background(com.zenithblue.sambas3.RPCSXColors.background)
                .padding(horizontal = if (compact) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
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
                modifier = if (!compact) Modifier.padding(end = 8.dp) else Modifier
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                    contentDescription = null,
                    tint = com.zenithblue.sambas3.RPCSXColors.primary
                )
            }

            if (isSearching) {
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
                    Icon(
                        painter = painterResource(id = R.drawable.tune),
                        contentDescription = null,
                        tint = com.zenithblue.sambas3.RPCSXColors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = displayTitle.uppercase(),
                    color = com.zenithblue.sambas3.RPCSXColors.primary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 16.sp else 18.sp,
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
        // Only folder nodes on the left. Leaf settings (have "type") are NOT categories —
        // selecting them previously left an empty right pane ("dummy screen").
        val categories = remember(settings) {
            settings.keys().asSequence().filter { key ->
                isSettingsFolder(settings.optJSONObject(key))
            }.toList()
        }
        // Keep the user's category across canonical-tree refreshes after a
        // successful write. Only recover to the first category if the backend
        // no longer exposes the selected one.
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
                .background(com.zenithblue.sambas3.RPCSXColors.background),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                items(categories) { category ->
                    HomePreference(
                        title = category,
                        icon = {
                            Icon(
                                painter = painterResource(id = advancedSettingIconRes(category)),
                                contentDescription = null
                            )
                        },
                        description = "",
                        onClick = { selectedCategoryKey = category },
                        onFocusChanged = { if (it) selectedCategoryKey = category }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .padding(end = 16.dp, top = 16.dp, bottom = 16.dp)
                    .background(com.zenithblue.sambas3.RPCSXColors.surfaceOverlay.copy(alpha = 0.2f))
                    .drawBehind {
                        drawRect(
                            color = com.zenithblue.sambas3.RPCSXColors.surfaceOverlay,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                            size = size,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                        )
                    }
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
                    modifier = Modifier.fillMaxSize()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit,
    navigateTo: (path: String) -> Unit,
    settings: JSONObject,
    onRefresh: () -> Unit
) {
    val topBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val activeUser by remember { UserRepository.activeUser }
    var focusedKey by rememberSaveable { mutableStateOf("internal_directory") }
    var activeSettingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val advancedSettingsPathStack = remember { mutableStateListOf("") }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp > 600

    BackHandler(enabled = activeSettingKey != null) {
        activeSettingKey = null
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(com.zenithblue.sambas3.RPCSXColors.background)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = navigateBack,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                        contentDescription = null,
                        tint = com.zenithblue.sambas3.RPCSXColors.primary
                    )
                }
                Icon(
                    painter = painterResource(id = R.drawable.gamepad),
                    contentDescription = null,
                    tint = com.zenithblue.sambas3.RPCSXColors.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.settings).uppercase(),
                    color = com.zenithblue.sambas3.RPCSXColors.primary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "v" + com.zenithblue.sambas3.BuildConfig.VERSION_NAME,
                        color = com.zenithblue.sambas3.RPCSXColors.textSecondary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                    PulsingDot()
                }
            }
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
        val context = LocalContext.current
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

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(com.zenithblue.sambas3.RPCSXColors.background),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(if (isWideScreen) 1f else 3f)
                    .fillMaxHeight(),
            ) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item(
                    key = "internal_directory"
                ) {
                    HomePreference(
                        title = stringResource(R.string.view_internal_dir),
                        icon = { PreferenceIcon(icon = painterResource(R.drawable.ic_folder)) },
                        description = stringResource(R.string.view_internal_dir_description),
                        onClick = {
                            if (!FileUtil.launchInternalDir(context)) {
                                AlertDialogQueue.showDialog(
                                    context.getString(R.string.failed_to_view_internal_dir),
                                    context.getString(R.string.no_activity_to_handle_action)
                                )
                            }
                        },
                        onFocusChanged = { if (it) focusedKey = "internal_directory" }
                    )
                }

                item(
                    key = "users"
                ) {
                    HomePreference(
                        title = stringResource(R.string.users),
                        description = "${stringResource(R.string.active_user)}: ${UserRepository.getUsername(activeUser)}",
                        icon = {
                            PreferenceIcon(icon = painterResource(id = R.drawable.ic_person))
                        },
                        onClick = {
                            if (isWideScreen) activeSettingKey = "users"
                            else navigateTo("users")
                        },
                        onFocusChanged = { if (it) focusedKey = "users" }
                    )
                }

                item(key = "onboarding") {
                    HomePreference(
                        title = stringResource(R.string.onboarding_replay_title),
                        description = stringResource(R.string.onboarding_replay_description),
                        icon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                        onClick = { navigateTo(ONBOARDING_ROUTE) },
                        onFocusChanged = { if (it) focusedKey = "onboarding" },
                    )
                }

                item(key = "advanced_settings") {
                    HomePreference(
                        title = stringResource(R.string.advanced_settings),
                        icon = { Icon(painterResource(R.drawable.tune), null) },
                        description = stringResource(R.string.advanced_settings_description),
                        onClick = {
                            navigateTo("settings@@$")
                        },
                        onLongClick = {
                            AlertDialogQueue.showDialog(
                                title = context.getString(R.string.manage_settings),
                                confirmText = context.getString(R.string.export),
                                dismissText = context.getString(R.string.import_),
                                onDismiss = {
                                    configPicker.launch(arrayOf("*/*"))
                                },
                                onConfirm = {
                                    configExporter.launch("config.yml")
                                }
                            )
                        },
                        onFocusChanged = { if (it) focusedKey = "advanced_settings" }
                    )
                }

                item(
                    key = "custom_driver"
                ) {
                    HomePreference(
                        title = stringResource(R.string.custom_driver),
                        icon = { Icon(painterResource(R.drawable.memory), contentDescription = null) },
                        description = stringResource(R.string.custom_driver_description),
                        onClick = {
                            if (RPCSX.instance.supportsCustomDriverLoading()) {
                                if (isWideScreen) activeSettingKey = "custom_driver"
                                else navigateTo("drivers")
                            } else {
                                AlertDialogQueue.showDialog(
                                    title = context.getString(R.string.custom_driver_not_supported),
                                    message = context.getString(R.string.custom_driver_not_supported_description),
                                    confirmText = context.getString(R.string.close),
                                    dismissText = ""
                                )
                            }
                        },
                        onFocusChanged = { if (it) focusedKey = "custom_driver" }
                    )
                }

                item(key = "controls") {
                    HomePreference(
                        title = stringResource(R.string.controls),
                        icon = { Icon(painterResource(R.drawable.gamepad), null) },
                        description = stringResource(R.string.controls_description),
                        onClick = {
                            if (isWideScreen) activeSettingKey = "controls"
                            else navigateTo("controls")
                        },
                        onFocusChanged = { if (it) focusedKey = "controls" }
                    )
                }

                item(key = "share_logs") {
                    HomePreference(
                        title = stringResource(R.string.share_log),
                        icon = { Icon(painter = painterResource(id = R.drawable.ic_share), contentDescription = null) },
                        description = stringResource(R.string.share_log_description),
                        onClick = {
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
                        },
                        onFocusChanged = { if (it) focusedKey = "share_logs" }
                    )
                }

                item(key = "logs") {
                    HomePreference(
                        title = stringResource(R.string.log_monitor),
                        icon = { Icon(painterResource(R.drawable.ic_terminal), null) },
                        description = stringResource(R.string.log_monitor_description),
                        onClick = {
                            if (isWideScreen) activeSettingKey = "logs"
                            else navigateTo("logs")
                        },
                        onFocusChanged = { if (it) focusedKey = "logs" }
                    )
                }

                item(key = "monitoring") {
                    HomePreference(
                        title = "Performance Monitor",
                        icon = { Icon(painterResource(R.drawable.ic_video), null) },
                        description = "In-game FPS, CPU/GPU, RAM, thermal and power telemetry.",
                        onClick = {
                            if (isWideScreen) activeSettingKey = "monitoring" else navigateTo("monitoring")
                        },
                        onFocusChanged = { if (it) focusedKey = "monitoring" }
                    )
                }

                item(key = "debug_controller") {
                    HomePreference(
                        title = "Debug — Controller",
                        icon = { Icon(painterResource(R.drawable.gamepad), null) },
                        description = "Agent ADB bridge (DEBUG_PAD broadcasts) + tap calibration 1632,873. Test X/UP/Sticks without restarting game.",
                        onClick = {
                            if (isWideScreen) activeSettingKey = "debug_controller"
                            else navigateTo("debug_controller")
                        },
                        onFocusChanged = { if (it) focusedKey = "debug_controller" }
                    )
                }

                if (!BuildConfig.IS_PLAYSTORE_BUILD) {
                    item(key = "patches") {
                        HomePreference(
                            title = stringResource(R.string.patch_manager),
                            icon = { Icon(painterResource(R.drawable.tune), null) },
                            description = stringResource(R.string.patch_manager_description),
                            onClick = {
                                if (isWideScreen) activeSettingKey = "patches"
                                else navigateTo("patches")
                            },
                            onFocusChanged = { if (it) focusedKey = "patches" }
                        )
                    }
                }
            }

            if (isWideScreen) {
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .padding(end = 16.dp, top = 16.dp, bottom = 16.dp)
                        .background(com.zenithblue.sambas3.RPCSXColors.surfaceOverlay.copy(alpha = 0.2f))
                        .drawBehind {
                            drawRect(
                                color = com.zenithblue.sambas3.RPCSXColors.surfaceOverlay,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                                size = size,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                            )
                        }
                ) {
                    when (activeSettingKey) {
                        "users" -> {
                            UsersScreen(
                                navigateBack = { activeSettingKey = null },
                                isInSplitPane = true
                            )
                        }
                        "advanced_settings" -> {
                            val currentPath = advancedSettingsPathStack.lastOrNull() ?: ""
                            val currentObj = getNestedSettings(settings, currentPath)
                            AdvancedSettingsScreen(
                                navigateBack = {
                                    if (advancedSettingsPathStack.size > 1) {
                                        advancedSettingsPathStack.removeAt(advancedSettingsPathStack.lastIndex)
                                    } else {
                                        activeSettingKey = null
                                    }
                                },
                                navigateTo = { route ->
                                    if (isAdvancedSettingsRoute(route)) {
                                        advancedSettingsPathStack.add(
                                            normalizeAdvancedSettingsPath(route)
                                        )
                                    } else {
                                        navigateTo(route)
                                    }
                                },
                                settings = currentObj,
                                path = currentPath,
                                isInSplitPane = true,
                                onValueCommitted = { _, _ -> onRefresh() },
                                settingsSetter = RPCSX.instance::settingsSetGlobalAndVerify
                            )
                        }
                        "custom_driver" -> {
                            GpuDriversScreen(
                                navigateBack = { activeSettingKey = null },
                                isInSplitPane = true
                            )
                        }
                        "controls" -> {
                            ControllerSettingsScreen(
                                navigateBack = { activeSettingKey = null },
                                isInSplitPane = true,
                                onOpenTest = { device ->
                                    // The test route is pushed above Settings. Restore the
                                    // controller pane when it pops instead of falling back to
                                    // the default Storage Directory detail pane.
                                    focusedKey = "controls"
                                    activeSettingKey = "controls"
                                    navigateTo("controller_test/${android.net.Uri.encode(device.deviceKey)}")
                                },
                            )
                        }
                        "logs" -> {
                            LogMonitorScreen(
                                navigateBack = { activeSettingKey = null },
                                isInSplitPane = true
                            )
                        }
                        "monitoring" -> {
                            MonitoringSettingsScreen(
                                navigateBack = { activeSettingKey = null },
                                isInSplitPane = true
                            )
                        }
                        "debug_controller" -> {
                            com.zenithblue.sambas3.ui.debug.DebugControllerScreen(
                                navigateBack = { activeSettingKey = null }
                            )
                        }
                        "patches" -> {
                            if (!BuildConfig.IS_PLAYSTORE_BUILD) {
                                PatchManagerScreen(
                                    navigateBack = { activeSettingKey = null },
                                    isInSplitPane = true
                                )
                            } else {
                                SettingsDetailPane(focusedKey = focusedKey, activeUser = activeUser)
                            }
                        }
                        else -> {
                            SettingsDetailPane(focusedKey = focusedKey, activeUser = activeUser)
                        }
                    }
                }
            }
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
