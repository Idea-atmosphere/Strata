package dev.cluvex.strata.core.unity

/**
 * Phase 4 core, part 3 (pure Kotlin, no Android deps, internal like
 * UnityBaseData / UnityPersianTmpPatcher because it exposes UVal):
 * the end-to-end Persian Apply pipeline for the Strata game-
 * translation mode. One call turns user-typed logical Persian terms
 * into (a) final I2 strings in PUA (word codes for baked words,
 * letter codes for fallback words, spaces/punctuation preserved),
 * and (b) patched TMP_FontAsset + atlas Texture2D UVal objects
 * ready to encode and rebuild into a base or bundle file.
 *
 * Pipeline (each step fails safe, see Result below):
 *  1. Validate inputs: terms must not contain PUA (type logical
 *     Persian, not cipher/PUA text); base TMP must not already
 *     contain PUA characters (fresh baking only, v1: start from
 *     an original fallback, not an already-PUA-baked one, because
 *     strip packing cannot reuse space inside an already-expanded
 *     atlas; incremental baking is future work, not a silent
 *     overwrite).
 *  2. Shape each term with UnityTextShaper (normalizePersian +
 *     shapeArabic + reverseBidi, all on: the pipeline shapes
 *     internally, so the I2 Apply that follows MUST have the 3
 *     transform checkboxes OFF, otherwise PUA text would be
 *     re-shaped / re-Bidi-reordered and break).
 *  3. Map shaped terms in one batch (UnityPersianWordMapper):
 *     shared word codes across terms, diacritics stripped,
 *     digits Latinised, ZWNJ/ZWJ dropped. If unmapped codepoints
 *     remain (logical Arabic the shaper did not shape, or the
 *     extension block exhausted), return NotReady: those codes
 *     would render as squares, so baking is not even attempted.
 *  4. Rank words by frequency (UnityPersianFrequency.rankWords).
 *  5. Rasterize distinct letters: every letter PUA in every word
 *     core (wordMap keys) plus every overflow-word core letter.
 *     The rasterizer receives the ORIGINAL codepoint to render
 *     (cipher reverse map for E000..E07F, extension reverse map
 *     for E080..E0FF), so it does not need to know cipher tables;
 *     it only needs to render one presentation-form glyph from a
 *     Persian font at the TMP pointSize. A missing glyph is a
 *     user-fixable font problem, reported as NotReady, not a crash
 *     (see [LetterRasterizer] / [PersianRasterizerException]).
 *  6. Bake letters first (PUA order), then words in frequency
 *     order, with preserveInputOrder=true, into a new atlas larger
 *     than the original (strip packing has no space when new ==
 *     original). Start at the smallest allowed size > original
 *     that is >= recommendAtlasSize; if unpacked words are >20%
 *     of total word occurrences and a larger allowed size exists,
 *     retry at the next larger size (up to 4096). Right pad is
 *     scaled from Bendy's 30 px at 84 pt to the TMP pointSize.
 *  7. Apply letter fallback for any words still unpacked, then
 *     check bake coverage (UnityPersianFrequency.checkBakeCoverage).
 *     Only isReady==true proceeds to patching; otherwise return
 *     NotReady. This is the gate that prevents shipping squares:
 *     a blocked Apply is a clear, fixable state, a shipped square
 *     is a broken game.
 *  8. Patch TMP + Texture (UnityPersianTmpPatcher) with the baked
 *     atlas, using the TMP's own padding and pointSize (derived
 *     from the base TMP, never supplied separately, so they cannot
 *     mismatch the patcher's checks).
 *
 * Result type (fail-safe by construction):
 * - [PipelineResult.Ready] carries finalTerms, patchedTmp,
 *   patchedTexture, and stats. The caller may encode the patched
 *   objects (with the correct TMP/Texture trees, via
 *   UnityBaseData.encodeWithExternalTree) and rebuild the base /
 *   bundle file, then apply finalTerms to I2 with transforms OFF.
 * - [PipelineResult.NotReady] carries issues, finalTerms (for
 *   debugging only, DO NOT APPLY), and partial stats, but NO
 *   patched objects, so a caller cannot accidentally Apply a
 *   broken patch by forgetting to check readiness. The UI must
 *   show issues and keep Apply disabled.
 *
 * What this module does NOT do (caller's job, future phases):
 * - It does not load fonts, open bundles/base files, encode UVal
 *   objects, rebuild serialized files, repack bundles/APKs, or
 *   touch I2 tables. It takes decoded UVal objects in and returns
 *   patched UVal objects + final strings out. That separation is
 *   what makes it VM-testable without Android or game files.
 * - It does not rasterize fonts itself: [LetterRasterizer] is a
 *   thin interface, implemented on Android by
 *   UnityPersianFontRasterizer (Paint/Typeface/Bitmap) and in VM
 *   tests by a synthetic rasterizer. Real glyph shapes on device
 *   are therefore ground truth for rendering, while VM tests prove
 *   shaping / mapping / ranking / baking / fallback / coverage /
 *   patching logic.
 */
