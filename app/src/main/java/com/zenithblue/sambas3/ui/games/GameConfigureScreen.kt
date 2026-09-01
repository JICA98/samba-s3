package com.zenithblue.sambas3.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.gameconfig.GameSettingsOverrides
import com.zenithblue.sambas3.gameconfig.SettingsValueCodec
import com.zenithblue.sambas3.gameconfig.SettingsValueCodec.SettingNodeSpec
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceIcon
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceTitle
import com.zenithblue.sambas3.ui.settings.components.preference.RegularPreference
import com.zenithblue.sambas3.ui.settings.components.preference.SingleSelectionDialog
import com.zenithblue.sambas3.ui.settings.components.preference.SliderPreference
import com.zenithblue.sambas3.ui.settings.components.preference.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Curated per-title sections; every candidate node is resolved from the LIVE tree. */
private data class CuratedSection(
    val titleRes: Int,
    val iconRes: Int,
    val nodePaths: List<String>
)

private val CURATED_SECTIONS = listOf(
    CuratedSection(
        titleRes = R.string.ingame_section_video,
        iconRes = R.drawable.ic_video,
        nodePaths = listOf(
            "Video@@Resolution",
            "Video@@Aspect ratio",
            "Video@@Anisotropic Filter",
            "Video@@MSAA",
            "Video@@Shader Mode",
            "Video@@Frame limit",
            "Video@@Write Color Buffers",
            "Video@@Read Color Buffers",
            "Video@@VSync"
        )
    ),
    CuratedSection(
        titleRes = R.string.ingame_section_core,
        iconRes = R.drawable.memory,
        nodePaths = listOf(
            "Core@@Max LLVM Compile Threads",
            "Core@@PPU Decoder",
            "Core@@SPU Decoder",
            "Core@@SPU Block Size",
            "Core@@SPU Threads"
        )
    ),
    CuratedSection(
        titleRes = R.string.ingame_section_audio,
        iconRes = R.drawable.ic_audio,
        nodePaths = listOf(
            "Audio@@Master Volume",
            "Audio@@Buffer Duration",
            "Audio@@Enable Buffering"
        )
    )
)

/**
 * Per-game Configure Game page: curated tri-state rows (Use Global vs Override),
 * per-row reset (long-click or the trailing restore action), Reset All overflow.
 * Values persist as sparse RPCS3 title overrides. The core loads them after the
 * canonical global config on the next emulation boot; this screen never writes a
 * title value through the global setter.
 *
 * Hosted either fullscreen inside the emulation overlay ([GameConfigureOverlay])
 * or from the launcher's game long-press bottom sheet (engine gated to Stopped by
 * the caller).
 */
