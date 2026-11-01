package dev.cluvex.strata.core.unity

/**
 * Persian word mapping for the Strata game-translation mode.
 *
 * This is a pure-Kotlin, Android-free module. It does NOT render fonts and
 * does NOT patch bundles; it only transforms text, so it can be unit-tested
 * on the JVM and reused by the Android UI layer and by the later SDF baker.
 *
 * Pipeline position (Persian mode, after the existing shaper):
 *   logical Persian typed by the user
 *     -> UnityTextShaper.transform (shapeArabic + Bidi + normalize ON,
 *        output is visual order, presentation forms, ZWNJ already dropped)
 *     -> UnityPersianWordMapper.mapShapedTerms (this file)
 *     -> I2 bundle values (word-PUA strings, stored via UnityLocalization)
 *     -> SDF baker (later phase) bakes one glyph per word-PUA code into
 *        the game's TMP fallback atlas, with the same right-pad rule that
 *        fixed Bendy's right-edge loss (30 px at 84 pt).
 *
 * Why PUA + word glyphs at all: on shipped Unity games, Arabic codepoints
 * are filtered at render time (proven on Bendy: Latin/Cyrillic render,
 * Arabic shapes render only when reached through a Latin unicode, and
 * letter-level PUA glyphs render while Arabic-unicode glyphs show as
 * squares). Plain Persian import therefore looks right in the editor and
 * shows squares in the game. PUA remapping bypasses the filter, and
 * whole-word glyphs bypass TMP's inter-letter layout, which was the
 * cause of Bendy's joining gaps (word-level bake fixed joining; a 30 px
 * blank right pad fixed the fixed ~15-17 px right-edge loss).
 *
 * Input contract:
 * - Input strings must already be shaped (presentation forms, visual
 *   order). Logical (unshaped) Persian must NOT be passed here; shape it
 *   first with UnityTextShaper, otherwise letter forms will be wrong.
 * - All codepoints handled here are in the BMP, so Char iteration is
 *   sufficient. Supplementary codepoints (e.g. emoji) are treated as
 *   non-letter separators and passed through unchanged.
 *
 * Output contract:
 * - Diacritics are STRIPPED (user rule, 2026-11-01: no kasra/fatha/damma
 *   etc. in new word-baked work). This happens at both stages: Arabic
 *   combining marks (U+064B..U+065F, U+0670) are removed before remap,
 *   and PUA diacritics (E002..E005) are removed whether they result
 *   from remap or were already present in a PUA input.
 * - Persian/Arabic-Indic digits (U+06F0..U+06F9, U+0660..U+0669) are
 *   converted to Latin digits ('0'..'9'). Latin digits render through the
 *   game's own font (they are not filtered), while Persian digits would
 *   be filtered as Arabic. This is a v1 readability trade-off, documented
 *   here; baking Persian digit glyphs is a later option, not a silent
 *   square.
 * - ZWNJ (U+200C) and ZWJ (U+200D) are dropped: the shaper has already
 *   encoded the joining decision into the presentation forms, so keeping
 *   them would only add a zero-width run break.
 * - Letter runs (maximal runs of letter-PUA codepoints E000..E07F, plus
 *   extension letters E080..E0FF assigned below) are replaced by a single
 *   word-PUA character, starting at 0xE200, in first-appearance order
 *   across the whole batch. The same word core in different terms gets
 *   the same word-PUA code, so the baker bakes it once and reuses it.
 * - Everything else (spaces, newlines, punctuation that is not in the
 *   letter-PUA block, Latin text, digits) is preserved byte-for-byte,
 *   including multi-newline paragraph separators.
 * - E000 (Arabic comma) and E001 (Arabic question mark) are in the
 *   letter-PUA block and are therefore included in a word run when
 *   attached to a word (e.g. a word ending in a comma). That is
 *   intentional: they were baked as glyphs in the Bendy cipher, and
 *   compositing them into the word glyph keeps them attached with
 *   correct spacing. A standalone comma/question mark (no letters
 *   adjacent) is preserved as its PUA character, not word-mapped.
 *
 * Atlas-fit limitation (v1, documented, not hidden):
 * - This mapper assigns a word-PUA code to every distinct word core,
 *   up to the PUA word block limit (E200..F8FF, 5,888 codes; cores
 *   beyond that stay as letter-PUA, see overflowWords). Atlas size
 *   is a separate limit: the baker phase must check fit and, when
 *   the vocabulary exceeds the atlas (2048 fits ~70 words for Bendy's
 *   warning; 4096 fits roughly 4x that, and CaviarDreams' own 4096 atlas
 *   proves 4096 renders in-game), fall back to letter-PUA rendering for
 *   overflow words instead of emitting a word-PUA code with no glyph
 *   (which would render as a square). Frequency-ordered packing plus
 *   that letter fallback is UnityPersianFrequency (Phase 4 core);
 *   until then, v1 scope is small vocabularies (menus, warnings,
 *   short UI tables), not a full game's subtitle set at once.
 *
 * Legacy compatibility:
 * - The 128-entry ARABIC_TO_PUA table below is EXACTLY the Bendy cipher
 *   table (E000..E07F, in PUA order), including 061B -> E07E and
 *   FEF5 -> E07F. Existing Bendy cipher texts depend on these
 *   assignments; they must never be reordered or changed.
 * - New letter forms not in that table are assigned extension codes
 *   E080..E0FF in first-appearance order, per mapper instance (per
 *   batch) — but ONLY for presentation forms (FB50..FEFF). Logical
 *   Arabic letters (0600..06FF, etc.) that are not cipher entries,
 *   digits, or diacritics are NOT assigned a glyph: they mean the input
 *   was not shaped, so they are reported in unmappedCodepoints and
 *   left as-is, so the caller rejects the batch instead of baking a
 *   wrong isolated form. They do not collide with word codes (E200+)
 *   or with Bendy's legacy header words (E100..E105, which are
 *   Bendy-specific and are NOT assigned by this general mapper).
 */
