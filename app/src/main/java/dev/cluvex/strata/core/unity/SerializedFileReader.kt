package dev.cluvex.strata.core.unity

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Reader for Unity serialized asset files ("*.assets", "CAB-*", "level*", ".sharedAssets", ...).
 *
 * Every object in the file is exposed as an entry so it can be previewed or extracted raw.
 * The metadata section (type table + object table) is parsed with the file's own endianness;
 * object data offsets are relative to the header's data offset. Format versions 9..22 are
 * supported; the Unity 2023+ (v23) shared type-tree layout is rejected with a clear error.
 */
internal object SerializedFileReader {
    private const val MAX_OBJECTS = 2_000_000
    private const val MAX_TYPES = 100_000
    private const val MAX_TREE_NODES = 4_000_000
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    fun parse(file: File, dataFile: File): UnityBundleSummary {
        RandomAccessFile(file, "r").use { input ->
            UnityBundleReader.readU32(input) // metadata size (32 bit, unused for v9+)
            val fileSize32 = UnityBundleReader.readU32(input)
            val version = UnityBundleReader.readU32(input)
            val dataOffset32 = UnityBundleReader.readU32(input)
            if (version !in 9..22) throw UnityFormatException("Unsupported serialized file version $version")
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
            val headerSize = input.filePointer
            if (fileSize < headerSize || fileSize > file.length()) throw UnityFormatException("Serialized file size is invalid")
            if (dataOffset < headerSize || dataOffset > fileSize) throw UnityFormatException("Serialized data offset is invalid")
            val metadataSize = dataOffset - headerSize
            if (metadataSize > MAX_STRING_BYTES * 64L) throw UnityFormatException("Serialized metadata is too large")
            val metadata = ByteArray(metadataSize.toInt())
            input.seek(headerSize)
            input.readFully(metadata)
            val table = UnityBundleReader.Cursor(metadata, bigEndian)

            var unityVersion = ""
            if (version >= 7) unityVersion = table.readCString(256)
            if (version >= 8) table.readI32() // build target
            val enableTypeTree = if (version >= 13) table.readU8() != 0 else true

            val typeCount = table.readI32()
            if (typeCount !in 0..MAX_TYPES) throw UnityFormatException("Serialized type count is invalid")
            val classIds = ArrayList<Int>(typeCount)
            repeat(typeCount) {
                val classId = table.readI32()
                if (version >= 16) table.readU8() // is stripped
                if (version >= 17) table.readI16() // script type index
                if (version >= 13) {
                    if ((version < 16 && classId < 0) || (version >= 16 && classId == 114)) table.readBytes(16)
                    table.readBytes(16) // old type hash
                }
                if (enableTypeTree) skipTypeTree(table, version)
                if (version >= 21) {
                    val deps = table.readI32()
                    if (deps !in 0..MAX_TYPES) throw UnityFormatException("Serialized type dependencies are invalid")
                    table.readBytes(deps * 4)
                }
                classIds += classId
            }

            var bigId = false
            if (version in 7..13) bigId = table.readI32() != 0

            val objectCount = table.readI32()
            if (objectCount !in 0..MAX_OBJECTS) throw UnityFormatException("Serialized object count is invalid")
            val dataSize = fileSize - dataOffset
            val entries = ArrayList<UnityBundleEntry>(objectCount)
            repeat(objectCount) {
                val pathId = when {
                    bigId -> table.readI64()
                    version < 14 -> table.readI32().toLong()
                    else -> {
                        table.align4()
                        table.readI64()
                    }
                }
                val start = if (version >= 22) table.readI64() else table.readU32()
                val size = table.readU32()
                val typeId = table.readI32()
                val classId = if (version < 16) table.readU16() else classIds.getOrElse(typeId) { -1 }
                if (version < 11) table.readU16() // is destroyed
                if (version in 11..16) table.readI16() // script type index
                if (version == 15L || version == 16L) table.readU8() // is stripped
                if (start < 0 || start > dataSize || size > dataSize - start) {
                    throw UnityFormatException("Serialized object is outside the data section")
                }
                entries += UnityBundleEntry("#$pathId ${className(classId)}", start, size, 0)
            }

            val availableSpace = dataFile.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (dataSize > availableSpace) throw UnityFormatException("Not enough free space to stage Unity data")
            dataFile.parentFile?.mkdirs()
            input.seek(dataOffset)
            FileOutputStream(dataFile).buffered(BUFFER_SIZE).use { out -> copyExactly(input, out, dataSize) }
            return UnityBundleSummary(
                "SerializedFile", version.toInt(), "", unityVersion,
                fileSize, dataSize, 0, entries,
            )
        }
    }

    private fun skipTypeTree(table: UnityBundleReader.Cursor, version: Long) {
        if (version >= 23) {
            throw UnityFormatException("Serialized file uses the Unity 2023+ shared type-tree layout, which is not supported yet")
        }
        if (version >= 12 || version == 10L) {
            val nodes = table.readI32()
            val strings = table.readI32()
            if (nodes !in 0..MAX_TREE_NODES || strings !in 0..MAX_STRING_BYTES) {
                throw UnityFormatException("Serialized type tree is invalid")
            }
            val nodeSize = if (version >= 19) 32 else 24
            table.readBytes(nodes * nodeSize)
            table.readBytes(strings)
        } else {
            skipTypeNodes(table, 0)
        }
    }

    private fun skipTypeNodes(table: UnityBundleReader.Cursor, depth: Int) {
        if (depth > 96) throw UnityFormatException("Serialized type tree is too deep")
        table.readCString(MAX_STRING_BYTES) // type
        table.readCString(MAX_STRING_BYTES) // name
        table.readI32() // byte size
        table.readI32() // index
        table.readI32() // flags
        table.readI32() // version
        table.readI32() // meta flag
        val children = table.readI32()
        if (children !in 0..100_000) throw UnityFormatException("Serialized type tree is invalid")
        repeat(children) { skipTypeNodes(table, depth + 1) }
    }

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

    private fun className(classId: Int): String = CLASS_NAMES[classId] ?: "Class$classId"
}