@Composable
fun GameConfigureScreen(
    gamePath: String?,
    modifier: Modifier = Modifier,
    isInGame: Boolean = false,
    onClose: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var tree by remember { mutableStateOf<JSONObject?>(null) }

    LaunchedEffect(Unit) {
        tree = withContext(Dispatchers.IO) {
            try {
                JSONObject(RPCSX.instance.settingsGetGlobal(""))
            } catch (e: Exception) {
                null
            }
        }
    }

    val titleId = remember(tree, gamePath) {
        GameSettingsOverrides.resolveTitleId(gamePath ?: "", context)
            ?: if (isInGame) runCatching { RPCSX.instance.getTitleId() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            else null
    }

    var overrides by remember(titleId) {
        mutableStateOf(GameSettingsOverrides.gameOverrides(context, titleId ?: ""))
    }
    var showResetAllConfirm by remember { mutableStateOf(false) }
    var resetAllMenuOpen by remember { mutableStateOf(false) }

    fun refreshOverrides() {
        overrides = GameSettingsOverrides.gameOverrides(context, titleId ?: "")
    }

    fun commitOverride(path: String, node: JSONObject, newDisplayValue: String) {
        val tid = titleId ?: return
        val spec = nodeSpec(node)
        val encoded = SettingsValueCodec.encodedFromNode(spec, newDisplayValue)
        val applied = GameSettingsOverrides.recordGame(
            context = context,
            titleId = tid,
            path = path,
            encoded = encoded,
            previousEncoded = overrides[path] ?: engineEncodedValue(node)
        )
        if (!applied) {
            AlertDialogQueue.showDialog(
                context.getString(R.string.error),
                context.getString(R.string.failed_to_assign_value, newDisplayValue, path)
            )
            return
        }
        refreshOverrides()
    }

    fun resetRow(path: String, node: JSONObject) {
        val tid = titleId ?: return
        val fallback = SettingsValueCodec.encodedDefault(nodeSpec(node))
            ?: engineEncodedValue(node)
        if (GameSettingsOverrides.clearGameSetting(context, tid, path, fallback)) {
            refreshOverrides()
        } else {
            AlertDialogQueue.showDialog(
                context.getString(R.string.error),
                context.getString(R.string.failed_to_reset_key, path)
            )
        }
    }

    Column(modifier = modifier.fillMaxSize().background(RPCSXColors.background)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp)
        ) {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                        contentDescription = null,
                        tint = RPCSXColors.primary
                    )
                }
            }
            Text(
                text = stringResource(R.string.configure_game).uppercase(),
                color = RPCSXColors.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 2.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (titleId != null || (onRemove != null && !isInGame)) {
                Box {
                    IconButton(onClick = { resetAllMenuOpen = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_menu),
                            contentDescription = null,
                            tint = RPCSXColors.primary
                        )
                    }
                    DropdownMenu(
                        expanded = resetAllMenuOpen,
                        onDismissRequest = { resetAllMenuOpen = false }
                    ) {
                        if (titleId != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reset_all_game)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_restore),
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    resetAllMenuOpen = false
                                    showResetAllConfirm = true
                                }
                            )
                        }
                        if (onRemove != null && !isInGame) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remove_game)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    resetAllMenuOpen = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            }
        }

        when {
            tree == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = RPCSXColors.primary)
            }

            titleId == null -> Box(Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    text = stringResource(R.string.configure_game_unresolved_id),
                    color = RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            else -> CuratedList(
                tree = tree!!,
                overrides = overrides,
                resolvedGlobals = emptyMap(),
                onCommit = ::commitOverride,
                onResetRow = ::resetRow
            )
        }
    }

    if (showResetAllConfirm && titleId != null) {
        AlertDialog(
            onDismissRequest = { showResetAllConfirm = false },
            title = { Text(stringResource(R.string.reset_all_game)) },
            text = { Text(stringResource(R.string.configure_game_reset_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetAllConfirm = false
                    val tid = titleId
                    if (GameSettingsOverrides.clearGame(context, tid)) refreshOverrides()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CuratedList(
    tree: JSONObject,
    overrides: Map<String, String>,
    resolvedGlobals: Map<String, String>,
    onCommit: (String, JSONObject, String) -> Unit,
    onResetRow: (String, JSONObject) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        CURATED_SECTIONS.forEach { section ->
            val rows = section.nodePaths.mapNotNull { path ->
                findCuratedNode(tree, path)?.let { path to it }
            }
            if (rows.isEmpty()) return@forEach

            item(key = "header_${section.titleRes}") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(
                        painter = painterResource(id = section.iconRes),
                        contentDescription = null,
                        tint = RPCSXColors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(section.titleRes).uppercase(),
                        color = RPCSXColors.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 1.5.sp
                    )
                }
            }

            rows.forEach { (path, node) ->
                item(key = path) {
                    CuratedRow(
                        path = path,
                        node = node,
                        sectionIcon = section.iconRes,
                        overridden = overrides.containsKey(path),
                        effectiveEncoded =
                            overrides[path] ?: resolvedGlobals[path] ?: engineEncodedValue(node),
                        onCommit = onCommit,
                        onResetRow = onResetRow
                    )
                }
            }
        }
    }
}

/** Effective layering mirrors the engine tree: game override > global tier > engine value. */
@Composable
private fun CuratedRow(
    path: String,
    node: JSONObject,
    sectionIcon: Int,
    overridden: Boolean,
    effectiveEncoded: String,
    onCommit: (String, JSONObject, String) -> Unit,
    onResetRow: (String, JSONObject) -> Unit
) {
    val label = path.substringAfterLast("@@")
    val effectiveDisplay = SettingsValueCodec.decodeToDisplay(effectiveEncoded)

    when (node.optString("type")) {
        "bool" -> SwitchPreference(
            checked = effectiveDisplay == "true",
            title = { PreferenceTitle(title = label) },
            subtitle = { OverrideStateBadge(overridden) },
            leadingIcon = { PreferenceIcon(icon = painterResource(id = sectionIcon)) },
            onClick = { value -> onCommit(path, node, value.toString()) },
            onLongClick = { if (overridden) onResetRow(path, node) }
        )

        "enum" -> {
            val variants = variantsOf(node)
            val coerced =
                if (effectiveDisplay in variants) effectiveDisplay else variants.firstOrNull()
            if (!variants.isNullOrEmpty() && coerced != null) {
                SingleSelectionDialog(
                    currentValue = coerced,
                    values = variants,
                    icon = { PreferenceIcon(icon = painterResource(id = sectionIcon)) },
                    title = { PreferenceTitle(title = label) },
                    subtitle = { OverrideStateBadge(overridden) },
                    trailingContent = {
                        if (overridden) {
                            IconButton(onClick = { onResetRow(path, node) }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_restore),
                                    contentDescription = stringResource(R.string.reset_row),
                                    tint = RPCSXColors.primary
                                )
                            }
                        }
                    },
                    onValueChange = { value -> onCommit(path, node, value) },
                    onLongClick = { if (overridden) onResetRow(path, node) }
                )
            } else {
                UnrenderableNodeRow(label, sectionIcon, effectiveDisplay, overridden, onResetRow = {
                    onResetRow(path, node)
                })
            }
        }

        else -> {
            val minF = node.optString("min").toFloatOrNull()
            val maxF = node.optString("max").toFloatOrNull()
            val currentF = effectiveDisplay.toFloatOrNull()
            if (minF != null && maxF != null && minF < maxF && currentF != null) {
                SliderPreference(
                    value = currentF.coerceIn(minF, maxF),
                    onValueChange = { value ->
                        onCommit(
                            path, node,
                            if (node.optString("type") == "float") value.toDouble().toString()
                            else value.toLong().toString()
                        )
                    },
                    title = label,
                    leadingIcon = { PreferenceIcon(icon = painterResource(id = sectionIcon)) },
                    valueRange = minF..maxF,
                    steps = (maxF - minF).toInt() - 1,
                    valueContent = {
                        Column {
                            Text(
                                text = effectiveDisplay,
                                color = if (overridden) RPCSXColors.primary
                                else RPCSXColors.textSecondary
                            )
                            OverrideStateBadge(overridden)
                        }
                    },
                    onLongClick = { if (overridden) onResetRow(path, node) }
                )
            } else {
                UnrenderableNodeRow(label, sectionIcon, effectiveDisplay, overridden, onResetRow = {
                    onResetRow(path, node)
                })
            }
        }
    }
}

