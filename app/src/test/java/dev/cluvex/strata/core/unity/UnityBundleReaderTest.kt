package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class UnityBundleReaderTest {
    private data class TestNode(val name: String, val offset: Long, val size: Long)
    private data class TestBlock(val uncompressed: Int, val compressed: Int, val codec: Int)
    private data class TestBundleTable(
        val declaredSize: Long,
        val hash: ByteArray,
        val infoCodec: Int,
        val infoAtEnd: Boolean,
        val blocks: List<TestBlock>,
    )

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
    fun repackKeepsRealLz4FixtureCompressedAndStable() {
        // The fixture encoder below emits a genuine LZ4 match and is deliberately independent
        // from the production encoder. The old writer expands this bundle from a few KiB to 700 KiB.
        val contents = ByteArray(700_000) { (it % 251).toByte() }
        val source = writeAlignedBundle(temp.root, "compressed-source.bundle", contents, formatVersion = 8, paddingFlag = true, lz4 = true)
        val first = File(temp.root, "compressed-first.bundle")
        val second = File(temp.root, "compressed-second.bundle")

        UnityBundleReader.open(source, File(temp.root, "cache-1")).use { it.repack(first) }
        assertThat(first.length()).isAtMost((source.length() * 1.15).toLong())
        UnityBundleReader.open(first, File(temp.root, "cache-2")).use { rebuilt ->
            val extracted = File(temp.root, "compressed-result.bin")
            rebuilt.copyEntry(rebuilt.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
            rebuilt.repack(second)
        }
        assertThat(second.length()).isAtMost((first.length() * 1.05).toLong())
    }

    @Test
    fun lz4EncoderCoversEmptyShortRepeatedRandomAndWindowBoundaryInputs() {
        val empty = ByteArray(0)
        assertThat(decodeLz4Fixture(Lz4BlockEncoder.encode(empty), 0)).isEqualTo(empty)

        listOf(byteArrayOf(1), byteArrayOf(1, 2, 3), "short literal".toByteArray()).forEach { input ->
            assertThat(decodeLz4Fixture(Lz4BlockEncoder.encode(input), input.size)).isEqualTo(input)
        }

        val repeated = ByteArray(40_000) { (it % 17).toByte() }
        val repeatedEncoded = Lz4BlockEncoder.encode(repeated)
        assertThat(repeatedEncoded.size).isLessThan(repeated.size / 10)
        assertThat(decodeLz4Fixture(repeatedEncoded, repeated.size)).isEqualTo(repeated)

        listOf(65_535, 65_536, 65_537).forEach { size ->
            val exactBoundary = ByteArray(size) { (it % 193).toByte() }
            assertThat(decodeLz4Fixture(Lz4BlockEncoder.encode(exactBoundary), size)).isEqualTo(exactBoundary)
        }

        var randomState = 0x2468ACE1
        val random = ByteArray(128 * 1024) {
            randomState = randomState xor (randomState shl 13)
            randomState = randomState xor (randomState ushr 17)
            randomState = randomState xor (randomState shl 5)
            randomState.toByte()
        }
        val randomEncoded = Lz4BlockEncoder.encode(random)
        assertThat(decodeLz4Fixture(randomEncoded, random.size)).isEqualTo(random)
        assertThat(randomEncoded.size).isAtLeast(random.size)

        val prefix = random.copyOfRange(0, 65_535)
        val boundary = prefix + prefix.copyOfRange(0, 64) + ByteArray(5) { 0x5A }
        val boundaryEncoded = Lz4BlockEncoder.encode(boundary)
        assertThat(decodeLz4Fixture(boundaryEncoded, boundary.size)).isEqualTo(boundary)
        assertThat(readLz4Distances(boundaryEncoded)).contains(65_535)
    }

    @Test
    fun repackWritesMixedLz4AndRawBlocks() {
        val blockSize = 128 * 1024
        val compressible = ByteArray(blockSize) { (it % 13).toByte() }
        var state = 0x13579BDF
        val random = ByteArray(blockSize) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            state.toByte()
        }
        val contents = compressible + random
        val source = writeBundle(temp.root, "mixed-source.bundle", contents, compression = 0)
        val output = File(temp.root, "mixed-output.bundle")
        UnityBundleReader.open(source, File(temp.root, "cache-mixed")).use { it.repack(output) }

        val table = readBundleTable(output)
        assertThat(table.blocks.map { it.codec }).containsExactly(3, 0).inOrder()
        assertThat(table.blocks.map { it.uncompressed }).containsExactly(blockSize, blockSize).inOrder()
        assertThat(table.infoCodec).isEqualTo(3)
        UnityBundleReader.open(output, File(temp.root, "cache-mixed-output")).use { document ->
            val extracted = File(temp.root, "mixed-extracted.bin")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
        }
    }

    @Test
    fun repackCarriesSourceHashConvention() {
        val contents = ByteArray(180_000) { (it % 79).toByte() }
        val replacementBytes = ByteArray(190_000) { (it % 83).toByte() }
        val replacement = File(temp.root, "hash-replacement.bin").apply { writeBytes(replacementBytes) }

        val zeroSource = writeAlignedBundle(
            temp.root, "zero-hash.bundle", contents, 8, paddingFlag = true, lz4 = true,
            storedHash = ByteArray(16),
        )
        val zeroOutput = File(temp.root, "zero-hash-output.bundle")
        UnityBundleReader.open(zeroSource, File(temp.root, "cache-zero-hash")).use { document ->
            document.repack(zeroOutput, mapOf(document.summary.entries.single().name to replacement))
        }
        assertThat(readBundleTable(zeroOutput).hash).isEqualTo(ByteArray(16))

        val md5Source = writeAlignedBundle(temp.root, "md5-hash.bundle", contents, 8, paddingFlag = true, lz4 = true)
        val md5Output = File(temp.root, "md5-hash-output.bundle")
        UnityBundleReader.open(md5Source, File(temp.root, "cache-md5-hash")).use { document ->
            document.repack(md5Output, mapOf(document.summary.entries.single().name to replacement))
        }
        assertThat(readBundleTable(md5Output).hash)
            .isEqualTo(MessageDigest.getInstance("MD5").digest(replacementBytes))

        val customHash = ByteArray(16) { (it * 7 + 1).toByte() }
        val customSource = writeAlignedBundle(
            temp.root, "custom-hash.bundle", contents, 8, paddingFlag = true, lz4 = true,
            storedHash = customHash,
        )
        val customOutput = File(temp.root, "custom-hash-output.bundle")
        UnityBundleReader.open(customSource, File(temp.root, "cache-custom-hash")).use { it.repack(customOutput) }
        assertThat(readBundleTable(customOutput).hash).isEqualTo(customHash)
    }

    @Test
    fun repackPreservesBytesPastDeclaredBundleSize() {
        val trailer = "TRAILING-SIGNATURE-DATA".toByteArray()
        val source = writeAlignedBundle(
            temp.root,
            "trailing.bundle",
            ByteArray(90_000) { (it % 31).toByte() },
            formatVersion = 8,
            paddingFlag = true,
            lz4 = true,
        )
        source.appendBytes(trailer)
        val output = File(temp.root, "trailing-output.bundle")
        UnityBundleReader.open(source, File(temp.root, "cache-trailing")).use { it.repack(output) }

        assertThat(output.readBytes().takeLast(trailer.size).toByteArray()).isEqualTo(trailer)
        assertThat(readBundleTable(output).declaredSize).isEqualTo(output.length() - trailer.size)
    }

    @Test
    fun closedDocumentFailsExplicitlyInsteadOfReadingDeletedStage() {
        val source = writeBundle(temp.root, "close.bundle", "content".toByteArray(), compression = 0)
        val document = UnityBundleReader.open(source, File(temp.root, "cache-close"))
        val entry = document.summary.entries.single()
        document.close()

        val error = assertThrows(UnityFormatException::class.java) {
            document.copyEntry(entry, File(temp.root, "after-close.bin"))
        }
        assertThat(error).hasMessageThat().contains("closed")
        val inspection = document.inspectText(entry) as UnityTextInspection.Unavailable
        assertThat(inspection.reason).isEqualTo(UnityTextUnavailableReason.DOCUMENT_CLOSED)
    }

    @Test
    fun cancelledRepackLeavesNoPublishedOrTemporaryOutput() {
        val source = writeBundle(temp.root, "cancel-source.bundle", ByteArray(300_000) { it.toByte() }, compression = 0)
        val output = File(temp.root, "cancel-output.bundle")
        UnityBundleReader.open(source, File(temp.root, "cache-cancel")).use { document ->
            Thread.currentThread().interrupt()
            try {
                assertThrows(UnityOperationCancelledException::class.java) { document.repack(output) }
            } finally {
                Thread.interrupted() // clear the JUnit worker's interrupt flag
            }
        }
        assertThat(output.exists()).isFalse()
        assertThat(temp.root.listFiles().orEmpty().filter { it.name.contains("cancel-output") }).isEmpty()
    }

    @Test
    fun closeWaitsForAnInFlightEntryCopy() {
        val stage = temp.newFolder("inflight-stage")
        val stagedData = File(stage, "bundle-data.bin")
        val dataSize = 64L * 1024 * 1024
        RandomAccessFile(stagedData, "rw").use { it.setLength(dataSize) }
        val source = File(temp.root, "inflight-source.bundle").apply { writeText("source") }
        val entry = UnityBundleEntry("large.bin", 0, dataSize, 0)
        val document = UnityBundleDocument(
            source,
            UnityBundleSummary("UnityFS", 8, "5.x.x", "2022.3.62f2", dataSize, dataSize, 1, listOf(entry)),
            stagedData,
        )
        val output = File(temp.root, "inflight-copy.bin")
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                document.copyEntry(entry, output)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        worker.start()
        while (worker.isAlive && temp.root.listFiles().orEmpty().none {
                it.name.startsWith(".${output.name}.") && it.name.endsWith(".extract.tmp")
            }
        ) {
            Thread.yield()
        }
        document.close()
        worker.join(10_000)

        assertThat(worker.isAlive).isFalse()
        assertThat(failure.get()).isNull()
        assertThat(output.length()).isEqualTo(dataSize)
        assertThat(stagedData.exists()).isFalse()
    }

    @Test
    fun readsCompressedBlockInfoAtEndAndRewritesItAtStart() {
        val contents = ByteArray(260_000) { (it % 113).toByte() }
        val source = writeAlignedBundle(
            temp.root,
            "info-at-end.bundle",
            contents,
            formatVersion = 8,
            paddingFlag = false,
            lz4 = true,
            infoAtEnd = true,
        )
        val output = File(temp.root, "info-at-start-output.bundle")
        UnityBundleReader.open(source, File(temp.root, "cache-info-end")).use { document ->
            val extracted = File(temp.root, "info-end-extracted.bin")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
            document.repack(output)
        }
        val outputTable = readBundleTable(output)
        assertThat(outputTable.infoCodec).isEqualTo(3)
        assertThat(outputTable.infoAtEnd).isFalse()
        UnityBundleReader.open(output, File(temp.root, "cache-info-start")).use { document ->
            val extracted = File(temp.root, "info-start-extracted.bin")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
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
    fun growingPersianTextRecomputesFollowingObjectOffsets() {
        val source = writeTypedSerialized(temp.root, "growing.assets", typedObjectOne(), typedObjectOne())
        val output = File(temp.root, "growing-output.assets")
        var oldSecondOffset = 0L
        val longPersian = "این یک متن فارسی بلندتر برای آزمون جابه‌جایی شیء بعدی است. ".repeat(200)
        UnityBundleReader.open(source, File(temp.root, "cache-growing")).use { document ->
            val first = document.summary.entries[0]
            oldSecondOffset = document.summary.entries[1].offset
            val replacement = File(temp.root, "growing-object.bin").apply {
                writeBytes(document.rebuildTexts(first, mapOf("m_Script" to longPersian)))
            }
            document.repack(output, mapOf(first.name to replacement))
        }
        UnityBundleReader.open(output, File(temp.root, "cache-growing-output")).use { document ->
            assertThat(document.summary.entries[1].offset).isGreaterThan(oldSecondOffset)
            assertThat(document.exportTexts(document.summary.entries[0])!!["m_Script"]).isEqualTo(longPersian)
            assertThat(document.exportTexts(document.summary.entries[1])!!["m_Script"]).isEqualTo("print('hi')")
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
    fun rebuiltSerializedFileAlignsEveryObjectStartToEightBytes() {
        val source = writeTypedSerialized(temp.root, "typed-align.assets", typedObjectOne(), typedObjectOne())
        val output = File(temp.root, "typed-align-output.assets")
        UnityBundleReader.open(source, File(temp.root, "cache-align")).use { document ->
            val first = document.summary.entries[0]
            // 41 bytes: an odd-sized replacement. Without start alignment the following
            // object would land on an unaligned offset, unlike a Unity-written file,
            // where every object start is 8-aligned.
            val replacement = File(temp.root, "odd-object.bin").apply {
                writeBytes(typedObjectTwo() + byteArrayOf(0))
            }
            document.repack(output, mapOf(first.name to replacement))
        }
        UnityBundleReader.open(output, File(temp.root, "cache-align-output")).use { rebuilt ->
            assertThat(rebuilt.summary.entries[0].size).isEqualTo(41L)
            assertThat(rebuilt.summary.entries[0].offset % 8L).isEqualTo(0L)
            assertThat(rebuilt.summary.entries[1].offset).isEqualTo(48L)
            assertThat(rebuilt.summary.entries[1].offset % 8L).isEqualTo(0L)
            assertThat(rebuilt.exportTexts(rebuilt.summary.entries[1])!!["m_Script"]).isEqualTo("print('hi')")
        }
    }

    @Test
    fun editsSerializedEntryInsideUnityFsBundle() {
        val serialized = writeTypedSerialized(temp.root, "inner.assets", typedObjectOne())
        val source = writeBundle(temp.root, "outer.bundle", serialized.readBytes(), compression = 0)
        val output = File(temp.root, "outer-edited.bundle")

        UnityBundleReader.open(source, File(temp.root, "cache-outer")).use { document ->
            val entry = document.summary.entries.single()
            val inspection = document.inspectText(entry)
            assertThat(inspection).isInstanceOf(UnityTextInspection.Editable::class.java)
            assertThat((inspection as UnityTextInspection.Editable).info.stringCount).isEqualTo(6)
            val texts = document.exportTexts(entry)!!
            assertThat(texts["files[0].objects[100].m_Items[0].Value"]).isEqualTo("v1-long")
            texts["files[0].objects[100].m_Items[0].Value"] = "متن فارسی"
            val rebuiltEntry = File(temp.root, "rebuilt-inner.assets").apply {
                writeBytes(document.rebuildTexts(entry, texts))
            }
            document.repack(output, mapOf(entry.name to rebuiltEntry))
        }

        UnityBundleReader.open(output, File(temp.root, "cache-reopen")).use { document ->
            val texts = document.exportTexts(document.summary.entries.single())!!
            assertThat(texts["files[0].objects[100].m_Items[0].Value"]).isEqualTo("متن فارسی")
            assertThat(texts["files[0].objects[100].m_Script"]).isEqualTo("print('hi')")
        }
    }

    @Test
    fun walksAndRebuildsConcatenatedSerializedFilesInsideOneEntry() {
        val first = writeTypedSerialized(temp.root, "concat-first.assets", typedObjectOne())
        val second = writeTypedSerialized(temp.root, "concat-second.assets", typedObjectTwo())
        val trailer = "ENTRY-TRAILER".toByteArray()
        val payload = first.readBytes() + second.readBytes() + trailer
        val source = writeBundle(temp.root, "concat.bundle", payload, compression = 0)
        val output = File(temp.root, "concat-edited.bundle")

        UnityBundleReader.open(source, File(temp.root, "cache-concat")).use { document ->
            val entry = document.summary.entries.single()
            assertThat(document.cachedEmbeddedEntryCount).isEqualTo(0)
            val texts = document.exportTexts(entry)!!
            assertThat(document.cachedEmbeddedEntryCount).isEqualTo(1)
            assertThat(texts["files[0].objects[100].m_Name"]).isEqualTo("obj1")
            assertThat(texts["files[1].objects[100].m_Name"]).isEqualTo("renamed")
            texts["files[0].objects[100].m_Script"] = "first-file-grew-before-second-سلام"
            texts["files[1].objects[100].m_Script"] = "second-file-grew-سلام"
            val replacement = File(temp.root, "concat-entry.bin").apply {
                writeBytes(document.rebuildTexts(entry, texts))
            }
            assertThat(replacement.readBytes().takeLast(trailer.size).toByteArray()).isEqualTo(trailer)
            document.repack(output, mapOf(entry.name to replacement))
        }

        UnityBundleReader.open(output, File(temp.root, "cache-concat-output")).use { document ->
            val entry = document.summary.entries.single()
            val texts = document.exportTexts(entry)!!
            assertThat(texts["files[0].objects[100].m_Script"]).isEqualTo("first-file-grew-before-second-سلام")
            assertThat(texts["files[1].objects[100].m_Script"]).isEqualTo("second-file-grew-سلام")
            val extracted = File(temp.root, "concat-extracted.bin")
            document.copyEntry(entry, extracted)
            assertThat(extracted.readBytes().takeLast(trailer.size).toByteArray()).isEqualTo(trailer)
        }
    }

    @Test
    fun embeddedIndexesAreParsedLazilyAndCachedPerEntry() {
        val serialized = writeTypedSerialized(temp.root, "lazy.assets", typedObjectOne()).readBytes()
        val raw = ByteArray(128) { it.toByte() }
        val payload = serialized + raw
        val source = writeBundle(
            temp.root,
            "lazy.bundle",
            payload,
            compression = 0,
            nodes = listOf(
                TestNode("first", 0, serialized.size.toLong()),
                TestNode("second", serialized.size.toLong(), raw.size.toLong()),
            ),
        )
        UnityBundleReader.open(source, File(temp.root, "cache-lazy")).use { document ->
            assertThat(document.cachedEmbeddedEntryCount).isEqualTo(0)
            assertThat(document.inspectText(document.summary.entries[0])).isInstanceOf(UnityTextInspection.Editable::class.java)
            assertThat(document.cachedEmbeddedEntryCount).isEqualTo(1)
            document.inspectText(document.summary.entries[0])
            assertThat(document.cachedEmbeddedEntryCount).isEqualTo(1)
            val rawInspection = document.inspectText(document.summary.entries[1]) as UnityTextInspection.Unavailable
            assertThat(rawInspection.reason).isEqualTo(UnityTextUnavailableReason.NOT_SERIALIZED_FILE)
            assertThat(document.cachedEmbeddedEntryCount).isEqualTo(2)
        }
    }

    @Test
    fun textAssetFallbackEditsUtf8WithoutATypeTree() {
        val objectBytes = ByteArrayOutputStream().also { out ->
            leStr(out, "dialogue")
            val script = "hello from TextAsset".toByteArray(Charsets.UTF_8)
            out.write(le32(script.size.toLong()))
            out.write(script)
        }.toByteArray()
        val source = writeTypedSerialized(
            temp.root,
            "textasset-no-tree.assets",
            objectBytes,
            enableTypeTree = false,
            classId = 49,
        )
        val output = File(temp.root, "textasset-no-tree-output.assets")
        UnityBundleReader.open(source, File(temp.root, "cache-textasset")).use { document ->
            val entry = document.summary.entries.single()
            val texts = document.exportTexts(entry)!!
            assertThat(texts["m_Script"]).isEqualTo("hello from TextAsset")
            val replacement = File(temp.root, "textasset-object.bin").apply {
                writeBytes(document.rebuildTexts(entry, mapOf("m_Script" to "متن بلندتر TextAsset")))
            }
            document.repack(output, mapOf(entry.name to replacement))
        }
        UnityBundleReader.open(output, File(temp.root, "cache-textasset-output")).use { document ->
            assertThat(document.exportTexts(document.summary.entries.single())!!["m_Script"])
                .isEqualTo("متن بلندتر TextAsset")
        }
    }

    @Test
    fun reportsUnsupportedVersionSeparatelyForEmbeddedSerializedEntry() {
        val future = writeSerialized(temp.root, "future-inner.assets", version = 23)
        val source = writeBundle(temp.root, "future-inner.bundle", future.readBytes(), compression = 0)
        UnityBundleReader.open(source, File(temp.root, "cache-future-inner")).use { document ->
            val inspection = document.inspectText(document.summary.entries.single()) as UnityTextInspection.Unavailable
            assertThat(inspection.reason).isEqualTo(UnityTextUnavailableReason.UNSUPPORTED_VERSION)
        }
    }

    @Test
    fun reportsMissingTypeInformationForEmbeddedSerializedEntry() {
        val serialized = writeTypedSerialized(
            temp.root,
            "stripped.assets",
            typedObjectOne(),
            enableTypeTree = false,
        )
        val source = writeBundle(temp.root, "stripped.bundle", serialized.readBytes(), compression = 0)
        UnityBundleReader.open(source, File(temp.root, "cache-stripped")).use { document ->
            val inspection = document.inspectText(document.summary.entries.single())
            assertThat(inspection).isInstanceOf(UnityTextInspection.Unavailable::class.java)
            val unavailable = inspection as UnityTextInspection.Unavailable
            assertThat(unavailable.reason).isEqualTo(UnityTextUnavailableReason.NO_TYPE_INFORMATION)
            assertThat(unavailable.diagnostic).contains("type tree")
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

    private fun readBundleTable(file: File): TestBundleTable {
        RandomAccessFile(file, "r").use { input ->
            fun cString(): String {
                val out = ByteArrayOutputStream()
                while (true) {
                    val value = input.read()
                    check(value >= 0)
                    if (value == 0) return out.toByteArray().toString(Charsets.UTF_8)
                    out.write(value)
                }
            }
            check(cString() == "UnityFS")
            val version = input.readInt()
            cString() // player version
            cString() // engine version
            val declaredSize = input.readLong()
            val compressedInfoSize = input.readInt()
            val uncompressedInfoSize = input.readInt()
            val flags = input.readInt()
            val alignedHeaderEnd = if (version >= 7) (input.filePointer + 15L) and -16L else input.filePointer
            val infoOffset = if (flags and 0x80 != 0) declaredSize - compressedInfoSize else alignedHeaderEnd
            input.seek(infoOffset)
            val payload = ByteArray(compressedInfoSize)
            input.readFully(payload)
            val info = when (flags and 0x3F) {
                0 -> payload
                2, 3 -> decodeLz4Fixture(payload, uncompressedInfoSize)
                else -> error("Unexpected test block-info codec")
            }
            DataInputStream(ByteArrayInputStream(info)).use { table ->
                val hash = ByteArray(16).also(table::readFully)
                val count = table.readInt()
                val blocks = List(count) {
                    TestBlock(table.readInt(), table.readInt(), table.readUnsignedShort() and 0x3F)
                }
                return TestBundleTable(declaredSize, hash, flags and 0x3F, flags and 0x80 != 0, blocks)
            }
        }
    }

    private fun readLz4Distances(input: ByteArray): List<Int> {
        val distances = ArrayList<Int>()
        var source = 0
        fun length(nibble: Int): Int {
            var value = nibble
            if (nibble == 15) {
                var extra: Int
                do {
                    extra = input[source++].toInt() and 0xFF
                    value += extra
                } while (extra == 255)
            }
            return value
        }
        while (source < input.size) {
            val token = input[source++].toInt() and 0xFF
            val literals = length(token ushr 4)
            source += literals
            if (source == input.size) break
            val distance = (input[source].toInt() and 0xFF) or ((input[source + 1].toInt() and 0xFF) shl 8)
            source += 2
            distances += distance
            length(token and 0x0F)
        }
        return distances
    }

    /** Independent test decoder for raw LZ4 blocks written by the production repacker. */
    private fun decodeLz4Fixture(input: ByteArray, expected: Int): ByteArray {
        val output = ByteArray(expected)
        var source = 0
        var target = 0
        fun length(nibble: Int): Int {
            var value = nibble
            if (nibble == 15) {
                var extra: Int
                do {
                    check(source < input.size)
                    extra = input[source++].toInt() and 0xFF
                    value += extra
                } while (extra == 255)
            }
            return value
        }
        while (source < input.size) {
            val token = input[source++].toInt() and 0xFF
            val literals = length(token ushr 4)
            check(source + literals <= input.size && target + literals <= output.size)
            input.copyInto(output, target, source, source + literals)
            source += literals
            target += literals
            if (source == input.size) break
            check(source + 2 <= input.size)
            val distance = (input[source].toInt() and 0xFF) or ((input[source + 1].toInt() and 0xFF) shl 8)
            source += 2
            check(distance in 1..target)
            val match = length(token and 0x0F) + 4
            repeat(match) {
                check(target < output.size)
                output[target] = output[target - distance]
                target++
            }
        }
        check(target == expected)
        return output
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
        storedHash: ByteArray = MessageDigest.getInstance("MD5").digest(contents),
        infoAtEnd: Boolean = false,
    ): File {
        val blockFlags = if (lz4) 3 else 0
        val fixtureBlockSize = 128 * 1024
        val blocks = if (lz4) {
            contents.asList().chunked(fixtureBlockSize).map { chunk ->
                val raw = chunk.toByteArray()
                raw to lz4FixtureBlock(raw)
            }
        } else {
            listOf(contents to contents)
        }
        val blockPayload = ByteArrayOutputStream().also { data -> blocks.forEach { data.write(it.second) } }.toByteArray()
        val info = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                require(storedHash.size == 16)
                out.write(storedHash)
                out.writeInt(blocks.size)
                blocks.forEach { (raw, encoded) ->
                    out.writeInt(raw.size)
                    out.writeInt(encoded.size)
                    out.writeShort(blockFlags)
                }
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
        val infoFlags = 0x40 or (if (infoAtEnd) 0x80 else 0) or (if (paddingFlag) 0x200 else 0) or blockFlags
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
        val dataPadding = if (!infoAtEnd && paddingFlag) (16 - (headerSize + padding + infoPayload.size) % 16) % 16 else 0
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
                if (infoAtEnd) {
                    out.write(blockPayload)
                    out.write(infoPayload)
                } else {
                    out.write(infoPayload)
                    out.write(ByteArray(dataPadding))
                    out.write(blockPayload)
                }
            }
        }
        return output
    }

    /**
     * Test-only LZ4 encoder for periodic fixtures. It writes one literal sequence, one overlapping
     * match and the required final literals. This intentionally does not call production code.
     */
    private fun lz4FixtureBlock(payload: ByteArray): ByteArray {
        val lastLiterals = 5
        val maxPeriod = minOf(1024, payload.size - 12)
        val period = (1..maxPeriod).firstOrNull { distance ->
            (distance until payload.size).all { payload[it] == payload[it - distance] }
        } ?: return lz4LiteralRun(payload)
        val matchLength = payload.size - period - lastLiterals
        if (matchLength < 4 || period > 65_535) return lz4LiteralRun(payload)

        val out = ByteArrayOutputStream()
        val encodedMatchLength = matchLength - 4
        out.write((minOf(period, 15) shl 4) or minOf(encodedMatchLength, 15))
        writeLz4Length(out, period - 15)
        out.write(payload, 0, period)
        out.write(period and 0xFF)
        out.write(period ushr 8)
        writeLz4Length(out, encodedMatchLength - 15)
        out.write(lastLiterals shl 4)
        out.write(payload, payload.size - lastLiterals, lastLiterals)
        return out.toByteArray()
    }

    private fun writeLz4Length(out: ByteArrayOutputStream, extra: Int) {
        if (extra < 0) return
        var left = extra
        while (left >= 255) {
            out.write(255)
            left -= 255
        }
        out.write(left)
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

    private fun writeTypedSerialized(
        root: File,
        name: String,
        vararg objects: ByteArray,
        enableTypeTree: Boolean = true,
        classId: Int = 114,
    ): File {
        val (nodes, strings) = typeTreeBlob()
        val meta = ByteArrayOutputStream()
        meta.write("2021.3.0f1".toByteArray(Charsets.UTF_8)); meta.write(0) // unity version
        meta.write(le32(13))          // build target
        meta.write(if (enableTypeTree) 1 else 0)
        meta.write(le32(1))           // type count
        meta.write(le32(classId.toLong()))
        meta.write(0)                 // is stripped
        meta.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte())) // script type index -1
        if (classId == 114) meta.write(ByteArray(16)) // script id (MonoBehaviour only)
        meta.write(ByteArray(16))     // old type hash
        if (enableTypeTree) {
            meta.write(le32((nodes.size / 24).toLong()))
            meta.write(le32(strings.size.toLong()))
            meta.write(nodes)
            meta.write(strings)
        }
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
