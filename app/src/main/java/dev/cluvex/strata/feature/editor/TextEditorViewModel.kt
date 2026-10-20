package dev.cluvex.strata.feature.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.res.AxmlCodec
import dev.cluvex.strata.core.res.BinaryResources
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.text.EditTracker
import dev.cluvex.strata.core.text.EditorLanguage
import dev.cluvex.strata.core.text.LineEnding
import dev.cluvex.strata.core.text.PageText
import dev.cluvex.strata.core.text.TextEncoding
import dev.cluvex.strata.core.text.TextFormat
import dev.cluvex.strata.core.text.TextPage
import dev.cluvex.strata.core.text.TextPager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.UUID

data class EditorUiState(
    val path: String = "",
    val name: String = "",
    val loading: Boolean = true,
    val readOnly: Boolean = false,
    val writable: Boolean = true,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val tooLarge: Boolean = false,
    val binary: Boolean = false,
    val charset: Charset = Charsets.UTF_8,
    val bom: Boolean = false,
    val lineEnding: LineEnding = LineEnding.LF,
    val language: EditorLanguage? = null,
    val syntaxHighlightingAvailable: Boolean? = null,
    val syntaxRevision: Int = 0,
    val decodedResource: String? = null,
    val binaryXml: Boolean = false,
    val loadSeq: Int = 0,
    val externallyModified: Boolean = false,
    val encodingProblem: Boolean = false,
    val size: Long = 0,
    val message: String? = null,
    val paged: Boolean = false,
    val page: Int = 0,
    val pageCount: Int = 0,
    val pageFirstLine: Long = 0,
    val totalLines: Long = 0,
    val pageLossy: Boolean = false,
    val jumpLine: Int? = null,
)

class TextEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    var pendingSelection: EditorSelection? = null

    private var access: FileAccess = LocalFileAccess
    private var pendingText: String? = null
    private var stamp: Pair<Long, Long>? = null

    private var pages: List<TextPage> = emptyList()
    private val tracker = EditTracker()
    private var userReadOnly = false
    private var languageOverridden = false
    private var suggestedLanguageName: String? = null

    fun takeText(): String? = pendingText.also { pendingText = null }

    fun stash(text: String) {
        if (!_state.value.loading) pendingText = text
    }

    fun load(path: String, rootEnabled: Boolean, charset: Charset? = null, force: Boolean = false) {
        val current = _state.value
        if (!force && charset == null && current.path == path && !current.loading) {
            _state.update { it.copy(loadSeq = it.loadSeq + 1) }
            return
        }
        viewModelScope.launch {
            val syntaxAvailable = when {
                TextMateEngine.isReady -> true
                TextMateEngine.unavailableCause != null -> false
                else -> null
            }
            _state.update {
                EditorUiState(
                    path = path,
                    name = File(path).name,
                    loading = true,
                    loadSeq = it.loadSeq,
                    syntaxHighlightingAvailable = syntaxAvailable,
                    syntaxRevision = it.syntaxRevision,
                )
            }
            pages = emptyList()
            tracker.reset("", TextFormat(Charsets.UTF_8, false, LineEnding.LF))
            userReadOnly = false
            languageOverridden = false
            suggestedLanguageName = null

            // Syntax initialization runs independently: file IO, decoding and save eligibility do
            // not wait for the optional TextMate service-provider graph.
            val syntaxInitialization = async(Dispatchers.Default) {
                TextMateEngine.init(getApplication())
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val local = File(path)
                    val a = if (local.canRead() && (local.canWrite() || !rootEnabled)) LocalFileAccess else RootManager.access() ?: LocalFileAccess
                    access = a
                    val file = a.file(path)
                    val size = file.length()
                    val head = a.openInput(file).use { readUpTo(it, SAMPLE_BYTES) }
                    val complete = head.size.toLong() >= size
                    if (shouldPage(path, size, head)) {
                        val (cs, bomLen) = if (charset != null) charset to TextEncoding.bomLength(head, charset)
                        else TextEncoding.detectSample(head, complete)
                        if (TextPager.pageable(cs)) {
                            val index = a.openReadChannel(file).use { TextPager.index(it, size, bomLen, cs) }
                            return@withContext Loaded.Paged(index, cs, bomLen > 0, TextEncoding.looksBinary(head), size, file.canWrite(), file.lastModified())
                        }
                    }
                    if (size > MAX_BYTES) return@withContext Loaded.TooLarge(size, file.canWrite(), file.lastModified())
                    val bytes = if (complete) head else a.openInput(file).use { it.readBytes() }
                    Loaded.Whole(bytes, size, file.canWrite(), file.lastModified())
                }
            }
            result.onSuccess { loaded ->
                stamp = loaded.modified to loaded.size
                when (loaded) {
                    is Loaded.TooLarge -> _state.update { it.copy(loading = false, readOnly = true, tooLarge = true, size = loaded.size) }
                    is Loaded.Paged -> openPaged(path, loaded)
                    is Loaded.Whole -> openWhole(path, loaded, charset)
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, readOnly = true, message = e.message ?: tr(R.string.editor_cant_open_file)) }
            }
            finishSyntaxInitialization(path, syntaxInitialization.await())
        }
    }

    private fun finishSyntaxInitialization(path: String, status: OptionalFeatureState) {
        if (_state.value.path != path) return
        when (status) {
            OptionalFeatureState.Uninitialized -> Unit
            OptionalFeatureState.Ready -> {
                val inferred = if (languageOverridden) null else {
                    suggestedLanguageName?.let(TextMateEngine::languageNamed)
                        ?: TextMateEngine.languageFor(File(path).name)
                }
                _state.update {
                    it.copy(
                        syntaxHighlightingAvailable = true,
                        syntaxRevision = it.syntaxRevision + 1,
                        language = if (languageOverridden) it.language else inferred,
                    )
                }
            }
            is OptionalFeatureState.Unavailable -> {
                _state.update {
                    it.copy(
                        syntaxHighlightingAvailable = false,
                        syntaxRevision = it.syntaxRevision + 1,
                        language = null,
                        message = it.message ?: tr(R.string.unity_editor_plain_text_fallback),
                    )
                }
            }
        }
    }

    private fun openWhole(path: String, loaded: Loaded.Whole, charset: Charset?) {
        val bytes = loaded.bytes
        val resource = if (charset == null) decodeBinaryResource(path, bytes) else null
        if (resource != null) {
            suggestedLanguageName = resource.language
            pendingText = resource.text
            tracker.reset(resource.text, TextFormat(Charsets.UTF_8, false, LineEnding.LF))
            val editable = resource.binaryXml && loaded.writable
            _state.update {
                it.copy(
                    loading = false, writable = editable, readOnly = !editable, binary = false, binaryXml = resource.binaryXml,
                    charset = Charsets.UTF_8, bom = false, lineEnding = LineEnding.LF,
                    language = resource.language?.let(TextMateEngine::languageNamed),
                    decodedResource = resource.label,
                    size = loaded.size, dirty = false, externallyModified = false,
                    loadSeq = it.loadSeq + 1,
                )
            }
            return
        }
        val decoded = TextEncoding.decode(bytes, charset)
        pendingText = decoded.text
        tracker.reset(decoded.text, TextFormat(decoded.charset, decoded.bom, decoded.lineEnding))
        _state.update {
            it.copy(
                loading = false,
                writable = loaded.writable,
                readOnly = !loaded.writable,
                binary = TextEncoding.looksBinary(bytes),
                charset = decoded.charset,
                bom = decoded.bom,
                lineEnding = decoded.lineEnding,
                language = TextMateEngine.languageFor(File(path).name),
                decodedResource = null,
                binaryXml = false,
                size = loaded.size,
                dirty = false,
                externallyModified = false,
                loadSeq = it.loadSeq + 1,
            )
        }
    }

    private suspend fun openPaged(path: String, p: Loaded.Paged) {
        pages = p.pages
        val first = readDiskPage(0, p.charset)
        val lineEnding = TextEncoding.lineEnding(first.text)
        tracker.reset(first.text, TextFormat(p.charset, p.bom, lineEnding))
        pendingText = first.text
        _state.update {
            it.copy(
                loading = false,
                writable = p.writable,
                readOnly = !p.writable || !first.lossless,
                binary = p.binary,
                charset = p.charset,
                bom = p.bom,
                lineEnding = lineEnding,
                language = TextMateEngine.languageFor(File(path).name),
                decodedResource = null,
                binaryXml = false,
                size = p.size,
                dirty = false,
                externallyModified = false,
                paged = true,
                page = 0,
                pageCount = pages.size,
                pageFirstLine = 0,
                totalLines = pages.sumOf { pg -> pg.lines } + 1,
                pageLossy = !first.lossless,
                loadSeq = it.loadSeq + 1,
            )
        }
    }

    private suspend fun readDiskPage(i: Int, charset: Charset): PageText = withContext(Dispatchers.IO) {
        access.openReadChannel(access.file(_state.value.path)).use { TextPager.decode(TextPager.readPage(it, pages[i]), charset) }
    }

    fun goToPage(target: Int, currentText: String, line: Int? = null) {
        val s = _state.value
        if (!s.paged || target !in pages.indices || s.loading || target == s.page) return
        tracker.leave(currentText)
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val disk = runCatching { readDiskPage(target, tracker.disk.charset) }.getOrElse { e ->
                _state.update { it.copy(loading = false, message = e.message ?: tr(R.string.editor_cant_read_page)) }
                return@launch
            }
            val edited = tracker.enter(target, disk.text)
            val lossless = edited != null || disk.lossless
            pendingText = edited ?: disk.text
            _state.update {
                it.copy(
                    loading = false,
                    page = target,
                    pageFirstLine = pages[target].firstLine,
                    pageLossy = !lossless,
                    readOnly = userReadOnly || !it.writable || !lossless,
                    jumpLine = line,
                    loadSeq = it.loadSeq + 1,
                )
            }
            refreshDirty()
        }
    }

    fun goToLine(line1: Int, currentText: String): Int? {
        if (!_state.value.paged || pages.isEmpty()) return line1
        val zero = (line1 - 1).toLong().coerceAtLeast(0)
        val target = TextPager.pageOfLine(pages, zero)
        val local = (zero - pages[target].firstLine).toInt() + 1
        if (target == _state.value.page) return local
        goToPage(target, currentText, line = local)
        return null
    }

    fun consumeJump() = _state.update { it.copy(jumpLine = null) }

    private class DecodedResource(val text: String, val language: String?, val label: String, val binaryXml: Boolean)

    private fun decodeBinaryResource(path: String, bytes: ByteArray): DecodedResource? {
        val name = File(path).name
        return runCatching {
            when {
                BinaryResources.isBinaryXml(bytes) ->
                    DecodedResource(BinaryResources.decodeXml(bytes), "xml", tr(R.string.editor_binary_xml), binaryXml = true)
                name == "resources.arsc" || BinaryResources.isResourceTable(bytes) ->
                    DecodedResource(BinaryResources.dumpResourcesFromArsc(File(path)), null, tr(R.string.editor_resource_table), binaryXml = false)
                else -> null
            }
        }.getOrNull()
    }

    fun onTextChanged(length: Int, current: () -> CharSequence) {
        tracker.changed(length, current)
        refreshDirty()
    }

    private fun refreshDirty() = _state.update { it.copy(dirty = tracker.dirty(TextFormat(it.charset, it.bom, it.lineEnding))) }

    fun setReadOnly(on: Boolean) {
        userReadOnly = on
        _state.update { it.copy(readOnly = on || !it.writable || it.pageLossy) }
    }

    fun setLanguage(language: EditorLanguage?) {
        languageOverridden = true
        _state.update { it.copy(language = language) }
    }

    fun reportSyntaxUnavailable() {
        _state.update {
            if (it.syntaxHighlightingAvailable == false) it else {
                it.copy(
                    syntaxHighlightingAvailable = false,
                    syntaxRevision = it.syntaxRevision + 1,
                    language = null,
                    message = it.message ?: tr(R.string.unity_editor_plain_text_fallback),
                )
            }
        }
    }

    fun setLineEnding(ending: LineEnding) {
        _state.update { it.copy(lineEnding = ending) }
        refreshDirty()
    }

    fun setSaveCharset(charset: Charset) {
        _state.update { it.copy(charset = charset, bom = it.bom && charset.name().startsWith("UTF"), encodingProblem = false) }
        refreshDirty()
    }

    fun save(text: String, onSaved: () -> Unit = {}) {
        val s = _state.value
        if (s.readOnly || s.saving) return
        if (s.paged) return savePaged(text, onSaved)
        val body = TextEncoding.convertLineEndings(text, s.lineEnding)
        if (!s.binaryXml && !TextEncoding.canEncode(body, s.charset)) {
            _state.update { it.copy(encodingProblem = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val a = access
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = if (s.binaryXml) AxmlCodec.encodeStandalone(body) else TextEncoding.encode(body, s.charset, s.bom)
                    val file = a.file(s.path)
                    a.openOutput(file).use { it.write(bytes) }
                    file.lastModified() to file.length()
                }
            }
            result.onSuccess { newStamp ->
                stamp = newStamp
                tracker.reset(text, TextFormat(s.charset, s.bom, s.lineEnding))
                _state.update { it.copy(saving = false, externallyModified = false, size = newStamp.second, message = tr(R.string.editor_saved)) }
                refreshDirty()
                onSaved()
            }.onFailure { e ->
                _state.update { it.copy(saving = false, message = e.message ?: tr(R.string.editor_save_failed)) }
            }
        }
    }

    private fun savePaged(currentText: String, onSaved: () -> Unit) {
        val s = _state.value
        val pageEdits = tracker.edits(currentText)
        for (edited in pageEdits.values) {
            if (!TextEncoding.canEncode(TextEncoding.convertLineEndings(edited, s.lineEnding), s.charset)) {
                _state.update { it.copy(encodingProblem = true) }
                return
            }
        }
        val disk = tracker.disk
        val transcode = s.charset != disk.charset || s.lineEnding != disk.lineEnding
        val bom = if (s.bom) TextEncoding.encode("", s.charset, bom = true) else ByteArray(0)
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val a = access
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val file = a.file(s.path)
                    val tmp = File(getApplication<Application>().cacheDir, "paged-save-${UUID.randomUUID()}")
                    try {
                        a.openReadChannel(file).use { ch ->
                            tmp.outputStream().buffered(256 * 1024).use { out ->
                                TextPager.assemble(ch, pages, bom, out) { i ->
                                    val edited = pageEdits[i]
                                    when {
                                        edited != null -> TextEncoding.convertLineEndings(edited, s.lineEnding).toByteArray(s.charset)
                                        transcode -> {
                                            val original = TextPager.decode(TextPager.readPage(ch, pages[i]), disk.charset).text
                                            TextEncoding.convertLineEndings(original, s.lineEnding).toByteArray(s.charset)
                                        }
                                        else -> null
                                    }
                                }
                            }
                        }
                        tmp.inputStream().buffered(256 * 1024).use { input -> a.openOutput(file).use { input.copyTo(it, 256 * 1024) } }
                    } finally {
                        tmp.delete()
                    }
                    val size = file.length()
                    val bomLen = bom.size
                    val index = a.openReadChannel(file).use { TextPager.index(it, size, bomLen, s.charset) }
                    Triple(index, file.lastModified() to size, size)
                }
            }
            result.onSuccess { (index, newStamp, size) ->
                stamp = newStamp
                pages = index
                val keep = s.page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                val text = runCatching { readDiskPage(keep, s.charset) }.getOrNull()
                if (text != null) pendingText = text.text
                tracker.reset(text?.text ?: currentText, TextFormat(s.charset, s.bom, s.lineEnding), keep)
                _state.update {
                    it.copy(
                        saving = false, dirty = false, externallyModified = false, size = size, message = tr(R.string.editor_saved),
                        page = keep, pageCount = pages.size, pageFirstLine = pages.getOrNull(keep)?.firstLine ?: 0,
                        totalLines = pages.sumOf { pg -> pg.lines } + 1, pageLossy = text?.lossless == false,
                        loadSeq = if (text != null) it.loadSeq + 1 else it.loadSeq,
                    )
                }
                onSaved()
            }.onFailure { e ->
                _state.update { it.copy(saving = false, message = e.message ?: tr(R.string.editor_save_failed)) }
            }
        }
    }

    fun checkExternalChange() {
        val s = _state.value
        val known = stamp ?: return
        if (s.loading || s.path.isEmpty()) return
        viewModelScope.launch {
            val now = withContext(Dispatchers.IO) {
                runCatching { access.file(s.path).let { it.lastModified() to it.length() } }.getOrNull()
            } ?: return@launch
            if (now != known) _state.update { it.copy(externallyModified = true) }
        }
    }

    fun ignoreExternalChange() {
        viewModelScope.launch {
            val s = _state.value
            stamp = withContext(Dispatchers.IO) {
                runCatching { access.file(s.path).let { it.lastModified() to it.length() } }.getOrNull()
            } ?: stamp
            _state.update { it.copy(externallyModified = false) }
        }
    }

    fun dismissEncodingProblem() = _state.update { it.copy(encodingProblem = false) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private sealed interface Loaded {
        val size: Long
        val writable: Boolean
        val modified: Long

        class Whole(val bytes: ByteArray, override val size: Long, override val writable: Boolean, override val modified: Long) : Loaded
        class TooLarge(override val size: Long, override val writable: Boolean, override val modified: Long) : Loaded
        class Paged(
            val pages: List<TextPage>,
            val charset: Charset,
            val bom: Boolean,
            val binary: Boolean,
            override val size: Long,
            override val writable: Boolean,
            override val modified: Long,
        ) : Loaded
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024

        const val PAGED_THRESHOLD = 1L * 1024 * 1024

        private const val LONG_LINE_MIN_SIZE = 128L * 1024
        private const val LONG_LINE = 32 * 1024

        private const val SAMPLE_BYTES = 1 shl 20

        internal fun shouldPage(path: String, size: Long, head: ByteArray): Boolean {
            if (File(path).name == "resources.arsc" || BinaryResources.isBinaryXml(head) || BinaryResources.isResourceTable(head)) return false
            if (size > PAGED_THRESHOLD) return true
            return size > LONG_LINE_MIN_SIZE && longestLine(head) > LONG_LINE
        }

        internal fun longestLine(bytes: ByteArray): Int {
            var max = 0
            var run = 0
            for (b in bytes) {
                if (b == 0x0A.toByte()) { if (run > max) max = run; run = 0 } else run++
            }
            return maxOf(max, run)
        }

        private fun readUpTo(input: InputStream, limit: Int): ByteArray {
            val buf = ByteArray(limit)
            var n = 0
            while (n < limit) {
                val r = input.read(buf, n, limit - n)
                if (r < 0) break
                n += r
            }
            return if (n == limit) buf else buf.copyOf(n)
        }
    }
}