object UnityPersianWordMapper {

    /** First word-PUA code assigned by this mapper. */
    const val WORD_PUA_START: Int = 0xE200

    /**
     * Last word-PUA code this mapper will assign. The BMP private-use
     * area ends at 0xF8FF, so E200..F8FF holds 5,888 word codes. Past
     * that, assigning codes would spill into CJK Compatibility
     * (0xF900+) or, past 0xFFFF, truncate via Char to garbage/null.
     * Word cores beyond this limit are NOT word-mapped: they stay as
     * letter-PUA in the output (see [WordSession.overflowWords]) and
     * must be counted as letter-fallback, never as squares. For
     * realistic game vocabularies this limit is far away (Bendy's
     * warning is 70 words, its subtitles ~1-2k words), but a full
     * game's subtitle set can exceed it, so the guard must exist
     * before frequency packing, not after a silent overflow.
     */
    const val WORD_PUA_END: Int = 0xF8FF

    /** First extension-letter PUA code (new letter forms, see above). */
    const val EXTENSION_LETTER_PUA_START: Int = 0xE080

    /** Last extension-letter PUA code (E080..E0FF inclusive). */
    const val EXTENSION_LETTER_PUA_END: Int = 0xE0FF

    /**
     * Bendy cipher table: shaped Arabic codepoint -> letter-PUA codepoint.
     * 128 entries, E000..E07F, in PUA order. DO NOT reorder or change:
     * existing Bendy cipher texts (subtitles + menu) depend on it.
     */
    val ARABIC_TO_PUA: Map<Int, Int> = mapOf(
        0x060C to 0xE000,
        0x061F to 0xE001,
        0x064B to 0xE002,
        0x0650 to 0xE003,
        0x0651 to 0xE004,
        0x0654 to 0xE005,
        0xFB57 to 0xE006,
        0xFB58 to 0xE007,
        0xFB59 to 0xE008,
        0xFB7A to 0xE009,
        0xFB7B to 0xE00A,
        0xFB7C to 0xE00B,
        0xFB7D to 0xE00C,
        0xFB8A to 0xE00D,
        0xFB8B to 0xE00E,
        0xFB8E to 0xE00F,
        0xFB8F to 0xE010,
        0xFB90 to 0xE011,
        0xFB91 to 0xE012,
        0xFB92 to 0xE013,
        0xFB93 to 0xE014,
        0xFB94 to 0xE015,
        0xFB95 to 0xE016,
        0xFBFC to 0xE017,
        0xFBFD to 0xE018,
        0xFBFE to 0xE019,
        0xFBFF to 0xE01A,
        0xFE80 to 0xE01B,
        0xFE81 to 0xE01C,
        0xFE84 to 0xE01D,
        0xFE85 to 0xE01E,
        0xFE86 to 0xE01F,
        0xFE8B to 0xE020,
        0xFE8C to 0xE021,
        0xFE8D to 0xE022,
        0xFE8E to 0xE023,
        0xFE8F to 0xE024,
        0xFE90 to 0xE025,
        0xFE91 to 0xE026,
        0xFE92 to 0xE027,
        0xFE95 to 0xE028,
        0xFE96 to 0xE029,
        0xFE97 to 0xE02A,
        0xFE98 to 0xE02B,
        0xFE9A to 0xE02C,
        0xFE9B to 0xE02D,
        0xFE9C to 0xE02E,
        0xFE9D to 0xE02F,
        0xFE9E to 0xE030,
        0xFE9F to 0xE031,
        0xFEA0 to 0xE032,
        0xFEA1 to 0xE033,
        0xFEA2 to 0xE034,
        0xFEA3 to 0xE035,
        0xFEA4 to 0xE036,
        0xFEA5 to 0xE037,
        0xFEA6 to 0xE038,
        0xFEA7 to 0xE039,
        0xFEA8 to 0xE03A,
        0xFEA9 to 0xE03B,
        0xFEAA to 0xE03C,
        0xFEAB to 0xE03D,
        0xFEAC to 0xE03E,
        0xFEAD to 0xE03F,
        0xFEAE to 0xE040,
        0xFEAF to 0xE041,
        0xFEB0 to 0xE042,
        0xFEB1 to 0xE043,
        0xFEB2 to 0xE044,
        0xFEB3 to 0xE045,
        0xFEB4 to 0xE046,
        0xFEB5 to 0xE047,
        0xFEB6 to 0xE048,
        0xFEB7 to 0xE049,
        0xFEB8 to 0xE04A,
        0xFEB9 to 0xE04B,
        0xFEBA to 0xE04C,
        0xFEBB to 0xE04D,
        0xFEBC to 0xE04E,
        0xFEBD to 0xE04F,
        0xFEBE to 0xE050,
        0xFEBF to 0xE051,
        0xFEC0 to 0xE052,
        0xFEC1 to 0xE053,
        0xFEC2 to 0xE054,
        0xFEC3 to 0xE055,
        0xFEC4 to 0xE056,
        0xFEC6 to 0xE057,
        0xFEC7 to 0xE058,
        0xFEC8 to 0xE059,
        0xFEC9 to 0xE05A,
        0xFECA to 0xE05B,
        0xFECB to 0xE05C,
        0xFECC to 0xE05D,
        0xFECD to 0xE05E,
        0xFECE to 0xE05F,
        0xFECF to 0xE060,
        0xFED0 to 0xE061,
        0xFED1 to 0xE062,
        0xFED2 to 0xE063,
        0xFED3 to 0xE064,
        0xFED4 to 0xE065,
        0xFED5 to 0xE066,
        0xFED6 to 0xE067,
        0xFED7 to 0xE068,
        0xFED8 to 0xE069,
        0xFEDD to 0xE06A,
        0xFEDE to 0xE06B,
        0xFEDF to 0xE06C,
        0xFEE0 to 0xE06D,
        0xFEE1 to 0xE06E,
        0xFEE2 to 0xE06F,
        0xFEE3 to 0xE070,
        0xFEE4 to 0xE071,
        0xFEE5 to 0xE072,
        0xFEE6 to 0xE073,
        0xFEE7 to 0xE074,
        0xFEE8 to 0xE075,
        0xFEE9 to 0xE076,
        0xFEEA to 0xE077,
        0xFEEB to 0xE078,
        0xFEEC to 0xE079,
        0xFEED to 0xE07A,
        0xFEEE to 0xE07B,
        0xFEFB to 0xE07C,
        0xFEFC to 0xE07D,
        0x061B to 0xE07E,
        0xFEF5 to 0xE07F,    )

