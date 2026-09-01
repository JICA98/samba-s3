package com.zenithblue.sambas3.ui.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import com.zenithblue.sambas3.BuildConfig
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.FirmwareRepository
import com.zenithblue.sambas3.PrecompilerService
import com.zenithblue.sambas3.PrecompilerServiceAction
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.UserRepository
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.overlay.OverlayEditActivity
import com.zenithblue.sambas3.ui.drivers.GpuDriversScreen
import com.zenithblue.sambas3.ui.games.GamesScreen
import com.zenithblue.sambas3.ui.settings.ADVANCED_SETTINGS_ROUTE
import com.zenithblue.sambas3.ui.settings.AdvancedSettingsScreen
import com.zenithblue.sambas3.ui.settings.LogMonitorScreen
import com.zenithblue.sambas3.ui.settings.PatchManagerScreen
import com.zenithblue.sambas3.ui.settings.ControllerSettings
import com.zenithblue.sambas3.ui.settings.SettingsScreen
import com.zenithblue.sambas3.ui.debug.DebugControllerScreen
import com.zenithblue.sambas3.ui.monitoring.MonitoringSettingsScreen
import com.zenithblue.sambas3.ui.controller.ControllerSettingsScreen
import com.zenithblue.sambas3.ui.controller.ControllerTestScreen
import com.zenithblue.sambas3.ui.settings.advancedSettingsRoute
import com.zenithblue.sambas3.ui.settings.decodeAdvancedSettingsPath
import com.zenithblue.sambas3.ui.settings.getNestedSettings
import com.zenithblue.sambas3.ui.settings.isAdvancedSettingsRoute
import com.zenithblue.sambas3.ui.settings.normalizeAdvancedSettingsPath
import com.zenithblue.sambas3.ui.user.UsersScreen
import com.zenithblue.sambas3.ui.onboarding.ONBOARDING_ROUTE
import com.zenithblue.sambas3.ui.onboarding.OnboardingDestination
import com.zenithblue.sambas3.ui.onboarding.OnboardingEntry
import com.zenithblue.sambas3.ui.onboarding.OnboardingPrefs
import com.zenithblue.sambas3.utils.FileUtil
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.json.JSONObject

