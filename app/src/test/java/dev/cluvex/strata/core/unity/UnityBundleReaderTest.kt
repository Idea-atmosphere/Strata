package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class UnityBundleReaderTest {
    private data class TestNode(val name: String, val offset: Long, val size: Long)

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readsAndRebuildsUncompressedUnityFs() {
        val contents = "small text asset".toByteArray()
        val source = writeBundle(temp.root, "source.bundle", contents, compression = 0)
        val cache = File(temp.root, "cache")

        assertThat(UnityBundleReader.probe(source).kind).isEqualTo(UnityFileKind.UNITY_FS)
        UnityBundleReader.open(source, cache).use { document ->
            assertThat(document.summary.entries.map { it.name }).containsExactly("assets/message.txt")
            val extracted = File(temp.root, "extracted.txt")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)

            val replacement = File(temp.root, "replacement.txt").apply { writeText("updated") }
            val output = File(temp.root, "rebuilt.bundle")
            document.repack(output, mapOf("assets/message.txt" to replacement))
            UnityBundleReader.open(output, cache).use { rebuilt ->
                val result = File(temp.root, "rebuilt-entry.txt")
                rebuilt.copyEntry(rebuilt.summary.entries.single(), result)
                assertThat(result.readText()).isEqualTo("updated")
                assertThat(rebuilt.summary.entries.single().size).isEqualTo(7L)
            }
        }
    }

    @Test
    fun refusesToOverwriteExistingExtractionOrRepackOutput() {
        val source = writeBundle(temp.root, "source.bundle", "content".toByteArray(), compression = 0)
        val extraction = File(temp.root, "existing.txt").apply { writeText("keep me") }
        val output = File(temp.root, "existing.bundle").apply { writeText("keep this too") }
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            assertThrows(java.io.IOException::class.java) {
                document.copyEntry(document.summary.entries.single(), extraction)
            }
            assertThrows(java.io.IOException::class.java) { document.repack(output) }
        }
        assertThat(extraction.readText()).isEqualTo("keep me")
        assertThat(output.readText()).isEqualTo("keep this too")
    }

    @Test
    fun preservesGapsAndFollowingEntriesWhenReplacementGrows() {
        val source = writeBundle(
            temp.root,
            "gapped.bundle",
            "headGAPoldTAIL".toByteArray(),
            compression = 0,
            nodes = listOf(
                TestNode("assets/head.bin", 0L, 4L),
                TestNode("assets/text.txt", 7L, 3L),
                TestNode("assets/tail.bin", 10L, 4L),
            ),
        )
        val replacement = File(temp.root, "larger.txt").apply { writeText("updated") }
        val output = File(temp.root, "gapped-rebuilt.bundle")
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            document.repack(output, mapOf("assets/text.txt" to replacement))
        }
        UnityBundleReader.open(output, File(temp.root, "cache")).use { rebuilt ->
            assertThat(rebuilt.summary.entries[2].offset).isEqualTo(14L)
            val tail = File(temp.root, "tail.bin")
            rebuilt.copyEntry(rebuilt.summary.entries[2], tail)
            assertThat(tail.readText()).isEqualTo("TAIL")
            assertThat(rebuilt.summary.dataSize).isEqualTo(18L)
        }
    }

    @Test
    fun decodesRawLz4LiteralBlock() {
        val contents = "unity".toByteArray()
        val source = writeBundle(temp.root, "lz4.bundle", contents, compression = 2)
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            val extracted = File(temp.root, "lz4-output")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
        }
    }

    @Test
    fun readsLegacyUnityRawBundle() {
        val contents = "legacy unity payload".toByteArray()
        val source = writeLegacy(temp.root, "legacy.raw", contents, web = false)
        assertThat(UnityBundleReader.probe(source).kind).isEqualTo(UnityFileKind.UNITY_RAW)
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            assertThat(document.summary.entries.map { it.name }).containsExactly("assets/data.bin")
            val extracted = File(temp.root, "legacy-out.bin")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
            assertThat(document.repackSupported).isFalse()
            assertThrows(java.io.IOException::class.java) {
                document.repack(File(temp.root, "should-not-exist.bundle"))
            }
        }
    }

    @Test
    fun decompressesLegacyUnityWebBundle() {
        val contents = "compressed legacy payload".toByteArray()
        val source = writeLegacy(temp.root, "legacy.web", contents, web = true)
        assertThat(UnityBundleReader.probe(source).kind).isEqualTo(UnityFileKind.UNITY_WEB)
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            val extracted = File(temp.root, "web-out.bin")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
        }
    }

    @Test
    fun listsSerializedFileObjectsAndExtractsRawBytes() {
        val source = writeSerialized(temp.root, "level.assets", version = 17)
        assertThat(UnityBundleReader.probe(source).kind).isEqualTo(UnityFileKind.SERIALIZED_FILE)
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            assertThat(document.summary.entries.map { it.name })
                .containsExactly("#101 TextAsset", "#102 TextAsset")
                .inOrder()
            val first = File(temp.root, "object-1.bin")
            val second = File(temp.root, "object-2.bin")
            document.copyEntry(document.summary.entries[0], first)
            document.copyEntry(document.summary.entries[1], second)
            assertThat(first.readText()).isEqualTo("hello")
            assertThat(second.readText()).isEqualTo("world")
            assertThat(document.repackSupported).isTrue()
        }
    }

    @Test
    fun refusesSerializedFileVersionOutsideSupportedRange() {
        val source = writeSerialized(temp.root, "future.assets", version = 23)
        assertThat(UnityBundleReader.probe(source).kind).isEqualTo(UnityFileKind.SERIALIZED_FILE)
        val error = assertThrows(UnityFormatException::class.java) {
            UnityBundleReader.open(source, File(temp.root, "cache"))
        }
        assertThat(error).hasMessageThat().contains("Unsupported serialized file version")
    }

    @Test
    fun readsVersion8BundleWithPaddingFlagAndLz4hcBlocks() {
        val contents = ByteArray(70_000) { (it % 251).toByte() }
        val source = writeAlignedBundle(temp.root, "v8.bundle", contents, formatVersion = 8, paddingFlag = true, lz4 = true)
        val probe = UnityBundleReader.probe(source)
        assertThat(probe.kind).isEqualTo(UnityFileKind.UNITY_FS)
        assertThat(probe.fileVersion).isEqualTo(8)
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            assertThat(document.summary.entries.map { it.name }).containsExactly("assets/data.bin")
            val extracted = File(temp.root, "v8-out.bin")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
            assertThat(document.repackSupported).isTrue()
        }
    }

    @Test
    fun repacksVersion8BundleIntoAlignedLayout() {
        val contents = ByteArray(40_000) { (it % 199).toByte() }
        val source = writeAlignedBundle(temp.root, "v8.bundle", contents, formatVersion = 8, paddingFlag = true, lz4 = true)
        val replacement = File(temp.root, "replacement.bin").apply { writeText("updated-v8") }
        val output = File(temp.root, "rebuilt-v8.bundle")
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            document.repack(output, mapOf("assets/data.bin" to replacement))
        }
        UnityBundleReader.open(output, File(temp.root, "cache")).use { rebuilt ->
            assertThat(rebuilt.summary.formatVersion).isEqualTo(8)
            val result = File(temp.root, "rebuilt-entry.bin")
            rebuilt.copyEntry(rebuilt.summary.entries.single(), result)
            assertThat(result.readText()).isEqualTo("updated-v8")
        }
    }

    @Test
    fun readsVersion7BundleWithoutPaddingFlag() {
        val contents = "aligned but uncompressed".toByteArray()
        val source = writeAlignedBundle(temp.root, "v7.bundle", contents, formatVersion = 7, paddingFlag = false, lz4 = false)
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            val extracted = File(temp.root, "v7-out.bin")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
        }
    }

    @Test
    fun rejectsLegacyEngineEncryptionFlag() {
        val source = writeAlignedBundle(
            temp.root,
            "encrypted.bundle",
            "payload".toByteArray(),
            formatVersion = 6,
            paddingFlag = true,
            lz4 = false,
            engineVersion = "2019.4.10f1",
        )
        val error = assertThrows(UnityFormatException::class.java) {
            UnityBundleReader.open(source, File(temp.root, "cache"))
        }
        assertThat(error).hasMessageThat().contains("encrypted")
    }

    @Test
    fun decodesTypedObjectsFromSerializedFile() {
        val source = writeTypedSerialized(temp.root, "typed.assets", typedObjectOne(), typedObjectOne())
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            assertThat(document.supportsTextEditing).isTrue()
            val first = document.summary.entries[0]
            val info = document.textInfo(first)
            assertThat(info).isNotNull()
            assertThat(info!!.stringCount).isEqualTo(6)
            val texts = document.exportTexts(first)!!
            assertThat(texts["m_Name"]).isEqualTo("obj1")
            assertThat(texts["m_Items[0].Key"]).isEqualTo("k1")
            assertThat(texts["m_Items[0].Value"]).isEqualTo("v1-long")
            assertThat(texts["m_Items[1].Key"]).isEqualTo("key2")
            assertThat(texts["m_Items[1].Value"]).isEqualTo("value2")
            assertThat(texts["m_Script"]).isEqualTo("print('hi')")
        }
    }

    @Test
    fun rebuildsSerializedFileWithEditedTexts() {
        val source = writeTypedSerialized(temp.root, "typed.assets", typedObjectOne(), typedObjectOne())
        val output = File(temp.root, "typed-edited.assets")
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            val first = document.summary.entries[0]
            val edited = document.rebuildTexts(
                first,
                mapOf(
                    "m_Items[0].Value" to "TRANSLATED",
                    "m_Script" to "println()",
                ),
            )
            val replacement = File(temp.root, "edited-object.bin").apply { writeBytes(edited) }
            document.repack(output, mapOf(first.name to replacement))
        }
        UnityBundleReader.open(output, File(temp.root, "cache")).use { rebuilt ->
            val first = rebuilt.summary.entries[0]
            val texts = rebuilt.exportTexts(first)!!
            assertThat(texts["m_Items[0].Value"]).isEqualTo("TRANSLATED")
            assertThat(texts["m_Script"]).isEqualTo("println()")
            assertThat(texts["m_Name"]).isEqualTo("obj1")
            assertThat(texts["m_Items[1].Value"]).isEqualTo("value2")
            assertThat(rebuilt.exportTexts(rebuilt.summary.entries[1])!!["m_Name"]).isEqualTo("obj1")
        }
    }

    @Test
    fun rebuildsSerializedFileWithRawReplacement() {
        val source = writeTypedSerialized(temp.root, "typed.assets", typedObjectOne(), typedObjectOne())
        val output = File(temp.root, "typed-raw.assets")
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            val first = document.summary.entries[0]
            val replacement = File(temp.root, "raw-object.bin").apply { writeBytes(typedObjectTwo()) }
            document.repack(output, mapOf(first.name to replacement))
        }
        UnityBundleReader.open(output, File(temp.root, "cache")).use { rebuilt ->
            val texts = rebuilt.exportTexts(rebuilt.summary.entries[0])!!
            assertThat(texts["m_Name"]).isEqualTo("renamed")
            assertThat(texts["m_Script"]).isEqualTo("edited-script")
            assertThat(rebuilt.summary.entries[0].size).isEqualTo(typedObjectTwo().size.toLong())
            assertThat(rebuilt.exportTexts(rebuilt.summary.entries[1])!!["m_Items[0].Key"]).isEqualTo("k1")
        }
    }

    @Test
    fun jsonRoundTripPreservesEscapesAndRejectsUnknownPaths() {
        val map = linkedMapOf(
            "a.b[0]" to "quote \" backslash \\ newline\nline2",
            "uni" to "سلام دنیا \u00e9\u4e2d",
            "empty" to "",
        )
        val parsed = TypeTreeCodec.parseJson(TypeTreeCodec.writeJson(map))
        assertThat(parsed).isEqualTo(map)
        val value = UVal.Struct(
            linkedMapOf<String, UVal>(
                "keep" to UVal.Str("old"),
                "drop" to UVal.Str("other"),
            ),
        )
        assertThat(TypeTreeCodec.applyStrings(value, mapOf("keep" to "new"))).isEqualTo(1)
        assertThat((value.fields["keep"] as UVal.Str).value).isEqualTo("new")
        assertThrows(UnityFormatException::class.java) {
            TypeTreeCodec.applyStrings(value, mapOf("missing.path" to "x"))
        }
    }

    private fun writeBundle(
        root: File,
        name: String,
        contents: ByteArray,
        compression: Int,
        nodes: List<TestNode> = listOf(TestNode("assets/message.txt", 0L, contents.size.toLong())),
    ): File {
        val compressed = if (compression == 2) {
            byteArrayOf((contents.size shl 4).toByte()) + contents
        } else {
            contents
        }
        val info = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MessageDigest.getInstance("MD5").digest(contents))
                out.writeInt(1)
                out.writeInt(contents.size)
                out.writeInt(compressed.size)
                out.writeShort(compression)
                out.writeInt(nodes.size)
                nodes.forEach { node ->
                    out.writeLong(node.offset)
                    out.writeLong(node.size)
                    out.writeInt(0)
                    out.write(node.name.toByteArray(Charsets.UTF_8))
                    out.writeByte(0)
                }
            }
            bytes.toByteArray()
        }
        val headerPrefix = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("UnityFS".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
                out.writeInt(6)
                out.write("2020.3.0f1".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
                out.write("2020.3.0f1".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
            }
            bytes.toByteArray()
        }
        val headerSize = headerPrefix.size + 8 + 4 + 4 + 4
        val output = File(root, name)
        FileOutputStream(output).use { stream ->
            DataOutputStream(stream).use { out ->
                out.write(headerPrefix)
                out.writeLong((headerSize + info.size + compressed.size).toLong())
                out.writeInt(info.size)
                out.writeInt(info.size)
                out.writeInt(0x40)
                out.write(info)
                out.write(compressed)
            }
        }
        return output
    }

    /** Legacy UnityRaw / UnityWeb container (big-endian header) with a single entry. */
    private fun writeLegacy(root: File, name: String, contents: ByteArray, web: Boolean): File {
        val signature = if (web) "UnityWeb" else "UnityRaw"
        fun header(headerSize: Int, compressedSize: Int, uncompressedSize: Int): ByteArray =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.write(signature.toByteArray(Charsets.UTF_8))
                    out.writeByte(0)
                    out.writeInt(6) // format version
                    out.write("5.x.x".toByteArray(Charsets.UTF_8)); out.writeByte(0)
                    out.write("2019.4.0f1".toByteArray(Charsets.UTF_8)); out.writeByte(0)
                    out.write(ByteArray(16)); out.writeInt(0) // hash + crc (version >= 4)
                    out.writeInt(0)                           // minimum streamed bytes
                    out.writeInt(headerSize)
                    out.writeInt(0)                           // levels before streaming
                    out.writeInt(1)                           // level count
                    out.writeInt(compressedSize)
                    out.writeInt(uncompressedSize)
                    out.writeInt(headerSize + compressedSize) // complete file size (version >= 2)
                    out.writeInt(headerSize)                  // file info header size (version >= 3)
                }
                bytes.toByteArray()
            }
        val headerSize = header(0, 0, 0).size // Every field is fixed width.
        val directory = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(1) // node count
                out.write("assets/data.bin".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
                out.writeInt(0) // absolute data offset, patched below
                out.writeInt(contents.size)
            }
            bytes.toByteArray()
        }
        val dataOffset = headerSize + directory.size
        directory[20] = (dataOffset ushr 24).toByte()
        directory[21] = (dataOffset ushr 16).toByte()
        directory[22] = (dataOffset ushr 8).toByte()
        directory[23] = dataOffset.toByte()
        val stream = directory + contents
        val compressed = if (web) lzmaAlone(stream) else stream
        val output = File(root, name)
        FileOutputStream(output).use { it.write(header(headerSize, compressed.size, stream.size) + compressed) }
        return output
    }

    /** LZMA1 stream with the 13-byte "alone" prefix (props + dictionary + size), as Unity emits. */
    private fun lzmaAlone(payload: ByteArray): ByteArray {
        val raw = ByteArrayOutputStream()
        org.tukaani.xz.LZMAOutputStream(raw, org.tukaani.xz.LZMA2Options(), false).use { it.write(payload) }
        val dictionary = 1 shl 23 // 8 MiB
        val header = byteArrayOf(0x5D) + // lc = 3, lp = 0, pb = 2
            byteArrayOf(
                dictionary.toByte(),
                (dictionary ushr 8).toByte(),
                (dictionary ushr 16).toByte(),
                (dictionary ushr 24).toByte(),
            ) +
            ByteArray(8) { index -> ((payload.size.toLong() ushr (8 * index)) and 0xFF).toByte() }
        return header + raw.toByteArray()
    }

    /** Serialized file with a big-endian header, one TextAsset type and two small objects. */
    private fun writeSerialized(root: File, name: String, version: Int): File {
        val metadata = ByteArrayOutputStream()
        fun int(value: Long) = metadata.write(
            byteArrayOf(
                value.toByte(),
                (value ushr 8).toByte(),
                (value ushr 16).toByte(),
                (value ushr 24).toByte(),
            ),
        )
        fun align() = metadata.write(ByteArray((4 - metadata.size() % 4) % 4))
        metadata.write("2021.3.0f1".toByteArray(Charsets.UTF_8)); metadata.write(0) // unity version
        int(13)          // build target (version >= 8)
        metadata.write(1) // enable type tree (version >= 13)
        int(1)           // type count
        int(49)          // class id: TextAsset
        metadata.write(0) // is stripped (version >= 16)
        metadata.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte())) // script type index (version >= 17)
        metadata.write(ByteArray(16)) // old type hash (version >= 13)
        int(1)           // type tree blob: node count (version >= 12)
        int(8)           // string buffer size
        metadata.write(ByteArray(24)) // one blob node (version < 19)
        metadata.write(ByteArray(8))  // type tree strings
        int(2)           // object count
        align()          // object path ids are 4-byte aligned (version >= 14)
        metadata.write(ByteArray(8) { index -> if (index == 0) 101 else 0 }) // path id 101
        int(0); int(5); int(0) // offset, size, type id
        align()
        metadata.write(ByteArray(8) { index -> if (index == 0) 102 else 0 }) // path id 102
        int(8); int(5); int(0)
        val metadataBytes = metadata.toByteArray()
        val headerSize = if (version >= 22) 48 else 20
        val dataOffset = (headerSize + metadataBytes.size + 15) / 16 * 16
        val data = ByteArray(16)
        "hello".toByteArray(Charsets.UTF_8).copyInto(data, 0)
        "world".toByteArray(Charsets.UTF_8).copyInto(data, 8)
        val output = File(root, name)
        FileOutputStream(output).use { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(metadataBytes.size)             // metadata size (header is big-endian)
                out.writeInt(dataOffset + data.size)         // file size
                out.writeInt(version)
                out.writeInt(dataOffset)
                out.writeByte(0)                             // little-endian metadata
                out.write(ByteArray(3))                      // reserved
                if (version >= 22) {
                    out.writeInt(metadataBytes.size)         // 32-bit metadata size repeat
                    out.writeLong((dataOffset + data.size).toLong())
                    out.writeLong(dataOffset.toLong())
                    out.writeLong(0)                         // unknown
                }
                out.write(metadataBytes)
                out.write(ByteArray(dataOffset - headerSize - metadataBytes.size))
                out.write(data)
            }
        }
        return output
    }

    /** Aligned UnityFS container (format version 6..8) mirroring what Unity 2019.4.15+ writes. */
    private fun writeAlignedBundle(
        root: File,
        name: String,
        contents: ByteArray,
        formatVersion: Int,
        paddingFlag: Boolean,
        lz4: Boolean,
        engineVersion: String = "2022.3.62f2",
    ): File {
        val blockFlags = if (lz4) 3 else 0
        val blockPayload = if (lz4) lz4LiteralRun(contents) else contents
        val info = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MessageDigest.getInstance("MD5").digest(contents))
                out.writeInt(1)
                out.writeInt(contents.size)
                out.writeInt(blockPayload.size)
                out.writeShort(blockFlags)
                out.writeInt(1)
                out.writeLong(0)
                out.writeLong(contents.size.toLong())
                out.writeInt(4)
                out.write("assets/data.bin".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
            }
            bytes.toByteArray()
        }
        val infoPayload = if (lz4) lz4LiteralRun(info) else info
        val infoFlags = 0x40 or (if (paddingFlag) 0x200 else 0) or blockFlags
        val headerPrefix = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("UnityFS".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
                out.writeInt(formatVersion)
                out.write("5.x.x".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
                out.write(engineVersion.toByteArray(Charsets.UTF_8))
                out.writeByte(0)
            }
            bytes.toByteArray()
        }
        val headerSize = headerPrefix.size + 8 + 4 + 4 + 4
        val padding = if (formatVersion >= 7) (16 - headerSize % 16) % 16 else 0
        val dataPadding = if (paddingFlag) (16 - (headerSize + padding + infoPayload.size) % 16) % 16 else 0
        val total = headerSize + padding + infoPayload.size + dataPadding + blockPayload.size
        val output = File(root, name)
        FileOutputStream(output).use { stream ->
            DataOutputStream(stream).use { out ->
                out.write(headerPrefix)
                out.writeLong(total.toLong())
                out.writeInt(infoPayload.size)
                out.writeInt(info.size)
                out.writeInt(infoFlags)
                out.write(ByteArray(padding))
                out.write(infoPayload)
                out.write(ByteArray(dataPadding))
                out.write(blockPayload)
            }
        }
        return output
    }

    /** Valid LZ4 block made of a single literal run (no matches), like an incompressible payload. */
    private fun lz4LiteralRun(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val run = payload.size
        out.write(if (run >= 15) 0xF0 else run shl 4)
        var remaining = run - 15
        while (remaining >= 0) {
            out.write(minOf(remaining, 255))
            remaining -= 255
        }
        out.write(payload)
        return out.toByteArray()
    }
    // ---------------------------------------------------------------------------------------
    // Typed serialized-file fixtures. Object bytes are hand-built (independent of the codec)
    // from this type tree:
    //   Base(TestObj): m_Name string; m_Items vector<Item{Key string; Value string}>;
    //                  m_Count int; m_Script string
    // ---------------------------------------------------------------------------------------

    private fun le32(value: Long): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun le64(value: Long): ByteArray = ByteArray(8) { index -> ((value ushr (8 * index)) and 0xFF).toByte() }

    private fun common(id: Int): Int = (id.toLong() or 0x80000000L).toInt()

    private fun leStr(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(le32(bytes.size.toLong()))
        out.write(bytes)
        out.write(ByteArray((4 - bytes.size % 4) % 4))
    }

    private fun typedObjectOne(): ByteArray {
        val out = ByteArrayOutputStream()
        leStr(out, "obj1")            // m_Name
        out.write(le32(2))            // m_Items count
        leStr(out, "k1")              // item 0 key
        leStr(out, "v1-long")         // item 0 value
        leStr(out, "key2")            // item 1 key
        leStr(out, "value2")          // item 1 value
        out.write(le32(7))            // m_Count
        leStr(out, "print('hi')")     // m_Script
        return out.toByteArray()
    }

    private fun typedObjectTwo(): ByteArray {
        val out = ByteArrayOutputStream()
        leStr(out, "renamed")
        out.write(le32(0))
        out.write(le32(9))
        leStr(out, "edited-script")
        return out.toByteArray()
    }

    private fun typeTreeBlob(): Pair<ByteArray, ByteArray> {
        val strings = "TestObj\u0000m_Items\u0000Item\u0000Key\u0000Value\u0000m_Count\u0000".replace("\\u0000", "\u0000").toByteArray(Charsets.UTF_8)
        val testObj = 0
        val mItems = 8
        val item = 16
        val key = 21
        val value = 25
        val mCount = 31
        val nodes = ByteArrayOutputStream()
        fun node(level: Int, type: Int, name: Int, metaFlag: Int) {
            nodes.write(byteArrayOf(1, 0))          // node version u16
            nodes.write(level)                      // level u8
            nodes.write(0)                          // type flags u8
            nodes.write(le32(type.toLong() and 0xFFFFFFFFL))
            nodes.write(le32(name.toLong() and 0xFFFFFFFFL))
            nodes.write(le32(-1L and 0xFFFFFFFFL))  // byte size (variable)
            nodes.write(le32(0))                    // index
            nodes.write(le32(metaFlag.toLong() and 0xFFFFFFFFL))
        }
        node(0, testObj, common(55), 0)             // Base
        node(1, common(840), common(427), 0)        // m_Name: string
        node(1, common(981), mItems, 0)             // m_Items: vector
        node(2, common(49), common(49), 0x4000)     //   Array (align after)
        node(3, common(222), common(795), 0)        //     size: int
        node(3, item, common(106), 0)               //     data: Item
        node(4, common(840), key, 0)                //       Key: string
        node(4, common(840), value, 0)              //       Value: string
        node(1, common(222), mCount, 0)             // m_Count: int
        node(1, common(840), common(490), 0)        // m_Script: string
        return nodes.toByteArray() to strings
    }

    private fun writeTypedSerialized(root: File, name: String, vararg objects: ByteArray): File {
        val (nodes, strings) = typeTreeBlob()
        val meta = ByteArrayOutputStream()
        meta.write("2021.3.0f1".toByteArray(Charsets.UTF_8)); meta.write(0) // unity version
        meta.write(le32(13))          // build target
        meta.write(1)                 // enable type tree
        meta.write(le32(1))           // type count
        meta.write(le32(114))         // class id: MonoBehaviour
        meta.write(0)                 // is stripped
        meta.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte())) // script type index -1
        meta.write(ByteArray(16))     // script id (class 114)
        meta.write(ByteArray(16))     // old type hash
        meta.write(le32((nodes.size / 24).toLong()))
        meta.write(le32(strings.size.toLong()))
        meta.write(nodes)
        meta.write(strings)
        meta.write(le32(objects.size.toLong()))
        var dataOffsetInSection = 0
        val data = ByteArrayOutputStream()
        objects.forEachIndexed { index, bytes ->
            meta.write(ByteArray((4 - meta.size() % 4) % 4)) // align4 before path id
            meta.write(le64((100 + index).toLong()))
            meta.write(le32(dataOffsetInSection.toLong()))
            meta.write(le32(bytes.size.toLong()))
            meta.write(le32(0))       // type id 0
            data.write(bytes)
            dataOffsetInSection += bytes.size
        }
        val metadata = meta.toByteArray()
        val dataBytes = data.toByteArray()
        val headerSize = 20
        val dataStart = (headerSize + metadata.size + 15) / 16 * 16
        val output = File(root, name)
        FileOutputStream(output).use { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(metadata.size)
                out.writeInt(dataStart + dataBytes.size)
                out.writeInt(17)
                out.writeInt(dataStart)
                out.writeByte(0)
                out.write(ByteArray(3))
                out.write(metadata)
                out.write(ByteArray(dataStart - headerSize - metadata.size))
                out.write(dataBytes)
            }
        }
        return output
    }
}
