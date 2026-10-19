package dev.cluvex.strata.feature.unity

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ArchiveParent
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.unity.UnityBundleDocument
import dev.cluvex.strata.core.unity.UnityBundleEntry
import dev.cluvex.strata.core.unity.UnityFileKind
import dev.cluvex.strata.core.unity.UnityProbe
import dev.cluvex.strata.core.unity.UnityTextInfo
import dev.cluvex.strata.core.unity.UnityTextUnavailableReason
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.isolated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
fun UnityAssetScreen(
    path: String,
    parents: List<ArchiveParent>,
    refreshToken: Long,
    onBack: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onOpenNested: (File, List<ArchiveParent>) -> Unit,
    onSignApk: (File) -> Unit,
    onLocate: (folder: String, reveal: String?) -> Unit,
    vm: UnityAssetViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val state by vm.state.collectAsStateWithLifecycle()
    var localBusy by remember(path) { mutableStateOf(false) }
    var signPrompt by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path, refreshToken) {
        vm.open(path)
        vm.refreshEditedFiles()
    }
    LifecycleResumeEffect(path) {
        vm.refreshEditedFiles()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(state.navigateTo) {
        state.navigateTo?.let { target ->
            vm.consumeNavigate()
            onOpenFile(FileItem.of(File(target)))
        }
    }

    val document = state.document
    val selectedEntry = remember(document, state.selectedName) {
        document?.summary?.entries?.firstOrNull { it.name == state.selectedName }
    }
    val busy = state.busy || localBusy

    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val entry = selectedEntry
        if (uri != null && entry != null) {
            scope.launch {
                localBusy = true
                try {
                    vm.replaceEntry(entry, uri, context.contentResolver)
                } catch (_: Exception) {
                    vm.notifyMessage(context.getString(R.string.unity_operation_failed))
                } finally {
                    localBusy = false
                }
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = selectedEntry
        if (uri != null && entry != null) {
            scope.launch {
                localBusy = true
                try {
                    val source = vm.fileFor(entry)
                    withContext(Dispatchers.IO) {
                        val target = context.contentResolver.openOutputStream(uri) ?: throw IOException("Export destination unavailable")
                        target.use { out -> source.inputStream().buffered().use { it.copyTo(out) } }
                    }
                    vm.notifyMessage(context.getString(R.string.unity_export_done, uri.lastPathSegment ?: entry.displayName))
                } catch (_: Exception) {
                    vm.notifyMessage(context.getString(R.string.unity_operation_failed))
                } finally {
                    localBusy = false
                }
            }
        }
    }

    BackHandler(enabled = !busy) { onBack() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.unity_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(isolated(File(path).name), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = { onLocate(File(path).parent ?: "/", path) }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.archive_show_in_files))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.unity_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.error != null -> ErrorPanel(state.error ?: stringResource(R.string.unity_load_failed))
                document != null -> {
                    BundleSummaryCard(document)
                    if (parents.isNotEmpty()) {
                        Text(stringResource(R.string.unity_parent_copy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    } else {
                        Text(stringResource(R.string.unity_output_copy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                    if (document.summary.entries.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.unity_no_entries), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(document.summary.entries, key = { index, entry -> "$index:${entry.name}" }) { index, entry ->
                                UnityEntryRow(
                                    index = index,
                                    entry = entry,
                                    selected = state.selectedName == entry.name,
                                    modified = entry.name in state.replacements || entry.name in state.editedFiles,
                                    textInfo = state.textInfos[entry.name],
                                    textIssue = state.textIssues[entry.name],
                                    onClick = { vm.select(entry.name) },
                                )
                            }
                        }
                    }
                    selectedEntry?.let { entry ->
                        UnityEntryActions(
                            entry = entry,
                            busy = busy,
                            onOpen = {
                                scope.launch {
                                    try {
                                        val staged = vm.fileFor(entry)
                                        onOpenNested(
                                            staged,
                                            parents + ArchiveParent(document.source.path, entry.name),
                                        )
                                    } catch (_: Exception) {
                                        vm.notifyMessage(context.getString(R.string.unity_operation_failed))
                                    }
                                }
                            },
                            onExport = { exportPicker.launch(entry.displayName) },
                            onReplace = { replacePicker.launch(arrayOf("*/*")) },
                            onExportTexts = if (state.textInfos.containsKey(entry.name)) {
                                { vm.exportTexts(entry) }
                            } else {
                                null
                            },
                            textIssue = state.textIssues[entry.name],
                        )
                    } ?: Text(stringResource(R.string.unity_select_entry), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))

                    val changedCount = state.replacements.size + state.editedFiles.keys.count { it !in state.replacements }
                    if (changedCount > 0) {
                        Text(
                            stringResource(R.string.unity_replacements_count, changedCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    Button(
                        onClick = { vm.repack(parents) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.unity_repack))
                    }
                    state.output?.let { saved ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onLocate(saved.parent ?: "/", saved.path) }, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.archive_show_in_files), maxLines = 1)
                            }
                            if (saved.extension.equals("apk", ignoreCase = true)) {
                                OutlinedButton(onClick = { signPrompt = true }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.unity_sign_copy), maxLines = 1)
                                }
                            }
                        }
                    }
                }
                else -> UnsupportedPanel(state.probe)
            }
        }
    }

    if (signPrompt) {
        AlertDialog(
            onDismissRequest = { signPrompt = false },
            title = { Text(stringResource(R.string.unity_sign_copy)) },
            text = { Text(stringResource(R.string.unity_sign_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    signPrompt = false
                    state.output?.let(onSignApk)
                }) { Text(stringResource(R.string.common_sign_apk)) }
            },
            dismissButton = { TextButton(onClick = { signPrompt = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun BundleSummaryCard(document: UnityBundleDocument) {
    val s = document.summary
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.unity_bundle_format, s.formatVersion), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.unity_engine_version, s.engineVersion.ifEmpty { "—" }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.unity_player_version, s.playerVersion.ifEmpty { "—" }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.unity_entries_count, s.entries.size, Formatters.size(s.dataSize)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun UnityEntryRow(
    index: Int,
    entry: UnityBundleEntry,
    selected: Boolean,
    modified: Boolean,
    textInfo: UnityTextInfo? = null,
    textIssue: UnityTextUnavailableReason? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(index.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (textInfo != null) {
                    Text(
                        stringResource(R.string.unity_texts_badge, textInfo.stringCount, textInfo.objectName ?: textInfo.className),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (textIssue != null) {
                    Text(
                        unityTextIssueLabel(textIssue),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(isolated(entry.name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Formatters.size(entry.size), style = MaterialTheme.typography.labelMedium)
                if (modified) Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun UnityEntryActions(
    entry: UnityBundleEntry,
    busy: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onReplace: () -> Unit,
    onExportTexts: (() -> Unit)? = null,
    textIssue: UnityTextUnavailableReason? = null,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(isolated(entry.name), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.unity_entry_size, Formatters.size(entry.size)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.unity_entry_flags, "0x${entry.flags.toString(16).uppercase().padStart(8, '0')}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (textIssue != null) {
                Text(
                    unityTextIssueLabel(textIssue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onOpen, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.unity_open_entry), maxLines = 1) }
                TextButton(onClick = onExport, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.unity_export_entry), maxLines = 1) }
                TextButton(onClick = onReplace, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.unity_replace_entry), maxLines = 1) }
            }
            if (onExportTexts != null) {
                Button(onClick = onExportTexts, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(stringResource(R.string.unity_export_texts), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun unityTextIssueLabel(reason: UnityTextUnavailableReason): String = when (reason) {
    UnityTextUnavailableReason.NOT_SERIALIZED_FILE -> stringResource(R.string.unity_text_not_serialized)
    UnityTextUnavailableReason.NO_TYPE_INFORMATION -> stringResource(R.string.unity_text_no_type_info)
    UnityTextUnavailableReason.UNSUPPORTED_OBJECT -> stringResource(R.string.unity_text_unsupported_object)
    UnityTextUnavailableReason.UNSUPPORTED_VERSION -> stringResource(R.string.unity_unsupported_version)
    UnityTextUnavailableReason.NO_TEXT -> stringResource(R.string.unity_text_none_found)
    UnityTextUnavailableReason.TOO_MANY_OBJECTS -> stringResource(R.string.unity_text_too_many_objects)
    UnityTextUnavailableReason.DECODE_FAILED -> stringResource(R.string.unity_text_decode_failed)
    UnityTextUnavailableReason.DOCUMENT_CLOSED -> stringResource(R.string.unity_text_document_closed)
}

@Composable
private fun UnsupportedPanel(probe: UnityProbe?) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.unity_not_supported), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.size(12.dp))
        when (probe?.kind) {
            UnityFileKind.SERIALIZED_FILE -> Text(
                stringResource(R.string.unity_serialized_info, probe.fileVersion ?: 0, probe.dataOffset?.let(Formatters::size) ?: "—"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UnityFileKind.UNKNOWN, null -> Text(stringResource(R.string.unity_unknown_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text(stringResource(R.string.unity_not_supported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorPanel(message: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.unity_error_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}
