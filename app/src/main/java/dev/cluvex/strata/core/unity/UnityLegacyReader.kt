package dev.cluvex.strata.core.unity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Parser for the legacy streamed Unity containers: UnityRaw (uncompressed) and UnityWeb (LZMA).
 *
 * Layout (big endian): signature, version, player version, engine version, [hash + crc when
 * version >= 4], minimum streamed bytes, header size, level streaming knobs, the compressed
 * directory+data block at [headerSize], then optional file size fields. The decompressed stream
 * starts with the directory (node count, then name/offset/size triples) followed by the file
 * data; node offsets are absolute file offsets with the stream mapped at [headerSize].
 */
internal object UnityLegacyReader {
    private const val MAX_STREAM_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_LEVELS = 1024
    private const val MAX_NODES = 500_000
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    fun parse(file: File, dataFile: File): UnityBundleSummary {
        RandomAccessFile(file, "r").use { input ->
            val signature = UnityBundleReader.readCString(input, 32)
            if (signature != "UnityWeb" && signature != "UnityRaw") {
                throw UnityFormatException("Unsupported legacy Unity container")
            }
            val version = UnityBundleReader.readU32(input)
            if (version !in 1..9) throw UnityUnsupportedVersionException("Unsupported legacy Unity bundle version $version")
            val playerVersion = UnityBundleReader.readCString(input, 4096)
            val engineVersion = UnityBundleReader.readCString(input, 4096)
            if (version >= 4) {
                input.skipBytes(16) // bundle hash
                UnityBundleReader.readU32(input) // crc
            }
            UnityBundleReader.readU32(input) // minimumStreamedBytes
            val headerSize = UnityBundleReader.readU32(input)
            UnityBundleReader.readU32(input) // numberOfLevelsToDownloadBeforeStreaming
            val levelCount = input.readInt()
            if (levelCount !in 1..MAX_LEVELS) throw UnityFormatException("Legacy Unity bundle has an invalid level count")
            input.skipBytes(8 * (levelCount - 1))
            val compressedSize = UnityBundleReader.readU32(input)
            val uncompressedSize = UnityBundleReader.readU32(input)
            var completeFileSize = 0L
            if (version >= 2) completeFileSize = UnityBundleReader.readU32(input)
            if (version >= 3) UnityBundleReader.readU32(input) // fileInfoHeaderSize
            if (headerSize < input.filePointer || headerSize > file.length()) {
                throw UnityFormatException("Legacy Unity header size is invalid")
            }
            if (compressedSize > file.length() - headerSize) {
                throw UnityFormatException("Legacy Unity data block is outside the file")
            }
            if (uncompressedSize > MAX_STREAM_BYTES) throw UnityFormatException("Legacy Unity stream is too large")
            val availableSpace = dataFile.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (uncompressedSize > availableSpace) throw UnityFormatException("Not enough free space to stage Unity data")
            input.seek(headerSize)
            val compressed = ByteArray(compressedSize.toInt())
            input.readFully(compressed)
            val stream = if (signature == "UnityWeb") {
                decompressWeb(compressed, uncompressedSize)
            } else {
                if (compressedSize != uncompressedSize) throw UnityFormatException("UnityRaw block sizes disagree")
                compressed
            }
            if (stream.size.toLong() != uncompressedSize) {
                throw UnityFormatException("Legacy Unity stream has an unexpected size")
            }
            val table = UnityBundleReader.Cursor(stream)
            val nodeCount = table.readI32()
            if (nodeCount !in 0..MAX_NODES) throw UnityFormatException("Legacy Unity node count is invalid")
            val entries = ArrayList<UnityBundleEntry>(nodeCount)
            repeat(nodeCount) {
                val path = table.readCString(MAX_STRING_BYTES)
                val offset = table.readU32()
                val size = table.readU32()
                val relative = offset - headerSize
                if (relative < 0 || relative > stream.size || size > stream.size - relative) {
                    throw UnityFormatException("Legacy Unity entry is outside the stream")
                }
                entries += UnityBundleEntry(path, relative, size, 0)
            }
            dataFile.parentFile?.mkdirs()
            FileOutputStream(dataFile).buffered(BUFFER_SIZE).use { out -> out.write(stream) }
            val fileSize = if (completeFileSize > 0) completeFileSize else file.length()
            return UnityBundleSummary(
                signature, version.toInt(), playerVersion, engineVersion,
                fileSize, stream.size.toLong(), levelCount, entries,
            )
        }
    }

    /** UnityWeb stores LZMA with a 5 byte property header plus a little endian uncompressed size. */
    private fun decompressWeb(compressed: ByteArray, expected: Long): ByteArray {
        if (compressed.size < 13) throw UnityFormatException("Truncated UnityWeb LZMA header")
        var declared = 0L
        for (i in 7 downTo 0) declared = (declared shl 8) or (compressed[5 + i].toLong() and 0xFF)
        if (declared != expected) throw UnityFormatException("UnityWeb LZMA size mismatch")
        val properties = compressed.copyOfRange(0, 5)
        val out = ByteArrayOutputStream(expected.coerceAtMost(1024 * 1024).toInt())
        val source = ByteArrayInputStream(compressed, 13, compressed.size - 13)
        val written = UnityBundleReader.decompressLzmaRaw(source, expected, out, properties)
        if (written != expected) throw UnityFormatException("UnityWeb LZMA stream has an unexpected size")
        return out.toByteArray()
    }
}
