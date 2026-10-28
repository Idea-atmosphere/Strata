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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnityFontAndShapingTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun shapesPersianWordsAndLamAlefLigaturesIntoPresentationForms() {
        // سلام -> Seen initial (0xFEB3), Lam-Alef final ligature (0xFEFC), Meem isolated (0xFEE1)
        assertThat(UnityTextShaper.shapeArabicPersian("سلام"))
            .isEqualTo("\uFEB3\uFEFC\uFEE1")

        // ایران -> Alef isolated (0xFE8D), Farsi Yeh initial (0xFBFE), Reh final (0xFEAE),
        //          Alef isolated (0xFE8D), Noon isolated (0xFEE5)
        assertThat(UnityTextShaper.shapeArabicPersian("ایران"))
            .isEqualTo("\uFE8D\uFBFE\uFEAE\uFE8D\uFEE5")

        // کتاب -> Persian Keheh initial (0xFB90), Teh medial (0xFE98), Alef final (0xFE8E), Beh isolated (0xFE8F)
        assertThat(UnityTextShaper.shapeArabicPersian("کتاب"))
            .isEqualTo("\uFB90\uFE98\uFE8E\uFE8F")

        // می‌شود (with ZWNJ \u200C) -> Meem initial (0xFEE3), Farsi Yeh final (0xFBFD),
        // ZWNJ stripped, Sheen initial (0xFEB7), Waw final (0xFEEE), Dal isolated (0xFEA9)
        assertThat(UnityTextShaper.shapeArabicPersian("می\u200Cشود"))
            .isEqualTo("\uFEE3\uFBFD\uFEB7\uFEEE\uFEA9")

        // Persian-specific letters پ چ ژ گ (Presentation Forms-A)
        // گچ‌پژ -> Gaf initial (0xFB94), Tcheh final (0xFB7B), Peh initial (0xFB58), Jeh final (0xFB8B)
        assertThat(UnityTextShaper.shapeArabicPersian("گچ\u200Cپژ"))
            .isEqualTo("\uFB94\uFB7B\uFB58\uFB8B")

        // Mandatory Lam-Alef ligatures: isolated (لا لآ لأ لإ) and final (بلا بلآ بلأ بلإ)
        assertThat(UnityTextShaper.shapeArabicPersian("لا لآ لأ لإ"))
            .isEqualTo("\uFEFB \uFEF5 \uFEF7 \uFEF9")
        assertThat(UnityTextShaper.shapeArabicPersian("بلا بلآ بلأ بلإ"))
            .isEqualTo("\uFE91\uFEFC \uFE91\uFEF6 \uFE91\uFEF8 \uFE91\uFEFA")
    }

    @Test
    fun reordersMixedPersianEnglishAndDigitsVisually() {
        val scoreLine = "امتیاز: 120 از 500"
        val reorderedScore = UnityTextShaper.reorderBidiVisual(scoreLine)
        // Digits "500" and "120" must stay left-to-right; RTL words and run order reverse visually
        assertThat(reorderedScore).isEqualTo("500 زا 120 :زایتما")
        assertThat(reorderedScore).contains("120")
        assertThat(reorderedScore).contains("500")
        assertThat(reorderedScore).doesNotContain("021")
        assertThat(reorderedScore).doesNotContain("005")

        val versionLine = "نسخه Unity 2022"
        val reorderedVersion = UnityTextShaper.reorderBidiVisual(versionLine)
        assertThat(reorderedVersion).isEqualTo("Unity 2022 هخسن")

        // Combined shaping + visual bidi reordering
        val combined = UnityTextShaper.transform(
            "نسخه Unity 2022",
            UnityTextTransformOptions(shapeArabic = true, reverseBidi = true),
        )
        // نسخه shaped is \uFEE7\uFEB4\uFEA8\uFEEA; reversed visually is \uFEEA\uFEA8\uFEB4\uFEE7
        assertThat(combined).isEqualTo("Unity 2022 \uFEEA\uFEA8\uFEB4\uFEE7")

        // Mirrored parentheses inside an RTL line
        assertThat(UnityTextShaper.reorderBidiVisual("سلام (12)")).isEqualTo("(12) مالس")
    }

    @Test
    fun detectsFontCapabilityAndUnicodeBlockCoverageOnSyntheticTmpAsset() {
        // Synthetic TMP_FontAsset with 3 Latin ('A', 'B', 'C'), 2 Cyrillic ('Д', 'Ж'),
        // 2 Arabic base ('س', 'م'), 1 Presentation Form-A ('ﭘ'), and 1 Presentation Form-B ('ﻼ')
        val codepoints = listOf(
            0x0041, 0x0042, 0x0043,
            0x0414, 0x0416,
            0x0633, 0x0645,
            0xFB58,
            0xFEFC,
        )
        val file = writeFontSerializedFile(
            root = temp.root,
            name = "capable-font.assets",
            tmpAssets = listOf(
                SyntheticTmpAsset(
                    pathId = 100L,
                    name = "DialogueFont SDF",
                    populationMode = 0,
                    sourceFontFileId = 0,
                    sourceFontPathId = 0L,
                    codepoints = codepoints,
                ),
            ),
            rawFonts = emptyList(),
        )

        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val report = document.inspectFonts()
            assertThat(report.tmpFontAssets).hasSize(1)
            val tmp = report.tmpFontAssets.single()
            assertThat(tmp.name).isEqualTo("DialogueFont SDF")
            assertThat(tmp.populationMode).isEqualTo(UnityFontPopulationMode.STATIC)
            assertThat(tmp.glyphCount).isEqualTo(9)
            assertThat(tmp.characterCount).isEqualTo(9)
            assertThat(tmp.coverage.basicLatinCount).isEqualTo(3)
            assertThat(tmp.coverage.cyrillicCount).isEqualTo(2)
            assertThat(tmp.coverage.arabicBaseCount).isEqualTo(2)
            assertThat(tmp.coverage.presentationFormsACount).isEqualTo(1)
            assertThat(tmp.coverage.presentationFormsBCount).isEqualTo(1)
            assertThat(tmp.coverage.arabicTotalCount).isEqualTo(4)
            assertThat(tmp.statusReason).isNull()
            assertThat(tmp.canRenderPersian).isTrue()
            assertThat(report.canRenderPersian).isTrue()
            assertThat(tmp.capabilityStatement).contains("4 Arabic/Persian glyphs")
        }
    }

    @Test
    fun detectsEmptyCharacterTableAndStaticAssetWithoutSourceFont() {
        val file = writeFontSerializedFile(
            root = temp.root,
            name = "missing-arabic.assets",
            tmpAssets = listOf(
                SyntheticTmpAsset(
                    pathId = 200L,
                    name = "EmptyFont SDF",
                    populationMode = 0,
                    sourceFontFileId = 0,
                    sourceFontPathId = 0L,
                    codepoints = emptyList(),
                ),
                SyntheticTmpAsset(
                    pathId = 201L,
                    name = "TitleFont",
                    populationMode = 0,
                    sourceFontFileId = 0,
                    sourceFontPathId = 0L,
                    codepoints = listOf(0x0041, 0x0042, 0x0410), // Latin + Cyrillic only
                ),
            ),
            rawFonts = emptyList(),
        )

        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val emptyReport = document.inspectFonts(document.summary.entries[0])
            val emptyAsset = emptyReport.tmpFontAssets.single()
            assertThat(emptyAsset.characterCount).isEqualTo(0)
            assertThat(emptyAsset.statusReason).isEqualTo(UnityTextUnavailableReason.FONT_NO_GLYPHS)
            assertThat(emptyReport.unavailableReason).isEqualTo(UnityTextUnavailableReason.FONT_NO_GLYPHS)

            val staticReport = document.inspectFonts(document.summary.entries[1])
            val staticAsset = staticReport.tmpFontAssets.single()
            assertThat(staticAsset.populationMode).isEqualTo(UnityFontPopulationMode.STATIC)
            assertThat(staticAsset.coverage.latinTotalCount).isEqualTo(2)
            assertThat(staticAsset.coverage.cyrillicCount).isEqualTo(1)
            assertThat(staticAsset.coverage.arabicTotalCount).isEqualTo(0)
            assertThat(staticAsset.statusReason).isEqualTo(UnityTextUnavailableReason.FONT_STATIC_NO_SOURCE)
            assertThat(staticAsset.capabilityStatement).isEqualTo(
                "TMP_FontAsset 'TitleFont' is Static with 3 glyphs (Latin, Cyrillic); 0 Arabic/Persian glyphs; no source TTF attached.",
            )
            assertThat(staticReport.missingCodepointsFor("سلام")).containsExactly(0x0633, 0x0644, 0x0627, 0x0645)
        }
    }

    @Test
    fun rejectsMismatchedGlyphAndCharacterTableSizes() {
        // 3 glyphs in m_GlyphTable vs 2 entries in m_CharacterTable violates the parallel-array invariant.
        val struct = buildDecodedTmpStruct(
            name = "BrokenFont SDF",
            populationMode = 0,
            sourceFileId = 0,
            sourcePathId = 0L,
            glyphCount = 3,
            codepoints = listOf(0x0041, 0x0042),
        )
        val error = assertThrows(UnityFormatException::class.java) {
            UnityFontInspector.validateTmpFontAssetInvariants(struct)
        }
        assertThat(error).hasMessageThat().contains("m_GlyphTable.size (3) != m_CharacterTable.size (2)")
    }

    @Test
    fun dynamicFontCoverageUsesExactLinkedSourceCmapInsteadOfScriptGuess() {
        val cmap = TtfCmapInspector.coverage(
            buildMinimalTtfWithCmap(listOf(0x0020..0x007E, 0x0698..0x0698)),
        )
        assertThat(cmap).isNotNull()
        assertThat(0x0698 in cmap!!).isTrue()
        assertThat(0x0628 in cmap).isFalse()

        val raw = UnityRawFontReport(
            entryName = "entry",
            partIndex = 0,
            pathId = 22L,
            name = "Source",
            embeddedFontBytes = 128,
            fontFormat = "TrueType",
            supportsArabicCmap = true,
            characterRectCount = 0,
            cmapCoverage = cmap,
        )
        val tmp = UnityTmpFontAssetReport(
            entryName = "entry",
            partIndex = 0,
            pathId = 11L,
            name = "Dynamic",
            glyphCount = 0,
            characterCount = 0,
            populationModeRaw = 1,
            populationMode = UnityFontPopulationMode.DYNAMIC,
            sourceFontFileId = 0,
            sourceFontPathId = raw.pathId,
            resolvedSourceFontName = raw.name,
            sourceFontSupportsArabic = true,
            atlasTextureCount = 1,
            atlasWidth = 64,
            atlasHeight = 64,
            coverage = UnicodeBlockCoverage(),
            codepoints = emptySet(),
            statusReason = null,
            capabilityStatement = "fixture",
        )
        val report = UnityFontReport(
            inspectedEntries = 1,
            tmpFontAssets = listOf(tmp),
            rawFonts = listOf(raw),
            summaryStatement = "fixture",
        )
        assertThat(report.missingCodepointsFor("Aژب")).containsExactly(0x0628)
    }

    @Test
    fun malformedCmapProducesATypedFailureInsteadOfSilentMissingCoverage() {
        val malformed = buildMinimalTtfWithCmap(listOf(0x0020..0x007E))
        ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN).putInt(24, Int.MAX_VALUE)

        val error = assertThrows(UnityFormatException::class.java) {
            TtfCmapInspector.coverage(malformed)
        }
        assertThat(error).hasMessageThat().contains("cmap")
    }

    @Test
    fun format4SentinelSegmentWithMaxRangeOffsetIsSkipped() {
        // TrashHand (Bendy and the Dark Revival) ends its format 4 cmap with a
        // 0xFFFF..0xFFFF segment whose idRangeOffset is 0xFFFF. Resolving glyphs
        // for it addresses past the subtable; the segment is a terminator and
        // must be ignored instead of failing the whole inspection.
        val font = buildMinimalTtfWithCmap(
            ranges = listOf(0x0041..0x0042),
            sentinelDelta = 1,
            sentinelRangeOffset = 0xFFFF,
        )

        val coverage = TtfCmapInspector.coverage(font)

        assertThat(coverage).isNotNull()
        assertThat(coverage!!.ranges).containsExactly(0x0041..0x0042)
        assertThat(0xFFFF in coverage).isFalse()
    }

    @Test
    fun patchesStaticTmpFontToDynamicWithEmbeddedArabicTtfAndVerifiesRoundTrip() {
        val arabicTtf = buildMinimalTtfWithCmap(
            ranges = listOf(0x0020..0x007E, 0x0600..0x06FF, 0xFB50..0xFDFF, 0xFE70..0xFEFF),
        )
        val file = writeFontSerializedFile(
            root = temp.root,
            name = "patchable.assets",
            tmpAssets = listOf(
                SyntheticTmpAsset(
                    pathId = 300L,
                    name = "BodyFont SDF",
                    populationMode = 0,
                    sourceFontFileId = 0,
                    sourceFontPathId = 0L,
                    codepoints = listOf(0x0041, 0x0042),
                ),
            ),
            rawFonts = listOf(
                SyntheticRawFont(
                    pathId = 400L,
                    name = "Vazirmatn-Regular",
                    fontData = ByteArray(16), // placeholder before replacement
                ),
            ),
        )

        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val before = document.inspectFonts()
            assertThat(before.canRenderPersian).isFalse()
            assertThat(before.tmpFontAssets.single().statusReason)
                .isEqualTo(UnityTextUnavailableReason.FONT_STATIC_NO_SOURCE)

            val patchResult = document.patchFontForDynamicGeneration(
                entry = document.summary.entries.first(),
                ttfBytes = arabicTtf,
            )
            assertThat(patchResult).isInstanceOf(UnityFontPatchResult.Success::class.java)
            val success = patchResult as UnityFontPatchResult.Success
            assertThat(success.patchedTmpAssets).isEqualTo(1)
            assertThat(success.boundFontPathId).isEqualTo(400L)
            assertThat(success.updatedReport.canRenderPersian).isTrue()

            val updatedTmp = success.updatedReport.tmpFontAssets.single()
            assertThat(updatedTmp.populationMode).isEqualTo(UnityFontPopulationMode.DYNAMIC)
            assertThat(updatedTmp.sourceFontFileId).isEqualTo(0)
            assertThat(updatedTmp.sourceFontPathId).isEqualTo(400L)
            assertThat(updatedTmp.sourceFontSupportsArabic).isTrue()
            assertThat(updatedTmp.statusReason).isNull()
        }
    }

    @Test
    fun reportsStaticNoSourceWhenPatchingBundleWithZeroRawFontObjects() {
        val arabicTtf = buildMinimalTtfWithCmap(ranges = listOf(0x0600..0x06FF))
        val file = writeFontSerializedFile(
            root = temp.root,
            name = "no-raw-font.assets",
            tmpAssets = listOf(
                SyntheticTmpAsset(
                    pathId = 500L,
                    name = "OrphanStatic SDF",
                    populationMode = 0,
                    sourceFontFileId = 0,
                    sourceFontPathId = 0L,
                    codepoints = listOf(0x0041),
                ),
            ),
            rawFonts = emptyList(),
        )

        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val result = document.patchFontForDynamicGeneration(
                entry = document.summary.entries.single(),
                ttfBytes = arabicTtf,
            )
            assertThat(result).isInstanceOf(UnityFontPatchResult.Unavailable::class.java)
            val unavailable = result as UnityFontPatchResult.Unavailable
            assertThat(unavailable.reason).isEqualTo(UnityTextUnavailableReason.FONT_STATIC_NO_SOURCE)
        }
    }

    @Test
    fun replacesRawFontDataWithoutTmpAssetAndVerifiesRoundTrip() {
        val arabicTtf = buildMinimalTtfWithCmap(
            ranges = listOf(0x0020..0x007E, 0x0600..0x06FF, 0xFB50..0xFDFF, 0xFE70..0xFEFF),
        )
        val file = writeFontSerializedFile(
            root = temp.root,
            name = "raw-only.assets",
            tmpAssets = emptyList(),
            rawFonts = listOf(
                SyntheticRawFont(
                    pathId = 400L,
                    name = "NotoSans-Regular",
                    fontData = ByteArray(16), // placeholder before replacement
                ),
            ),
        )

        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val before = document.inspectFonts()
            assertThat(before.tmpFontAssets).isEmpty()
            assertThat(before.rawFonts.single().supportsArabicCmap).isFalse()
            assertThat(before.canRenderPersian).isFalse()

            val patchResult = document.patchRawFonts(
                entry = document.summary.entries.first(),
                ttfBytes = arabicTtf,
            )
            assertThat(patchResult).isInstanceOf(UnityFontPatchResult.Success::class.java)
            val success = patchResult as UnityFontPatchResult.Success
            assertThat(success.patchedTmpAssets).isEqualTo(0)
            assertThat(success.patchedRawFonts).isEqualTo(1)
            assertThat(success.boundFontPathId).isEqualTo(400L)
            assertThat(success.updatedReport.canRenderPersian).isTrue()

            val rebuiltFile = File(temp.root, "raw-only-patched.assets")
            rebuiltFile.writeBytes(success.rebuiltEntryBytes)
            UnityBundleReader.open(rebuiltFile, File(temp.root, "cache")).use { rebuilt ->
                val report = rebuilt.inspectFonts()
                assertThat(report.tmpFontAssets).isEmpty()
                val raw = report.rawFonts.single()
                assertThat(raw.name).isEqualTo("NotoSans-Regular")
                assertThat(raw.embeddedFontBytes).isEqualTo(arabicTtf.size)
                assertThat(raw.supportsArabicCmap).isTrue()
            }
        }
    }

    @Test
    fun replacesOnlyTargetedRawFontWhenPathIdGiven() {
        val arabicTtf = buildMinimalTtfWithCmap(
            ranges = listOf(0x0020..0x007E, 0x0600..0x06FF, 0xFB50..0xFDFF, 0xFE70..0xFEFF),
        )
        val file = writeFontSerializedFile(
            root = temp.root,
            name = "raw-targeted.assets",
            tmpAssets = emptyList(),
            rawFonts = listOf(
                SyntheticRawFont(pathId = 400L, name = "First", fontData = ByteArray(16)),
                SyntheticRawFont(pathId = 401L, name = "Second", fontData = ByteArray(8)),
            ),
        )

        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val patchResult = document.patchRawFonts(
                entry = document.summary.entries.first(),
                ttfBytes = arabicTtf,
                targetFontPathId = 401L,
            )
            assertThat(patchResult).isInstanceOf(UnityFontPatchResult.Success::class.java)
            val success = patchResult as UnityFontPatchResult.Success
            assertThat(success.patchedRawFonts).isEqualTo(1)
            assertThat(success.boundFontPathId).isEqualTo(401L)

            val rebuiltFile = File(temp.root, "raw-targeted-patched.assets")
            rebuiltFile.writeBytes(success.rebuiltEntryBytes)
            UnityBundleReader.open(rebuiltFile, File(temp.root, "cache")).use { rebuilt ->
                val byName = rebuilt.inspectFonts().rawFonts.associateBy { it.name }
                assertThat(byName["First"]?.embeddedFontBytes).isEqualTo(16)
                assertThat(byName["First"]?.supportsArabicCmap).isFalse()
                assertThat(byName["Second"]?.embeddedFontBytes).isEqualTo(arabicTtf.size)
                assertThat(byName["Second"]?.supportsArabicCmap).isTrue()
            }
        }
    }

    @Test
    fun reportsFontAssetNotFoundWhenPatchingRawFontsWithNoFontObjects() {
        val arabicTtf = buildMinimalTtfWithCmap(ranges = listOf(0x0600..0x06FF))
        val file = writeFontSerializedFile(
            root = temp.root,
            name = "raw-none.assets",
            tmpAssets = listOf(
                SyntheticTmpAsset(
                    pathId = 500L,
                    name = "OrphanStatic SDF",
                    populationMode = 0,
                    sourceFontFileId = 0,
                    sourceFontPathId = 0L,
                    codepoints = listOf(0x0041),
                ),
            ),
            rawFonts = emptyList(),
        )

        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val result = document.patchRawFonts(
                entry = document.summary.entries.single(),
                ttfBytes = arabicTtf,
            )
            assertThat(result).isInstanceOf(UnityFontPatchResult.Unavailable::class.java)
            val unavailable = result as UnityFontPatchResult.Unavailable
            assertThat(unavailable.reason).isEqualTo(UnityTextUnavailableReason.FONT_ASSET_NOT_FOUND)
        }
    }

    @Test
    fun rejectsTtfWithoutArabicGlyphsWhenPatchingRawFonts() {
        val asciiOnlyTtf = buildMinimalTtfWithCmap(ranges = listOf(0x0020..0x007E))
        val file = writeFontSerializedFile(
            root = temp.root,
            name = "raw-reject.assets",
            tmpAssets = emptyList(),
            rawFonts = listOf(
                SyntheticRawFont(pathId = 400L, name = "NotoSans-Regular", fontData = ByteArray(16)),
            ),
        )

        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val result = document.patchRawFonts(
                entry = document.summary.entries.first(),
                ttfBytes = asciiOnlyTtf,
            )
            assertThat(result).isInstanceOf(UnityFontPatchResult.Unavailable::class.java)
            val unavailable = result as UnityFontPatchResult.Unavailable
            assertThat(unavailable.reason).isEqualTo(UnityTextUnavailableReason.FONT_NO_GLYPHS)
        }
    }

    @Test
    fun rejectsTtfMissingShapedCodepointsWhenPatchingRawFonts() {
        // Logical Arabic letters only: shaping output (presentation forms) is not covered.
        val logicalOnlyTtf = buildMinimalTtfWithCmap(ranges = listOf(0x0627..0x064A))
        val file = writeFontSerializedFile(
            root = temp.root,
            name = "raw-shaped-gap.assets",
            tmpAssets = emptyList(),
            rawFonts = listOf(
                SyntheticRawFont(pathId = 400L, name = "NotoSans-Regular", fontData = ByteArray(16)),
            ),
        )

        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val result = document.patchRawFonts(
                entry = document.summary.entries.first(),
                ttfBytes = logicalOnlyTtf,
                expectedShapedText = "\uFEE3\uFBFD",
            )
            assertThat(result).isInstanceOf(UnityFontPatchResult.Unavailable::class.java)
            val unavailable = result as UnityFontPatchResult.Unavailable
            assertThat(unavailable.reason).isEqualTo(UnityTextUnavailableReason.FONT_NO_GLYPHS)
        }
    }

    @Test
    fun rebuildsTextAssetWithToggleableShapingAndBidiWhilePreservingAssetName() {
        val file = writeSimpleTextAssetSerializedFile(temp.root, "dialogue.assets", "Localization", "Hello 123")
        UnityBundleReader.open(file, File(temp.root, "cache")).use { document ->
            val entry = document.summary.entries.single()
            val edits = mapOf(
                "m_Name" to "Localization",
                "m_Script" to "امتیاز: 120 از 500",
            )
            val rebuiltBytes = document.rebuildTexts(
                entry = entry,
                edits = edits,
                transformOptions = UnityTextTransformOptions(shapeArabic = true, reverseBidi = true),
            )
            val replacement = File(temp.root, "rebuilt-script.bin").apply { writeBytes(rebuiltBytes) }
            val output = File(temp.root, "dialogue-rebuilt.assets")
            document.repack(output, mapOf(entry.name to replacement))

            UnityBundleReader.open(output, File(temp.root, "cache2")).use { rebuiltDoc ->
                val exported = rebuiltDoc.exportTexts(rebuiltDoc.summary.entries.single())!!
                assertThat(exported["m_Name"]).isEqualTo("Localization")
                val expectedShapedAndBidi = UnityTextShaper.transform(
                    "امتیاز: 120 از 500",
                    UnityTextTransformOptions(shapeArabic = true, reverseBidi = true),
                )
                assertThat(exported["m_Script"]).isEqualTo(expectedShapedAndBidi)
            }
        }
    }

    @Test
    fun normalizesArabicYehKafAndAlefMaksuraToPersianBeforeShaping() {
        // Raw mappings, written with escapes so Arabic vs Persian letters cannot be confused.
        assertThat(UnityTextShaper.normalizePersianCharacters("\u064A\u0643"))
            .isEqualTo("\u06CC\u06A9")
        assertThat(UnityTextShaper.normalizePersianCharacters("\u0649")).isEqualTo("\u06CC")
        // Digits, punctuation and ZWNJ are untouched by normalization.
        assertThat(UnityTextShaper.normalizePersianCharacters("می\u200Cشود ۱۲۳"))
            .isEqualTo("می\u200Cشود ۱۲۳")

        // Arabic Kaf (ك) typed on an Arabic keyboard shapes to Persian Keheh forms once
        // normalization runs before shaping: كتاب -> کتاب -> FB90 FE98 FE8E FE8F.
        assertThat(
            UnityTextShaper.transform(
                "\u0643تاب",
                UnityTextTransformOptions(normalizePersian = true, shapeArabic = true),
            ),
        ).isEqualTo("\uFB90\uFE98\uFE8E\uFE8F")
        // Without normalization the same input keeps Arabic Kaf forms (initial 0xFEDB).
        assertThat(
            UnityTextShaper.transform(
                "\u0643تاب",
                UnityTextTransformOptions(shapeArabic = true),
            ),
        ).isEqualTo("\uFEDB\uFE98\uFE8E\uFE8F")
    }

    @Test
    fun fontCoverageChecksShapedCodepointsWhenShapingIsEnabled() {
        // Static TMP atlas containing only the LOGICAL Persian letters of "می"
        // (Meem 0x0645, Farsi Yeh 0x06CC) — no presentation forms at all.
        val tmp = UnityTmpFontAssetReport(
            entryName = "test",
            partIndex = 0,
            pathId = 1L,
            name = "TestFont",
            glyphCount = 2,
            characterCount = 2,
            populationModeRaw = 0,
            populationMode = UnityFontPopulationMode.STATIC,
            sourceFontFileId = 0,
            sourceFontPathId = 0L,
            resolvedSourceFontName = null,
            sourceFontSupportsArabic = false,
            atlasTextureCount = 1,
            atlasWidth = 512,
            atlasHeight = 512,
            coverage = UnicodeBlockCoverage(arabicBaseCount = 2),
            // + the Latin glyphs of the English source text "Hello": the coverage
            // `complete` flag covers both the target and source columns, so the
            // fixture must cover both — otherwise the unshaped check fails on
            // the source letters even though the Persian letters are covered.
            codepoints = setOf(0x0645, 0x06CC, 0x0048, 0x0065, 0x006C, 0x006F),
            statusReason = null,
            capabilityStatement = "test",
        )
        val report = UnityFontReport(
            inspectedEntries = 1,
            tmpFontAssets = listOf(tmp),
            rawFonts = emptyList(),
            summaryStatement = "test",
        )
        val origin = LocalizationOrigin(
            entryName = "test",
            partIndex = 0,
            pathId = 1L,
            objectEntryName = "test",
            termsPath = emptyList(),
            languagesPath = emptyList(),
            languageCountPath = null,
            termKeyField = "key",
            termValuesField = "values",
            languageNameField = "name",
            languageCodeField = "code",
            languageFlagsField = "flags",
        )
        val source = LocalizationSource.create(
            name = "test",
            languages = listOf(
                LocalizationLanguage("English", "en", 0),
                LocalizationLanguage("Persian", "fa", 0),
            ),
            terms = listOf(
                LocalizationTerm.aligned("greeting", listOf("Hello", "می"), 2),
            ),
            origin = origin,
        )

        // Shaping off: logical letters are covered, so the check passes.
        val unshaped = UnityTranslationFontCoverageEvaluator.evaluate(
            report = report,
            source = source,
            targetLanguageCode = "fa",
            sourceLanguageCode = "en",
        )
        assertThat(unshaped.complete).isTrue()
        assertThat(unshaped.transformApplied).isFalse()

        // Shaping on: the game renders Meem initial (0xFEE3) + Farsi Yeh final (0xFBFD),
        // neither of which is in the atlas — the old logical-only check would wrongly pass.
        val shaped = UnityTranslationFontCoverageEvaluator.evaluate(
            report = report,
            source = source,
            targetLanguageCode = "fa",
            sourceLanguageCode = "en",
            transformOptions = UnityTextTransformOptions(shapeArabic = true),
        )
        assertThat(shaped.transformApplied).isTrue()
        assertThat(shaped.missingTransformedTargetCodepoints).containsExactly(0xFBFD, 0xFEE3)
        assertThat(shaped.effectiveMissingCodepoints).containsExactly(0xFBFD, 0xFEE3)
        assertThat(shaped.complete).isFalse()
    }

    @Test
    fun ttfMissingCodepointsReportsShapedGlyphGaps() {
        // Minimal TTF covering only logical Arabic letters (0x0627..0x064A).
        val logicalOnlyTtf = buildMinimalTtfWithCmap(listOf(0x0627..0x064A))
        // Shaped "می" needs Meem initial (0xFEE3) and Farsi Yeh final (0xFBFD).
        assertThat(TtfCmapInspector.missingCodepoints(logicalOnlyTtf, "\uFEE3\uFBFD"))
            .containsExactly(0xFBFD, 0xFEE3)

        // A TTF that also covers the presentation-form blocks passes the same text.
        val fullTtf = buildMinimalTtfWithCmap(listOf(0x0627..0x064A, 0xFB50..0xFEFF))
        assertThat(TtfCmapInspector.missingCodepoints(fullTtf, "\uFEE3\uFBFD")).isEmpty()
    }

    // ---------------------------------------------------------------------------------------
    // Synthetic fixtures for TMP_FontAsset (MonoBehaviour class 114), Font (class 128), and TTF
    // ---------------------------------------------------------------------------------------

    private data class SyntheticTmpAsset(
        val pathId: Long,
        val name: String,
        val populationMode: Int,
        val sourceFontFileId: Int,
        val sourceFontPathId: Long,
        val codepoints: List<Int>,
        val atlasWidth: Int = 1024,
        val atlasHeight: Int = 1024,
    )

    private data class SyntheticRawFont(
        val pathId: Long,
        val name: String,
        val fontData: ByteArray,
    )

    private fun buildDecodedTmpStruct(
        name: String,
        populationMode: Int,
        sourceFileId: Int,
        sourcePathId: Long,
        glyphCount: Int,
        codepoints: List<Int>,
        atlasWidth: Int = 1024,
        atlasHeight: Int = 1024,
    ): UVal.Struct {
        val glyphItems = ArrayList<UVal>(glyphCount)
        for (i in 0 until glyphCount) {
            glyphItems += UVal.Struct(
                linkedMapOf(
                    "m_Index" to UVal.Num(i.toLong(), 4),
                    "m_GlyphRect" to UVal.Struct(
                        linkedMapOf(
                            "m_X" to UVal.Num((i * 16).toLong(), 4),
                            "m_Y" to UVal.Num(0L, 4),
                            "m_Width" to UVal.Num(12L, 4),
                            "m_Height" to UVal.Num(16L, 4),
                        ),
                    ),
                    "m_AtlasIndex" to UVal.Num(0L, 4),
                ),
            )
        }
        val charItems = ArrayList<UVal>(codepoints.size)
        codepoints.forEachIndexed { idx, cp ->
            charItems += UVal.Struct(
                linkedMapOf(
                    "m_Unicode" to UVal.Num(cp.toLong(), 4),
                    "m_GlyphIndex" to UVal.Num(idx.toLong(), 4),
                ),
            )
        }
        return UVal.Struct(
            linkedMapOf(
                "m_Name" to UVal.Str(name),
                "m_AtlasPopulationMode" to UVal.Num(populationMode.toLong(), 4),
                "m_SourceFontFile" to UVal.Struct(
                    linkedMapOf(
                        "m_FileID" to UVal.Num(sourceFileId.toLong(), 4),
                        "m_PathID" to UVal.Num(sourcePathId, 8),
                    ),
                ),
                "m_AtlasWidth" to UVal.Num(atlasWidth.toLong(), 4),
                "m_AtlasHeight" to UVal.Num(atlasHeight.toLong(), 4),
                "m_GlyphTable" to UVal.Arr(glyphItems),
                "m_CharacterTable" to UVal.Arr(charItems),
            ),
        )
    }

    private fun tmpFontTypeTree(): TypeTree {
        fun intNode(name: String) = TTNode(1, "int", name, 4, 0)
        fun uintNode(name: String) = TTNode(1, "UInt32", name, 4, 0)
        fun i64Node(name: String) = TTNode(1, "SInt64", name, 8, 0)
        fun strNode(name: String) = TTNode(1, "string", name, -1, 0x4000)
        fun vectorOf(name: String, itemNode: TTNode): TTNode {
            val array = TTNode(1, "Array", "Array", -1, 0x4000, mutableListOf(intNode("size"), itemNode))
            return TTNode(1, "vector", name, -1, 0, mutableListOf(array))
        }

        val glyphRectNode = TTNode(
            1,
            "GlyphRect",
            "m_GlyphRect",
            16,
            0,
            mutableListOf(intNode("m_X"), intNode("m_Y"), intNode("m_Width"), intNode("m_Height")),
        )
        val glyphNode = TTNode(
            1,
            "Glyph",
            "data",
            24,
            0,
            mutableListOf(uintNode("m_Index"), glyphRectNode, intNode("m_AtlasIndex")),
        )
        val charNode = TTNode(
            1,
            "TMP_Character",
            "data",
            8,
            0,
            mutableListOf(uintNode("m_Unicode"), uintNode("m_GlyphIndex")),
        )
        val sourceFontNode = TTNode(
            1,
            "PPtr<Font>",
            "m_SourceFontFile",
            12,
            0,
            mutableListOf(intNode("m_FileID"), i64Node("m_PathID")),
        )
        val root = TTNode(
            1,
            "MonoBehaviour",
            "Base",
            -1,
            0,
            mutableListOf(
                strNode("m_Name"),
                intNode("m_AtlasPopulationMode"),
                sourceFontNode,
                intNode("m_AtlasWidth"),
                intNode("m_AtlasHeight"),
                vectorOf("m_GlyphTable", glyphNode),
                vectorOf("m_CharacterTable", charNode),
            ),
        )
        return TypeTree(root)
    }

    private fun rawFontTypeTree(): TypeTree {
        val strNode = TTNode(1, "string", "m_Name", -1, 0x4000)
        val fontDataNode = TTNode(1, "TypelessData", "m_FontData", -1, 0x4000)
        val root = TTNode(1, "Font", "Base", -1, 0, mutableListOf(strNode, fontDataNode))
        return TypeTree(root)
    }

    private fun encodeTreeToBlob(tree: TypeTree): Pair<ByteArray, ByteArray> {
        val stringPool = ByteArrayOutputStream()
        val offsets = LinkedHashMap<String, Int>()
        fun offsetOf(s: String): Int = offsets.getOrPut(s) {
            val pos = stringPool.size()
            stringPool.write(s.toByteArray(Charsets.UTF_8))
            stringPool.write(0)
            pos
        }
        val nodesOut = ByteArrayOutputStream()
        fun walk(node: TTNode, level: Int) {
            nodesOut.write(byteArrayOf(node.version.toByte(), (node.version ushr 8).toByte()))
            nodesOut.write(level)
            nodesOut.write(if (node.type == "Array") 1 else 0)
            nodesOut.write(le32(offsetOf(node.type).toLong()))
            nodesOut.write(le32(offsetOf(node.name).toLong()))
            nodesOut.write(le32(node.byteSize.toLong() and 0xFFFFFFFFL))
            nodesOut.write(le32(0))
            nodesOut.write(le32(node.metaFlag.toLong() and 0xFFFFFFFFL))
            node.children.forEach { walk(it, level + 1) }
        }
        walk(tree.root, 0)
        return nodesOut.toByteArray() to stringPool.toByteArray()
    }

    private fun writeFontSerializedFile(
        root: File,
        name: String,
        tmpAssets: List<SyntheticTmpAsset>,
        rawFonts: List<SyntheticRawFont>,
    ): File {
        val tmpTree = tmpFontTypeTree()
        val fontTree = rawFontTypeTree()
        val (tmpNodes, tmpStrings) = encodeTreeToBlob(tmpTree)
        val (fontNodes, fontStrings) = encodeTreeToBlob(fontTree)

        val hasRaw = rawFonts.isNotEmpty()
        val typeCount = if (hasRaw) 2 else 1

        val meta = ByteArrayOutputStream()
        meta.write("2022.3.62f2".toByteArray(Charsets.UTF_8))
        meta.write(0)
        meta.write(le32(13)) // Android build target
        meta.write(1) // enableTypeTree = true
        meta.write(le32(typeCount.toLong()))

        // Type 0: MonoBehaviour (114)
        meta.write(le32(114))
        meta.write(0)
        meta.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        meta.write(ByteArray(16)) // script id
        meta.write(ByteArray(16)) // old type hash
        meta.write(le32((tmpNodes.size / 24).toLong()))
        meta.write(le32(tmpStrings.size.toLong()))
        meta.write(tmpNodes)
        meta.write(tmpStrings)

        // Type 1: Font (128) if present
        if (hasRaw) {
            meta.write(le32(128))
            meta.write(0)
            meta.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            meta.write(ByteArray(16))
            meta.write(le32((fontNodes.size / 24).toLong()))
            meta.write(le32(fontStrings.size.toLong()))
            meta.write(fontNodes)
            meta.write(fontStrings)
        }

        val encodedObjects = ArrayList<Triple<Long, Int, ByteArray>>()
        tmpAssets.forEach { asset ->
            val struct = buildDecodedTmpStruct(
                name = asset.name,
                populationMode = asset.populationMode,
                sourceFileId = asset.sourceFontFileId,
                sourcePathId = asset.sourceFontPathId,
                glyphCount = asset.codepoints.size,
                codepoints = asset.codepoints,
                atlasWidth = asset.atlasWidth,
                atlasHeight = asset.atlasHeight,
            )
            encodedObjects += Triple(asset.pathId, 0, TypeTreeCodec.encode(tmpTree, struct, bigEndian = false))
        }
        rawFonts.forEach { raw ->
            val struct = UVal.Struct(
                linkedMapOf(
                    "m_Name" to UVal.Str(raw.name),
                    "m_FontData" to UVal.Bytes(raw.fontData),
                ),
            )
            encodedObjects += Triple(raw.pathId, 1, TypeTreeCodec.encode(fontTree, struct, bigEndian = false))
        }

        meta.write(le32(encodedObjects.size.toLong()))
        var dataOffsetInSection = 0
        val data = ByteArrayOutputStream()
        encodedObjects.forEach { (pathId, typeIdx, bytes) ->
            meta.write(ByteArray((4 - meta.size() % 4) % 4))
            meta.write(le64(pathId))
            meta.write(le32(dataOffsetInSection.toLong()))
            meta.write(le32(bytes.size.toLong()))
            meta.write(le32(typeIdx.toLong()))
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

    private fun writeSimpleTextAssetSerializedFile(
        root: File,
        fileName: String,
        assetName: String,
        scriptText: String,
    ): File {
        val bytes = TypeTreeCodec.encodeTextAsset(assetName, scriptText.toByteArray(Charsets.UTF_8), bigEndian = false)
        val meta = ByteArrayOutputStream()
        meta.write("2022.3.62f2".toByteArray(Charsets.UTF_8))
        meta.write(0)
        meta.write(le32(13))
        meta.write(0) // enableTypeTree = false (uses TextAsset built-in fallback)
        meta.write(le32(1))
        meta.write(le32(49)) // TextAsset
        meta.write(0)
        meta.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        meta.write(ByteArray(16))
        meta.write(le32(1))
        meta.write(ByteArray((4 - meta.size() % 4) % 4))
        meta.write(le64(1L))
        meta.write(le32(0L))
        meta.write(le32(bytes.size.toLong()))
        meta.write(le32(0L))

        val metadata = meta.toByteArray()
        val headerSize = 20
        val dataStart = (headerSize + metadata.size + 15) / 16 * 16
        val output = File(root, fileName)
        FileOutputStream(output).use { stream ->
            DataOutputStream(stream).use { out ->
                out.writeInt(metadata.size)
                out.writeInt(dataStart + bytes.size)
                out.writeInt(17)
                out.writeInt(dataStart)
                out.writeByte(0)
                out.write(ByteArray(3))
                out.write(metadata)
                out.write(ByteArray(dataStart - headerSize - metadata.size))
                out.write(bytes)
            }
        }
        return output
    }

    private fun buildMinimalTtfWithCmap(
        ranges: List<IntRange>,
        sentinelDelta: Int = 0,
        sentinelRangeOffset: Int = 0,
    ): ByteArray {
        val allRanges = ranges + listOf(0xFFFF..0xFFFF)
        val segCount = allRanges.size
        val segCountX2 = segCount * 2
        val subtableLen = 16 + segCount * 8
        val cmapLen = 12 + subtableLen
        val totalLen = 12 + 16 + cmapLen
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        // sfnt header (TrueType 0x00010000, 1 table)
        buf.putInt(0x00010000)
        buf.putShort(1)
        buf.putShort(16)
        buf.putShort(0)
        buf.putShort(0)

        // Table directory entry for 'cmap'
        buf.putInt(0x636D6170) // 'cmap'
        buf.putInt(0) // checksum
        buf.putInt(28) // offset
        buf.putInt(cmapLen) // length

        // cmap header (version 0, 1 encoding record -> subtable at offset 12)
        buf.putShort(0)
        buf.putShort(1)
        buf.putShort(3) // platformID = Windows
        buf.putShort(1) // encodingID = Unicode BMP
        buf.putInt(12)

        // Format 4 subtable
        buf.putShort(4)
        buf.putShort(subtableLen.toShort())
        buf.putShort(0)
        buf.putShort(segCountX2.toShort())
        buf.putShort(2)
        buf.putShort(1)
        buf.putShort(0)
        allRanges.forEach { buf.putShort(it.last.toShort()) }
        buf.putShort(0) // reservedPad
        allRanges.forEach { buf.putShort(it.first.toShort()) }
        allRanges.forEachIndexed { index, _ ->
            buf.putShort(if (index == allRanges.lastIndex) sentinelDelta.toShort() else 0)
        } // idDelta
        allRanges.forEachIndexed { index, _ ->
            buf.putShort(if (index == allRanges.lastIndex) sentinelRangeOffset.toShort() else 0)
        } // idRangeOffset

        return buf.array()
    }

    private fun le32(value: Long): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun le64(value: Long): ByteArray =
        ByteArray(8) { index -> ((value ushr (8 * index)) and 0xFF).toByte() }
}
