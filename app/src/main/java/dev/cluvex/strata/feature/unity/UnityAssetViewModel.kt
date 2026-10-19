package dev.cluvex.strata.feature.unity

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ArchiveParent
import dev.cluvex.strata.core.unity.TypeTreeCodec
import dev.cluvex.strata.core.unity.UnityBundleDocument
import dev.cluvex.strata.core.unity.UnityBundleEntry
import dev.cluvex.strata.core.unity.UnityBundleReader
import dev.cluvex.strata.core.unity.UnityDocumentClosedException
import dev.cluvex.strata.core.unity.UnityFormatException
import dev.cluvex.strata.core.unity.UnityOperationCancelledException
import dev.cluvex.strata.core.unity.UnityPackageRepacker
import dev.cluvex.strata.core.unity.UnityProbe
import dev.cluvex.strata.core.unity.UnityTextInfo
import dev.cluvex.strata.core.unity.UnityTextInspection
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
                    UnityPackageRepacker.repack(doc, collectChanges(doc, current), parents, getApplication<Application>().cacheDir)
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

    /** Merges staged edits, picked replacements and any exported text JSON the user has modified. */
    private fun collectChanges(doc: UnityBundleDocument, current: UnityAssetUiState): Map<String, File> {
        val dir = stageDir
        val textChanges = HashMap<String, File>()
        if (dir != null) {
            current.exportedTexts.forEach { (name, jsonFile) ->
                if (!jsonFile.isFile) return@forEach
                val baseline = exportedBaselines[name]
                if (baseline != null && jsonFile.length() == baseline.first && jsonFile.lastModified() == baseline.second) return@forEach
                val entry = doc.summary.entries.firstOrNull { it.name == name } ?: return@forEach
                val edits = TypeTreeCodec.parseJson(jsonFile.readText())
                val bytes = doc.rebuildTexts(entry, edits)
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