internal object UnityPersianApplyPipeline {

    /** One term to translate, in the order of the I2 table. */
    data class PersianTermInput(
        val termKey: String,
        val persianText: String,
    )

    /**
     * Renders one letter glyph. [letterPua] is the PUA code to bake
     * (E000..E0FF); [originalCodepoint] is the Arabic / presentation-
     * form codepoint whose glyph to render (e.g. 0xFB57 for E006,
     * 0xFEF6 for E080, 0x060C for E000). [pointSize] is the TMP
     * FaceInfo pointSize (e.g. 84f). Return a LetterInput whose
     * [UnitySdfBaker.LetterInput.pua] equals [letterPua] exactly;
     * a different PUA is a rasterizer bug and is rejected. Throw
     * [PersianRasterizerException] (not a generic exception) when
     * the selected font has no ink for this glyph: the pipeline
     * converts that to NotReady ("choose a Persian font that
     * covers ..."), instead of crashing or baking an empty glyph.
     */
    interface LetterRasterizer {
        fun rasterize(
            letterPua: Int,
            originalCodepoint: Int,
            pointSize: Float,
        ): UnitySdfBaker.LetterInput
    }

    /** Thrown by a rasterizer when the font has no glyph for a letter. */
    class PersianRasterizerException(message: String) : Exception(message)

    /** Counts for UI display and for the final report. */
    data class PipelineStats(
        val distinctWordCount: Int,
        val totalWordOccurrences: Int,
        val bakedWordOccurrences: Int,
        val letterFallbackOccurrences: Int,
        val overflowWordCount: Int,
        val bakedGlyphCount: Int,
        val letterGlyphCount: Int,
        val atlasSize: Int,
        val originalAtlasSize: Int,
        val atlasGrowthBytes: Long,
        val atlasGrowthText: String,
        val unpackedWordPuas: List<Int>,
        val bakeAttempts: Int,
    )

    sealed class PipelineResult {
        abstract val finalTerms: List<String>
        abstract val issues: List<String>
        abstract val stats: PipelineStats?

        data class Ready(
            override val finalTerms: List<String>,
            val patchedTmp: UVal.Struct,
            val patchedTexture: UVal.Struct,
            val glyphIndexByPua: Map<Int, Int>,
            override val stats: PipelineStats,
        ) : PipelineResult() {
            override val issues: List<String> = emptyList()
        }

        data class NotReady(
            override val finalTerms: List<String>,
            override val issues: List<String>,
            override val stats: PipelineStats?,
        ) : PipelineResult()
    }

    private val EMPTY_STATS = PipelineStats(
        distinctWordCount = 0,
        totalWordOccurrences = 0,
        bakedWordOccurrences = 0,
        letterFallbackOccurrences = 0,
        overflowWordCount = 0,
        bakedGlyphCount = 0,
        letterGlyphCount = 0,
        atlasSize = 0,
        originalAtlasSize = 0,
        atlasGrowthBytes = 0,
        atlasGrowthText = "0.0 KB",
        unpackedWordPuas = emptyList(),
        bakeAttempts = 0,
    )

    private fun numField(struct: UVal.Struct, field: String): Long {
        val v = struct.fields[field]
        require(v is UVal.Num) { "Persian pipeline: expected Num for $field, got ${v?.javaClass?.simpleName}" }
        return v.value
    }

    private fun structField(struct: UVal.Struct, field: String): UVal.Struct {
        val v = struct.fields[field]
        require(v is UVal.Struct) { "Persian pipeline: expected Struct for $field, got ${v?.javaClass?.simpleName}" }
        return v
    }

