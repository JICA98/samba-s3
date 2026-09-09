package com.zenithblue.sambas3.ui.drivers

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.utils.AdrenoGpuInfo
import com.zenithblue.sambas3.utils.GpuDriverMetadata
import kotlin.math.abs

/**
 * Shared driver-page branding. Uses the same transparent PNG logo assets as the
 * onboarding pages (hw_gpu_adreno/hw_gpu_mali/hw_driver_mesa/hw_driver_vulkan).
 * Vortex and Wrapper have no bundled art yet — small vector marks stand in.
 */
data class ComingSoonDriver(val title: String, val subtitle: String, val logoRes: Int)

val comingSoonDrivers = listOf(
    ComingSoonDriver(
        title = "Vortex",
        subtitle = "High-performance open driver — coming soon for Snapdragon and Mali.",
        logoRes = R.drawable.ic_brand_vortex,
    ),
    ComingSoonDriver(
        title = "Wrapper Driver",
        subtitle = "Compatibility wrapper driver — coming soon for Snapdragon and Mali.",
        logoRes = R.drawable.ic_brand_wrapper,
    ),
)

/** Pure: vendor chip for the built-in system driver. */
fun systemVendorChip(info: AdrenoGpuInfo): String = when {
    info.isAdreno -> "QUALCOMM ADRENO"
    info.rawModel?.contains("mali", ignoreCase = true) == true -> "ARM MALI"
    else -> "SYSTEM"
}

/** Pure: true when running on a Mali GPU. */
fun isMaliDevice(info: AdrenoGpuInfo): Boolean =
    !info.isAdreno && info.rawModel?.contains("mali", ignoreCase = true) == true

/** Pure: Mesa-based driver (Turnip) gets the MESA + VULKAN chips. */
fun isMesaDriver(metadata: GpuDriverMetadata): Boolean =
    metadata.isBundled ||
        metadata.vendor.equals("Mesa", ignoreCase = true) ||
        metadata.name.contains("turnip", ignoreCase = true) ||
        metadata.bundledId?.contains("turnip", ignoreCase = true) == true

/** Pure: brand chips for a driver card. First chip is the vendor, last is VULKAN. */
fun brandChipsFor(metadata: GpuDriverMetadata, systemVendor: String): List<String> =
    if (metadata.name == "Default") {
        listOf(systemVendor, "VULKAN")
    } else if (isMesaDriver(metadata)) {
        listOf("MESA", "VULKAN")
    } else {
        buildList {
            metadata.vendor.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
            add("VULKAN")
        }
    }

/** Pure: logo drawable for a device GPU. */
fun vendorLogoRes(info: AdrenoGpuInfo): Int = when {
    info.isAdreno -> R.drawable.hw_gpu_adreno
    isMaliDevice(info) -> R.drawable.hw_gpu_mali
    else -> R.drawable.hw_gpu_fallback
}

/** Pure: logo drawable for a brand chip. [vendorLogo] is the device vendor mark. */
fun brandLogoResFor(chip: String, vendorLogo: Int): Int = when {
    chip.equals("VULKAN", ignoreCase = true) -> R.drawable.hw_driver_vulkan
    chip.equals("MESA", ignoreCase = true) -> R.drawable.hw_driver_mesa
    chip.contains("ADRENO", ignoreCase = true) || chip.contains("MALI", ignoreCase = true) -> vendorLogo
    else -> R.drawable.hw_gpu_fallback
}

data class BrandLogo(val iconRes: Int, val label: String)

fun brandLogosFor(metadata: GpuDriverMetadata, systemVendor: String, vendorLogo: Int): List<BrandLogo> =
    brandChipsFor(metadata, systemVendor).map { BrandLogo(brandLogoResFor(it, vendorLogo), it) }

