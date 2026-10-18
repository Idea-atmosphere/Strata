package dev.cluvex.strata.feature.archive

import dev.cluvex.strata.ui.isolated
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ArchiveEntry
import dev.cluvex.strata.core.archive.ArchiveManager
import dev.cluvex.strata.core.archive.PasswordRequiredException
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.res.ArscDocument
import dev.cluvex.strata.feature.explorer.components.FileRow
import dev.cluvex.strata.feature.explorer.components.InputDialog
import dev.cluvex.strata.feature.explorer.components.InputSelection
import dev.cluvex.strata.feature.explorer.components.PasswordDialog
import dev.cluvex.strata.feature.explorer.components.TaskOverlay
import java.io.File
import dev.cluvex.strata.ui.expressive.Art
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import kotlinx.coroutines.launch

data class ArchiveActions(
    val onBack: () -> Unit,
    val onOpenFile: (File) -> Unit,
    val onSignApk: (String) -> Unit,
    val onLocate: (folder: String, reveal: String?) -> Unit,
    val onOpenArsc: (archive: String, entry: String) -> Unit,
    val onOpenEntry: (archive: String, entry: ArchiveEntry, materialized: File) -> Unit,
)

private sealed interface ArchiveDialog {
    data object None : ArchiveDialog
    data class Extract(val paths: Set<String>) : ArchiveDialog
    data class Rename(val entry: ArchiveEntry) : ArchiveDialog
    data class Delete(val paths: Set<String>) : ArchiveDialog
    data object Password : ArchiveDialog
    data object LeaveApk : ArchiveDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArchiveScreen(
    path: String,
    otherPaneDir: String?,
    actions: ArchiveActions,
    vm: ArchiveViewModel = viewModel(),
) {
    LaunchedEffect(path) { vm.open(path) }
    LifecycleResumeEffect(path) {
        vm.checkModified()
        onPauseOrDispose { }
    }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<ArchiveDialog>(ArchiveDialog.None) }
    var menu by remember { mutableStateOf(false) }
    var pendingOpen by remember { mutableStateOf<ArchiveEntry?>(null) }

    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    LaunchedEffect(s.passwordNeeded) { if (s.passwordNeeded) dialog = ArchiveDialog.Password }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dir = File(context.cacheDir, "archive-add/${System.nanoTime()}").apply { mkdirs() }
                uris.mapNotNull { uri -> copyToDir(context, uri, dir) }
            }
            vm.add(staged)
        }
    }

    fun leave() {
        if (s.apkChanged) dialog = ArchiveDialog.LeaveApk else actions.onBack()
    }

    BackHandler {
        when {
            s.selected.isNotEmpty() -> vm.clearSelection()
            vm.up() -> Unit
            else -> leave()
        }
    }

    fun open(entry: ArchiveEntry) {
        if (entry.isDirectory) {
            vm.enter(entry.path)
            return
        }
        if (entry.name == ArscDocument.RESOURCES_ARSC && s.canUpdate) {
            actions.onOpenArsc(s.path, entry.path)
            return
        }
        scope.launch {
            try {
                actions.onOpenEntry(s.path, entry, vm.materialize(entry))
            } catch (e: PasswordRequiredException) {
                pendingOpen = entry
                dialog = ArchiveDialog.Password
            } catch (e: dev.cluvex.strata.core.archive.WrongPasswordException) {
                pendingOpen = entry
                dialog = ArchiveDialog.Password
                snackbar.showSnackbar(tr(R.string.archive_wrong_password))
            } catch (e: Exception) {
                snackbar.showSnackbar(tr(R.string.archive_couldnt_open, entry.name, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (s.selected.isNotEmpty()) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_clear_selection)) } },
                    title = { Text(pluralStringResource(R.plurals.archive_selected, s.selected.size, s.selected.size)) },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.common_select_all)) }
                        IconButton(onClick = { dialog = ArchiveDialog.Extract(s.selected) }) {
                            Icon(Icons.Rounded.Unarchive, contentDescription = stringResource(R.string.archive_extract))
                        }
                        if (s.canUpdate) {
                            if (s.selected.size == 1) {
                                IconButton(onClick = {
                                    s.entries.firstOrNull { it.path in s.selected }?.let { dialog = ArchiveDialog.Rename(it) }
                                }) { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = stringResource(R.string.common_rename)) }
                            }
                            IconButton(onClick = { dialog = ArchiveDialog.Delete(s.selected) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_delete))
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                    title = {
                        Column {
                            Text(isolated(s.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val entriesText = pluralStringResource(R.plurals.archive_entries, s.totalEntries, s.totalEntries)
                            val readOnly = stringResource(R.string.archive_read_only)
                            Text(
                                entriesText + if (s.canUpdate) "" else " · $readOnly",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { dialog = ArchiveDialog.Extract(emptySet()) }, enabled = !s.loading) {
                            Icon(Icons.Rounded.Unarchive, contentDescription = stringResource(R.string.archive_extract_all))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.archive_options)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (s.canUpdate) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.archive_add_files)) }, onClick = { menu = false; pick.launch(arrayOf("*/*")) })
                                }
                                if (s.isApk) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.common_sign_apk)) }, onClick = { menu = false; actions.onSignApk(s.path) })
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.archive_show_in_files)) },
                                    onClick = { menu = false; actions.onLocate(File(s.path).parent ?: "/", s.path) },
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 4.dp)) {
            InnerPath(s.name, s.dir)
            when {
                s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                s.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(s.error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                else -> EntryList(s, onUp = { vm.up() }, onOpen = ::open, onToggle = { vm.toggle(it.path) })
            }
        }
    }

    s.task?.let { TaskOverlay(it) }
    s.build?.let { dev.cluvex.strata.feature.build.BuildDialog(it, onClose = vm::closeBuild) }

    s.encodeError?.takeIf { s.build == null }?.let { err ->
        AlertDialog(
            onDismissRequest = vm::dismissEncodeError,
            title = { Text(stringResource(R.string.archive_couldnt_save, err.entry.substringAfterLast('/'))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(err.message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.archive_edits_kept))
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissEncodeError(); actions.onOpenFile(err.file) }) { Text(stringResource(R.string.archive_edit_again)) }
            },
            dismissButton = { TextButton(onClick = { vm.discardEdits(err.entry) }) { Text(stringResource(R.string.archive_discard_edits)) } },
        )
    }

    if (s.modified.isNotEmpty() && dialog == ArchiveDialog.None && s.encodeError == null) {
        ModifiedDialog(
            modified = s.modified,
            canUpdate = s.canUpdate,
            onUpdate = vm::applyUpdates,
            onDiscard = { vm.discardUpdates() },
            onIgnore = { vm.discardUpdates(deleteCopies = false) },
            onLocate = { f -> vm.discardUpdates(deleteCopies = false); actions.onLocate(f.parent ?: "/", f.path) },
        )
    }

    when (val d = dialog) {
        ArchiveDialog.None -> Unit
        is ArchiveDialog.Extract -> ExtractDialog(
            archivePath = s.path,
            count = d.paths.size,
            otherPaneDir = otherPaneDir,
            onConfirm = { dest -> dialog = ArchiveDialog.None; vm.extract(d.paths, dest) },
            onDismiss = { dialog = ArchiveDialog.None },
        )
        is ArchiveDialog.Rename -> InputDialog(
            title = stringResource(R.string.common_rename),
            label = stringResource(R.string.archive_new_name),
            initial = d.entry.name,
            select = if (d.entry.isDirectory) InputSelection.ALL else InputSelection.BASE_NAME,
            onConfirm = { vm.rename(d.entry.path, it); dialog = ArchiveDialog.None },
            onDismiss = { dialog = ArchiveDialog.None },
        )
        is ArchiveDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = ArchiveDialog.None },
            title = { Text(stringResource(R.string.archive_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.archive_delete_text, d.paths.size, d.paths.size, s.name)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(d.paths); dialog = ArchiveDialog.None }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = ArchiveDialog.None }) { Text(stringResource(R.string.common_cancel)) } },
        )
        ArchiveDialog.Password -> PasswordDialog(
            title = stringResource(R.string.archive_encrypted_title),
            fileName = s.name,
            wrong = s.wrongPassword,
            onConfirm = { pw ->
                dialog = ArchiveDialog.None
                vm.submitPassword(pw)
                pendingOpen?.let { pendingOpen = null; open(it) }
            },
            onDismiss = {
                dialog = ArchiveDialog.None
                pendingOpen = null
                if (s.passwordNeeded && s.totalEntries == 0) actions.onBack()
            },
        )
        ArchiveDialog.LeaveApk -> AlertDialog(
            onDismissRequest = { dialog = ArchiveDialog.None },
            title = { Text(stringResource(R.string.archive_apk_modified)) },
            text = { Text(stringResource(R.string.archive_apk_modified_text)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = ArchiveDialog.None
                    vm.acknowledgeApkChange()
                    actions.onSignApk(s.path)
                }) { Text(stringResource(R.string.common_sign_apk)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = ArchiveDialog.None; vm.acknowledgeApkChange(); actions.onBack() }) { Text(stringResource(R.string.common_later)) }
            },
        )
    }
}

