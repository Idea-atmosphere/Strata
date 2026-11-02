package dev.cluvex.strata.feature.unity

import android.content.ActivityNotFoundException
import android.net.Uri
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
import androidx.compose.foundation.layout.height
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
import dev.cluvex.strata.core.unity.LocalizationImportIssueKind
import dev.cluvex.strata.core.unity.LocalizationImportPlan
import dev.cluvex.strata.core.unity.LocalizationSource
import dev.cluvex.strata.core.unity.UnifiedTablePlan
import dev.cluvex.strata.core.unity.UnityUnifiedLocalization
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
    var storeUncompressed by remember(path) { mutableStateOf(false) }
    var singleBlock by remember(path) { mutableStateOf(false) }
    var pendingRawFontTarget by remember(path) { mutableStateOf<Long?>(null) }
    var pendingRawFontMerge by remember(path) { mutableStateOf(false) }
    var rawFontModePrompt by remember(path) { mutableStateOf<Long?>(null) }
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
            vm.applyRawFont(entry, uri, context.contentResolver, pendingRawFontTarget, pendingRawFontMerge)
        }
        pendingRawFontTarget = null
        pendingRawFontMerge = false
    }

    // Persian Apply pickers (Phase 4 part 4, global, not per-entry:
    // the I2 entry comes from the selected table, not from a tapped
    // row). All use "*/*": base .bin, scene .bundle and TTF files
    // have no reliable picker mime type, so filtering would hide them.
    val persianBasePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setPersianBaseFallbackUri(uri)
    }

    val persianScenePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setPersianSceneBundleUri(uri)
    }

    val persianFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setPersianFontUri(uri)
    }

    val persianBaseExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = state.persianPatchedBaseFile
        if (uri != null && file != null) {
            scope.launch {
                localBusy = true
                try {
                    withContext(Dispatchers.IO) {
                        val target = context.contentResolver.openOutputStream(uri) ?: throw IOException("Export destination unavailable")
                        target.use { out -> file.inputStream().buffered().use { it.copyTo(out) } }
                    }
                    vm.notifyMessage(context.getString(R.string.unity_persian_base_export_done, uri.lastPathSegment ?: file.name))
                } catch (_: Exception) {
                    vm.notifyMessage(context.getString(R.string.unity_operation_failed))
                } finally {
                    localBusy = false
                }
            }
        }
    }

    // Phase 5 prefab repoint pickers (global, like the Persian
    // Apply pickers): the prefab file is a standalone serialized
    // .bin, so OpenDocument "*/*" is used again (no reliable mime
    // type). The export picker mirrors persianBaseExportPicker.
    val persianPrefabPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setPersianPrefabUri(uri)
    }

    val persianPrefabExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = state.persianPrefabPatchedFile
        if (uri != null && file != null) {
            scope.launch {
                localBusy = true
                try {
                    withContext(Dispatchers.IO) {
                        val target = context.contentResolver.openOutputStream(uri) ?: throw IOException("Export destination unavailable")
                        target.use { out -> file.inputStream().buffered().use { it.copyTo(out) } }
                    }
                    vm.notifyMessage(context.getString(R.string.unity_persian_prefab_export_done, uri.lastPathSegment ?: file.name))
                } catch (_: Exception) {
                    vm.notifyMessage(context.getString(R.string.unity_operation_failed))
                } finally {
                    localBusy = false
                }
            }
        }
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
                    // One LazyColumn for the whole page: the entries
                    // list must NOT be a weight(1f) list with the
                    // Persian/repack sections after it -- that pushed
                    // them below the bottom edge with no scroll to
                    // reach them. Everything below scrolls as items.
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item(key = "summary") { BundleSummaryCard(document) }
                        item(key = "output-note") {
                            if (parents.isNotEmpty()) {
                                Text(stringResource(R.string.unity_parent_copy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                            } else {
                                Text(stringResource(R.string.unity_output_copy_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                            }
                        }
                        if (document.summary.entries.isEmpty()) {
                            item(key = "no-entries") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.unity_no_entries), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
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
                                                    rawFontModePrompt = pathId
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
                                                onExportPackage = { vm.repack(parents, storeUncompressed, singleBlock) },
                                                onExportAll = { vm.exportLocalization() },
                                                onExportLanguage = vm::exportLocalization,
                                                onAddLanguage = {
                                                    newLanguageName = ""
                                                    newLanguageCode = ""
                                                    addLanguagePrompt = true
                                                },
                                                onImportTsv = {
                                                    try {
                                                        tsvImportPicker.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
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
                        item(key = "unified") {
                            val allSources = state.localizationSources.values.flatten()
                            // Default the source language to the first
                            // language of the first table, like the
                            // per-table flow does; the target stays
                            // user-picked (no guessing which language
                            // to edit).
                            val firstCode = allSources.firstOrNull()?.languages?.firstOrNull()?.code
                            LaunchedEffect(state.unifiedSourceCode, firstCode) {
                                if (state.unifiedSourceCode == null && firstCode != null) {
                                    vm.setUnifiedSourceLanguage(firstCode)
                                }
                            }
                            if (allSources.isNotEmpty()) {
                                val unifiedPlans = remember(
                                    state.localizationSources, state.unifiedEdits,
                                    state.unifiedSourceCode, state.unifiedTargetCode,
                                    state.textTransformOptions,
                                ) { vm.unifiedPlans() }
                                val sharedCounts = remember(state.localizationSources) {
                                    UnityUnifiedLocalization.sharedTableCounts(allSources)
                                }
                                UnifiedLocalizationActions(
                                    busy = busy,
                                    sourcesByEntry = state.localizationSources,
                                    unifiedEdits = state.unifiedEdits,
                                    unifiedSourceCode = state.unifiedSourceCode,
                                    unifiedTargetCode = state.unifiedTargetCode,
                                    plans = unifiedPlans,
                                    sharedCounts = sharedCounts,
                                    unifiedStatusText = state.unifiedStatusText,
                                    onSelectSourceLanguage = vm::setUnifiedSourceLanguage,
                                    onSelectTargetLanguage = vm::setUnifiedTargetLanguage,
                                    onSetEdit = vm::setUnifiedEdit,
                                    onClearEdits = vm::clearUnifiedEdits,
                                    onApply = vm::applyUnifiedEdits,
                                )
                            }
                        }
                        item(key = "persian") {
                            val selectedPersianTable = state.localizationSources.values.flatten()
                                .firstOrNull { it.origin.tableId == state.selectedLocalizationTableId }
                            val persianEntry = document.summary.entries
                                .firstOrNull { it.name == selectedPersianTable?.origin?.entryName }
                            PersianModeActions(
                                busy = busy,
                                persianMode = state.persianMode,
                                persianTerms = state.persianTerms,
                                persianReady = state.persianReady,
                                persianIssues = state.persianIssues,
                                persianStatsText = state.persianStatsText,
                                persianBaseFallbackUri = state.persianBaseFallbackUri,
                                persianSceneBundleUri = state.persianSceneBundleUri,
                                persianFontUri = state.persianFontUri,
                                persianApplied = state.persianApplied,
                                persianPatchedBaseFile = state.persianPatchedBaseFile,
                                persianPatchedBaseCrc = state.persianPatchedBaseCrc,
                                persianEntry = persianEntry,
                                onTogglePersianMode = vm::setPersianMode,
                                onLoadTerms = vm::loadPersianTermsFromImportPlan,
                                onCheckReadiness = vm::checkPersianReadiness,
                                onSetPersianTerm = vm::setPersianTerm,
                                onPickBaseFallback = { persianBasePicker.launch(arrayOf("*/*")) },
                                onPickSceneBundle = { persianScenePicker.launch(arrayOf("*/*")) },
                                onPickFont = { persianFontPicker.launch(arrayOf("*/*")) },
                                onApplyPersian = { entry -> vm.applyPersianPlan(entry, context.contentResolver) },
                                onClearPersianApply = vm::clearPersianApply,
                                onExportPersianBase = { persianBaseExportPicker.launch("persian-fallback.bin") },
                            )
                        }
                        item(key = "persian-prefab") {
                            // Phase 5: static-prefab repoint. Shown after the
                            // Persian section, not inside it: it does not need
                            // persianMode or an open document (the prefab file
                            // is standalone), and it is the step that makes
                            // statically-fonted menus render Persian at all.
                            PersianPrefabRepointActions(
                                busy = busy,
                                prefabUri = state.persianPrefabUri,
                                sourceGuid = state.persianPrefabSourceGuid,
                                targetGuid = state.persianPrefabTargetGuid,
                                pptrFromFile = state.persianPrefabPptrFromFile,
                                pptrFromPath = state.persianPrefabPptrFromPath,
                                pptrToFile = state.persianPrefabPptrToFile,
                                pptrToPath = state.persianPrefabPptrToPath,
                                patchedFile = state.persianPrefabPatchedFile,
                                patchedCrc = state.persianPrefabPatchedCrc,
                                report = state.persianPrefabReport,
                                onPickPrefab = { persianPrefabPicker.launch(arrayOf("*/*")) },
                                onSourceGuidChange = vm::setPersianPrefabSourceGuid,
                                onTargetGuidChange = vm::setPersianPrefabTargetGuid,
                                onPptrFromFileChange = vm::setPersianPrefabPptrFromFile,
                                onPptrFromPathChange = vm::setPersianPrefabPptrFromPath,
                                onPptrToFileChange = vm::setPersianPrefabPptrToFile,
                                onPptrToPathChange = vm::setPersianPrefabPptrToPath,
                                onRepoint = { vm.repointPersianPrefab(context.contentResolver) },
                                onClear = vm::clearPersianPrefab,
                                onExport = { persianPrefabExportPicker.launch("persian-prefab.bin") },
                            )
                        }
                        item(key = "repack") {
                            val changedCount = state.replacements.size + state.editedFiles.keys.count { it !in state.replacements }
                            if (changedCount > 0) {
                                Text(
                                    stringResource(R.string.unity_replacements_count, changedCount),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { storeUncompressed = !storeUncompressed },
                            ) {
                                Checkbox(checked = storeUncompressed, onCheckedChange = { storeUncompressed = it })
                                Text(
                                    stringResource(R.string.unity_repack_store_uncompressed),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { singleBlock = !singleBlock },
                            ) {
                                Checkbox(checked = singleBlock, onCheckedChange = { singleBlock = it })
                                Text(
                                    stringResource(R.string.unity_repack_single_block),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Button(
                                onClick = { vm.repack(parents, storeUncompressed, singleBlock) },
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

    rawFontModePrompt?.let {
        val launchPicker = { merge: Boolean ->
            rawFontModePrompt = null
            pendingRawFontMerge = merge
            val estimate = vm.estimateRewrite()
            if (estimate != null) {
                fontRewritePrompt = estimate
            } else {
                rawFontTtfPicker.launch(arrayOf("*/*"))
            }
        }
        AlertDialog(
            onDismissRequest = {
                rawFontModePrompt = null
                pendingRawFontTarget = null
            },
            title = { Text(stringResource(R.string.unity_font_mode_title)) },
            text = { Text(stringResource(R.string.unity_font_mode_body)) },
            confirmButton = {
                TextButton(onClick = { launchPicker(false) }) {
                    Text(stringResource(R.string.unity_font_mode_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = { launchPicker(true) }) {
                    Text(stringResource(R.string.unity_font_mode_merge))
                }
            },
        )
    }

    fontRewritePrompt?.let { estimate ->
        AlertDialog(
            onDismissRequest = {
                fontRewritePrompt = null
                pendingRawFontTarget = null
                pendingRawFontMerge = false
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
                                if (pendingRawFontMerge) {
                                    R.string.unity_font_mode_merge
                                } else {
                                    R.string.unity_font_replace_raw
                                }
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
                    pendingRawFontMerge = false
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
private fun PersianModeActions(
    busy: Boolean,
    persianMode: Boolean,
    persianTerms: Map<String, String>,
    persianReady: Boolean,
    persianIssues: List<String>,
    persianStatsText: String?,
    persianBaseFallbackUri: Uri?,
    persianSceneBundleUri: Uri?,
    persianFontUri: Uri?,
    persianApplied: Boolean,
    persianPatchedBaseFile: File?,
    persianPatchedBaseCrc: String?,
    persianEntry: UnityBundleEntry?,
    onTogglePersianMode: (Boolean) -> Unit,
    onLoadTerms: () -> Unit,
    onCheckReadiness: () -> Unit,
    onSetPersianTerm: (String, String) -> Unit,
    onPickBaseFallback: () -> Unit,
    onPickSceneBundle: () -> Unit,
    onPickFont: () -> Unit,
    onApplyPersian: (UnityBundleEntry) -> Unit,
    onClearPersianApply: () -> Unit,
    onExportPersianBase: () -> Unit,
) {
    // Term editor is collapsed by default (remember, no key, so it
    // stays open across term edits; a keyed remember would collapse
    // on every keystroke). LazyColumn with a fixed height scrolls
    // independently and composes only visible terms, so large tables
    // (e.g. subtitles, 397 terms) do not compose 397 TextFields at
    // once. Term editing in v1 is via TSV import + Load terms, plus
    // this in-app editor; full baking / patching (base fallback +
    // font) is the Apply section below (Phase 4 part 4). The
    // Check readiness button above is preliminary only (shaping /
    // mapping / ranking); Apply runs the full pipeline.
    var showTerms by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = persianMode,
                    onCheckedChange = onTogglePersianMode,
                    enabled = !busy,
                )
                Text(stringResource(R.string.unity_persian_title), style = MaterialTheme.typography.titleSmall)
            }
            if (persianMode) {
                Text(
                    stringResource(R.string.unity_persian_note_transforms_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onLoadTerms, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unity_persian_load_terms), maxLines = 1)
                }
                Button(
                    onClick = onCheckReadiness,
                    enabled = !busy && persianTerms.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.unity_persian_check_readiness), maxLines = 1)
                }
                persianStatsText?.let { stats ->
                    Text(
                        stats,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Preliminary note only when ready but NOT yet
                // applied: after a successful Apply, full baking
                // already happened, so this note would be wrong.
                if (persianReady && !persianApplied) {
                    Text(
                        stringResource(R.string.unity_persian_preliminary_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (issue in persianIssues) {
                    Text("• $issue", style = MaterialTheme.typography.bodySmall)
                }
                // Full Apply (Phase 4 part 4): pick the three files
                // first (stored in ViewModel, surviving rotation),
                // then Apply (needs the selected table's entry; if
                // no table is selected, the button stays disabled
                // with a hint, instead of staging into the wrong
                // bundle). Scene-bundle opening can take minutes
                // for large bundles; the busy spinner covers it in
                // v1 (no progress percentage yet).
                OutlinedButton(onClick = onPickBaseFallback, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (persianBaseFallbackUri != null) {
                            stringResource(R.string.unity_persian_picked_base)
                        } else {
                            stringResource(R.string.unity_persian_pick_base)
                        },
                        maxLines = 1,
                    )
                }
                OutlinedButton(onClick = onPickSceneBundle, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (persianSceneBundleUri != null) {
                            stringResource(R.string.unity_persian_picked_scene)
                        } else {
                            stringResource(R.string.unity_persian_pick_scene)
                        },
                        maxLines = 1,
                    )
                }
                OutlinedButton(onClick = onPickFont, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (persianFontUri != null) {
                            stringResource(R.string.unity_persian_picked_font)
                        } else {
                            stringResource(R.string.unity_persian_pick_font)
                        },
                        maxLines = 1,
                    )
                }
                if (persianEntry == null) {
                    Text(
                        stringResource(R.string.unity_persian_no_table),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { persianEntry?.let(onApplyPersian) },
                    enabled = !busy && persianTerms.isNotEmpty() && persianEntry != null &&
                        persianBaseFallbackUri != null && persianSceneBundleUri != null && persianFontUri != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.unity_persian_apply), maxLines = 1)
                }
                if (persianApplied && persianPatchedBaseFile != null) {
                    Text(
                        stringResource(
                            R.string.unity_persian_patched_base,
                            persianPatchedBaseFile.name,
                            persianPatchedBaseFile.length(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (persianPatchedBaseCrc != null) {
                        Text(
                            stringResource(R.string.unity_persian_patched_base_crc, persianPatchedBaseCrc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.unity_persian_base_install_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onExportPersianBase, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.unity_persian_export_base), maxLines = 1)
                    }
                    OutlinedButton(onClick = onClearPersianApply, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.unity_persian_clear_apply), maxLines = 1)
                    }
                }
                if (persianTerms.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showTerms = !showTerms },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (showTerms) {
                                stringResource(R.string.unity_persian_hide_terms)
                            } else {
                                stringResource(R.string.unity_persian_show_terms, persianTerms.size)
                            },
                            maxLines = 1,
                        )
                    }
                    if (showTerms) {
                        // Fixed height is REQUIRED: a LazyColumn inside
                        // a non-scrollable Column needs bounded height,
                        // otherwise it measures with infinite height
                        // (or overflows the screen). 400 dp shows a
                        // handful of terms and scrolls for the rest,
                        // without composing all terms at once.
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(400.dp).padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(persianTerms.entries.toList(), key = { it.key }) { (termKey, persianText) ->
                                OutlinedTextField(
                                    value = persianText,
                                    onValueChange = { onSetPersianTerm(termKey, it) },
                                    label = {
                                        Text(
                                            stringResource(R.string.unity_persian_term_label, termKey),
                                            maxLines = 1,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy,
                                    singleLine = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Phase 5: static-prefab repoint section. The prefab file is a
// standalone serialized .bin (not a bundle entry), so this section
// does not need persianMode or a selected table. GUID fields are
// the static font asset's external GUID and the Persian dynamic
// fallback's external GUID (32 lowercase hex each, validated by
// the module). PPtr fields are optional: all four blank means
// GUID-only repoint (Bendy warnings); all four filled means one
// material PPtr remap as well (Bendy subtitles: from (4, 1) to
// (3, 1)). A partially filled PPtr is blocked by the ViewModel.
@Composable
private fun PersianPrefabRepointActions(
    busy: Boolean,
    prefabUri: Uri?,
    sourceGuid: String,
    targetGuid: String,
    pptrFromFile: String,
    pptrFromPath: String,
    pptrToFile: String,
    pptrToPath: String,
    patchedFile: File?,
    patchedCrc: String?,
    report: String?,
    onPickPrefab: () -> Unit,
    onSourceGuidChange: (String) -> Unit,
    onTargetGuidChange: (String) -> Unit,
    onPptrFromFileChange: (String) -> Unit,
    onPptrFromPathChange: (String) -> Unit,
    onPptrToFileChange: (String) -> Unit,
    onPptrToPathChange: (String) -> Unit,
    onRepoint: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.unity_persian_prefab_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.unity_persian_prefab_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onPickPrefab, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (prefabUri != null) {
                        stringResource(R.string.unity_persian_prefab_picked)
                    } else {
                        stringResource(R.string.unity_persian_prefab_pick)
                    },
                    maxLines = 1,
                )
            }
            OutlinedTextField(
                value = sourceGuid,
                onValueChange = onSourceGuidChange,
                label = { Text(stringResource(R.string.unity_persian_prefab_source_guid), maxLines = 1) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = targetGuid,
                onValueChange = onTargetGuidChange,
                label = { Text(stringResource(R.string.unity_persian_prefab_target_guid), maxLines = 1) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = pptrFromFile,
                onValueChange = onPptrFromFileChange,
                label = { Text(stringResource(R.string.unity_persian_prefab_pptr_from_file), maxLines = 1) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = pptrFromPath,
                onValueChange = onPptrFromPathChange,
                label = { Text(stringResource(R.string.unity_persian_prefab_pptr_from_path), maxLines = 1) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = pptrToFile,
                onValueChange = onPptrToFileChange,
                label = { Text(stringResource(R.string.unity_persian_prefab_pptr_to_file), maxLines = 1) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                value = pptrToPath,
                onValueChange = onPptrToPathChange,
                label = { Text(stringResource(R.string.unity_persian_prefab_pptr_to_path), maxLines = 1) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
            )
            Button(
                onClick = onRepoint,
                enabled = !busy && prefabUri != null && sourceGuid.isNotBlank() && targetGuid.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.unity_persian_prefab_repoint), maxLines = 1)
            }
            report?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (patchedFile != null) {
                Text(
                    stringResource(
                        R.string.unity_persian_prefab_patched,
                        patchedFile.name,
                        patchedFile.length(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (patchedCrc != null) {
                    Text(
                        stringResource(R.string.unity_persian_prefab_crc, patchedCrc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.unity_persian_prefab_install_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unity_persian_prefab_export), maxLines = 1)
                }
                OutlinedButton(onClick = onClear, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.unity_persian_prefab_clear), maxLines = 1)
                }
            }
        }
    }
}

private data class UnifiedRowItem(
    val tableName: String,
    val key: String,
    val sourceValue: String,
    val targetValue: String,
    val isEdited: Boolean,
    val sharedCount: Int,
    val warned: Boolean,
)

// Unified multi-table editing: every discovered table on one page.
// One edit map (term key -> new target value) is shared by all
// tables, so a key present in several tables is edited in all of
// them at once; Apply writes the edits into every affected table
// (grouped by bundle entry, round-trip verified per entry) through
// the same overlay mechanism as the per-table apply.
@Composable
private fun UnifiedLocalizationActions(
    busy: Boolean,
    sourcesByEntry: Map<String, List<LocalizationSource>>,
    unifiedEdits: Map<String, String>,
    unifiedSourceCode: String?,
    unifiedTargetCode: String?,
    plans: List<UnifiedTablePlan>,
    sharedCounts: Map<String, Int>,
    unifiedStatusText: String?,
    onSelectSourceLanguage: (String) -> Unit,
    onSelectTargetLanguage: (String) -> Unit,
    onSetEdit: (String, String) -> Unit,
    onClearEdits: () -> Unit,
    onApply: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var tableFilter by remember { mutableStateOf<String?>(null) }
    var onlyEdited by remember { mutableStateOf(false) }

    val allSources = sourcesByEntry.values.flatten()
    val allCodes = remember(allSources) {
        val ordered = ArrayList<String>()
        for (source in allSources) {
            for (language in source.languages) {
                if (language.code !in ordered) ordered += language.code
            }
        }
        ordered
    }
    val tableNames = remember(allSources) { allSources.map { it.name }.distinct() }
    val warnedKeys = remember(plans) {
        plans.flatMap { it.plan.issues }
            .filter { it.kind == LocalizationImportIssueKind.PLACEHOLDER_MISMATCH }
            .mapNotNull { it.key }.toSet()
    }
    val rows = remember(
        allSources, unifiedEdits, unifiedSourceCode, unifiedTargetCode,
        search, tableFilter, onlyEdited,
    ) {
        val list = ArrayList<UnifiedRowItem>()
        val query = search.trim()
        for (source in allSources) {
            if (tableFilter != null && source.name != tableFilter) continue
            val sourceIndex = unifiedSourceCode?.let { source.languageIndex(it) } ?: -1
            val targetIndex = unifiedTargetCode?.let { source.languageIndex(it) } ?: -1
            for (term in source.terms) {
                val originalTarget = if (targetIndex >= 0) term.values.getOrNull(targetIndex) ?: "" else ""
                val targetValue = unifiedEdits[term.key] ?: originalTarget
                val isEdited = term.key in unifiedEdits && unifiedEdits[term.key] != originalTarget
                if (onlyEdited && !isEdited) continue
                val sourceValue = if (sourceIndex >= 0) term.values.getOrNull(sourceIndex) ?: "" else ""
                if (query.isNotEmpty() &&
                    !term.key.contains(query, ignoreCase = true) &&
                    !sourceValue.contains(query, ignoreCase = true) &&
                    !targetValue.contains(query, ignoreCase = true)
                ) continue
                list += UnifiedRowItem(
                    tableName = source.name,
                    key = term.key,
                    sourceValue = sourceValue,
                    targetValue = targetValue,
                    isEdited = isEdited,
                    sharedCount = sharedCounts[term.key] ?: 1,
                    warned = term.key in warnedKeys,
                )
            }
        }
        list
    }
    val distinctKeys = remember(allSources) {
        allSources.flatMap { it.terms }.map { it.key }.toSet().size
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.unity_unified_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.unity_unified_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.unity_unified_source_label), style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(allCodes, key = { "src:$it" }) { code ->
                    if (code == unifiedSourceCode) {
                        Button(onClick = { onSelectSourceLanguage(code) }, enabled = !busy) { Text(code, maxLines = 1) }
                    } else {
                        OutlinedButton(onClick = { onSelectSourceLanguage(code) }, enabled = !busy) { Text(code, maxLines = 1) }
                    }
                }
            }
            Text(stringResource(R.string.unity_unified_target_label), style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(allCodes, key = { "tgt:$it" }) { code ->
                    if (code == unifiedTargetCode) {
                        Button(onClick = { onSelectTargetLanguage(code) }, enabled = !busy) { Text(code, maxLines = 1) }
                    } else {
                        OutlinedButton(onClick = { onSelectTargetLanguage(code) }, enabled = !busy) { Text(code, maxLines = 1) }
                    }
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text(stringResource(R.string.unity_unified_search_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    if (tableFilter == null) {
                        Button(onClick = { tableFilter = null }, enabled = !busy) {
                            Text(stringResource(R.string.unity_unified_table_all), maxLines = 1)
                        }
                    } else {
                        OutlinedButton(onClick = { tableFilter = null }, enabled = !busy) {
                            Text(stringResource(R.string.unity_unified_table_all), maxLines = 1)
                        }
                    }
                }
                items(tableNames, key = { "table:$it" }) { name ->
                    if (name == tableFilter) {
                        Button(onClick = { tableFilter = name }, enabled = !busy) { Text(name, maxLines = 1) }
                    } else {
                        OutlinedButton(onClick = { tableFilter = name }, enabled = !busy) { Text(name, maxLines = 1) }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onlyEdited = !onlyEdited },
            ) {
                Checkbox(checked = onlyEdited, onCheckedChange = { onlyEdited = it }, enabled = !busy)
                Text(stringResource(R.string.unity_unified_only_edited), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(
                    R.string.unity_unified_stats,
                    allSources.size,
                    allSources.sumOf { it.terms.size },
                    distinctKeys,
                    unifiedEdits.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Fixed height is REQUIRED: a LazyColumn inside a
            // non-scrollable Column needs bounded height, otherwise
            // it measures with infinite height (or overflows the
            // screen). 480 dp shows a handful of terms and scrolls
            // for the rest, without composing all terms at once.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(480.dp).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(rows, key = { "${it.tableName}:${it.key}" }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                row.tableName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            if (row.sharedCount > 1) {
                                Text(
                                    stringResource(R.string.unity_unified_shared_badge, row.sharedCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (row.isEdited) {
                                Text(
                                    stringResource(R.string.unity_unified_edited_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            if (row.warned) {
                                Text(
                                    stringResource(R.string.unity_unified_placeholder_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = row.targetValue,
                            onValueChange = { onSetEdit(row.key, it) },
                            label = { Text(row.key, maxLines = 1) },
                            supportingText = if (unifiedSourceCode != null) {
                                {
                                    Text(
                                        row.sourceValue,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && unifiedTargetCode != null,
                            singleLine = false,
                        )
                    }
                }
            }
            for (plan in plans) {
                Text(
                    stringResource(
                        R.string.unity_unified_table_summary,
                        plan.source.name,
                        plan.plan.changes.size,
                        plan.plan.placeholderFailureCount,
                        plan.plan.emptyCellCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unifiedStatusText != null) {
                Text(
                    unifiedStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApply,
                    enabled = !busy && plans.any { it.plan.canApply },
                ) {
                    Text(stringResource(R.string.unity_unified_apply))
                }
                OutlinedButton(
                    onClick = onClearEdits,
                    enabled = !busy && unifiedEdits.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.unity_unified_clear))
                }
            }
            Text(
                stringResource(R.string.unity_unified_plain_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
