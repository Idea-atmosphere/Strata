package dev.cluvex.strata.core.unity

/**
 * Phase 4 core (pure Kotlin, no Android deps): frequency-ordered
 * word selection, letter-PUA hybrid fallback, and bake-coverage
 * readiness for the Strata Persian translation mode.
 *
 * Problem: a game's vocabulary can exceed both PUA word-code space
 * (5,888 codes, E200..F8FF, guarded in UnityPersianWordMapper) and
 * atlas space (2048 fits ~70 Bendy warning words with letters;
 * 4096 fits roughly 4x that, and is the largest atlas proven to
 * render in-game via CaviarDreams). Emitting a word-PUA code with
 * no glyph renders as a square in-game, which is worse than a
 * letter-rendered word (readable, with joining gaps). So for large
 * vocabularies, the correct behaviour is:
 *   1. Rank words by total occurrence count (descending), with
 *      first-appearance order as the deterministic tie-break.
 *   2. Bake letters FIRST (always, in PUA order), then words in
 *      frequency order, with UnitySdfBaker preserveInputOrder=true,
 *      so frequent words win atlas space and letters are never
 *      evicted by words (letter fallback depends on letters).
 *   3. Words the baker reports as unpacked are replaced in the
 *      final I2 strings by their letter-PUA cores
 *      ([applyLetterFallback]), never shipped as glyphless codes.
 *   4. [checkBakeCoverage] verifies the final strings contain NO
 *      glyphless word codes, NO unbaked letter PUAs, NO logical
 *      Arabic, and NO diacritics (user rule). Only then is the
 *      translation READY; otherwise Apply must be blocked, not
 *      warned-and-shipped, because a shipped square is a broken
 *      game, while a blocked Apply is a clear, fixable state.
 *
 * Scope and non-goals (v1):
 * - This module does NOT render fonts and does NOT decide atlas
 *   fit by itself: fit depends on rendered glyph sizes, which only
 *   the baker knows after rendering. Ranking here is by frequency
 *   only; the baker's unpacked list is the final fit decision, and
 *   [applyLetterFallback] + [checkBakeCoverage] close the loop.
 * - Legacy Bendy header words (E100..E105) are NOT ranked here:
 *   they are not assigned by the mapper (they are Bendy-specific,
 *   pre-baked in the warning fallback), so they are ignored by
 *   ranking and must be included in the baked set supplied to
 *   [checkBakeCoverage] if the final strings contain them (for
 *   Bendy, when the base is a warning-full fallback). For fresh
 *   games, final strings should not contain E100..E105 at all.
 * - Single-letter words are still word-baked, not optimised away:
 *   word glyphs carry the 30 px right pad that compensates Bendy's
 *   fixed right-edge loss, while letter glyphs do not, so a
 *   single-letter word as a word glyph renders more correctly
 *   than as a letter glyph. Do not "optimise" this away.
 * - [recommendAtlasSize] and [shouldRetryAt4096] are heuristics,
 *   documented as estimates, not guarantees. The baker's unpacked
 *   list is the ground truth; retry logic is a convenience, not a
 *   replacement for checking unpacked.
 */
object UnityPersianFrequency {

    /** One mapper-assigned word, ranked for baking. */
    data class WordFrequency(
        val wordPua: Int,
        /** Letter-PUA core (visual order), from the mapper's wordMap. */
        val core: String,
        /** Total occurrences across all mapped terms (not distinct terms). */
        val frequency: Int,
        /** Index in wordMap iteration order (mapper first-appearance order). */
        val firstAppearance: Int,
    )

    /** Result of [checkBakeCoverage]. */
    data class BakeReadiness(
        val isReady: Boolean,
        val issues: List<String>,
        /** Word codes in final terms that have a glyph (baked). */
        val bakedWordOccurrences: Int,
    )

    /** Hybrid counts: (baked word occurrences, letter-fallback word occurrences). */
    data class HybridStats(
        val bakedWordOccurrences: Int,
        val letterFallbackOccurrences: Int,
    )

    /**
     * Counts hybrid outcomes BEFORE applying fallback: word codes in
     * [mappedTerms] split by whether they are in [unpackedWordPuas]
     * (letter fallback) or not (baked, assuming the baker packed all
     * non-unpacked words). Use this for UI counts/percent, since
     * post-hybrid terms cannot distinguish a replaced word's letters
     * from originally-letter terms. Codes not in [wordMap] are
     * ignored here (legacy words, letters, etc., are not hybrid
     * candidates).
     */
    fun hybridStats(
        mappedTerms: List<String>,
        unpackedWordPuas: Set<Int>,
        wordMap: Map<String, Int>,
    ): HybridStats {
        val wordPuas = wordMap.values.toSet()
        var baked = 0
        var fallback = 0
        for (term in mappedTerms) {
            for (ch in term) {
                val code = ch.code
                if (code in wordPuas) {
                    if (code in unpackedWordPuas) fallback++ else baked++
                }
            }
        }
        return HybridStats(baked, fallback)
    }

