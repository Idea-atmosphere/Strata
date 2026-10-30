package dev.cluvex.strata.core.unity

import java.io.ByteArrayOutputStream

/**
 * Decoder/encoder for Unity type trees (blob and legacy layouts).
 *
 * Objects of any class can be decoded into [UVal] trees when the serialized file stores type
 * trees, which is the common case for asset bundles. The codec mirrors the reference semantics
 * (UnityPy TypeTreeHelper): primitive widths by type name, aligned strings, Array/vector/map/pair
 * handling, per-node 0x4000 alignment flags and the array-level alignment propagation quirk.
 */
internal class TTNode(
    val version: Int,
    val type: String,
    val name: String,
    val byteSize: Int,
    val metaFlag: Int,
    val children: MutableList<TTNode> = ArrayList(),
) {
    val aligns: Boolean get() = metaFlag and ALIGN_FLAG != 0

    companion object {
        const val ALIGN_FLAG = 0x4000
    }
}

internal class TypeTree(val root: TTNode)

/** Decoded type-tree value. Preserves exact widths so unedited data re-encodes bit-identically. */
internal sealed interface UVal {
    class Struct(val fields: LinkedHashMap<String, UVal>) : UVal
    class Arr(val items: ArrayList<UVal>) : UVal
    class Str(var value: String) : UVal
    class Num(val value: Long, val width: Int) : UVal
    class Flt(val value: Double, val single: Boolean) : UVal
    class Bool(val raw: Int) : UVal
    class Bytes(val data: ByteArray) : UVal
}

internal object TypeTreeCodec {
    private const val MAX_STRING_BYTES = 8 * 1024 * 1024
    private const val MAX_DATA_BYTES = 256 * 1024 * 1024
    private const val MAX_ELEMENTS = 4_000_000

    private val PRIMITIVE_WIDTHS = mapOf(
        "SInt8" to 1, "UInt8" to 1, "char" to 1,
        "short" to 2, "SInt16" to 2, "unsigned short" to 2, "UInt16" to 2,
        "int" to 4, "SInt32" to 4, "unsigned int" to 4, "UInt32" to 4, "Type*" to 4,
        "long long" to 8, "SInt64" to 8, "unsigned long long" to 8, "UInt64" to 8, "FileSize" to 8,
    )

    // ---------------------------------------------------------------- reading

    private class TTReader(val data: ByteArray, val bigEndian: Boolean) {
        var pos = 0

        fun require(n: Int) {
            if (n < 0 || pos > data.size - n) throw UnityFormatException("Type tree read is out of bounds")
        }

        fun readU8(): Int {
            require(1)
            return data[pos++].toInt() and 0xFF
        }

        fun readRaw(width: Int): Long {
            require(width)
            var v = 0L
            if (bigEndian) {
                for (i in 0 until width) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
            } else {
                for (i in width - 1 downTo 0) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
            }
            pos += width
            return v
        }

        fun readI32(): Int = readRaw(4).toInt()

        fun readFloat(): Float = Float.fromBits(readRaw(4).toInt())

        fun readDouble(): Double = Double.fromBits(readRaw(8))

        fun readAlignedString(): String {
            val size = readI32()
            if (size < 0 || size > MAX_STRING_BYTES) throw UnityFormatException("Type tree string is too large")
            val bytes = readBytes(size)
            align4()
            return bytes.toString(Charsets.UTF_8)
        }

        fun readByteArray(): ByteArray {
            val size = readI32()
            if (size < 0 || size > MAX_DATA_BYTES) throw UnityFormatException("Type tree byte array is too large")
            return readBytes(size)
        }

        fun readBytes(n: Int): ByteArray {
            require(n)
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun align4() {
            val rem = pos and 3
            if (rem != 0) {
                require(4 - rem)
                pos += 4 - rem
            }
        }
    }

    fun decode(tree: TypeTree, data: ByteArray, bigEndian: Boolean): UVal {
        val reader = TTReader(data, bigEndian)
        return readValue(tree.root, reader)
    }

    /** Bytes consumed by the last [decode] call's reader; used for size validation. */
    fun decodeChecked(tree: TypeTree, data: ByteArray, bigEndian: Boolean): Pair<UVal, Int> {
        val reader = TTReader(data, bigEndian)
        val value = readValue(tree.root, reader)
        return value to reader.pos
    }

