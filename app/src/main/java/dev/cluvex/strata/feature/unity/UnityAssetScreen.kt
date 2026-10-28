package dev.cluvex.strata.feature.unity

import android.content.ActivityNotFoundException
import android.os.Environment
import android.provider.DocumentsContract
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.cluvex.strata.core.open.IncomingPaths
import dev.cluvex.strata.core.unity.LocalizationAddLanguagePlan
import dev.cluvex.strata.core.unity.LocalizationImportPlan
import dev.cluvex.strata.core.unity.LocalizationSource
import dev.cluvex.strata.core.unity.UnityBundleDocument
import dev.cluvex.strata.core.unity.UnityBundleEntry
import dev.cluvex.strata.core.unity.UnityBundleRewriteEstimate
import dev.cluvex.strata.core.unity.UnityFileKind
import dev.cluvex.strata.core.unity.UnityFontReport
import dev.cluvex.strata.core.unity.UnityLocalizationOpenRequest
import dev.cluvex.strata.core.unity.UnityLocalizationScanProgress
import dev.cluvex.strata.core.unity.UnityProbe
import dev.cluvex.strata.core.unity.UnityRtlFlagInference
import dev.cluvex.strata.core.unity.UnityRtlInferenceStatus
import dev.cluvex.strata.core.unity.UnityTextInfo
import dev.cluvex.strata.core.unity.UnityTextTransformOptions
import dev.cluvex.strata.core.unity.UnityTextUnavailableReason
import dev.cluvex.strata.core.unity.UnityTranslationFontCoverage
import dev.cluvex.strata.core.unity.UnityTranslationNextAction
import dev.cluvex.strata.core.unity.UnityTranslationReadiness
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.isolated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal fun toggleUnityEntrySelection(current: String?, tapped: String): String? =
    tapped.takeUnless { it == current }

