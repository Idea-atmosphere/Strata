package dev.cluvex.strata.core.unity

import org.tukaani.xz.LZMAInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Logger

/** Unity container formats recognized by Strata's built-in Unity asset browser. */
enum class UnityFileKind {
    UNITY_FS,
    UNITY_WEB,
    UNITY_RAW,
    SERIALIZED_FILE,
    UNKNOWN,
}

data class UnityProbe(
    val kind: UnityFileKind,
    val signature: String,
    val fileVersion: Int? = null,
    val engineVersion: String? = null,
    val dataOffset: Long? = null,
    val note: String? = null,
) {
    /** True when the built-in browser can open and list this kind. Repacking may still be unsupported. */
    val bundleSupported: Boolean get() = kind != UnityFileKind.UNKNOWN
}

data class UnityBundleEntry(
    val name: String,
    val offset: Long,
    val size: Long,
    val flags: Long,
) {
    val displayName: String get() = name.substringAfterLast('/').ifEmpty { name }
}

data class UnityBundleSummary(
    val signature: String,
    val formatVersion: Int,
    val playerVersion: String,
    val engineVersion: String,
    val fileSize: Long,
    val dataSize: Long,
    val blockCount: Int,
    val entries: List<UnityBundleEntry>,
)

/** Result of scanning one serialized-file object for editable game text. */
data class UnityTextInfo(
    val objectName: String?,
    val className: String,
    val stringCount: Int,
    val textBytes: Long,
    val sample: String,
)

enum class UnityTextUnavailableReason {
    NOT_SERIALIZED_FILE,
    NO_TYPE_INFORMATION,
    UNSUPPORTED_OBJECT,
    UNSUPPORTED_VERSION,
    NO_TEXT,
    TOO_MANY_OBJECTS,
    DECODE_FAILED,
    DOCUMENT_CLOSED,
    FONT_NO_GLYPHS,
    FONT_STATIC_NO_SOURCE,
    FONT_ATLAS_FULL,
    FONT_ASSET_NOT_FOUND,
    LOCALIZATION_TABLE_NOT_FOUND,
    LOCALIZATION_SHAPE_AMBIGUOUS,
    LOCALIZATION_ALIGNMENT_INVALID,
    LOCALIZATION_TSV_INVALID,
    LOCALIZATION_CONFLICT,
    LOCALIZATION_SIZE_GUARD,
}

/** Per-entry text capability, including a diagnostic when editing is unsafe or impossible. */
sealed class UnityTextInspection {
    data class Editable(val info: UnityTextInfo) : UnityTextInspection()
    data class Unavailable(
        val reason: UnityTextUnavailableReason,
        val diagnostic: String,
    ) : UnityTextInspection()
}

open class UnityFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

class UnityDocumentClosedException : UnityFormatException(
    "Unity document is closed and its staged data is unavailable",
)

class UnityOperationCancelledException : InterruptedIOException("Unity operation was cancelled")

class UnityUnsupportedVersionException(message: String) : UnityFormatException(message)

internal enum class UnityFsHashConvention {
    ZERO,
    UNCOMPRESSED_MD5,
    COMPRESSED_MD5,
    PRESERVE,
}

/** Source layout choices that must survive a normalized UnityFS rewrite. */
internal data class UnityFsWriteProfile(
    val preferredCodec: Int,
    val sourceFlags: Long,
    val hashConvention: UnityFsHashConvention,
    val sourceHash: ByteArray,
    val declaredBundleSize: Long,
    val physicalFileSize: Long,
)

/**
 * A parsed UnityFS bundle. The decompressed data stream is staged in app cache so entry reads
 * and replacement/repacking do not require holding the whole bundle in memory.
 */