    private fun readValue(node: TTNode, reader: TTReader): UVal {
        var align = node.aligns
        val value: UVal
        val width = PRIMITIVE_WIDTHS[node.type]
        when {
            node.type == "string" -> value = UVal.Str(reader.readAlignedString())
            node.type == "TypelessData" -> value = UVal.Bytes(reader.readByteArray())
            node.type == "bool" -> value = UVal.Bool(reader.readU8())
            node.type == "float" -> value = UVal.Flt(reader.readFloat().toDouble(), true)
            node.type == "double" -> value = UVal.Flt(reader.readDouble(), false)
            width != null -> value = UVal.Num(reader.readRaw(width), width)
            node.type == "pair" -> {
                if (node.children.size < 2) throw UnityFormatException("Type tree pair node is malformed")
                val fields = LinkedHashMap<String, UVal>(2)
                fields.put(unique(fields, node.children[0].name), readValue(node.children[0], reader))
                fields.put(unique(fields, node.children[1].name), readValue(node.children[1], reader))
                value = UVal.Struct(fields)
            }
            node.children.isNotEmpty() && node.children[0].type == "Array" -> {
                val array = node.children[0]
                if (array.aligns) align = true
                val size = reader.readI32()
                val subtype = array.children.getOrElse(1) { throw UnityFormatException("Type tree array is malformed") }
                // Byte vectors (notably Font.m_FontData) can legitimately exceed the
                // element-count guard: a 22 MB font is 22M one-byte elements. Read
                // one-byte elements in bulk as bytes instead of one UVal per byte,
                // bounded by the byte-array limit rather than the element limit.
                value = if (PRIMITIVE_WIDTHS[subtype.type] == 1 && !subtype.aligns) {
                    if (size < 0 || size > MAX_DATA_BYTES) throw UnityFormatException("Type tree byte array is too large")
                    UVal.Bytes(reader.readBytes(size))
                } else {
                    if (size < 0 || size > MAX_ELEMENTS) throw UnityFormatException("Type tree array is too large")
                    if (subtype.aligns) {
                        readValueArray(subtype, size, reader)
                    } else {
                        val items = ArrayList<UVal>(size)
                        repeat(size) { items += readValue(subtype, reader) }
                        UVal.Arr(items)
                    }
                }
            }
            else -> {
                val fields = LinkedHashMap<String, UVal>(node.children.size)
                node.children.forEach { child ->
                    fields.put(unique(fields, child.name), readValue(child, reader))
                }
                value = UVal.Struct(fields)
            }
        }
        if (align) reader.align4()
        return value
    }

    /** Mirrors UnityPy read_value_array: elements are read without per-element self alignment. */
    private fun readValueArray(node: TTNode, size: Int, reader: TTReader): UVal.Arr {
        var align = node.aligns
        val items = ArrayList<UVal>(size)
        val width = PRIMITIVE_WIDTHS[node.type]
        when {
            node.type == "string" -> repeat(size) { items += UVal.Str(reader.readAlignedString()) }
            node.type == "TypelessData" -> repeat(size) { items += UVal.Bytes(reader.readByteArray()) }
            node.type == "bool" -> repeat(size) { items += UVal.Bool(reader.readU8()) }
            node.type == "float" -> repeat(size) { items += UVal.Flt(reader.readFloat().toDouble(), true) }
            node.type == "double" -> repeat(size) { items += UVal.Flt(reader.readDouble(), false) }
            width != null -> repeat(size) { items += UVal.Num(reader.readRaw(width), width) }
            node.type == "pair" -> repeat(size) {
                if (node.children.size < 2) throw UnityFormatException("Type tree pair node is malformed")
                val fields = LinkedHashMap<String, UVal>(2)
                fields.put(unique(fields, node.children[0].name), readValue(node.children[0], reader))
                fields.put(unique(fields, node.children[1].name), readValue(node.children[1], reader))
                items += UVal.Struct(fields)
            }
            node.children.isNotEmpty() && node.children[0].type == "Array" -> {
                val array = node.children[0]
                if (array.aligns) align = true
                val subtype = array.children.getOrElse(1) { throw UnityFormatException("Type tree array is malformed") }
                repeat(size) {
                    val inner = reader.readI32()
                    if (PRIMITIVE_WIDTHS[subtype.type] == 1 && !subtype.aligns) {
                        if (inner < 0 || inner > MAX_DATA_BYTES) throw UnityFormatException("Type tree byte array is too large")
                        items += UVal.Bytes(reader.readBytes(inner))
                    } else {
                        if (inner < 0 || inner > MAX_ELEMENTS) throw UnityFormatException("Type tree array is too large")
                        items += if (subtype.aligns) {
                            readValueArray(subtype, inner, reader)
                        } else {
                            val innerItems = ArrayList<UVal>(inner)
                            repeat(inner) { innerItems += readValue(subtype, reader) }
                            UVal.Arr(innerItems)
                        }
                    }
                }
            }
            else -> repeat(size) {
                val fields = LinkedHashMap<String, UVal>(node.children.size)
                node.children.forEach { child ->
                    fields.put(unique(fields, child.name), readValue(child, reader))
                }
                items += UVal.Struct(fields)
            }
        }
        if (align) reader.align4()
        return UVal.Arr(items)
    }