@Composable
private fun InnerPath(name: String, dir: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                if (dir.isEmpty()) name else dir.substringAfterLast('/'),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$name/" + dir,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

@Composable
private fun EntryList(
    s: ArchiveUiState,
    onUp: () -> Unit,
    onOpen: (ArchiveEntry) -> Unit,
    onToggle: (ArchiveEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(s.dir) { listState.scrollToItem(0) }
    val selecting = s.selected.isNotEmpty()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("archive-list"),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!s.atRoot) {
            item(key = "..") { UpRow(onUp) }
        }
        if (s.entries.isEmpty()) {
            item(key = "empty") {
                ExpressiveEmptyState(stringResource(R.string.archive_empty_folder), Modifier.fillMaxWidth().padding(vertical = 32.dp), art = Art.EMPTY_FOLDER, maxHero = 140.dp)
            }
        }
        items(s.entries, key = { it.path }) { entry ->
            FileRow(
                item = entry.toFileItem(),
                selected = entry.path in s.selected,
                flashKey = 0,
                showThumbnail = false,
                onClick = { if (selecting) onToggle(entry) else onOpen(entry) },
                onLongClick = { onToggle(entry) },
                onIconClick = { onToggle(entry) },
            )
        }
    }
}

@Composable
private fun UpRow(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val parentLabel = stringResource(R.string.archive_parent_folder)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClickLabel = parentLabel, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        Text("..", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
    }
}

