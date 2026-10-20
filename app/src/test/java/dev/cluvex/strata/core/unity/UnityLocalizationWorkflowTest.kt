package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import dev.cluvex.strata.core.archive.ArchiveParent
import dev.cluvex.strata.feature.unity.stagePickedApk
import dev.cluvex.strata.feature.unity.writeLocalizationTsvDraft
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
    fun streamedTsvMatchesTheStringApiAndPublishesAnAtomicEditorDraft() {
        val source = sourceModel()
        val options = UnityTextTransformOptions(shapeArabic = true, reverseBidi = true)
        val streamedAll = StringBuilder()
        val streamedFrench = StringBuilder()

        UnityLocalizationTsv.exportAllTo(streamedAll, source, options, "en")
        UnityLocalizationTsv.exportLanguageTo(streamedFrench, source, "fr", options, "en")

        assertThat(streamedAll.toString()).isEqualTo(UnityLocalizationTsv.exportAll(source, options, "en"))
        assertThat(streamedFrench.toString()).isEqualTo(
            UnityLocalizationTsv.exportLanguage(source, "fr", options, "en"),
        )

        val root = Files.createTempDirectory("localization-tsv-draft").toFile()
        try {
            val draft = writeLocalizationTsvDraft(root, source, "fr", options, "en")
            assertThat(draft.isFile).isTrue()
            assertThat(draft.extension).isEqualTo("tsv")
            assertThat(draft.readText(Charsets.UTF_8)).isEqualTo(streamedFrench.toString())
            assertThat(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") }).isFalse()
            assertThrows(IllegalArgumentException::class.java) {
                writeLocalizationTsvDraft(root, source, "zz", options, "en")
            }
            assertThat(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") }).isFalse()
        } finally {
            root.deleteRecursively()
        }
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
            assertThat(report.budgetStopReason).isEqualTo(UnityLocalizationBudgetStopReason.SOURCE_BYTES)
            assertThat(report.candidates.single().path).isEqualTo(small.canonicalPath)
            assertThat(progress.first()).isEqualTo(small.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun discoveryCacheKeyIncludesScopeAndNeverMasksChangedSourceMetadata() {
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
            val changed = UnityLocalizationDiscovery.scan(listOf(firstRequest), File(root, "cache"))
            assertThat(changed.candidates.single().reason)
                .isEqualTo(UnityLocalizationCandidateReason.SOURCE_METADATA_MISMATCH)

            writeFixtureBundle(root, "candidate.bin", serialized.readBytes(), "arbitrary")
            assertThat(bundle.setLastModified(modified)).isTrue()
            val otherScope = UnityLocalizationDiscovery.scan(
                listOf(firstRequest.copy(scope = "scope-b")),
                File(root, "cache"),
            )
            assertThat(otherScope.candidates.single().tableCount).isEqualTo(1)
        } finally {
            UnityLocalizationDiscovery.clearCache()
            root.deleteRecursively()
        }
    }

    @Test
    fun discoveryResultsPutLocalizationTablesBeforeSmallerNonCandidates() {
        val root = Files.createTempDirectory("localization-result-ranking").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val candidate = writeFixtureBundle(root, "candidate.bin", serialized.readBytes(), "opaque")
            val decoy = File(root, "tiny.bin").apply { writeText("x") }
            val report = UnityLocalizationDiscovery.scan(
                listOf(UnityLocalizationScanRequest(decoy), UnityLocalizationScanRequest(candidate)),
                File(root, "cache"),
                UnityLocalizationScanBudget(maxFiles = 4, maxSourceBytes = 10_000_000, maxElapsedMillis = 10_000),
            )
            assertThat(report.candidates).hasSize(2)
            assertThat(report.candidates.first().hasLocalizationTables).isTrue()
            assertThat(report.candidates.last().hasLocalizationTables).isFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun discoveryReportsEveryInvalidInputWithTypedReason() {
        val root = Files.createTempDirectory("localization-invalid-inputs").toFile()
        try {
            val missing = File(root, "missing.bundle")
            val directory = File(root, "directory.bundle").apply { mkdirs() }
            val report = UnityLocalizationDiscovery.scan(
                listOf(
                    UnityLocalizationScanRequest(missing, reportedSize = 0L, reportedLastModified = 0L),
                    UnityLocalizationScanRequest(
                        directory,
                        reportedSize = 0L,
                        reportedLastModified = directory.lastModified(),
                    ),
                ),
                File(root, "cache"),
            )
            assertThat(report.scannedFiles).isEqualTo(2)
            assertThat(report.candidates.map { it.reason }).containsExactly(
                UnityLocalizationCandidateReason.SOURCE_MISSING,
                UnityLocalizationCandidateReason.SOURCE_NOT_A_FILE,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun discoveryCacheNeverHidesADeletedStagedSource() {
        val root = Files.createTempDirectory("localization-cache-liveness").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val bundle = writeFixtureBundle(root, "candidate.bundle", serialized.readBytes(), "opaque")
            val request = UnityLocalizationScanRequest(bundle)
            UnityLocalizationDiscovery.clearCache()
            val first = UnityLocalizationDiscovery.scan(listOf(request), File(root, "cache"))
            assertThat(first.candidates.single().hasLocalizationTables).isTrue()
            assertThat(bundle.delete()).isTrue()
            val second = UnityLocalizationDiscovery.scan(listOf(request), File(root, "cache"))
            assertThat(second.candidates.single().reason)
                .isEqualTo(UnityLocalizationCandidateReason.SOURCE_MISSING)
        } finally {
            UnityLocalizationDiscovery.clearCache()
            root.deleteRecursively()
        }
    }

    @Test
    fun directoryEnumerationStopsAtFileBudgetBeforeBuildingAnUnboundedList() {
        val root = Files.createTempDirectory("localization-directory-budget").toFile()
        try {
            repeat(5) { File(root, "file-$it.bin").writeText("$it") }
            val listing = UnityLocalizationDirectoryDiscovery.list(
                root,
                UnityLocalizationScanBudget(maxFiles = 2, maxSourceBytes = 1024, maxElapsedMillis = 10_000),
            )
            assertThat(listing.files).hasSize(2)
            assertThat(listing.stoppedByBudget).isTrue()
            assertThat(listing.budgetStopReason).isEqualTo(UnityLocalizationBudgetStopReason.FILE_LIMIT)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pickedApkStagingIsBoundedAndCleansFailedTemporaryFiles() {
        val root = Files.createTempDirectory("localization-picked-apk-limit").toFile()
        try {
            assertThrows(IOException::class.java) {
                stagePickedApk(root, "content://fixture", { ByteArrayInputStream(ByteArray(33)) }, maxBytes = 32)
            }
            val staging = File(root, "unity-picked-apk")
            assertThat(staging.listFiles().orEmpty()).isEmpty()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun apkListingBudgetStillStagesBoundedShapeMatchedCandidates() {
        val root = Files.createTempDirectory("localization-apk-list-budget").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val bundle = writeFixtureBundle(root, "payload.bundle", serialized.readBytes(), "opaque-child")
            val apk = File(root, "sample.apk")
            ZipOutputStream(FileOutputStream(apk)).use { zip ->
                repeat(3) { index ->
                    zip.putNextEntry(ZipEntry("assets/content-$index"))
                    bundle.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            UnityLocalizationApkDiscovery.prepare(
                apk,
                File(root, "adapter-cache"),
                "apk-scope",
                UnityLocalizationScanBudget(maxFiles = 2, maxSourceBytes = 10_000_000, maxElapsedMillis = 10_000),
            ).use { session ->
                assertThat(session.requests).hasSize(2)
                assertThat(session.stoppedByBudget).isTrue()
                assertThat(session.budgetStopReason).isEqualTo(UnityLocalizationBudgetStopReason.FILE_LIMIT)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun apkAdapterRejectsShapeMatchedContentAtUnsafeInternalPaths() {
        val root = Files.createTempDirectory("localization-apk-unsafe-path").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val bundle = writeFixtureBundle(root, "payload.bundle", serialized.readBytes(), "opaque-child")
            val apk = File(root, "sample.apk")
            ZipOutputStream(FileOutputStream(apk)).use { zip ->
                listOf("../opaque", "/absolute", "assets\\backslash").forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    bundle.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            UnityLocalizationApkDiscovery.prepare(apk, File(root, "adapter-cache"), "apk-scope").use { session ->
                assertThat(session.requests).isEmpty()
                assertThat(session.failures).hasSize(3)
                assertThat(session.failures.map { it.reason }.toSet())
                    .containsExactly(UnityLocalizationCandidateReason.ARCHIVE_ENTRY_UNSAFE_PATH)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun apkAdapterRejectsDuplicateEntryIdentityInsteadOfOpeningAnAmbiguousParent() {
        val root = Files.createTempDirectory("localization-apk-duplicate").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val bundle = writeFixtureBundle(root, "payload.bundle", serialized.readBytes(), "opaque-child")
            val apk = File(root, "sample.apk")
            ZipArchiveOutputStream(apk).use { zip ->
                repeat(2) {
                    zip.putArchiveEntry(ZipArchiveEntry("assets/duplicate"))
                    bundle.inputStream().use { it.copyTo(zip) }
                    zip.closeArchiveEntry()
                }
            }
            UnityLocalizationApkDiscovery.prepare(apk, File(root, "adapter-cache"), "apk-scope").use { session ->
                assertThat(session.requests).isEmpty()
                assertThat(session.failures.single().reason)
                    .isEqualTo(UnityLocalizationCandidateReason.ARCHIVE_ENTRY_DUPLICATE)
                assertThat(session.failures.single().openRequest).isNull()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun apkAdapterFindsContentNamedArbitrarilyAndRetainsParentIdentityUntilClose() {
        val root = Files.createTempDirectory("localization-apk-adapter").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val bundle = writeFixtureBundle(root, "payload.bundle", serialized.readBytes(), "opaque-child")
            val apk = File(root, "sample.apk")
            ZipOutputStream(FileOutputStream(apk)).use { zip ->
                zip.putNextEntry(ZipEntry("assets/no-extension-random-name"))
                bundle.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/name-only.assets"))
                zip.write("not unity".toByteArray())
                zip.closeEntry()
            }
            val session = UnityLocalizationApkDiscovery.prepare(apk, File(root, "adapter-cache"), "apk-scope")
            val staged = session.requests.single().file
            assertThat(session.requests.single().identity)
                .isEqualTo("${apk.canonicalPath}!/assets/no-extension-random-name")
            assertThat(session.requests.single().openRequest.parents).containsExactly(
                ArchiveParent(apk.canonicalPath, "assets/no-extension-random-name"),
            )
            val report = session.merge(UnityLocalizationDiscovery.scan(session.requests, File(root, "scan-cache")))
            assertThat(report.candidates.single().tableCount).isEqualTo(1)
            assertThat(staged.isFile).isTrue()
            session.close()
            assertThat(staged.exists()).isFalse()
        } finally {
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
    fun rtlInferenceChangesOnlyOneRuntimeDerivedBitAndNeverGuessesAmbiguity() {
        val resolver = UnityLanguageDirectionResolver { code ->
            when (code) {
                "la-AA", "lb-BB" -> UnityLanguageDirection.LEFT_TO_RIGHT
                "ra-AA", "fa" -> UnityLanguageDirection.RIGHT_TO_LEFT
                else -> UnityLanguageDirection.UNKNOWN
            }
        }
        val languages = listOf(
            LocalizationLanguage("Left A", "la-AA", 0b1000),
            LocalizationLanguage("Left B", "lb-BB", 0b1000),
            LocalizationLanguage("Right A", "ra-AA", 0b1010),
        )
        val inferred = UnityRtlFlagInferer.infer(languages, "fa", 0b1100, resolver)
        assertThat(inferred.status).isEqualTo(UnityRtlInferenceStatus.DETERMINED)
        assertThat(inferred.bitMask).isEqualTo(0b0010)
        assertThat(inferred.targetFlags).isEqualTo(0b1110)
        assertThat(inferred.targetFlags!! and 0b1101).isEqualTo(0b1100)

        val ambiguous = UnityRtlFlagInferer.infer(
            listOf(
                LocalizationLanguage("Left", "la-AA", 0),
                LocalizationLanguage("Right", "ra-AA", 0b11),
            ),
            "fa",
            0,
            resolver,
        )
        assertThat(ambiguous.status).isEqualTo(UnityRtlInferenceStatus.AMBIGUOUS_BITS)
        assertThat(ambiguous.targetFlags).isNull()

        val blockedPlan = UnityLocalizationOperations.planAddLanguage(
            sourceModel(singleTerm = true),
            LocalizationLanguage("Target", "fa", 0),
        ).copy(rtlFlagInference = ambiguous)
        assertThat(blockedPlan.canApply).isFalse()
        assertThat(blockedPlan.rtlFlagInference?.status)
            .isEqualTo(UnityRtlInferenceStatus.AMBIGUOUS_BITS)
    }

    @Test
    fun fontCoverageUsesActualPendingTargetAndSourceStrings() {
        val source = sourceModel(singleTerm = true, english = "Hello", french = "ژ")
        val tmp = UnityTmpFontAssetReport(
            entryName = "fixture",
            partIndex = 0,
            pathId = 1,
            name = "Static",
            glyphCount = 4,
            characterCount = 4,
            populationModeRaw = 0,
            populationMode = UnityFontPopulationMode.STATIC,
            sourceFontFileId = 0,
            sourceFontPathId = 0,
            resolvedSourceFontName = null,
            sourceFontSupportsArabic = false,
            atlasTextureCount = 1,
            atlasWidth = 64,
            atlasHeight = 64,
            coverage = UnicodeBlockCoverage(basicLatinCount = 4),
            codepoints = "Helo".map { it.code }.toSet(),
            statusReason = null,
            capabilityStatement = "fixture",
        )
        val report = UnityFontReport(
            inspectedEntries = 1,
            tmpFontAssets = listOf(tmp),
            rawFonts = emptyList(),
            summaryStatement = "fixture",
        )
        val coverage = UnityTranslationFontCoverageEvaluator.evaluate(report, source, "fr", "en")
        assertThat(coverage.targetCodepointCount).isEqualTo(1)
        assertThat(coverage.sourceCodepointCount).isEqualTo(4)
        assertThat(coverage.missingTargetCodepoints).containsExactly('ژ'.code)
        assertThat(coverage.missingSourceCodepoints).isEmpty()
        assertThat(coverage.complete).isFalse()

        val dynamicWithoutExactCmap = report.copy(
            tmpFontAssets = listOf(
                tmp.copy(
                    populationMode = UnityFontPopulationMode.DYNAMIC,
                    populationModeRaw = 1,
                    sourceFontSupportsArabic = true,
                ),
            ),
        )
        assertThat(
            UnityTranslationFontCoverageEvaluator.evaluate(dynamicWithoutExactCmap, source, "fr", "en")
                .missingTargetCodepoints,
        ).containsExactly('ژ'.code)

        val coveredReport = report.copy(tmpFontAssets = listOf(tmp.copy(codepoints = tmp.codepoints + 'ژ'.code)))
        val covered = UnityTranslationFontCoverageEvaluator.evaluate(coveredReport, source, "fr", "en")
        assertThat(covered.complete).isTrue()
        assertThat(covered.missingCodepoints).isEmpty()
    }

    @Test
    fun readinessBlocksMissingGlyphsAndEnablesExportOnlyAfterRoundTrip() {
        val missing = UnityTranslationFontCoverage(
            targetLanguageCode = "target",
            targetCodepointCount = 1,
            sourceCodepointCount = 0,
            missingTargetCodepoints = listOf(0x0698),
            missingSourceCodepoints = emptyList(),
            inspectedTmpFontCount = 1,
            inspectedRawFontCount = 0,
        )
        val blocked = UnityTranslationReadinessEvaluator.evaluate(
            UnityTranslationReadinessInput(
                tableSelected = true,
                tableInspectionFailed = false,
                targetLanguageSelected = true,
                fontCoverage = missing,
                compressionSupported = true,
                roundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
            ),
        )
        assertThat(blocked.canExport).isFalse()
        assertThat(blocked.checks.single { it.kind == UnityTranslationReadinessKind.FONT_COVERAGE }.status)
            .isEqualTo(UnityTranslationReadinessStatus.BLOCKED)

        val ready = UnityTranslationReadinessEvaluator.evaluate(
            UnityTranslationReadinessInput(
                tableSelected = true,
                tableInspectionFailed = false,
                targetLanguageSelected = true,
                fontCoverage = missing.copy(missingTargetCodepoints = emptyList()),
                compressionSupported = true,
                compressionDetail = "LZ4",
                roundTripStatus = UnityLocalizationRoundTripStatus.PASSED,
            ),
        )
        assertThat(ready.canExport).isTrue()
        assertThat(ready.nextAction).isEqualTo(UnityTranslationNextAction.EXPORT_OUTPUT)

        val unsupportedCompression = UnityTranslationReadinessEvaluator.evaluate(
            UnityTranslationReadinessInput(
                tableSelected = true,
                tableInspectionFailed = false,
                targetLanguageSelected = true,
                fontCoverage = missing.copy(missingTargetCodepoints = emptyList()),
                compressionSupported = false,
                compressionDetail = "unsupported",
                roundTripStatus = UnityLocalizationRoundTripStatus.PASSED,
            ),
        )
        assertThat(unsupportedCompression.canExport).isFalse()
        assertThat(unsupportedCompression.checks.single { it.kind == UnityTranslationReadinessKind.COMPRESSION }.status)
            .isEqualTo(UnityTranslationReadinessStatus.BLOCKED)

        val needsTranslation = UnityTranslationReadinessEvaluator.evaluate(
            UnityTranslationReadinessInput(
                tableSelected = true,
                tableInspectionFailed = false,
                targetLanguageSelected = true,
                fontCoverage = missing.copy(missingTargetCodepoints = emptyList()),
                compressionSupported = true,
                translationPending = false,
                roundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
            ),
        )
        assertThat(needsTranslation.nextAction).isEqualTo(UnityTranslationNextAction.PREPARE_TRANSLATION)
        val pending = UnityTranslationReadinessEvaluator.evaluate(
            UnityTranslationReadinessInput(
                tableSelected = true,
                tableInspectionFailed = false,
                targetLanguageSelected = true,
                fontCoverage = missing.copy(missingTargetCodepoints = emptyList()),
                compressionSupported = true,
                translationPending = true,
                roundTripStatus = UnityLocalizationRoundTripStatus.NOT_RUN,
            ),
        )
        assertThat(pending.nextAction).isEqualTo(UnityTranslationNextAction.VALIDATE_AND_STAGE)
    }

    @Test
    fun signingHandoffRequiresAValidCertificateForEditedApks() {
        assertThat(UnityApkSigningHandoffEvaluator.evaluate(false, false, 0))
            .isEqualTo(UnityApkSigningHandoffState.NOT_APK)
        assertThat(UnityApkSigningHandoffEvaluator.evaluate(true, false, 0))
            .isEqualTo(UnityApkSigningHandoffState.SIGNING_REQUIRED)
        assertThat(UnityApkSigningHandoffEvaluator.evaluate(true, true, 0))
            .isEqualTo(UnityApkSigningHandoffState.SIGNING_REQUIRED)
        assertThat(UnityApkSigningHandoffEvaluator.evaluate(true, true, 1))
            .isEqualTo(UnityApkSigningHandoffState.VERIFIED_CERTIFICATE)
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
                UnityBundleReader.open(replacement, File(root, "replacement-cache")).use { staged ->
                    val stagedFound = staged.inspectLocalizations(staged.summary.entries.single())
                        as UnityLocalizationInspection.Found
                    assertThat(stagedFound.sources.single().languages.map { it.code })
                        .containsExactly("en", "fr", "fa").inOrder()
                }
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

    @Test
    fun standaloneGrowthFailureDeletesEveryUnpublishedOutput() {
        val root = Files.createTempDirectory("localization-growth-abort").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val bundle = writeFixtureBundle(root, "guard.bundle", serialized.readBytes(), "opaque")
            val oversized = File(root, "oversized.bin").apply {
                writeBytes(ByteArray(128 * 1024).also { Random(91).nextBytes(it) })
            }
            UnityBundleReader.open(bundle, File(root, "guard-cache")).use { document ->
                assertThrows(UnityLocalizationSizeGuardException::class.java) {
                    UnityPackageRepacker.repack(
                        document,
                        mapOf(document.summary.entries.single().name to oversized),
                        emptyList(),
                        File(root, "repack-cache"),
                        1.25,
                    )
                }
            }
            assertThat(File(root, "guard-unity-edited.bundle").exists()).isFalse()
            assertThat(root.listFiles().orEmpty().none { it.name.startsWith(".guard-unity-edited.bundle") }).isTrue()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localizationOverlayPreservesAnExistingStagedEntryWhileApplyingTableValues() {
        val root = Files.createTempDirectory("localization-overlay").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "base.assets")
            val bundle = writeFixtureBundle(root, "source.bundle", serialized.readBytes(), "opaque")
            val desired = UnityBundleReader.open(bundle, File(root, "source-cache")).use { document ->
                val found = document.inspectLocalizations(document.summary.entries.single())
                    as UnityLocalizationInspection.Found
                found.sources.single().addLanguage(LocalizationLanguage("Spanish", "es", 0))
            }
            val output = File(root, "overlay.assets")
            UnityLocalizationOverlay.write(serialized, listOf(desired), output, File(root, "overlay-cache"))
            assertThat(sha256(serialized)).isNotEqualTo(sha256(output))
            UnityBundleReader.open(output, File(root, "verify-overlay")).use { document ->
                val found = document.summary.entries.asSequence()
                    .map { document.inspectLocalizations(it) }
                    .filterIsInstance<UnityLocalizationInspection.Found>()
                    .flatMap { it.sources.asSequence() }
                    .single()
                assertThat(found.languages.map { it.code }).containsExactly("en", "fr", "es").inOrder()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nestedApkRepackIsAtomicPreservesMethodAndPublishesEditedName() {
        val root = Files.createTempDirectory("localization-apk-repack").toFile()
        try {
            val serialized = writeLocalizationSerialized(root, "table.assets")
            val bundle = writeFixtureBundle(root, "source.bundle", serialized.readBytes(), "opaque")
            val apk = File(root, "game.apk")
            val padding = ByteArray(256 * 1024).also { Random(73).nextBytes(it) }
            ZipOutputStream(FileOutputStream(apk)).use { zip ->
                zip.putNextEntry(ZipEntry("assets/opaque.data"))
                bundle.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("assets/padding.bin"))
                zip.write(padding)
                zip.closeEntry()
            }
            val originalHash = sha256(apk)
            val originalMethod = ZipFile(apk).use { it.getEntry("assets/opaque.data").method }
            val replacement = File(root, "replacement.data")
            val output = UnityBundleReader.open(bundle, File(root, "open-cache")).use { document ->
                val entry = document.summary.entries.single()
                val table = (document.inspectLocalizations(entry) as UnityLocalizationInspection.Found)
                    .sources.single()
                document.writeLocalizationReplacement(
                    entry,
                    listOf(table.addLanguage(LocalizationLanguage("Third", "es", 0))),
                    replacement,
                )
                UnityPackageRepacker.repack(
                    document,
                    mapOf(entry.name to replacement),
                    listOf(ArchiveParent(apk.path, "assets/opaque.data")),
                    File(root, "repack-cache"),
                    1.25,
                )
            }
            assertThat(output.name).isEqualTo("game-edited.apk")
            assertThat(sha256(apk)).isEqualTo(originalHash)
            ZipFile(output).use { zip ->
                val edited = zip.getEntry("assets/opaque.data")
                assertThat(edited).isNotNull()
                assertThat(edited.method).isEqualTo(originalMethod)
                val extracted = File(root, "edited.bundle")
                zip.getInputStream(edited).use { input ->
                    extracted.outputStream().use { input.copyTo(it) }
                }
                UnityBundleReader.open(extracted, File(root, "verify-cache")).use { document ->
                    val found = document.inspectLocalizations(document.summary.entries.single())
                        as UnityLocalizationInspection.Found
                    assertThat(found.sources.single().languages.map { it.code })
                        .containsExactly("en", "fr", "es").inOrder()
                }
            }
            assertThat(root.listFiles().orEmpty().none { it.name.startsWith(".game-edited.apk") }).isTrue()
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
