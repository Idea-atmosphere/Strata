package dev.cluvex.strata.feature.unity

import android.app.Application
import android.content.ContentResolver
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.View
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.ApkSignatureInspector
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
import dev.cluvex.strata.core.unity.UnityApkSigningHandoffEvaluator
import dev.cluvex.strata.core.unity.UnityApkSigningHandoffState
import dev.cluvex.strata.core.unity.UnityLanguageDirection
import dev.cluvex.strata.core.unity.UnityLanguageDirectionResolver
import dev.cluvex.strata.core.unity.UnityLocalizationApkDiscovery
import dev.cluvex.strata.core.unity.UnityLocalizationApkScanSession
import dev.cluvex.strata.core.unity.UnityLocalizationBudgetStopReason
import dev.cluvex.strata.core.unity.UnityLocalizationCandidateResult
import dev.cluvex.strata.core.unity.UnityLocalizationDirectoryDiscovery
import dev.cluvex.strata.core.unity.UnityLocalizationDiscovery
import dev.cluvex.strata.core.unity.UnityLocalizationInspection
import dev.cluvex.strata.core.unity.UnityLocalizationJson
import dev.cluvex.strata.core.unity.UnityLocalizationOpenRequest
import dev.cluvex.strata.core.unity.UnityLocalizationOverlay
import dev.cluvex.strata.core.unity.UnityLocalizationRoundTripStatus
import dev.cluvex.strata.core.unity.UnityLocalizationScanBudget
import dev.cluvex.strata.core.unity.UnityLocalizationScanProgress
import dev.cluvex.strata.core.unity.UnityLocalizationScanReport
import dev.cluvex.strata.core.unity.UnityLocalizationScanRequest
import dev.cluvex.strata.core.unity.UnityLocalizationOperations
import dev.cluvex.strata.core.unity.UnifiedTablePlan
import dev.cluvex.strata.core.unity.UnityLocalizationTsv
import dev.cluvex.strata.core.unity.UnityUnifiedLocalization
import dev.cluvex.strata.core.unity.UnityUnifiedPersian
import dev.cluvex.strata.core.unity.UVal
import dev.cluvex.strata.core.unity.UnityBaseData
import dev.cluvex.strata.core.unity.UnityPersianApplyPipeline
import dev.cluvex.strata.core.unity.UnityPersianFontRasterizer
import dev.cluvex.strata.core.unity.UnityPersianFrequency
import dev.cluvex.strata.core.unity.UnityPersianPlaceholders
import dev.cluvex.strata.core.unity.UnityPersianPrefabRepoint
import dev.cluvex.strata.core.unity.UnityPersianWordMapper
import dev.cluvex.strata.core.unity.UnityProbe
import dev.cluvex.strata.core.unity.UnityRtlFlagInference
import dev.cluvex.strata.core.unity.UnityRtlFlagInferer
import dev.cluvex.strata.core.unity.UnityTextInfo
import dev.cluvex.strata.core.unity.UnityTextInspection
import dev.cluvex.strata.core.unity.UnityTextShaper
import dev.cluvex.strata.core.unity.UnityTextTransformOptions
import dev.cluvex.strata.core.unity.UnityTextUnavailableReason
import dev.cluvex.strata.core.unity.UnityTranslationFontCoverage
import dev.cluvex.strata.core.unity.UnityTranslationFontCoverageEvaluator
import dev.cluvex.strata.core.unity.UnityTranslationReadiness
import dev.cluvex.strata.core.unity.UnityTranslationReadinessEvaluator
import dev.cluvex.strata.core.unity.UnityTranslationReadinessInput
import dev.cluvex.strata.core.unity.UnityTranslationReadinessKind
import dev.cluvex.strata.core.unity.UnityUnsupportedVersionException
import dev.cluvex.strata.core.unity.ensureUnityOperationActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/** One-shot request for a generated draft that must open in Strata's text editor. */
data class UnityEditorRequest(
    val id: Long,
    val path: String,
) {
    init {
        require(id > 0L) { "Unity editor request id must be positive" }
        require(path.isNotBlank()) { "Unity editor request path must not be blank" }
    }
}

internal fun consumeUnityEditorRequest(
    current: UnityEditorRequest?,
    consumedId: Long,
): UnityEditorRequest? = current?.takeUnless { it.id == consumedId }

/** One-shot request to save a generated TSV through Android's document provider. */
data class UnityTsvExportRequest(
    val id: Long,
    val suggestedName: String,
) {
    init {
        require(id > 0L) { "Unity TSV export request id must be positive" }
        require(suggestedName.endsWith(".tsv", ignoreCase = true)) {
            "Unity TSV export name must use the .tsv extension"
        }
    }
}

internal fun consumeUnityTsvExportRequest(
    current: UnityTsvExportRequest?,
    consumedId: Long,
): UnityTsvExportRequest? = current?.takeUnless { it.id == consumedId }

/** One-shot request to save a generated all-tables JSON through Android's document provider. */
data class UnityJsonExportRequest(
    val id: Long,
    val suggestedName: String,
) {
    init {
        require(id > 0L) { "Unity JSON export request id must be positive" }
        require(suggestedName.endsWith(".json", ignoreCase = true)) {
            "Unity JSON export name must use the .json extension"
        }
    }
}

internal fun consumeUnityJsonExportRequest(
    current: UnityJsonExportRequest?,
    consumedId: Long,
): UnityJsonExportRequest? = current?.takeUnless { it.id == consumedId }

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
    val localizationReplacementEntries: Set<String> = emptySet(),
    val textInfos: Map<String, UnityTextInfo> = emptyMap(),
    val textIssues: Map<String, UnityTextUnavailableReason> = emptyMap(),
    val textAutoInspectionRunning: Boolean = false,
    val textAutoInspectionComplete: Boolean = false,
    val textAutoInspectionScanned: Int = 0,
    val textAutoInspectionTotal: Int = 0,
    val fontReports: Map<String, UnityFontReport> = emptyMap(),
    val localizationSources: Map<String, List<LocalizationSource>> = emptyMap(),
    val localizationIssues: Map<String, UnityTextUnavailableReason> = emptyMap(),
    val localizationAutoInspectionComplete: Boolean = false,
    val selectedLocalizationTableId: String? = null,
    val localizationAddPlan: LocalizationAddLanguagePlan? = null,
    val localizationImportPlan: LocalizationImportPlan? = null,
    val localizationTsv: File? = null,
    val localizationSourceLanguageCode: String? = null,
    val localizationTargetLanguageCode: String? = null,
    val rtlFlagInference: UnityRtlFlagInference? = null,
    val fontCoverage: UnityTranslationFontCoverage? = null,
    val localizationRoundTripStatus: UnityLocalizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
    val localizationRoundTripDetail: String? = null,
    val discoveryRunning: Boolean = false,
    val discoveryProgress: UnityLocalizationScanProgress? = null,
    val discoveryReport: UnityLocalizationScanReport? = null,
    val discoveryScopeLabel: String? = null,
    val textTransformOptions: UnityTextTransformOptions = UnityTextTransformOptions(),
    val exportedTexts: Map<String, File> = emptyMap(),
    val editorRequest: UnityEditorRequest? = null,
    val tsvExportRequest: UnityTsvExportRequest? = null,
    val unifiedJsonFile: File? = null,
    val jsonExportRequest: UnityJsonExportRequest? = null,
    val output: File? = null,
    val outputSigningHandoff: UnityApkSigningHandoffState = UnityApkSigningHandoffState.NOT_APK,
    val message: String? = null,
    // Persian mode (Phase 4, additive, defaults keep existing flows unchanged).
    // Term editing in v1 is via TSV import (existing flow) + Load terms,
    // plus in-app editing in the Persian section below; full baking /
    // patching (base fallback + font) is Phase 4 part 4, not here.
    // Readiness here is PRELIMINARY only (shaping / mapping / ranking);
    // it does not check font coverage or base fallback, which the full
    // pipeline checks. Public types only (no internal PipelineResult
    // in UiState, which would break the public API).
    val persianMode: Boolean = false,
    val persianTerms: Map<String, String> = emptyMap(),
    val persianReady: Boolean = false,
    val persianIssues: List<String> = emptyList(),
    val persianStatsText: String? = null,
    // Phase 4 part 4: files picked for full Persian Apply, plus its
    // outputs. Uris are stored here (not just in Screen remember)
    // so they survive rotation (ViewModel survives config change,
    // Screen remember does not). persianPatchedBaseFile is the
    // rebuilt base fallback in stageDir (NOT a bundle replacement:
    // base files are not bundle entries, so repack does not include
    // it; the user exports it and installs it via MT Manager, with
    // the size + CRC in the success message, as for Bendy fallbacks).
    // persianApplied distinguishes full-Apply success from
    // preliminary readiness (persianReady): the Screen shows the
    // preliminary note only when ready && !applied.
    val persianBaseFallbackUri: Uri? = null,
    val persianSceneBundleUri: Uri? = null,
    val persianFontUri: Uri? = null,
    val persianApplied: Boolean = false,
    val persianPatchedBaseFile: File? = null,
    // CRC32 of persianPatchedBaseFile (MT Manager format, hex,
    // uppercase, 8 digits), for the user to verify after export /
    // install (size alone is not proof, per Bendy file-identity
    // lesson). Shown persistently in the Persian section, not only
    // in the transient success message.
    val persianPatchedBaseCrc: String? = null,
    // Phase 5: standalone prefab repoint (static TMP font -> Persian
    // dynamic fallback). The prefab file is picked directly (a
    // serialized .bin, as exported from an installed game), not a
    // bundle entry: repacking a repointed prefab into a bundle is
    // NOT in v1. GUID fields are free text (32 lowercase hex);
    // the module validates them. PPtr fields are free text too:
    // all four blank means GUID-only repoint (Bendy warnings);
    // all four filled means GUID + one material PPtr remap
    // (Bendy subtitles). A partially filled PPtr is blocked.
    // persianPrefabPatchedFile lives in cacheDir/persian-prefab
    // until exported, like persianPatchedBaseFile in stageDir.
    val persianPrefabUri: Uri? = null,
    val persianPrefabSourceGuid: String = "",
    val persianPrefabTargetGuid: String = "",
    val persianPrefabPptrFromFile: String = "",
    val persianPrefabPptrFromPath: String = "",
    val persianPrefabPptrToFile: String = "",
    val persianPrefabPptrToPath: String = "",
    val persianPrefabPatchedFile: File? = null,
    val persianPrefabPatchedCrc: String? = null,
    val persianPrefabReport: String? = null,
    // Unified multi-table editing: one edit map (term key -> new
    // target value) shared by every discovered table. A key present
    // in several tables is edited in all of them at once; Apply
    // writes the edits into every affected table (grouped by
    // bundle entry, round-trip verified per entry) via the same
    // overlay mechanism as the per-table apply. Values are written
    // as-is: no shaping, no PUA mapping, no font baking here (that
    // stays with the Persian Apply pipeline).
    val unifiedEdits: Map<String, String> = emptyMap(),
    val unifiedSourceCode: String? = null,
    val unifiedTargetCode: String? = null,
    val unifiedStatusText: String? = null,
    /** Export language for the all-tables JSON; null means all languages. */
    val unifiedExportLanguageCode: String? = null,
) {
    val outputNeedsSigning: Boolean
        get() = outputSigningHandoff == UnityApkSigningHandoffState.SIGNING_REQUIRED

    val translationReadiness: UnityTranslationReadiness
        get() = UnityTranslationReadinessEvaluator.evaluate(
            UnityTranslationReadinessInput(
                tableSelected = selectedLocalizationTableId != null,
                tableInspectionFailed = selectedName?.let {
                    it in localizationIssues && localizationSources[it].isNullOrEmpty()
                } == true,
                targetLanguageSelected = localizationTargetLanguageCode != null,
                fontCoverage = fontCoverage,
                compressionSupported = document?.repackSupported == true,
                compressionDetail = document?.repackCompressionLabel,
                translationPending = localizationAddPlan?.canApply == true || localizationImportPlan?.canApply == true,
                roundTripStatus = localizationRoundTripStatus,
                roundTripDetail = localizationRoundTripDetail,
            ),
        )

    val canValidateAndStageLocalization: Boolean
        get() = translationReadiness.checks
            .filter { it.kind != UnityTranslationReadinessKind.ROUND_TRIP }
            .all { it.status == dev.cluvex.strata.core.unity.UnityTranslationReadinessStatus.READY } &&
            (localizationAddPlan?.canApply == true || localizationImportPlan?.canApply == true)
}

