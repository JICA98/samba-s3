package com.zenithblue.sambas3.ui.onboarding

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
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
import com.zenithblue.sambas3.iso.DirectIsoManager
import com.zenithblue.sambas3.utils.GameFolderMatch
import com.zenithblue.sambas3.utils.ScannedFolder
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch

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
    isoImportResult: DirectIsoManager.IsoFolderImportResult?,
    scannedFolders: List<ScannedFolder> = emptyList(),
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
    val rootFocusRequester = remember { FocusRequester() }
    val continueFocusRequester = remember { FocusRequester() }
    val currentPage = pagerState.currentPage
    val isLastPage = currentPage == ONBOARDING_PAGE_COUNT - 1

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
    }

    LaunchedEffect(Unit) {
        rootFocusRequester.requestFocus()
    }

    BackHandler {
        goBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                when (code) {
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        if (currentPage > 0) goToPage(currentPage - 1)
                        true
                    }
                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        if (!isLastPage) goToPage(currentPage + 1)
                        true
                    }
                    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                        goBack()
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                when (code) {
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (isLastPage) onFinished() else goToPage(currentPage + 1)
                        true
                    }
                    else -> false
                }
            },
    ) {
        Image(
            painter = painterResource(R.drawable.default_wallpaper),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.85f),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.45f),
                            Color.Black.copy(alpha = 0.22f),
                            Color.Black.copy(alpha = 0.55f),
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_sambas3_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = AppTypography.displayLarge.copy(fontSize = 16.sp, letterSpacing = 2.sp),
                    color = RPCSXColors.primary,
                )

                Spacer(Modifier.weight(1f))

                // Dots indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(ONBOARDING_PAGE_COUNT) { index ->
                        val isSelected = index == currentPage
                        val dotWidth by animateDpAsState(if (isSelected) 18.dp else 6.dp, label = "dotWidth")
                        val dotColor by animateColorAsState(
                            if (isSelected) RPCSXColors.primary else RPCSXColors.textDisabled.copy(alpha = 0.4f),
                            label = "dotColor"
                        )
                        Box(
                            modifier = Modifier
                                .size(width = dotWidth, height = 6.dp)
                                .background(dotColor, CircleShape)
                                .clickable { goToPage(index) }
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = RPCSXColors.surfaceElevated.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, Color(0x2230363D)),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_page_counter, currentPage + 1, ONBOARDING_PAGE_COUNT),
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = RPCSXColors.textSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // Main Content Area
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                userScrollEnabled = true,
            ) { page ->
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
                    isoImportResult = isoImportResult,
                    scannedFolders = scannedFolders,
                    runtimeAvailable = runtimeAvailable,
                    firmwareActionEnabled = firmwareActionEnabled,
                    gameFolderActionEnabled = gameFolderActionEnabled,
                    onInstallFirmware = onInstallFirmware,
                    onSelectGameFolder = onSelectGameFolder,
                    onRequestNotifications = onRequestNotifications,
                    onSelectDriver = onSelectDriver,
                )
            }

            // Bottom Bar: Controller navigation hints on left, Action buttons on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Controller Navigation Hints
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControllerHintDual(
                        iconLeft = R.drawable.l1,
                        iconRight = R.drawable.r1,
                        label = "Page",
                    )
                }

                // Action Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentPage > 0 || entry == OnboardingEntry.Replay) {
                        var isBackFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = ::goBack,
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x20FFFFFF),
                            border = BorderStroke(
                                1.dp,
                                if (isBackFocused) RPCSXColors.primary else Color(0x38FFFFFF),
                            ),
                            modifier = Modifier
                                .height(38.dp)
                                .onFocusChanged { isBackFocused = it.isFocused }
                                .gamepadClickable(::goBack),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.circle),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = stringResource(R.string.onboarding_back),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                            }
                        }
                    }

                    var isContinueFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { if (isLastPage) onFinished() else goToPage(currentPage + 1) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isContinueFocused) Color(0xFFFFCC00) else Color(0xFFFFB800),
                        border = if (isContinueFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0x60FFFFFF)),
                        modifier = Modifier
                            .height(38.dp)
                            .focusRequester(continueFocusRequester)
                            .onFocusChanged { isContinueFocused = it.isFocused }
                            .gamepadClickable {
                                if (isLastPage) onFinished() else goToPage(currentPage + 1)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.cross),
                                contentDescription = null,
                                tint = Color(0xFF0D1117),
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = stringResource(
                                    if (isLastPage) R.string.onboarding_finish else R.string.onboarding_continue
                                ),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = Color(0xFF0D1117),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControllerHint(
    iconRes: Int,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = AppTypography.labelSmall.copy(fontSize = 10.sp),
            color = RPCSXColors.textSecondary,
        )
    }
}

@Composable
private fun ControllerHintDual(
    iconLeft: Int,
    iconRight: Int,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Image(
            painter = painterResource(iconLeft),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "/",
            style = AppTypography.labelSmall.copy(fontSize = 10.sp),
            color = RPCSXColors.textSecondary,
        )
        Image(
            painter = painterResource(iconRight),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = label,
            style = AppTypography.labelSmall.copy(fontSize = 10.sp),
            color = RPCSXColors.textSecondary,
        )
    }
}