    private fun unique(fields: LinkedHashMap<String, UVal>, name: String): String {
        if (!fields.containsKey(name)) return name
        var index = 2
        while (fields.containsKey("$name#$index")) index++
        return "$name#$index"
    }

    // ---------------------------------------------------------------- writing

    private class TTWriter(val bigEndian: Boolean) {
        val out = ByteArrayOutputStream()

        fun writeRaw(value: Long, width: Int) {
            if (bigEndian) {
                for (i in width - 1 downTo 0) out.write(((value ushr (8 * i)) and 0xFF).toInt())
            } else {
                for (i in 0 until width) out.write(((value ushr (8 * i)) and 0xFF).toInt())
            }
        }

        fun writeAlignedString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeRaw(bytes.size.toLong(), 4)
            out.write(bytes)
            align4()
        }

        fun align4() {
            val rem = out.size() and 3
            if (rem != 0) out.write(ByteArray(4 - rem))
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    fun encode(tree: TypeTree, value: UVal, bigEndian: Boolean): ByteArray {
        val writer = TTWriter(bigEndian)
        writeValue(tree.root, value, writer)
        return writer.toByteArray()
    }

    private fun writeValue(node: TTNode, value: UVal, writer: TTWriter) {
        var align = node.aligns
        val width = PRIMITIVE_WIDTHS[node.type]
        when {
            node.type == "string" -> writer.writeAlignedString((value as UVal.Str).value)
            node.type == "TypelessData" -> {
                val bytes = (value as UVal.Bytes).data
                writer.writeRaw(bytes.size.toLong(), 4)
                writer.out.write(bytes)
            }
            node.type == "bool" -> writer.writeRaw((value as UVal.Bool).raw.toLong(), 1)
            node.type == "float" -> writer.writeRaw((value as UVal.Flt).value.toFloat().toRawBits().toLong(), 4)
            node.type == "double" -> writer.writeRaw((value as UVal.Flt).value.toRawBits(), 8)
            width != null -> writer.writeRaw((value as UVal.Num).value, width)
            node.children.isNotEmpty() && node.children[0].type == "Array" -> {
                val array = node.children[0]
                if (array.aligns) align = true
                val subtype = array.children.getOrElse(1) { throw UnityFormatException("Type tree array is malformed") }
                when (value) {
                    is UVal.Bytes -> {
                        writer.writeRaw(value.data.size.toLong(), 4)
                        writer.out.write(value.data)
                    }
                    is UVal.Arr -> {
                        val items = value.items
                        writer.writeRaw(items.size.toLong(), 4)
                        if (subtype.aligns) {
                            writeValueArray(subtype, items, writer)
                        } else {
                            items.forEach { writeValue(subtype, it, writer) }
                        }
                    }
                    else -> throw UnityFormatException("Type tree array value has unexpected type ${value::class.simpleName}")
                }
            }
            else -> {
                val fields = (value as UVal.Struct).fields
                node.children.forEach { child ->
                    val key = fields.keys.firstOrNull { it == child.name || it.startsWith(child.name + "#") }
                        ?: throw UnityFormatException("Decoded value is missing field ${child.name}")
                    writeValue(child, fields.getValue(key), writer)
                }
            }
        }
        if (align) writer.align4()
    }

    /** Mirrors read_value_array on the write side: single alignment after all elements. */
    private fun writeValueArray(node: TTNode, items: List<UVal>, writer: TTWriter) {
        var align = node.aligns
        val width = PRIMITIVE_WIDTHS[node.type]
        when {
            node.type == "string" -> items.forEach { writer.writeAlignedString((it as UVal.Str).value) }
            node.type == "TypelessData" -> items.forEach {
                val bytes = (it as UVal.Bytes).data
                writer.writeRaw(bytes.size.toLong(), 4)
                writer.out.write(bytes)
            }
            node.type == "bool" -> items.forEach { writer.writeRaw((it as UVal.Bool).raw.toLong(), 1) }
            node.type == "float" -> items.forEach { writer.writeRaw((it as UVal.Flt).value.toFloat().toRawBits().toLong(), 4) }
            node.type == "double" -> items.forEach { writer.writeRaw((it as UVal.Flt).value.toRawBits(), 8) }
            width != null -> items.forEach { writer.writeRaw((it as UVal.Num).value, width) }
            node.type == "pair" -> items.forEach { item ->
                val fields = (item as UVal.Struct).fields
                val values = fields.values.toList()
                writeValue(node.children[0], values[0], writer)
                writeValue(node.children[1], values[1], writer)
            }
            node.children.isNotEmpty() && node.children[0].type == "Array" -> {
                val array = node.children[0]
                if (array.aligns) align = true
                val subtype = array.children.getOrElse(1) { throw UnityFormatException("Type tree array is malformed") }
                items.forEach { item ->
                    when (item) {
                        is UVal.Bytes -> {
                            writer.writeRaw(item.data.size.toLong(), 4)
                            writer.out.write(item.data)
                        }
                        is UVal.Arr -> {
                            val inner = item.items
                            writer.writeRaw(inner.size.toLong(), 4)
                            if (subtype.aligns) {
                                writeValueArray(subtype, inner, writer)
                            } else {
                                inner.forEach { writeValue(subtype, it, writer) }
                            }
                        }
                        else -> throw UnityFormatException("Type tree array item has unexpected type ${item::class.simpleName}")
                    }
                }
            }
            else -> items.forEach { item ->
                val fields = (item as UVal.Struct).fields
                node.children.forEach { child ->
                    val key = fields.keys.firstOrNull { it == child.name || it.startsWith(child.name + "#") }
                        ?: throw UnityFormatException("Decoded value is missing field ${child.name}")
                    writeValue(child, fields.getValue(key), writer)
                }
            }
        }
        if (align) writer.align4()
    }

    // ---------------------------------------------------------------- strings

    /** Flat "path -> string value" map of every string leaf, in decode order. */
    fun exportStrings(value: UVal): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        collectStrings(value, "", out)
        return out
    }