private data class LoadedUnityAsset(
    val probe: UnityProbe,
    val document: UnityBundleDocument?,
    val localizationSources: Map<String, List<LocalizationSource>>,
    val localizationIssues: Map<String, UnityTextUnavailableReason>,
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
    private val textInspectionMutex = Mutex()
    private var textInspectionJob: Job? = null
    private var apkDiscoverySession: UnityLocalizationApkScanSession? = null
    private var discoveryJob: Job? = null
    private var nextEditorRequestId = 0L
    @Volatile
    private var discoveryGeneration = 0L
    private val recognizedLocaleLanguages = Locale.getAvailableLocales()
        .asSequence()
        .map { it.language }
        .filter { it.isNotBlank() && it != "und" }
        .toSet()
    private val languageDirectionResolver = UnityLanguageDirectionResolver { code ->
        val locale = runCatching {
            Locale.Builder().setLanguageTag(code.replace('_', '-')).build()
        }.getOrNull()
        if (locale == null || locale.language !in recognizedLocaleLanguages) {
            UnityLanguageDirection.UNKNOWN
        } else {
            when (TextUtils.getLayoutDirectionFromLocale(locale)) {
                View.LAYOUT_DIRECTION_RTL -> UnityLanguageDirection.RIGHT_TO_LEFT
                View.LAYOUT_DIRECTION_LTR -> UnityLanguageDirection.LEFT_TO_RIGHT
                else -> UnityLanguageDirection.UNKNOWN
            }
        }
    }

    fun open(path: String, inspectLocalizations: Boolean = false) {
        val current = _state.value
        if (current.path == path && (current.loading || current.probe != null || current.error != null)) {
            if (!current.loading && !current.textAutoInspectionComplete && !current.textAutoInspectionRunning) {
                document?.let(::inspectAllTexts)
            }
            if (inspectLocalizations && !current.loading && !current.localizationAutoInspectionComplete) {
                inspectAllLocalizations()
            }
            return
        }

        textInspectionJob?.cancel()
        textInspectionJob = null
        document?.close()
        stageDir?.deleteRecursively()
        document = null
        workingBaselines = emptyMap()
        exportedBaselines = emptyMap()
        inspectingNames.clear()
        localizationInspectingNames.clear()
        val newStageDir = File(
            getApplication<Application>().cacheDir,
            "unity-editor/${path.hashCode().toUInt().toString(16)}-${System.nanoTime()}",
        )
        newStageDir.mkdirs()
        stageDir = newStageDir
        _state.value = UnityAssetUiState(path = path, loading = true)

        viewModelScope.launch {
            try {
                val loaded = runInterruptible(Dispatchers.IO) {
                    val file = File(path)
                    val probe = UnityBundleReader.probe(file)
                    val parsed = if (probe.bundleSupported) {
                        UnityBundleReader.open(file, getApplication<Application>().cacheDir)
                    } else {
                        null
                    }
                    try {
                        val found = LinkedHashMap<String, List<LocalizationSource>>()
                        val issues = LinkedHashMap<String, UnityTextUnavailableReason>()
                        if (inspectLocalizations && parsed != null) {
                            parsed.summary.entries.forEach { entry ->
                                if (Thread.currentThread().isInterrupted) throw UnityOperationCancelledException()
                                when (val inspection = parsed.inspectLocalizations(entry)) {
                                    is UnityLocalizationInspection.Found -> found[entry.name] = inspection.sources
                                    is UnityLocalizationInspection.Unavailable -> issues[entry.name] = inspection.reason
                                }
                            }
                        }
                        LoadedUnityAsset(probe, parsed, found, issues)
                    } catch (error: Throwable) {
                        parsed?.close()
                        throw error
                    }
                }
                val parsed = loaded.document
                document = parsed
                val firstSource = loaded.localizationSources.values.flatten().firstOrNull()
                _state.update {
                    it.copy(
                        loading = false,
                        probe = loaded.probe,
                        document = parsed,
                        localizationSources = loaded.localizationSources,
                        localizationIssues = loaded.localizationIssues,
                        localizationAutoInspectionComplete = inspectLocalizations,
                        selectedName = firstSource?.origin?.entryName,
                        selectedLocalizationTableId = firstSource?.origin?.tableId,
                        localizationSourceLanguageCode = firstSource?.languages?.firstOrNull()?.code,
                    )
                }
                parsed?.let(::inspectAllTexts)
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

    private fun inspectAllTexts(doc: UnityBundleDocument) {
        if (document !== doc || _state.value.textAutoInspectionRunning) return
        val entries = doc.summary.entries
        _state.update {
            it.copy(
                textAutoInspectionRunning = entries.isNotEmpty(),
                textAutoInspectionComplete = entries.isEmpty(),
                textAutoInspectionScanned = 0,
                textAutoInspectionTotal = entries.size,
            )
        }
        if (entries.isEmpty()) return

        textInspectionJob?.cancel()
        textInspectionJob = viewModelScope.launch {
            var completed = false
            try {
                for ((index, entry) in entries.withIndex()) {
                    if (document !== doc) return@launch
                    val name = entry.name
                    val known = name in _state.value.textInfos || name in _state.value.textIssues
                    if (known || !inspectingNames.add(name)) {
                        _state.update { current ->
                            current.copy(textAutoInspectionScanned = index + 1)
                        }
                        continue
                    }
                    try {
                        val inspection = inspectText(doc, entry)
                        if (document !== doc) return@launch
                        _state.update { current ->
                            when (inspection) {
                                is UnityTextInspection.Editable -> current.copy(
                                    textInfos = current.textInfos + (name to inspection.info),
                                    textIssues = current.textIssues - name,
                                    textAutoInspectionScanned = index + 1,
                                )
                                is UnityTextInspection.Unavailable -> current.copy(
                                    textIssues = current.textIssues + (name to inspection.reason),
                                    textInfos = current.textInfos - name,
                                    textAutoInspectionScanned = index + 1,
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: UnityOperationCancelledException) {
                        throw CancellationException(error.message).also { it.initCause(error) }
                    } catch (error: Exception) {
                        if (document !== doc) return@launch
                        _state.update { current ->
                            current.copy(
                                textIssues = current.textIssues + (name to UnityTextUnavailableReason.DECODE_FAILED),
                                textInfos = current.textInfos - name,
                                textAutoInspectionScanned = index + 1,
                            )
                        }
                    } finally {
                        inspectingNames.remove(name)
                    }
                }
                completed = true
            } finally {
                if (document === doc) {
                    _state.update { current ->
                        current.copy(
                            textAutoInspectionRunning = false,
                            textAutoInspectionComplete = completed,
                            textAutoInspectionScanned = if (completed) entries.size else current.textAutoInspectionScanned,
                        )
                    }
                }
            }
        }
    }

    private suspend fun inspectText(
        doc: UnityBundleDocument,
        entry: UnityBundleEntry,
    ): UnityTextInspection = textInspectionMutex.withLock {
        runInterruptible(Dispatchers.IO) { doc.inspectText(entry) }
    }

    private fun inspectAllLocalizations() {
        val doc = document ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    val found = LinkedHashMap<String, List<LocalizationSource>>()
                    val issues = LinkedHashMap<String, UnityTextUnavailableReason>()
                    doc.summary.entries.forEach { entry ->
                        if (Thread.currentThread().isInterrupted) throw UnityOperationCancelledException()
                        when (val inspection = doc.inspectLocalizations(entry)) {
                            is UnityLocalizationInspection.Found -> found[entry.name] = inspection.sources
                            is UnityLocalizationInspection.Unavailable -> issues[entry.name] = inspection.reason
                        }
                    }
                    found to issues
                }
                if (document !== doc) return@launch
                val first = result.first.values.flatten().firstOrNull()
                _state.update {
                    it.copy(
                        busy = false,
                        localizationSources = result.first,
                        localizationIssues = result.second,
                        localizationAutoInspectionComplete = true,
                        selectedName = first?.origin?.entryName ?: it.selectedName,
                        selectedLocalizationTableId = first?.origin?.tableId,
                        localizationSourceLanguageCode = first?.languages?.firstOrNull()?.code,
                    )
                }
            } catch (error: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(busy = false, message = localizationFailureMessage(error)) }
            }
        }
    }

    private fun releaseApkDiscoverySession() {
        apkDiscoverySession?.close()
        apkDiscoverySession = null
    }

    private fun scanLocalizationFiles(
        files: List<File>,
        scopeLabel: String,
        budget: UnityLocalizationScanBudget,
        generation: Long,
    ): UnityLocalizationScanReport {
        val requests = files.map { file ->
            val identity = file.absoluteFile.path
            UnityLocalizationScanRequest(
                file = file,
                identity = identity,
                reportedSize = file.length().coerceAtLeast(0L),
                reportedLastModified = file.lastModified().coerceAtLeast(0L),
                scanFileLastModified = file.lastModified().coerceAtLeast(0L),
                scope = scopeLabel,
                openRequest = UnityLocalizationOpenRequest(file, identity, identity),
            )
        }
        return UnityLocalizationDiscovery.scan(
            requests = requests,
            cacheRoot = File(getApplication<Application>().cacheDir, "unity-localization-discovery"),
            budget = budget,
            onProgress = { progress ->
                if (generation == discoveryGeneration) {
                    _state.update { it.copy(discoveryProgress = progress) }
                }
            },
        )
    }

    fun discoverLocalizationTables(
        files: List<File>,
        scopeLabel: String,
        budget: UnityLocalizationScanBudget = UnityLocalizationScanBudget(),
    ) {
        if (_state.value.discoveryRunning) return
        releaseApkDiscoverySession()
        val generation = ++discoveryGeneration
        _state.update {
            it.copy(
                discoveryRunning = true,
                discoveryProgress = UnityLocalizationScanProgress(0, files.size, 0L, scopeLabel),
                discoveryReport = null,
                discoveryScopeLabel = scopeLabel,
                message = null,
            )
        }
        discoveryJob = viewModelScope.launch {
            try {
                val report = runInterruptible(Dispatchers.IO) {
                    scanLocalizationFiles(files, scopeLabel, budget, generation)
                }
                if (generation != discoveryGeneration) return@launch
                _state.update {
                    it.copy(
                        discoveryRunning = false,
                        discoveryReport = report,
                        message = getApplication<Application>().getString(
                            R.string.unity_discovery_complete,
                            report.scannedFiles,
                            report.candidates.count { candidate -> candidate.hasLocalizationTables },
                        ),
                    )
                }
            } catch (error: CancellationException) {
                if (generation == discoveryGeneration) {
                    _state.update {
                        it.copy(
                            discoveryRunning = false,
                            message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                        )
                    }
                }
                throw error
            } catch (error: Exception) {
                if (generation == discoveryGeneration) {
                    _state.update { it.copy(discoveryRunning = false, message = localizationFailureMessage(error)) }
                }
            }
        }
    }

    fun discoverLocalizationDirectory(
        directory: File,
        scopeLabel: String,
        budget: UnityLocalizationScanBudget = UnityLocalizationScanBudget(),
    ) {
        if (_state.value.discoveryRunning) return
        releaseApkDiscoverySession()
        val generation = ++discoveryGeneration
        _state.update {
            it.copy(
                discoveryRunning = true,
                discoveryProgress = UnityLocalizationScanProgress(0, 0, 0L, directory.path),
                discoveryReport = null,
                discoveryScopeLabel = scopeLabel,
                message = null,
            )
        }
        discoveryJob = viewModelScope.launch {
            try {
                val report = runInterruptible(Dispatchers.IO) {
                    val listing = UnityLocalizationDirectoryDiscovery.list(directory, budget) { progress ->
                        if (generation == discoveryGeneration) {
                            _state.update { it.copy(discoveryProgress = progress) }
                        }
                    }
                    val remainingMillis = budget.maxElapsedMillis - listing.elapsedMillis
                    val scanned = if (remainingMillis > 0L && listing.files.isNotEmpty()) {
                        scanLocalizationFiles(
                            files = listing.files,
                            scopeLabel = scopeLabel,
                            budget = budget.copy(maxElapsedMillis = remainingMillis),
                            generation = generation,
                        )
                    } else {
                        UnityLocalizationScanReport(
                            candidates = emptyList(),
                            scannedFiles = 0,
                            scannedSourceBytes = 0L,
                            stoppedByBudget = remainingMillis <= 0L,
                            budgetStopReason = if (remainingMillis <= 0L) {
                                UnityLocalizationBudgetStopReason.ELAPSED_TIME
                            } else {
                                null
                            },
                        )
                    }
                    val stop = listing.budgetStopReason ?: scanned.budgetStopReason
                    scanned.copy(
                        candidates = (scanned.candidates + listing.failures).sortedWith(
                            compareByDescending<UnityLocalizationCandidateResult> { it.hasLocalizationTables }
                                .thenBy { it.fileSize }
                                .thenBy { it.path },
                        ),
                        scannedFiles = scanned.scannedFiles + listing.failures.size,
                        stoppedByBudget = stop != null,
                        budgetStopReason = stop,
                    )
                }
                if (generation != discoveryGeneration) return@launch
                _state.update {
                    it.copy(
                        discoveryRunning = false,
                        discoveryReport = report,
                        message = getApplication<Application>().getString(
                            R.string.unity_discovery_complete,
                            report.scannedFiles,
                            report.candidates.count { candidate -> candidate.hasLocalizationTables },
                        ),
                    )
                }
            } catch (error: CancellationException) {
                if (generation == discoveryGeneration) {
                    _state.update {
                        it.copy(
                            discoveryRunning = false,
                            message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                        )
                    }
                }
                throw error
            } catch (error: Exception) {
                if (generation == discoveryGeneration) {
                    _state.update { it.copy(discoveryRunning = false, message = localizationFailureMessage(error)) }
                }
            }
        }
    }

    fun discoverLocalizationApk(
        apk: File,
        scopeLabel: String,
        budget: UnityLocalizationScanBudget = UnityLocalizationScanBudget(),
    ) {
        if (_state.value.discoveryRunning) return
        releaseApkDiscoverySession()
        val generation = ++discoveryGeneration
        val deleteSourceOnClose = apk.absoluteFile.parentFile ==
            File(getApplication<Application>().cacheDir, "unity-picked-apk").absoluteFile
        _state.update {
            it.copy(
                discoveryRunning = true,
                discoveryProgress = UnityLocalizationScanProgress(0, 0, 0L, apk.path),
                discoveryReport = null,
                discoveryScopeLabel = scopeLabel,
                message = null,
            )
        }
        discoveryJob = viewModelScope.launch {
            var prepared: UnityLocalizationApkScanSession? = null
            try {
                val report = runInterruptible(Dispatchers.IO) {
                    prepared = UnityLocalizationApkDiscovery.prepare(
                        apk = apk,
                        cacheRoot = File(getApplication<Application>().cacheDir, "unity-localization-apk"),
                        scope = scopeLabel,
                        budget = budget,
                        deleteSourceOnClose = deleteSourceOnClose,
                        onProgress = { progress ->
                            if (generation == discoveryGeneration) {
                                _state.update { it.copy(discoveryProgress = progress) }
                            }
                        },
                    )
                    val remainingMillis = budget.maxElapsedMillis - prepared!!.preparationElapsedMillis
                    val scanned = if (remainingMillis > 0L) {
                        UnityLocalizationDiscovery.scan(
                            requests = prepared!!.requests,
                            cacheRoot = File(
                                getApplication<Application>().cacheDir,
                                "unity-localization-discovery",
                            ),
                            budget = budget.copy(maxElapsedMillis = remainingMillis),
                            onProgress = { progress ->
                                if (generation == discoveryGeneration) {
                                    _state.update { it.copy(discoveryProgress = progress) }
                                }
                            },
                        )
                    } else {
                        UnityLocalizationScanReport(
                            candidates = emptyList(),
                            scannedFiles = 0,
                            scannedSourceBytes = 0L,
                            stoppedByBudget = true,
                            budgetStopReason = UnityLocalizationBudgetStopReason.ELAPSED_TIME,
                        )
                    }
                    prepared!!.merge(scanned)
                }
                if (generation != discoveryGeneration) {
                    prepared?.close()
                    prepared = null
                    return@launch
                }
                apkDiscoverySession = prepared
                prepared = null
                _state.update {
                    it.copy(
                        discoveryRunning = false,
                        discoveryReport = report,
                        message = getApplication<Application>().getString(
                            R.string.unity_discovery_complete,
                            report.scannedFiles,
                            report.candidates.count { candidate -> candidate.hasLocalizationTables },
                        ),
                    )
                }
            } catch (error: CancellationException) {
                prepared?.close()
                if (prepared == null && deleteSourceOnClose) apk.delete()
                if (generation == discoveryGeneration) {
                    _state.update {
                        it.copy(
                            discoveryRunning = false,
                            message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                        )
                    }
                }
                throw error
            } catch (error: Exception) {
                prepared?.close()
                if (prepared == null && deleteSourceOnClose) apk.delete()
                if (generation == discoveryGeneration) {
                    _state.update { it.copy(discoveryRunning = false, message = localizationFailureMessage(error)) }
                }
            }
        }
    }

    fun cancelDiscovery() {
        discoveryJob?.cancel()
    }

    fun clearDiscovery() {
        val job = discoveryJob
        discoveryJob = null
        discoveryGeneration++
        job?.cancel()
        releaseApkDiscoverySession()
        _state.update {
            it.copy(
                discoveryRunning = false,
                discoveryProgress = null,
                discoveryReport = null,
                discoveryScopeLabel = null,
            )
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
                val inspection = inspectText(doc, entry)
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: UnityOperationCancelledException) {
                throw CancellationException(error.message).also { it.initCause(error) }
            } catch (error: Exception) {
                if (document === doc) {
                    _state.update { current ->
                        current.copy(
                            textIssues = current.textIssues + (name to UnityTextUnavailableReason.DECODE_FAILED),
                            textInfos = current.textInfos - name,
                        )
                    }
                }
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
                val editorRequest = newEditorRequest(result.first)
                _state.update {
                    it.copy(
                        busy = false,
                        exportedTexts = it.exportedTexts + (entry.name to result.first),
                        editorRequest = editorRequest,
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
                                localizationTargetLanguageCode = null,
                                rtlFlagInference = null,
                                fontCoverage = null,
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                localizationAddPlan = null,
                                localizationImportPlan = null,
                                localizationIssues = current.localizationIssues - entry.name,
                                message = getApplication<Application>().getString(
                                    R.string.unity_localization_found,
                                    inspection.sources.size,
                                    inspection.sources.sumOf { it.terms.size },
                                ),
                            )
                        }
                        is UnityLocalizationInspection.Unavailable -> current.copy(
                            localizationIssues = current.localizationIssues + (entry.name to inspection.reason),
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
                localizationTargetLanguageCode = null,
                rtlFlagInference = null,
                fontCoverage = null,
                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                localizationRoundTripDetail = null,
                localizationAddPlan = null,
                localizationImportPlan = null,
            )
        }
    }

    fun setLocalizationSourceLanguage(code: String) {
        val source = selectedLocalizationSource() ?: return
        if (source.languageIndex(code) < 0) return
        _state.update {
            withTranslationDerived(
                it.copy(
                    localizationSourceLanguageCode = code,
                    localizationImportPlan = null,
                ),
            )
        }
    }

    fun setLocalizationTargetLanguage(code: String) {
        val source = pendingLocalizationSource(_state.value) ?: return
        val language = source.languages.firstOrNull { it.code == code } ?: return
        _state.update { current ->
            val inferenceBase = selectedLocalizationSource(current) ?: source
            val inference = UnityRtlFlagInferer.infer(
                languages = inferenceBase.languages,
                targetLanguageCode = code,
                templateFlags = language.flags,
                directionResolver = languageDirectionResolver,
            )
            withTranslationDerived(
                current.copy(
                    localizationTargetLanguageCode = code,
                    rtlFlagInference = inference,
                ),
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
                    writeLocalizationTsvDraft(
                        directory = dir,
                        source = source,
                        languageCode = languageCode,
                        transformOptions = options,
                        sourceLanguageCode = sourceCode,
                    )
                }
                val exportRequest = newTsvExportRequest(output)
                _state.update {
                    it.copy(
                        busy = false,
                        localizationTsv = output,
                        tsvExportRequest = exportRequest,
                        message = null,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false, message = getApplication<Application>().getString(R.string.unity_operation_cancelled)) }
                throw e
            } catch (error: Throwable) {
                _state.update { it.copy(busy = false, message = localizationFailureMessage(error)) }
            }
        }
    }

    /** Copies the generated TSV to a user-selected document; no editor route is involved. */
    fun saveLocalizationTsv(uri: Uri, resolver: ContentResolver) {
        val sourceFile = _state.value.localizationTsv?.takeIf { it.isFile } ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    val sourceDigest = MessageDigest.getInstance("SHA-256")
                    val target = resolver.openOutputStream(uri, "rwt")
                        ?: throw IOException("TSV export destination is unavailable")
                    val copied = target.use { output ->
                        DigestInputStream(sourceFile.inputStream().buffered(), sourceDigest).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                ensureUnityOperationActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                total += read
                            }
                            total
                        }
                    }
                    if (copied != sourceFile.length()) {
                        throw IOException("TSV export destination received an incomplete copy")
                    }

                    val destinationDigest = MessageDigest.getInstance("SHA-256")
                    val destination = resolver.openInputStream(uri)
                        ?: throw IOException("TSV export destination cannot be verified")
                    val verifiedBytes = DigestInputStream(destination.buffered(), destinationDigest).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            ensureUnityOperationActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) total += read
                        }
                        total
                    }
                    if (verifiedBytes != copied || !sourceDigest.digest().contentEquals(destinationDigest.digest())) {
                        throw IOException("TSV export destination failed read-after-write verification")
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(
                            R.string.unity_localization_exported,
                            uri.lastPathSegment ?: uri.toString(),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                discardCreatedDocument(resolver, uri)
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw error
            } catch (error: Throwable) {
                discardCreatedDocument(resolver, uri)
                _state.update { it.copy(busy = false, message = localizationFailureMessage(error)) }
            }
        }
    }

    /**
     * Stages every discovered table as one JSON text (same flat
     * shape the JSON import accepts, one object per table) and
     * requests a save through Android's document provider. This
     * replaces the earlier clipboard copy: copying the whole text
     * crashed on device (large clipboard payload), while a file
     * save has no such limit.
     */
    fun exportUnifiedJson() {
        val dir = stageDir ?: return
        val sources = _state.value.localizationSources
        if (sources.isEmpty()) return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val text = UnityLocalizationJson.toJsonAll(sources, _state.value.unifiedExportLanguageCode)
                val output = runInterruptible(Dispatchers.IO) {
                    File(dir, "i2-tables-${System.nanoTime()}.json").apply { writeText(text) }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        unifiedJsonFile = output,
                        jsonExportRequest = newJsonExportRequest(output),
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
            } catch (error: Throwable) {
                _state.update { it.copy(busy = false, message = localizationFailureMessage(error)) }
            }
        }
    }

    /** Copies the staged all-tables JSON to a user-selected document; mirrors saveLocalizationTsv. */
    fun saveUnifiedJson(uri: Uri, resolver: ContentResolver) {
        val sourceFile = _state.value.unifiedJsonFile?.takeIf { it.isFile } ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    val sourceDigest = MessageDigest.getInstance("SHA-256")
                    val target = resolver.openOutputStream(uri, "rwt")
                        ?: throw IOException("JSON export destination is unavailable")
                    val copied = target.use { output ->
                        DigestInputStream(sourceFile.inputStream().buffered(), sourceDigest).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                ensureUnityOperationActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                output.write(buffer, 0, read)
                                total += read
                            }
                            total
                        }
                    }
                    if (copied != sourceFile.length()) {
                        throw IOException("JSON export destination received an incomplete copy")
                    }
                    val destinationDigest = MessageDigest.getInstance("SHA-256")
                    val destination = resolver.openInputStream(uri)
                        ?: throw IOException("JSON export destination cannot be verified")
                    val verifiedBytes = DigestInputStream(destination.buffered(), destinationDigest).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            ensureUnityOperationActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) total += read
                        }
                        total
                    }
                    if (verifiedBytes != copied || !sourceDigest.digest().contentEquals(destinationDigest.digest())) {
                        throw IOException("JSON export destination failed read-after-write verification")
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(
                            R.string.unity_unified_json_exported,
                            uri.lastPathSegment ?: uri.toString(),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                discardCreatedDocument(resolver, uri)
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw error
            } catch (error: Throwable) {
                discardCreatedDocument(resolver, uri)
                _state.update { it.copy(busy = false, message = localizationFailureMessage(error)) }
            }
        }
    }

    /**
     * Imports a multi-table JSON file (the export shape): each
     * object is routed by m_Name, each value by language code, so
     * edited values replace only their own language column and
     * the other languages are untouched. Writes per entry with
     * round-trip verification, like [applyUnifiedEdits]. Blocked
     * while a Persian Apply is staged (plain values would
     * clobber staged PUA text).
     */
    fun importUnifiedJson(uri: Uri, resolver: ContentResolver) {
        val doc = document ?: return
        val dir = stageDir ?: return
        val state = _state.value
        if (state.busy) return
        if (state.localizationSources.isEmpty()) return
        if (state.persianApplied) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_persian_plain_apply_blocked))
            }
            return
        }
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            var staged: File? = null
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    val file = stageImportedLocalizationTsv(dir) {
                        resolver.openInputStream(uri) ?: throw IOException("JSON import source is unavailable")
                    }
                    staged = file
                    try {
                        val text = readLocalizationTsvUtf8(file)
                        val parsed = UnityLocalizationJson.parsedTables(UnityLocalizationJson.parseAll(text))
                        UnityLocalizationJson.planImportAll(
                            sourcesByEntry = state.localizationSources,
                            parsed = parsed,
                            sourceCode = state.unifiedSourceCode,
                            options = state.textTransformOptions,
                        )
                    } catch (error: Throwable) {
                        file.delete()
                        throw error
                    }
                }
                // The staged import copy is no longer needed; plans are in memory.
                staged?.delete()
                staged = null
                val plans = result.plans
                val app = getApplication<Application>()
                if (plans.isEmpty()) {
                    val status = buildString {
                        if (result.unknownTables.isNotEmpty()) {
                            append(app.getString(R.string.unity_unified_import_unknown_tables, result.unknownTables.joinToString(", ")))
                        }
                        if (result.unknownLanguages.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(app.getString(R.string.unity_unified_import_unknown_languages, result.unknownLanguages.joinToString(", ")))
                        }
                    }
                    _state.update {
                        it.copy(
                            busy = false,
                            unifiedStatusText = status.ifEmpty { app.getString(R.string.unity_unified_import_no_tables) },
                            message = app.getString(R.string.unity_unified_import_no_tables),
                        )
                    }
                    return@launch
                }
                val plansByEntry = plans.groupBy { it.entryName }
                _state.update {
                    it.copy(
                        localizationRoundTripStatus = UnityLocalizationRoundTripStatus.RUNNING,
                        localizationRoundTripDetail = null,
                    )
                }
                val successes = LinkedHashMap<String, Pair<File, List<LocalizationSource>>>()
                val failures = ArrayList<Pair<String, Throwable>>()
                for ((entryName, entryPlans) in plansByEntry) {
                    val entry = doc.summary.entries.firstOrNull { it.name == entryName }
                    if (entry == null) {
                        failures += entryName to UnityFormatException("Bundle entry was not found: $entryName")
                        continue
                    }
                    val tables = state.localizationSources[entryName].orEmpty()
                    val updatedById = entryPlans.associate { it.source.origin.tableId to it.plan.updated!! }
                    val rewritten = tables.map { table -> updatedById[table.origin.tableId] ?: table }
                    val stagedEntry = File(
                        dir,
                        "localization-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin",
                    )
                    try {
                        runInterruptible(Dispatchers.IO) {
                            val existingEntryReplacement = state.replacements[entry.name]
                            if (existingEntryReplacement != null) {
                                UnityLocalizationOverlay.write(existingEntryReplacement, rewritten, stagedEntry, dir)
                            } else {
                                doc.writeLocalizationReplacement(entry, rewritten, stagedEntry)
                            }
                            verifyLocalizationRoundTrip(stagedEntry, rewritten, dir)
                        }
                        successes[entryName] = stagedEntry to rewritten
                    } catch (e: CancellationException) {
                        stagedEntry.delete()
                        successes.values.forEach { it.first.delete() }
                        throw e
                    } catch (e: Exception) {
                        stagedEntry.delete()
                        failures += entryName to e
                    }
                }
                val newSources = state.localizationSources.toMutableMap()
                for ((entryName, pair) in successes) newSources[entryName] = pair.second
                val appliedPlans = plans.filter { it.entryName in successes }
                val appliedTerms = appliedPlans.sumOf { it.plan.changes.size }
                val appliedCodes = appliedPlans
                    .flatMap { it.plan.changes.map { change -> change.languageCode } }
                    .distinct().sorted()
                val status = buildString {
                    append(app.getString(R.string.unity_unified_import_summary, appliedTerms, appliedPlans.size))
                    if (appliedCodes.isNotEmpty()) append(" (${appliedCodes.joinToString(", ")})")
                    if (result.placeholderSkippedTerms > 0) {
                        append(" · ").append(app.getString(R.string.unity_unified_import_skipped, result.placeholderSkippedTerms))
                    }
                    if (result.unknownTables.isNotEmpty()) {
                        append(" · ").append(app.getString(R.string.unity_unified_import_unknown_tables, result.unknownTables.joinToString(", ")))
                    }
                    if (result.unknownLanguages.isNotEmpty()) {
                        append(" · ").append(app.getString(R.string.unity_unified_import_unknown_languages, result.unknownLanguages.joinToString(", ")))
                    }
                    if (result.unknownKeyTerms > 0) {
                        append(" · ").append(app.getString(R.string.unity_unified_import_unknown_keys, result.unknownKeyTerms))
                    }
                }
                val message = if (failures.isEmpty()) status else localizationFailureMessage(failures.first().second)
                _state.update {
                    withTranslationDerived(
                        it.copy(
                            busy = false,
                            replacements = it.replacements + successes.mapValues { entry -> entry.value.first },
                            localizationReplacementEntries = it.localizationReplacementEntries + successes.keys,
                            localizationSources = it.localizationSources + newSources.filterKeys { name -> name in successes },
                            localizationRoundTripStatus = if (failures.isEmpty()) {
                                UnityLocalizationRoundTripStatus.PASSED
                            } else {
                                UnityLocalizationRoundTripStatus.FAILED
                            },
                            localizationRoundTripDetail = failures.firstOrNull()?.second?.message,
                            unifiedStatusText = if (failures.isEmpty()) {
                                status
                            } else {
                                app.getString(R.string.unity_unified_import_partial, successes.size, failures.size)
                            },
                            message = message,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                staged?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw error
            } catch (error: Throwable) {
                staged?.delete()
                _state.update { it.copy(busy = false, message = localizationFailureMessage(error)) }
            }
        }
    }

    /** Stages, strictly decodes and plans a user-selected TSV without mutating the bundle. */
    fun importLocalizationTsv(uri: Uri, resolver: ContentResolver) {
        val source = selectedLocalizationSource() ?: return
        val dir = stageDir ?: return
        val options = _state.value.textTransformOptions
        val sourceCode = _state.value.localizationSourceLanguageCode
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            var staged: File? = null
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    val file = stageImportedLocalizationTsv(dir) {
                        resolver.openInputStream(uri) ?: throw IOException("TSV source is unavailable")
                    }
                    staged = file
                    try {
                        file to planImportFromText(
                            source,
                            readLocalizationTsvUtf8(file),
                            options,
                            sourceCode,
                        )
                    } catch (error: Throwable) {
                        file.delete()
                        throw error
                    }
                }
                if (selectedLocalizationSource()?.origin?.tableId != source.origin.tableId) {
                    result.first.delete()
                    _state.update {
                        it.copy(
                            busy = false,
                            message = getApplication<Application>().getString(R.string.unity_localization_conflict),
                        )
                    }
                    return@launch
                }
                val plan = result.second
                _state.update {
                    withTranslationDerived(
                        it.copy(
                            busy = false,
                            localizationTsv = result.first,
                            localizationImportPlan = plan,
                            localizationAddPlan = null,
                            localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                            localizationRoundTripDetail = null,
                            message = getApplication<Application>().getString(
                                R.string.unity_localization_import_preview,
                                plan.changes.size,
                                plan.unknownKeyCount,
                                plan.missingKeyCount,
                                plan.emptyCellCount,
                                plan.placeholderFailureCount,
                            ),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                staged?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw error
            } catch (error: Throwable) {
                staged?.delete()
                _state.update { it.copy(busy = false, message = localizationFailureMessage(error)) }
            }
        }
    }

    fun planAddLocalizationLanguage(name: String, code: String, flags: Int? = null) {
        val source = selectedLocalizationSource() ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val targetCode = code.trim()
                val templateFlags = flags ?: source.languages.last().flags
                val inference = runInterruptible(Dispatchers.IO) {
                    UnityRtlFlagInferer.infer(
                        languages = source.languages,
                        targetLanguageCode = targetCode,
                        templateFlags = templateFlags,
                        directionResolver = languageDirectionResolver,
                    )
                }
                val plan = runInterruptible(Dispatchers.IO) {
                    UnityLocalizationOperations.planAddLanguage(
                        source,
                        LocalizationLanguage(name.trim(), targetCode, inference.targetFlags ?: templateFlags),
                    ).copy(rtlFlagInference = inference)
                }
                _state.update {
                    withTranslationDerived(
                        it.copy(
                            busy = false,
                            localizationAddPlan = plan,
                            localizationImportPlan = null,
                            localizationTargetLanguageCode = targetCode.takeIf { plan.canApply },
                            rtlFlagInference = inference,
                            localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                            localizationRoundTripDetail = null,
                            message = if (plan.canApply) {
                                getApplication<Application>().getString(
                                    R.string.unity_localization_add_preview,
                                    plan.termsUpdated,
                                    plan.estimatedDataDeltaBytes,
                                )
                            } else {
                                plan.diagnostic ?: getApplication<Application>().getString(
                                    R.string.unity_rtl_flag_not_determined,
                                )
                            },
                        ),
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
                    planImportFromText(
                        source,
                        readLocalizationTsvUtf8(file),
                        options,
                        sourceCode,
                    )
                }
                _state.update {
                    withTranslationDerived(
                        it.copy(
                            busy = false,
                            localizationImportPlan = plan,
                            localizationAddPlan = null,
                            localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                            localizationRoundTripDetail = null,
                            message = getApplication<Application>().getString(
                                R.string.unity_localization_import_preview,
                                plan.changes.size,
                                plan.unknownKeyCount,
                                plan.missingKeyCount,
                                plan.emptyCellCount,
                                plan.placeholderFailureCount,
                            ),
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

    /** Validates read-after-write on IO and stages the current plan without touching the source. */
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
        // Plain Apply must not overwrite a Persian staged I2 while
        // the Persian base is still referenced: plain terms
        // (logical, not PUA) with a Persian base (PUA glyphs, no
        // logical Arabic) would render as squares. Clear Persian
        // Apply first (Persian section), which drops the Persian
        // base reference; plain Apply then overwrites the staged I2
        // via overlay, as usual.
        if (state.persianApplied) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_persian_plain_apply_blocked))
            }
            return
        }
        if (!state.canValidateAndStageLocalization) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_readiness_complete_checks))
            }
            return
        }
        _state.update {
            it.copy(
                busy = true,
                message = null,
                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.RUNNING,
                localizationRoundTripDetail = null,
            )
        }
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
                    val existingEntryReplacement = state.replacements[entry.name]
                    if (existingEntryReplacement != null) {
                        UnityLocalizationOverlay.write(existingEntryReplacement, rewritten, staged, dir)
                    } else {
                        doc.writeLocalizationReplacement(entry, rewritten, staged)
                    }
                    verifyLocalizationRoundTrip(staged, rewritten, dir)
                }
                _state.update {
                    withTranslationDerived(
                        it.copy(
                            busy = false,
                            replacements = it.replacements + (entry.name to staged),
                            localizationReplacementEntries = it.localizationReplacementEntries + entry.name,
                            localizationSources = it.localizationSources + (entry.name to rewritten),
                            localizationAddPlan = null,
                            localizationImportPlan = null,
                            localizationTsv = null,
                            localizationSourceLanguageCode = state.localizationSourceLanguageCode
                                ?.takeIf { code -> updated.languageIndex(code) >= 0 }
                                ?: updated.languages.first().code,
                            localizationRoundTripStatus = UnityLocalizationRoundTripStatus.PASSED,
                            localizationRoundTripDetail = getApplication<Application>().getString(
                                R.string.unity_round_trip_verified,
                            ),
                            message = getApplication<Application>().getString(R.string.unity_localization_applied),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                staged.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                        localizationRoundTripDetail = null,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw e
            } catch (e: Exception) {
                staged.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        localizationRoundTripStatus = UnityLocalizationRoundTripStatus.FAILED,
                        localizationRoundTripDetail = e.message,
                        message = localizationFailureMessage(e),
                    )
                }
            }
        }
    }

    // ---- Unified multi-table editing ----

    fun setUnifiedSourceLanguage(code: String) {
        val known = _state.value.localizationSources.values.flatten()
            .any { it.languageIndex(code) >= 0 }
        if (!known) return
        _state.update { it.copy(unifiedSourceCode = code) }
    }

    fun setUnifiedTargetLanguage(code: String) {
        val known = _state.value.localizationSources.values.flatten()
            .any { it.languageIndex(code) >= 0 }
        if (!known) return
        _state.update { it.copy(unifiedTargetCode = code) }
    }

    fun setUnifiedExportLanguage(code: String?) {
        if (code != null) {
            val known = _state.value.localizationSources.values.flatten()
                .any { it.languageIndex(code) >= 0 }
            if (!known) return
        }
        _state.update { it.copy(unifiedExportLanguageCode = code) }
    }

    fun setUnifiedEdit(key: String, value: String) {
        _state.update { it.copy(unifiedEdits = it.unifiedEdits + (key to value)) }
    }

    fun clearUnifiedEdits() {
        _state.update { it.copy(unifiedEdits = emptyMap(), unifiedStatusText = null) }
    }

    /** Pure computation, no IO: one import plan per affected table. */
    fun unifiedPlans(): List<UnifiedTablePlan> {
        val state = _state.value
        val target = state.unifiedTargetCode ?: return emptyList()
        return UnityUnifiedLocalization.buildPlans(
            sourcesByEntry = state.localizationSources,
            edits = state.unifiedEdits,
            targetCode = target,
            sourceCode = state.unifiedSourceCode,
            options = state.textTransformOptions,
        )
    }

    /**
     * Writes the unified edits into every affected table, grouped by
     * bundle entry (one staged replacement per entry, round-trip
     * verified per entry, like [applyLocalizationPlan]). Edits whose
     * key is fully applied everywhere are dropped; edits still
     * differing from the staged values (failed entries, placeholder
     * skips) are kept so the user can retry them.
     */
    fun applyUnifiedEdits() {
        val doc = document ?: return
        val dir = stageDir ?: return
        val state = _state.value
        if (state.busy) return
        val target = state.unifiedTargetCode
        if (target == null) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_unified_select_target))
            }
            return
        }
        if (state.unifiedEdits.isEmpty()) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_unified_nothing_to_apply))
            }
            return
        }
        // Same guard as plain apply: a staged Persian I2 must not be
        // overwritten with plain values (they would render as
        // squares against the Persian base).
        if (state.persianApplied) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_persian_plain_apply_blocked))
            }
            return
        }
        val plans = UnityUnifiedLocalization.buildPlans(
            sourcesByEntry = state.localizationSources,
            edits = state.unifiedEdits,
            targetCode = target,
            sourceCode = state.unifiedSourceCode,
            options = state.textTransformOptions,
        ).filter { it.plan.canApply }
        if (plans.isEmpty()) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_unified_nothing_to_apply))
            }
            return
        }
        val plansByEntry = plans.groupBy { it.entryName }
        _state.update {
            it.copy(
                busy = true,
                message = null,
                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.RUNNING,
                localizationRoundTripDetail = null,
            )
        }
        viewModelScope.launch {
            val successes = LinkedHashMap<String, Pair<File, List<LocalizationSource>>>()
            val failures = ArrayList<Pair<String, Throwable>>()
            for ((entryName, entryPlans) in plansByEntry) {
                val entry = doc.summary.entries.firstOrNull { it.name == entryName }
                if (entry == null) {
                    failures += entryName to UnityFormatException("Bundle entry was not found: $entryName")
                    continue
                }
                val tables = state.localizationSources[entryName].orEmpty()
                val updatedById = entryPlans.associate { it.source.origin.tableId to it.plan.updated!! }
                val rewritten = tables.map { table -> updatedById[table.origin.tableId] ?: table }
                val staged = File(
                    dir,
                    "localization-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin",
                )
                try {
                    runInterruptible(Dispatchers.IO) {
                        val existingEntryReplacement = state.replacements[entry.name]
                        if (existingEntryReplacement != null) {
                            UnityLocalizationOverlay.write(existingEntryReplacement, rewritten, staged, dir)
                        } else {
                            doc.writeLocalizationReplacement(entry, rewritten, staged)
                        }
                        verifyLocalizationRoundTrip(staged, rewritten, dir)
                    }
                    successes[entryName] = staged to rewritten
                } catch (e: CancellationException) {
                    staged.delete()
                    successes.values.forEach { it.first.delete() }
                    throw e
                } catch (e: Exception) {
                    staged.delete()
                    failures += entryName to e
                }
            }
            // New sources map from the snapshot plus successful entries.
            val newSources = state.localizationSources.toMutableMap()
            for ((entryName, pair) in successes) newSources[entryName] = pair.second
            // Keep only edits not fully applied everywhere.
            val targetIndexOf: (LocalizationSource) -> Int = { it.languageIndex(target) }
            val keptEdits = state.unifiedEdits.filter { (key, value) ->
                newSources.values.flatten().any { source ->
                    val term = source.terms.firstOrNull { it.key == key }
                    val index = targetIndexOf(source)
                    term != null && index >= 0 && term.values.getOrNull(index) != value
                }
            }
            val appliedTerms = plans.filter { it.entryName in successes }.sumOf { it.plan.changes.size }
            val app = getApplication<Application>()
            val message = if (failures.isEmpty()) {
                app.getString(R.string.unity_unified_applied, successes.size, appliedTerms)
            } else {
                localizationFailureMessage(failures.first().second)
            }
            _state.update {
                withTranslationDerived(
                    it.copy(
                        busy = false,
                        replacements = it.replacements + successes.mapValues { entry -> entry.value.first },
                        localizationReplacementEntries = it.localizationReplacementEntries + successes.keys,
                        localizationSources = it.localizationSources + newSources.filterKeys { name -> name in successes },
                        unifiedEdits = keptEdits,
                        localizationRoundTripStatus = if (failures.isEmpty()) {
                            UnityLocalizationRoundTripStatus.PASSED
                        } else {
                            UnityLocalizationRoundTripStatus.FAILED
                        },
                        localizationRoundTripDetail = failures.firstOrNull()?.second?.message,
                        unifiedStatusText = if (failures.isEmpty()) {
                            app.getString(R.string.unity_unified_applied, successes.size, appliedTerms)
                        } else {
                            app.getString(R.string.unity_unified_partial, successes.size, failures.size)
                        },
                        message = message,
                    ),
                )
            }
        }
    }

    /**
     * Unified Persian Apply: one bake for every word in every
     * table, then PUA text staged into every affected table.
     * Unlike per-table [applyPersianPlan] this does NOT require
     * persianMode: terms come from the tables themselves (unified
     * edits override current target values), and the pipeline
     * shapes internally. The 3 file picks are shared with the
     * Persian section (same state fields, picked once there).
     * Terms whose placeholders mismatch are excluded with an
     * issue, never baked, never blocking the other tables.
     * Unified edits are KEPT on success: they are the logical
     * texts, so a later re-bake (fresh original base) still works;
     * the staged tables hold PUA, and the pipeline rejects PUA
     * input, so clearing edits would make re-baking impossible.
     * On failure, a previous successful Persian Apply is left
     * untouched (plain applies must stay blocked while PUA I2 is
     * staged); only issues / message / round-trip state change.
     */
    fun applyUnifiedPersian(resolver: ContentResolver) {
        val doc = document ?: return
        val dir = stageDir ?: return
        val startingState = _state.value
        if (startingState.busy) return
        val targetCode = startingState.unifiedTargetCode
        if (targetCode == null) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_unified_select_target))
            }
            return
        }
        val baseUri = startingState.persianBaseFallbackUri
        val sceneUri = startingState.persianSceneBundleUri
        val fontUri = startingState.persianFontUri
        if (baseUri == null || sceneUri == null || fontUri == null) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_persian_apply_no_files))
            }
            return
        }
        val gathered = UnityUnifiedPersian.gatherTerms(
            sourcesByEntry = startingState.localizationSources,
            edits = startingState.unifiedEdits,
            targetCode = targetCode,
            sourceCode = startingState.unifiedSourceCode,
        )
        val placeholderIssues = gathered.placeholderIssues
        fun reportNotReady(issues: List<String>) {
            _state.update {
                it.copy(
                    busy = false,
                    persianIssues = issues,
                    unifiedStatusText = getApplication<Application>().getString(
                        R.string.unity_persian_apply_not_ready, issues.size,
                    ),
                    localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                    localizationRoundTripDetail = null,
                    message = getApplication<Application>().getString(
                        R.string.unity_persian_apply_not_ready, issues.size,
                    ),
                )
            }
        }
        if (gathered.pipelineTerms.isEmpty()) {
            reportNotReady(placeholderIssues)
            return
        }
        _state.update {
            it.copy(
                busy = true,
                message = null,
                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.RUNNING,
                localizationRoundTripDetail = null,
            )
        }
        viewModelScope.launch {
            var baseTemp: File? = null
            var sceneTemp: File? = null
            var fontTemp: File? = null
            var baseStaged: File? = null
            var rebuiltStaged: File? = null
            var tmpOut: File? = null
            var texOut: File? = null
            var sceneCacheDir: File? = null
            var rebuiltBase: File? = null
            val stagedI2Files = ArrayList<File>()
            try {
                runInterruptible(Dispatchers.IO) {
                    // 1. Fast shaping/mapping check (before slow
                    // scene-bundle opening), same options as the
                    // pipeline (all ON).
                    val shapeOptions = UnityTextTransformOptions(
                        shapeArabic = true,
                        reverseBidi = true,
                        normalizePersian = true,
                    )
                    val shaped = gathered.pipelineTerms.map {
                        UnityTextShaper.transform(it.persianText, shapeOptions)
                    }
                    val batch = UnityPersianWordMapper.mapShapedTerms(shaped)
                    if (batch.unmappedCodepoints.isNotEmpty()) {
                        val unmappedIssues = batch.unmappedCodepoints.sorted().map { code ->
                            "Unmapped codepoint U+" + code.toString(16).uppercase().padStart(4, '0') +
                                " (logical Arabic the shaper did not shape, or extension block exhausted)"
                        }
                        reportNotReady(placeholderIssues + unmappedIssues)
                        return@runInterruptible
                    }
                    // 2. Copy picked files to stageDir (streaming,
                    // not readBytes: scene bundles can be 100+ MB).
                    baseTemp = copyUriToStageFile(resolver, baseUri, dir, "persian-base-", ".bin")
                    sceneTemp = copyUriToStageFile(resolver, sceneUri, dir, "persian-scene-", ".bundle")
                    fontTemp = copyUriToStageFile(resolver, fontUri, dir, "persian-font-", ".ttf")
                    baseStaged = File(dir, "persian-base-data-${System.nanoTime()}.bin")
                    val base = UnityBaseData.openBaseFile(baseTemp!!, baseStaged!!)
                    sceneCacheDir = File(dir, "persian-scene-cache-${System.nanoTime()}").apply { mkdirs() }
                    val (tmpTree, texTree) = UnityBundleReader.open(sceneTemp!!, sceneCacheDir!!).use { sceneDoc ->
                        UnityBaseData.findTmpFontAssetTree(sceneDoc.dataFile) to UnityBaseData.findTextureTree(sceneDoc.dataFile)
                    }
                    if (tmpTree == null) {
                        reportNotReady(
                            placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_no_tmp_tree),
                        )
                        return@runInterruptible
                    }
                    if (texTree == null) {
                        reportNotReady(
                            placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_no_tex_tree),
                        )
                        return@runInterruptible
                    }
                    // 3. Single TMP + single atlas Texture in base.
                    val monoSlots = base.index.slots.filter { it.classId == 114 }
                    val tmpSlots = ArrayList<dev.cluvex.strata.core.unity.SerializedFileIndex.Slot>()
                    for (slot in monoSlots) {
                        val raw = try {
                            UnityBaseData.readRawObject(base, slot)
                        } catch (_: Exception) {
                            continue
                        }
                        val decoded = try {
                            UnityBaseData.decodeWithExternalTree(tmpTree, raw, base.index.bigEndian)
                        } catch (_: Exception) {
                            continue
                        }
                        if (decoded is UVal.Struct &&
                            "m_GlyphTable" in decoded.fields &&
                            "m_CharacterTable" in decoded.fields
                        ) {
                            tmpSlots.add(slot)
                        }
                    }
                    if (tmpSlots.isEmpty()) {
                        reportNotReady(
                            placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_no_tmp_slot),
                        )
                        return@runInterruptible
                    }
                    if (tmpSlots.size > 1) {
                        reportNotReady(
                            placeholderIssues + getApplication<Application>().getString(
                                R.string.unity_persian_multiple_tmp, tmpSlots.size,
                            ),
                        )
                        return@runInterruptible
                    }
                    val texSlots = base.index.slots.filter { it.classId == 28 }
                    if (texSlots.isEmpty()) {
                        reportNotReady(
                            placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_no_tex_slot),
                        )
                        return@runInterruptible
                    }
                    if (texSlots.size > 1) {
                        reportNotReady(
                            placeholderIssues + getApplication<Application>().getString(
                                R.string.unity_persian_multiple_tex, texSlots.size,
                            ),
                        )
                        return@runInterruptible
                    }
                    val tmpSlot = tmpSlots.first()
                    val texSlot = texSlots.first()
                    val tmpVal = UnityBaseData.decodeWithExternalTree(tmpTree, UnityBaseData.readRawObject(base, tmpSlot), base.index.bigEndian)
                    val texVal = UnityBaseData.decodeWithExternalTree(texTree, UnityBaseData.readRawObject(base, texSlot), base.index.bigEndian)
                    if (tmpVal !is UVal.Struct || texVal !is UVal.Struct) {
                        throw UnityFormatException("Base TMP/Texture object is not a Struct (wrong object)")
                    }
                    // 4. Full pipeline: one batch, one baked atlas
                    // for every word in every table.
                    val typeface = Typeface.createFromFile(fontTemp!!)
                        ?: throw IOException("Could not load selected Persian font (not a valid TTF/OTF)")
                    val pipelineResult = UnityPersianApplyPipeline.preparePersianTerms(
                        gathered.pipelineTerms,
                        tmpVal,
                        texVal,
                        UnityPersianFontRasterizer(typeface),
                    )
                    if (pipelineResult !is UnityPersianApplyPipeline.PipelineResult.Ready) {
                        reportNotReady(placeholderIssues + pipelineResult.issues)
                        return@runInterruptible
                    }
                    // 5. Encode patched objects + rebuild base file
                    // + verify rebuilt by re-opening and decoding.
                    tmpOut = File(dir, "persian-tmp-${System.nanoTime()}.bin")
                    texOut = File(dir, "persian-tex-${System.nanoTime()}.bin")
                    tmpOut!!.writeBytes(UnityBaseData.encodeWithExternalTree(tmpTree, pipelineResult.patchedTmp, base.index.bigEndian))
                    texOut!!.writeBytes(UnityBaseData.encodeWithExternalTree(texTree, pipelineResult.patchedTexture, base.index.bigEndian))
                    rebuiltBase = File(dir, "persian-fallback-${System.nanoTime()}.bin")
                    UnityBaseData.rebuildBaseFile(
                        base,
                        rebuiltBase!!,
                        mapOf(tmpSlot.entryName to tmpOut!!, texSlot.entryName to texOut!!),
                    )
                    rebuiltStaged = File(dir, "persian-rebuilt-data-${System.nanoTime()}.bin")
                    val rebuilt = UnityBaseData.openBaseFile(rebuiltBase!!, rebuiltStaged!!)
                    val rebuiltTmp = UnityBaseData.decodeWithExternalTree(tmpTree, UnityBaseData.readRawObject(rebuilt, tmpSlot), rebuilt.index.bigEndian)
                    val rebuiltTex = UnityBaseData.decodeWithExternalTree(texTree, UnityBaseData.readRawObject(rebuilt, texSlot), rebuilt.index.bigEndian)
                    if (rebuiltTmp !is UVal.Struct || rebuiltTex !is UVal.Struct) {
                        throw UnityFormatException("Rebuilt base TMP/Texture is not a Struct (rebuild verification failed)")
                    }
                    val rebuiltGlyphs = (rebuiltTmp.fields["m_GlyphTable"] as? UVal.Arr)?.items?.size
                    val rebuiltChars = (rebuiltTmp.fields["m_CharacterTable"] as? UVal.Arr)?.items?.size
                    val expectedGlyphs = (pipelineResult.patchedTmp.fields["m_GlyphTable"] as? UVal.Arr)?.items?.size
                    if (rebuiltGlyphs != expectedGlyphs || rebuiltChars != expectedGlyphs) {
                        throw UnityFormatException("Rebuilt base glyph/char counts do not match patched TMP (rebuild verification failed)")
                    }
                    // 6. Stage PUA I2 per entry (same overlay +
                    // round-trip path as plain unified Apply).
                    // Distribution is positional: final term i
                    // belongs to gathered term ref i.
                    val puaByTable = UnityUnifiedPersian.distributeFinalTerms(gathered, pipelineResult.finalTerms)
                    val successes = LinkedHashMap<String, Pair<File, List<LocalizationSource>>>()
                    val failures = ArrayList<Pair<String, Throwable>>()
                    for ((entryName, tables) in startingState.localizationSources) {
                        val updatedById = HashMap<String, LocalizationSource>()
                        for (table in tables) {
                            val puaMap = puaByTable[table.origin.tableId] ?: continue
                            val targetIndex = table.languageIndex(targetCode)
                            if (targetIndex < 0) continue
                            val valuesByKey = puaMap.mapValues { (key, puaTerm) ->
                                val oldValues = table.terms.firstOrNull { it.key == key }?.values
                                    ?: throw UnityFormatException(
                                        "Unified Persian Apply: term key '$key' not in table '${table.name}'",
                                    )
                                oldValues.toMutableList().apply { set(targetIndex, puaTerm) }
                            }
                            updatedById[table.origin.tableId] = table.withValues(valuesByKey)
                        }
                        if (updatedById.isEmpty()) continue
                        val entry = doc.summary.entries.firstOrNull { it.name == entryName }
                        if (entry == null) {
                            failures += entryName to UnityFormatException("Bundle entry was not found: $entryName")
                            continue
                        }
                        val rewritten = tables.map { updatedById[it.origin.tableId] ?: it }
                        val stagedI2 = File(
                            dir,
                            "localization-persian-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin",
                        )
                        try {
                            val existingEntryReplacement = startingState.replacements[entry.name]
                            if (existingEntryReplacement != null) {
                                UnityLocalizationOverlay.write(existingEntryReplacement, rewritten, stagedI2, dir)
                            } else {
                                doc.writeLocalizationReplacement(entry, rewritten, stagedI2)
                            }
                            verifyLocalizationRoundTrip(stagedI2, rewritten, dir)
                            successes[entryName] = stagedI2 to rewritten
                            stagedI2Files += stagedI2
                        } catch (e: Exception) {
                            stagedI2.delete()
                            failures += entryName to e
                        }
                    }
                    // 7. Success state (no withTranslationDerived:
                    // bundle font coverage for PUA terms would
                    // misleadingly report missing PUA glyphs).
                    val rebuiltBytes = rebuiltBase!!.readBytes()
                    val crcHex = CRC32().apply { update(rebuiltBytes) }.value.toString(16).uppercase().padStart(8, '0')
                    val stats = pipelineResult.stats
                    val statsText = getApplication<Application>().getString(
                        R.string.unity_persian_apply_stats,
                        gathered.pipelineTerms.size,
                        stats.distinctWordCount,
                        stats.totalWordOccurrences,
                        stats.bakedWordOccurrences,
                        stats.letterFallbackOccurrences,
                        stats.atlasSize,
                        stats.atlasGrowthText,
                    )
                    val app = getApplication<Application>()
                    val doneMessage = if (failures.isEmpty()) {
                        if (placeholderIssues.isEmpty()) {
                            app.getString(
                                R.string.unity_persian_apply_done,
                                gathered.pipelineTerms.size,
                                stats.distinctWordCount,
                                stats.atlasSize,
                                stats.atlasGrowthText,
                                rebuiltBase!!.name,
                                rebuiltBase!!.length(),
                                crcHex,
                            )
                        } else {
                            app.getString(
                                R.string.unity_persian_apply_done_skipped,
                                gathered.pipelineTerms.size,
                                stats.distinctWordCount,
                                stats.atlasSize,
                                stats.atlasGrowthText,
                                rebuiltBase!!.name,
                                rebuiltBase!!.length(),
                                crcHex,
                                placeholderIssues.size,
                            )
                        }
                    } else {
                        localizationFailureMessage(failures.first().second)
                    }
                    val newSources = startingState.localizationSources.toMutableMap()
                    for ((entryName, pair) in successes) newSources[entryName] = pair.second
                    _state.update {
                        it.copy(
                            busy = false,
                            replacements = it.replacements + successes.mapValues { entry -> entry.value.first },
                            localizationReplacementEntries = it.localizationReplacementEntries + successes.keys,
                            localizationSources = it.localizationSources + newSources.filterKeys { name -> name in successes },
                            localizationRoundTripStatus = if (failures.isEmpty()) {
                                UnityLocalizationRoundTripStatus.PASSED
                            } else {
                                UnityLocalizationRoundTripStatus.FAILED
                            },
                            localizationRoundTripDetail = failures.firstOrNull()?.second?.message
                                ?: app.getString(R.string.unity_round_trip_verified),
                            fontCoverage = null,
                            persianReady = true,
                            persianIssues = placeholderIssues,
                            persianStatsText = statsText,
                            persianApplied = true,
                            persianPatchedBaseFile = rebuiltBase,
                            persianPatchedBaseCrc = crcHex,
                            unifiedStatusText = if (failures.isEmpty()) {
                                statsText
                            } else {
                                app.getString(R.string.unity_unified_partial, successes.size, failures.size)
                            },
                            message = doneMessage,
                        )
                    }
                }
            } catch (e: CancellationException) {
                stagedI2Files.forEach { it.delete() }
                rebuiltBase?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                        localizationRoundTripDetail = null,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw e
            } catch (e: Exception) {
                stagedI2Files.forEach { it.delete() }
                rebuiltBase?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        localizationRoundTripStatus = UnityLocalizationRoundTripStatus.FAILED,
                        localizationRoundTripDetail = e.message,
                        message = localizationFailureMessage(e),
                    )
                }
            } finally {
                baseTemp?.delete()
                sceneTemp?.delete()
                fontTemp?.delete()
                baseStaged?.delete()
                rebuiltStaged?.delete()
                tmpOut?.delete()
                texOut?.delete()
                sceneCacheDir?.deleteRecursively()
            }
        }
    }

    private fun selectedLocalizationSource(): LocalizationSource? = selectedLocalizationSource(_state.value)

    private fun selectedLocalizationSource(state: UnityAssetUiState): LocalizationSource? {
        val id = state.selectedLocalizationTableId ?: return null
        return state.localizationSources.values.flatten().firstOrNull { it.origin.tableId == id }
    }

    private fun pendingLocalizationSource(state: UnityAssetUiState): LocalizationSource? =
        state.localizationAddPlan?.takeIf { it.canApply }?.updated
            ?: state.localizationImportPlan?.takeIf { it.canApply }?.updated
            ?: selectedLocalizationSource(state)

    private fun withTranslationDerived(state: UnityAssetUiState): UnityAssetUiState {
        val source = pendingLocalizationSource(state)
        val target = state.localizationTargetLanguageCode
        val report = source?.origin?.entryName?.let(state.fontReports::get)
        val coverage = if (source != null && target != null && source.languageIndex(target) >= 0 && report != null) {
            UnityTranslationFontCoverageEvaluator.evaluate(
                report = report,
                source = source,
                targetLanguageCode = target,
                sourceLanguageCode = state.localizationSourceLanguageCode,
                // Coverage must follow the transforms that will be applied at import:
                // shaped text needs presentation-form glyphs, not the logical letters.
                transformOptions = state.textTransformOptions,
            )
        } else {
            null
        }
        return state.copy(fontCoverage = coverage)
    }

    private fun verifyLocalizationRoundTrip(
        replacement: File,
        expected: List<LocalizationSource>,
        cacheParent: File,
    ) {
        val verificationCache = File(cacheParent, "round-trip-${System.nanoTime()}")
        try {
            val probe = UnityBundleReader.probe(replacement)
            if (!probe.bundleSupported) throw UnityFormatException("Localization replacement could not be reopened")
            UnityBundleReader.open(replacement, verificationCache).use { reopened ->
                val actual = reopened.summary.entries.flatMap { candidateEntry ->
                    when (val inspection = reopened.inspectLocalizations(candidateEntry)) {
                        is UnityLocalizationInspection.Found -> inspection.sources
                        is UnityLocalizationInspection.Unavailable -> emptyList()
                    }
                }
                expected.forEach { wanted ->
                    val found = actual.firstOrNull { candidate ->
                        candidate.origin.partIndex == wanted.origin.partIndex &&
                            candidate.origin.pathId == wanted.origin.pathId &&
                            candidate.origin.termsPath == wanted.origin.termsPath &&
                            candidate.origin.languagesPath == wanted.origin.languagesPath &&
                            candidate.terms.map { it.key } == wanted.terms.map { it.key }
                    } ?: throw UnityFormatException("Localization table was not found after read-after-write")
                    if (found.languages != wanted.languages || found.terms != wanted.terms) {
                        throw UnityFormatException("Localization read-after-write values do not match the staged plan")
                    }
                }
            }
        } finally {
            verificationCache.deleteRecursively()
        }
    }

    private fun discardCreatedDocument(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (_: Exception) {
            // The primary export failure is reported; some providers do not permit delete.
        }
    }

    /**
     * Plans an import from picked text. A document starting with '{'
     * is a flat I2 JSON file (see [UnityLocalizationJson]) and is
     * converted to TSV first, so JSON and TSV share the same
     * validation in [UnityLocalizationTsv.planImport]. The staged
     * file keeps the original picked content, so re-planning a JSON
     * import converts it again instead of failing on the JSON text.
     */
    private fun planImportFromText(
        source: LocalizationSource,
        text: String,
        options: UnityTextTransformOptions,
        sourceCode: String?,
    ): LocalizationImportPlan {
        val tsv = if (text.trimStart().startsWith("{")) {
            UnityLocalizationJson.toTsv(source, text)
        } else {
            text
        }
        return UnityLocalizationTsv.planImport(source, tsv, options, sourceCode)
    }

    private fun localizationFailureMessage(error: Throwable): String {
        if (error is VirtualMachineError && error !is OutOfMemoryError) throw error
        if (error is ThreadDeath) throw error
        // Include the exception type: a bare message can be a bare
        // class name (as with a class-load failure), which alone
        // does not identify what actually failed.
        val typeName = error::class.simpleName
            ?: error::class.qualifiedName?.substringAfterLast('.')
            ?: "Error"
        val detail = error.message?.takeIf { it.isNotBlank() }?.let { "$typeName: $it" } ?: typeName
        return when {
            error is UnityDocumentClosedException -> getApplication<Application>().getString(R.string.unity_staging_unavailable)
            error is UnityOperationCancelledException -> getApplication<Application>().getString(R.string.unity_operation_cancelled)
            else -> getApplication<Application>().getString(R.string.unity_operation_failed_detail, detail)
        }
    }

    private fun nextDraftRequestId(): Long {
        nextEditorRequestId = if (nextEditorRequestId == Long.MAX_VALUE) 1L else nextEditorRequestId + 1L
        return nextEditorRequestId
    }

    private fun newEditorRequest(file: File): UnityEditorRequest {
        if (!file.isFile || !file.canRead()) throw IOException("Generated editor draft is unavailable")
        return UnityEditorRequest(nextDraftRequestId(), file.absolutePath)
    }

    private fun newTsvExportRequest(file: File): UnityTsvExportRequest {
        if (!file.isFile || !file.canRead()) throw IOException("Generated TSV draft is unavailable")
        return UnityTsvExportRequest(nextDraftRequestId(), file.name)
    }

    fun consumeEditorRequest(id: Long) {
        _state.update { current ->
            val remaining = consumeUnityEditorRequest(current.editorRequest, id)
            if (remaining === current.editorRequest) current else current.copy(editorRequest = remaining)
        }
    }

    fun consumeTsvExportRequest(id: Long) {
        _state.update { current ->
            val remaining = consumeUnityTsvExportRequest(current.tsvExportRequest, id)
            if (remaining === current.tsvExportRequest) current else current.copy(tsvExportRequest = remaining)
        }
    }

    private fun newJsonExportRequest(file: File): UnityJsonExportRequest {
        if (!file.isFile || !file.canRead()) throw IOException("Generated JSON draft is unavailable")
        return UnityJsonExportRequest(nextDraftRequestId(), file.name)
    }

    fun consumeJsonExportRequest(id: Long) {
        _state.update { current ->
            val remaining = consumeUnityJsonExportRequest(current.jsonExportRequest, id)
            if (remaining === current.jsonExportRequest) current else current.copy(jsonExportRequest = remaining)
        }
    }

    fun refreshEditedFiles() {
        val current = _state.value
        val changed = current.workingFiles.filter { (name, file) ->
            val baseline = workingBaselines[name]
            baseline != null && (file.length() != baseline.first || file.lastModified() != baseline.second)
        }
        _state.update { it.copy(editedFiles = changed) }
    }

    fun repack(
        parents: List<ArchiveParent>,
        storeUncompressed: Boolean = false,
        singleBlock: Boolean = false,
    ) {
        val doc = document ?: return
        if (!doc.repackSupported) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_repack_unsupported))
            }
            return
        }
        val current = _state.value
        if (current.busy) return
        val localizationChanged = current.localizationReplacementEntries.isNotEmpty()
        if (localizationChanged && !current.translationReadiness.canExport) {
            _state.update {
                it.copy(message = getApplication<Application>().getString(R.string.unity_readiness_complete_checks))
            }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val (output, signingHandoff) = runInterruptible(Dispatchers.IO) {
                    val file = UnityPackageRepacker.repack(
                        document = doc,
                        replacements = collectChanges(doc, current),
                        parents = parents,
                        cacheDir = getApplication<Application>().cacheDir,
                        maxBundleGrowthRatio = if (localizationChanged) 1.25 else null,
                        storeUncompressed = storeUncompressed,
                        singleBlock = singleBlock,
                    )
                    val isApk = file.extension.equals("apk", ignoreCase = true)
                    val signature = if (isApk) ApkSignatureInspector.inspect(file) else null
                    val handoff = UnityApkSigningHandoffEvaluator.evaluate(
                        isApk = isApk,
                        signatureVerified = signature?.verified == true,
                        certificateCount = signature?.certificates?.size ?: 0,
                    )
                    file to handoff
                }
                _state.update {
                    it.copy(
                        output = output,
                        outputSigningHandoff = signingHandoff,
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

    fun setNormalizePersian(enabled: Boolean) {
        exportedBaselines = emptyMap()
        _state.update {
            it.copy(textTransformOptions = it.textTransformOptions.copy(normalizePersian = enabled))
        }
    }

    // ---- Persian mode (Phase 4, part 3) ----
    // Toggling ON forces the 3 transform checkboxes OFF: the Persian
    // pipeline shapes + maps internally (UnityPersianApplyPipeline),
    // so I2 Apply must NOT re-shape / re-Bidi PUA text (that would
    // break visual order). Toggling OFF does not restore previous
    // transform settings; the user re-enables them manually if needed.
    // Terms are preserved across toggles (edits are not lost).
    fun setPersianMode(enabled: Boolean) {
        exportedBaselines = emptyMap()
        _state.update {
            it.copy(
                persianMode = enabled,
                textTransformOptions = if (enabled) UnityTextTransformOptions() else it.textTransformOptions,
            )
        }
    }

    fun setPersianTerm(termKey: String, text: String) {
        // Editing a term invalidates preliminary readiness AND any
        // full Apply (staged I2 + rebuilt base were built from the
        // old terms; their word codes would not match new terms).
        // Staged I2 in replacements is NOT deleted here (plain Apply
        // can still overwrite it via overlay, and deleting it would
        // lose the user's staged work); persianApplied/patched are
        // cleared so the UI cannot export a stale base file.
        _state.update {
            it.copy(
                persianTerms = it.persianTerms + (termKey to text),
                persianReady = false,
                persianIssues = emptyList(),
                persianStatsText = null,
                persianApplied = false,
                persianPatchedBaseFile = null,
                persianPatchedBaseCrc = null,
            )
        }
    }

    fun setPersianBaseFallbackUri(uri: Uri?) {
        _state.update { it.copy(persianBaseFallbackUri = uri) }
    }

    fun setPersianSceneBundleUri(uri: Uri?) {
        _state.update { it.copy(persianSceneBundleUri = uri) }
    }

    fun setPersianFontUri(uri: Uri?) {
        _state.update { it.copy(persianFontUri = uri) }
    }

    // Phase 5 prefab repoint setters. Changing any input clears
    // the previous patched output (it was built from the old
    // inputs, like setPersianTerm clearing a stale Apply).
    fun setPersianPrefabUri(uri: Uri?) {
        _state.update {
            it.copy(
                persianPrefabUri = uri,
                persianPrefabPatchedFile = null,
                persianPrefabPatchedCrc = null,
                persianPrefabReport = null,
            )
        }
    }

    fun setPersianPrefabSourceGuid(guid: String) {
        _state.update {
            it.copy(
                persianPrefabSourceGuid = guid,
                persianPrefabPatchedFile = null,
                persianPrefabPatchedCrc = null,
                persianPrefabReport = null,
            )
        }
    }

    fun setPersianPrefabTargetGuid(guid: String) {
        _state.update {
            it.copy(
                persianPrefabTargetGuid = guid,
                persianPrefabPatchedFile = null,
                persianPrefabPatchedCrc = null,
                persianPrefabReport = null,
            )
        }
    }

    fun setPersianPrefabPptrFromFile(value: String) {
        _state.update {
            it.copy(
                persianPrefabPptrFromFile = value,
                persianPrefabPatchedFile = null,
                persianPrefabPatchedCrc = null,
                persianPrefabReport = null,
            )
        }
    }

    fun setPersianPrefabPptrFromPath(value: String) {
        _state.update {
            it.copy(
                persianPrefabPptrFromPath = value,
                persianPrefabPatchedFile = null,
                persianPrefabPatchedCrc = null,
                persianPrefabReport = null,
            )
        }
    }

    fun setPersianPrefabPptrToFile(value: String) {
        _state.update {
            it.copy(
                persianPrefabPptrToFile = value,
                persianPrefabPatchedFile = null,
                persianPrefabPatchedCrc = null,
                persianPrefabReport = null,
            )
        }
    }

    fun setPersianPrefabPptrToPath(value: String) {
        _state.update {
            it.copy(
                persianPrefabPptrToPath = value,
                persianPrefabPatchedFile = null,
                persianPrefabPatchedCrc = null,
                persianPrefabReport = null,
            )
        }
    }

    fun clearPersianPrefab() {
        _state.update {
            it.copy(
                persianPrefabPatchedFile = null,
                persianPrefabPatchedCrc = null,
                persianPrefabReport = null,
                message = getApplication<Application>().getString(R.string.unity_persian_prefab_cleared),
            )
        }
    }

    fun clearPersianApply() {
        // Clears Apply results only, not terms: the user can re-run
        // preliminary checks or Apply again with the same terms.
        // Staged I2 in replacements is left in place (plain Apply
        // overwrites it via overlay); the rebuilt base file stays
        // on disk in stageDir until the document closes (stageDir
        // cleanup), but is no longer referenced, so the UI cannot
        // export it as if it matched current terms.
        _state.update {
            it.copy(
                persianApplied = false,
                persianPatchedBaseFile = null,
                persianPatchedBaseCrc = null,
                persianReady = false,
                persianIssues = emptyList(),
                persianStatsText = null,
                message = getApplication<Application>().getString(R.string.unity_persian_apply_cleared),
            )
        }
    }

    // Loads Persian terms from the current import / add-language plan's
    // updated source, for the selected target language. The plan comes
    // from the existing TSV import flow (user edits TSV externally
    // with Persian text, imports, then loads here for in-app editing
    // and readiness checking). No plan -> no load (clear message in
    // the Persian section, not a crash, and not a load of original
    // (non-Persian) terms as if they were Persian).
    fun loadPersianTermsFromImportPlan() {
        val state = _state.value
        val updated = when {
            state.localizationAddPlan?.canApply == true -> state.localizationAddPlan.updated
            state.localizationImportPlan?.canApply == true -> state.localizationImportPlan.updated
            else -> null
        }
        if (updated == null) {
            _state.update {
                it.copy(
                    persianReady = false,
                    persianIssues = listOf(
                        getApplication<Application>().getString(R.string.unity_persian_no_plan),
                    ),
                    persianStatsText = null,
                )
            }
            return
        }
        val targetCode = state.localizationTargetLanguageCode
        val targetIndex = targetCode?.let { updated.languageIndex(it) } ?: -1
        if (targetIndex < 0) {
            _state.update {
                it.copy(
                    persianReady = false,
                    persianIssues = listOf(
                        getApplication<Application>().getString(R.string.unity_persian_no_target),
                    ),
                    persianStatsText = null,
                )
            }
            return
        }
        val loaded = updated.terms.associate { term -> term.key to (term.values[targetIndex] ?: "") }
        _state.update {
            it.copy(
                persianTerms = loaded,
                persianReady = false,
                persianIssues = emptyList(),
                persianStatsText = null,
                message = getApplication<Application>().getString(
                    R.string.unity_persian_terms_loaded,
                    loaded.size,
                ),
            )
        }
    }

    // Preliminary Persian readiness: shaping + mapping + ranking only
    // (no font, no base fallback, no baking). Fast, pure CPU work on
    // IO (same pattern as planLocalizationImport), so the UI stays
    // responsive for large tables (e.g. subtitles, 397 terms).
    // A passing preliminary check does NOT mean the full pipeline
    // will succeed (font coverage and base fallback are checked by
    // the full pipeline in part 4); a failing check DOES mean Apply
    // must be blocked (unmapped codes would render as squares).
    fun checkPersianReadiness() {
        val startingState = _state.value
        if (startingState.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val terms = startingState.persianTerms.entries.map { (key, text) ->
                    UnityPersianApplyPipeline.PersianTermInput(key, text)
                }
                val (ready, issues, statsText) = runInterruptible(Dispatchers.IO) {
                    if (terms.isEmpty()) {
                        Triple(
                            false,
                            listOf(
                                getApplication<Application>().getString(R.string.unity_persian_no_terms),
                            ),
                            null as String?,
                        )
                    } else {
                        val shapeOptions = UnityTextTransformOptions(
                            shapeArabic = true,
                            reverseBidi = true,
                            normalizePersian = true,
                        )
                        val shaped = terms.map { UnityTextShaper.transform(it.persianText, shapeOptions) }
                        val batch = UnityPersianWordMapper.mapShapedTerms(shaped)
                        if (batch.unmappedCodepoints.isNotEmpty()) {
                            val unmappedIssues = batch.unmappedCodepoints.sorted().map { code ->
                                "Unmapped codepoint U+" + code.toString(16).uppercase().padStart(4, '0') +
                                    " (logical Arabic the shaper did not shape, or extension block exhausted)"
                            }
                            Triple(false, unmappedIssues, null as String?)
                        } else {
                            val ranked = UnityPersianFrequency.rankWords(batch.mappedTerms, batch.wordMap)
                            val totalOcc = ranked.sumOf { it.frequency }
                            val recommended = UnityPersianFrequency.recommendAtlasSize(batch.wordMap.size)
                            val text = getApplication<Application>().getString(
                                R.string.unity_persian_readiness_stats,
                                terms.size,
                                batch.wordMap.size,
                                totalOcc,
                                batch.overflowWords.size,
                                recommended,
                            )
                            Triple(true, emptyList<String>(), text)
                        }
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        persianReady = ready,
                        persianIssues = issues,
                        persianStatsText = statsText,
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

    // Full one-click Persian Apply (Phase 4 part 4). Takes the
    // in-app Persian terms (logical Persian, from Load terms or the
    // editor), validates placeholders, runs the full pipeline
    // (shape -> map -> rank -> rasterize with the picked TTF ->
    // bake -> letter fallback -> coverage gate -> TMP/Texture
    // patch), rebuilds the picked base fallback file, and stages
    // the PUA I2 terms through the same overlay + round-trip path
    // as plain Apply (applyLocalizationPlan). Transforms must stay
    // OFF (persianMode forces them OFF): PUA terms are already
    // shaped + Bidi-ordered, and re-shaping them would break
    // visual order. Add/import plans are NOT consumed here (PUA
    // terms come from persianTerms, not from plans); they stay in
    // state for a later plain Apply (after Clear, if needed). Preliminary readiness (persianReady) is NOT a
    // gate here: the pipeline re-checks shaping/mapping itself,
    // plus font, base and coverage, which preliminary does not.
    // A fast shaping/mapping check runs BEFORE opening the scene
    // bundle (which can take minutes for large bundles) so obvious
    // unmapped-code failures report in seconds, not minutes; the
    // pipeline remains the final gate, so a fast-check/pipeline
    // divergence would only cost time, never ship a bad Apply.
    // Outputs: staged I2 file in replacements (as for plain Apply,
    // repacked normally) + rebuilt base file in
    // persianPatchedBaseFile (NOT a bundle replacement: base files
    // are not bundle entries, so repack does not include it; the
    // user exports it and installs it via MT Manager, with the
    // size + CRC in the success message, as for Bendy fallbacks).
    // Catalog CRC is NOT patched here (v1): coredata-style bundles
    // (Crc 0) need no catalog change, and nonzero-CRC bundles
    // (e.g. Bendy defaultlocalgroup) need a manual catalog patch
    // after repack (crc32(bundle[144:]), as before). The success
    // message says so conditionally, so the user checks the
    // original CRC first instead of patching unconditionally.
    // On any failure before I2 staging, nothing is staged and the
    // rebuilt base (if any) is deleted, so a partial Apply can
    // never look like success. Empty terms are included when
    // their placeholders match (expected empty): they bake no
    // words and apply as empty (same as old, or an explicit clear).
    fun applyPersianPlan(entry: UnityBundleEntry, resolver: ContentResolver) {
        val doc = document ?: return
        val dir = stageDir ?: return
        val startingState = _state.value
        if (startingState.busy) return
        if (!startingState.persianMode) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_apply_needs_mode)) }
            return
        }
        val selected = selectedLocalizationSource(startingState)
        if (selected == null) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_no_table)) }
            return
        }
        val targetCode = startingState.localizationTargetLanguageCode
        val targetIndex = if (targetCode != null) selected.languageIndex(targetCode) else -1
        if (targetIndex < 0) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_no_target)) }
            return
        }
        val baseUri = startingState.persianBaseFallbackUri
        val sceneUri = startingState.persianSceneBundleUri
        val fontUri = startingState.persianFontUri
        if (baseUri == null || sceneUri == null || fontUri == null) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_apply_no_files)) }
            return
        }
        val persianTerms = startingState.persianTerms
        if (persianTerms.isEmpty()) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_no_terms)) }
            return
        }
        val tables = startingState.localizationSources[entry.name].orEmpty()
        if (tables.none { it.origin.tableId == selected.origin.tableId }) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_no_table)) }
            return
        }
        _state.update {
            it.copy(
                busy = true,
                message = null,
                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.RUNNING,
                localizationRoundTripDetail = null,
            )
        }
        viewModelScope.launch {
            var baseTemp: File? = null
            var sceneTemp: File? = null
            var fontTemp: File? = null
            var baseStaged: File? = null
            var rebuiltStaged: File? = null
            var tmpOut: File? = null
            var texOut: File? = null
            var sceneCacheDir: File? = null
            var stagedI2: File? = null
            var rebuiltBase: File? = null
            try {
                runInterruptible(Dispatchers.IO) {
                    // 1. Placeholder validation (fast, pure). Expected
                    // directives come from the ORIGINAL source-language
                    // term (as in planImport), not from the target's
                    // old value: placeholders are game logic.
                    val sourceCode = startingState.localizationSourceLanguageCode
                        ?.takeIf { code -> selected.languageIndex(code) >= 0 }
                        ?: selected.languages.first().code
                    val sourceIndex = selected.languageIndex(sourceCode).coerceAtLeast(0)
                    val expectedByKey = selected.terms.associate { term ->
                        term.key to UnityPersianPlaceholders.directives(term.values[sourceIndex].orEmpty())
                    }
                    val termInputs = persianTerms.entries.map { (key, text) ->
                        UnityPersianPlaceholders.TermInput(key, text)
                    }
                    val placeholderResult = UnityPersianPlaceholders.validateTerms(termInputs, expectedByKey)
                    val placeholderIssues = placeholderResult.issues
                    val validTerms = placeholderResult.validTerms
                    if (validTerms.isEmpty()) {
                        _state.update {
                            it.copy(
                                busy = false,
                                persianReady = false,
                                persianIssues = placeholderIssues,
                                persianStatsText = null,
                                persianApplied = false,
                                persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                message = getApplication<Application>().getString(R.string.unity_persian_apply_not_ready, placeholderIssues.size),
                            )
                        }
                        return@runInterruptible
                    }
                    // 2. Fast shaping/mapping check (before slow
                    // scene-bundle opening). Same options as the
                    // pipeline (all ON, plain transform, which is
                    // correct for placeholders: shaper breaks joining
                    // at non-Arabic directives, and whole-term Bidi
                    // places LTR directive runs correctly).
                    val shapeOptions = UnityTextTransformOptions(
                        shapeArabic = true,
                        reverseBidi = true,
                        normalizePersian = true,
                    )
                    val shaped = validTerms.map { UnityTextShaper.transform(it.persianText, shapeOptions) }
                    val batch = UnityPersianWordMapper.mapShapedTerms(shaped)
                    if (batch.unmappedCodepoints.isNotEmpty()) {
                        val unmappedIssues = batch.unmappedCodepoints.sorted().map { code ->
                            "Unmapped codepoint U+" + code.toString(16).uppercase().padStart(4, '0') +
                                " (logical Arabic the shaper did not shape, or extension block exhausted)"
                        }
                        _state.update {
                            it.copy(
                                busy = false,
                                persianReady = false,
                                persianIssues = placeholderIssues + unmappedIssues,
                                persianStatsText = null,
                                persianApplied = false,
                                persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                message = getApplication<Application>().getString(R.string.unity_persian_apply_not_ready, placeholderIssues.size + unmappedIssues.size),
                            )
                        }
                        return@runInterruptible
                    }
                    // 3. Copy picked files to stageDir (streaming,
                    // not readBytes: scene bundles can be 100+ MB,
                    // and readBytes would hold the whole file in RAM).
                    baseTemp = copyUriToStageFile(resolver, baseUri, dir, "persian-base-", ".bin")
                    sceneTemp = copyUriToStageFile(resolver, sceneUri, dir, "persian-scene-", ".bundle")
                    fontTemp = copyUriToStageFile(resolver, fontUri, dir, "persian-font-", ".ttf")
                    baseStaged = File(dir, "persian-base-data-${System.nanoTime()}.bin")
                    val base = UnityBaseData.openBaseFile(baseTemp!!, baseStaged!!)
                    sceneCacheDir = File(dir, "persian-scene-cache-${System.nanoTime()}").apply { mkdirs() }
                    val (tmpTree, texTree) = UnityBundleReader.open(sceneTemp!!, sceneCacheDir!!).use { sceneDoc ->
                        UnityBaseData.findTmpFontAssetTree(sceneDoc.dataFile) to UnityBaseData.findTextureTree(sceneDoc.dataFile)
                    }
                    if (tmpTree == null) {
                        _state.update {
                            it.copy(
                                busy = false,
                                persianReady = false,
                                persianApplied = false,
                                persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                                persianStatsText = null,
                                persianIssues = placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_no_tmp_tree),
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                message = getApplication<Application>().getString(R.string.unity_persian_apply_not_ready, placeholderIssues.size + 1),
                            )
                        }
                        return@runInterruptible
                    }
                    if (texTree == null) {
                        _state.update {
                            it.copy(
                                busy = false,
                                persianReady = false,
                                persianApplied = false,
                                persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                                persianStatsText = null,
                                persianIssues = placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_no_tex_tree),
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                message = getApplication<Application>().getString(R.string.unity_persian_apply_not_ready, placeholderIssues.size + 1),
                            )
                        }
                        return@runInterruptible
                    }
                    // 4. Find the single TMP + single atlas Texture in
                    // the base file. Multiple TMPs / Textures are
                    // blocked with a clear message (v1 only handles
                    // single-fallback files, like Bendy 2e498d1c);
                    // picking the first of several would silently
                    // patch the wrong font/atlas.
                    val monoSlots = base.index.slots.filter { it.classId == 114 }
                    val tmpSlots = ArrayList<dev.cluvex.strata.core.unity.SerializedFileIndex.Slot>()
                    for (slot in monoSlots) {
                        val raw = try {
                            UnityBaseData.readRawObject(base, slot)
                        } catch (_: Exception) {
                            continue
                        }
                        val decoded = try {
                            UnityBaseData.decodeWithExternalTree(tmpTree, raw, base.index.bigEndian)
                        } catch (_: Exception) {
                            continue
                        }
                        if (decoded is UVal.Struct &&
                            "m_GlyphTable" in decoded.fields &&
                            "m_CharacterTable" in decoded.fields
                        ) {
                            tmpSlots.add(slot)
                        }
                    }
                    if (tmpSlots.isEmpty()) {
                        _state.update {
                            it.copy(
                                busy = false,
                                persianReady = false,
                                persianApplied = false,
                                persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                                persianStatsText = null,
                                persianIssues = placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_no_tmp_slot),
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                message = getApplication<Application>().getString(R.string.unity_persian_apply_not_ready, placeholderIssues.size + 1),
                            )
                        }
                        return@runInterruptible
                    }
                    if (tmpSlots.size > 1) {
                        _state.update {
                            it.copy(
                                busy = false,
                                persianReady = false,
                                persianApplied = false,
                                persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                                persianStatsText = null,
                                persianIssues = placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_multiple_tmp, tmpSlots.size),
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                message = getApplication<Application>().getString(R.string.unity_persian_apply_not_ready, placeholderIssues.size + 1),
                            )
                        }
                        return@runInterruptible
                    }
                    val texSlots = base.index.slots.filter { it.classId == 28 }
                    if (texSlots.isEmpty()) {
                        _state.update {
                            it.copy(
                                busy = false,
                                persianReady = false,
                                persianApplied = false,
                                persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                                persianStatsText = null,
                                persianIssues = placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_no_tex_slot),
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                message = getApplication<Application>().getString(R.string.unity_persian_apply_not_ready, placeholderIssues.size + 1),
                            )
                        }
                        return@runInterruptible
                    }
                    if (texSlots.size > 1) {
                        _state.update {
                            it.copy(
                                busy = false,
                                persianReady = false,
                                persianApplied = false,
                                persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                                persianStatsText = null,
                                persianIssues = placeholderIssues + getApplication<Application>().getString(R.string.unity_persian_multiple_tex, texSlots.size),
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                message = getApplication<Application>().getString(R.string.unity_persian_apply_not_ready, placeholderIssues.size + 1),
                            )
                        }
                        return@runInterruptible
                    }
                    val tmpSlot = tmpSlots.first()
                    val texSlot = texSlots.first()
                    val tmpVal = UnityBaseData.decodeWithExternalTree(tmpTree, UnityBaseData.readRawObject(base, tmpSlot), base.index.bigEndian)
                    val texVal = UnityBaseData.decodeWithExternalTree(texTree, UnityBaseData.readRawObject(base, texSlot), base.index.bigEndian)
                    if (tmpVal !is UVal.Struct || texVal !is UVal.Struct) {
                        throw UnityFormatException("Base TMP/Texture object is not a Struct (wrong object)")
                    }
                    // 5. Full pipeline (font rasterizer uses the
                    // picked TTF; temp TTF is deleted in finally,
                    // after the pipeline returns, since the rasterizer
                    // is only used during the pipeline call).
                    val typeface = Typeface.createFromFile(fontTemp!!)
                        ?: throw IOException("Could not load selected Persian font (not a valid TTF/OTF)")
                    val pipelineTerms = validTerms.map { UnityPersianApplyPipeline.PersianTermInput(it.termKey, it.persianText) }
                    val pipelineResult = UnityPersianApplyPipeline.preparePersianTerms(
                        pipelineTerms,
                        tmpVal,
                        texVal,
                        UnityPersianFontRasterizer(typeface),
                    )
                    if (pipelineResult !is UnityPersianApplyPipeline.PipelineResult.Ready) {
                        val issues = placeholderIssues + pipelineResult.issues
                        _state.update {
                            it.copy(
                                busy = false,
                                persianReady = false,
                                persianIssues = issues,
                                persianStatsText = null,
                                persianApplied = false,
                                persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                                localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                                localizationRoundTripDetail = null,
                                message = getApplication<Application>().getString(R.string.unity_persian_apply_not_ready, issues.size),
                            )
                        }
                        return@runInterruptible
                    }
                    // 6. Encode patched objects + rebuild base file.
                    // rebuildBaseFile refuses to overwrite an existing
                    // output, so all outputs here use fresh nanoTime
                    // names (see finally: intermediates are deleted,
                    // outputs are kept).
                    tmpOut = File(dir, "persian-tmp-${System.nanoTime()}.bin")
                    texOut = File(dir, "persian-tex-${System.nanoTime()}.bin")
                    tmpOut!!.writeBytes(UnityBaseData.encodeWithExternalTree(tmpTree, pipelineResult.patchedTmp, base.index.bigEndian))
                    texOut!!.writeBytes(UnityBaseData.encodeWithExternalTree(texTree, pipelineResult.patchedTexture, base.index.bigEndian))
                    rebuiltBase = File(dir, "persian-fallback-${System.nanoTime()}.bin")
                    UnityBaseData.rebuildBaseFile(
                        base,
                        rebuiltBase!!,
                        mapOf(tmpSlot.entryName to tmpOut!!, texSlot.entryName to texOut!!),
                    )
                    // Verify rebuilt base by re-opening it and
                    // decoding TMP/Texture with the same trees, as
                    // in VM patcher/pipeline tests (rebuilt objects
                    // must equal patched objects, counts must match).
                    rebuiltStaged = File(dir, "persian-rebuilt-data-${System.nanoTime()}.bin")
                    val rebuilt = UnityBaseData.openBaseFile(rebuiltBase!!, rebuiltStaged!!)
                    val rebuiltTmp = UnityBaseData.decodeWithExternalTree(tmpTree, UnityBaseData.readRawObject(rebuilt, tmpSlot), rebuilt.index.bigEndian)
                    val rebuiltTex = UnityBaseData.decodeWithExternalTree(texTree, UnityBaseData.readRawObject(rebuilt, texSlot), rebuilt.index.bigEndian)
                    if (rebuiltTmp !is UVal.Struct || rebuiltTex !is UVal.Struct) {
                        throw UnityFormatException("Rebuilt base TMP/Texture is not a Struct (rebuild verification failed)")
                    }
                    val rebuiltGlyphs = (rebuiltTmp.fields["m_GlyphTable"] as? UVal.Arr)?.items?.size
                    val rebuiltChars = (rebuiltTmp.fields["m_CharacterTable"] as? UVal.Arr)?.items?.size
                    val expectedGlyphs = (pipelineResult.patchedTmp.fields["m_GlyphTable"] as? UVal.Arr)?.items?.size
                    if (rebuiltGlyphs != expectedGlyphs || rebuiltChars != expectedGlyphs) {
                        throw UnityFormatException("Rebuilt base glyph/char counts do not match patched TMP (rebuild verification failed)")
                    }
                    // 7. Stage PUA I2 terms (same overlay +
                    // round-trip path as plain Apply). Terms not in
                    // validTerms keep old target values (withValues
                    // preserves missing keys); skipped terms are in
                    // persianIssues, not silently dropped.
                    val finalByKey = validTerms.mapIndexed { i, term ->
                        term.termKey to pipelineResult.finalTerms[i]
                    }.toMap()
                    val originalByKey = selected.terms.associateBy { it.key }
                    val valuesByKey = finalByKey.mapValues { (key, puaTerm) ->
                        val oldValues = originalByKey[key]?.values
                            ?: throw UnityFormatException("Persian Apply: valid term key '$key' not in selected table (placeholder validation missed an unknown key)")
                        oldValues.toMutableList().apply { set(targetIndex, puaTerm) }
                    }
                    val updated = selected.withValues(valuesByKey)
                    val rewritten = tables.map { table ->
                        if (table.origin.tableId == selected.origin.tableId) updated else table
                    }
                    stagedI2 = File(
                        dir,
                        "localization-persian-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin",
                    )
                    val existingEntryReplacement = startingState.replacements[entry.name]
                    if (existingEntryReplacement != null) {
                        UnityLocalizationOverlay.write(existingEntryReplacement, rewritten, stagedI2!!, dir)
                    } else {
                        doc.writeLocalizationReplacement(entry, rewritten, stagedI2!!)
                    }
                    verifyLocalizationRoundTrip(stagedI2!!, rewritten, dir)
                    // Success: rebuilt base CRC (MT Manager format:
                    // hex, uppercase, 8 digits, as for Bendy).
                    val rebuiltBytes = rebuiltBase!!.readBytes()
                    val crcHex = CRC32().apply { update(rebuiltBytes) }.value.toString(16).uppercase().padStart(8, '0')
                    val stats = pipelineResult.stats
                    val statsText = getApplication<Application>().getString(
                        R.string.unity_persian_apply_stats,
                        validTerms.size,
                        stats.distinctWordCount,
                        stats.totalWordOccurrences,
                        stats.bakedWordOccurrences,
                        stats.letterFallbackOccurrences,
                        stats.atlasSize,
                        stats.atlasGrowthText,
                    )
                    val doneMessage = if (placeholderIssues.isEmpty()) {
                        getApplication<Application>().getString(
                            R.string.unity_persian_apply_done,
                            validTerms.size,
                            stats.distinctWordCount,
                            stats.atlasSize,
                            stats.atlasGrowthText,
                            rebuiltBase!!.name,
                            rebuiltBase!!.length(),
                            crcHex,
                        )
                    } else {
                        getApplication<Application>().getString(
                            R.string.unity_persian_apply_done_skipped,
                            validTerms.size,
                            stats.distinctWordCount,
                            stats.atlasSize,
                            stats.atlasGrowthText,
                            rebuiltBase!!.name,
                            rebuiltBase!!.length(),
                            crcHex,
                            placeholderIssues.size,
                        )
                    }
                    _state.update {
                        // No withTranslationDerived here: bundle font
                        // coverage for PUA terms would misleadingly
                        // report missing PUA glyphs (PUA is covered
                        // by the patched base fallback, not by bundle
                        // fonts). Pipeline coverage already gates Apply.
                        it.copy(
                            busy = false,
                            replacements = it.replacements + (entry.name to stagedI2!!),
                            localizationReplacementEntries = it.localizationReplacementEntries + entry.name,
                            localizationSources = it.localizationSources + (entry.name to rewritten),
                            localizationRoundTripStatus = UnityLocalizationRoundTripStatus.PASSED,
                            localizationRoundTripDetail = getApplication<Application>().getString(R.string.unity_round_trip_verified),
                            fontCoverage = null,
                            persianReady = true,
                            persianIssues = placeholderIssues,
                            persianStatsText = statsText,
                            persianApplied = true,
                            persianPatchedBaseFile = rebuiltBase,
                                persianPatchedBaseCrc = crcHex,
                            message = doneMessage,
                        )
                    }
                }
            } catch (e: CancellationException) {
                stagedI2?.delete()
                rebuiltBase?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        localizationRoundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
                        localizationRoundTripDetail = null,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw e
            } catch (e: Exception) {
                stagedI2?.delete()
                rebuiltBase?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        localizationRoundTripStatus = UnityLocalizationRoundTripStatus.FAILED,
                        localizationRoundTripDetail = e.message,
                        persianApplied = false,
                        persianPatchedBaseFile = null,
                                persianPatchedBaseCrc = null,
                        message = localizationFailureMessage(e),
                    )
                }
            } finally {
                baseTemp?.delete()
                sceneTemp?.delete()
                fontTemp?.delete()
                baseStaged?.delete()
                rebuiltStaged?.delete()
                tmpOut?.delete()
                texOut?.delete()
                sceneCacheDir?.deleteRecursively()
            }
        }
    }

    // Phase 5: repoint a standalone prefab serialized file from a
    // static TMP font asset to the Persian dynamic fallback.
    // Order matters and mirrors the VM proof (MainPrefabRepointTest):
    // 1. GUID replacement is metadata-only (headerSize..dataOffset).
    // 2. When a PPtr remap is given, the PPtr is first patched
    //    inside its MonoBehaviour object and the file is rebuilt
    //    (SerializedFileIndex.rebuild, which copies the ORIGINAL
    //    metadata verbatim), and only THEN is the GUID replaced
    //    on the rebuilt bytes -- doing GUID first would lose the
    //    edit to the rebuild's verbatim metadata copy.
    // 3. The final file is verified by re-parsing: same object
    //    count and same per-object sizes (both edits are
    //    size-preserving, so any size change means corruption).
    // PPtr safety: matches are collected over EVERY MonoBehaviour
    // object (classId 114) and exactly one in total is required.
    // Zero matches (wrong reference) and multiple matches
    // (ambiguous: repointing one would risk the wrong component)
    // are blocked with clear messages, not guessed.
    // This does NOT require persianMode or an open document: the
    // prefab file is standalone. It also does NOT repack the file
    // into a bundle (v1 limit, stated in the UI): the user
    // exports the patched file and installs it like the Bendy
    // prefabs (MT Manager), with size + CRC to verify.
    fun repointPersianPrefab(resolver: ContentResolver) {
        val startingState = _state.value
        if (startingState.busy) return
        val prefabUri = startingState.persianPrefabUri
        if (prefabUri == null) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_prefab_no_file)) }
            return
        }
        val sourceGuid = startingState.persianPrefabSourceGuid.trim()
        val targetGuid = startingState.persianPrefabTargetGuid.trim()
        if (sourceGuid.isEmpty() || targetGuid.isEmpty()) {
            _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_prefab_no_guids)) }
            return
        }
        val pptrTexts = listOf(
            startingState.persianPrefabPptrFromFile.trim(),
            startingState.persianPrefabPptrFromPath.trim(),
            startingState.persianPrefabPptrToFile.trim(),
            startingState.persianPrefabPptrToPath.trim(),
        )
        val pptr: UnityPersianPrefabRepoint.PptrRepoint? = when {
            pptrTexts.all { it.isEmpty() } -> null
            pptrTexts.any { it.isEmpty() } -> {
                _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_prefab_pptr_incomplete)) }
                return
            }
            else -> try {
                UnityPersianPrefabRepoint.PptrRepoint(
                    pptrTexts[0].toInt(),
                    pptrTexts[1].toLong(),
                    pptrTexts[2].toInt(),
                    pptrTexts[3].toLong(),
                )
            } catch (_: NumberFormatException) {
                _state.update { it.copy(message = getApplication<Application>().getString(R.string.unity_persian_prefab_pptr_bad_number)) }
                return
            }
        }
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            var prefabTemp: File? = null
            var prefabStaged: File? = null
            var patchedObjectTemp: File? = null
            var rebuiltTemp: File? = null
            var rebuiltStaged: File? = null
            var verifyTemp: File? = null
            var verifyStaged: File? = null
            var finalFile: File? = null
            try {
                runInterruptible(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, "persian-prefab").apply { mkdirs() }
                    prefabTemp = copyUriToStageFile(resolver, prefabUri, dir, "persian-prefab-", ".bin")
                    if (prefabTemp!!.length() > 64L * 1024 * 1024) {
                        throw UnityFormatException("Prefab file is too large for v1 repoint (max 64 MB)")
                    }
                    prefabStaged = File(dir, "persian-prefab-data-${System.nanoTime()}.bin")
                    val prefab = UnityBaseData.openBaseFile(prefabTemp!!, prefabStaged!!)
                    val originalBytes = prefabTemp!!.readBytes()
                    if (prefab.index.dataOffset > Int.MAX_VALUE) {
                        throw UnityFormatException("Prefab data offset is too large for v1 repoint")
                    }
                    val dataOffset = prefab.index.dataOffset.toInt()
                    val guidOffsets: List<Int>
                    val pptrFileOffsets: List<Int>
                    val workingBytes: ByteArray
                    if (pptr == null) {
                        val guidResult = UnityPersianPrefabRepoint.repointExternalGuid(
                            originalBytes, prefab.index.headerSize, dataOffset, sourceGuid, targetGuid,
                        )
                        guidOffsets = guidResult.offsets
                        pptrFileOffsets = emptyList()
                        workingBytes = guidResult.bytes
                    } else {
                        var matchCount = 0
                        var matchSlot: dev.cluvex.strata.core.unity.SerializedFileIndex.Slot? = null
                        var matchOffset = -1
                        var patchedObjectBytes: ByteArray? = null
                        for (slot in prefab.index.slots) {
                            if (slot.classId != 114) continue
                            val raw = prefab.index.readObjectBytes(prefab.dataFile, slot)
                            val found = UnityPersianPrefabRepoint.findPptrOccurrences(raw, pptr.fromFileId, pptr.fromPathId)
                            for (offset in found) {
                                matchCount++
                                if (matchCount == 1) {
                                    matchSlot = slot
                                    matchOffset = offset
                                    patchedObjectBytes = UnityPersianPrefabRepoint.repointPptr(raw, pptr).bytes
                                }
                            }
                        }
                        if (matchCount == 0) {
                            _state.update {
                                it.copy(
                                    busy = false,
                                    persianPrefabPatchedFile = null,
                                    persianPrefabPatchedCrc = null,
                                    persianPrefabReport = null,
                                    message = getApplication<Application>().getString(R.string.unity_persian_prefab_pptr_not_found),
                                )
                            }
                            return@runInterruptible
                        }
                        if (matchCount > 1) {
                            _state.update {
                                it.copy(
                                    busy = false,
                                    persianPrefabPatchedFile = null,
                                    persianPrefabPatchedCrc = null,
                                    persianPrefabReport = null,
                                    message = getApplication<Application>().getString(R.string.unity_persian_prefab_pptr_ambiguous, matchCount),
                                )
                            }
                            return@runInterruptible
                        }
                        patchedObjectTemp = File(dir, "persian-prefab-object-${System.nanoTime()}.bin")
                        patchedObjectTemp!!.writeBytes(patchedObjectBytes!!)
                        rebuiltTemp = File(dir, "persian-prefab-rebuilt-${System.nanoTime()}.bin")
                        prefab.index.rebuild(
                            rebuiltTemp!!, prefab.dataFile,
                            mapOf(matchSlot!!.entryName to patchedObjectTemp!!), prefab.summary.entries,
                        )
                        val rebuiltBytes = rebuiltTemp!!.readBytes()
                        rebuiltStaged = File(dir, "persian-prefab-rebuilt-data-${System.nanoTime()}.bin")
                        val rebuilt = UnityBaseData.openBaseFile(rebuiltTemp!!, rebuiltStaged!!)
                        if (rebuilt.index.dataOffset > Int.MAX_VALUE) {
                            throw UnityFormatException("Rebuilt prefab data offset is too large for v1 repoint")
                        }
                        val rebuiltDataOffset = rebuilt.index.dataOffset.toInt()
                        val guidResult = UnityPersianPrefabRepoint.repointExternalGuid(
                            rebuiltBytes, rebuilt.index.headerSize, rebuiltDataOffset, sourceGuid, targetGuid,
                        )
                        guidOffsets = guidResult.offsets
                        pptrFileOffsets = listOf(rebuiltDataOffset + matchSlot!!.start.toInt() + matchOffset)
                        workingBytes = guidResult.bytes
                    }
                    verifyTemp = File(dir, "persian-prefab-final-${System.nanoTime()}.bin")
                    verifyTemp!!.writeBytes(workingBytes)
                    verifyStaged = File(dir, "persian-prefab-verify-data-${System.nanoTime()}.bin")
                    val verified = UnityBaseData.openBaseFile(verifyTemp!!, verifyStaged!!)
                    if (verified.summary.entries.size != prefab.summary.entries.size) {
                        throw UnityFormatException("Repointed prefab object count changed (rebuild verification failed)")
                    }
                    verified.summary.entries.forEachIndexed { index, entry ->
                        if (entry.size != prefab.summary.entries[index].size) {
                            throw UnityFormatException("Repointed prefab object size changed (rebuild verification failed)")
                        }
                    }
                    finalFile = verifyTemp
                    verifyTemp = null
                    val crcHex = CRC32().apply { update(workingBytes) }.value.toString(16).uppercase().padStart(8, '0')
                    val report = getApplication<Application>().getString(
                        R.string.unity_persian_prefab_report,
                        guidOffsets.size,
                        pptrFileOffsets.size,
                        finalFile!!.name,
                        finalFile!!.length(),
                        crcHex,
                    )
                    _state.update {
                        it.copy(
                            busy = false,
                            persianPrefabPatchedFile = finalFile,
                            persianPrefabPatchedCrc = crcHex,
                            persianPrefabReport = report,
                            message = getApplication<Application>().getString(R.string.unity_persian_prefab_done),
                        )
                    }
                }
            } catch (e: CancellationException) {
                finalFile?.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        persianPrefabPatchedFile = null,
                        persianPrefabPatchedCrc = null,
                        persianPrefabReport = null,
                        message = getApplication<Application>().getString(R.string.unity_operation_cancelled),
                    )
                }
                throw e
            } catch (e: Exception) {
                finalFile?.delete()
                finalFile = null
                _state.update {
                    it.copy(
                        busy = false,
                        persianPrefabPatchedFile = null,
                        persianPrefabPatchedCrc = null,
                        persianPrefabReport = null,
                        message = localizationFailureMessage(e),
                    )
                }
            } finally {
                prefabTemp?.delete()
                prefabStaged?.delete()
                patchedObjectTemp?.delete()
                rebuiltTemp?.delete()
                rebuiltStaged?.delete()
                verifyTemp?.delete()
                verifyStaged?.delete()
            }
        }
    }

    private fun copyUriToStageFile(
        resolver: ContentResolver,
        uri: Uri,
        dir: File,
        prefix: String,
        suffix: String,
    ): File {
        val out = File(dir, prefix + System.nanoTime() + suffix)
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Could not open selected file")
        input.use { stream ->
            FileOutputStream(out).use { output -> stream.copyTo(output) }
        }
        return out
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
                    withTranslationDerived(
                        current.copy(
                            busy = false,
                            fontReports = current.fontReports + (entry.name to report),
                            message = report.summaryStatement,
                        ),
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

    /** Writes a patched font entry to the stage dir, overlaying staged localization tables when the entry already carries them. */
    private fun stagePatchedFontEntry(
        entry: UnityBundleEntry,
        rebuiltEntryBytes: ByteArray,
        preserveLocalization: Boolean,
        localizationTables: List<LocalizationSource>,
        dir: File,
    ): File {
        val fontBase = File(
            dir,
            "font-base-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin",
        )
        fontBase.writeBytes(rebuiltEntryBytes)
        if (!preserveLocalization) {
            return fontBase
        }
        if (localizationTables.isEmpty()) {
            fontBase.delete()
            throw UnityFormatException(
                "Staged localization tables are unavailable for font overlay",
            )
        }
        val composed = File(
            dir,
            "font-localization-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin",
        )
        try {
            UnityLocalizationOverlay.write(fontBase, localizationTables, composed, dir)
            verifyLocalizationRoundTrip(composed, localizationTables, dir)
            return composed
        } finally {
            fontBase.delete()
        }
    }

    fun applyDynamicFont(entry: UnityBundleEntry, ttfUri: Uri?, resolver: ContentResolver) {
        val doc = document ?: return
        val dir = stageDir ?: return
        val startingState = _state.value
        if (startingState.busy) return
        val preserveLocalization = entry.name in startingState.localizationReplacementEntries
        val localizationTables = startingState.localizationSources[entry.name].orEmpty()
        // When a pending translation exists and transforms are active, the picked font must
        // cover the shaped text the game will render — not just the logical letters — so the
        // patch is rejected up front instead of producing tofu boxes after a long repack.
        // Without pending translations (or with transforms off) the check is skipped; the
        // post-patch font-coverage check still reports gaps.
        val expectedShapedText = if (startingState.textTransformOptions.isEnabled) {
            val source = pendingLocalizationSource(startingState)
            val target = startingState.localizationTargetLanguageCode
            val targetIndex = source?.let { it.languageIndex(target ?: "") } ?: -1
            if (source != null && targetIndex >= 0) {
                source.terms.mapNotNull { term ->
                    term.values[targetIndex]?.let {
                        UnityLocalizationTsv.transformPreservingDirectives(it, startingState.textTransformOptions)
                    }
                }.joinToString("\n").takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } else {
            null
        }
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
                    doc.patchFontForDynamicGeneration(entry, ttfBytes, expectedShapedText = expectedShapedText)
                }
                when (outcome) {
                    is UnityFontPatchResult.Success -> {
                        val staged = runInterruptible(Dispatchers.IO) {
                            stagePatchedFontEntry(
                                entry,
                                outcome.rebuiltEntryBytes,
                                preserveLocalization,
                                localizationTables,
                                dir,
                            )
                        }
                        _state.update {
                            withTranslationDerived(
                                it.copy(
                                    busy = false,
                                    replacements = it.replacements + (entry.name to staged),
                                    fontReports = it.fontReports + (entry.name to outcome.updatedReport),
                                    localizationRoundTripStatus = if (preserveLocalization) {
                                        UnityLocalizationRoundTripStatus.PASSED
                                    } else {
                                        UnityLocalizationRoundTripStatus.NOT_RUN
                                    },
                                    localizationRoundTripDetail = if (preserveLocalization) {
                                        getApplication<Application>().getString(R.string.unity_round_trip_verified)
                                    } else {
                                        null
                                    },
                                    message = getApplication<Application>().getString(
                                        R.string.unity_font_patch_done,
                                        outcome.patchedTmpAssets,
                                    ),
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

    /**
     * Replaces the embedded TTF data of raw Font object(s) in [entry] with the picked font.
     * Unlike [applyDynamicFont] this needs no TMP_FontAsset: it serves games whose UI renders
     * with legacy Unity Text. When [targetFontPathId] is null every Font in the entry is
     * replaced; otherwise only that Font is. The same shaped-text coverage guard as the
     * dynamic path applies, so a font that cannot render the pending translation is rejected
     * before any repack.
     */
    fun applyRawFont(
        entry: UnityBundleEntry,
        ttfUri: Uri?,
        resolver: ContentResolver,
        targetFontPathId: Long? = null,
        mergeWithExisting: Boolean = false,
    ) {
        val doc = document ?: return
        val dir = stageDir ?: return
        if (ttfUri == null) return
        val startingState = _state.value
        if (startingState.busy) return
        val preserveLocalization = entry.name in startingState.localizationReplacementEntries
        val localizationTables = startingState.localizationSources[entry.name].orEmpty()
        // Same shaped-text guard as the dynamic path: when a pending translation exists and
        // transforms are active, the picked font must cover the shaped text the game will
        // render — not just the logical letters.
        val expectedShapedText = if (startingState.textTransformOptions.isEnabled) {
            val source = pendingLocalizationSource(startingState)
            val target = startingState.localizationTargetLanguageCode
            val targetIndex = source?.let { it.languageIndex(target ?: "") } ?: -1
            if (source != null && targetIndex >= 0) {
                source.terms.mapNotNull { term ->
                    term.values[targetIndex]?.let {
                        UnityLocalizationTsv.transformPreservingDirectives(it, startingState.textTransformOptions)
                    }
                }.joinToString("\n").takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } else {
            null
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val outcome = runInterruptible(Dispatchers.IO) {
                    val ttfBytes = resolver.openInputStream(ttfUri)?.use { it.readBytes() }
                        ?: throw IOException("Could not open selected TTF font")
                    doc.patchRawFonts(entry, ttfBytes, targetFontPathId, expectedShapedText, mergeWithExisting)
                }
                when (outcome) {
                    is UnityFontPatchResult.Success -> {
                        val staged = runInterruptible(Dispatchers.IO) {
                            stagePatchedFontEntry(
                                entry,
                                outcome.rebuiltEntryBytes,
                                preserveLocalization,
                                localizationTables,
                                dir,
                            )
                        }
                        _state.update {
                            withTranslationDerived(
                                it.copy(
                                    busy = false,
                                    replacements = it.replacements + (entry.name to staged),
                                    fontReports = it.fontReports + (entry.name to outcome.updatedReport),
                                    localizationRoundTripStatus = if (preserveLocalization) {
                                        UnityLocalizationRoundTripStatus.PASSED
                                    } else {
                                        UnityLocalizationRoundTripStatus.NOT_RUN
                                    },
                                    localizationRoundTripDetail = if (preserveLocalization) {
                                        getApplication<Application>().getString(R.string.unity_round_trip_verified)
                                    } else {
                                        null
                                    },
                                    message = getApplication<Application>().getString(
                                        if (mergeWithExisting) {
                                            R.string.unity_font_raw_merge_done
                                        } else {
                                            R.string.unity_font_raw_patch_done
                                        },
                                        outcome.patchedRawFonts,
                                    ),
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
        textInspectionJob?.cancel()
        document?.close()
        stageDir?.deleteRecursively()
        releaseApkDiscoverySession()
        super.onCleared()
    }
}