/**
 * Read-only row for nodes whose shape makes them unrenderable as editors
 * (still shows the effective value plus the reset action when overridden).
 */
@Composable
private fun UnrenderableNodeRow(
    label: String,
    sectionIcon: Int,
    display: String,
    overridden: Boolean,
    onResetRow: () -> Unit
) {
    RegularPreference(
        title = { PreferenceTitle(title = label) },
        leadingIcon = { PreferenceIcon(icon = painterResource(id = sectionIcon)) },
        subtitle = { OverrideStateBadge(overridden) },
        value = {
            Text(
                text = display,
                color = if (overridden) RPCSXColors.primary else RPCSXColors.textSecondary
            )
        },
        onClick = {},
        onLongClick = if (overridden) onResetRow else ({})
    )
}

@Composable
private fun OverrideStateBadge(overridden: Boolean) {
    Text(
        text = stringResource(if (overridden) R.string.override_value else R.string.use_global)
            .uppercase(),
        color = if (overridden) RPCSXColors.primary else RPCSXColors.textSecondary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

private fun findCuratedNode(root: JSONObject?, path: String): JSONObject? {
    var current = root ?: return null
    for (part in path.split("@@")) {
        current = current.optJSONObject(part) ?: return null
    }
    return if (current.has("type")) current else null
}

private fun nodeSpec(node: JSONObject): SettingNodeSpec = SettingNodeSpec(
    type = node.optString("type"),
    min = if (node.has("min")) node.optString("min") else null,
    max = if (node.has("max")) node.optString("max") else null,
    default = if (node.has("default")) node.optString("default") else null
)

private fun rawDisplayValue(node: JSONObject): String = when (node.optString("type")) {
    "bool" -> node.optBoolean("value", false).toString()
    "enum", "string" -> node.optString("value")
    else -> node.optString("value")
}

private fun engineEncodedValue(node: JSONObject): String =
    SettingsValueCodec.encodedFromNode(nodeSpec(node), rawDisplayValue(node))

private fun variantsOf(node: JSONObject): List<String> = try {
    val array: JSONArray = node.getJSONArray("variants")
    List(array.length()) { index -> array.getString(index) }
} catch (e: Exception) {
    emptyList()
}

/**
 * Fullscreen in-game wrapper for [GameConfigureScreen]; hosts dialogs with
 * respectHostSuppression = false so engine rejections render during gameplay.
 */
@Composable
fun GameConfigureOverlay(
    gamePath: String?,
    onBackToMenu: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(RPCSXColors.background)) {
        GameConfigureScreen(
            gamePath = gamePath,
            isInGame = true,
            onClose = onBackToMenu
        )
        AlertDialogQueue.AlertDialog(respectHostSuppression = false)
    }
}