    /**
     * Ranks mapper-assigned words by frequency (descending), with
     * first-appearance order (ascending) as the tie-break.
     *
     * Only codes present in [wordMap] are ranked. Letter PUAs,
     * extension letters, punctuation, spaces, and legacy words
     * (E100..E105) in [mappedTerms] are ignored here: letters are
     * baked separately (always, first), legacy words are assumed
     * pre-baked, and the rest are not words.
     *
     * @throws IllegalArgumentException if [wordMap] is not bijective
     *   (same word-PUA code for two different cores), which would
     *   make hybrid fallback ambiguous. That is a mapper bug, not a
     *   ranking choice, so it fails fast instead of shipping a
     *   wrong letter replacement.
     */
    fun rankWords(mappedTerms: List<String>, wordMap: Map<String, Int>): List<WordFrequency> {
        val puaToCore = HashMap<Int, String>(wordMap.size)
        val firstAppearance = HashMap<Int, Int>(wordMap.size)
        for ((index, entry) in wordMap.entries.withIndex()) {
            val (core, pua) = entry
            val previous = puaToCore.putIfAbsent(pua, core)
            require(previous == null || previous == core) {
                "Word map is not bijective: word-PUA U+%04X maps to two cores".format(pua)
            }
            firstAppearance[pua] = index
        }
        val counts = HashMap<Int, Int>()
        for (term in mappedTerms) {
            for (ch in term) {
                val code = ch.code
                if (code in puaToCore) {
                    counts[code] = (counts[code] ?: 0) + 1
                }
            }
        }
        val ranked = ArrayList<WordFrequency>(counts.size)
        for ((pua, frequency) in counts) {
            ranked.add(
                WordFrequency(
                    wordPua = pua,
                    core = puaToCore.getValue(pua),
                    frequency = frequency,
                    firstAppearance = firstAppearance.getValue(pua),
                ),
            )
        }
        ranked.sortWith(
            compareByDescending<WordFrequency> { it.frequency }
                .thenBy { it.firstAppearance },
        )
        return ranked
    }

    /**
     * Replaces unpacked word-PUA codes in [mappedTerms] by their
     * letter-PUA cores, preserving everything else exactly
     * (spaces, newlines, punctuation, baked word codes, letter
     * PUAs, Latin text, digits).
     *
     * Unpacked codes with no core in [wordMap] are left as-is:
     * they are not mapper-assigned words (e.g. legacy words), so
     * this module does not know their letters, and keeping them
     * preserves whatever glyph they already had. Callers must only
     * pass baker-unpacked codes (which are always in wordMap) for
     * correct hybrid behaviour; [checkBakeCoverage] will flag any
     * remaining glyphless word code afterwards, so a mistake here
     * cannot silently ship a square.
     */
    fun applyLetterFallback(
        mappedTerms: List<String>,
        unpackedWordPuas: Set<Int>,
        wordMap: Map<String, Int>,
    ): List<String> {
        if (unpackedWordPuas.isEmpty()) return mappedTerms
        val puaToCore = HashMap<Int, String>(wordMap.size)
        for ((core, pua) in wordMap) {
            puaToCore.putIfAbsent(pua, core)
        }
        return mappedTerms.map { term ->
            val out = StringBuilder(term.length)
            for (ch in term) {
                val code = ch.code
                if (code in unpackedWordPuas) {
                    val core = puaToCore[code]
                    if (core != null) out.append(core) else out.append(ch)
                } else {
                    out.append(ch)
                }
            }
            out.toString()
        }
    }

