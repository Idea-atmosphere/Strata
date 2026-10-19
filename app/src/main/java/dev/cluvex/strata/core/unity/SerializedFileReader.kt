package dev.cluvex.strata.core.unity

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Reader for Unity serialized asset files ("*.assets", "CAB-*", "level*", ".sharedAssets", ...).
 *
 * Every object in the file is exposed as an entry so it can be previewed or extracted raw.
 * The metadata section (type table + object table) is parsed with the file's own endianness;
 * object data offsets are relative to the header's data offset. Format versions 9..22 are
 * supported; the Unity 2023+ (v23) shared type-tree layout is rejected with a clear error.
 *
 * When the file stores type trees, objects can also be decoded into editable values through
 * [SerializedFileIndex], which additionally supports rebuilding the file with replaced objects.
 */
internal object SerializedFileReader {
    private const val MAX_OBJECTS = 2_000_000
    private const val MAX_TYPES = 100_000
    private const val MAX_TREE_NODES = 4_000_000
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_DECODE_BYTES = 256 * 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    fun parse(file: File, dataFile: File): Pair<UnityBundleSummary, SerializedFileIndex> =
        parseInternal(file, 0L, file.length(), dataFile, stageObjectData = true)

    /** Parses a serialized file stored inside an already-staged UnityFS data section. */
    fun parseEmbedded(file: File, fileOffset: Long, fileLength: Long): Pair<UnityBundleSummary, SerializedFileIndex> =
        parseInternal(file, fileOffset, fileLength, file, stageObjectData = false)

    /** Cheap header check that separates arbitrary entry bytes from an unsupported file version. */
    fun looksLikeSerializedHeader(file: File, fileOffset: Long, fileLength: Long): Boolean = runCatching {
        if (fileOffset < 0 || fileLength < 20 || fileOffset > file.length() || fileLength > file.length() - fileOffset) {
            return@runCatching false
        }
        RandomAccessFile(file, "r").use { input ->
            input.seek(fileOffset)
            var metadataSize = UnityBundleReader.readU32(input)
            var declaredSize = UnityBundleReader.readU32(input)
            val version = UnityBundleReader.readU32(input)
            var dataOffset = UnityBundleReader.readU32(input)
            val endian = input.readUnsignedByte()
            input.skipBytes(3)
            var headerSize = 20L
            if (version >= 22 && fileLength >= 48) {
                metadataSize = UnityBundleReader.readU32(input)
                declaredSize = input.readLong()
                dataOffset = input.readLong()
                input.readLong()
                headerSize = 48L
            }
            metadataSize > 0 && declaredSize in headerSize..fileLength &&
                dataOffset in headerSize..declaredSize && metadataSize <= dataOffset - headerSize && endian in 0..1
        }
    }.getOrDefault(false)