    /** PUA diacritics in the cipher table (stripped, see class doc). */
    val DIACRITIC_PUA: Set<Int> = setOf(0xE002, 0xE003, 0xE004, 0xE005)

    /** Arabic combining marks stripped before remap (kasra, fatha, damma, etc.). */
    private fun isArabicDiacritic(code: Int): Boolean =
        (code in 0x064B..0x065F) || code == 0x0670

    private fun isLetterPua(code: Int): Boolean =
        code in 0xE000..0xE07F || code in EXTENSION_LETTER_PUA_START..EXTENSION_LETTER_PUA_END

    private fun persianDigitToLatin(code: Int): Char? = when (code) {
        in 0x06F0..0x06F9 -> ('0' + (code - 0x06F0))
        in 0x0660..0x0669 -> ('0' + (code - 0x0660))
        else -> null
    }

    /** Result of mapping one batch of shaped terms. */
    data class MappedBatch(
        /** Mapped terms, in the same order as the input terms. */
        val mappedTerms: List<String>,
        /** Word core (letter-PUA string) -> word-PUA code, first-appearance order. */
        val wordMap: Map<String, Int>,
        /**
         * Shaped Arabic codepoints that had no cipher-table entry and
         * were assigned an extension letter code (E080..). Empty for
         * Bendy's vocabulary. The baker must bake these as letter glyphs.
         */
        val extensionLetters: Map<Int, Int>,
        /**
         * Arabic codepoints that could not be mapped at all (extension
         * block exhausted). These are left as-is in the output and must
         * be reported to the user, never silently shipped (they would
         * render as squares in-game). Empty in all realistic batches:
         * the extension block holds 128 codes and the presentation-form
         * set has fewer than 128 forms outside the Bendy table.
         */
        val unmappedCodepoints: Set<Int>,
        /**
         * Word cores that could NOT get a word-PUA code because the
         * PUA word block (E200..F8FF, 5,888 codes) was exhausted.
         * These cores are left as letter-PUA in [mappedTerms] (not
         * word-mapped, not dropped, not assigned a garbage code), so
         * they render as letters (with joining gaps, but readable),
         * never as squares. Callers must count them as letter
         * fallback, the same as baker-unpacked words.
         */
        val overflowWords: Set<String> = emptySet(),
    )

