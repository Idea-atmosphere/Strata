package dev.cluvex.strata.feature.unity

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ArchiveParent
import dev.cluvex.strata.core.unity.LocalizationAddLanguagePlan
import dev.cluvex.strata.core.unity.LocalizationImportPlan
import dev.cluvex.strata.core.unity.LocalizationLanguage
import dev.cluvex.strata.core.unity.LocalizationSource
import dev.cluvex.strata.core.unity.TypeTreeCodec
import dev.cluvex.strata.core.unity.UnityBundleDocument
import dev.cluvex.strata.core.unity.UnityBundleEntry
import dev.cluvex.strata.core.unity.UnityBundleReader
import dev.cluvex.strata.core.unity.UnityBundleRewriteEstimate
import dev.cluvex.strata.core.unity.UnityDocumentClosedException
import dev.cluvex.strata.core.unity.UnityFontPatchResult
import dev.cluvex.strata.core.unity.UnityFontReport
import dev.cluvex.strata.core.unity.UnityFormatException
import dev.cluvex.strata.core.unity.UnityOperationCancelledException
import dev.cluvex.strata.core.unity.UnityPackageRepacker
import dev.cluvex.strata.core.unity.UnityLocalizationInspection
import dev.cluvex.strata.core.unity.UnityLocalizationOperations
import dev.cluvex.strata.core.unity.UnityLocalizationTsv
import dev.cluvex.strata.core.unity.UnityProbe
import dev.cluvex.strata.core.unity.UnityTextInfo
import dev.cluvex.strata.core.unity.UnityTextInspection
import dev.cluvex.strata.core.unity.UnityTextTransformOptions
import dev.cluvex.strata.core.unity.UnityTextUnavailableReason
import dev.cluvex.strata.core.unity.UnityUnsupportedVersionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class UnityAssetUiState(
    val path: String = "",
    val loading: Boolean = true,
    val busy: Boolean = false,
    val probe: UnityProbe? = null,
    val document: UnityBundleDocument? = null,
    val error: String? = null,
    val selectedName: String? = null,
    val workingFiles: Map<String, File> = emptyMap(),
    val editedFiles: Map<String, File> = emptyMap(),
    val replacements: Map<String, File> = emptyMap(),
    val textInfos: Map<String, UnityTextInfo> = emptyMap(),
    val textIssues: Map<String, UnityTextUnavailableReason> = emptyMap(),
    val fontReports: Map<String, UnityFontReport> = emptyMap(),
    val localizationSources: Map<String, List<LocalizationSource>> = emptyMap(),
    val selectedLocalizationTableId: String? = null,
    val localizationAddPlan: LocalizationAddLanguagePlan? = null,
    val localizationImportPlan: LocalizationImportPlan? = null,
    val localizationTsv: File? = null,
    val localizationSourceLanguageCode: String? = null,
    val textTransformOptions: UnityTextTransformOptions = UnityTextTransformOptions(),
    val exportedTexts: Map<String, File> = emptyMap(),
    val navigateTo: String? = null,
    val output: File? = null,
    val message: String? = null,
)

class UnityAssetViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UnityAssetUiState())
    val state: StateFlow<UnityAssetUiState> = _state.asStateFlow()

    private var document: UnityBundleDocument? = null
    private var stageDir: File? = null
    private var workingBaselines: Map<String, Pair<Long, Long>> = emptyMap()
    private var exportedBaselines: Map<String, Pair<Long, Long>> = emptyMap()
    private val inspectingNames = HashSet<String>()
    private val localizationInspectingNames = HashSet<String>()

    fun open(path: String) {
        val current = _state.value
        if (current.path == path && (current.loading || current.probe != null || current.error != null)) return

        document?.close()
        stageDir?.deleteRecursively()
        document = null
        workingBaselines = emptyMap()
        exportedBaselines = emptyMap()
        inspectingNames.clear()
        val newStageDir = File(getApplication<Application>().cacheDir, "unity-editor/${path.hashCode().toUInt().toString(16)}-${System.nanoTime()}")
        newStageDir.mkdirs()
        stageDir = newStageDir
        _state.value = UnityAssetUiState(path = path, loading = true)

        viewModelScope.launch {
            try {
                val (probe, parsed) = runInterruptible(Dispatchers.IO) {
                    val file = File(path)
                    val probe = UnityBundleReader.probe(file)
                    val parsed = if (probe.bundleSupported) UnityBundleReader.open(file, getApplication<Application>().cacheDir) else null
                    probe to parsed
                }
                document = parsed
                _state.update {
                    it.copy(
                        loading = false,
                        probe = probe,
                        document = parsed,
                    )
                }
            } catch (e: CancellationException) {
                document?.close()
                document = null
                throw e
            } catch (e: Exception) {
                document?.close()
                document = null
                val message = if (e is UnityUnsupportedVersionException) {
                    getApplication<Application>().getString(R.string.unity_unsupported_version)
                } else {
                    getApplication<Application>().getString(R.string.unity_load_failed)
                }
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    fun select(name: String?) {
        _state.update { it.copy(selectedName = name) }
        if (name == null || name in _state.value.textInfos || name in _state.value.textIssues) return
        val doc = document ?: return
        val entry = doc.summary.entries.firstOrNull { it.name == name } ?: return
        if (!inspectingNames.add(name)) return
        viewModelScope.launch {
            try {
                val inspection = runInterruptible(Dispatchers.IO) { doc.inspectText(entry) }
                if (document !== doc) return@launch
                _state.update { current ->
                    when (inspection) {
                        is UnityTextInspection.Editable -> current.copy(
                            textInfos = current.textInfos + (name to inspection.info),
                            textIssues = current.textIssues - name,
                        )
                        is UnityTextInspection.Unavailable -> current.copy(
                            textIssues = current.textIssues + (name to inspection.reason),
                            textInfos = current.textInfos - name,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                inspectingNames.remove(name)
            }
        }
    }

    suspend fun fileFor(entry: UnityBundleEntry): File {
        val current = _state.value
        current.replacements[entry.name]?.takeIf { it.isFile }?.let { return it }
        current.workingFiles[entry.name]?.takeIf { it.isFile }?.let { return it }
        val doc = document ?: throw IOException("UnityFS bundle is not available")
        val dir = stageDir ?: throw IOException("Unity staging directory is not available")
        val index = doc.summary.entries.indexOf(entry).coerceAtLeast(0)
        val suffix = entry.displayName.substringAfterLast('.', "").takeIf { it.length in 1..10 }?.let { ".$it" }.orEmpty()
        val target = File(dir, "entry-$index$suffix")
        runInterruptible(Dispatchers.IO) { doc.copyEntry(entry, target) }
        workingBaselines = workingBaselines + (entry.name to (target.length() to target.lastModified()))
        _state.update { it.copy(workingFiles = it.workingFiles + (entry.name to target)) }
        return target
    }

    suspend fun replaceEntry(entry: UnityBundleEntry, uri: Uri, resolver: ContentResolver) {
        val doc = document ?: throw IOException("UnityFS bundle is not available")
        val dir = stageDir ?: throw IOException("Unity staging directory is not available")
        val index = doc.summary.entries.indexOf(entry).coerceAtLeast(0)
        val suffix = entry.displayName.substringAfterLast('.', "").takeIf { it.length in 1..10 }?.let { ".$it" }.orEmpty()
        val staged = File(dir, "replacement-$index-${System.nanoTime()}$suffix")
        try {
            runInterruptible(Dispatchers.IO) {
                val input = resolver.openInputStream(uri) ?: throw IOException("Could not open replacement")
                input.use { source -> FileOutputStream(staged).use { target -> source.copyTo(target) } }
            }
            _state.update {
                it.copy(
                    replacements = it.replacements + (entry.name to staged),
                    message = getApplication<Application>().getString(R.string.unity_replace_done),
                )
            }
        } catch (e: Exception) {
            staged.delete()
            throw e
        }
    }

    /** Exports every string inside a serialized-file object as an editable JSON file. */
    fun exportTexts(entry: UnityBundleEntry) {
        val doc = document ?: return
        val dir = stageDir ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    val texts = doc.exportTexts(entry) ?: throw IOException("Entry has no editable text")
                    val index = doc.summary.entries.indexOf(entry).coerceAtLeast(0)
                    val safe = entry.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifEmpty { "object" }
                    val file = File(dir, "texts-$index-$safe.json")
                    file.writeText(TypeTreeCodec.writeJson(texts))
                    Triple(file, texts.size, file.length() to file.lastModified())
                }
                exportedBaselines = exportedBaselines + (entry.name to result.third)
                _state.update {
                    it.copy(
                        busy = false,
                        exportedTexts = it.exportedTexts + (entry.name to result.first),
                        navigateTo = result.first.path,
                        message = getApplication<Application>().getString(R.string.unity_texts_exported, result.second),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false, message = getApplication<Application>().getString(R.string.unity_operation_cancelled)) }
                throw e
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() }
                val message = when {
                    e is UnityDocumentClosedException -> getApplication<Application>().getString(R.string.unity_staging_unavailable)
                    e is UnityOperationCancelledException -> getApplication<Application>().getString(R.string.unity_operation_cancelled)
                    detail != null -> getApplication<Application>().getString(R.string.unity_operation_failed_detail, detail)
                    else -> getApplication<Application>().getString(R.string.unity_operation_failed)
                }
                _state.update { it.copy(busy = false, message = message) }
            }
        }
    }

    /** Finds structurally valid localization tables inside the selected serialized entry. */
    fun inspectLocalizations(entry: UnityBundleEntry) {
        val doc = document ?: return
        if (_state.value.busy || !localizationInspectingNames.add(entry.name)) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val inspection = runInterruptible(Dispatchers.IO) { doc.inspectLocalizations(entry) }
                if (document !== doc) return@launch
                _state.update { current ->
                    when (inspection) {
                        is UnityLocalizationInspection.Found -> {
                            val selected = current.selectedLocalizationTableId
                                ?.takeIf { id -> inspection.sources.any { it.origin.tableId == id } }
                                ?: inspection.sources.first().origin.tableId
                            val selectedSource = inspection.sources.first { it.origin.tableId == selected }
                            current.copy(
                                localizationSources = current.localizationSources + (entry.name to inspection.sources),
                                selectedLocalizationTableId = selected,
                                localizationSourceLanguageCode = selectedSource.languages.first().code,
                                localizationAddPlan = null,
                                localizationImportPlan = null,
                                textIssues = current.textIssues - entry.name,
                                message = getApplication<Application>().getString(
                                    R.string.unity_localization_found,
                                    inspection.sources.size,
                                    inspection.sources.sumOf { it.terms.size },
                                ),
                            )
                        }
                        is UnityLocalizationInspection.Unavailable -> current.copy(
                            textIssues = current.textIssues + (entry.name to inspection.reason),
                            message = inspection.diagnostic,
                        )
                    }
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_operation_cancelled)) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = localizationFailureMessage(e)) }
            } finally {
                localizationInspectingNames.remove(entry.name)
                if (document === doc) _state.update { it.copy(busy = false) }
            }
        }
    }

    fun selectLocalizationTable(tableId: String) {
        val source = _state.value.localizationSources.values.flatten().firstOrNull { it.origin.tableId == tableId }
        _state.update {
            it.copy(
                selectedLocalizationTableId = tableId,
                localizationSourceLanguageCode = source?.languages?.firstOrNull()?.code,
                localizationAddPlan = null,
                localizationImportPlan = null,
            )
        }
    }

    fun setLocalizationSourceLanguage(code: String) {
        val source = selectedLocalizationSource() ?: return
        if (source.languageIndex(code) < 0) return
        _state.update {
            it.copy(
                localizationSourceLanguageCode = code,
                localizationImportPlan = null,
            )
        }
    }

    fun exportLocalization(languageCode: String? = null) {
        val source = selectedLocalizationSource() ?: return
        val dir = stageDir ?: return
        val options = _state.value.textTransformOptions
        val sourceCode = _state.value.localizationSourceLanguageCode
            ?.takeIf { source.languageIndex(it) >= 0 }
            ?: source.languages.first().code
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val output = runInterruptible(Dispatchers.IO) {
                    val tableTag = source.origin.tableId.hashCode().toUInt().toString(16)
                    val languageTag = languageCode ?: "all"
                    val file = File(dir, "localization-$tableTag-$languageTag.tsv")
                    val text = if (languageCode == null) {
                        UnityLocalizationTsv.exportAll(source, options, sourceCode)
                    } else {
                        UnityLocalizationTsv.exportLanguage(source, languageCode, options, sourceCode)
                    }
                    file.writeText(text, Charsets.UTF_8)
                    file
                }
                _state.update {
                    it.copy(
                        busy = false,
                        localizationTsv = output,
                        navigateTo = output.path,
                        message = getApplication<Application>().getString(R.string.unity_localization_exported, output.path),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false, message = getApplication<Application>().getString(R.string.unity_operation_cancelled)) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = localizationFailureMessage(e)) }
            }
        }
    }

    fun planAddLocalizationLanguage(name: String, code: String, flags: Int = 0) {
        val source = selectedLocalizationSource() ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val plan = runInterruptible(Dispatchers.IO) {
                    UnityLocalizationOperations.planAddLanguage(
                        source,
                        LocalizationLanguage(name.trim(), code.trim(), flags),
                    )
                }
                _state.update {
                    it.copy(
                        busy = false,
                        localizationAddPlan = plan,
                        localizationImportPlan = null,
                        message = if (plan.canApply) {
                            getApplication<Application>().getString(
                                R.string.unity_localization_add_preview,
                                plan.termsUpdated,
                                plan.estimatedDataDeltaBytes,
                            )
                        } else {
                            plan.diagnostic
                        },
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = localizationFailureMessage(e)) }
            }
        }
    }

    /** Parses the edited TSV on IO and produces a dry-run plan; it never mutates a bundle. */
    fun planLocalizationImport() {
        val source = selectedLocalizationSource() ?: return
        val file = _state.value.localizationTsv ?: return
        val options = _state.value.textTransformOptions
        val sourceCode = _state.value.localizationSourceLanguageCode
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val plan = runInterruptible(Dispatchers.IO) {
                    UnityLocalizationTsv.planImport(
                        source,
                        file.readText(Charsets.UTF_8),
                        options,
                        sourceCode,
                    )
                }
                _state.update {
                    it.copy(
                        busy = false,
                        localizationImportPlan = plan,
                        localizationAddPlan = null,
                        message = getApplication<Application>().getString(
                            R.string.unity_localization_import_preview,
                            plan.changes.size,
                            plan.unknownKeyCount,
                            plan.missingKeyCount,
                            plan.emptyCellCount,
                            plan.placeholderFailureCount,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false, message = getApplication<Application>().getString(R.string.unity_operation_cancelled)) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = localizationFailureMessage(e)) }
            }
        }
    }

    /** Applies the current dry-run atomically to a staged entry replacement. */
    fun applyLocalizationPlan(entry: UnityBundleEntry) {
        val doc = document ?: return
        val dir = stageDir ?: return
        val selected = selectedLocalizationSource() ?: return
        val state = _state.value
        val updated = when {
            state.localizationAddPlan?.canApply == true -> state.localizationAddPlan.updated
            state.localizationImportPlan?.canApply == true -> state.localizationImportPlan.updated
            else -> null
        } ?: return
        val tables = state.localizationSources[entry.name].orEmpty()
        if (tables.none { it.origin.tableId == selected.origin.tableId }) return
        if (state.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val staged = File(
                dir,
                "localization-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin",
            )
            try {
                val rewritten = tables.map { table ->
                    if (table.origin.tableId == selected.origin.tableId) updated else table
                }
                runInterruptible(Dispatchers.IO) {
                    doc.writeLocalizationReplacement(entry, rewritten, staged)
                }
                _state.update {
                    it.copy(
                        busy = false,
                        replacements = it.replacements + (entry.name to staged),
                        localizationSources = it.localizationSources + (entry.name to rewritten),
                        localizationAddPlan = null,
                        localizationImportPlan = null,
                        localizationTsv = null,
                        localizationSourceLanguageCode = updated.languages.first().code,
                        message = getApplication<Application>().getString(R.string.unity_localization_applied),
                    )
                }
            } catch (e: CancellationException) {
                staged.delete()
                _state.update { it.copy(busy = false, message = getApplication<Application>().getString(R.string.unity_operation_cancelled)) }
                throw e
            } catch (e: Exception) {
                staged.delete()
                _state.update { it.copy(busy = false, message = localizationFailureMessage(e)) }
            }
        }
    }

    private fun selectedLocalizationSource(): LocalizationSource? {
        val id = _state.value.selectedLocalizationTableId ?: return null
        return _state.value.localizationSources.values.flatten().firstOrNull { it.origin.tableId == id }
    }

    private fun localizationFailureMessage(error: Exception): String {
        val detail = error.message?.takeIf { it.isNotBlank() }
        return when {
            error is UnityDocumentClosedException -> getApplication<Application>().getString(R.string.unity_staging_unavailable)
            error is UnityOperationCancelledException -> getApplication<Application>().getString(R.string.unity_operation_cancelled)
            detail != null -> getApplication<Application>().getString(R.string.unity_operation_failed_detail, detail)
            else -> getApplication<Application>().getString(R.string.unity_operation_failed)
        }
    }

    fun consumeNavigate() {
        _state.update { it.copy(navigateTo = null) }
    }

    fun refreshEditedFiles() {
        val current = _state.value
        val changed = current.workingFiles.filter { (name, file) ->
            val baseline = workingBaselines[name]
            baseline != null && (file.length() != baseline.first || file.lastModified() != baseline.second)
        }
        _state.update { it.copy(editedFiles = changed) }
    }

    fun repack(parents: List<ArchiveParent>) {
        val doc = document ?: return
        if (!doc.repackSupported) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_repack_unsupported))
            }
            return
        }
        val current = _state.value
        if (current.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val output = runInterruptible(Dispatchers.IO) {
                    val localizationChanged = current.replacements.keys.any { it in current.localizationSources }
                    UnityPackageRepacker.repack(
                        document = doc,
                        replacements = collectChanges(doc, current),
                        parents = parents,
                        cacheDir = getApplication<Application>().cacheDir,
                        maxBundleGrowthRatio = if (localizationChanged) 1.25 else null,
                    )
                }
                _state.update {
                    it.copy(
                        output = output,
                        message = getApplication<Application>().getString(R.string.unity_repack_done, output.path),
                        busy = false,
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                        busy = false,
                    )
                }
                throw e
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() }
                val message = if (e is UnityOperationCancelledException) {
                    getApplication<Application>().getString(R.string.unity_operation_cancelled)
                } else if (e is UnityDocumentClosedException) {
                    getApplication<Application>().getString(R.string.unity_staging_unavailable)
                } else if (e is UnityUnsupportedVersionException) {
                    getApplication<Application>().getString(R.string.unity_unsupported_version)
                } else if (e is UnityFormatException && detail?.contains("JSON") == true) {
                    getApplication<Application>().getString(R.string.unity_texts_bad_json, detail)
                } else if (detail != null) {
                    getApplication<Application>().getString(R.string.unity_operation_failed_detail, detail)
                } else {
                    getApplication<Application>().getString(R.string.unity_operation_failed)
                }
                _state.update { it.copy(message = message, busy = false) }
            }
        }
    }

    fun setShapeArabic(enabled: Boolean) {
        exportedBaselines = emptyMap()
        _state.update {
            it.copy(textTransformOptions = it.textTransformOptions.copy(shapeArabic = enabled))
        }
    }

    fun setReverseBidi(enabled: Boolean) {
        exportedBaselines = emptyMap()
        _state.update {
            it.copy(textTransformOptions = it.textTransformOptions.copy(reverseBidi = enabled))
        }
    }

    fun estimateRewrite(): UnityBundleRewriteEstimate? = document?.estimateRewrite()

    fun inspectFonts(entry: UnityBundleEntry) {
        val doc = document ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val report = runInterruptible(Dispatchers.IO) { doc.inspectFonts(entry) }
                if (document !== doc) return@launch
                _state.update { current ->
                    current.copy(
                        busy = false,
                        fontReports = current.fontReports + (entry.name to report),
                        message = report.summaryStatement,
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw e
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() }
                val message = when {
                    e is UnityDocumentClosedException -> getApplication<Application>().getString(R.string.unity_staging_unavailable)
                    e is UnityOperationCancelledException -> getApplication<Application>().getString(R.string.unity_operation_cancelled)
                    detail != null -> getApplication<Application>().getString(R.string.unity_operation_failed_detail, detail)
                    else -> getApplication<Application>().getString(R.string.unity_operation_failed)
                }
                _state.update { it.copy(busy = false, message = message) }
            }
        }
    }

    fun applyDynamicFont(entry: UnityBundleEntry, ttfUri: Uri?, resolver: ContentResolver) {
        val doc = document ?: return
        val dir = stageDir ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val outcome = runInterruptible(Dispatchers.IO) {
                    val ttfBytes = if (ttfUri != null) {
                        resolver.openInputStream(ttfUri)?.use { it.readBytes() }
                            ?: throw IOException("Could not open selected TTF font")
                    } else {
                        null
                    }
                    doc.patchFontForDynamicGeneration(entry, ttfBytes)
                }
                when (outcome) {
                    is UnityFontPatchResult.Success -> {
                        val staged = runInterruptible(Dispatchers.IO) {
                            File(dir, "font-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin").apply {
                                writeBytes(outcome.rebuiltEntryBytes)
                            }
                        }
                        _state.update {
                            it.copy(
                                busy = false,
                                replacements = it.replacements + (entry.name to staged),
                                fontReports = it.fontReports + (entry.name to outcome.updatedReport),
                                message = getApplication<Application>().getString(
                                    R.string.unity_font_patch_done,
                                    outcome.patchedTmpAssets,
                                ),
                            )
                        }
                    }
                    is UnityFontPatchResult.Unavailable -> {
                        _state.update {
                            it.copy(
                                busy = false,
                                textIssues = it.textIssues + (entry.name to outcome.reason),
                                message = outcome.diagnostic,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw e
            } catch (e: Exception) {
                val detail = e.message?.takeIf { it.isNotBlank() }
                val message = when {
                    e is UnityDocumentClosedException -> getApplication<Application>().getString(R.string.unity_staging_unavailable)
                    e is UnityOperationCancelledException -> getApplication<Application>().getString(R.string.unity_operation_cancelled)
                    detail != null -> getApplication<Application>().getString(R.string.unity_operation_failed_detail, detail)
                    else -> getApplication<Application>().getString(R.string.unity_operation_failed)
                }
                _state.update { it.copy(busy = false, message = message) }
            }
        }
    }

    /** Merges staged edits, picked replacements and any exported text JSON the user has modified. */
    private fun collectChanges(doc: UnityBundleDocument, current: UnityAssetUiState): Map<String, File> {
        val dir = stageDir
        val textChanges = HashMap<String, File>()
        if (dir != null) {
            current.exportedTexts.forEach { (name, jsonFile) ->
                if (!jsonFile.isFile) return@forEach
                val baseline = exportedBaselines[name]
                if (!current.textTransformOptions.isEnabled &&
                    baseline != null &&
                    jsonFile.length() == baseline.first &&
                    jsonFile.lastModified() == baseline.second
                ) {
                    return@forEach
                }
                val entry = doc.summary.entries.firstOrNull { it.name == name } ?: return@forEach
                val edits = TypeTreeCodec.parseJson(jsonFile.readText())
                val bytes = doc.rebuildTexts(entry, edits, current.textTransformOptions)
                val staged = File(dir, "text-${name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin")
                staged.writeBytes(bytes)
                textChanges[name] = staged
                exportedBaselines = exportedBaselines + (name to (jsonFile.length() to jsonFile.lastModified()))
            }
        }
        return current.editedFiles + textChanges + current.replacements
    }

    fun notifyMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        document?.close()
        stageDir?.deleteRecursively()
        super.onCleared()
    }
}
