package dev.cluvex.strata.feature.unity

import android.app.Application
import android.content.ContentResolver
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
import dev.cluvex.strata.core.unity.UnityLocalizationOpenRequest
import dev.cluvex.strata.core.unity.UnityLocalizationOverlay
import dev.cluvex.strata.core.unity.UnityLocalizationRoundTripStatus
import dev.cluvex.strata.core.unity.UnityLocalizationScanBudget
import dev.cluvex.strata.core.unity.UnityLocalizationScanProgress
import dev.cluvex.strata.core.unity.UnityLocalizationScanReport
import dev.cluvex.strata.core.unity.UnityLocalizationScanRequest
import dev.cluvex.strata.core.unity.UnityLocalizationOperations
import dev.cluvex.strata.core.unity.UnityLocalizationTsv
import dev.cluvex.strata.core.unity.UnityProbe
import dev.cluvex.strata.core.unity.UnityRtlFlagInference
import dev.cluvex.strata.core.unity.UnityRtlFlagInferer
import dev.cluvex.strata.core.unity.UnityTextInfo
import dev.cluvex.strata.core.unity.UnityTextInspection
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
    val output: File? = null,
    val outputSigningHandoff: UnityApkSigningHandoffState = UnityApkSigningHandoffState.NOT_APK,
    val message: String? = null,
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
                        file to UnityLocalizationTsv.planImport(
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
                    UnityLocalizationTsv.planImport(
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

    private fun localizationFailureMessage(error: Throwable): String {
        if (error is VirtualMachineError && error !is OutOfMemoryError) throw error
        if (error is ThreadDeath) throw error
        val detail = error.message?.takeIf { it.isNotBlank() }
        return when {
            error is UnityDocumentClosedException -> getApplication<Application>().getString(R.string.unity_staging_unavailable)
            error is UnityOperationCancelledException -> getApplication<Application>().getString(R.string.unity_operation_cancelled)
            detail != null -> getApplication<Application>().getString(R.string.unity_operation_failed_detail, detail)
            else -> getApplication<Application>().getString(R.string.unity_operation_failed)
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
                            val fontBase = File(
                                dir,
                                "font-base-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin",
                            )
                            fontBase.writeBytes(outcome.rebuiltEntryBytes)
                            if (!preserveLocalization) {
                                fontBase
                            } else {
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
                                    composed
                                } finally {
                                    fontBase.delete()
                                }
                            }
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
