package dev.cluvex.strata.core.unity

import java.text.Bidi

/**
 * Individually toggleable text transformations applied before [UnityBundleDocument.rebuildTexts]
 * encodes edited strings back into a serialized Unity asset.
 *
 * - [shapeArabic]: converts logical Arabic/Persian characters (`0x0600..0x06FF`) into contextual
 *   Unicode Presentation Forms-A (`0xFB50..0xFDFF`) and Presentation Forms-B (`0xFE70..0xFEFF`),
 *   including mandatory Lam-Alef (`لا`, `لآ`, `لأ`, `لإ`) ligatures and ZWNJ (`\u200C`) boundaries.
 * - [reverseBidi]: converts logical bidirectional text into visual left-to-right order using
 *   [java.text.Bidi] (UAX #9) so renderers without RTL support draw RTL runs right-to-left while
 *   keeping embedded Latin words and digits (`120`, `2022`, `۱۲۳`) left-to-right.
 * - [normalizePersian]: maps Arabic Yeh (`ي`, `0x064A`) and Arabic Kaf (`ك`, `0x0643`) to their
 *   Persian counterparts (`ی`, `0x06CC`; `ک`, `0x06A9`), and Alef Maksura (`ى`, `0x0649`) to
 *   Farsi Yeh. Translators typing on Arabic keyboards otherwise produce Arabic-style glyphs
 *   inside Persian text; normalization runs before shaping so the shaped output always uses
 *   the Persian letter forms.
 */
data class UnityTextTransformOptions(
    val shapeArabic: Boolean = false,
    val reverseBidi: Boolean = false,
    val normalizePersian: Boolean = false,
) {
    val isEnabled: Boolean get() = shapeArabic || reverseBidi || normalizePersian
}

internal object UnityTextShaper {
    private const val ZWNJ = '\u200C'
    private const val ZWJ = '\u200D'
    private const val TATWEEL = '\u0640'
    private const val LAM = '\u0644'

    private enum class JoinType {
        NONE,
        RIGHT,
        DUAL,
        TRANSPARENT,
    }

    private data class LetterForms(
        val isolated: Char,
        val finalForm: Char,
        val initial: Char = isolated,
        val medial: Char = finalForm,
        val joinType: JoinType,
    )

