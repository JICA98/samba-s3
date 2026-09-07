package com.zenithblue.sambas3.ui.crash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.crash.HomeRecoveryState
import com.zenithblue.sambas3.logging.LogSessionStore
import com.zenithblue.sambas3.ui.games.preview.GamePreviewModel
import com.zenithblue.sambas3.ui.games.preview.GamePreviewRepository
import java.io.File

@Composable
fun CrashRecoveryCard(
    state: HomeRecoveryState,
    onContinueSave: () -> Unit,
    onRetry: () -> Unit,
    onPlayFresh: () -> Unit,
    onChooseSave: () -> Unit,
    onDetails: () -> Unit,
    onViewLogs: () -> Unit,
    onOpenAllCrashLogs: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val session = when (state) {
        is HomeRecoveryState.ConfirmedCrash -> state.session
        is HomeRecoveryState.Interrupted -> state.session
        is HomeRecoveryState.ActionFailed -> state.session
        else -> null
    }
    val logSession = remember(session?.sessionId) {
        session?.sessionId?.let { LogSessionStore.read(context, it) }
    }
    val gamePath = session?.gamePath ?: (state as? HomeRecoveryState.LoadFailure)?.gamePath
    val libraryGame = remember(gamePath) { gamePath?.let { GameRepository.find(it) } }
    val rawTitle = logSession?.gameTitleSnapshot
        ?: session?.gameName
        ?: libraryGame?.info?.name?.value
        ?: gamePath?.substringAfterLast('/')
        ?: "Game"
    val titleId = logSession?.titleId ?: session?.titleId
    val displayTitle = if (rawTitle.isNotBlank()) rawTitle else titleId ?: "Game"
    val showId = !titleId.isNullOrBlank() && !titleId.equals(displayTitle, ignoreCase = true)

    val iconPath = logSession?.gameIconSnapshotPath
        ?: libraryGame?.info?.iconPath?.value
    val preview = remember(iconPath) { GamePreviewRepository.resolveInstalledPreview(iconPath) }
    val coilModel: Any? = when (preview) {
        is GamePreviewModel.LocalFile -> preview.file
        is GamePreviewModel.ContentUri -> preview.uri
        GamePreviewModel.None -> iconPath?.takeIf { File(it).isFile }?.let(::File)
    }
    val isLoadFailure = state is HomeRecoveryState.LoadFailure
    val isRunning = state is HomeRecoveryState.ActionRunning
    val confirmed = state is HomeRecoveryState.ConfirmedCrash
    val report = when (state) {
        is HomeRecoveryState.ConfirmedCrash -> state.report
        is HomeRecoveryState.Interrupted -> state.report
        is HomeRecoveryState.LoadFailure -> state.report
        else -> null
    }
    val message = when (state) {
        is HomeRecoveryState.ConfirmedCrash -> "The emulator reported a fatal error. Likely cause: ${state.report.cause}."
        is HomeRecoveryState.Interrupted -> state.message
        is HomeRecoveryState.LoadFailure -> "Saved slot could not be restored: ${state.reason}"
        is HomeRecoveryState.ActionFailed -> state.message
        is HomeRecoveryState.ActionRunning -> "Preparing emulator..."
        HomeRecoveryState.None -> ""
    }
    val diagnosticSessionId = report?.sessionId ?: session?.sessionId ?: (state as? HomeRecoveryState.LoadFailure)?.sessionId
    val logHint = when {
        report == null && diagnosticSessionId != null -> "Logs not yet analyzed"
        report == null -> "No report object"
        report.sources.isEmpty() -> "No log files"
        else -> "${report.sources.size} log source${if (report.sources.size == 1) "" else "s"}"
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (confirmed || isLoadFailure) RPCSXColors.errorColor.copy(alpha = 0.65f)
            else Color(0x35FFFFFF)
        ),
        shadowElevation = 12.dp,
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth(0.85f)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (coilModel != null) {
                AsyncImage(
                    model = coilModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .scale(1.25f)
                        .blur(radius = 20.dp)
                        .alpha(0.30f),
                )
            }
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .blur(45.dp)
                    .background(
                        if (confirmed || isLoadFailure) RPCSXColors.errorColor.copy(alpha = 0.20f)
                        else RPCSXColors.primary.copy(alpha = 0.18f),
                        CircleShape,
                    )
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xF2101726),
                                Color(0xF80B0E17),
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Row 1: Icon + Title info + Status badge + Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(RPCSXColors.surfaceOverlay),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (coilModel != null) {
                                AsyncImage(
                                    model = coilModel,
                                    contentDescription = displayTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text("!", color = RPCSXColors.errorColor, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    if (isLoadFailure) "LOAD FAILED" else if (confirmed) "CRASHED" else "STOPPED UNEXPECTEDLY",
                                    color = if (confirmed || isLoadFailure) RPCSXColors.errorColor else RPCSXColors.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "·",
                                    color = RPCSXColors.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    displayTitle.uppercase() + if (showId) " ($titleId)" else "",
                                    color = RPCSXColors.textPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                "$message  ·  $logHint",
                                color = RPCSXColors.textSecondary,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Dismiss",
                            tint = RPCSXColors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Row 2: Action buttons
                if (isRunning) {
                    Text("Working...", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isLoadFailure) {
                            Button(
                                onClick = onRetry,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFB800),
                                    contentColor = Color(0xFF0D1117),
                                ),
                            ) { Text("RETRY SAVE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)) }
                            OutlinedButton(
                                onClick = onPlayFresh,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0x35FFFFFF)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            ) { Text("PLAY FRESH", style = MaterialTheme.typography.labelSmall) }
                        } else {
                            Button(
                                onClick = onContinueSave,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFB800),
                                    contentColor = Color(0xFF0D1117),
                                ),
                            ) { Text("CONTINUE SAVE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)) }
                            OutlinedButton(
                                onClick = onRetry,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0x35FFFFFF)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            ) { Text("RETRY", style = MaterialTheme.typography.labelSmall) }
                        }
                        OutlinedButton(
                            onClick = onDetails,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Color(0x35FFFFFF)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) { Text("DETAILS", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            onClick = onViewLogs,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Color(0x35FFFFFF)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) { Text("VIEW LOGS", style = MaterialTheme.typography.labelSmall) }
                        if (onOpenAllCrashLogs != null) {
                            OutlinedButton(
                                onClick = onOpenAllCrashLogs,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0x35FFFFFF)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            ) { Text("ALL CRASH LOGS", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}
