package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest

class UnityLocalizationWorkflowTest {
    @Test
    fun detectorUsesShapeInsteadOfNames() {
        val root = localizationValue(
            containerName = "CompletelyUnrelatedContainer",
            languageField = "x7",
            termsField = "q9",
        )

        val found = UnityLocalizationShape.detect(root, "random.bin", 4, 91, "object:91")
            as LocalizationShapeResult.Found

        assertThat(found.sources).hasSize(1)
        assertThat(found.sources.single().languages.map { it.code }).containsExactly("en", "fr").inOrder()
        assertThat(found.sources.single().terms.map { it.key }).containsExactly("greeting", "farewell").inOrder()
    }

    @Test
    fun detectorFindsTermsNestedUnderSourceStruct() {
        val root = localizationValue(nested = true)

        val source = (UnityLocalizationShape.detect(root, "x", 0, 7, "object:7") as LocalizationShapeResult.Found)
            .sources.single()

        assertThat(source.origin.termsPath).containsExactly("mSource", "mTerms").inOrder()
        assertThat(source.origin.languagesPath).containsExactly("mSource", "mLanguages").inOrder()
        assertThat(source.origin.sourcePathPrefix).containsExactly("mSource")
        assertThat(source.origin.tableId).contains("mSource.mTerms")
    }

    @Test
    fun detectorRejectsDecoysAndMisalignedShapes() {
        val decoy = UVal.Struct(linkedMapOf(
            "first" to UVal.Arr(arrayListOf(UVal.Struct(linkedMapOf(
                "a" to UVal.Str("not-a-code"),
                "b" to UVal.Str("also not a language"),
                "n" to UVal.Num(0, 4),
            )))),
            "second" to UVal.Arr(arrayListOf(UVal.Struct(linkedMapOf(
                "k" to UVal.Str("key"),
                "v" to UVal.Arr(arrayListOf(UVal.Str("value"))),
            )))),
        ))
        assertThat(UnityLocalizationShape.detect(decoy, "x", 0, 1, "o"))
            .isInstanceOf(LocalizationShapeResult.NotFound::class.java)

        val misaligned = localizationValue(languageCodes = listOf("en", "fr"), valuesPerTerm = 3)
        val result = UnityLocalizationShape.detect(misaligned, "x", 0, 1, "o")
        assertThat(result).isInstanceOf(LocalizationShapeResult.Invalid::class.java)
    }