private const val FIRST_REAL_TIME = 378_691_200_000L

private fun ArchiveEntry.toFileItem() = FileItem(
    name = name,
    path = path,
    isDirectory = isDirectory,
    size = size.coerceAtLeast(0),
    lastModified = if (time < FIRST_REAL_TIME) 0 else time,
    isHidden = false,
    canRead = true,
    canWrite = false,
)

@Composable
private fun ModifiedDialog(
    modified: List<OpenedEntry>,
    canUpdate: Boolean,
    onUpdate: () -> Unit,
    onDiscard: () -> Unit,
    onIgnore: () -> Unit,
    onLocate: (File) -> Unit,
) {
    if (!canUpdate) {
        AlertDialog(
            onDismissRequest = onIgnore,
            title = { Text(stringResource(R.string.archive_cant_update_title)) },
            text = { Text(stringResource(R.string.archive_cant_update_text)) },
            confirmButton = { TextButton(onClick = onIgnore) { Text(stringResource(R.string.common_ok)) } },
            dismissButton = { TextButton(onClick = { onLocate(modified.first().file) }) { Text(stringResource(R.string.archive_locate_file)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.archive_update_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (modified.size == 1) {
                    Text(stringResource(R.string.archive_modified_one, modified.first().entry.substringAfterLast('/')))
                } else {
                    Text(stringResource(R.string.archive_modified_many))
                    modified.forEach {
                        Text(it.entry, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text(stringResource(R.string.archive_update)) } },
        dismissButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.common_discard)) } },
    )
}

@Composable
private fun ExtractDialog(
    archivePath: String,
    count: Int,
    otherPaneDir: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val parent = File(archivePath).parent ?: "/"
    val separate = File(parent, ArchiveManager.extractionFolderName(File(archivePath).name)).path
    val intoNew = stringResource(R.string.archive_into_new_folder)
    val nextTo = stringResource(R.string.archive_next_to_archive)
    val otherPane = stringResource(R.string.archive_other_pane_folder)
    val options = buildList {
        add(intoNew to separate)
        add(nextTo to parent)
        if (otherPaneDir != null && otherPaneDir != parent) add(otherPane to otherPaneDir)
    }
    var choice by remember { mutableStateOf(0) }
    var dest by remember { mutableStateOf(options[0].second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 0) stringResource(R.string.archive_extract_all) else pluralStringResource(R.plurals.archive_extract_items, count, count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEachIndexed { i, (label, target) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = choice == i, role = Role.RadioButton, onClick = { choice = i; dest = target })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == i, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = dest,
                    onValueChange = { dest = it },
                    label = { Text(stringResource(R.string.archive_extract_to)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(dest.trim()) }, enabled = dest.isNotBlank()) { Text(stringResource(R.string.archive_extract)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private fun copyToDir(context: android.content.Context, uri: Uri, dir: File): File? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    val safe = name.replace('/', '_')
    val out = File(dir, safe)
    context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
    out
}.getOrNull()