class UnityBundleDocument internal constructor(
    val source: File,
    val summary: UnityBundleSummary,
    internal val dataFile: File,
    internal val serialized: SerializedFileIndex? = null,
    private val unityFsProfile: UnityFsWriteProfile? = null,
) : Closeable {
    private data class EmbeddedPart(
        val partIndex: Int,
        val relativeOffset: Long,
        val declaredSize: Long,
        val summary: UnityBundleSummary,
        val index: SerializedFileIndex,
    )

    private sealed class EmbeddedParse {
        data class Success(
            val parts: List<EmbeddedPart>,
            val parsedBytes: Long,
        ) : EmbeddedParse() {
            val objectCount: Int get() = parts.sumOf { it.summary.entries.size }
        }
        data class Failure(
            val reason: UnityTextUnavailableReason,
            val diagnostic: String,
        ) : EmbeddedParse()
    }

    private val embeddedLock = Any()
    private val embeddedIndexes = HashMap<UnityBundleEntry, EmbeddedParse>()
    private val localizationLock = Any()
    private val localizationInspections = HashMap<UnityBundleEntry, UnityLocalizationInspection>()
    private val lifecycleLock = ReentrantReadWriteLock(true)
    @Volatile private var closed = false

    /** Repacking is implemented for UnityFS bundles and serialized files; legacy containers are read-only. */
    val repackSupported: Boolean get() = summary.signature == "UnityFS" || serialized != null

    /** Codec retained by a normalized rewrite; exposed for translation readiness without guessing. */
    val repackCompressionLabel: String
        get() = when {
            serialized != null -> "SerializedFile"
            unityFsProfile == null -> "Unavailable"
            unityFsProfile.preferredCodec == 0 -> "None"
            unityFsProfile.preferredCodec == 1 -> "LZMA (read); LZ4HC (write)"
            unityFsProfile.preferredCodec == 2 -> "LZ4"
            unityFsProfile.preferredCodec == 3 -> "LZ4HC"
            else -> "Unsupported (${unityFsProfile.preferredCodec})"
        }

    /** True when this document can contain editable serialized objects. Check individual entries too. */
    val supportsTextEditing: Boolean get() = serialized != null || summary.signature == "UnityFS"

    /** Exact, per-entry predicate. Parsing of a UnityFS child is lazy and metadata-only. */
    fun supportsTextEditing(entry: UnityBundleEntry): Boolean = inspectText(entry) is UnityTextInspection.Editable

    /** Returns either text statistics or a concrete reason that this entry is not editable. */
    fun inspectText(entry: UnityBundleEntry): UnityTextInspection = try {
        withOpenDocument { inspectTextOpen(entry) }
    } catch (_: UnityDocumentClosedException) {
        unavailable(UnityTextUnavailableReason.DOCUMENT_CLOSED, "Unity document is closed")
    }

    private fun inspectTextOpen(entry: UnityBundleEntry): UnityTextInspection = try {
        requireEntry(entry)
        val standalone = serialized
        if (standalone != null) {
            inspectObject(standalone, entry)
        } else if (summary.signature == "UnityFS") {
            inspectEmbedded(entry)
        } else {
            unavailable(UnityTextUnavailableReason.NOT_SERIALIZED_FILE, "Entry is not a serialized file")
        }
    } catch (e: UnityDocumentClosedException) {
        throw e
    } catch (e: Exception) {
        unavailable(UnityTextUnavailableReason.DECODE_FAILED, e.message ?: "Text inspection failed")
    }

    /** Compatibility API: null means callers should use [inspectText] to retrieve the reason. */
    fun textInfo(entry: UnityBundleEntry): UnityTextInfo? =
        (inspectText(entry) as? UnityTextInspection.Editable)?.info

    /** Flat "path -> value" map of every string inside the entry, in decode order. */
    fun exportTexts(entry: UnityBundleEntry): LinkedHashMap<String, String>? =
        withOpenDocument { exportTextsOpen(entry) }

    private fun exportTextsOpen(entry: UnityBundleEntry): LinkedHashMap<String, String>? {
        requireEntry(entry)
        val standalone = serialized
        if (standalone != null) {
            val value = decodeOrThrow(standalone, entry)
            return TypeTreeCodec.exportStrings(value).takeIf { it.isNotEmpty() }
        }
        val embedded = embeddedOrThrow(entry)
        if (embedded.objectCount > MAX_EMBEDDED_TEXT_OBJECTS) {
            throw UnityFormatException("Serialized entry has too many objects to scan safely")
        }
        val exported = LinkedHashMap<String, String>()
        embedded.parts.forEach { part ->
            part.summary.entries.forEach { objectEntry ->
                val decoded = part.index.decodeResult(dataFile, objectEntry)
                if (decoded is SerializedDecodeResult.Success) {
                    val slot = part.index.slotFor(objectEntry) ?: return@forEach
                    TypeTreeCodec.exportStrings(decoded.value).forEach { (path, text) ->
                        exported[embeddedPath(part.partIndex, slot.pathId, path)] = text
                    }
                }
            }
        }
        return exported.takeIf { it.isNotEmpty() }
    }

    /** Re-encodes the entry with edited strings applied; returns its new raw bytes. */
    fun rebuildTexts(
        entry: UnityBundleEntry,
        edits: Map<String, String>,
        transformOptions: UnityTextTransformOptions = UnityTextTransformOptions(),
    ): ByteArray = withOpenDocument { rebuildTextsOpen(entry, edits, transformOptions) }

    private fun rebuildTextsOpen(
        entry: UnityBundleEntry,
        edits: Map<String, String>,
        transformOptions: UnityTextTransformOptions,
    ): ByteArray {
        requireEntry(entry)
        val effectiveEdits = if (transformOptions.isEnabled) {
            UnityTextShaper.transformEdits(edits, transformOptions)
        } else {
            edits
        }
        val standalone = serialized
        if (standalone != null) {
            val value = decodeOrThrow(standalone, entry)
            TypeTreeCodec.applyStrings(value, effectiveEdits)
            return standalone.encode(dataFile, entry, value)
        }

        val embedded = embeddedOrThrow(entry)
        val editsByPart = LinkedHashMap<Int, LinkedHashMap<Long, LinkedHashMap<String, String>>>()
        effectiveEdits.forEach { (path, text) ->
            val match = EMBEDDED_PATH.matchEntire(path)
                ?: throw UnityFormatException("Edited JSON contains an invalid embedded text path: $path")
            val partIndex = match.groupValues[1].toIntOrNull()
                ?: throw UnityFormatException("Edited JSON contains an invalid serialized-file index: $path")
            val pathId = match.groupValues[2].toLongOrNull()
                ?: throw UnityFormatException("Edited JSON contains an invalid object id: $path")
            val localPath = match.groupValues[3]
            editsByPart.getOrPut(partIndex) { LinkedHashMap() }
                .getOrPut(pathId) { LinkedHashMap() }[localPath] = text
        }
        val knownParts = embedded.parts.map { it.partIndex }.toSet()
        val unknownPart = editsByPart.keys.firstOrNull { it !in knownParts }
        if (unknownPart != null) throw UnityFormatException("Edited JSON refers to unknown serialized file $unknownPart")

        val scratch = dataFile.parentFile ?: throw IOException("Unity cache directory is missing")
        val rebuiltEntry = File(scratch, ".embedded-${System.nanoTime()}.entry")
        val temporaryFiles = ArrayList<File>()
        try {
            RandomAccessFile(dataFile, "r").use { sourceData ->
                FileOutputStream(rebuiltEntry).buffered(BUFFER_SIZE).use { combinedOut ->
                    var sourceCursor = 0L
                    embedded.parts.forEach { part ->
                        if (part.relativeOffset < sourceCursor) {
                            throw UnityFormatException("Embedded serialized-file ranges overlap")
                        }
                        val gap = part.relativeOffset - sourceCursor
                        if (gap > 0) {
                            sourceData.seek(entry.offset + sourceCursor)
                            copyExactly(sourceData, combinedOut, gap)
                        }
                        val partEdits = editsByPart[part.partIndex]
                        if (partEdits.isNullOrEmpty()) {
                            sourceData.seek(entry.offset + part.relativeOffset)
                            copyExactly(sourceData, combinedOut, part.declaredSize)
                        } else {
                            val slotsByPathId = part.index.slots.associateBy { it.pathId }
                            val objectReplacements = LinkedHashMap<String, File>()
                            partEdits.forEach { (pathId, objectEdits) ->
                                val slot = slotsByPathId[pathId]
                                    ?: throw UnityFormatException("Edited JSON refers to unknown serialized object $pathId")
                                val objectEntry = part.summary.entries.firstOrNull { it.name == slot.entryName }
                                    ?: throw UnityFormatException("Serialized object table is inconsistent for $pathId")
                                val value = decodeOrThrow(part.index, objectEntry)
                                TypeTreeCodec.applyStrings(value, objectEdits)
                                val encoded = part.index.encode(dataFile, objectEntry, value)
                                val replacement = File(scratch, ".object-${part.partIndex}-$pathId-${System.nanoTime()}.bin")
                                replacement.writeBytes(encoded)
                                temporaryFiles += replacement
                                objectReplacements[slot.entryName] = replacement
                            }
                            val rebuiltPart = File(scratch, ".part-${part.partIndex}-${System.nanoTime()}.assets")
                            temporaryFiles += rebuiltPart
                            part.index.rebuild(rebuiltPart, dataFile, objectReplacements, part.summary.entries)
                            rebuiltPart.inputStream().buffered(BUFFER_SIZE).use { it.copyTo(combinedOut, BUFFER_SIZE) }
                        }
                        sourceCursor = checkedAdd(part.relativeOffset, part.declaredSize, "Embedded serialized-file range overflows")
                    }
                    if (sourceCursor < entry.size) {
                        sourceData.seek(entry.offset + sourceCursor)
                        copyExactly(sourceData, combinedOut, entry.size - sourceCursor)
                    }
                }
            }
            if (rebuiltEntry.length() > MAX_REBUILD_BYTES || rebuiltEntry.length() > Int.MAX_VALUE) {
                throw UnityFormatException("Rebuilt serialized entry is too large for the text-editing API")
            }
            return rebuiltEntry.readBytes()
        } finally {
            temporaryFiles.forEach { it.delete() }
            rebuiltEntry.delete()
        }
    }

    /** Detects localization tables by decoded field shape, never by bundle or asset name. */
    fun inspectLocalizations(entry: UnityBundleEntry): UnityLocalizationInspection = try {
        withOpenDocument { inspectLocalizationsOpen(entry) }
    } catch (_: UnityDocumentClosedException) {
        UnityLocalizationInspection.Unavailable(
            UnityTextUnavailableReason.DOCUMENT_CLOSED,
            "Unity document is closed",
        )
    }

    private fun inspectLocalizationsOpen(entry: UnityBundleEntry): UnityLocalizationInspection {
        requireEntry(entry)
        synchronized(localizationLock) { localizationInspections[entry] }?.let { return it }
        val result = try {
            val standalone = serialized
            if (standalone != null) {
                inspectLocalizationObject(standalone, entry, entry.name, 0)
            } else if (summary.signature == "UnityFS") {
                inspectEmbeddedLocalizations(entry)
            } else {
                UnityLocalizationInspection.Unavailable(
                    UnityTextUnavailableReason.NOT_SERIALIZED_FILE,
                    "Entry is not a serialized file",
                )
            }
        } catch (error: UnityDocumentClosedException) {
            throw error
        } catch (error: UnityOperationCancelledException) {
            throw error
        } catch (error: UnityUnsupportedVersionException) {
            UnityLocalizationInspection.Unavailable(
                UnityTextUnavailableReason.UNSUPPORTED_VERSION,
                error.message ?: "Unsupported serialized-file version",
            )
        } catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) throw UnityOperationCancelledException()
            UnityLocalizationInspection.Unavailable(
                UnityTextUnavailableReason.DECODE_FAILED,
                error.message ?: "Localization inspection failed",
            )
        }
        synchronized(localizationLock) { localizationInspections[entry] = result }
        return result
    }

    private fun inspectEmbeddedLocalizations(entry: UnityBundleEntry): UnityLocalizationInspection {
        val embedded = when (val parsed = embedded(entry)) {
            is EmbeddedParse.Failure -> return UnityLocalizationInspection.Unavailable(parsed.reason, parsed.diagnostic)
            is EmbeddedParse.Success -> parsed
        }
        if (embedded.objectCount > MAX_EMBEDDED_TEXT_OBJECTS) {
            return UnityLocalizationInspection.Unavailable(
                UnityTextUnavailableReason.TOO_MANY_OBJECTS,
                "Serialized entry has ${embedded.objectCount} objects",
            )
        }
        val sources = ArrayList<LocalizationSource>()
        var firstInvalid: LocalizationShapeResult.Invalid? = null
        var firstDecodeFailure: SerializedDecodeResult.Failure? = null
        embedded.parts.forEach { part ->
            part.summary.entries.forEach objectLoop@{ objectEntry ->
                ensureUnityOperationActive()
                when (val decoded = part.index.decodeResult(dataFile, objectEntry)) {
                    is SerializedDecodeResult.Failure -> if (firstDecodeFailure == null) firstDecodeFailure = decoded
                    is SerializedDecodeResult.Success -> {
                        val slot = part.index.slotFor(objectEntry) ?: return@objectLoop
                        when (
                            val shape = UnityLocalizationShape.detect(
                                decoded.value,
                                entry.name,
                                part.partIndex,
                                slot.pathId,
                                objectEntry.name,
                            )
                        ) {
                            is LocalizationShapeResult.Found -> sources += shape.sources
                            is LocalizationShapeResult.Invalid -> if (firstInvalid == null) firstInvalid = shape
                            LocalizationShapeResult.NotFound -> Unit
                        }
                    }
                }
            }
        }
        if (sources.isNotEmpty()) return UnityLocalizationInspection.Found(sources)
        firstInvalid?.let { invalid ->
            val reason = if (invalid.diagnostic.contains("ambiguous", ignoreCase = true)) {
                UnityTextUnavailableReason.LOCALIZATION_SHAPE_AMBIGUOUS
            } else {
                UnityTextUnavailableReason.LOCALIZATION_ALIGNMENT_INVALID
            }
            return UnityLocalizationInspection.Unavailable(reason, invalid.diagnostic)
        }
        if (firstDecodeFailure != null && embedded.objectCount == 1) {
            return inspectionFailure(firstDecodeFailure!!).let {
                UnityLocalizationInspection.Unavailable(it.reason, it.diagnostic)
            }
        }
        return UnityLocalizationInspection.Unavailable(
            UnityTextUnavailableReason.LOCALIZATION_TABLE_NOT_FOUND,
            "No localization table shape was found in this entry",
        )
    }

    private fun inspectLocalizationObject(
        index: SerializedFileIndex,
        objectEntry: UnityBundleEntry,
        bundleEntryName: String,
        partIndex: Int,
    ): UnityLocalizationInspection {
        return when (val decoded = index.decodeResult(dataFile, objectEntry)) {
            is SerializedDecodeResult.Failure -> inspectionFailure(decoded).let {
                UnityLocalizationInspection.Unavailable(it.reason, it.diagnostic)
            }
            is SerializedDecodeResult.Success -> {
                val slot = index.slotFor(objectEntry)
                    ?: return UnityLocalizationInspection.Unavailable(
                        UnityTextUnavailableReason.DECODE_FAILED,
                        "Serialized object table is inconsistent",
                    )
                when (
                    val shape = UnityLocalizationShape.detect(
                        decoded.value,
                        bundleEntryName,
                        partIndex,
                        slot.pathId,
                        objectEntry.name,
                    )
                ) {
                    is LocalizationShapeResult.Found -> UnityLocalizationInspection.Found(shape.sources)
                    is LocalizationShapeResult.Invalid -> UnityLocalizationInspection.Unavailable(
                        if (shape.diagnostic.contains("ambiguous", ignoreCase = true)) {
                            UnityTextUnavailableReason.LOCALIZATION_SHAPE_AMBIGUOUS
                        } else {
                            UnityTextUnavailableReason.LOCALIZATION_ALIGNMENT_INVALID
                        },
                        shape.diagnostic,
                    )
                    LocalizationShapeResult.NotFound -> UnityLocalizationInspection.Unavailable(
                        UnityTextUnavailableReason.LOCALIZATION_TABLE_NOT_FOUND,
                        "Decoded object does not contain a localization table shape",
                    )
                }
            }
        }
    }

    /**
     * Writes a staged replacement for [entry] without loading the entry or bundle as one byte array.
     * The replacement is reparsed and every supplied table is compared before atomic publication.
     */
    fun writeLocalizationReplacement(
        entry: UnityBundleEntry,
        sources: List<LocalizationSource>,
        output: File,
    ) = withOpenDocument { writeLocalizationReplacementOpen(entry, sources, output) }

    private fun writeLocalizationReplacementOpen(
        entry: UnityBundleEntry,
        sources: List<LocalizationSource>,
        output: File,
    ) {
        requireEntry(entry)
        if (sources.isEmpty()) throw UnityFormatException("No localization tables were supplied")
        if (sources.any { it.origin.entryName != entry.name }) {
            throw UnityFormatException("Localization table belongs to a different bundle entry")
        }
        val duplicate = sources.groupingBy { it.origin.tableId }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) throw UnityFormatException("Duplicate localization table '${duplicate.key}'")
        if (output.exists()) throw IOException("Output already exists: ${output.name}")
        output.parentFile?.mkdirs()
        val temporary = temporarySibling(output, "localization")
        try {
            val standalone = serialized
            if (standalone != null) {
                writeStandaloneLocalizationReplacement(standalone, entry, sources, temporary)
            } else {
                writeEmbeddedLocalizationReplacement(entry, embeddedOrThrow(entry), sources, temporary)
            }
            RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            publishTemporary(temporary, output)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun writeStandaloneLocalizationReplacement(
        index: SerializedFileIndex,
        entry: UnityBundleEntry,
        sources: List<LocalizationSource>,
        output: File,
    ) {
        val slot = index.slotFor(entry) ?: throw UnityFormatException("Serialized object table is inconsistent")
        if (sources.any { it.origin.pathId != slot.pathId || it.origin.partIndex != 0 }) {
            throw UnityFormatException("Localization table points to a different serialized object")
        }
        val value = decodeOrThrow(index, entry)
        sources.forEach { UnityLocalizationShape.apply(value, it) }
        verifyLocalizationValue(value, sources)
        val encoded = index.encode(dataFile, entry, value)
        FileOutputStream(output).use { stream ->
            stream.write(encoded)
            stream.fd.sync()
        }
    }

    private fun writeEmbeddedLocalizationReplacement(
        entry: UnityBundleEntry,
        embedded: EmbeddedParse.Success,
        sources: List<LocalizationSource>,
        output: File,
    ) {
        val sourcesByPart = sources.groupBy { it.origin.partIndex }
        val knownParts = embedded.parts.map { it.partIndex }.toSet()
        val unknownPart = sourcesByPart.keys.firstOrNull { it !in knownParts }
        if (unknownPart != null) throw UnityFormatException("Localization table refers to unknown serialized file $unknownPart")
        val scratch = dataFile.parentFile ?: throw IOException("Unity cache directory is missing")
        val temporaryFiles = ArrayList<File>()
        val outputPartOffsets = HashMap<Int, Long>()
        try {
            RandomAccessFile(dataFile, "r").use { sourceData ->
                FileOutputStream(output).buffered(BUFFER_SIZE).use { combinedOut ->
                    var sourceCursor = 0L
                    var outputCursor = 0L
                    embedded.parts.forEach { part ->
                        ensureUnityOperationActive()
                        if (part.relativeOffset < sourceCursor) throw UnityFormatException("Embedded serialized-file ranges overlap")
                        val gap = part.relativeOffset - sourceCursor
                        if (gap > 0) {
                            sourceData.seek(entry.offset + sourceCursor)
                            copyExactly(sourceData, combinedOut, gap)
                            outputCursor = checkedAdd(outputCursor, gap, "Localization replacement is too large")
                        }
                        outputPartOffsets[part.partIndex] = outputCursor
                        val partSources = sourcesByPart[part.partIndex].orEmpty()
                        if (partSources.isEmpty()) {
                            sourceData.seek(entry.offset + part.relativeOffset)
                            copyExactly(sourceData, combinedOut, part.declaredSize)
                            outputCursor = checkedAdd(outputCursor, part.declaredSize, "Localization replacement is too large")
                        } else {
                            val entriesByName = part.summary.entries.associateBy { it.name }
                            val replacements = LinkedHashMap<String, File>()
                            partSources.groupBy { it.origin.pathId }.forEach { (pathId, objectSources) ->
                                val slot = part.index.slots.firstOrNull { it.pathId == pathId }
                                    ?: throw UnityFormatException("Localization table refers to unknown object $pathId")
                                val objectEntry = entriesByName[slot.entryName]
                                    ?: throw UnityFormatException("Serialized object table is inconsistent for $pathId")
                                val value = decodeOrThrow(part.index, objectEntry)
                                objectSources.forEach { UnityLocalizationShape.apply(value, it) }
                                verifyLocalizationValue(value, objectSources)
                                val encoded = part.index.encode(dataFile, objectEntry, value)
                                val objectFile = File(
                                    scratch,
                                    ".localization-${part.partIndex}-$pathId-${System.nanoTime()}.bin",
                                )
                                objectFile.writeBytes(encoded)
                                temporaryFiles += objectFile
                                replacements[slot.entryName] = objectFile
                            }
                            val rebuiltPart = File(
                                scratch,
                                ".localization-part-${part.partIndex}-${System.nanoTime()}.assets",
                            )
                            temporaryFiles += rebuiltPart
                            part.index.rebuild(rebuiltPart, dataFile, replacements, part.summary.entries)
                            rebuiltPart.inputStream().buffered(BUFFER_SIZE).use { input ->
                                input.copyTo(combinedOut, BUFFER_SIZE)
                            }
                            outputCursor = checkedAdd(outputCursor, rebuiltPart.length(), "Localization replacement is too large")
                        }
                        sourceCursor = checkedAdd(part.relativeOffset, part.declaredSize, "Embedded serialized-file range overflows")
                    }
                    if (sourceCursor < entry.size) {
                        val tail = entry.size - sourceCursor
                        sourceData.seek(entry.offset + sourceCursor)
                        copyExactly(sourceData, combinedOut, tail)
                    }
                }
            }
            verifyEmbeddedLocalizationReplacement(output, sources, outputPartOffsets)
        } finally {
            temporaryFiles.forEach { it.delete() }
            if (Thread.currentThread().isInterrupted) output.delete()
        }
    }

    private fun verifyEmbeddedLocalizationReplacement(
        replacement: File,
        expected: List<LocalizationSource>,
        partOffsets: Map<Int, Long>,
    ) {
        expected.groupBy { it.origin.partIndex }.forEach { (partIndex, partSources) ->
            val offset = partOffsets[partIndex]
                ?: throw UnityFormatException("Rebuilt localization part $partIndex is missing")
            val (partSummary, partIndexData) = SerializedFileReader.parseEmbedded(
                replacement,
                offset,
                replacement.length() - offset,
            )
            val entriesByName = partSummary.entries.associateBy { it.name }
            partSources.groupBy { it.origin.pathId }.forEach { (pathId, objectSources) ->
                val slot = partIndexData.slots.firstOrNull { it.pathId == pathId }
                    ?: throw UnityFormatException("Rebuilt localization object $pathId is missing")
                val objectEntry = entriesByName[slot.entryName]
                    ?: throw UnityFormatException("Rebuilt localization object table is inconsistent")
                val decoded = partIndexData.decodeResult(replacement, objectEntry)
                val value = (decoded as? SerializedDecodeResult.Success)?.value
                    ?: throw UnityFormatException("Rebuilt localization object $pathId could not be decoded")
                verifyLocalizationValue(value, objectSources)
            }
        }
    }

    private fun verifyLocalizationValue(value: UVal, expected: List<LocalizationSource>) {
        val first = expected.first().origin
        val detected = UnityLocalizationShape.detect(
            value,
            first.entryName,
            first.partIndex,
            first.pathId,
            first.objectEntryName,
        ) as? LocalizationShapeResult.Found
            ?: throw UnityFormatException("Rebuilt object no longer has a localization table shape")
        val actualById = detected.sources.associateBy { it.origin.tableId }
        expected.forEach { source ->
            val actual = actualById[source.origin.tableId]
                ?: throw UnityFormatException("Rebuilt localization table '${source.origin.tableId}' is missing")
            val alignedTerms = actual.terms.size == source.terms.size &&
                actual.terms.zip(source.terms).all { (left, right) ->
                    left.key == right.key && left.values.map { it.orEmpty() } == right.values.map { it.orEmpty() }
                }
            if (actual.languages != source.languages || !alignedTerms) {
                throw UnityFormatException("Rebuilt localization table '${source.origin.tableId}' failed alignment verification")
            }
        }
    }

    /** Estimates disk space and duration required to rewrite this bundle or serialized file. */
    fun estimateRewrite(): UnityBundleRewriteEstimate =
        UnityFontInspector.estimateRewrite(source.name, summary.dataSize)

    /**
     * Read-only font capability inspection, scoped either to a single [entry] or to all serialized
     * entries in this document. Results are cached by `(path, size, lastModified, scope)`.
     */
    fun inspectFonts(entry: UnityBundleEntry? = null): UnityFontReport = try {
        withOpenDocument { inspectFontsOpen(entry) }
    } catch (_: UnityDocumentClosedException) {
        UnityFontReport(
            inspectedEntries = 0,
            tmpFontAssets = emptyList(),
            rawFonts = emptyList(),
            unavailableReason = UnityTextUnavailableReason.DOCUMENT_CLOSED,
            summaryStatement = "Unity document is closed",
        )
    }

    private fun inspectFontsOpen(entry: UnityBundleEntry?): UnityFontReport {
        if (entry != null) requireEntry(entry)
        val canonicalPath = runCatching { source.canonicalPath }.getOrDefault(source.path)
        val fileLength = source.length()
        val lastModified = source.lastModified()
        val scope = entry?.name ?: "*"
        UnityFontInspector.getCachedReport(canonicalPath, fileLength, lastModified, scope)?.let { return it }

        val standalone = serialized
        val report = if (standalone != null) {
            inspectStandaloneFonts(standalone, entry)
        } else if (summary.signature == "UnityFS") {
            val targets = if (entry != null) listOf(entry) else summary.entries
            inspectEmbeddedFonts(targets)
        } else {
            UnityFontReport(
                inspectedEntries = 0,
                tmpFontAssets = emptyList(),
                rawFonts = emptyList(),
                unavailableReason = UnityTextUnavailableReason.NOT_SERIALIZED_FILE,
                summaryStatement = "Bundle format does not expose serialized font objects",
            )
        }
        UnityFontInspector.putCachedReport(canonicalPath, fileLength, lastModified, scope, report)
        return report
    }

    private fun inspectStandaloneFonts(
        index: SerializedFileIndex,
        singleEntry: UnityBundleEntry?,
    ): UnityFontReport {
        ensureUnityOperationActive()
        val rawFonts = ArrayList<UnityRawFontReport>()
        val rawByPartAndId = LinkedHashMap<Pair<Int, Long>, UnityRawFontReport>()
        var strippedTypeTrees = 0

        // Always scan Font (class 128) slots first so m_SourceFontFile references resolve cleanly.
        index.slots.forEach { slot ->
            ensureUnityOperationActive()
            if (slot.classId == UnityFontInspector.FONT_CLASS_ID) {
                val tree = index.trees.getOrNull(slot.typeIndex)
                if (tree == null) {
                    strippedTypeTrees++
                    return@forEach
                }
                val entryObj = summary.entries.firstOrNull { it.name == slot.entryName } ?: return@forEach
                val decoded = index.decodeResult(dataFile, entryObj)
                if (decoded is SerializedDecodeResult.Success && decoded.value is UVal.Struct) {
                    val raw = UnityFontInspector.inspectRawFont(slot.entryName, 0, slot.pathId, decoded.value)
                    if (singleEntry == null || singleEntry.name == slot.entryName) rawFonts += raw
                    rawByPartAndId[0 to slot.pathId] = raw
                }
            }
        }

        val tmpAssets = ArrayList<UnityTmpFontAssetReport>()
        val targetSlots = if (singleEntry != null) {
            listOfNotNull(index.slotFor(singleEntry))
        } else {
            index.slots
        }
        targetSlots.forEach { slot ->
            ensureUnityOperationActive()
            if (slot.classId == UnityFontInspector.MONO_BEHAVIOUR_CLASS_ID) {
                val tree = index.trees.getOrNull(slot.typeIndex)
                if (tree == null) {
                    strippedTypeTrees++
                    return@forEach
                }
                if (!UnityFontInspector.hasTmpFontAssetShape(tree.root)) return@forEach
                val entryObj = summary.entries.firstOrNull { it.name == slot.entryName } ?: return@forEach
                val decoded = index.decodeResult(dataFile, entryObj)
                if (decoded is SerializedDecodeResult.Success &&
                    decoded.value is UVal.Struct &&
                    UnityFontInspector.isTmpFontAsset(decoded.value)
                ) {
                    tmpAssets += UnityFontInspector.inspectTmpFontAsset(
                        entryName = slot.entryName,
                        partIndex = 0,
                        pathId = slot.pathId,
                        struct = decoded.value,
                        rawFontsByPartAndId = rawByPartAndId,
                    )
                }
            }
        }

        val reason = deriveFontUnavailableReason(tmpAssets, rawFonts, strippedTypeTrees)
        return UnityFontReport(
            inspectedEntries = if (singleEntry != null) 1 else summary.entries.size,
            tmpFontAssets = tmpAssets,
            rawFonts = rawFonts,
            strippedTypeTreeObjects = strippedTypeTrees,
            unavailableReason = reason,
            summaryStatement = UnityFontInspector.buildSummaryStatement(tmpAssets, rawFonts, strippedTypeTrees),
        )
    }

    private fun inspectEmbeddedFonts(entries: List<UnityBundleEntry>): UnityFontReport {
        val rawFonts = ArrayList<UnityRawFontReport>()
        val tmpAssets = ArrayList<UnityTmpFontAssetReport>()
        var strippedTypeTrees = 0
        var serializedEntriesCount = 0
        var firstParseFailure: EmbeddedParse.Failure? = null

        entries.forEach { bundleEntry ->
            ensureUnityOperationActive()
            val parsed = embedded(bundleEntry)
            if (parsed is EmbeddedParse.Failure) {
                if (firstParseFailure == null) firstParseFailure = parsed
                return@forEach
            }
            val embeddedSuccess = parsed as EmbeddedParse.Success
            serializedEntriesCount++
            val rawByPartAndId = LinkedHashMap<Pair<Int, Long>, UnityRawFontReport>()

            embeddedSuccess.parts.forEach { part ->
                part.index.slots.forEach { slot ->
                    ensureUnityOperationActive()
                    if (slot.classId == UnityFontInspector.FONT_CLASS_ID) {
                        val tree = part.index.trees.getOrNull(slot.typeIndex)
                        if (tree == null) {
                            strippedTypeTrees++
                            return@forEach
                        }
                        val objectEntry = part.summary.entries.firstOrNull { it.name == slot.entryName } ?: return@forEach
                        val decoded = part.index.decodeResult(dataFile, objectEntry)
                        if (decoded is SerializedDecodeResult.Success && decoded.value is UVal.Struct) {
                            val raw = UnityFontInspector.inspectRawFont(
                                entryName = bundleEntry.name,
                                partIndex = part.partIndex,
                                pathId = slot.pathId,
                                struct = decoded.value,
                            )
                            rawFonts += raw
                            rawByPartAndId[part.partIndex to slot.pathId] = raw
                        }
                    }
                }

                part.index.slots.forEach { slot ->
                    ensureUnityOperationActive()
                    if (slot.classId == UnityFontInspector.MONO_BEHAVIOUR_CLASS_ID) {
                        val tree = part.index.trees.getOrNull(slot.typeIndex)
                        if (tree == null) {
                            strippedTypeTrees++
                            return@forEach
                        }
                        if (!UnityFontInspector.hasTmpFontAssetShape(tree.root)) return@forEach
                        val objectEntry = part.summary.entries.firstOrNull { it.name == slot.entryName } ?: return@forEach
                        val decoded = part.index.decodeResult(dataFile, objectEntry)
                        if (decoded is SerializedDecodeResult.Success &&
                            decoded.value is UVal.Struct &&
                            UnityFontInspector.isTmpFontAsset(decoded.value)
                        ) {
                            tmpAssets += UnityFontInspector.inspectTmpFontAsset(
                                entryName = bundleEntry.name,
                                partIndex = part.partIndex,
                                pathId = slot.pathId,
                                struct = decoded.value,
                                rawFontsByPartAndId = rawByPartAndId,
                            )
                        }
                    }
                }
            }
        }

        if (serializedEntriesCount == 0 && firstParseFailure != null) {
            return UnityFontReport(
                inspectedEntries = 0,
                tmpFontAssets = emptyList(),
                rawFonts = emptyList(),
                unavailableReason = firstParseFailure!!.reason,
                summaryStatement = firstParseFailure!!.diagnostic,
            )
        }

        val reason = deriveFontUnavailableReason(tmpAssets, rawFonts, strippedTypeTrees)
        return UnityFontReport(
            inspectedEntries = serializedEntriesCount,
            tmpFontAssets = tmpAssets,
            rawFonts = rawFonts,
            strippedTypeTreeObjects = strippedTypeTrees,
            unavailableReason = reason,
            summaryStatement = UnityFontInspector.buildSummaryStatement(tmpAssets, rawFonts, strippedTypeTrees),
        )
    }

    private fun deriveFontUnavailableReason(
        tmpAssets: List<UnityTmpFontAssetReport>,
        rawFonts: List<UnityRawFontReport>,
        strippedTypeTrees: Int,
    ): UnityTextUnavailableReason? {
        if (tmpAssets.isEmpty() && rawFonts.isEmpty()) {
            return if (strippedTypeTrees > 0) {
                UnityTextUnavailableReason.NO_TYPE_INFORMATION
            } else {
                UnityTextUnavailableReason.FONT_ASSET_NOT_FOUND
            }
        }
        if (tmpAssets.any { it.canRenderPersian }) return null
        tmpAssets.firstNotNullOfOrNull { it.statusReason }?.let { return it }
        if (tmpAssets.isEmpty() && rawFonts.none { it.supportsArabicCmap }) {
            return UnityTextUnavailableReason.FONT_NO_GLYPHS
        }
        return null
    }

    /**
     * Option A: Switches `TMP_FontAsset` object(s) in [entry] to `m_AtlasPopulationMode = 1`
     * (Dynamic) and binds `m_SourceFontFile` to a same-file `Font` (`classId = 128`), optionally
     * replacing that `Font`'s `m_FontData` with [ttfBytes].
     */
    fun patchFontForDynamicGeneration(
        entry: UnityBundleEntry,
        ttfBytes: ByteArray? = null,
        targetTmpPathId: Long? = null,
        targetFontPathId: Long? = null,
    ): UnityFontPatchResult = try {
        withOpenDocument {
            patchFontForDynamicGenerationOpen(entry, ttfBytes, targetTmpPathId, targetFontPathId)
        }
    } catch (_: UnityDocumentClosedException) {
        UnityFontPatchResult.Unavailable(
            UnityTextUnavailableReason.DOCUMENT_CLOSED,
            "Unity document is closed",
        )
    }

    private fun patchFontForDynamicGenerationOpen(
        entry: UnityBundleEntry,
        ttfBytes: ByteArray?,
        targetTmpPathId: Long?,
        targetFontPathId: Long?,
    ): UnityFontPatchResult {
        requireEntry(entry)
        if (ttfBytes != null) {
            if (!TtfCmapInspector.isValidFontHeader(ttfBytes)) {
                return UnityFontPatchResult.Unavailable(
                    UnityTextUnavailableReason.DECODE_FAILED,
                    "Supplied font file is not a valid TrueType/OpenType (sfnt) font",
                )
            }
            if (!TtfCmapInspector.supportsArabicScript(ttfBytes)) {
                return UnityFontPatchResult.Unavailable(
                    UnityTextUnavailableReason.FONT_NO_GLYPHS,
                    "Supplied font file does not contain Arabic/Persian codepoints in its cmap table",
                )
            }
        }

        val standalone = serialized
        if (standalone != null) {
            return patchStandaloneFont(standalone, entry, ttfBytes, targetTmpPathId, targetFontPathId)
        }
        val embedded = when (val parsed = embedded(entry)) {
            is EmbeddedParse.Failure -> return UnityFontPatchResult.Unavailable(parsed.reason, parsed.diagnostic)
            is EmbeddedParse.Success -> parsed
        }
        return patchEmbeddedFont(entry, embedded, ttfBytes, targetTmpPathId, targetFontPathId)
    }

    private fun patchStandaloneFont(
        index: SerializedFileIndex,
        selectedEntry: UnityBundleEntry,
        ttfBytes: ByteArray?,
        targetTmpPathId: Long?,
        targetFontPathId: Long?,
    ): UnityFontPatchResult {
        val scratch = dataFile.parentFile ?: throw IOException("Unity cache directory is missing")
        val replacements = LinkedHashMap<String, File>()
        val tempFiles = ArrayList<File>()
        try {
            val partResult = buildPartFontReplacements(
                partIndex = 0,
                index = index,
                entries = summary.entries,
                scratch = scratch,
                ttfBytes = ttfBytes,
                targetTmpPathId = targetTmpPathId ?: index.slotFor(selectedEntry)?.takeIf {
                    it.classId == UnityFontInspector.MONO_BEHAVIOUR_CLASS_ID
                }?.pathId,
                targetFontPathId = targetFontPathId,
                tempFiles = tempFiles,
            )
            when (partResult) {
                is PartFontPatchOutcome.Unavailable -> return UnityFontPatchResult.Unavailable(partResult.reason, partResult.diagnostic)
                is PartFontPatchOutcome.Patched -> replacements.putAll(partResult.replacements)
            }

            val rebuiltFile = File(scratch, ".standalone-font-${System.nanoTime()}.assets")
            tempFiles += rebuiltFile
            index.rebuild(rebuiltFile, dataFile, replacements, summary.entries)
            val verifyData = File(scratch, ".standalone-verify-${System.nanoTime()}.data")
            tempFiles += verifyData
            val (rebuiltSummary, rebuiltIndex) = SerializedFileReader.parse(rebuiltFile, verifyData)
            val updatedDoc = UnityBundleDocument(rebuiltFile, rebuiltSummary, verifyData, rebuiltIndex)
            val updatedReport = updatedDoc.inspectFonts()
            UnityFontInspector.clearCache()
            return UnityFontPatchResult.Success(
                rebuiltEntryBytes = rebuiltFile.readBytes(),
                patchedTmpAssets = partResult.patchedTmpCount,
                boundFontPathId = partResult.boundFontPathId,
                updatedReport = updatedReport,
            )
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    private fun patchEmbeddedFont(
        entry: UnityBundleEntry,
        embedded: EmbeddedParse.Success,
        ttfBytes: ByteArray?,
        targetTmpPathId: Long?,
        targetFontPathId: Long?,
    ): UnityFontPatchResult {
        val scratch = dataFile.parentFile ?: throw IOException("Unity cache directory is missing")
        val rebuiltEntry = File(scratch, ".font-entry-${System.nanoTime()}.bin")
        val tempFiles = ArrayList<File>()
        var totalPatchedTmp = 0
        var chosenFontPathId = 0L
        var lastUnavailable: PartFontPatchOutcome.Unavailable? = null

        try {
            RandomAccessFile(dataFile, "r").use { sourceData ->
                FileOutputStream(rebuiltEntry).buffered(BUFFER_SIZE).use { combinedOut ->
                    var sourceCursor = 0L
                    embedded.parts.forEach { part ->
                        if (part.relativeOffset < sourceCursor) {
                            throw UnityFormatException("Embedded serialized-file ranges overlap")
                        }
                        val gap = part.relativeOffset - sourceCursor
                        if (gap > 0) {
                            sourceData.seek(entry.offset + sourceCursor)
                            copyExactly(sourceData, combinedOut, gap)
                        }
                        val outcome = buildPartFontReplacements(
                            partIndex = part.partIndex,
                            index = part.index,
                            entries = part.summary.entries,
                            scratch = scratch,
                            ttfBytes = ttfBytes,
                            targetTmpPathId = targetTmpPathId,
                            targetFontPathId = targetFontPathId,
                            tempFiles = tempFiles,
                        )
                        when (outcome) {
                            is PartFontPatchOutcome.Unavailable -> {
                                lastUnavailable = outcome
                                sourceData.seek(entry.offset + part.relativeOffset)
                                copyExactly(sourceData, combinedOut, part.declaredSize)
                            }
                            is PartFontPatchOutcome.Patched -> {
                                totalPatchedTmp += outcome.patchedTmpCount
                                chosenFontPathId = outcome.boundFontPathId
                                val rebuiltPart = File(scratch, ".font-part-${part.partIndex}-${System.nanoTime()}.assets")
                                tempFiles += rebuiltPart
                                part.index.rebuild(rebuiltPart, dataFile, outcome.replacements, part.summary.entries)
                                rebuiltPart.inputStream().buffered(BUFFER_SIZE).use { it.copyTo(combinedOut, BUFFER_SIZE) }
                            }
                        }
                        sourceCursor = checkedAdd(part.relativeOffset, part.declaredSize, "Embedded serialized-file range overflows")
                    }
                    if (sourceCursor < entry.size) {
                        sourceData.seek(entry.offset + sourceCursor)
                        copyExactly(sourceData, combinedOut, entry.size - sourceCursor)
                    }
                }
            }

            if (totalPatchedTmp == 0) {
                val unavail = lastUnavailable
                return UnityFontPatchResult.Unavailable(
                    unavail?.reason ?: UnityTextUnavailableReason.FONT_ASSET_NOT_FOUND,
                    unavail?.diagnostic ?: "No compatible TMP_FontAsset could be patched in ${entry.name}",
                )
            }
            if (rebuiltEntry.length() > MAX_REBUILD_BYTES || rebuiltEntry.length() > Int.MAX_VALUE) {
                throw UnityFormatException("Rebuilt serialized entry is too large")
            }

            // Re-parse the rebuilt embedded entry to verify all structural invariants and build the updated report.
            val verifyParsed = SerializedFileReader.parseEmbedded(rebuiltEntry, 0L, rebuiltEntry.length())
            val verifyDoc = UnityBundleDocument(rebuiltEntry, verifyParsed.first, rebuiltEntry, verifyParsed.second)
            val updatedReport = verifyDoc.inspectFonts()
            UnityFontInspector.clearCache()
            return UnityFontPatchResult.Success(
                rebuiltEntryBytes = rebuiltEntry.readBytes(),
                patchedTmpAssets = totalPatchedTmp,
                boundFontPathId = chosenFontPathId,
                updatedReport = updatedReport,
            )
        } finally {
            tempFiles.forEach { it.delete() }
            rebuiltEntry.delete()
        }
    }

    private sealed class PartFontPatchOutcome {
        data class Patched(
            val replacements: Map<String, File>,
            val patchedTmpCount: Int,
            val boundFontPathId: Long,
        ) : PartFontPatchOutcome()

        data class Unavailable(
            val reason: UnityTextUnavailableReason,
            val diagnostic: String,
        ) : PartFontPatchOutcome()
    }

    private fun buildPartFontReplacements(
        partIndex: Int,
        index: SerializedFileIndex,
        entries: List<UnityBundleEntry>,
        scratch: File,
        ttfBytes: ByteArray?,
        targetTmpPathId: Long?,
        targetFontPathId: Long?,
        tempFiles: MutableList<File>,
    ): PartFontPatchOutcome {
        val entriesByName = entries.associateBy { it.name }
        val fontSlots = index.slots.filter { it.classId == UnityFontInspector.FONT_CLASS_ID }
        val tmpCandidates = index.slots.filter { slot ->
            slot.classId == UnityFontInspector.MONO_BEHAVIOUR_CLASS_ID &&
                (targetTmpPathId == null || slot.pathId == targetTmpPathId)
        }

        val decodedTmps = ArrayList<Pair<SerializedFileIndex.Slot, UVal.Struct>>()
        var missingTypeTree = false
        tmpCandidates.forEach { slot ->
            val tree = index.trees.getOrNull(slot.typeIndex)
            if (tree == null) {
                missingTypeTree = true
                return@forEach
            }
            if (!UnityFontInspector.hasTmpFontAssetShape(tree.root)) return@forEach
            val objEntry = entriesByName[slot.entryName] ?: return@forEach
            val decoded = index.decodeResult(dataFile, objEntry)
            if (decoded is SerializedDecodeResult.Success &&
                decoded.value is UVal.Struct &&
                UnityFontInspector.isTmpFontAsset(decoded.value)
            ) {
                decodedTmps += slot to decoded.value
            }
        }

        if (decodedTmps.isEmpty()) {
            return if (missingTypeTree) {
                PartFontPatchOutcome.Unavailable(
                    UnityTextUnavailableReason.NO_TYPE_INFORMATION,
                    "MonoBehaviour objects in part $partIndex have no type trees",
                )
            } else {
                PartFontPatchOutcome.Unavailable(
                    UnityTextUnavailableReason.FONT_ASSET_NOT_FOUND,
                    "No TMP_FontAsset MonoBehaviour found in part $partIndex",
                )
            }
        }

        val chosenFontSlot = when {
            targetFontPathId != null -> fontSlots.firstOrNull { it.pathId == targetFontPathId }
            else -> {
                val existingReferencedId = decodedTmps.firstNotNullOfOrNull { (_, struct) ->
                    val pptr = struct.fields["m_SourceFontFile"] as? UVal.Struct
                    val fileId = (pptr?.fields?.get("m_FileID") as? UVal.Num)?.value?.toInt() ?: 0
                    val pathId = (pptr?.fields?.get("m_PathID") as? UVal.Num)?.value ?: 0L
                    pathId.takeIf { fileId == 0 && it != 0L }
                }
                if (existingReferencedId != null) {
                    fontSlots.firstOrNull { it.pathId == existingReferencedId } ?: fontSlots.firstOrNull()
                } else {
                    fontSlots.firstOrNull()
                }
            }
        }

        val replacements = LinkedHashMap<String, File>()

        if (chosenFontSlot != null) {
            val fontTree = index.trees.getOrNull(chosenFontSlot.typeIndex)
                ?: return PartFontPatchOutcome.Unavailable(
                    UnityTextUnavailableReason.NO_TYPE_INFORMATION,
                    "Font #${chosenFontSlot.pathId} has no type tree",
                )
            val fontEntry = entriesByName[chosenFontSlot.entryName]
                ?: return PartFontPatchOutcome.Unavailable(
                    UnityTextUnavailableReason.DECODE_FAILED,
                    "Font slot #${chosenFontSlot.pathId} is missing from entry table",
                )
            val decodedFont = index.decodeResult(dataFile, fontEntry)
            val fontStruct = (decodedFont as? SerializedDecodeResult.Success)?.value as? UVal.Struct
                ?: return PartFontPatchOutcome.Unavailable(
                    UnityTextUnavailableReason.DECODE_FAILED,
                    "Could not decode Font #${chosenFontSlot.pathId}",
                )
            if (ttfBytes != null) {
                UnityFontInspector.replaceRawFontData(fontStruct, ttfBytes)
                val encodedFont = index.encode(dataFile, fontEntry, fontStruct)
                val fontFile = File(scratch, ".font-obj-$partIndex-${chosenFontSlot.pathId}-${System.nanoTime()}.bin")
                fontFile.writeBytes(encodedFont)
                tempFiles += fontFile
                replacements[chosenFontSlot.entryName] = fontFile
            } else {
                val existingBytes = UnityFontInspector.extractFontDataBytes(fontStruct)
                if (!TtfCmapInspector.supportsArabicScript(existingBytes)) {
                    return PartFontPatchOutcome.Unavailable(
                        UnityTextUnavailableReason.FONT_NO_GLYPHS,
                        "In-bundle Font #${chosenFontSlot.pathId} does not contain Arabic/Persian glyphs; supply a TTF font",
                    )
                }
            }

            decodedTmps.forEach { (tmpSlot, tmpStruct) ->
                val tmpEntry = entriesByName.getValue(tmpSlot.entryName)
                UnityFontInspector.applyDynamicFontBinding(
                    tmpStruct = tmpStruct,
                    sourceFileId = 0,
                    sourcePathId = chosenFontSlot.pathId,
                    sameFileSlots = index.slots,
                )
                val encodedTmp = index.encode(dataFile, tmpEntry, tmpStruct)
                val tmpFile = File(scratch, ".tmp-obj-$partIndex-${tmpSlot.pathId}-${System.nanoTime()}.bin")
                tmpFile.writeBytes(encodedTmp)
                tempFiles += tmpFile
                replacements[tmpSlot.entryName] = tmpFile
            }

            return PartFontPatchOutcome.Patched(
                replacements = replacements,
                patchedTmpCount = decodedTmps.size,
                boundFontPathId = chosenFontSlot.pathId,
            )
        }

        // No same-file Font (classId = 128) slot exists.
        // If ttfBytes == null and the TMP_FontAsset already references an external m_SourceFontFile
        // (m_FileID != 0 && m_PathID != 0), we can still switch m_AtlasPopulationMode from 0 to 1.
        if (ttfBytes == null) {
            var externalPathId = 0L
            decodedTmps.forEach { (tmpSlot, tmpStruct) ->
                val pptr = tmpStruct.fields["m_SourceFontFile"] as? UVal.Struct
                val fileId = (pptr?.fields?.get("m_FileID") as? UVal.Num)?.value?.toInt() ?: 0
                val pathId = (pptr?.fields?.get("m_PathID") as? UVal.Num)?.value ?: 0L
                if (fileId != 0 && pathId != 0L) {
                    val tmpEntry = entriesByName.getValue(tmpSlot.entryName)
                    UnityFontInspector.applyDynamicFontBinding(
                        tmpStruct = tmpStruct,
                        sourceFileId = fileId,
                        sourcePathId = pathId,
                        sameFileSlots = index.slots,
                    )
                    val encodedTmp = index.encode(dataFile, tmpEntry, tmpStruct)
                    val tmpFile = File(scratch, ".tmp-ext-$partIndex-${tmpSlot.pathId}-${System.nanoTime()}.bin")
                    tmpFile.writeBytes(encodedTmp)
                    tempFiles += tmpFile
                    replacements[tmpSlot.entryName] = tmpFile
                    externalPathId = pathId
                }
            }
            if (replacements.isNotEmpty()) {
                return PartFontPatchOutcome.Patched(
                    replacements = replacements,
                    patchedTmpCount = replacements.size,
                    boundFontPathId = externalPathId,
                )
            }
        }

        return PartFontPatchOutcome.Unavailable(
            UnityTextUnavailableReason.FONT_STATIC_NO_SOURCE,
            "Serialized file has no Font (classID 128) object slot and SerializedFileIndex cannot insert new object-table rows",
        )
    }

    private fun inspectObject(index: SerializedFileIndex, entry: UnityBundleEntry): UnityTextInspection {
        return when (val decoded = index.decodeResult(dataFile, entry)) {
            is SerializedDecodeResult.Failure -> inspectionFailure(decoded)
            is SerializedDecodeResult.Success -> {
                val (count, bytes, sample) = TypeTreeCodec.textStats(decoded.value)
                if (count == 0) {
                    unavailable(UnityTextUnavailableReason.NO_TEXT, "Decoded object contains no string fields")
                } else {
                    val slot = index.slotFor(entry)
                        ?: return unavailable(UnityTextUnavailableReason.DECODE_FAILED, "Serialized object table is inconsistent")
                    UnityTextInspection.Editable(
                        UnityTextInfo(
                            index.objectName(decoded.value),
                            SerializedFileReader.className(slot.classId),
                            count,
                            bytes,
                            sample,
                        ),
                    )
                }
            }
        }
    }

    private fun inspectEmbedded(entry: UnityBundleEntry): UnityTextInspection {
        val embedded = when (val parsed = embedded(entry)) {
            is EmbeddedParse.Failure -> return unavailable(parsed.reason, parsed.diagnostic)
            is EmbeddedParse.Success -> parsed
        }
        if (embedded.objectCount > MAX_EMBEDDED_TEXT_OBJECTS) {
            return unavailable(UnityTextUnavailableReason.TOO_MANY_OBJECTS, "Serialized entry has ${embedded.objectCount} objects")
        }
        var count = 0
        var bytes = 0L
        var sample = ""
        var decodedObjects = 0
        var namedObject: String? = null
        var firstFailure: SerializedDecodeResult.Failure? = null
        embedded.parts.forEach { part ->
            part.summary.entries.forEach { objectEntry ->
                when (val decoded = part.index.decodeResult(dataFile, objectEntry)) {
                    is SerializedDecodeResult.Failure -> if (firstFailure == null) firstFailure = decoded
                    is SerializedDecodeResult.Success -> {
                        decodedObjects++
                        val stats = TypeTreeCodec.textStats(decoded.value)
                        if (stats.first > 0) {
                            count += stats.first
                            bytes += stats.second
                            if (stats.third.length > sample.length) sample = stats.third
                            if (namedObject == null) namedObject = part.index.objectName(decoded.value)
                        }
                    }
                }
            }
        }
        if (count > 0) {
            return UnityTextInspection.Editable(
                UnityTextInfo(namedObject ?: entry.displayName, "SerializedFile", count, bytes, sample),
            )
        }
        if (decodedObjects > 0) {
            return unavailable(UnityTextUnavailableReason.NO_TEXT, "Serialized entry was decoded but contains no string fields")
        }
        return firstFailure?.let(::inspectionFailure)
            ?: unavailable(UnityTextUnavailableReason.NO_TEXT, "Serialized entry contains no objects")
    }

    private fun inspectionFailure(failure: SerializedDecodeResult.Failure): UnityTextInspection.Unavailable {
        val reason = when (failure.issue) {
            SerializedDecodeIssue.NO_TYPE_TREE -> UnityTextUnavailableReason.NO_TYPE_INFORMATION
            SerializedDecodeIssue.UNSUPPORTED_CLASS -> UnityTextUnavailableReason.UNSUPPORTED_OBJECT
            SerializedDecodeIssue.OBJECT_TOO_LARGE -> UnityTextUnavailableReason.DECODE_FAILED
            SerializedDecodeIssue.ENTRY_NOT_FOUND, SerializedDecodeIssue.MALFORMED_OBJECT -> UnityTextUnavailableReason.DECODE_FAILED
        }
        return unavailable(reason, failure.detail)
    }

    private fun decodeOrThrow(index: SerializedFileIndex, entry: UnityBundleEntry): UVal =
        when (val decoded = index.decodeResult(dataFile, entry)) {
            is SerializedDecodeResult.Success -> decoded.value
            is SerializedDecodeResult.Failure -> throw UnityFormatException(decoded.detail, decoded.cause)
        }

    private fun embeddedOrThrow(entry: UnityBundleEntry): EmbeddedParse.Success = when (val parsed = embedded(entry)) {
        is EmbeddedParse.Success -> parsed
        is EmbeddedParse.Failure -> if (parsed.reason == UnityTextUnavailableReason.UNSUPPORTED_VERSION) {
            throw UnityUnsupportedVersionException(parsed.diagnostic)
        } else {
            throw UnityFormatException(parsed.diagnostic)
        }
    }

    private fun embedded(entry: UnityBundleEntry): EmbeddedParse = synchronized(embeddedLock) {
        embeddedIndexes[entry]?.let { return@synchronized it }
        if (closed || !dataFile.isFile) {
            return@synchronized EmbeddedParse.Failure(
                UnityTextUnavailableReason.DOCUMENT_CLOSED,
                "Unity document is closed and its staged data is unavailable",
            )
        }
        val parts = ArrayList<EmbeddedPart>()
        var cursor = 0L
        var firstFailure: Exception? = null
        while (entry.size - cursor >= SERIALIZED_HEADER_MIN_BYTES) {
            val remaining = entry.size - cursor
            if (!SerializedFileReader.looksLikeSerializedHeader(dataFile, entry.offset + cursor, remaining)) break
            val parsed = try {
                SerializedFileReader.parseEmbedded(dataFile, entry.offset + cursor, remaining)
            } catch (e: Exception) {
                firstFailure = e
                break
            }
            val nestedSummary = parsed.first
            if (nestedSummary.fileSize <= 0 || nestedSummary.fileSize > remaining) {
                firstFailure = UnityFormatException("Embedded serialized file has an invalid declared size")
                break
            }
            parts += EmbeddedPart(parts.size, cursor, nestedSummary.fileSize, nestedSummary, parsed.second)
            cursor = checkedAdd(cursor, nestedSummary.fileSize, "Embedded serialized-file sizes overflow")
        }
        val result = if (parts.isEmpty()) {
            EmbeddedParse.Failure(
                if (firstFailure is UnityUnsupportedVersionException) {
                    UnityTextUnavailableReason.UNSUPPORTED_VERSION
                } else {
                    UnityTextUnavailableReason.NOT_SERIALIZED_FILE
                },
                firstFailure?.message ?: "Entry is not a supported serialized file",
            )
        } else {
            EmbeddedParse.Success(parts, cursor)
        }
        embeddedIndexes[entry] = result
        result
    }

    internal val cachedEmbeddedEntryCount: Int
        get() = synchronized(embeddedLock) { embeddedIndexes.size }

    private fun embeddedPath(partIndex: Int, pathId: Long, path: String): String =
        "files[$partIndex].objects[$pathId].$path"

    private fun unavailable(reason: UnityTextUnavailableReason, diagnostic: String) =
        UnityTextInspection.Unavailable(reason, diagnostic)

    private inline fun <T> withOpenDocument(block: () -> T): T {
        val read = lifecycleLock.readLock()
        read.lock()
        try {
            if (closed || !dataFile.isFile) throw UnityDocumentClosedException()
            return block()
        } finally {
            read.unlock()
        }
    }

    fun copyEntry(entry: UnityBundleEntry, output: File) =
        withOpenDocument { copyEntryOpen(entry, output) }

    private fun copyEntryOpen(entry: UnityBundleEntry, output: File) {
        requireEntry(entry)
        val parent = output.parentFile ?: throw IOException("Output directory is missing")
        parent.mkdirs()
        if (output.exists()) throw IOException("Output already exists: ${output.name}")
        val temporary = temporarySibling(output, "extract")
        try {
            RandomAccessFile(dataFile, "r").use { input ->
                FileOutputStream(temporary).use { out ->
                    input.seek(entry.offset)
                    copyExactly(input, out, entry.size)
                    out.fd.sync()
                }
            }
            if (temporary.length() != entry.size) throw IOException("Extracted Unity entry failed size verification")
            publishTemporary(temporary, output)
        } catch (e: Throwable) {
            temporary.delete()
            throw e
        }
    }

    /** Writes a normalized UnityFS bundle. Inputs are never overwritten. */
    fun repack(output: File, replacements: Map<String, File> = emptyMap()) =
        withOpenDocument { repackOpen(output, replacements) }

    private fun repackOpen(output: File, replacements: Map<String, File>) {
        if (!repackSupported) throw UnityFormatException("This Unity format is read-only; repacking needs a UnityFS bundle or serialized file")
        if (output.canonicalFile == source.canonicalFile) throw IOException("Output must not replace the source bundle")
        if (output.exists()) throw IOException("Output already exists: ${output.name}")
        val parent = output.parentFile ?: throw IOException("Output directory is missing")
        parent.mkdirs()
        val temporary = temporarySibling(output, "repack")
        try {
            repackTo(temporary, replacements)
            RandomAccessFile(temporary, "rw").use { it.fd.sync() }
            publishTemporary(temporary, output)
        } catch (e: Throwable) {
            temporary.delete()
            throw e
        }
    }

    private fun repackTo(output: File, replacements: Map<String, File>) {
        val serializedIndex = serialized
        if (serializedIndex != null) {
            serializedIndex.rebuild(output, dataFile, replacements, summary.entries)
            return
        }
        val unknown = replacements.keys - summary.entries.map { it.name }.toSet()
        if (unknown.isNotEmpty()) throw IOException("Replacement entry is not in this bundle: ${unknown.first()}")

        val parent = output.parentFile ?: throw IOException("Output directory is missing")
        parent.mkdirs()
        if (!output.createNewFile()) throw IOException("Output already exists: ${output.name}")
        val staged = File(parent, ".${output.name}.${System.nanoTime()}.data")
        var stagedCreated = false
        try {
            if (!staged.createNewFile()) throw IOException("Could not create a unique Unity staging file")
            stagedCreated = true
            val outputOffsets = LongArray(summary.entries.size)
            var sourceCursor = 0L
            var outputCursor = 0L
            RandomAccessFile(dataFile, "r").use { input ->
                FileOutputStream(staged).buffered(BUFFER_SIZE).use { out ->
                    summary.entries.withIndex().sortedBy { it.value.offset }.forEach { (index, entry) ->
                        if (entry.offset > sourceCursor) {
                            val gap = entry.offset - sourceCursor
                            input.seek(sourceCursor)
                            copyExactly(input, out, gap)
                            outputCursor = checkedAdd(outputCursor, gap, "UnityFS data is too large")
                            sourceCursor = entry.offset
                        }
                        outputOffsets[index] = outputCursor
                        val replacement = replacements[entry.name]
                        if (replacement != null) {
                            val size = replacement.length()
                            val copied = replacement.inputStream().buffered().use { it.copyTo(out, BUFFER_SIZE) }
                            if (copied != size) throw IOException("Replacement changed while it was being read")
                            outputCursor = checkedAdd(outputCursor, size, "UnityFS data is too large")
                        } else {
                            input.seek(entry.offset)
                            copyExactly(input, out, entry.size)
                            outputCursor = checkedAdd(outputCursor, entry.size, "UnityFS data is too large")
                        }
                        sourceCursor = maxOf(sourceCursor, checkedAdd(entry.offset, entry.size, "UnityFS entry range overflows"))
                    }
                    if (sourceCursor < summary.dataSize) {
                        val tail = summary.dataSize - sourceCursor
                        input.seek(sourceCursor)
                        copyExactly(input, out, tail)
                        outputCursor = checkedAdd(outputCursor, tail, "UnityFS data is too large")
                    }
                }
            }
            val updated = summary.entries.mapIndexed { index, entry ->
                entry.copy(offset = outputOffsets[index], size = replacements[entry.name]?.length() ?: entry.size)
            }
            writeNormalizedUnityFs(staged, output, source, summary, updated, outputCursor, unityFsProfile)
            UnityBundleReader.verifyUnityFsOutput(output, updated, outputCursor, unityFsProfile)
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            if (stagedCreated) staged.delete()
        }
    }

    private fun requireEntry(entry: UnityBundleEntry) {
        if (summary.entries.none { it == entry }) throw IllegalArgumentException("Entry does not belong to this bundle")
        if (entry.offset < 0 || entry.size < 0 || entry.offset > summary.dataSize || entry.size > summary.dataSize - entry.offset) {
            throw UnityFormatException("UnityFS entry is outside the data section")
        }
    }

    override fun close() {
        // A writer lock makes close wait for every in-flight copy/decode/repack operation. New
        // operations cannot acquire a read lease after [closed] is set, so staged data is never
        // deleted underneath a worker thread.
        val write = lifecycleLock.writeLock()
        write.lock()
        try {
            if (closed) return
            closed = true
            synchronized(embeddedLock) { embeddedIndexes.clear() }
            synchronized(localizationLock) { localizationInspections.clear() }
            dataFile.delete()
            dataFile.parentFile?.delete()
        } finally {
            write.unlock()
        }
    }

    private companion object {
        private val PUBLISH_LOCK = Any()
        private val LOGGER = Logger.getLogger("dev.cluvex.strata.core.unity")

        fun temporarySibling(output: File, purpose: String): File {
            val parent = output.parentFile ?: throw IOException("Output directory is missing")
            repeat(100) {
                val candidate = File(parent, ".${output.name}.${System.nanoTime()}.$purpose.tmp")
                if (!candidate.exists()) return candidate
            }
            throw IOException("Could not allocate a unique temporary output")
        }

        fun publishTemporary(temporary: File, output: File) {
            synchronized(PUBLISH_LOCK) {
                if (output.exists()) throw IOException("Output already exists: ${output.name}")
                if (!temporary.renameTo(output)) throw IOException("Could not atomically publish ${output.name}")
            }
        }

        const val BUFFER_SIZE = 64 * 1024
        const val MAX_EMBEDDED_TEXT_OBJECTS = 5_000
        const val MAX_REBUILD_BYTES = 256L * 1024 * 1024
        val EMBEDDED_PATH = Regex("""files\[(\d+)]\.objects\[(-?\d+)]\.(.+)""")
        const val SERIALIZED_HEADER_MIN_BYTES = 20L

        // The measured Unity 2022 sample uses 128 KiB blocks. This is small enough for LZ4's
        // 64 KiB match window to remain effective and bounds encoder memory on very large bundles.
        const val OUTPUT_BLOCK_SIZE = 128 * 1024

        data class OutputBlock(val uncompressed: Int, val compressed: Int, val flags: Int)

        fun writeNormalizedUnityFs(
            data: File,
            output: File,
            source: File,
            original: UnityBundleSummary,
            entries: List<UnityBundleEntry>,
            dataSize: Long,
            profile: UnityFsWriteProfile?,
        ) {
            val blockCountLong = if (dataSize == 0L) 0L else 1L + (dataSize - 1) / OUTPUT_BLOCK_SIZE
            if (blockCountLong > MAX_BLOCKS) throw UnityFormatException("UnityFS has too many data blocks")
            val preferredCodec = profile?.preferredCodec?.takeIf { it == LZ4 || it == LZ4HC } ?: LZ4HC
            val convention = profile?.hashConvention ?: UnityFsHashConvention.ZERO
            val uncompressedDigest = if (convention == UnityFsHashConvention.UNCOMPRESSED_MD5) MessageDigest.getInstance("MD5") else null
            val compressedDigest = if (convention == UnityFsHashConvention.COMPRESSED_MD5) MessageDigest.getInstance("MD5") else null
            val parent = output.parentFile ?: throw IOException("Output directory is missing")
            val compressedData = File(parent, ".${output.name}.${System.nanoTime()}.blocks")
            val blocks = ArrayList<OutputBlock>(blockCountLong.toInt())
            var rawFallbackBlocks = 0

            try {
                FileInputStream(data).buffered(BUFFER_SIZE).use { input ->
                    FileOutputStream(compressedData).buffered(BUFFER_SIZE).use { blockOut ->
                        val buffer = ByteArray(OUTPUT_BLOCK_SIZE)
                        var left = dataSize
                        while (left > 0) {
                            ensureUnityOperationActive()
                            val size = minOf(left, OUTPUT_BLOCK_SIZE.toLong()).toInt()
                            readExactly(input, buffer, size)
                            uncompressedDigest?.update(buffer, 0, size)
                            val encoded = try {
                                Lz4BlockEncoder.encode(buffer, size)
                            } catch (_: Exception) {
                                null
                            }
                            if (encoded != null && encoded.size < size) {
                                blockOut.write(encoded)
                                compressedDigest?.update(encoded)
                                blocks += OutputBlock(size, encoded.size, preferredCodec)
                            } else {
                                blockOut.write(buffer, 0, size)
                                compressedDigest?.update(buffer, 0, size)
                                blocks += OutputBlock(size, size, UNCOMPRESSED)
                                rawFallbackBlocks++
                            }
                            left -= size
                        }
                        if (input.read() >= 0) throw IOException("UnityFS staging data grew while it was being compressed")
                    }
                }
                if (rawFallbackBlocks > 0) {
                    LOGGER.fine("UnityFS repack used raw fallback for $rawFallbackBlocks of ${blocks.size} data blocks")
                }

                val storedHash = when (convention) {
                    UnityFsHashConvention.ZERO -> ByteArray(16)
                    UnityFsHashConvention.UNCOMPRESSED_MD5 -> uncompressedDigest!!.digest()
                    UnityFsHashConvention.COMPRESSED_MD5 -> compressedDigest!!.digest()
                    UnityFsHashConvention.PRESERVE -> profile?.sourceHash?.copyOf() ?: ByteArray(16)
                }
                val blockInfo = ByteArrayOutputStream().use { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.write(storedHash)
                        out.writeInt(blocks.size)
                        blocks.forEach { block ->
                            out.writeInt(block.uncompressed)
                            out.writeInt(block.compressed)
                            out.writeShort(block.flags)
                        }
                        out.writeInt(entries.size)
                        entries.forEach { entry ->
                            out.writeLong(entry.offset)
                            out.writeLong(entry.size)
                            out.writeInt(entry.flags.toInt())
                            writeCString(out, entry.name)
                        }
                    }
                    bytes.toByteArray()
                }
                val compressedInfoCandidate = try {
                    Lz4BlockEncoder.encode(blockInfo)
                } catch (_: Exception) {
                    null
                }
                val infoCodec: Int
                val infoPayload: ByteArray
                if (compressedInfoCandidate != null && compressedInfoCandidate.size < blockInfo.size) {
                    infoCodec = preferredCodec
                    infoPayload = compressedInfoCandidate
                } else {
                    infoCodec = UNCOMPRESSED
                    infoPayload = blockInfo
                }

                val headerPrefix = ByteArrayOutputStream().use { bytes ->
                    DataOutputStream(bytes).use { out ->
                        writeCString(out, "UnityFS")
                        out.writeInt(original.formatVersion)
                        writeCString(out, original.playerVersion)
                        writeCString(out, original.engineVersion)
                        // Size is appended below after the compressed table and payload are known.
                    }
                    bytes.toByteArray()
                }
                val headerSize = headerPrefix.size + 8 + 4 + 4 + 4
                // UnityFS format version 7 and later align the block table to a 16-byte boundary,
                // matching the quirk that some 2019.4.15+ games apply to version 6 bundles too.
                val alignmentPadding =
                    if (original.formatVersion >= 7 || UnityBundleReader.usesLegacyBlockAlignment(original.engineVersion)) {
                        (16 - headerSize % 16) % 16
                    } else {
                        0
                    }
                // Keep the source's post-table alignment convention. Old engines using 0x200 for
                // UnityCN encryption were rejected during parsing, so it is safe to preserve here.
                val sourceLayoutFlags = profile?.sourceFlags?.and(PRESERVED_LAYOUT_FLAGS) ?: 0L
                val outputFlags = sourceLayoutFlags or BLOCKS_AND_DIRECTORY_COMBINED or infoCodec.toLong()
                val beforeData = checkedAdd(
                    (headerSize + alignmentPadding).toLong(),
                    infoPayload.size.toLong(),
                    "UnityFS is too large",
                )
                val dataPadding = if (outputFlags and BLOCK_INFO_NEEDS_PADDING != 0L) {
                    ((16L - beforeData % 16L) % 16L).toInt()
                } else {
                    0
                }
                val declaredSize = checkedAdd(
                    checkedAdd(beforeData, dataPadding.toLong(), "UnityFS is too large"),
                    compressedData.length(),
                    "UnityFS is too large",
                )
                val trailingSize = profile?.let { (it.physicalFileSize - it.declaredBundleSize).coerceAtLeast(0L) } ?: 0L

                FileOutputStream(output).buffered(BUFFER_SIZE).use { fileOut ->
                    DataOutputStream(fileOut).use { out ->
                        out.write(headerPrefix)
                        out.writeLong(declaredSize)
                        out.writeInt(infoPayload.size)
                        out.writeInt(blockInfo.size)
                        out.writeInt(outputFlags.toInt())
                        if (alignmentPadding > 0) out.write(ByteArray(alignmentPadding))
                        out.write(infoPayload)
                        if (dataPadding > 0) out.write(ByteArray(dataPadding))
                        FileInputStream(compressedData).buffered(BUFFER_SIZE).use { it.copyTo(out, BUFFER_SIZE) }
                        if (trailingSize > 0) {
                            RandomAccessFile(source, "r").use { tail ->
                                tail.seek(profile!!.declaredBundleSize)
                                copyExactly(tail, out, trailingSize)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                output.delete()
                throw e
            } finally {
                compressedData.delete()
            }
        }

        private fun readExactly(input: InputStream, buffer: ByteArray, length: Int) {
            var position = 0
            while (position < length) {
                val count = input.read(buffer, position, length - position)
                if (count < 0) throw EOFException("UnityFS staging data is truncated")
                position += count
            }
        }

        private const val UNCOMPRESSED = 0
        private const val LZ4 = 2
        private const val LZ4HC = 3
        private const val BLOCKS_AND_DIRECTORY_COMBINED = 0x40L
        private const val BLOCK_INFO_NEEDS_PADDING = 0x200L
        private const val PRESERVED_LAYOUT_FLAGS = 0x300L
        private const val MAX_BLOCKS = 100_000
    }
}


internal fun ensureUnityOperationActive() {
    if (Thread.currentThread().isInterrupted) throw UnityOperationCancelledException()
}

internal fun copyExactly(input: InputStream, output: OutputStream, length: Long): Long {
    if (length < 0) throw UnityFormatException("Negative UnityFS block length")
    val buffer = ByteArray(64 * 1024)
    var left = length
    var copied = 0L
    while (left > 0) {
        ensureUnityOperationActive()
        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (n < 0) throw EOFException("Truncated UnityFS data")
        output.write(buffer, 0, n)
        left -= n
        copied += n
    }
    return copied
}

internal fun copyExactly(input: RandomAccessFile, output: OutputStream, length: Long): Long {
    if (length < 0) throw UnityFormatException("Negative UnityFS block length")
    val buffer = ByteArray(64 * 1024)
    var left = length
    var copied = 0L
    while (left > 0) {
        ensureUnityOperationActive()
        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (n < 0) throw EOFException("Truncated UnityFS data")
        output.write(buffer, 0, n)
        left -= n
        copied += n
    }
    return copied
}

internal fun checkedAdd(a: Long, b: Long, message: String): Long {
    if (b < 0 || a > Long.MAX_VALUE - b) throw UnityFormatException(message)
    return a + b
}

private fun writeCString(output: DataOutputStream, value: String) {
    if ('\u0000' in value) throw UnityFormatException("UnityFS string contains a null character")
    output.write(value.toByteArray(Charsets.UTF_8))
    output.writeByte(0)
}

object UnityBundleReader {
    private const val MAX_INFO_BYTES = 64L * 1024 * 1024
    private const val MAX_NODES = 500_000
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_LZMA_DICTIONARY = 256 * 1024 * 1024
    private const val FLAG_BLOCK_INFO_AT_END = 0x80L
    private const val FLAG_BLOCK_INFO_NEEDS_PADDING = 0x200L
    private const val COMPRESSION_MASK = 0x3F
    private const val BUFFER_SIZE = 64 * 1024

    fun isUnityRelated(name: String): Boolean {
        val n = name.substringAfterLast('/').lowercase()
        val ext = n.substringAfterLast('.', "")
        return ext in setOf("bundle", "assetbundle", "unity3d", "assets", "asset", "resource", "ress", "res")
    }

    fun probe(file: File): UnityProbe {
        if (!file.isFile) return UnityProbe(UnityFileKind.UNKNOWN, "", note = "Not a regular file")
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val signature = readCString(input, 32)
                when (signature) {
                    "UnityFS" -> {
                        input.seek(0)
                        UnityProbe(UnityFileKind.UNITY_FS, signature, readBundleVersion(input), note = "UnityFS bundle")
                    }
                    "UnityWeb" -> UnityProbe(UnityFileKind.UNITY_WEB, signature, note = "Legacy UnityWeb container")
                    "UnityRaw" -> UnityProbe(UnityFileKind.UNITY_RAW, signature, note = "Legacy UnityRaw container")
                    else -> probeSerializedFile(file, signature)
                }
            }
        }.getOrElse { UnityProbe(UnityFileKind.UNKNOWN, "", note = it.message ?: "Unknown Unity file") }
    }

    fun open(file: File, cacheDir: File): UnityBundleDocument {
        if (!file.isFile) throw UnityFormatException("Unity file is not a regular file")
        val cache = File(cacheDir, "unity/${file.name.hashCode().toUInt().toString(16)}-${file.length()}-${file.lastModified()}-${System.nanoTime()}")
        cache.mkdirs()
        val dataFile = File(cache, "bundle-data.bin")
        try {
            var serializedIndex: SerializedFileIndex? = null
            var unityFsProfile: UnityFsWriteProfile? = null
            val summary = when (probe(file).kind) {
                UnityFileKind.UNITY_FS -> parseUnityFs(file, dataFile).let { parsed ->
                    unityFsProfile = parsed.profile
                    parsed.summary
                }
                UnityFileKind.UNITY_WEB, UnityFileKind.UNITY_RAW -> UnityLegacyReader.parse(file, dataFile)
                UnityFileKind.SERIALIZED_FILE -> {
                    val (parsed, index) = SerializedFileReader.parse(file, dataFile)
                    serializedIndex = index
                    parsed
                }
                UnityFileKind.UNKNOWN -> throw UnityFormatException("Not a Unity file: ${file.name}")
            }
            return UnityBundleDocument(file, summary, dataFile, serializedIndex, unityFsProfile)
        } catch (e: Throwable) {
            dataFile.delete()
            cache.delete()
            throw e
        }
    }

    /** Full streaming verification used before an atomically written bundle is published. */
    internal fun verifyUnityFsOutput(
        file: File,
        expectedEntries: List<UnityBundleEntry>,
        expectedDataSize: Long,
        expectedProfile: UnityFsWriteProfile?,
    ) {
        val verified = parseUnityFs(file, dataFile = null)
        if (verified.summary.dataSize != expectedDataSize) throw UnityFormatException("Rebuilt UnityFS data size failed verification")
        if (verified.summary.entries != expectedEntries) throw UnityFormatException("Rebuilt UnityFS entry table failed verification")
        if (expectedProfile != null && verified.profile.hashConvention != expectedProfile.hashConvention) {
            throw UnityFormatException("Rebuilt UnityFS hash convention failed verification")
        }
        if (expectedProfile?.hashConvention == UnityFsHashConvention.PRESERVE &&
            !verified.profile.sourceHash.contentEquals(expectedProfile.sourceHash)
        ) {
            throw UnityFormatException("Rebuilt UnityFS preserved hash failed verification")
        }
    }

    private data class Block(val compressed: Long, val uncompressed: Long, val flags: Int)
    private data class ParsedUnityFs(val summary: UnityBundleSummary, val profile: UnityFsWriteProfile)

    private fun parseUnityFs(file: File, dataFile: File?): ParsedUnityFs {
        RandomAccessFile(file, "r").use { input ->
            val signature = readCString(input, 32)
            if (signature != "UnityFS") {
                val probe = probe(file)
                val detail = probe.note?.let { ": $it" }.orEmpty()
                throw UnityFormatException("Unsupported Unity format${detail}")
            }
            val versionLong = readU32(input)
            if (versionLong !in 6L..8L) throw UnityUnsupportedVersionException("Unsupported UnityFS format version $versionLong")
            val formatVersion = versionLong.toInt()
            val playerVersion = readCString(input, 4096)
            val engineVersion = readCString(input, 4096)
            val bundleSize = input.readLong()
            val compressedInfoSize = readU32(input)
            val uncompressedInfoSize = readU32(input)
            val flags = readU32(input)
            val headerEnd = input.filePointer
            if (flags and FLAG_BLOCK_INFO_NEEDS_PADDING != 0L && usesOldArchiveFlags(engineVersion)) {
                // Engines before the alignment fix used 0x200 for UnityCN encryption instead of padding.
                throw UnityFormatException("UnityCN-encrypted bundles are not supported")
            }
            // UnityFS format version 7 and later align the stream to 16 bytes right after the header.
            // Quirk: some 2019.4.15+ games write format version 6 with the aligned layout as well.
            val aligned = formatVersion >= 7 || usesLegacyBlockAlignment(engineVersion)
            val alignedHeaderEnd = if (aligned) align16(headerEnd.toLong()) else headerEnd.toLong()
            if (bundleSize < alignedHeaderEnd || bundleSize > file.length() || bundleSize <= 0) throw UnityFormatException("UnityFS bundle size is invalid")
            if (compressedInfoSize > MAX_INFO_BYTES || uncompressedInfoSize > MAX_INFO_BYTES || compressedInfoSize > Int.MAX_VALUE || uncompressedInfoSize > Int.MAX_VALUE) {
                throw UnityFormatException("UnityFS block table is too large")
            }
            if (compressedInfoSize > bundleSize) throw UnityFormatException("UnityFS block table is outside the file")
            val infoAtEnd = flags and FLAG_BLOCK_INFO_AT_END != 0L
            val infoOffset = if (infoAtEnd) bundleSize - compressedInfoSize else alignedHeaderEnd
            if (infoOffset < alignedHeaderEnd || infoOffset + compressedInfoSize > bundleSize) throw UnityFormatException("UnityFS block table offset is invalid")
            input.seek(infoOffset)
            val compressedInfo = ByteArray(compressedInfoSize.toInt())
            input.readFully(compressedInfo)
            val infoCompression = (flags and COMPRESSION_MASK.toLong()).toInt()
            val infoBytes = decompressBytes(compressedInfo, uncompressedInfoSize.toInt(), infoCompression)
            val table = Cursor(infoBytes)
            val storedHash = table.readBytes(16)
            val blockCount = table.readU32().toIntChecked("block count")
            if (blockCount !in 0..MAX_NODES) throw UnityFormatException("UnityFS block count is invalid")
            val blocks = ArrayList<Block>(blockCount)
            var uncompressedTotal = 0L
            var compressedTotal = 0L
            repeat(blockCount) {
                val uncompressed = table.readU32()
                val compressed = table.readU32()
                val blockFlags = table.readU16()
                if (uncompressed <= 0 || compressed <= 0) throw UnityFormatException("UnityFS contains an empty data block")
                if ((blockFlags and COMPRESSION_MASK) !in 0..3) throw UnityFormatException("Unsupported UnityFS block compression")
                uncompressedTotal = checkedAdd(uncompressedTotal, uncompressed, "UnityFS data size overflows")
                compressedTotal = checkedAdd(compressedTotal, compressed, "UnityFS compressed data size overflows")
                blocks += Block(compressed, uncompressed, blockFlags)
            }
            if (dataFile != null) {
                val availableSpace = dataFile.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (uncompressedTotal > availableSpace) throw UnityFormatException("Not enough free space to stage UnityFS data")
            }
            val nodeCount = table.readU32().toIntChecked("node count")
            if (nodeCount !in 0..MAX_NODES) throw UnityFormatException("UnityFS node count is invalid")
            val nodes = ArrayList<UnityBundleEntry>(nodeCount)
            repeat(nodeCount) {
                val offset = table.readI64()
                val size = table.readI64()
                val nodeFlags = table.readU32()
                val path = table.readCString(MAX_STRING_BYTES)
                if (offset < 0 || size < 0 || offset > uncompressedTotal || size > uncompressedTotal - offset) {
                    throw UnityFormatException("UnityFS entry is outside the data section")
                }
                nodes += UnityBundleEntry(path, offset, size, nodeFlags)
            }
            if (table.remaining > 16) throw UnityFormatException("UnityFS block table contains unexpected trailing data")
            val dataOffset = if (infoAtEnd) alignedHeaderEnd else {
                val afterInfo = infoOffset + compressedInfoSize
                if (flags and FLAG_BLOCK_INFO_NEEDS_PADDING != 0L) align16(afterInfo) else afterInfo
            }
            val dataEnd = if (infoAtEnd) infoOffset else bundleSize
            if (dataOffset > dataEnd || compressedTotal > dataEnd - dataOffset) throw UnityFormatException("UnityFS data section is truncated or overlaps its block table")
            input.seek(dataOffset)
            dataFile?.parentFile?.mkdirs()
            val detectHashConvention = storedHash.any { it != 0.toByte() }
            val uncompressedDigest = if (detectHashConvention) MessageDigest.getInstance("MD5") else null
            val compressedDigest = if (detectHashConvention) MessageDigest.getInstance("MD5") else null
            val destination: OutputStream = if (dataFile != null) {
                FileOutputStream(dataFile).buffered(BUFFER_SIZE)
            } else {
                DiscardOutputStream
            }
            destination.use { stagedOut ->
                val out: OutputStream = if (uncompressedDigest != null) DigestOutputStream(stagedOut, uncompressedDigest) else stagedOut
                var consumedCompressed = 0L
                var written = 0L
                blocks.forEach { block ->
                    ensureUnityOperationActive()
                    if (dataOffset + consumedCompressed + block.compressed > bundleSize) throw UnityFormatException("UnityFS data block is truncated")
                    val slice = RandomAccessFileSlice(input, block.compressed, compressedDigest)
                    val codec = block.flags and COMPRESSION_MASK
                    val count = when (codec) {
                        0 -> copyExactly(slice, out, block.compressed)
                        1 -> decompressLzma(slice, block.uncompressed, out)
                        2, 3 -> decompressLz4(slice, block.compressed, block.uncompressed, out)
                        else -> throw UnityFormatException("UnityFS uses unsupported compression $codec")
                    }
                    // Some decoders can finish before consuming codec padding. Include all stored
                    // payload bytes when detecting a source convention based on compressed MD5.
                    slice.drain()
                    if (count != block.uncompressed) throw UnityFormatException("UnityFS data block has an unexpected size")
                    consumedCompressed += block.compressed
                    written += count
                    input.seek(dataOffset + consumedCompressed)
                }
                out.flush()
                if (written != uncompressedTotal) throw UnityFormatException("UnityFS data section size does not match its block table")
            }
            val hashConvention = when {
                !detectHashConvention -> UnityFsHashConvention.ZERO
                storedHash.contentEquals(uncompressedDigest!!.digest()) -> UnityFsHashConvention.UNCOMPRESSED_MD5
                storedHash.contentEquals(compressedDigest!!.digest()) -> UnityFsHashConvention.COMPRESSED_MD5
                else -> UnityFsHashConvention.PRESERVE
            }
            val preferredCodec = blocks.asSequence()
                .map { it.flags and COMPRESSION_MASK }
                .filter { it == 2 || it == 3 }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: infoCompression.takeIf { it == 2 || it == 3 }
                ?: 3
            val summary = UnityBundleSummary(signature, formatVersion, playerVersion, engineVersion, bundleSize, uncompressedTotal, blockCount, nodes)
            val profile = UnityFsWriteProfile(
                preferredCodec = preferredCodec,
                sourceFlags = flags,
                hashConvention = hashConvention,
                sourceHash = storedHash,
                declaredBundleSize = bundleSize,
                physicalFileSize = file.length(),
            )
            return ParsedUnityFs(summary, profile)
        }
    }

    private fun readBundleVersion(input: RandomAccessFile): Int? = runCatching {
        input.seek(8)
        readU32(input).toInt()
    }.getOrNull()

    private fun decompressBytes(input: ByteArray, expected: Int, compression: Int): ByteArray {
        if (expected < 0) throw UnityFormatException("UnityFS block table has an invalid size")
        val out = ByteArrayOutputStream(expected.coerceAtMost(1024 * 1024))
        val source = ByteArrayInputStream(input)
        val written = when (compression) {
            0 -> copyExactly(source, out, expected.toLong())
            1 -> decompressLzma(source, expected.toLong(), out)
            2, 3 -> decompressLz4(source, input.size.toLong(), expected.toLong(), out)
            else -> throw UnityFormatException("UnityFS uses unsupported block-table compression $compression")
        }
        if (written != expected.toLong()) throw UnityFormatException("UnityFS block table could not be decompressed")
        return out.toByteArray()
    }

    private fun decompressLzma(input: InputStream, expected: Long, output: OutputStream): Long {
        val properties = ByteArray(5)
        readFully(input, properties)
        return decompressLzmaRaw(input, expected, output, properties)
    }

    internal fun decompressLzmaRaw(input: InputStream, expected: Long, output: OutputStream, properties: ByteArray): Long {
        val dictRaw = ((properties[1].toLong() and 0xFF) or
            ((properties[2].toLong() and 0xFF) shl 8) or
            ((properties[3].toLong() and 0xFF) shl 16) or
            ((properties[4].toLong() and 0xFF) shl 24))
        if (dictRaw > LZMAInputStream.DICT_SIZE_MAX.toLong() || dictRaw > MAX_LZMA_DICTIONARY) {
            throw UnityFormatException("UnityFS LZMA dictionary exceeds the supported size")
        }
        val dictSize = maxOf(dictRaw, 4096L).toInt()
        val decoder = try {
            LZMAInputStream(BufferedInputStream(input, BUFFER_SIZE), expected, properties[0], dictSize)
        } catch (e: Exception) {
            throw UnityFormatException("Could not initialize UnityFS LZMA decompression", e)
        }
        decoder.use { return copyExactly(it, output, expected) }
    }

    private fun decompressLz4(input: InputStream, compressedSize: Long, expected: Long, output: OutputStream): Long {
        if (compressedSize < 0 || expected < 0) throw UnityFormatException("Invalid UnityFS LZ4 block size")
        val window = ByteArray(65_536)
        val source = if (input is BufferedInputStream) input else BufferedInputStream(input, BUFFER_SIZE)
        var consumed = 0L
        var produced = 0L

        fun nextByte(): Int {
            if (consumed >= compressedSize) throw UnityFormatException("Truncated UnityFS LZ4 block")
            val value = source.read()
            if (value < 0) throw UnityFormatException("Truncated UnityFS LZ4 block")
            consumed++
            return value
        }

        fun length(initial: Int): Int {
            var result = initial
            if (initial == 15) {
                while (true) {
                    val extra = nextByte()
                    if (result > Int.MAX_VALUE - extra) throw UnityFormatException("UnityFS LZ4 length overflows")
                    result += extra
                    if (extra != 255) break
                }
            }
            return result
        }

        fun emit(value: Int) {
            if (produced >= expected) throw UnityFormatException("UnityFS LZ4 block expands beyond its declared size")
            val b = value.toByte()
            output.write(value)
            window[(produced and 0xFFFF).toInt()] = b
            produced++
        }

        while (consumed < compressedSize) {
            val token = nextByte()
            val literals = length(token ushr 4)
            repeat(literals) { emit(nextByte()) }
            if (consumed == compressedSize) break
            val distance = nextByte() or (nextByte() shl 8)
            if (distance <= 0 || distance > minOf(produced, 65_535L)) throw UnityFormatException("Invalid UnityFS LZ4 back-reference")
            val matchLength = length(token and 0x0F)
            if (matchLength > Int.MAX_VALUE - 4) throw UnityFormatException("UnityFS LZ4 match length overflows")
            val match = matchLength + 4
            if (match.toLong() > expected - produced) throw UnityFormatException("UnityFS LZ4 block expands beyond its declared size")
            repeat(match) {
                val source = ((produced - distance) and 0xFFFF).toInt()
                emit(window[source].toInt() and 0xFF)
            }
        }
        output.flush()
        if (produced != expected) throw UnityFormatException("UnityFS LZ4 block expands to $produced bytes; expected $expected")
        return produced
    }

    private fun probeSerializedFile(file: File, signature: String): UnityProbe = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 20) return@use UnityProbe(UnityFileKind.UNKNOWN, signature, note = "Unknown or truncated Unity file")
            var metadataSize = readU32(input)
            var declaredSize = readU32(input)
            val version = readU32(input).toInt()
            var dataOffset = readU32(input)
            if (version !in 9..100) return@use UnityProbe(UnityFileKind.UNKNOWN, signature, note = "Unknown Unity file signature")
            val endian = input.readUnsignedByte()
            input.skipBytes(3)
            if (version >= 22 && input.filePointer + 28 <= input.length()) {
                val extendedMetadata = readU32(input)
                val extendedSize = input.readLong()
                val extendedOffset = input.readLong()
                input.readLong()
                if (extendedMetadata in 1..MAX_INFO_BYTES && extendedSize in 1..file.length() && extendedOffset in 1..file.length()) {
                    metadataSize = extendedMetadata
                    declaredSize = extendedSize
                    dataOffset = extendedOffset
                }
            }
            val headerSize = if (version >= 22) 48L else 20L
            val plausible = metadataSize in 1..file.length() &&
                declaredSize in headerSize..file.length() &&
                dataOffset in headerSize..declaredSize &&
                metadataSize <= dataOffset - headerSize &&
                endian in 0..1
            if (!plausible) UnityProbe(UnityFileKind.UNKNOWN, signature, note = "Unknown Unity file signature")
            else UnityProbe(UnityFileKind.SERIALIZED_FILE, "SerializedFile", version, dataOffset = dataOffset, note = "Unity serialized asset file")
        }
    }.getOrElse { UnityProbe(UnityFileKind.UNKNOWN, signature, note = "Unknown Unity file signature") }

    private fun align16(value: Long): Long = (value + 15L) and -16L

    /**
     * Engines older than 2020.3.34 / 2021.3.2 / 2022.1.1 used archive flag 0x200 for UnityCN
     * encryption; newer engines reuse the same bit for block-info start padding.
     */
    private fun usesOldArchiveFlags(engineVersion: String): Boolean {
        val parts = parseEngineVersion(engineVersion) ?: return false
        val (major, minor, patch) = parts
        return when {
            major < 2020 -> true
            major == 2020 -> minor < 3 || (minor == 3 && patch < 34)
            major == 2021 -> minor < 3 || (minor == 3 && patch < 2)
            major == 2022 -> minor < 1
            else -> false
        }
    }

    /** Quirk: some 2019.4.15+ games write format version 6 bundles with the aligned (version 7) layout. */
    internal fun usesLegacyBlockAlignment(engineVersion: String): Boolean {
        val parts = parseEngineVersion(engineVersion) ?: return false
        val (major, minor, patch) = parts
        return major == 2019 && (minor > 4 || (minor == 4 && patch >= 15))
    }

    private fun parseEngineVersion(engineVersion: String): Triple<Int, Int, Int>? {
        val parts = Regex("\\d+").findAll(engineVersion).mapNotNull { it.value.toIntOrNull() }.toList()
        if (parts.isEmpty()) return null
        return Triple(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
    }

    internal fun readCString(input: RandomAccessFile, max: Int): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < max) {
            val b = input.read()
            if (b < 0) throw EOFException("Truncated UnityFS string")
            if (b == 0) return bytes.toByteArray().toString(Charsets.UTF_8)
            bytes.write(b)
        }
        throw UnityFormatException("UnityFS string is too long")
    }

    internal fun readU32(input: RandomAccessFile): Long = input.readInt().toLong() and 0xFFFF_FFFFL

    internal fun readFully(input: InputStream, bytes: ByteArray) {
        var p = 0
        while (p < bytes.size) {
            val n = input.read(bytes, p, bytes.size - p)
            if (n < 0) throw EOFException("Truncated UnityFS compressed block")
            p += n
        }
    }

    internal class Cursor(private val bytes: ByteArray, private val bigEndian: Boolean = true) {
        private var position = 0
        val remaining: Int get() = bytes.size - position

        fun align4() {
            position = (position + 3) and 3.inv()
        }

        fun readU8(): Int {
            requireBytes(1)
            return bytes[position++].toInt() and 0xFF
        }

        fun readU16(): Int {
            requireBytes(2)
            val value = if (bigEndian) {
                ((bytes[position].toInt() and 0xFF) shl 8) or (bytes[position + 1].toInt() and 0xFF)
            } else {
                (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)
            }
            position += 2
            return value
        }

        fun readI16(): Int {
            val value = readU16()
            return if (value and 0x8000 != 0) value - 0x10000 else value
        }

        fun readU32(): Long {
            requireBytes(4)
            val value = if (bigEndian) {
                ((bytes[position].toLong() and 0xFF) shl 24) or
                    ((bytes[position + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[position + 2].toLong() and 0xFF) shl 8) or
                    (bytes[position + 3].toLong() and 0xFF)
            } else {
                (bytes[position].toLong() and 0xFF) or
                    ((bytes[position + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[position + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[position + 3].toLong() and 0xFF) shl 24)
            }
            position += 4
            return value
        }

        fun readI32(): Int = readU32().toInt()

        fun readI64(): Long {
            requireBytes(8)
            var value = 0L
            if (bigEndian) {
                repeat(8) { value = (value shl 8) or (bytes[position++].toLong() and 0xFF) }
            } else {
                for (i in 7 downTo 0) value = (value shl 8) or (bytes[position + i].toLong() and 0xFF)
                position += 8
            }
            return value
        }

        fun readBytes(length: Int): ByteArray {
            requireBytes(length)
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        fun readCString(max: Int): String {
            val start = position
            while (position < bytes.size && position - start <= max && bytes[position] != 0.toByte()) position++
            if (position >= bytes.size || position - start > max) throw UnityFormatException("UnityFS entry name is invalid")
            val value = bytes.copyOfRange(start, position).toString(Charsets.UTF_8)
            position++
            return value
        }

        internal fun requireBytes(count: Int) {
            if (count < 0 || count > remaining) throw UnityFormatException("UnityFS block table is truncated")
        }
    }

    private object DiscardOutputStream : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        override fun close() = Unit
    }

    private class RandomAccessFileSlice(
        private val input: RandomAccessFile,
        private var remaining: Long,
        private val digest: MessageDigest? = null,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = input.read()
            if (value >= 0) {
                remaining--
                digest?.update(value.toByte())
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0) return -1
            val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) {
                remaining -= count
                digest?.update(buffer, offset, count)
            }
            return count
        }

        fun drain() {
            val buffer = ByteArray(BUFFER_SIZE)
            while (remaining > 0) {
                val count = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (count < 0) throw EOFException("Truncated UnityFS data block")
            }
        }

        override fun close() = Unit
    }

    private fun Long.toIntChecked(name: String): Int {
        if (this > Int.MAX_VALUE) throw UnityFormatException("UnityFS $name is too large")
        return toInt()
    }
}
