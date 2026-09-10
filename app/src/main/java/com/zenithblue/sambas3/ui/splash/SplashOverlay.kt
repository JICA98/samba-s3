package com.zenithblue.sambas3.ui.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** Set once the home GamesScreen has loaded real content; releases the splash. */
object SplashGate {
    private val ready = java.util.concurrent.atomic.AtomicBoolean(false)
    fun homeReady() { ready.set(true) }
    internal fun isHomeReady(): Boolean = ready.get()
}


private val BgDark = Color(0xFF0A0D1A)
private val Gold = Color(0xFFC9A84C)
private val GoldBright = Color(0xFFF0C040)
private val Cream = Color(0xFFF0E8D0)
private val ChromaticRed = Color(0xFFFF2040)
private val ChromaticBlue = Color(0xFF2040FF)

// design/design_3.md: Press Start 2P — reserved for app logotype / boot splash. Never substitute.
private val PressStart2P = FontFamily(Font(R.font.press_start_2p))

/**
 * Minimal boot splash: logo + logotype on dark bg with soft gold glow,
 * staggered fade/scale-in animations, breathing glow, thin animated
 * progress line. Simple, no busy artwork.
 */
@Composable
fun SplashOverlay(
    modifier: Modifier = Modifier,
    minDurationMs: Long = 1200,
    fadeMs: Int = 300,
    onFinished: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(true) }
    var fading by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (fading) 0f else 1f,
        animationSpec = tween(fadeMs),
        label = "splashAlpha",
    )
    LaunchedEffect(Unit) {
        // Hold until home screen content is loaded (or hard cap), so the
        // fade reveals the real UI instead of an empty background.
        val deadline = System.currentTimeMillis() + 6000
        delay(minDurationMs)
        while (!SplashGate.isHomeReady() && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        fading = true
        delay(fadeMs.toLong())
        visible = false
        onFinished()
    }
    if (visible) {
        Box(
            modifier
                .fillMaxSize()
                .alpha(alpha)
                .background(BgDark),
        ) { SplashContent() }
    }
}

@Composable
private fun SplashContent() {
    // Breathing glow behind logo
    val glowTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by glowTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "glowAlpha",
    )
    // Animated loading line 0..1
    val lineTransition = rememberInfiniteTransition(label = "line")
    val linePhase by lineTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "linePhase",
    )

    // Staggered entrance
    var logoIn by remember { mutableStateOf(false) }
    var textIn by remember { mutableStateOf(false) }
    var taglineIn by remember { mutableStateOf(false) }
    val logoAlpha by animateFloatAsState(if (logoIn) 1f else 0f, tween(450), label = "logoA")
    val logoScale by animateFloatAsState(if (logoIn) 1f else 0.88f, tween(450), label = "logoS")
    val textAlpha by animateFloatAsState(if (textIn) 1f else 0f, tween(400), label = "textA")
    val taglineAlpha by animateFloatAsState(if (taglineIn) 1f else 0f, tween(400), label = "tagA")

    LaunchedEffect(Unit) {
        logoIn = true
        delay(180)
        textIn = true
        delay(220)
        taglineIn = true
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Home default wallpaper + dark cinematic tint + vignette (mirrors GamesScreen bg)
        Image(
            painter = painterResource(R.drawable.default_wallpaper),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.85f),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Black.copy(alpha = 0.15f),
                            Color.Black.copy(alpha = 0.35f),
                        )
                    )
                ),
        )
        // Breathing gold glow behind logo
        Canvas(Modifier.size(320.dp).alpha(0.6f)) {
            drawCircle(
                Brush.radialGradient(
                    listOf(GoldBright.copy(alpha = glowAlpha), Color.Transparent),
                ),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.splash_logo),
                contentDescription = "SambaS3",
                modifier = Modifier.size(112.dp).alpha(logoAlpha).graphicsLayer {
                    scaleX = logoScale
                    scaleY = logoScale
                },
            )
            Spacer(Modifier.height(24.dp))
            // Chromatic aberration: blue left channel, red right channel, cream core
            Box(Modifier.alpha(textAlpha)) {
                Text(text = "SambaS3", style = logotypeStyle(ChromaticBlue), modifier = Modifier.offset(x = -3.dp))
                Text(text = "SambaS3", style = logotypeStyle(ChromaticRed), modifier = Modifier.offset(x = 3.dp))
                Text(text = "SambaS3", style = logotypeStyle(Cream))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "PS3 EMULATION",
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = PressStart2P,
                    fontSize = 12.sp,
                    letterSpacing = 0.4.sp,
                    color = Gold,
                ),
                modifier = Modifier.alpha(taglineAlpha),
            )
            Spacer(Modifier.height(28.dp))
            Canvas(Modifier.size(28.dp).alpha(taglineAlpha)) {
                val stroke = 3f.toDp().toPx()
                drawArc(
                    color = Gold.copy(alpha = 0.20f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = GoldBright,
                    startAngle = -90f + linePhase * 360f,
                    sweepAngle = 70f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

private fun logotypeStyle(color: Color) = androidx.compose.ui.text.TextStyle(
    fontFamily = PressStart2P,
    fontSize = 22.sp,
    lineHeight = 22.sp * 1.6f,
    letterSpacing = 0.04.sp,
    color = color,
)