    @Test
    fun termAlignmentInvariantCannotBeConstructedBroken() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalizationTerm.aligned("key", listOf("one"), 2)
        }
    }

    @Test
    fun addLanguageDryRunIsAtomicAcrossEveryTerm() {
        val source = sourceModel()
        val plan = UnityLocalizationOperations.planAddLanguage(source, LocalizationLanguage("Persian", "fa", 0))

        assertThat(plan.canApply).isTrue()
        assertThat(plan.original.languages).hasSize(2)
        assertThat(plan.updated!!.languages.map { it.code }).containsExactly("en", "fr", "fa").inOrder()
        assertThat(plan.updated.terms.all { it.values.size == 3 && it.values.last() == null }).isTrue()
        assertThat(plan.termsUpdated).isEqualTo(source.terms.size)
    }

    @Test
    fun tsvHandlesBomTabsQuotesAndEmbeddedNewlines() {
        val source = sourceModel(singleTerm = true)
        val tsv = "\uFEFFkey\ten\tfr\r\n" +
            "greeting\t\"Hello\tworld\"\t\"Line 1\nLine 2 says \"\"bonjour\"\"\"\r\n"

        val plan = UnityLocalizationTsv.planImport(source, tsv)

        assertThat(plan.issues).isEmpty()
        assertThat(plan.changes).hasSize(2)
        val updated = plan.updated!!
        assertThat(updated.terms.single().values[0]).isEqualTo("Hello\tworld")
        assertThat(updated.terms.single().values[1]).isEqualTo("Line 1\nLine 2 says \"bonjour\"")
        val exported = UnityLocalizationTsv.exportAll(updated)
        assertThat(exported).contains("\"Hello\tworld\"")
        assertThat(exported).contains("\"Line 1\nLine 2 says \"\"bonjour\"\"\"")
    }

    @Test
    fun importReportsUnknownMissingAndEmptyWithoutErasing() {
        val source = sourceModel()
        val originalFrench = source.terms.first().values[1]
        val tsv = "key\ten\tfr\n" +
            "greeting\tHello\t\n" +
            "unknown-key\tnew\tnouveau\n"

        val plan = UnityLocalizationTsv.planImport(source, tsv)

        assertThat(plan.issues.map { it.kind }).containsAtLeast(
            LocalizationImportIssueKind.EMPTY_CELL,
            LocalizationImportIssueKind.UNKNOWN_KEY,
            LocalizationImportIssueKind.MISSING_KEY,
        )
        assertThat(plan.updated!!.terms.first().values[1]).isEqualTo(originalFrench)
        assertThat(plan.changes.none { it.key == "greeting" && it.languageCode == "fr" }).isTrue()

        val quotedEmpty = UnityLocalizationTsv.planImport(
            sourceModel(singleTerm = true),
            "key\ten\tfr\ngreeting\tHello\t\"\"\n",
        )
        assertThat(quotedEmpty.emptyCellCount).isEqualTo(1)
        assertThat(quotedEmpty.updated!!.terms.single().values[1]).isEqualTo("Bonjour")
    }

    @Test
    fun placeholderMultisetMismatchRejectsOnlyInvalidCell() {
        val source = sourceModel(
            singleTerm = true,
            english = "Hello {name}, {count}: {name}",
            french = "Bonjour {name}, {count}: {name}",
        )
        val missing = "key\ten\tfr\n" +
            "greeting\tHello {name}, {count}: {name}\tSalut {name}, {count}\n"

        val rejected = UnityLocalizationTsv.planImport(source, missing)

        assertThat(rejected.placeholderFailureCount).isEqualTo(1)
        assertThat(rejected.changes.none { it.languageCode == "fr" }).isTrue()
        assertThat(rejected.updated!!.terms.single().values[1]).isEqualTo(source.terms.single().values[1])

        val reordered = "key\ten\tfr\n" +
            "greeting\tHello {name}, {count}: {name}\t{count} Salut {name} {name}\n"
        val accepted = UnityLocalizationTsv.planImport(source, reordered)
        assertThat(accepted.placeholderFailureCount).isEqualTo(0)
        assertThat(accepted.updated!!.terms.single().values[1]).isEqualTo("{count} Salut {name} {name}")
    }

    @Test
    fun metadataRecordsExportShapingOptionsAndMismatchIsVisible() {
        val source = sourceModel(singleTerm = true)
        val options = UnityTextTransformOptions(shapeArabic = true, reverseBidi = true)
        val exported = UnityLocalizationTsv.exportAll(source, options, "fr")

        assertThat(exported.lineSequence().first()).contains("source=fr")
        assertThat(exported.lineSequence().first()).contains("shapeArabic=true")
        assertThat(exported.lineSequence().first()).contains("reverseBidi=true")
        val mismatch = UnityLocalizationTsv.planImport(source, exported, UnityTextTransformOptions(), "fr")
        assertThat(mismatch.issues.map { it.kind }).contains(LocalizationImportIssueKind.METADATA_MISMATCH)
        assertThat(mismatch.updated).isNull()

        val malformed = UnityLocalizationTsv.planImport(
            source,
            "#@strata-localization\tversion=bad\nkey\ten\tfr\ngreeting\tHello\tBonjour\n",
        )
        assertThat(malformed.issues.map { it.kind }).contains(LocalizationImportIssueKind.MALFORMED_ROW)
        assertThat(malformed.updated).isNull()
    }

    @Test
    fun sizeGuardAcceptsMeasuredRangeAndRejectsBugSignal() {
        UnityLocalizationOperations.verifyBundleGrowth(1_000, 1_249)
        assertThrows(UnityLocalizationSizeGuardException::class.java) {
            UnityLocalizationOperations.verifyBundleGrowth(1_000, 1_251)
        }
    }

    @Test
    fun discoverySortsBySizeAndHonorsByteBudget() {
        val root = Files.createTempDirectory("localization-discovery").toFile()
        try {
            val large = File(root, "large.bin").apply { writeBytes(ByteArray(20)) }
            val small = File(root, "small.bin").apply { writeBytes(ByteArray(2)) }
            val progress = ArrayList<String?>()

            val report = UnityLocalizationDiscovery.scan(
                requests = listOf(UnityLocalizationScanRequest(large), UnityLocalizationScanRequest(small)),
                cacheRoot = File(root, "cache"),
                budget = UnityLocalizationScanBudget(maxFiles = 10, maxSourceBytes = 3, maxElapsedMillis = 10_000),
                onProgress = { progress += it.currentPath },
            )

            assertThat(report.scannedFiles).isEqualTo(1)
            assertThat(report.stoppedByBudget).isTrue()
            assertThat(report.candidates.single().path).isEqualTo(small.canonicalPath)
            assertThat(progress.first()).isEqualTo(small.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun discoveryCacheKeyIncludesScopeAndReportedFileIdentity() {
        val root = Files.createTempDirectory("localization-cache").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val bundle = writeFixtureBundle(root, "candidate.bin", serialized.readBytes(), "arbitrary")
            val size = bundle.length()
            val modified = bundle.lastModified()
            val firstRequest = UnityLocalizationScanRequest(
                file = bundle,
                identity = "explicit-candidate",
                reportedSize = size,
                reportedLastModified = modified,
                scope = "scope-a",
            )
            UnityLocalizationDiscovery.clearCache()
            val first = UnityLocalizationDiscovery.scan(listOf(firstRequest), File(root, "cache"))
            assertThat(first.candidates.single().tableCount).isEqualTo(1)

            bundle.writeBytes(byteArrayOf(1))
            val cached = UnityLocalizationDiscovery.scan(listOf(firstRequest), File(root, "cache"))
            assertThat(cached.candidates.single().tableCount).isEqualTo(1)

            val otherScope = UnityLocalizationDiscovery.scan(
                listOf(firstRequest.copy(scope = "scope-b")),
                File(root, "cache"),
            )
            assertThat(otherScope.candidates.single().tableCount).isEqualTo(0)
        } finally {
            UnityLocalizationDiscovery.clearCache()
            root.deleteRecursively()
        }
    }

    @Test
    fun discoveryCancellationIsTyped() {
        val root = Files.createTempDirectory("localization-cancel").toFile()
        try {
            File(root, "a.bin").writeBytes(byteArrayOf(1))
            Thread.currentThread().interrupt()
            assertThrows(UnityOperationCancelledException::class.java) {
                UnityLocalizationDiscovery.scan(
                    listOf(UnityLocalizationScanRequest(File(root, "a.bin"))),
                    File(root, "cache"),
                )
            }
        } finally {
            Thread.interrupted()
            root.deleteRecursively()
        }
    }

    @Test
    fun embeddedBundleRoundTripDetectsAddsWritesRepacksAndReopens() {
        val root = Files.createTempDirectory("localization-e2e").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val bundle = writeFixtureBundle(root, "localization.bundle", serialized.readBytes(), "CAB-random-name")
            val originalHash = sha256(bundle)
            val replacement = File(root, "replacement.assets")
            val output = File(root, "output.bundle")

            UnityBundleReader.open(bundle, File(root, "cache-1")).use { document ->
                val entry = document.summary.entries.single()
                val found = document.inspectLocalizations(entry) as UnityLocalizationInspection.Found
                assertThat(found.sources).hasSize(1)
                val updated = found.sources.single().addLanguage(LocalizationLanguage("Persian", "fa", 0))
                document.writeLocalizationReplacement(entry, listOf(updated), replacement)
                document.repack(output, mapOf(entry.name to replacement))
            }

            assertThat(sha256(bundle)).isEqualTo(originalHash)
            UnityBundleReader.open(output, File(root, "cache-2")).use { document ->
                val found = document.inspectLocalizations(document.summary.entries.single())
                    as UnityLocalizationInspection.Found
                val table = found.sources.single()
                assertThat(table.languages.map { it.code }).containsExactly("en", "fr", "fa").inOrder()
                assertThat(table.terms.all { it.values.size == 3 }).isTrue()
                assertThat(table.terms.map { it.values.last() }).containsExactly("", "")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sourceModel(
        singleTerm: Boolean = false,
        english: String = "Hello",
        french: String = "Bonjour",
    ): LocalizationSource {
        val root = localizationValue(
            terms = if (singleTerm) {
                listOf("greeting" to listOf(english, french))
            } else {
                listOf(
                    "greeting" to listOf(english, french),
                    "farewell" to listOf("Goodbye", "Au revoir"),
                )
            },
        )
        return (UnityLocalizationShape.detect(root, "fixture", 0, 100, "object:100") as LocalizationShapeResult.Found)
            .sources.single()
    }

    private fun localizationValue(
        nested: Boolean = true,
        containerName: String = "mSource",
        languageField: String = "mLanguages",
        termsField: String = "mTerms",
        languageCodes: List<String> = listOf("en", "fr"),
        valuesPerTerm: Int = languageCodes.size,
        terms: List<Pair<String, List<String>>> = listOf(
            "greeting" to listOf("Hello", "Bonjour"),
            "farewell" to listOf("Goodbye", "Au revoir"),
        ),
    ): UVal.Struct {
        val languages = UVal.Arr(ArrayList(languageCodes.mapIndexed { index, code ->
            UVal.Struct(linkedMapOf(
                "display" to UVal.Str(if (index == 0) "English" else "Language $index"),
                "locale" to UVal.Str(code),
                "mask" to UVal.Num(index.toLong(), 4),
            ))
        }))
        val rows = UVal.Arr(ArrayList(terms.map { (key, supplied) ->
            val padded = List(valuesPerTerm) { index -> supplied.getOrElse(index) { "value-$index" } }
            UVal.Struct(linkedMapOf(
                "token" to UVal.Str(key),
                "columns" to UVal.Arr(ArrayList(padded.map { UVal.Str(it) })),
            ))
        }))
        val source = UVal.Struct(linkedMapOf(
            languageField to languages,
            "count" to UVal.Num(languageCodes.size.toLong(), 4),
            termsField to rows,
        ))
        return if (nested) {
            UVal.Struct(linkedMapOf("name" to UVal.Str("ArbitraryTable"), containerName to source))
        } else {
            UVal.Struct(linkedMapOf(
                "name" to UVal.Str("ArbitraryTable"),
                languageField to languages,
                "count" to UVal.Num(languageCodes.size.toLong(), 4),
                termsField to rows,
            ))
        }
    }

    // The fixture below is hand-encoded and uses an uncompressed UnityFS block. It deliberately
    // does not call the production serializer or compressor to create its expected input.
    private fun writeLocalizationSerialized(root: File, name: String): File {
        val (nodes, strings) = localizationTypeTreeBlob()
        val objectBytes = localizationObjectBytes()
        val metadata = ByteArrayOutputStream()
        metadata.write("2021.3.0f1".toByteArray(Charsets.UTF_8)); metadata.write(0)
        metadata.write(le32(13))
        metadata.write(1)
        metadata.write(le32(1))
        metadata.write(le32(114))
        metadata.write(0)
        metadata.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        metadata.write(ByteArray(16))
        metadata.write(ByteArray(16))
        metadata.write(le32((nodes.size / 24).toLong()))
        metadata.write(le32(strings.size.toLong()))
        metadata.write(nodes)
        metadata.write(strings)
        metadata.write(le32(1))
        metadata.write(ByteArray((4 - metadata.size() % 4) % 4))
        metadata.write(le64(100))
        metadata.write(le32(0))
        metadata.write(le32(objectBytes.size.toLong()))
        metadata.write(le32(0))

        val meta = metadata.toByteArray()
        val headerSize = 20
        val dataOffset = (headerSize + meta.size + 15) / 16 * 16
        val output = File(root, name)
        FileOutputStream(output).use { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(meta.size)
                out.writeInt(dataOffset + objectBytes.size)
                out.writeInt(17)
                out.writeInt(dataOffset)
                out.writeByte(0)
                out.write(ByteArray(3))
                out.write(meta)
                out.write(ByteArray(dataOffset - headerSize - meta.size))
                out.write(objectBytes)
            }
        }
        return output
    }

    private fun localizationObjectBytes(): ByteArray = ByteArrayOutputStream().also { out ->
        leString(out, "ArbitraryTable")
        out.write(le32(2))
        leString(out, "English")
        leString(out, "en")
        out.write(le32(0))
        leString(out, "French")
        leString(out, "fr")
        out.write(le32(0))
        out.write(le32(2))
        out.write(le32(2))
        leString(out, "greeting")
        out.write(le32(2))
        leString(out, "Hello")
        leString(out, "Bonjour")
        leString(out, "farewell")
        out.write(le32(2))
        leString(out, "Goodbye")
        leString(out, "Au revoir")
    }.toByteArray()

    private fun localizationTypeTreeBlob(): Pair<ByteArray, ByteArray> {
        val stringsOut = ByteArrayOutputStream()
        val offsets = LinkedHashMap<String, Int>()
        fun custom(value: String): Int = offsets.getOrPut(value) {
            val offset = stringsOut.size()
            stringsOut.write(value.toByteArray(Charsets.UTF_8)); stringsOut.write(0)
            offset
        }
        val nodes = ByteArrayOutputStream()
        fun node(level: Int, type: Int, field: Int, flags: Int = 0) {
            nodes.write(byteArrayOf(1, 0))
            nodes.write(level)
            nodes.write(0)
            nodes.write(le32(type.toLong() and 0xFFFFFFFFL))
            nodes.write(le32(field.toLong() and 0xFFFFFFFFL))
            nodes.write(le32(-1L and 0xFFFFFFFFL))
            nodes.write(le32(0))
            nodes.write(le32(flags.toLong()))
        }
        node(0, custom("LocalizationBlob"), common(55))
        node(1, common(840), common(427))
        node(1, custom("SourceShape"), custom("mSource"))
        node(2, common(981), custom("mLanguages"))
        node(3, common(49), common(49), 0x4000)
        node(4, common(222), common(795))
        node(4, custom("LanguageShape"), common(106))
        node(5, common(840), custom("display"))
        node(5, common(840), custom("locale"))
        node(5, common(222), custom("mask"))
        node(2, common(222), custom("count"))
        node(2, common(981), custom("mTerms"))
        node(3, common(49), common(49), 0x4000)
        node(4, common(222), common(795))
        node(4, custom("TermShape"), common(106))
        node(5, common(840), custom("token"))
        node(5, common(981), custom("columns"))
        node(6, common(49), common(49), 0x4000)
        node(7, common(222), common(795))
        node(7, common(840), common(106))
        return nodes.toByteArray() to stringsOut.toByteArray()
    }

    private fun writeFixtureBundle(root: File, name: String, contents: ByteArray, entryName: String): File {
        val info = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MessageDigest.getInstance("MD5").digest(contents))
                out.writeInt(1)
                out.writeInt(contents.size)
                out.writeInt(contents.size)
                out.writeShort(0)
                out.writeInt(1)
                out.writeLong(0)
                out.writeLong(contents.size.toLong())
                out.writeInt(0)
                out.write(entryName.toByteArray(Charsets.UTF_8)); out.writeByte(0)
            }
            bytes.toByteArray()
        }
        val prefix = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("UnityFS".toByteArray(Charsets.UTF_8)); out.writeByte(0)
                out.writeInt(6)
                out.write("2020.3.0f1".toByteArray(Charsets.UTF_8)); out.writeByte(0)
                out.write("2020.3.0f1".toByteArray(Charsets.UTF_8)); out.writeByte(0)
            }
            bytes.toByteArray()
        }
        val headerSize = prefix.size + 8 + 4 + 4 + 4
        val output = File(root, name)
        DataOutputStream(FileOutputStream(output)).use { out ->
            out.write(prefix)
            out.writeLong((headerSize + info.size + contents.size).toLong())
            out.writeInt(info.size)
            out.writeInt(info.size)
            out.writeInt(0x40)
            out.write(info)
            out.write(contents)
        }
        return output
    }

    private fun le32(value: Long): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun le64(value: Long): ByteArray = ByteArray(8) { index ->
        ((value ushr (8 * index)) and 0xFF).toByte()
    }

    private fun common(id: Int): Int = (id.toLong() or 0x80000000L).toInt()

    private fun leString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(le32(bytes.size.toLong()))
        out.write(bytes)
        out.write(ByteArray((4 - bytes.size % 4) % 4))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