    /**
     * Verifies final (post-hybrid) terms contain no glyphless codes.
     *
     * [bakedGlyphPuas] must be the FINAL merged glyph set: letters
     * baked in this run + words baked in this run + any PUA glyphs
     * already present in the base fallback (e.g. Bendy legacy header
     * words, when the base is a warning-full fallback). Supplying
     * only the current run's glyphs while final terms contain
     * legacy words will (correctly) report those legacy words as
     * missing, which is safe: it blocks Apply instead of shipping
     * a square, and the caller fixes the baked set, not the check.
     *
     * Checks, per character in every term:
     * - Letter PUA (E000..E0FF, incl. extension letters, comma E000
     *   and question mark E001) without a glyph -> issue.
     * - Word PUA (E100..E105 legacy, or E200+ in [wordMap], or any
     *   other code >= 0xE200 that looks like a word code) without
     *   a glyph -> issue.
     * - Logical Arabic (0600..06FF, 0750..077F, 08A0..08FF) or
     *   Arabic combining marks (064B..065F, 0670) or PUA diacritics
     *   (E002..E005) -> issue (input was not shaped/stripped, or
     *   mapper was bypassed; user rule: no diacritics in new work).
     * - Other characters (spaces, newlines, punctuation, Latin,
     *   digits, placeholders) are never issues here; they render
     *   through the game's own font, not through the PUA fallback.
     */
    fun checkBakeCoverage(
        finalTerms: List<String>,
        bakedGlyphPuas: Set<Int>,
        wordMap: Map<String, Int>,
    ): BakeReadiness {
        val issues = ArrayList<String>()
        var bakedWords = 0
        for (term in finalTerms) {
            for (ch in term) {
                val code = ch.code
                when {
                    code in 0xE002..0xE005 -> {
                        // PUA diacritics are ALWAYS an issue in new
                        // work (user rule: strip diacritics), even if
                        // a glyph exists in an old cipher fallback.
                        issues.add("Diacritic PUA U+%04X present (user rule: strip diacritics)".format(code))
                    }
                    code in 0xE000..0xF8FF -> {
                        // Any BMP PUA code without a glyph renders as
                        // a square in-game (PUA has no system-font
                        // glyphs). Letter, word, legacy, or otherwise
                        // unused PUA: all must be baked, or flagged.
                        if (code !in bakedGlyphPuas) {
                            val kind = when {
                                code in 0xE000..0xE0FF -> "Letter PUA"
                                code in 0xE100..0xE105 || code in wordMap.values || code >= 0xE200 -> "Word PUA"
                                else -> "PUA"
                            }
                            issues.add("$kind U+%04X has no glyph (would render as square)".format(code))
                        } else if (code in 0xE100..0xE105 || code in wordMap.values || code >= 0xE200) {
                            bakedWords++
                        }
                    }
                    code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0x08A0..0x08FF -> {
                        // Includes Arabic combining marks (064B..065F,
                        // 0670), which are inside 0600..06FF.
                        issues.add("Unmapped Arabic U+%04X in final terms (input was not shaped/stripped)".format(code))
                    }
                    else -> {
                        // Spaces, punctuation, Latin, digits, etc.: fine.
                    }
                }
            }
        }
        // Deduplicate issues (same missing PUA in many terms = one issue).
        val uniqueIssues = issues.distinct()
        return BakeReadiness(
            isReady = uniqueIssues.isEmpty(),
            issues = uniqueIssues,
            bakedWordOccurrences = bakedWords,
        )
    }

    /**
     * Estimated fallback-file growth from enlarging the atlas:
     * new atlas bytes minus original atlas bytes (1 byte per pixel,
     * grayscale SDF). Glyph/character table growth is small compared
     * to atlas bytes (tens of KB for hundreds of glyphs) and is NOT
     * included here; callers should present this as "at least" /
     * "about", not as an exact file size.
     */
    fun estimateAtlasGrowthBytes(atlasSize: Int, originalAtlasSize: Int): Long {
        require(atlasSize > 0 && originalAtlasSize >= 0 && originalAtlasSize <= atlasSize)
        return atlasSize.toLong() * atlasSize - originalAtlasSize.toLong() * originalAtlasSize
    }

    /** Human-readable byte count (1024-based, 1 decimal for MB/KB). */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 0 -> "-%s".format(formatBytes(-bytes))
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Heuristic atlas recommendation by unique word count only.
     * 2048 for small vocabularies (<= 100 unique words, Bendy
     * warning scale), 4096 otherwise. This is an estimate: actual
     * fit depends on rendered word sizes, which only the baker
     * knows. Never recommends > 4096: 4096 is the largest atlas
     * proven to render in-game (CaviarDreams), and larger atlases
     * would be 64 MB+ and may exceed mobile GPU texture limits.
     * For very large vocabularies, the answer is 4096 + letter
     * fallback, not a larger atlas.
     */
    fun recommendAtlasSize(uniqueWordCount: Int): Int {
        return if (uniqueWordCount <= 100) 2048 else 4096
    }

    /**
     * Whether to retry baking at 4096 after a 2048 attempt unpacked
     * a large share of word occurrences. Threshold: more than 20%
     * of total word occurrences unpacked means frequent words are
     * falling back to letters, which is a readability loss worth
     * a larger atlas for. Below that, accept 2048 + fallback (the
     * unpacked words are rare, so letter rendering affects few
     * on-screen words). If already at 4096, never retry (no larger
     * proven atlas); return false.
     */
    fun shouldRetryAt4096(
        currentAtlasSize: Int,
        unpackedOccurrences: Int,
        totalWordOccurrences: Int,
    ): Boolean {
        if (currentAtlasSize >= 4096) return false
        if (totalWordOccurrences <= 0) return false
        return unpackedOccurrences * 5 > totalWordOccurrences
    }
}