    private fun parseInternal(
        file: File,
        sourceOffset: Long,
        regionLength: Long,
        dataFile: File,
        stageObjectData: Boolean,
    ): Pair<UnityBundleSummary, SerializedFileIndex> {
        RandomAccessFile(file, "r").use { input ->
            if (sourceOffset < 0 || regionLength < 0 || sourceOffset > input.length() || regionLength > input.length() - sourceOffset) {
                throw UnityFormatException("Serialized file range is outside its container")
            }
            input.seek(sourceOffset)
            UnityBundleReader.readU32(input) // metadata size (32 bit, unused for v9+)
            val fileSize32 = UnityBundleReader.readU32(input)
            val version = UnityBundleReader.readU32(input)
            val dataOffset32 = UnityBundleReader.readU32(input)
            if (version !in 9..22) throw UnityUnsupportedVersionException("Unsupported serialized file version $version")
            val bigEndian = input.readUnsignedByte() == 1
            input.skipBytes(3)
            var fileSize = fileSize32
            var dataOffset = dataOffset32
            if (version >= 22) {
                UnityBundleReader.readU32(input) // 32 bit metadata size repeat
                fileSize = input.readLong()
                dataOffset = input.readLong()
                input.readLong() // unknown
            }
            val headerSize = input.filePointer - sourceOffset
            if (fileSize < headerSize || fileSize > regionLength) throw UnityFormatException("Serialized file size is invalid")
            if (dataOffset < headerSize || dataOffset > fileSize) throw UnityFormatException("Serialized data offset is invalid")
            val metadataSize = dataOffset - headerSize
            if (metadataSize > MAX_STRING_BYTES * 64L) throw UnityFormatException("Serialized metadata is too large")
            val metadata = ByteArray(metadataSize.toInt())
            input.seek(sourceOffset + headerSize)
            input.readFully(metadata)
            val table = UnityBundleReader.Cursor(metadata, bigEndian)
            fun pos(): Int = metadata.size - table.remaining

            var unityVersion = ""
            if (version >= 7) unityVersion = table.readCString(256)
            if (version >= 8) table.readI32() // build target
            val enableTypeTree = if (version >= 13) table.readU8() != 0 else true

            val typeCount = table.readI32()
            if (typeCount !in 0..MAX_TYPES) throw UnityFormatException("Serialized type count is invalid")
            val classIds = ArrayList<Int>(typeCount)
            val trees = ArrayList<TypeTree?>(typeCount)
            repeat(typeCount) {
                val classId = table.readI32()
                if (version >= 16) table.readU8() // is stripped
                if (version >= 17) table.readI16() // script type index
                if (version >= 13) {
                    if ((version < 16 && classId < 0) || (version >= 16 && classId == 114)) table.readBytes(16)
                    table.readBytes(16) // old type hash
                }
                val tree = if (enableTypeTree) readTypeTree(table, version) else null
                if (version >= 21) {
                    val deps = table.readI32()
                    if (deps !in 0..MAX_TYPES) throw UnityFormatException("Serialized type dependencies are invalid")
                    table.readBytes(deps * 4)
                }
                classIds += classId
                trees += tree
            }

            var bigId = false
            if (version in 7..13) bigId = table.readI32() != 0

            val objectCount = table.readI32()
            if (objectCount !in 0..MAX_OBJECTS) throw UnityFormatException("Serialized object count is invalid")
            val dataSize = fileSize - dataOffset
            val entries = ArrayList<UnityBundleEntry>(objectCount)
            val slots = ArrayList<SerializedFileIndex.Slot>(objectCount)
            repeat(objectCount) {
                val pathId = when {
                    bigId -> table.readI64()
                    version < 14 -> table.readI32().toLong()
                    else -> {
                        table.align4()
                        table.readI64()
                    }
                }
                val startIsI64 = version >= 22
                val startFieldPos = pos()
                val start = if (startIsI64) table.readI64() else table.readU32()
                val sizeFieldPos = pos()
                val size = table.readU32()
                val typeId = table.readI32()
                val classId = if (version < 16) table.readU16() else classIds.getOrElse(typeId) { -1 }
                if (version < 11) table.readU16() // is destroyed
                if (version in 11..16) table.readI16() // script type index
                if (version == 15L || version == 16L) table.readU8() // is stripped
                if (start < 0 || start > dataSize || size > dataSize - start) {
                    throw UnityFormatException("Serialized object is outside the data section")
                }
                val name = "#$pathId ${className(classId)}"
                entries += UnityBundleEntry(name, start, size, 0)
                slots += SerializedFileIndex.Slot(pathId, typeId, classId, start, size, startFieldPos, startIsI64, sizeFieldPos, name)
            }

            val dataBaseOffset = if (stageObjectData) {
                val availableSpace = dataFile.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (dataSize > availableSpace) throw UnityFormatException("Not enough free space to stage Unity data")
                dataFile.parentFile?.mkdirs()
                input.seek(sourceOffset + dataOffset)
                FileOutputStream(dataFile).buffered(BUFFER_SIZE).use { out -> copyExactly(input, out, dataSize) }
                0L
            } else {
                sourceOffset + dataOffset
            }
            val summary = UnityBundleSummary(
                "SerializedFile", version.toInt(), "", unityVersion,
                fileSize, dataSize, 0, entries,
            )
            val index = SerializedFileIndex(
                source = file,
                sourceOffset = sourceOffset,
                dataBaseOffset = dataBaseOffset,
                version = version,
                bigEndian = bigEndian,
                enableTypeTree = enableTypeTree,
                headerSize = headerSize.toInt(),
                dataOffset = dataOffset,
                metadataBytes = metadata,
                slots = slots,
                trees = trees,
            )
            return summary to index
        }
    }

    private fun readTypeTree(table: UnityBundleReader.Cursor, version: Long): TypeTree {
        if (version >= 23) {
            throw UnityFormatException("Serialized file uses the Unity 2023+ shared type-tree layout, which is not supported yet")
        }
        return if (version >= 12 || version == 10L) {
            val nodes = table.readI32()
            val strings = table.readI32()
            if (nodes !in 1..MAX_TREE_NODES || strings !in 0..MAX_STRING_BYTES) {
                throw UnityFormatException("Serialized type tree is invalid")
            }
            val blobNodes = ArrayList<TypeTreeCodec.BlobNode>(nodes)
            repeat(nodes) {
                val nodeVersion = table.readU16()
                val level = table.readU8()
                table.readU8() // type flags
                val typeOffset = table.readI32()
                val nameOffset = table.readI32()
                val byteSize = table.readI32()
                table.readI32() // index
                val metaFlag = table.readI32()
                if (version >= 19) table.readBytes(8) // ref type hash
                blobNodes += TypeTreeCodec.BlobNode(nodeVersion, level, typeOffset, nameOffset, byteSize, metaFlag)
            }
            val stringBytes = table.readBytes(strings)
            TypeTreeCodec.treeFromBlob(blobNodes, stringBytes)
        } else {
            TypeTree(TypeTreeCodec.treeFromLegacy(table))
        }
    }