@Preview
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppNavHost(initialRoute: String? = null) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var needsFirstRunOnboarding by remember {
        mutableStateOf(!OnboardingPrefs.isCompleted())
    }
    val rpcsxLibrary by remember { RPCSX.activeLibrary }

    val navigateTo: (String) -> Unit = { route ->
        // Advanced settings keys can contain '/' (e.g. "Input/Output"). Encode into a
        // single path segment instead of registering fragile dynamic routes.
        val dest = if (isAdvancedSettingsRoute(route) && !route.startsWith("advanced_settings/")) {
            advancedSettingsRoute(normalizeAdvancedSettingsPath(route))
        } else {
            route
        }
        navController.navigate(dest) {
            launchSingleTop = true
            restoreState = true
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    AlertDialogQueue.AlertDialog()

    if (needsFirstRunOnboarding) {
        OnboardingDestination(
            entry = OnboardingEntry.FirstRun,
            onFinished = {
                OnboardingPrefs.markCompleted()
                needsFirstRunOnboarding = false
            },
            onExitAtFirstPage = null,
        )
        return
    }

    if (rpcsxLibrary == null) {
        GamesDestination(
            navigateToSettings = { },
            navigateToDrivers = { },
            navigateToPatches = { },
            navigateToLogs = { },
            drawerState
        )

        return
    }

    val settings = remember { mutableStateOf(JSONObject(RPCSX.instance.settingsGet(""))) }
    val refreshSettings: () -> Unit = {
        settings.value = JSONObject(RPCSX.instance.settingsGet(""))
    }

    NavHost(
        navController = navController,
        startDestination = initialRoute ?: "games"
    ) {
        composable(
            route = "games"
        ) {
            GamesDestination(
                navigateToSettings = { navigateTo("settings") },
                navigateToDrivers = { navigateTo("drivers") },
                navigateToPatches = { navigateTo("patches") },
                navigateToLogs = { navigateTo("logs") },
                drawerState
            )
        }

        composable(
            route = "users"
        ) {
            UsersScreen(navigateBack = navController::navigateUp)
        }

        composable(
            route = ADVANCED_SETTINGS_ROUTE,
            arguments = listOf(
                navArgument("encodedPath") {
                    type = NavType.StringType
                    defaultValue = "_"
                }
            )
        ) { entry ->
            val nestedPath = decodeAdvancedSettingsPath(entry.arguments?.getString("encodedPath"))
            val node = remember(settings.value, nestedPath) {
                getNestedSettings(settings.value, nestedPath)
            }
            AdvancedSettingsScreen(
                navigateBack = navController::navigateUp,
                navigateTo = navigateTo,
                settings = node,
                path = nestedPath
            )
        }

        composable(
            route = "settings"
        ) {
            SettingsScreen(
                navigateBack = navController::navigateUp,
                navigateTo = navigateTo,
                onRefresh = refreshSettings
            )
        }

        composable(
            route = ONBOARDING_ROUTE,
        ) {
            OnboardingDestination(
                entry = OnboardingEntry.Replay,
                onFinished = {
                    if (!navController.navigateUp()) {
                        navController.navigate("settings") { launchSingleTop = true }
                    }
                },
                onExitAtFirstPage = {
                    if (!navController.navigateUp()) {
                        navController.navigate("settings") { launchSingleTop = true }
                    }
                },
            )
        }

        composable(
            route = "controls"
        ) {
            ControllerSettingsScreen(
                navigateBack = navController::navigateUp,
                onOpenTest = { device -> navigateTo("controller_test/${Uri.encode(device.deviceKey)}") },
            )
        }

        composable(
            route = "controller_test/{deviceKey}",
            arguments = listOf(navArgument("deviceKey") { type = NavType.StringType }),
        ) { entry ->
            ControllerTestScreen(
                deviceKey = Uri.decode(entry.arguments?.getString("deviceKey").orEmpty()),
                navigateBack = navController::navigateUp,
            )
        }

        composable(
            route = "drivers"
        ) {
            GpuDriversScreen(
                navigateBack = navController::navigateUp
            )
        }

        composable(
            route = "logs"
        ) {
            LogMonitorScreen(
                navigateBack = navController::navigateUp
            )
        }

        composable(route = "debug_controller") {
            DebugControllerScreen(navigateBack = navController::navigateUp)
        }

        composable(route = "monitoring") {
            MonitoringSettingsScreen(navigateBack = navController::navigateUp)
        }

        if (!BuildConfig.IS_PLAYSTORE_BUILD) {
            composable(
                route = "patches"
            ) {
                PatchManagerScreen(
                    navigateBack = navController::navigateUp
                )
            }
        }
    }
}

@Composable
fun GamesDestination(
    navigateToSettings: () -> Unit,
    navigateToDrivers: () -> Unit,
    navigateToPatches: () -> Unit,
    navigateToLogs: () -> Unit,
    drawerState: androidx.compose.material3.DrawerState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val rpcsxLibrary by remember { RPCSX.activeLibrary }

    if (rpcsxLibrary == null) {
        GamesScreen()
        return
    }

    LaunchedEffect(Unit) {
        UserRepository.load()
    }

    val installPkgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            if (uri != null) PrecompilerService.start(
                context,
                PrecompilerServiceAction.Install,
                uri
            )
        }
    )

    val installFwLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            if (uri != null) PrecompilerService.start(
                context,
                PrecompilerServiceAction.InstallFirmware,
                uri
            )
        }
    )

    val gameFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            uri?.let {
                // TODO: FileUtil.saveGameFolderUri(prefs, it)
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
                FileUtil.installPackages(context, it)
            }
        }
    )

    GamesScreen(
        installPkgLauncher = installPkgLauncher,
        gameFolderPickerLauncher = gameFolderPickerLauncher,
        installFwLauncher = installFwLauncher,
        navigateToSettings = navigateToSettings,
        navigateToDrivers = navigateToDrivers,
        navigateToPatches = navigateToPatches,
        navigateToLogs = navigateToLogs,
        emulatorState = RPCSX.state,
        emulatorActiveGame = RPCSX.activeGame
    )
}
