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
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

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

class UnityFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A parsed UnityFS bundle. The decompressed data stream is staged in app cache so entry reads
 * and replacement/repacking do not require holding the whole bundle in memory.
 */
class UnityBundleDocument internal constructor(
    val source: File,
    val summary: UnityBundleSummary,
    internal val dataFile: File,
    internal val serialized: SerializedFileIndex? = null,
) : Closeable {

    /** Repacking is implemented for UnityFS bundles and serialized files; legacy containers are read-only. */
    val repackSupported: Boolean get() = summary.signature == "UnityFS" || serialized != null

    /** True when objects can be decoded through type trees and their texts edited. */
    val supportsTextEditing: Boolean get() = serialized != null

    /** Text statistics for one serialized-file object; null when it holds no decodable text. */
    fun textInfo(entry: UnityBundleEntry): UnityTextInfo? {
        val ser = serialized ?: return null
        val slot = ser.slotFor(entry) ?: return null
        if (!ser.hasTreeFor(entry)) return null
        val value = ser.decode(dataFile, entry) ?: return null
        val (count, bytes, sample) = TypeTreeCodec.textStats(value)
        if (count == 0) return null
        return UnityTextInfo(ser.objectName(value), SerializedFileReader.className(slot.classId), count, bytes, sample)
    }

    /** Flat "path -> value" map of every string inside the object, in decode order. */
    fun exportTexts(entry: UnityBundleEntry): LinkedHashMap<String, String>? {
        val ser = serialized ?: return null
        val value = ser.decode(dataFile, entry) ?: return null
        return TypeTreeCodec.exportStrings(value)
    }

    /** Re-encodes the object with the edited strings applied; returns the new raw object bytes. */
    fun rebuildTexts(entry: UnityBundleEntry, edits: Map<String, String>): ByteArray {
        val ser = serialized ?: throw UnityFormatException("This document does not support text editing")
        val value = ser.decode(dataFile, entry) ?: throw UnityFormatException("Object cannot be decoded for editing")
        TypeTreeCodec.applyStrings(value, edits)
        return ser.encode(dataFile, entry, value)
    }

    fun copyEntry(entry: UnityBundleEntry, output: File) {
        requireEntry(entry)
        output.parentFile?.mkdirs()
        if (!output.createNewFile()) throw IOException("Output already exists: ${output.name}")
        try {
            RandomAccessFile(dataFile, "r").use { input ->
                FileOutputStream(output).use { out ->
                    input.seek(entry.offset)
                    copyExactly(input, out, entry.size)
                }
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        }
    }

    /** Writes a normalized UnityFS bundle. Inputs are never overwritten. */
    fun repack(output: File, replacements: Map<String, File> = emptyMap()) {
        if (!repackSupported) throw UnityFormatException("This Unity format is read-only; repacking needs a UnityFS bundle or serialized file")
        if (output.canonicalFile == source.canonicalFile) throw IOException("Output must not replace the source bundle")
        if (output.exists()) throw IOException("Output already exists: ${output.name}")
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
            writeNormalizedUnityFs(staged, output, summary, updated, outputCursor)
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
        dataFile.delete()
        dataFile.parentFile?.delete()
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val OUTPUT_BLOCK_SIZE = 32 * 1024 * 1024

        fun writeNormalizedUnityFs(
            data: File,
            output: File,
            original: UnityBundleSummary,
            entries: List<UnityBundleEntry>,
            dataSize: Long,
        ) {
            val blockCountLong = if (dataSize == 0L) 0L else 1L + (dataSize - 1) / OUTPUT_BLOCK_SIZE
            if (blockCountLong > MAX_BLOCKS) throw UnityFormatException("UnityFS has too many data blocks")
            val blockCount = blockCountLong.toInt()
            val blockInfo = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.write(md5(data))
                    out.writeInt(blockCount)
                    var left = dataSize
                    repeat(blockCount) {
                        val size = minOf(left, OUTPUT_BLOCK_SIZE.toLong()).toInt()
                        out.writeInt(size)
                        out.writeInt(size)
                        out.writeShort(0) // Uncompressed data block.
                        left -= size
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
            val headerPrefix = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    writeCString(out, "UnityFS")
                    out.writeInt(original.formatVersion)
                    writeCString(out, original.playerVersion)
                    writeCString(out, original.engineVersion)
                    // Size is appended below after the total has been calculated.
                }
                bytes.toByteArray()
            }
            val headerSize = headerPrefix.size + 8 + 4 + 4 + 4
            // UnityFS format version 7 and later align the block table to a 16-byte boundary,
            // matching the quirk that some 2019.4.15+ games align version 6 bundles as well.
            val alignmentPadding =
                if (original.formatVersion >= 7 || UnityBundleReader.usesLegacyBlockAlignment(original.engineVersion)) {
                    (16 - headerSize % 16) % 16
                } else {
                    0
                }
            val totalSize = checkedAdd(checkedAdd((headerSize + alignmentPadding).toLong(), blockInfo.size.toLong(), "UnityFS is too large"), dataSize, "UnityFS is too large")
            try {
                FileOutputStream(output).buffered(BUFFER_SIZE).use { fileOut ->
                    DataOutputStream(fileOut).use { out ->
                        out.write(headerPrefix)
                        out.writeLong(totalSize)
                        out.writeInt(blockInfo.size)
                        out.writeInt(blockInfo.size)
                        out.writeInt(BLOCK_INFO_AT_START)
                        if (alignmentPadding > 0) out.write(ByteArray(alignmentPadding))
                        out.write(blockInfo)
                        FileInputStream(data).buffered().use { it.copyTo(out, BUFFER_SIZE) }
                    }
                }
            } catch (e: Throwable) {
                output.delete()
                throw e
            }
        }

        private const val BLOCK_INFO_AT_START = 0x40
        private const val MAX_BLOCKS = 100_000
    }
}