    private val LETTER_TABLE: Map<Char, LetterForms> = buildMap {
        fun right(base: Int, isolated: Int, finalForm: Int) {
            put(base.toChar(), LetterForms(isolated.toChar(), finalForm.toChar(), joinType = JoinType.RIGHT))
        }
        fun dual(base: Int, isolated: Int, finalForm: Int, initial: Int, medial: Int) {
            put(
                base.toChar(),
                LetterForms(
                    isolated = isolated.toChar(),
                    finalForm = finalForm.toChar(),
                    initial = initial.toChar(),
                    medial = medial.toChar(),
                    joinType = JoinType.DUAL,
                ),
            )
        }

        // Standard Arabic letters (Presentation Forms-B: 0xFE70..0xFEFF)
        put('\u0621', LetterForms('\uFE80', '\uFE80', joinType = JoinType.NONE)) // Hamza
        right(0x0622, 0xFE81, 0xFE82) // Alef with Madda
        right(0x0623, 0xFE83, 0xFE84) // Alef with Hamza above
        right(0x0624, 0xFE85, 0xFE86) // Waw with Hamza
        right(0x0625, 0xFE87, 0xFE88) // Alef with Hamza below
        dual(0x0626, 0xFE89, 0xFE8A, 0xFE8B, 0xFE8C) // Yeh with Hamza
        right(0x0627, 0xFE8D, 0xFE8E) // Alef
        dual(0x0628, 0xFE8F, 0xFE90, 0xFE91, 0xFE92) // Beh
        right(0x0629, 0xFE93, 0xFE94) // Teh Marbuta
        dual(0x062A, 0xFE95, 0xFE96, 0xFE97, 0xFE98) // Teh
        dual(0x062B, 0xFE99, 0xFE9A, 0xFE9B, 0xFE9C) // Theh
        dual(0x062C, 0xFE9D, 0xFE9E, 0xFE9F, 0xFEA0) // Jeem
        dual(0x062D, 0xFEA1, 0xFEA2, 0xFEA3, 0xFEA4) // Hah
        dual(0x062E, 0xFEA5, 0xFEA6, 0xFEA7, 0xFEA8) // Khah
        right(0x062F, 0xFEA9, 0xFEAA) // Dal
        right(0x0630, 0xFEAB, 0xFEAC) // Thal
        right(0x0631, 0xFEAD, 0xFEAE) // Reh
        right(0x0632, 0xFEAF, 0xFEB0) // Zain
        dual(0x0633, 0xFEB1, 0xFEB2, 0xFEB3, 0xFEB4) // Seen
        dual(0x0634, 0xFEB5, 0xFEB6, 0xFEB7, 0xFEB8) // Sheen
        dual(0x0635, 0xFEB9, 0xFEBA, 0xFEBB, 0xFEBC) // Sad
        dual(0x0636, 0xFEBD, 0xFEBE, 0xFEBF, 0xFEC0) // Dad
        dual(0x0637, 0xFEC1, 0xFEC2, 0xFEC3, 0xFEC4) // Tah
        dual(0x0638, 0xFEC5, 0xFEC6, 0xFEC7, 0xFEC8) // Zah
        dual(0x0639, 0xFEC9, 0xFECA, 0xFECB, 0xFECC) // Ain
        dual(0x063A, 0xFECD, 0xFECE, 0xFECF, 0xFED0) // Ghain
        dual(0x0640, 0x0640, 0x0640, 0x0640, 0x0640) // Tatweel
        dual(0x0641, 0xFED1, 0xFED2, 0xFED3, 0xFED4) // Feh
        dual(0x0642, 0xFED5, 0xFED6, 0xFED7, 0xFED8) // Qaf
        dual(0x0643, 0xFED9, 0xFEDA, 0xFEDB, 0xFEDC) // Arabic Kaf
        dual(0x0644, 0xFEDD, 0xFEDE, 0xFEDF, 0xFEE0) // Lam
        dual(0x0645, 0xFEE1, 0xFEE2, 0xFEE3, 0xFEE4) // Meem
        dual(0x0646, 0xFEE5, 0xFEE6, 0xFEE7, 0xFEE8) // Noon
        dual(0x0647, 0xFEE9, 0xFEEA, 0xFEEB, 0xFEEC) // Heh
        right(0x0648, 0xFEED, 0xFEEE) // Waw
        dual(0x0649, 0xFEEF, 0xFEF0, 0xFBE8, 0xFBE9) // Alef Maksura
        dual(0x064A, 0xFEF1, 0xFEF2, 0xFEF3, 0xFEF4) // Arabic Yeh

        // Persian / extended Arabic letters (Presentation Forms-A: 0xFB50..0xFDFF)
        right(0x0671, 0xFB50, 0xFB51) // Alef Wasla
        dual(0x0679, 0xFB66, 0xFB67, 0xFB68, 0xFB69) // Tteh
        dual(0x067E, 0xFB56, 0xFB57, 0xFB58, 0xFB59) // Persian Peh (پ)
        dual(0x0686, 0xFB7A, 0xFB7B, 0xFB7C, 0xFB7D) // Persian Tcheh (چ)
        right(0x0688, 0xFB88, 0xFB89) // Ddal
        right(0x0691, 0xFB8C, 0xFB8D) // Rreh
        right(0x0698, 0xFB8A, 0xFB8B) // Persian Jeh (ژ)
        dual(0x06A9, 0xFB8E, 0xFB8F, 0xFB90, 0xFB91) // Persian Keheh (ک)
        dual(0x06AF, 0xFB92, 0xFB93, 0xFB94, 0xFB95) // Persian Gaf (گ)
        dual(0x06BA, 0xFB9E, 0xFB9F, 0xFB9E, 0xFB9F) // Noon Ghunna
        dual(0x06BE, 0xFBAA, 0xFBAB, 0xFBAC, 0xFBAD) // Heh Doachashmee
        right(0x06C0, 0xFBA4, 0xFBA5) // Heh with Yeh above (ۀ)
        dual(0x06C1, 0xFBA6, 0xFBA7, 0xFBA8, 0xFBA9) // Heh Goal
        right(0x06C6, 0xFBD9, 0xFBDA) // Oe
        right(0x06C7, 0xFBD7, 0xFBD8) // U
        right(0x06CB, 0xFBDE, 0xFBDF) // Ve
        dual(0x06CC, 0xFBFC, 0xFBFD, 0xFBFE, 0xFBFF) // Persian Farsi Yeh (ی)
        right(0x06D2, 0xFBAE, 0xFBAF) // Yeh Barree
    }