@Composable
fun BrandLogoMark(logo: BrandLogo, contentColor: Color, size: Dp = 20.dp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Image(
            painter = painterResource(logo.iconRes),
            contentDescription = logo.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
        )
        Text(
            text = logo.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
fun DriverBrandRow(metadata: GpuDriverMetadata, systemVendor: String, vendorLogo: Int) {
    val logos = remember(metadata, systemVendor, vendorLogo) {
        brandLogosFor(metadata, systemVendor, vendorLogo)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        logos.forEachIndexed { index, logo ->
            BrandLogoMark(
                logo = logo,
                contentColor = if (index == 0) RPCSXColors.textPrimary else RPCSXColors.textSecondary,
            )
        }
    }
}

@Composable
fun BrandChip(label: String, primary: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (primary) RPCSXColors.primary.copy(alpha = 0.18f)
        else RPCSXColors.textSecondary.copy(alpha = 0.12f),
        border = BorderStroke(
            1.dp,
            if (primary) RPCSXColors.primary.copy(alpha = 0.7f)
            else RPCSXColors.textSecondary.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = if (primary) RPCSXColors.primary else RPCSXColors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
fun ComingSoonDriverCard(title: String, subtitle: String, logoRes: Int) {
    var isFocused by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface),
        border = if (isFocused) {
            BorderStroke(2.dp, RPCSXColors.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(RPCSXColors.textSecondary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(logoRes),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = RPCSXColors.textPrimary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = RPCSXColors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            BrandChip(label = "COMING SOON")
        }
    }
}

@Composable
fun ComingSoonDriversSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "COMING SOON",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = RPCSXColors.primary,
        )
        comingSoonDrivers.forEach { driver ->
            ComingSoonDriverCard(
                title = driver.title,
                subtitle = driver.subtitle,
                logoRes = driver.logoRes,
            )
        }
    }
}

/** Activates a focused element with gamepad A / center d-pad / Enter. */
fun Modifier.gamepadActivate(onClick: () -> Unit): Modifier = this.onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown &&
        event.nativeKeyEvent.keyCode.let {
            it == KeyEvent.KEYCODE_BUTTON_A || it == KeyEvent.KEYCODE_DPAD_CENTER || it == KeyEvent.KEYCODE_ENTER
        }
    ) {
        onClick()
        true
    } else {
        false
    }
}

/**
 * Left-stick / analog-hat D-pad navigation for Compose focus (dpad keys are
 * already handled by Compose's built-in focus search). Mirrors the threshold +
 * hold-repeat behavior used by SettingsScreen.
 */
@Composable
fun DriverStickFocusNav() {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    DisposableEffect(view) {
        var armedX = false
        var armedY = false
        var holdStartX = 0L
        var holdStartY = 0L
        var lastStepX = 0L
        var lastStepY = 0L

        fun handleAxis(
            value: Float,
            negative: FocusDirection,
            positive: FocusDirection,
            isX: Boolean,
            now: Long,
        ): Boolean {
            val armed = if (isX) armedX else armedY
            val holdStart = if (isX) holdStartX else holdStartY
            val lastStep = if (isX) lastStepX else lastStepY
            var moved = false
            when {
                value < -0.55f -> {
                    if (armed) {
                        if (isX) { armedX = false; holdStartX = now; lastStepX = now } else { armedY = false; holdStartY = now; lastStepY = now }
                        focusManager.moveFocus(negative)
                        moved = true
                    } else if (now - holdStart > 350L && now - lastStep > 180L) {
                        if (isX) lastStepX = now else lastStepY = now
                        focusManager.moveFocus(negative)
                        moved = true
                    }
                }
                value > 0.55f -> {
                    if (armed) {
                        if (isX) { armedX = false; holdStartX = now; lastStepX = now } else { armedY = false; holdStartY = now; lastStepY = now }
                        focusManager.moveFocus(positive)
                        moved = true
                    } else if (now - holdStart > 350L && now - lastStep > 180L) {
                        if (isX) lastStepX = now else lastStepY = now
                        focusManager.moveFocus(positive)
                        moved = true
                    }
                }
                abs(value) < 0.20f -> {
                    if (isX) armedX = true else armedY = true
                }
            }
            return moved
        }

        val listener = View.OnGenericMotionListener { _, event ->
            val source = event.source
            if (source and InputDevice.SOURCE_GAMEPAD == 0 && source and InputDevice.SOURCE_JOYSTICK == 0) {
                return@OnGenericMotionListener false
            }
            val rawX = event.getAxisValue(MotionEvent.AXIS_X)
            val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
            val x = if (abs(rawX) > 0.45f) rawX else event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = if (abs(rawY) > 0.45f) rawY else event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val now = SystemClock.uptimeMillis()
            // Joystick Y is inverted: negative = up.
            var moved = handleAxis(x, FocusDirection.Left, FocusDirection.Right, isX = true, now = now)
            if (handleAxis(y, FocusDirection.Up, FocusDirection.Down, isX = false, now = now)) moved = true
            moved
        }
        view.setOnGenericMotionListener(listener)
        onDispose { view.setOnGenericMotionListener(null) }
    }
}