    /**
     * Maps a batch of shaped terms to word-PUA strings.
     *
     * Word assignments are shared across the whole batch: the same word
     * core always maps to the same word-PUA code, so the baker bakes it
     * once. Callers mapping terms incrementally should keep one mapper
     * instance per game/table and reuse its [WordSession], not create a
     * new mapper per term (a new mapper would restart word codes at
     * 0xE200 and collide with earlier terms).
     */
    fun mapShapedTerms(shapedTerms: List<String>): MappedBatch {
        val session = WordSession()
        val mapped = shapedTerms.map { session.mapTerm(it) }
        return MappedBatch(
            mappedTerms = mapped,
            wordMap = session.wordMap.toMap(),
            extensionLetters = session.extensionLetters.toMap(),
            unmappedCodepoints = session.unmappedCodepoints.toSet(),
            overflowWords = session.overflowWords.toSet(),
        )
    }

    /**
     * Stateful session for one game/table. Holds word assignments and
     * extension-letter assignments so incremental mapping stays
     * consistent. Not thread-safe; use one session per batch, on one
     * thread (the same constraint as Strata's other core mappers).
     */
    class WordSession {
        private val _wordMap = LinkedHashMap<String, Int>()
        private val _extensionLetters = LinkedHashMap<Int, Int>()
        private val _unmapped = LinkedHashSet<Int>()
        private val _overflowWords = LinkedHashSet<String>()
        private var nextWordCode: Int = WORD_PUA_START
        private var nextExtensionCode: Int = EXTENSION_LETTER_PUA_START