    private fun copyExactly(input: RandomAccessFile, out: FileOutputStream, length: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var left = length
        var total = 0L
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read < 0) throw UnityFormatException("Serialized file is truncated")
            out.write(buffer, 0, read)
            left -= read
            total += read
        }
        return total
    }

    internal fun className(classId: Int): String = CLASS_NAMES[classId] ?: "Class$classId"

    private val CLASS_NAMES = mapOf(
        1 to "GameObject", 2 to "Component", 4 to "Transform", 8 to "Behaviour",
        11 to "AudioManager", 13 to "InputManager", 20 to "Camera", 21 to "Material",
        23 to "Texture", 25 to "Renderer", 28 to "Texture2D", 33 to "MeshFilter",
        43 to "Mesh", 45 to "Skybox", 47 to "QualitySettings", 48 to "Shader",
        49 to "TextAsset", 60 to "Rigidbody", 65 to "BoxCollider", 74 to "AnimationClip",
        83 to "AudioClip", 95 to "Animator", 104 to "MeshRenderer", 114 to "MonoBehaviour",
        115 to "MonoScript", 128 to "Font", 142 to "AssetBundle", 155 to "AnimatorController",
        213 to "Sprite", 222 to "CanvasRenderer", 223 to "Canvas", 224 to "RectTransform",
        329 to "VideoClip",
    )
}

internal enum class SerializedDecodeIssue {
    ENTRY_NOT_FOUND,
    NO_TYPE_TREE,
    UNSUPPORTED_CLASS,
    OBJECT_TOO_LARGE,
    MALFORMED_OBJECT,
}

internal sealed class SerializedDecodeResult {
    data class Success(val value: UVal) : SerializedDecodeResult()
    data class Failure(
        val issue: SerializedDecodeIssue,
        val detail: String,
        val cause: Throwable? = null,
    ) : SerializedDecodeResult()
}

/**
 * Everything needed to decode, re-encode and rebuild a parsed serialized file. Object table
 * field positions are recorded during parsing so a rebuild only patches offsets/sizes in place.
 * Rebuilds may resize existing objects, but cannot grow metadata or add/remove object-table rows;
 * callers that need a different object count must use a full serialized-file authoring pipeline.
 */