internal fun copyExactly(input: InputStream, output: OutputStream, length: Long): Long {
    if (length < 0) throw UnityFormatException("Negative UnityFS block length")
    val buffer = ByteArray(64 * 1024)
    var left = length
    var copied = 0L
    while (left > 0) {
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

private fun md5(file: File): ByteArray {
    val digest = MessageDigest.getInstance("MD5")
    FileInputStream(file).buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest()
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
            val summary = when (probe(file).kind) {
                UnityFileKind.UNITY_FS -> parseUnityFs(file, dataFile)
                UnityFileKind.UNITY_WEB, UnityFileKind.UNITY_RAW -> UnityLegacyReader.parse(file, dataFile)
                UnityFileKind.SERIALIZED_FILE -> {
                    val (parsed, index) = SerializedFileReader.parse(file, dataFile)
                    serializedIndex = index
                    parsed
                }
                UnityFileKind.UNKNOWN -> throw UnityFormatException("Not a Unity file: ${file.name}")
            }
            return UnityBundleDocument(file, summary, dataFile, serializedIndex)
        } catch (e: Throwable) {
            dataFile.delete()
            cache.delete()
            throw e
        }
    }

    private data class Block(val compressed: Long, val uncompressed: Long, val flags: Int)

    private fun parseUnityFs(file: File, dataFile: File): UnityBundleSummary {
        RandomAccessFile(file, "r").use { input ->
            val signature = readCString(input, 32)
            if (signature != "UnityFS") {
                val probe = probe(file)
                val detail = probe.note?.let { ": $it" }.orEmpty()
                throw UnityFormatException("Unsupported Unity format${detail}")
            }
            val versionLong = readU32(input)
            if (versionLong !in 6L..8L) throw UnityFormatException("Unsupported UnityFS format version $versionLong")
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
            table.readBytes(16)
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
            val availableSpace = dataFile.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (uncompressedTotal > availableSpace) throw UnityFormatException("Not enough free space to stage UnityFS data")
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
            dataFile.parentFile?.mkdirs()
            FileOutputStream(dataFile).buffered(BUFFER_SIZE).use { out ->
                var consumedCompressed = 0L
                var written = 0L
                blocks.forEach { block ->
                    if (dataOffset + consumedCompressed + block.compressed > bundleSize) throw UnityFormatException("UnityFS data block is truncated")
                    val slice = RandomAccessFileSlice(input, block.compressed)
                    val codec = block.flags and COMPRESSION_MASK
                    val count = when (codec) {
                        0 -> copyExactly(slice, out, block.compressed)
                        1 -> decompressLzma(slice, block.uncompressed, out)
                        2, 3 -> decompressLz4(slice, block.compressed, block.uncompressed, out)
                        else -> throw UnityFormatException("UnityFS uses unsupported compression $codec")
                    }
                    if (count != block.uncompressed) throw UnityFormatException("UnityFS data block has an unexpected size")
                    consumedCompressed += block.compressed
                    written += count
                    input.seek(dataOffset + consumedCompressed)
                }
                if (written != uncompressedTotal) throw UnityFormatException("UnityFS data section size does not match its block table")
            }
            return UnityBundleSummary(signature, formatVersion, playerVersion, engineVersion, bundleSize, uncompressedTotal, blockCount, nodes)
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

    private class RandomAccessFileSlice(private val input: RandomAccessFile, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = input.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0) return -1
            val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count > 0) remaining -= count
            return count
        }

        override fun close() = Unit
    }

    private fun Long.toIntChecked(name: String): Int {
        if (this > Int.MAX_VALUE) throw UnityFormatException("UnityFS $name is too large")
        return toInt()
    }
}