    private fun collectStrings(value: UVal, path: String, out: LinkedHashMap<String, String>) {
        when (value) {
            is UVal.Str -> out[path] = value.value
            is UVal.Struct -> value.fields.forEach { (key, child) ->
                collectStrings(child, if (path.isEmpty()) key else "$path.$key", out)
            }
            is UVal.Arr -> value.items.forEachIndexed { index, child ->
                collectStrings(child, "$path[$index]", out)
            }
            else -> Unit
        }
    }

    /** Replaces string leaves listed in [edits]; unknown paths are rejected. Returns edit count. */
    fun applyStrings(value: UVal, edits: Map<String, String>): Int {
        if (edits.isEmpty()) return 0
        val remaining = LinkedHashMap(edits)
        applyStringsInto(value, "", remaining)
        if (remaining.isNotEmpty()) {
            throw UnityFormatException("Edited JSON contains unknown text path: ${remaining.keys.first()}")
        }
        return edits.size
    }

    private fun applyStringsInto(value: UVal, path: String, remaining: MutableMap<String, String>) {
        when (value) {
            is UVal.Str -> {
                if (remaining.containsKey(path)) value.value = remaining.remove(path)!!
            }
            is UVal.Struct -> value.fields.forEach { (key, child) ->
                applyStringsInto(child, if (path.isEmpty()) key else "$path.$key", remaining)
            }
            is UVal.Arr -> value.items.forEachIndexed { index, child ->
                applyStringsInto(child, "$path[$index]", remaining)
            }
            else -> Unit
        }
    }