@Composable
fun UnityAssetScreen(
    path: String,
    parents: List<ArchiveParent>,
    refreshToken: Long,
    autoInspectLocalizations: Boolean = false,
    onBack: () -> Unit,
    onEditTextFile: (String) -> Unit,
    onOpenNested: (File, List<ArchiveParent>) -> Unit,
    onOpenDiscoveryCandidate: (UnityLocalizationOpenRequest) -> Unit,
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
    var fontRewritePrompt by remember(path) { mutableStateOf<UnityBundleRewriteEstimate?>(null) }
    var pendingRawFontTarget by remember(path) { mutableStateOf<Long?>(null) }
    var addLanguagePrompt by remember(path) { mutableStateOf(false) }
    var showDiscovery by rememberSaveable(path) { mutableStateOf(false) }
    var discoveryStaging by remember(path) { mutableStateOf(false) }
    var discoveryStagingIdentity by remember(path) { mutableStateOf<String?>(null) }
    var discoveryStagingJob by remember(path) { mutableStateOf<Job?>(null) }
    var newLanguageName by remember(path) { mutableStateOf("") }
    var newLanguageCode by remember(path) { mutableStateOf("") }

    LaunchedEffect(path, refreshToken, autoInspectLocalizations) {
        vm.open(path, autoInspectLocalizations)
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
    LaunchedEffect(state.output?.path, state.outputNeedsSigning) {
        if (state.output != null && state.outputNeedsSigning) signPrompt = true
    }
    LaunchedEffect(state.editorRequest?.id) {
        state.editorRequest?.let { request ->
            // Consume before changing routes so returning from the editor cannot replay the handoff.
            vm.consumeEditorRequest(request.id)
            val draft = File(request.path)
            if (!draft.isFile || !draft.canRead()) {
                vm.notifyMessage(context.getString(R.string.unity_staging_unavailable))
            } else {
                try {
                    // Generated JSON drafts open directly in Strata's text editor. Localization
                    // TSV uses a document-provider round trip so this route is never required.
                    onEditTextFile(draft.absolutePath)
                } catch (error: Exception) {
                    val detail = error.message?.takeIf { it.isNotBlank() }
                    vm.notifyMessage(
                        if (detail == null) context.getString(R.string.unity_operation_failed)
                        else context.getString(R.string.unity_operation_failed_detail, detail),
                    )
                }
            }
        }
    }

    val document = state.document
    val selectedEntry = remember(document, state.selectedName) {
        document?.summary?.entries?.firstOrNull { it.name == state.selectedName }
    }
    val selectedLocalization = remember(
        state.localizationSources,
        state.selectedLocalizationTableId,
    ) {
        val tableId = state.selectedLocalizationTableId
        state.localizationSources.values.flatten().firstOrNull { it.origin.tableId == tableId }
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

    val tsvSavePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values"),
    ) { uri ->
        if (uri != null) vm.saveLocalizationTsv(uri, context.contentResolver)
    }

    val tsvImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLocalizationTsv(uri, context.contentResolver)
    }

    LaunchedEffect(state.tsvExportRequest?.id) {
        state.tsvExportRequest?.let { request ->
            // Consume first so configuration changes or returning from the picker cannot replay it.
            vm.consumeTsvExportRequest(request.id)
            try {
                tsvSavePicker.launch(request.suggestedName)
            } catch (_: ActivityNotFoundException) {
                vm.notifyMessage(context.getString(R.string.unity_operation_failed))
            } catch (_: SecurityException) {
                vm.notifyMessage(context.getString(R.string.unity_operation_failed))
            } catch (_: Exception) {
                vm.notifyMessage(context.getString(R.string.unity_operation_failed))
            }
        }
    }

    val fontTtfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val entry = selectedEntry
        if (uri != null && entry != null) {
            vm.applyDynamicFont(entry, uri, context.contentResolver)
        }
    }

    val rawFontTtfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val entry = selectedEntry
        if (uri != null && entry != null) {
            vm.applyRawFont(entry, uri, context.contentResolver, pendingRawFontTarget)
        }
        pendingRawFontTarget = null
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val localPath = documentId?.let {
                IncomingPaths.fromDocumentId(
                    uri.authority,
                    it,
                    Environment.getExternalStorageDirectory().path,
                )
            }
            if (localPath == null) {
                vm.notifyMessage(context.getString(R.string.unity_discovery_folder_unavailable))
            } else {
                showDiscovery = true
                vm.discoverLocalizationDirectory(File(localPath), localPath)
            }
        }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showDiscovery = true
            discoveryStaging = true
            discoveryStagingIdentity = uri.toString()
            discoveryStagingJob = scope.launch {
                localBusy = true
                try {
                    val apk = runInterruptible(Dispatchers.IO) {
                        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                        val local = documentId?.let {
                            IncomingPaths.fromDocumentId(
                                uri.authority,
                                it,
                                Environment.getExternalStorageDirectory().path,
                            )
                        }?.let(::File)?.takeIf(File::isFile)
                        local ?: stagePickedApk(
                            context.cacheDir,
                            uri.toString(),
                            open = {
                                context.contentResolver.openInputStream(uri)
                                    ?: throw IOException("APK source unavailable")
                            },
                        )
                    }
                    vm.discoverLocalizationApk(apk, apk.path)
                } catch (_: CancellationException) {
                    // The temporary file is deleted by stagePickedApk.
                } catch (error: Exception) {
                    val detail = error.message?.takeIf { it.isNotBlank() }
                    vm.notifyMessage(
                        if (detail == null) context.getString(R.string.unity_operation_failed)
                        else context.getString(R.string.unity_operation_failed_detail, detail),
                    )
                } finally {
                    discoveryStaging = false
                    discoveryStagingIdentity = null
                    discoveryStagingJob = null
                    localBusy = false
                }
            }
        }
    }

    fun leave() {
        discoveryStagingJob?.cancel()
        vm.clearDiscovery()
        onBack()
    }
    BackHandler(enabled = !busy && !state.discoveryRunning) { leave() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::leave, enabled = !busy && !state.discoveryRunning) {
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
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (state.textAutoInspectionRunning) {
                                item(key = "text-auto-inspection-progress") {
                                    Column(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.unity_text_auto_inspection_progress,
                                                state.textAutoInspectionScanned,
                                                state.textAutoInspectionTotal,
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        val fraction = state.textAutoInspectionScanned.toFloat() /
                                            state.textAutoInspectionTotal.coerceAtLeast(1).toFloat()
                                        LinearProgressIndicator(
                                            progress = { fraction.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                            document.summary.entries.forEachIndexed { index, entry ->
                                val selected = state.selectedName == entry.name
                                item(key = "entry:$index:${entry.name}") {
                                    UnityEntryRow(
                                        index = index,
                                        entry = entry,
                                        selected = selected,
                                        modified = entry.name in state.replacements || entry.name in state.editedFiles,
                                        textInfo = state.textInfos[entry.name],
                                        onClick = {
                                            vm.select(toggleUnityEntrySelection(state.selectedName, entry.name))
                                        },
                                    )
                                }
                                if (selected) {
                                    item(key = "entry-actions:$index:${entry.name}") {
                                        Column(Modifier.fillMaxWidth()) {
                                            UnityEntryActions(
                                                entry = entry,
                                                busy = busy,
                                                onCollapse = { vm.select(null) },
                                                onOpen = {
                                                    scope.launch {
                                                        try {
                                                            val staged = vm.fileFor(entry)
                                                            onOpenNested(
                                                                staged,
                                                                parents + ArchiveParent(document.source.path, entry.name),
                                                            )
                                                        } catch (_: Exception) {
                                                            vm.notifyMessage(
                                                                context.getString(R.string.unity_operation_failed),
                                                            )
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
                                                onInspectFonts = { vm.inspectFonts(entry) },
                                                onBindDynamicFont = {
                                                    val estimate = vm.estimateRewrite()
                                                    if (estimate != null) {
                                                        fontRewritePrompt = estimate
                                                    } else {
                                                        fontTtfPicker.launch(arrayOf("*/*"))
                                                    }
                                                },
                                                onReplaceRawFont = { pathId ->
                                                    pendingRawFontTarget = pathId
                                                    val estimate = vm.estimateRewrite()
                                                    if (estimate != null) {
                                                        fontRewritePrompt = estimate
                                                    } else {
                                                        rawFontTtfPicker.launch(arrayOf("*/*"))
                                                    }
                                                },
                                                fontReport = state.fontReports[entry.name],
                                                textTransformOptions = state.textTransformOptions,
                                                onToggleShapeArabic = vm::setShapeArabic,
                                                onToggleReverseBidi = vm::setReverseBidi,
                                                onToggleNormalizePersian = vm::setNormalizePersian,
                                                textIssue = state.textIssues[entry.name],
                                            )
                                            LocalizationActions(
                                                busy = busy,
                                                sources = state.localizationSources[entry.name].orEmpty(),
                                                selected = selectedLocalization?.takeIf {
                                                    it.origin.entryName == entry.name
                                                },
                                                sourceLanguageCode = state.localizationSourceLanguageCode,
                                                targetLanguageCode = state.localizationTargetLanguageCode,
                                                rtlInference = state.rtlFlagInference,
                                                fontCoverage = state.fontCoverage,
                                                readiness = state.translationReadiness,
                                                canValidateAndStage = state.canValidateAndStageLocalization,
                                                addPlan = state.localizationAddPlan,
                                                importPlan = state.localizationImportPlan,
                                                hasTsv = state.localizationTsv?.isFile == true,
                                                textTransformOptions = state.textTransformOptions,
                                                onToggleShapeArabic = vm::setShapeArabic,
                                                onToggleReverseBidi = vm::setReverseBidi,
                                                onToggleNormalizePersian = vm::setNormalizePersian,
                                                onFind = { vm.inspectLocalizations(entry) },
                                                onDiscoverSource = { showDiscovery = true },
                                                onSelectTable = vm::selectLocalizationTable,
                                                onSelectSourceLanguage = vm::setLocalizationSourceLanguage,
                                                onSelectTargetLanguage = vm::setLocalizationTargetLanguage,
                                                onInspectFont = {
                                                    if (state.fontCoverage == null) {
                                                        vm.inspectFonts(entry)
                                                    } else {
                                                        val estimate = vm.estimateRewrite()
                                                        if (estimate != null) {
                                                            fontRewritePrompt = estimate
                                                        } else {
                                                            fontTtfPicker.launch(arrayOf("*/*"))
                                                        }
                                                    }
                                                },
                                                onExportPackage = { vm.repack(parents) },
                                                onExportAll = { vm.exportLocalization() },
                                                onExportLanguage = vm::exportLocalization,
                                                onAddLanguage = {
                                                    newLanguageName = ""
                                                    newLanguageCode = ""
                                                    addLanguagePrompt = true
                                                },
                                                onImportTsv = {
                                                    try {
                                                        tsvImportPicker.launch(arrayOf("text/*", "application/octet-stream"))
                                                    } catch (_: ActivityNotFoundException) {
                                                        vm.notifyMessage(context.getString(R.string.unity_operation_failed))
                                                    } catch (_: SecurityException) {
                                                        vm.notifyMessage(context.getString(R.string.unity_operation_failed))
                                                    } catch (_: Exception) {
                                                        vm.notifyMessage(context.getString(R.string.unity_operation_failed))
                                                    }
                                                },
                                                onApply = { vm.applyLocalizationPlan(entry) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

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

    if (showDiscovery) {
        val dialogState = if (discoveryStaging) {
            state.copy(
                discoveryRunning = true,
                discoveryProgress = UnityLocalizationScanProgress(0, 1, 0L, discoveryStagingIdentity),
                discoveryReport = null,
                discoveryScopeLabel = discoveryStagingIdentity,
            )
        } else {
            state
        }
        UnityLocalizationDiscoveryDialog(
            state = dialogState,
            onPickFolder = { folderPicker.launch(null) },
            onPickApk = {
                apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/zip"))
            },
            onOpen = { request -> onOpenDiscoveryCandidate(request) },
            onCancel = {
                if (discoveryStaging) discoveryStagingJob?.cancel() else vm.cancelDiscovery()
            },
            onDismiss = {
                showDiscovery = false
                if (!dialogState.discoveryRunning) vm.clearDiscovery()
            },
        )
    }

    if (addLanguagePrompt) {
        AlertDialog(
            onDismissRequest = { addLanguagePrompt = false },
            title = { Text(stringResource(R.string.unity_localization_add_language)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newLanguageName,
                        onValueChange = { newLanguageName = it },
                        label = { Text(stringResource(R.string.unity_localization_language_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newLanguageCode,
                        onValueChange = { newLanguageCode = it },
                        label = { Text(stringResource(R.string.unity_localization_language_code)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newLanguageName.isNotBlank() && newLanguageCode.isNotBlank(),
                    onClick = {
                        vm.planAddLocalizationLanguage(newLanguageName, newLanguageCode)
                        addLanguagePrompt = false
                    },
                ) { Text(stringResource(R.string.unity_localization_preview)) }
            },
            dismissButton = {
                TextButton(onClick = { addLanguagePrompt = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (signPrompt) {
        AlertDialog(
            onDismissRequest = { signPrompt = false },
            title = { Text(stringResource(R.string.unity_sign_copy)) },
            text = {
                Text(
                    stringResource(
                        if (state.outputNeedsSigning) R.string.unity_sign_missing_certificate
                        else R.string.unity_sign_prompt,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    signPrompt = false
                    state.output?.let(onSignApk)
                }) { Text(stringResource(R.string.common_sign_apk)) }
            },
            dismissButton = { TextButton(onClick = { signPrompt = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    fontRewritePrompt?.let { estimate ->
        AlertDialog(
            onDismissRequest = {
                fontRewritePrompt = null
                pendingRawFontTarget = null
            },
            title = { Text(stringResource(R.string.unity_font_rewrite_warning_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.unity_font_rewrite_warning_body,
                        isolated(estimate.bundleName),
                        Formatters.size(estimate.temporaryDiskBytes),
                        estimate.estimatedMinSeconds,
                        estimate.estimatedMaxSeconds,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    fontRewritePrompt = null
                    if (pendingRawFontTarget != null) {
                        rawFontTtfPicker.launch(arrayOf("*/*"))
                    } else {
                        fontTtfPicker.launch(arrayOf("*/*"))
                    }
                }) {
                    Text(
                        stringResource(
                            if (pendingRawFontTarget != null) {
                                R.string.unity_font_replace_raw
                            } else {
                                R.string.unity_font_enable_dynamic
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    fontRewritePrompt = null
                    pendingRawFontTarget = null
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
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
    onCollapse: () -> Unit,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onReplace: () -> Unit,
    onExportTexts: (() -> Unit)? = null,
    onInspectFonts: (() -> Unit)? = null,
    onBindDynamicFont: (() -> Unit)? = null,
    onReplaceRawFont: ((Long) -> Unit)? = null,
    fontReport: UnityFontReport? = null,
    textTransformOptions: UnityTextTransformOptions = UnityTextTransformOptions(),
    onToggleShapeArabic: ((Boolean) -> Unit)? = null,
    onToggleReverseBidi: ((Boolean) -> Unit)? = null,
    onToggleNormalizePersian: ((Boolean) -> Unit)? = null,
    textIssue: UnityTextUnavailableReason? = null,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    isolated(entry.name),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCollapse, enabled = !busy) {
                    Text(stringResource(R.string.common_close))
                }
            }
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
            if (fontReport != null) {
                Text(
                    stringResource(
                        R.string.unity_font_report_title,
                        fontReport.tmpFontAssets.size,
                        fontReport.rawFonts.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                fontReport.tmpFontAssets.forEach { tmp ->
                    Text(
                        isolated(tmp.capabilityStatement),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                fontReport.rawFonts.forEach { raw ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            isolated("${raw.name} (${Formatters.size(raw.embeddedFontBytes.toLong())})"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (onReplaceRawFont != null) {
                            TextButton(
                                onClick = { onReplaceRawFont(raw.pathId) },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.unity_font_replace_raw), maxLines = 1)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onOpen, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.unity_open_entry), maxLines = 1) }
                TextButton(onClick = onExport, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.unity_export_entry), maxLines = 1) }
                TextButton(onClick = onReplace, enabled = !busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.unity_replace_entry), maxLines = 1) }
            }
            if (onInspectFonts != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onInspectFonts, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.unity_inspect_fonts), maxLines = 1)
                    }
                    if (onBindDynamicFont != null && fontReport != null && fontReport.tmpFontAssets.isNotEmpty()) {
                        OutlinedButton(onClick = onBindDynamicFont, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.unity_font_enable_dynamic), maxLines = 1)
                        }
                    }
                }
            }
            if (onExportTexts != null) {
                if (onToggleShapeArabic != null && onToggleReverseBidi != null && onToggleNormalizePersian != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = textTransformOptions.shapeArabic,
                            onCheckedChange = onToggleShapeArabic,
                            enabled = !busy,
                        )
                        Text(
                            stringResource(R.string.unity_shape_arabic),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = textTransformOptions.reverseBidi,
                            onCheckedChange = onToggleReverseBidi,
                            enabled = !busy,
                        )
                        Text(
                            stringResource(R.string.unity_reverse_bidi),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = textTransformOptions.normalizePersian,
                            onCheckedChange = onToggleNormalizePersian,
                            enabled = !busy,
                        )
                        Text(
                            stringResource(R.string.unity_normalize_persian),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Button(onClick = onExportTexts, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(stringResource(R.string.unity_export_texts), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LocalizationActions(
    busy: Boolean,
    sources: List<LocalizationSource>,
    selected: LocalizationSource?,
    sourceLanguageCode: String?,
    targetLanguageCode: String?,
    rtlInference: UnityRtlFlagInference?,
    fontCoverage: UnityTranslationFontCoverage?,
    readiness: UnityTranslationReadiness,
    canValidateAndStage: Boolean,
    addPlan: LocalizationAddLanguagePlan?,
    importPlan: LocalizationImportPlan?,
    hasTsv: Boolean,
    textTransformOptions: UnityTextTransformOptions,
    onToggleShapeArabic: (Boolean) -> Unit,
    onToggleReverseBidi: (Boolean) -> Unit,
    onToggleNormalizePersian: (Boolean) -> Unit,
    onFind: () -> Unit,
    onDiscoverSource: () -> Unit,
    onSelectTable: (String) -> Unit,
    onSelectSourceLanguage: (String) -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onInspectFont: () -> Unit,
    onExportPackage: () -> Unit,
    onExportAll: () -> Unit,
    onExportLanguage: (String) -> Unit,
    onAddLanguage: () -> Unit,
    onImportTsv: () -> Unit,
    onApply: () -> Unit,
) {
    var targetPrompt by remember(selected?.origin?.tableId) { mutableStateOf(false) }
    var readinessPrompt by remember(selected?.origin?.tableId) { mutableStateOf(false) }
    val performReadinessAction: (UnityTranslationNextAction) -> Unit = { action ->
        when (action) {
            UnityTranslationNextAction.DISCOVER_TABLES -> onFind()
            UnityTranslationNextAction.SELECT_TARGET_LANGUAGE -> targetPrompt = true
            UnityTranslationNextAction.INSPECT_OR_REPLACE_FONT -> onInspectFont()
            UnityTranslationNextAction.USE_SUPPORTED_COMPRESSION -> onDiscoverSource()
            UnityTranslationNextAction.PREPARE_TRANSLATION -> {
                if (hasTsv) onImportTsv()
                else targetLanguageCode?.let(onExportLanguage) ?: run { targetPrompt = true }
            }
            UnityTranslationNextAction.VALIDATE_AND_STAGE,
            UnityTranslationNextAction.REVIEW_ROUND_TRIP_FAILURE -> onApply()
            UnityTranslationNextAction.EXPORT_OUTPUT -> onExportPackage()
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.unity_localization_title), style = MaterialTheme.typography.titleSmall)
            if (sources.isEmpty()) {
                OutlinedButton(onClick = onFind, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unity_localization_find))
                }
            } else {
                Text(
                    stringResource(
                        R.string.unity_localization_tables_summary,
                        sources.size,
                        sources.sumOf { it.terms.size },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(sources, key = { it.origin.tableId }) { source ->
                        if (source.origin.tableId == selected?.origin?.tableId) {
                            Button(onClick = { onSelectTable(source.origin.tableId) }, enabled = !busy) {
                                Text(source.name, maxLines = 1)
                            }
                        } else {
                            OutlinedButton(onClick = { onSelectTable(source.origin.tableId) }, enabled = !busy) {
                                Text(source.name, maxLines = 1)
                            }
                        }
                    }
                }
                selected?.let { table ->
                    Text(
                        stringResource(
                            R.string.unity_localization_table_detail,
                            table.languages.size,
                            table.terms.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        stringResource(R.string.unity_localization_source_language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(table.languages, key = { it.code }) { language ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                TextButton(
                                    onClick = { onSelectSourceLanguage(language.code) },
                                    enabled = !busy,
                                ) {
                                    Text(
                                        if (language.code == sourceLanguageCode) "✓ ${language.code}" else language.code,
                                        maxLines = 1,
                                    )
                                }
                                TextButton(
                                    onClick = { onExportLanguage(language.code) },
                                    enabled = !busy,
                                ) {
                                    Text(stringResource(R.string.unity_localization_export_one_short), maxLines = 1)
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { targetPrompt = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (targetLanguageCode == null) {
                                stringResource(R.string.unity_localization_choose_target)
                            } else {
                                stringResource(R.string.unity_localization_target_selected, targetLanguageCode)
                            },
                            maxLines = 1,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = textTransformOptions.shapeArabic,
                                onCheckedChange = onToggleShapeArabic,
                                enabled = !busy,
                            )
                            Text(stringResource(R.string.unity_shape_arabic), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = textTransformOptions.reverseBidi,
                                onCheckedChange = onToggleReverseBidi,
                                enabled = !busy,
                            )
                            Text(stringResource(R.string.unity_reverse_bidi), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = textTransformOptions.normalizePersian,
                            onCheckedChange = onToggleNormalizePersian,
                            enabled = !busy,
                        )
                        Text(stringResource(R.string.unity_normalize_persian), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onExportAll, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.unity_localization_export_all), maxLines = 1)
                        }
                        OutlinedButton(onClick = onAddLanguage, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.unity_localization_add_language), maxLines = 1)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onImportTsv,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.unity_localization_import_tsv), maxLines = 1)
                        }
                        Button(
                            onClick = onApply,
                            enabled = !busy && canValidateAndStage,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.unity_localization_apply), maxLines = 1)
                        }
                    }
                    addPlan?.let { plan ->
                        Text(
                            stringResource(
                                R.string.unity_localization_add_preview,
                                plan.termsUpdated,
                                plan.estimatedDataDeltaBytes,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (plan.canApply) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    importPlan?.let { plan ->
                        Text(
                            stringResource(
                                R.string.unity_localization_import_preview,
                                plan.changes.size,
                                plan.unknownKeyCount,
                                plan.missingKeyCount,
                                plan.emptyCellCount,
                                plan.placeholderFailureCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (plan.canApply) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    rtlInference?.let { inference ->
                        Text(
                            if (inference.status == UnityRtlInferenceStatus.DETERMINED) {
                                stringResource(
                                    R.string.unity_rtl_flag_determined,
                                    "0x${inference.bitMask!!.toUInt().toString(16)}",
                                    inference.targetFlags!!,
                                )
                            } else {
                                stringResource(R.string.unity_rtl_flag_not_determined)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (inference.status == UnityRtlInferenceStatus.DETERMINED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                        )
                    }
                    fontCoverage?.let { coverage ->
                        val missing = coverage.missingCodepoints.take(32).joinToString(
                            separator = ", ",
                            postfix = if (coverage.missingCount > 32) ", …" else "",
                        ) {
                            "U+${it.toString(16).uppercase().padStart(4, '0')}"
                        }
                        Text(
                            when {
                                coverage.complete -> stringResource(
                                    R.string.unity_font_coverage_ready,
                                    coverage.targetCodepointCount,
                                )
                                coverage.unavailableReason != null -> unityTextIssueLabel(
                                    coverage.unavailableReason,
                                )
                                else -> stringResource(
                                    R.string.unity_font_coverage_missing,
                                    coverage.missingCount,
                                    missing,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (coverage.complete) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedButton(
                        onClick = { readinessPrompt = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.unity_readiness_next_action,
                                unityReadinessActionLabel(readiness.nextAction),
                            ),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }

    if (targetPrompt && selected != null) {
        AlertDialog(
            onDismissRequest = { targetPrompt = false },
            title = { Text(stringResource(R.string.unity_localization_choose_target)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(selected.languages, key = { it.code }) { language ->
                        TextButton(
                            onClick = {
                                onSelectTargetLanguage(language.code)
                                targetPrompt = false
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${language.name} (${language.code})")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { targetPrompt = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (readinessPrompt) {
        AlertDialog(
            onDismissRequest = { readinessPrompt = false },
            title = { Text(stringResource(R.string.unity_readiness_title)) },
            text = {
                UnityTranslationReadinessPanel(
                    readiness = readiness,
                    busy = busy,
                    onNextAction = { action ->
                        readinessPrompt = false
                        performReadinessAction(action)
                    },
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { readinessPrompt = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
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
    UnityTextUnavailableReason.FONT_NO_GLYPHS -> stringResource(R.string.unity_text_font_no_glyphs)
    UnityTextUnavailableReason.FONT_STATIC_NO_SOURCE -> stringResource(R.string.unity_text_font_static_no_source)
    UnityTextUnavailableReason.FONT_ATLAS_FULL -> stringResource(R.string.unity_text_font_atlas_full)
    UnityTextUnavailableReason.FONT_ASSET_NOT_FOUND -> stringResource(R.string.unity_text_font_not_found)
    UnityTextUnavailableReason.LOCALIZATION_TABLE_NOT_FOUND -> stringResource(R.string.unity_localization_not_found)
    UnityTextUnavailableReason.LOCALIZATION_SHAPE_AMBIGUOUS -> stringResource(R.string.unity_localization_shape_ambiguous)
    UnityTextUnavailableReason.LOCALIZATION_ALIGNMENT_INVALID -> stringResource(R.string.unity_localization_alignment_invalid)
    UnityTextUnavailableReason.LOCALIZATION_TSV_INVALID -> stringResource(R.string.unity_localization_tsv_invalid)
    UnityTextUnavailableReason.LOCALIZATION_CONFLICT -> stringResource(R.string.unity_localization_conflict)
    UnityTextUnavailableReason.LOCALIZATION_SIZE_GUARD -> stringResource(R.string.unity_localization_size_guard)
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
