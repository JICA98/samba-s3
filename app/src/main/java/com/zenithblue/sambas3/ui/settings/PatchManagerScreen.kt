package com.zenithblue.sambas3.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.PatchDownloadResult
import com.zenithblue.sambas3.PatchGroup
import com.zenithblue.sambas3.PatchRepository
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.settings.components.preference.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val UNIVERSAL_LABEL = "All games (universal)"

private fun gameLabelsFor(titles: List<String>, serials: List<String>): List<String> {
    val t = titles.filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
    if (t.isNotEmpty()) return t.distinctBy { it.lowercase() }
    val s = serials.filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
    if (s.isNotEmpty()) return s.distinctBy { it.lowercase() }
    return listOf(UNIVERSAL_LABEL)
}

private data class GameGroup(
    val label: String,
    val patches: List<PatchGroup>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchManagerScreen(
    navigateBack: () -> Unit,
    isInSplitPane: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    var loading by remember { mutableStateOf(true) }
    var groups by remember { mutableStateOf<List<GameGroup>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var downloadState by remember { mutableStateOf<PatchDownloadResult?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            val all = withContext(Dispatchers.IO) { PatchRepository.list() }
            val grouped = withContext(Dispatchers.IO) { PatchRepository.group(all) }
            val gameGroups = grouped
                .flatMap { pg ->
                    gameLabelsFor(pg.titles, pg.serials).map { label ->
                        label to pg
                    }
                }
                .groupBy({ it.first }, { it.second })
                .map { (label, patches) ->
                    // Same patch can map into one label more than once; keep one entry per identity.
                    GameGroup(
                        label = label,
                        patches = patches.distinctBy { it.identityKey() },
                    )
                }
                .sortedBy { it.label.lowercase() }
            groups = gameGroups
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun downloadOfficial() {
        scope.launch {
            downloadState = null
            downloadState = withContext(Dispatchers.IO) {
                PatchRepository.downloadOfficial()
            }
            if (downloadState is PatchDownloadResult.Success) {
                refresh()
            }
        }
    }

    val filteredGroups = remember(groups, query) {
        if (query.isEmpty()) {
            groups
        } else {
            groups.mapNotNull { group ->
                val labelMatch = group.label.contains(query, ignoreCase = true)
                val matching = group.patches.filter { p ->
                    p.name.contains(query, ignoreCase = true) ||
                        p.author.contains(query, ignoreCase = true) ||
                        p.notes.contains(query, ignoreCase = true) ||
                        p.serials.any { it.contains(query, ignoreCase = true) }
                }
                when {
                    matching.isNotEmpty() -> GameGroup(group.label, matching)
                    labelMatch -> group
                    else -> null
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()
                            ?.readText().orEmpty()
                    }
                    if (content.isNotEmpty()) {
                        PatchRepository.importLocal(content)
                        refresh()
                        Toast.makeText(context, "Patch imported", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        context, "Import failed: ${e.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @Composable
    fun PatchSearchBar() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                placeholder = {
                    Text(
                        "Search patches…",
                        color = RPCSXColors.textDisabled,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = RPCSXColors.textPrimary,
                ),
                shape = RoundedCornerShape(8.dp)
            )
            // Actions stay next to search when top bar is hidden (split pane).
            if (isInSplitPane) {
                IconButton(onClick = { downloadOfficial() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cloud_download),
                        contentDescription = "Download patches",
                        tint = RPCSXColors.textSecondary,
                    )
                }
                IconButton(onClick = { importLauncher.launch("*/*") }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = "Import patch",
                        tint = RPCSXColors.textSecondary,
                    )
                }
            }
        }
    }

    @Composable
    fun PatchListBody(contentPadding: PaddingValues = PaddingValues(0.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            PatchSearchBar()

            when {
                downloadState is PatchDownloadResult.Error -> {
                    Text(
                        (downloadState as PatchDownloadResult.Error).message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = RPCSXColors.errorColor,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                downloadState is PatchDownloadResult.Success &&
                    !(downloadState as PatchDownloadResult.Success).updated -> {
                    Text(
                        "Patches already up to date",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = RPCSXColors.primaryDim,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }

            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = RPCSXColors.primary)
                }
            } else if (filteredGroups.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No patches found",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                color = RPCSXColors.textSecondary,
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { downloadOfficial() }) {
                            Text(
                                "DOWNLOAD OFFICIAL PATCHES",
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    filteredGroups.forEach { gameGroup ->
                        // Unique across game groups — same patch name can appear under many titles.
                        val headerKey = "header-${gameGroup.label}"
                        item(key = headerKey) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(RPCSXColors.surface)
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = gameGroup.label,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = RPCSXColors.primary,
                                    )
                                )
                                Text(
                                    text = "${gameGroup.patches.size} patch${if (gameGroup.patches.size != 1) "es" else ""}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = RPCSXColors.textSecondary,
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(1.dp))
                        }

                        items(
                            items = gameGroup.patches,
                            key = { patch -> "${gameGroup.label}::${patch.identityKey()}" },
                        ) { patch ->
                            SwitchPreference(
                                checked = patch.enabled,
                                title = patch.name,
                                subtitle = {
                                    if (patch.notes.isNotEmpty()) {
                                        Text(
                                            patch.notes,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                color = RPCSXColors.textSecondary,
                                            ),
                                            maxLines = 2,
                                        )
                                    }
                                },
                                onClick = { enabled ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            PatchRepository.setEnabled(patch, enabled)
                                        }
                                        refresh()
                                    }
                                }
                            )
                        }

                        item(key = "spacer-${gameGroup.label}") {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    if (isInSplitPane) {
        // No nested back/title — Settings pane already labels the selection.
        PatchListBody()
    } else {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "PATCH MANAGER",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(
                                    2f,
                                    androidx.compose.ui.unit.TextUnitType.Sp
                                )
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_keyboard_arrow_left),
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { downloadOfficial() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cloud_download),
                                contentDescription = "Download patches"
                            )
                        }
                        IconButton(onClick = { importLauncher.launch("*/*") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = "Import patch"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = RPCSXColors.background,
                        titleContentColor = RPCSXColors.textPrimary,
                        navigationIconContentColor = RPCSXColors.primary,
                        actionIconContentColor = RPCSXColors.textSecondary,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = RPCSXColors.background,
            bottomBar = {
                ControllerHintStrip(
                    hints = listOf(R.drawable.cross to "Toggle", R.drawable.circle to "Back")
                )
            }
        ) { padding ->
            PatchListBody(contentPadding = padding)
        }
    }
}

/** Stable unique id for a patch group across game-label buckets. */
private fun PatchGroup.identityKey(): String =
    listOf(name, author, version, notes, hashes.sorted().joinToString(",")).joinToString("|")
