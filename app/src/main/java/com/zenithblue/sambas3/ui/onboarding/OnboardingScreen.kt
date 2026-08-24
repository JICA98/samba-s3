package com.zenithblue.sambas3.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.AppTypography
import com.zenithblue.sambas3.FirmwareStatus
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.utils.GameFolderMatch
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    entry: OnboardingEntry,
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
    onFinished: () -> Unit,
    onExitAtFirstPage: (() -> Unit)?,
) {
    var savedPage by rememberSaveable { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(
        initialPage = savedPage.coerceIn(0, ONBOARDING_PAGE_COUNT - 1),
        pageCount = { ONBOARDING_PAGE_COUNT },
    )
    val scope = rememberCoroutineScope()
    val primaryFocusRequester = remember { FocusRequester() }
    val currentPage = pagerState.currentPage
    val isLastPage = currentPage == ONBOARDING_PAGE_COUNT - 1
    val pageTitles = listOf(
        R.string.onboarding_page_welcome,
        R.string.onboarding_page_permissions,
        R.string.onboarding_page_device,
        R.string.onboarding_page_firmware,
        R.string.onboarding_page_driver,
        R.string.onboarding_page_games,
        R.string.onboarding_page_complete,
    )

    fun goToPage(page: Int) {
        val bounded = page.coerceIn(0, ONBOARDING_PAGE_COUNT - 1)
        scope.launch { pagerState.animateScrollToPage(bounded) }
    }

    fun goBack() {
        if (currentPage > 0) {
            goToPage(currentPage - 1)
        } else if (entry == OnboardingEntry.Replay) {
            onExitAtFirstPage?.invoke()
        }
    }

    LaunchedEffect(currentPage) {
        savedPage = currentPage
        primaryFocusRequester.requestFocus()
    }

    BackHandler {
        goBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(
                Brush.radialGradient(
                    colors = listOf(RPCSXColors.surfaceElevated, RPCSXColors.background),
                )
            )
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (currentPage > 0) goToPage(currentPage - 1)
                        true
                    }
                    Key.DirectionRight -> {
                        if (!isLastPage) goToPage(currentPage + 1)
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_sambas3_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = AppTypography.displayLarge.copy(letterSpacing = 3.sp),
                    color = RPCSXColors.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.onboarding_page_counter, currentPage + 1, ONBOARDING_PAGE_COUNT),
                    style = AppTypography.labelMedium,
                    color = RPCSXColors.textSecondary,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(ONBOARDING_PAGE_COUNT) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (index == currentPage) 10.dp else 7.dp)
                            .background(
                                if (index == currentPage) RPCSXColors.primary else RPCSXColors.textDisabled,
                                CircleShape,
                            )
                    )
                }
            }

            Text(
                text = stringResource(pageTitles[currentPage]),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                style = AppTypography.headlineMedium,
                color = RPCSXColors.textPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                userScrollEnabled = true,
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OnboardingPageContent(
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = ::goBack,
                    enabled = currentPage > 0 || entry == OnboardingEntry.Replay,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RPCSXColors.surfaceElevated,
                        contentColor = RPCSXColors.textPrimary,
                        disabledContainerColor = RPCSXColors.surface,
                        disabledContentColor = RPCSXColors.textDisabled,
                    ),
                ) {
                    Icon(painterResource(R.drawable.circle), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.onboarding_back))
                }
                Button(
                    onClick = { if (isLastPage) onFinished() else goToPage(currentPage + 1) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(primaryFocusRequester),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RPCSXColors.primary,
                        contentColor = RPCSXColors.onPrimary,
                    ),
                ) {
                    Icon(painterResource(R.drawable.cross), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (isLastPage) R.string.onboarding_finish else R.string.onboarding_continue
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.cross), null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(if (isLastPage) R.string.onboarding_hint_finish else R.string.onboarding_hint_continue),
                    style = MaterialTheme.typography.labelSmall,
                    color = RPCSXColors.textSecondary,
                )
                Spacer(Modifier.width(18.dp))
                Image(painterResource(R.drawable.circle), null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.onboarding_hint_back),
                    style = MaterialTheme.typography.labelSmall,
                    color = RPCSXColors.textSecondary,
                )
            }
        }
    }
}