    /**
     * Run the full pipeline. [baseTmp] / [baseTexture] are decoded
     * TMP_FontAsset / atlas Texture2D objects (same game, same
     * Unity/TMP version as the trees that will encode the patched
     * objects). [rasterizer] renders distinct letters on demand.
     * This function does no I/O and no Android calls, so it is
     * VM-testable; the caller does file / bundle / I2 work.
     */
    fun preparePersianTerms(
        terms: List<PersianTermInput>,
        baseTmp: UVal.Struct,
        baseTexture: UVal.Struct,
        rasterizer: LetterRasterizer,
    ): PipelineResult {
        // ---- 1. Input validation (user-fixable -> NotReady) ----
        val puaInInput = ArrayList<String>()
        for (term in terms) {
            for (ch in term.persianText) {
                if (ch.code in 0xE000..0xF8FF) {
                    puaInInput.add(
                        "${term.termKey}: PUA U+${ch.code.toString(16).uppercase().padStart(4, '0')} " +
                            "(type logical Persian, not cipher/PUA text)",
                    )
                    break
                }
            }
        }
        if (puaInInput.isNotEmpty()) {
            return PipelineResult.NotReady(
                finalTerms = terms.map { it.persianText },
                issues = puaInInput,
                stats = EMPTY_STATS,
            )
        }
        val tmpFields = baseTmp.fields
        val origGlyphTable = tmpFields["m_GlyphTable"]
        val origCharTable = tmpFields["m_CharacterTable"]
        if (origGlyphTable !is UVal.Arr || origGlyphTable.items.isEmpty() ||
            origCharTable !is UVal.Arr || origCharTable.items.isEmpty()
        ) {
            return PipelineResult.NotReady(
                finalTerms = terms.map { it.persianText },
                issues = listOf(
                    "Base TMP font asset has an empty glyph or character table; " +
                        "cannot copy table templates (wrong object, or not a TMP_FontAsset)",
                ),
                stats = EMPTY_STATS,
            )
        }
        val origUnicodes = HashSet<Int>()
        for (item in origCharTable.items) {
            val c = item as? UVal.Struct ?: continue
            val u = c.fields["m_Unicode"] as? UVal.Num ?: continue
            origUnicodes.add(u.value.toInt())
        }
        val alreadyPua = origUnicodes.filter { it in 0xE000..0xF8FF }
        if (alreadyPua.isNotEmpty()) {
            return PipelineResult.NotReady(
                finalTerms = terms.map { it.persianText },
                issues = listOf(
                    "Base TMP fallback already contains PUA glyphs " +
                        "(${alreadyPua.take(5).joinToString(", ") { "U+" + it.toString(16).uppercase().padStart(4, '0') }}" +
                        (if (alreadyPua.size > 5) ", ... (${alreadyPua.size} total)" else "") + "); " +
                        "v1 only bakes fresh: start from an original fallback, " +
                        "not an already-PUA-baked one (incremental baking is future work)",
                ),
                stats = EMPTY_STATS,
            )
        }
        val origAtlasSize = numField(baseTmp, "m_AtlasWidth").toInt()
        val origAtlasHeight = numField(baseTmp, "m_AtlasHeight").toInt()
        if (origAtlasSize != origAtlasHeight) {
            return PipelineResult.NotReady(
                finalTerms = terms.map { it.persianText },
                issues = listOf("Base TMP atlas is not square ($origAtlasSize x $origAtlasHeight); v1 only handles square atlases"),
                stats = EMPTY_STATS,
            )
        }
        val pad = numField(baseTmp, "m_AtlasPadding").toInt()
        val faceInfo = structField(baseTmp, "m_FaceInfo")
        val pointSize = numField(faceInfo, "m_PointSize").toInt()
        val texFields = baseTexture.fields
        val origImage = texFields["image data"]
        if (origImage !is UVal.Bytes) {
            return PipelineResult.NotReady(
                finalTerms = terms.map { it.persianText },
                issues = listOf("Base atlas Texture2D has no 'image data' field; wrong Unity version or wrong object"),
                stats = EMPTY_STATS,
            )
        }
        val origAtlasBytes = origImage.data
        if (origAtlasBytes.size != origAtlasSize * origAtlasSize) {
            // Non-Alpha8 base atlas (e.g. RGBA) would have 4x bytes;
            // the patcher will reject it anyway, but report it here
            // with a clearer message, before any baking work.
            return PipelineResult.NotReady(
                finalTerms = terms.map { it.persianText },
                issues = listOf(
                    "Base atlas image size ${origAtlasBytes.size} != $origAtlasSize x $origAtlasSize " +
                        "(expected Alpha8, 1 byte/pixel); v1 only handles Alpha8 SDF atlases",
                ),
                stats = EMPTY_STATS,
            )
        }

        // ---- 2. Shape + 3. Map (one batch, shared word codes) ----
        val shapeOptions = UnityTextTransformOptions(
            shapeArabic = true,
            reverseBidi = true,
            normalizePersian = true,
        )
        val shaped = terms.map { UnityTextShaper.transform(it.persianText, shapeOptions) }
        val batch = UnityPersianWordMapper.mapShapedTerms(shaped)
        if (batch.unmappedCodepoints.isNotEmpty()) {
            val issues = batch.unmappedCodepoints.sorted().map { code ->
                "Unmapped codepoint U+${code.toString(16).uppercase().padStart(4, '0')} " +
                    "(logical Arabic the shaper did not shape, or extension block exhausted); " +
                    "remove it from the Persian terms, or it would render as a square"
            }
            return PipelineResult.NotReady(
                finalTerms = batch.mappedTerms,
                issues = issues,
                stats = EMPTY_STATS.copy(
                    distinctWordCount = batch.wordMap.size,
                    overflowWordCount = batch.overflowWords.size,
                    originalAtlasSize = origAtlasSize,
                ),
            )
        }

        // ---- 4. Rank words ----
        val ranked = UnityPersianFrequency.rankWords(batch.mappedTerms, batch.wordMap)
        val totalWordOccurrences = ranked.sumOf { it.frequency }

        // ---- 5. Rasterize distinct letters ----
        // Reverse maps: cipher (PUA -> Arabic form) + extension
        // (extension PUA -> presentation form, this batch's order).
        val cipherReverse = HashMap<Int, Int>()
        for ((arabic, pua) in UnityPersianWordMapper.ARABIC_TO_PUA) {
            cipherReverse[pua] = arabic
        }
        val extensionReverse = HashMap<Int, Int>()
        for ((form, extPua) in batch.extensionLetters) {
            extensionReverse[extPua] = form
        }
        fun originalCodepointFor(letterPua: Int): Int? {
            return cipherReverse[letterPua] ?: extensionReverse[letterPua]
        }
        val letterPuas = LinkedHashSet<Int>()
        for (core in batch.wordMap.keys) {
            for (ch in core) letterPuas.add(ch.code)
        }
        for (core in batch.overflowWords) {
            for (ch in core) letterPuas.add(ch.code)
        }
        val rasterized = LinkedHashMap<Int, UnitySdfBaker.LetterInput>()
        for (pua in letterPuas.sorted()) {
            val orig = originalCodepointFor(pua)
            if (orig == null) {
                return PipelineResult.NotReady(
                    finalTerms = batch.mappedTerms,
                    issues = listOf(
                        "Letter PUA U+${pua.toString(16).uppercase().padStart(4, '0')} has no known original codepoint " +
                            "(not in cipher table, not in this batch's extension letters); " +
                            "this is a mapper/rasterizer mismatch, not a font problem",
                    ),
                    stats = EMPTY_STATS.copy(
                        distinctWordCount = batch.wordMap.size,
                        totalWordOccurrences = totalWordOccurrences,
                        overflowWordCount = batch.overflowWords.size,
                        originalAtlasSize = origAtlasSize,
                    ),
                )
            }
            val letter = try {
                rasterizer.rasterize(pua, orig, pointSize.toFloat())
            } catch (e: PersianRasterizerException) {
                return PipelineResult.NotReady(
                    finalTerms = batch.mappedTerms,
                    issues = listOf(
                        "Font has no glyph for letter PUA U+${pua.toString(16).uppercase().padStart(4, '0')} " +
                            "(original U+${orig.toString(16).uppercase().padStart(4, '0')}): ${e.message} " +
                            "Choose a Persian font that covers all shaped forms (e.g. Vazirmatn, IRANSansX).",
                    ),
                    stats = EMPTY_STATS.copy(
                        distinctWordCount = batch.wordMap.size,
                        totalWordOccurrences = totalWordOccurrences,
                        overflowWordCount = batch.overflowWords.size,
                        originalAtlasSize = origAtlasSize,
                    ),
                )
            }
            require(letter.pua == pua) {
                "Persian pipeline: rasterizer returned PUA ${letter.pua} for requested $pua (rasterizer bug)"
            }
            require(letter.core.size == letter.coreWidth * letter.coreHeight && letter.coreWidth > 0 && letter.coreHeight > 0) {
                "Persian pipeline: rasterizer returned an inconsistent core for PUA $pua " +
                    "(${letter.core.size} vs ${letter.coreWidth}x${letter.coreHeight}; rasterizer bug)"
            }
            rasterized[pua] = letter
        }
        val lettersInPuaOrder = letterPuas.sorted().map { rasterized.getValue(it) }
        val wordsInFreqOrder = ranked.map { freq ->
            UnitySdfBaker.WordInput(
                wordPua = freq.wordPua,
                letters = freq.core.map { rasterized.getValue(it.code) },
            )
        }

        // ---- 6. Bake (letters first, words frequency order) ----
        val allowedSizes = listOf(512, 1024, 2048, 4096)
        val larger = allowedSizes.filter { it > origAtlasSize }
        if (larger.isEmpty()) {
            return PipelineResult.NotReady(
                finalTerms = batch.mappedTerms,
                issues = listOf(
                    "Base atlas is already 4096 (largest proven size); no strip space for new glyphs. " +
                        "v1 cannot bake into a full 4096 atlas (needs free-rect packing, future work).",
                ),
                stats = EMPTY_STATS.copy(
                    distinctWordCount = batch.wordMap.size,
                    totalWordOccurrences = totalWordOccurrences,
                    overflowWordCount = batch.overflowWords.size,
                    originalAtlasSize = origAtlasSize,
                ),
            )
        }
        val recommended = UnityPersianFrequency.recommendAtlasSize(batch.wordMap.size)
        var currentAtlas = larger.firstOrNull { it >= recommended } ?: larger.last()
        val rightPadPx = UnitySdfBaker.scaledRightPad(
            basePadPx = 30,
            basePointSize = 84f,
            targetPointSize = pointSize.toFloat(),
        )
        var baked: UnitySdfBaker.BakedAtlas? = null
        var bakeAttempts = 0
        while (true) {
            bakeAttempts++
            val attempt = UnitySdfBaker.bakeWordsAndLetters(
                letters = lettersInPuaOrder,
                words = wordsInFreqOrder,
                atlasSize = currentAtlas,
                originalAtlasSize = origAtlasSize,
                originalAtlasBytes = origAtlasBytes,
                pad = pad,
                rightPadPx = rightPadPx,
                preserveInputOrder = true,
            )
            baked = attempt
            if (attempt.unpacked.isEmpty()) break
            val unpackedOcc = attempt.unpacked.sumOf { pua ->
                ranked.firstOrNull { it.wordPua == pua }?.frequency ?: 0
            }
            val nextLarger = larger.firstOrNull { it > currentAtlas }
            if (nextLarger == null) break
            if (!UnityPersianFrequency.shouldRetryAt4096(currentAtlas, unpackedOcc, totalWordOccurrences)) break
            currentAtlas = nextLarger
        }
        val finalBaked = baked!!
        val unpackedSet = finalBaked.unpacked.toSet()

        // ---- 7. Letter fallback + coverage gate ----
        val hybridTerms = UnityPersianFrequency.applyLetterFallback(
            batch.mappedTerms,
            unpackedSet,
            batch.wordMap,
        )
        val hybridStats = UnityPersianFrequency.hybridStats(
            batch.mappedTerms,
            unpackedSet,
            batch.wordMap,
        )
        val bakedPuas = finalBaked.glyphs.map { it.pua }.toSet()
        val coverage = UnityPersianFrequency.checkBakeCoverage(hybridTerms, bakedPuas, batch.wordMap)
        val stats = PipelineStats(
            distinctWordCount = batch.wordMap.size,
            totalWordOccurrences = totalWordOccurrences,
            bakedWordOccurrences = hybridStats.bakedWordOccurrences,
            letterFallbackOccurrences = hybridStats.letterFallbackOccurrences,
            overflowWordCount = batch.overflowWords.size,
            bakedGlyphCount = finalBaked.glyphs.size,
            letterGlyphCount = lettersInPuaOrder.size,
            atlasSize = finalBaked.atlasSize,
            originalAtlasSize = origAtlasSize,
            atlasGrowthBytes = UnityPersianFrequency.estimateAtlasGrowthBytes(
                finalBaked.atlasSize,
                origAtlasSize,
            ),
            atlasGrowthText = UnityPersianFrequency.formatBytes(
                UnityPersianFrequency.estimateAtlasGrowthBytes(
                    finalBaked.atlasSize,
                    origAtlasSize,
                ),
            ),
            unpackedWordPuas = finalBaked.unpacked,
            bakeAttempts = bakeAttempts,
        )
        if (!coverage.isReady) {
            return PipelineResult.NotReady(
                finalTerms = hybridTerms,
                issues = coverage.issues,
                stats = stats,
            )
        }

        // ---- 8. Patch TMP + Texture (only when ready) ----
        val patchedTmp = UnityPersianTmpPatcher.patchTmpFontAsset(
            baseTmp,
            finalBaked,
            bakerPad = pad,
            pointSize = pointSize,
        )
        val patchedTexture = UnityPersianTmpPatcher.patchAtlasTexture(
            baseTexture,
            finalBaked.atlasBytes,
            finalBaked.atlasSize,
        )
        return PipelineResult.Ready(
            finalTerms = hybridTerms,
            patchedTmp = patchedTmp.tmp,
            patchedTexture = patchedTexture.texture,
            glyphIndexByPua = patchedTmp.glyphIndexByPua,
            stats = stats,
        )
    }
}