    /** True when the value holds meaningful text (for "game text" detection). */
    fun textStats(value: UVal): Triple<Int, Long, String> {
        var count = 0
        var bytes = 0L
        var sample = ""
        fun walk(v: UVal) {
            when (v) {
                is UVal.Str -> {
                    if (v.value.isNotEmpty()) {
                        count++
                        bytes += v.value.toByteArray(Charsets.UTF_8).size
                        if (sample.length < 120 && v.value.length > sample.length) sample = v.value.take(120)
                    }
                }
                is UVal.Struct -> v.fields.values.forEach { walk(it) }
                is UVal.Arr -> v.items.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(value)
        return Triple(count, bytes, sample)
    }

    // ---------------------------------------------------------------- json

    /** Writes a flat string map as a JSON object (paths as keys). */
    fun writeJson(map: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("{\n")
        var first = true
        map.forEach { (key, value) ->
            if (!first) sb.append(",\n")
            first = false
            writeJsonString(sb, key)
            sb.append(": ")
            writeJsonString(sb, value)
        }
        sb.append("\n}\n")
        return sb.toString()
    }

    private fun writeJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        value.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Parses a flat JSON object of string -> string. Rejects other shapes with a clear error. */
    fun parseJson(text: String): LinkedHashMap<String, String> {
        val parser = JsonParser(text)
        val map = parser.parseObject()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw UnityFormatException("Edited JSON has trailing content")
        return map
    }

    private class JsonParser(val text: String) {
        var pos = 0

        fun atEnd() = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun expect(c: Char) {
            skipWhitespace()
            if (atEnd() || text[pos] != c) throw UnityFormatException("Edited JSON is malformed at offset $pos")
            pos++
        }

        fun parseObject(): LinkedHashMap<String, String> {
            val map = LinkedHashMap<String, String>()
            expect('{')
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                val key = parseString()
                expect(':')
                val value = parseString()
                map[key] = value
                skipWhitespace()
                when {
                    atEnd() -> throw UnityFormatException("Edited JSON ends inside the object")
                    text[pos] == ',' -> { pos++; skipWhitespace() }
                    text[pos] == '}' -> { pos++; return map }
                    else -> throw UnityFormatException("Edited JSON is malformed at offset $pos")
                }
            }
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw UnityFormatException("Edited JSON ends inside a string")
                val c = text[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw UnityFormatException("Edited JSON ends inside an escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > text.length) throw UnityFormatException("Edited JSON has a bad \\u escape")
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw UnityFormatException("Edited JSON has an unknown escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
    }

    // ---------------------------------------------------------------- trees

    class BlobNode(
        val version: Int,
        val level: Int,
        val typeOffset: Int,
        val nameOffset: Int,
        val byteSize: Int,
        val metaFlag: Int,
    )

    /** Builds a [TypeTree] from blob nodes (flat level list) plus the tree string buffer. */
    fun treeFromBlob(nodes: List<BlobNode>, strings: ByteArray): TypeTree {
        if (nodes.isEmpty()) throw UnityFormatException("Serialized type tree is empty")
        fun resolve(offset: Int): String {
            return if (offset >= 0) {
                if (offset >= strings.size) throw UnityFormatException("Serialized type tree string is out of bounds")
                var end = offset
                while (end < strings.size && strings[end] != 0.toByte()) end++
                strings.copyOfRange(offset, end).toString(Charsets.UTF_8)
            } else {
                COMMON_STRINGS[offset and 0x7FFFFFFF] ?: (offset and 0x7FFFFFFF).toString()
            }
        }
        val roots = ArrayList<TTNode>()
        val stack = ArrayList<TTNode>()
        nodes.forEach { blob ->
            val node = TTNode(blob.version, resolve(blob.typeOffset), resolve(blob.nameOffset), blob.byteSize, blob.metaFlag)
            while (stack.size > blob.level) stack.removeAt(stack.size - 1)
            if (stack.isEmpty()) roots += node else stack[stack.size - 1].children += node
            stack += node
        }
        if (roots.size != 1) throw UnityFormatException("Serialized type tree has multiple roots")
        return TypeTree(roots[0])
    }

    /** Builds a [TypeTree] from the legacy recursive node layout (version 10/11 files). */
    fun treeFromLegacy(table: UnityBundleReader.Cursor, depth: Int = 0): TTNode {
        if (depth > 96) throw UnityFormatException("Serialized type tree is too deep")
        val type = table.readCString(MAX_STRING_BYTES)
        val name = table.readCString(MAX_STRING_BYTES)
        val byteSize = table.readI32()
        table.readI32() // index
        val flags = table.readI32()
        val version = table.readI32()
        val metaFlag = table.readI32()
        val node = TTNode(version, type, name, byteSize, metaFlag)
        val children = table.readI32()
        if (children !in 0..100_000) throw UnityFormatException("Serialized type tree is invalid")
        repeat(children) { node.children += treeFromLegacy(table, depth + 1) }
        return node
    }

    /** TextAsset fallback decoding (used when no type tree is available). */
    fun decodeTextAsset(data: ByteArray, bigEndian: Boolean): Pair<String, ByteArray> {
        try {
            val reader = TTReader(data, bigEndian)
            val name = reader.readAlignedString()
            val script = reader.readByteArray()
            if (reader.pos != data.size) throw UnityFormatException("TextAsset has unexpected trailing bytes")
            return name to script
        } catch (e: UnityFormatException) {
            throw e
        } catch (e: Exception) {
            throw UnityFormatException("TextAsset fallback decode failed", e)
        }
    }

    /** TextAsset fallback encoding, symmetric to [decodeTextAsset]. */
    fun encodeTextAsset(name: String, script: ByteArray, bigEndian: Boolean): ByteArray {
        val writer = TTWriter(bigEndian)
        writer.writeAlignedString(name)
        writer.writeRaw(script.size.toLong(), 4)
        writer.out.write(script)
        return writer.toByteArray()
    }

    internal val COMMON_STRINGS: Map<Int, String> = mapOf(
        0 to "AABB",
        5 to "AnimationClip",
        19 to "AnimationCurve",
        34 to "AnimationState",
        49 to "Array",
        55 to "Base",
        60 to "BitField",
        69 to "bitset",
        76 to "bool",
        81 to "char",
        86 to "ColorRGBA",
        96 to "Component",
        106 to "data",
        111 to "deque",
        117 to "double",
        124 to "dynamic_array",
        138 to "FastPropertyName",
        155 to "first",
        161 to "float",
        167 to "Font",
        172 to "GameObject",
        183 to "Generic Mono",
        196 to "GradientNEW",
        208 to "GUID",
        213 to "GUIStyle",
        222 to "int",
        226 to "list",
        231 to "long long",
        241 to "map",
        245 to "Matrix4x4f",
        256 to "MdFour",
        263 to "MonoBehaviour",
        277 to "MonoScript",
        288 to "m_ByteSize",
        299 to "m_Curve",
        307 to "m_EditorClassIdentifier",
        331 to "m_EditorHideFlags",
        349 to "m_Enabled",
        359 to "m_ExtensionPtr",
        374 to "m_GameObject",
        387 to "m_Index",
        395 to "m_IsArray",
        405 to "m_IsStatic",
        416 to "m_MetaFlag",
        427 to "m_Name",
        434 to "m_ObjectHideFlags",
        452 to "m_PrefabInternal",
        469 to "m_PrefabParentObject",
        490 to "m_Script",
        499 to "m_StaticEditorFlags",
        519 to "m_Type",
        526 to "m_Version",
        536 to "Object",
        543 to "pair",
        548 to "PPtr<Component>",
        564 to "PPtr<GameObject>",
        581 to "PPtr<Material>",
        596 to "PPtr<MonoBehaviour>",
        616 to "PPtr<MonoScript>",
        633 to "PPtr<Object>",
        646 to "PPtr<Prefab>",
        659 to "PPtr<Sprite>",
        672 to "PPtr<TextAsset>",
        688 to "PPtr<Texture>",
        702 to "PPtr<Texture2D>",
        718 to "PPtr<Transform>",
        734 to "Prefab",
        741 to "Quaternionf",
        753 to "Rectf",
        759 to "RectInt",
        767 to "RectOffset",
        778 to "second",
        785 to "set",
        789 to "short",
        795 to "size",
        800 to "SInt16",
        807 to "SInt32",
        814 to "SInt64",
        821 to "SInt8",
        827 to "staticvector",
        840 to "string",
        847 to "TextAsset",
        857 to "TextMesh",
        866 to "Texture",
        874 to "Texture2D",
        884 to "Transform",
        894 to "TypelessData",
        907 to "UInt16",
        914 to "UInt32",
        921 to "UInt64",
        928 to "UInt8",
        934 to "unsigned int",
        947 to "unsigned long long",
        966 to "unsigned short",
        981 to "vector",
        988 to "Vector2f",
        997 to "Vector3f",
        1006 to "Vector4f",
        1015 to "m_ScriptingClassIdentifier",
        1042 to "Gradient",
        1051 to "Type*",
        1057 to "int2_storage",
        1070 to "int3_storage",
        1083 to "BoundsInt",
        1093 to "m_CorrespondingSourceObject",
        1121 to "m_PrefabInstance",
        1138 to "m_PrefabAsset",
        1152 to "FileSize",
        1161 to "Hash128",
        1169 to "RenderingLayerMask",
        1188 to "fixed_array",
        1200 to "EntityId",
        1209 to "LoadableObjectId",
        1226 to "LoadableSceneId",
    )
}
