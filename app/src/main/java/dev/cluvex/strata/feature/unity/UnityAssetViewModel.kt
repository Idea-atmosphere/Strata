package dev.cluvex.strata.feature.unity

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ArchiveParent
import dev.cluvex.strata.core.unity.UnityBundleDocument
import dev.cluvex.strata.core.unity.UnityBundleEntry
import dev.cluvex.strata.core.unity.UnityBundleReader
import dev.cluvex.strata.core.unity.UnityPackageRepacker
import dev.cluvex.strata.core.unity.UnityProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val output: File? = null,
    val message: String? = null,
)

class UnityAssetViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UnityAssetUiState())
    val state: StateFlow<UnityAssetUiState> = _state.asStateFlow()

    private var document: UnityBundleDocument? = null
    private var stageDir: File? = null
    private var workingBaselines: Map<String, Pair<Long, Long>> = emptyMap()

    fun open(path: String) {
        val current = _state.value
        if (current.path == path && (current.loading || current.probe != null || current.error != null)) return

        document?.close()
        stageDir?.deleteRecursively()
        document = null
        workingBaselines = emptyMap()
        val newStageDir = File(getApplication<Application>().cacheDir, "unity-editor/${path.hashCode().toUInt().toString(16)}-${System.nanoTime()}")
        newStageDir.mkdirs()
        stageDir = newStageDir
        _state.value = UnityAssetUiState(path = path, loading = true)

        viewModelScope.launch {
            try {
                val (probe, parsed) = withContext(Dispatchers.IO) {
                    val file = File(path)
                    val probe = UnityBundleReader.probe(file)
                    val parsed = if (probe.bundleSupported) UnityBundleReader.open(file, getApplication<Application>().cacheDir) else null
                    probe to parsed
                }
                document = parsed
                _state.update { it.copy(loading = false, probe = probe, document = parsed) }
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, error = getApplication<Application>().getString(R.string.unity_load_failed)) }
            }
        }
    }

    fun select(name: String?) {
        _state.update { it.copy(selectedName = name) }
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
        withContext(Dispatchers.IO) { doc.copyEntry(entry, target) }
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
            withContext(Dispatchers.IO) {
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
        val changes = current.editedFiles + current.replacements
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    UnityPackageRepacker.repack(doc, changes, parents, getApplication<Application>().cacheDir)
                }
                _state.update {
                    it.copy(
                        output = output,
                        message = getApplication<Application>().getString(R.string.unity_repack_done, output.path),
                        busy = false,
                    )
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(message = getApplication<Application>().getString(R.string.unity_operation_failed), busy = false)
                }
            }
        }
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