        val wordMap: Map<String, Int> get() = _wordMap
        val extensionLetters: Map<Int, Int> get() = _extensionLetters
        val unmappedCodepoints: Set<Int> get() = _unmapped
        val overflowWords: Set<String> get() = _overflowWords

        /** Maps one shaped term, reusing this session's assignments. */
        fun mapTerm(shaped: String): String {
            val pua = remapToPua(shaped)
            val out = StringBuilder(pua.length)
            val run = StringBuilder()
            fun flushRun() {
                if (run.isEmpty()) return
                val core = run.toString()
                run.clear()
                val existing = _wordMap[core]
                if (existing != null) {
                    out.append(existing.toChar())
                } else if (nextWordCode <= WORD_PUA_END) {
                    val assigned = nextWordCode
                    nextWordCode++
                    _wordMap[core] = assigned
                    out.append(assigned.toChar())
                } else {
                    // PUA word block exhausted: leave the core as
                    // letter-PUA (readable with gaps, never a square
                    // or a truncated/garbage code). Record it so the
                    // caller counts it as letter fallback.
                    _overflowWords.add(core)
                    out.append(core)
                }
            }
            for (ch in pua) {
                val code = ch.code
                if (isLetterPua(code)) {
                    run.append(ch)
                } else {
                    flushRun()
                    out.append(ch)
                }
            }
            flushRun()
            return out.toString()
        }

        /**
         * Shaped Arabic -> PUA, with diacritic stripping, digit
         * conversion, and ZWNJ/ZWJ dropping (see class doc). Characters
         * that are not Arabic at all pass through unchanged.
         */
        private fun remapToPua(shaped: String): String {
            val out = StringBuilder(shaped.length)
            for (ch in shaped) {
                val code = ch.code
                when {
                    code == 0x200C || code == 0x200D -> {
                        // Dropped: joining already encoded by the shaper.
                    }
                    isArabicDiacritic(code) -> {
                        // Stripped: user rule, no diacritics in new work.
                    }
                    code in DIACRITIC_PUA -> {
                        // Stripped: covers inputs that were already
                        // PUA-remapped before reaching here (e.g. a
                        // cipher text re-imported through this mapper).
                    }
                    persianDigitToLatin(code) != null -> {
                        out.append(persianDigitToLatin(code))
                    }
                    ARABIC_TO_PUA.containsKey(code) -> {
                        val pua = ARABIC_TO_PUA.getValue(code)
                        if (pua !in DIACRITIC_PUA) out.append(pua.toChar())
                        // PUA diacritics stripped (covers inputs that
                        // were already PUA-remapped before reaching here).
                    }
                    code in 0xFB50..0xFEFF -> {
                        // Presentation form with no cipher entry:
                        // assign an extension letter code, or record it
                        // as unmapped if the extension block is full.
                        // (Deliberately not getOrPut: an overflow code
                        // must NOT be stored as -1 in extensionLetters.)
                        val existing = _extensionLetters[code]
                        if (existing != null) {
                            out.append(existing.toChar())
                        } else if (nextExtensionCode <= EXTENSION_LETTER_PUA_END) {
                            val assigned = nextExtensionCode
                            nextExtensionCode++
                            _extensionLetters[code] = assigned
                            out.append(assigned.toChar())
                        } else {
                            _unmapped.add(code)
                            out.append(ch)
                        }
                    }
                    code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0x08A0..0x08FF -> {
                        // Logical Arabic (or Arabic Supplement/Extended)
                        // that is not a cipher-table entry, digit, or
                        // diacritic: this means the input was NOT shaped
                        // (shaped input contains presentation forms, not
                        // logical letters). Do NOT silently assign it a
                        // glyph (that would bake the wrong, isolated
                        // form); record it as unmapped so the caller
                        // rejects the batch with a clear message.
                        _unmapped.add(code)
                        out.append(ch)
                    }
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }
    }
}