internal class SerializedFileIndex(
    val source: File,
    val sourceOffset: Long,
    val dataBaseOffset: Long,
    val version: Long,
    val bigEndian: Boolean,
    val enableTypeTree: Boolean,
    val headerSize: Int,
    val dataOffset: Long,
    val metadataBytes: ByteArray,
    val slots: List<Slot>,
    val trees: List<TypeTree?>,
) {
    internal class Slot(
        val pathId: Long,
        val typeIndex: Int,
        val classId: Int,
        val start: Long,
        val size: Long,
        val startFieldPos: Int,
        val startIsI64: Boolean,
        val sizeFieldPos: Int,
        val entryName: String,
    )

    private val slotsByName: Map<String, Slot> = slots.associateBy { it.entryName }

    fun slotFor(entry: UnityBundleEntry): Slot? = slotsByName[entry.name]

    fun readObjectBytes(dataFile: File, slot: Slot): ByteArray {
        if (slot.size > MAX_DECODE_BYTES) throw UnityFormatException("Serialized object is too large to decode")
        val bytes = ByteArray(slot.size.toInt())
        RandomAccessFile(dataFile, "r").use { input ->
            input.seek(dataBaseOffset + slot.start)
            input.readFully(bytes)
        }
        return bytes
    }

    /** Typed decode result; failures keep the concrete reason instead of disappearing as null. */
    fun decodeResult(dataFile: File, entry: UnityBundleEntry): SerializedDecodeResult {
        val slot = slotFor(entry) ?: return SerializedDecodeResult.Failure(
            SerializedDecodeIssue.ENTRY_NOT_FOUND,
            "Entry does not belong to this serialized file",
        )
        val bytes = try {
            readObjectBytes(dataFile, slot)
        } catch (e: UnityFormatException) {
            return SerializedDecodeResult.Failure(SerializedDecodeIssue.OBJECT_TOO_LARGE, e.message ?: "Object is too large", e)
        } catch (e: Exception) {
            return SerializedDecodeResult.Failure(SerializedDecodeIssue.MALFORMED_OBJECT, e.message ?: "Object could not be read", e)
        }
        return decodeBytes(slot, bytes)
    }

    private fun decodeBytes(slot: Slot, bytes: ByteArray): SerializedDecodeResult {
        val tree = trees.getOrNull(slot.typeIndex)
        if (tree != null) {
            return try {
                val (value, consumed) = TypeTreeCodec.decodeChecked(tree, bytes, bigEndian)
                if (consumed != bytes.size) {
                    SerializedDecodeResult.Failure(
                        SerializedDecodeIssue.MALFORMED_OBJECT,
                        "Decoded ${consumed} of ${bytes.size} object bytes",
                    )
                } else {
                    SerializedDecodeResult.Success(value)
                }
            } catch (e: Exception) {
                SerializedDecodeResult.Failure(
                    SerializedDecodeIssue.MALFORMED_OBJECT,
                    e.message ?: "Object does not match its type tree",
                    e,
                )
            }
        }
        if (slot.classId == TEXT_ASSET_CLASS_ID) {
            val decoded = try {
                TypeTreeCodec.decodeTextAsset(bytes, bigEndian)
            } catch (e: Exception) {
                return SerializedDecodeResult.Failure(
                    SerializedDecodeIssue.MALFORMED_OBJECT,
                    "TextAsset does not match the built-in fallback layout",
                    e,
                )
            }
            val (name, script) = decoded
            val scriptText = decodeUtf8(script)
            val scriptValue: UVal = if (scriptText != null) UVal.Str(scriptText) else UVal.Bytes(script)
            return SerializedDecodeResult.Success(
                UVal.Struct(
                    linkedMapOf(
                        "m_Name" to UVal.Str(name),
                        "m_Script" to scriptValue,
                    ),
                ),
            )
        }
        val className = SerializedFileReader.className(slot.classId)
        val detail = when (slot.classId) {
            MONO_BEHAVIOUR_CLASS_ID -> "MonoBehaviour custom fields require a type tree or managed script schema"
            FONT_CLASS_ID -> "Font layout requires a type tree for this Unity version"
            else -> "$className has no type tree and no safe built-in fallback"
        }
        return SerializedDecodeResult.Failure(
            if (enableTypeTree) SerializedDecodeIssue.UNSUPPORTED_CLASS else SerializedDecodeIssue.NO_TYPE_TREE,
            detail,
        )
    }

    /** Re-encodes a value and immediately decodes it again with the same schema. */
    fun encode(dataFile: File, entry: UnityBundleEntry, value: UVal): ByteArray {
        val slot = slotFor(entry) ?: throw UnityFormatException("Entry does not belong to this serialized file")
        val tree = trees.getOrNull(slot.typeIndex)
        val encoded = if (tree != null) {
            TypeTreeCodec.encode(tree, value, bigEndian)
        } else if (slot.classId == TEXT_ASSET_CLASS_ID && value is UVal.Struct) {
            val name = (value.fields["m_Name"] as? UVal.Str)?.value
                ?: throw UnityFormatException("TextAsset value is missing m_Name")
            val script = when (val scriptValue = value.fields["m_Script"]) {
                is UVal.Str -> scriptValue.value.toByteArray(Charsets.UTF_8)
                is UVal.Bytes -> scriptValue.data
                else -> throw UnityFormatException("TextAsset value is missing m_Script")
            }
            TypeTreeCodec.encodeTextAsset(name, script, bigEndian)
        } else {
            throw UnityFormatException("${SerializedFileReader.className(slot.classId)} has no type information and cannot be re-encoded safely")
        }
        when (val verified = decodeBytes(slot, encoded)) {
            is SerializedDecodeResult.Failure -> throw UnityFormatException(
                "Re-encoded object failed its self-check: ${verified.detail}",
                verified.cause,
            )
            is SerializedDecodeResult.Success -> {
                if (TypeTreeCodec.exportStrings(verified.value) != TypeTreeCodec.exportStrings(value)) {
                    throw UnityFormatException("Re-encoded object failed its text round-trip self-check")
                }
            }
        }
        return encoded
    }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    fun objectName(value: UVal): String? =
        (value as? UVal.Struct)?.fields?.get("m_Name")?.let { (it as? UVal.Str)?.value }

    /**
     * Writes a new serialized file where entries listed in [replacements] use the replacement
     * file's bytes. Objects are written sequentially in table order; the object table is patched
     * in place (fixed width fields) so metadata size and data offset stay valid.
     */
    fun rebuild(output: File, dataFile: File, replacements: Map<String, File>, entries: List<UnityBundleEntry>) {
        if (entries.size != slots.size) throw IOException("Entry list does not match this serialized file")
        val unknown = replacements.keys - slotsByName.keys
        if (unknown.isNotEmpty()) throw IOException("Replacement entry is not in this serialized file: ${unknown.first()}")
        val parent = output.parentFile ?: throw IOException("Output directory is missing")
        parent.mkdirs()

        val newStarts = LongArray(slots.size)
        val newSizes = LongArray(slots.size)
        var cursor = 0L
        slots.forEachIndexed { index, slot ->
            val replacement = replacements[slot.entryName]
            if (replacement != null && !replacement.isFile) throw IOException("Replacement file is missing: ${replacement.name}")
            newStarts[index] = cursor
            newSizes[index] = replacement?.length() ?: slot.size
            cursor += newSizes[index]
            if (cursor < 0) throw IOException("Serialized data is too large")
        }
        val newDataSize = cursor
        val space = parent.usableSpace
        if (space != 0L && newDataSize > space) throw IOException("Not enough free space to rebuild the serialized file")

        val metadata = metadataBytes.copyOf()
        slots.forEachIndexed { index, slot ->
            if (newStarts[index] != slot.start) writeField(metadata, slot.startFieldPos, newStarts[index], if (slot.startIsI64) 8 else 4)
            if (newSizes[index] != slot.size) writeField(metadata, slot.sizeFieldPos, newSizes[index], 4)
        }
        val newFileSize = dataOffset + newDataSize

        if (!output.createNewFile()) throw IOException("Output already exists: ${output.name}")
        try {
            val header = ByteArray(headerSize)
            RandomAccessFile(source, "r").use {
                it.seek(sourceOffset)
                it.readFully(header)
            }
            if (version >= 22) {
                writeField(header, 24, newFileSize, 8, bigEndian = true)
            } else {
                writeField(header, 4, newFileSize, 4, bigEndian = true)
            }
            FileOutputStream(output).buffered(64 * 1024).use { out ->
                out.write(header)
                out.write(metadata)
                val padding = dataOffset - headerSize - metadata.size
                if (padding > 0) out.write(ByteArray(padding.toInt()))
                RandomAccessFile(dataFile, "r").use { staged ->
                    val buffer = ByteArray(64 * 1024)
                    slots.forEachIndexed { index, slot ->
                        val replacement = replacements[slot.entryName]
                        if (replacement != null) {
                            replacement.inputStream().buffered().use { input ->
                                var left = newSizes[index]
                                while (left > 0) {
                                    val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                                    if (read < 0) throw IOException("Replacement changed while it was being read")
                                    out.write(buffer, 0, read)
                                    left -= read
                                }
                            }
                        } else {
                            staged.seek(dataBaseOffset + slot.start)
                            var left = slot.size
                            while (left > 0) {
                                val read = staged.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                                if (read < 0) throw UnityFormatException("Staged serialized data is truncated")
                                out.write(buffer, 0, read)
                                left -= read
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        }

        // Self-check: the rebuilt file must parse and expose the expected object sizes.
        val verifyData = File(parent, ".${output.name}.${System.nanoTime()}.verify")
        try {
            val (summary, _) = SerializedFileReader.parse(output, verifyData)
            if (summary.entries.size != slots.size) throw IOException("Rebuilt serialized file has a wrong object count")
            summary.entries.forEachIndexed { index, entry ->
                if (entry.size != newSizes[index]) throw IOException("Rebuilt serialized file has a wrong object size")
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            verifyData.delete()
        }
    }

    private fun writeField(bytes: ByteArray, pos: Int, value: Long, width: Int, bigEndian: Boolean = this.bigEndian) {
        if (pos < 0 || pos + width > bytes.size) throw IOException("Serialized metadata patch is out of bounds")
        if (width == 4 && (value < 0 || value > 0xFFFFFFFFL)) throw IOException("Serialized object is too large for its 32-bit field")
        if (bigEndian) {
            for (i in width - 1 downTo 0) bytes[pos + (width - 1 - i)] = ((value ushr (8 * i)) and 0xFF).toByte()
        } else {
            for (i in 0 until width) bytes[pos + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    companion object {
        private const val MAX_DECODE_BYTES = 256 * 1024 * 1024
        private const val TEXT_ASSET_CLASS_ID = 49
        private const val MONO_BEHAVIOUR_CLASS_ID = 114
        private const val FONT_CLASS_ID = 128
    }
}