    private val LAM_ALEF_LIGATURES: Map<Char, Pair<Char, Char>> = mapOf(
        '\u0622' to ('\uFEF5' to '\uFEF6'), // لآ (isolated, final)
        '\u0623' to ('\uFEF7' to '\uFEF8'), // لأ (isolated, final)
        '\u0625' to ('\uFEF9' to '\uFEFA'), // لإ (isolated, final)
        '\u0627' to ('\uFEFB' to '\uFEFC'), // لا (isolated, final)
    )

    private val MIRRORED_CHARS: Map<Char, Char> = mapOf(
        '(' to ')', ')' to '(',
        '[' to ']', ']' to '[',
        '{' to '}', '}' to '{',
        '<' to '>', '>' to '<',
        '«' to '»', '»' to '«',
        '‹' to '›', '›' to '‹',
    )

    /** Applies [options] to every edited value except Unity `m_Name` object identifiers. */
    fun transformEdits(
        edits: Map<String, String>,
        options: UnityTextTransformOptions,
    ): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>(edits.size)
        edits.forEach { (path, value) ->
            val isAssetName = path == "m_Name" || path.endsWith(".m_Name")
            out[path] = if (isAssetName || !options.isEnabled) {
                value
            } else {
                transform(value, options)
            }
        }
        return out
    }

    fun transform(text: String, options: UnityTextTransformOptions): String {
        if (text.isEmpty() || !options.isEnabled) return text
        var current = text
        if (options.normalizePersian) {
            current = normalizePersianCharacters(current)
        }
        if (options.shapeArabic) {
            current = shapeArabicPersian(current)
        }
        if (options.reverseBidi) {
            current = reorderBidiVisual(current)
        }
        return current
    }

    /**
     * Maps Arabic-script letters that Persian does not use to their Persian counterparts.
     * Only the three well-known confusables are mapped; digits, punctuation, diacritics and
     * join controls (ZWNJ/ZWJ) are left untouched so digit style and word boundaries stay
     * exactly as the translator typed them.
     */
    fun normalizePersianCharacters(text: String): String {
        if (text.isEmpty()) return text
        var changed = false
        val chars = text.toCharArray()
        for (i in chars.indices) {
            val replacement = when (chars[i]) {
                '\u064A', '\u0649' -> '\u06CC' // Arabic Yeh / Alef Maksura -> Farsi Yeh
                '\u0643' -> '\u06A9' // Arabic Kaf -> Persian Keheh
                else -> null
            }
            if (replacement != null) {
                chars[i] = replacement
                changed = true
            }
        }
        return if (changed) String(chars) else text
    }

    /**
     * Contextual Arabic/Persian glyph shaping into Presentation Forms-A (`0xFB50..0xFDFF`) and
     * Presentation Forms-B (`0xFE70..0xFEFF`).
     *
     * Handles initial/medial/final/isolated selection, transparent combining marks
     * (`0x064B..0x065F`, `0x0670`), ZWNJ (`\u200C`) non-joining boundaries, and mandatory
     * Lam-Alef (`لا`, `لآ`, `لأ`, `لإ`) ligatures.
     */
    fun shapeArabicPersian(text: String, stripJoinControls: Boolean = true): String {
        if (text.isEmpty() || !containsArabicLetters(text)) return text
        val n = text.length
        val out = StringBuilder(n)
        var i = 0
        while (i < n) {
            val ch = text[i]
            if (ch == ZWNJ || ch == ZWJ) {
                if (!stripJoinControls) out.append(ch)
                i++
                continue
            }
            val forms = LETTER_TABLE[ch]
            if (forms == null) {
                out.append(ch)
                i++
                continue
            }

            val joinsPrev = canJoinWithPrevious(text, i)

            // Mandatory Lam + Alef ligature check (skipping transparent marks between Lam and Alef)
            if (ch == LAM) {
                val alefIndex = nextNonTransparentIndex(text, i + 1)
                if (alefIndex < n) {
                    val ligature = LAM_ALEF_LIGATURES[text[alefIndex]]
                    if (ligature != null) {
                        out.append(if (joinsPrev) ligature.second else ligature.first)
                        // Preserve any transparent harakat that appeared between Lam and Alef
                        for (t in (i + 1) until alefIndex) {
                            out.append(text[t])
                        }
                        i = alefIndex + 1
                        continue
                    }
                }
            }

            val joinsNext = (forms.joinType == JoinType.DUAL) && canJoinWithNext(text, i)
            val shaped = when {
                joinsPrev && joinsNext -> forms.medial
                joinsPrev && !joinsNext -> forms.finalForm
                !joinsPrev && joinsNext -> forms.initial
                else -> forms.isolated
            }
            out.append(shaped)
            i++
        }
        return out.toString()
    }

    /**
     * Visual bidirectional reordering (UAX #9 via [java.text.Bidi]).
     *
     * Processes each line independently:
     * - Pure LTR lines are returned untouched.
     * - Lines containing RTL characters are segmented into directional runs; odd-level (RTL) runs
     *   have their grapheme clusters reversed into visual left-to-right order with mirrored brackets,
     *   while even-level (LTR) runs — such as Latin identifiers (`Unity`) and digits (`120`, `500`,
     *   `2022`, `۱۲۳`) — remain left-to-right.
     */
    fun reorderBidiVisual(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var lineStart = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\r' || ch == '\n') {
                sb.append(reorderLineVisual(text.substring(lineStart, i)))
                sb.append(ch)
                if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                    i++
                    sb.append('\n')
                }
                lineStart = i + 1
            }
            i++
        }
        if (lineStart <= text.length) {
            sb.append(reorderLineVisual(text.substring(lineStart)))
        }
        return sb.toString()
    }

    private data class BidiRun(
        val start: Int,
        val limit: Int,
        val level: Int,
    )

    private fun reorderLineVisual(line: String): String {
        if (line.isEmpty()) return line
        val normalizedForBidi = normalizePresentationFormsForBidi(line)
        val chars = normalizedForBidi.toCharArray()
        if (!Bidi.requiresBidi(chars, 0, chars.size) && !containsRtlCharacters(line)) {
            return line
        }
        val baseDirection = if (containsRtlCharacters(line)) {
            Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT
        } else {
            Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT
        }
        val bidi = Bidi(normalizedForBidi, baseDirection)
        if (bidi.isLeftToRight) return line

        val runCount = bidi.runCount
        val levels = ByteArray(runCount)
        val runs = Array<Any>(runCount) { idx ->
            val level = bidi.getRunLevel(idx)
            levels[idx] = level.toByte()
            BidiRun(bidi.getRunStart(idx), bidi.getRunLimit(idx), level)
        }
        Bidi.reorderVisually(levels, 0, runs, 0, runCount)

        val out = StringBuilder(line.length)
        for (r in 0 until runCount) {
            val run = runs[r] as BidiRun
            val slice = line.substring(run.start, run.limit)
            if (run.level and 1 != 0) {
                appendReversedRtlRun(out, slice)
            } else {
                out.append(slice)
            }
        }
        return out.toString()
    }

    /**
     * Maps Arabic Presentation Forms and Extended Arabic-Indic digits (`۰`..`۹`) where needed
     * so [Bidi] assigns the same directional classes after [shapeArabicPersian] as before it.
     */
    private fun normalizePresentationFormsForBidi(line: String): String {
        var changed = false
        val chars = CharArray(line.length)
        for (i in line.indices) {
            val c = line[i]
            // Extended Arabic-Indic digits (U+06F0..U+06F9) have bidi class EN, whereas
            // Arabic-Indic digits (U+0660..U+0669) have bidi class AN; both are even-level LTR runs,
            // but normalizing Presentation Forms (0xFB50..0xFDFF, 0xFE70..0xFEFF) to AL guarantees
            // consistent UAX #9 run levels even on stripped JVM char tables.
            val replacement = if (c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFC) {
                changed = true
                '\u0628' // AL (Arabic Letter)
            } else {
                c
            }
            chars[i] = replacement
        }
        return if (changed) String(chars) else line
    }

    private fun appendReversedRtlRun(out: StringBuilder, run: String) {
        // Group each base codepoint with any trailing transparent combining marks so diacritics
        // stay attached to their base letter when the RTL run is reversed into visual order.
        val clusters = ArrayList<String>(run.length)
        var i = 0
        while (i < run.length) {
            val start = i
            val cp = Character.codePointAt(run, i)
            i += Character.charCount(cp)
            while (i < run.length && isCombiningMark(Character.codePointAt(run, i))) {
                i += Character.charCount(Character.codePointAt(run, i))
            }
            val cluster = run.substring(start, i)
            if (cluster.length == 1) {
                val mirrored = MIRRORED_CHARS[cluster[0]]
                clusters += (mirrored?.toString() ?: cluster)
            } else {
                clusters += cluster
            }
        }
        for (idx in clusters.size - 1 downTo 0) {
            out.append(clusters[idx])
        }
    }

    private fun joinTypeOf(ch: Char): JoinType {
        if (ch == ZWNJ) return JoinType.NONE
        if (ch == ZWJ || ch == TATWEEL) return JoinType.DUAL
        if (isTransparentChar(ch)) return JoinType.TRANSPARENT
        return LETTER_TABLE[ch]?.joinType ?: JoinType.NONE
    }

    private fun canJoinWithPrevious(text: String, index: Int): Boolean {
        var p = index - 1
        while (p >= 0) {
            when (joinTypeOf(text[p])) {
                JoinType.TRANSPARENT -> p--
                JoinType.DUAL -> return true
                JoinType.RIGHT, JoinType.NONE -> return false
            }
        }
        return false
    }

    private fun canJoinWithNext(text: String, index: Int): Boolean {
        val next = nextNonTransparentIndex(text, index + 1)
        if (next >= text.length) return false
        return when (joinTypeOf(text[next])) {
            JoinType.DUAL, JoinType.RIGHT -> true
            JoinType.NONE, JoinType.TRANSPARENT -> false
        }
    }

    private fun nextNonTransparentIndex(text: String, startIndex: Int): Int {
        var n = startIndex
        while (n < text.length && joinTypeOf(text[n]) == JoinType.TRANSPARENT) {
            n++
        }
        return n
    }

    private fun isTransparentChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x0610..0x061A ||
            code in 0x064B..0x065F ||
            code == 0x0670 ||
            code in 0x06D6..0x06ED ||
            code in 0x08D3..0x08FF ||
            Character.getType(ch).let {
                it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt()
            }
    }

    private fun isCombiningMark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    fun containsArabicLetters(text: String): Boolean {
        for (i in text.indices) {
            val code = text[i].code
            if (code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0x08A0..0x08FF) {
                return true
            }
        }
        return false
    }

    fun containsRtlCharacters(text: String): Boolean {
        for (i in text.indices) {
            val code = text[i].code
            if (code in 0x0590..0x08FF || code in 0xFB1D..0xFDFF || code in 0xFE70..0xFEFC) {
                return true
            }
        }
        return false
    }
}
