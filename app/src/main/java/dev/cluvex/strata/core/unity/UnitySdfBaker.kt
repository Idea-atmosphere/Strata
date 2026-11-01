package dev.cluvex.strata.core.unity

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-Kotlin SDF baker for the Strata game-translation mode.
 *
 * No Android dependencies, no font rendering: the caller supplies tight
 * letter cores (binary masks, top-down) plus their metrics, and this
 * module composites words, computes exact Euclidean SDFs, packs glyphs
 * into a TMP atlas, and returns the atlas bytes plus glyph tables.
 *
 * This is a direct port of the proven Python word-bake pipeline used
 * for Bendy (word_bake_header_rightpad.py, word_bake_warning_full.py),
 * with two deliberate generalisations:
 * - Word canvases are sized dynamically (Python used a fixed 1200x260
 *   canvas, which fails for very long words). Metrics formulas are
 *   identical, so Bendy words bake to the same advances/bearings.
 * - Packing tries the same sort keys and strip orders as Python, so
 *   rects are deterministic and comparable, but the caller may use a
 *   different atlas size (2048 default, 4096 for large vocabularies;
 *   CaviarDreams' own 4096 atlas proves 4096 renders in-game).
 *
 * Orientation (critical, copied from Python, do not "fix"):
 * - Input cores are TOP-DOWN (row 0 = glyph top), as rendered by a
 *   font renderer (PIL / Android Bitmap).
 * - SDFs are computed top-down, then padded, then FLIPPED vertically
 *   when written to the atlas, because the TMP Texture2D file stores
 *   rows bottom-up (file row == table Y). The original atlas area is
 *   copied verbatim, row by row, in file order.
 * - Glyph rects (rectX, rectY) are in atlas-array coordinates, exactly
 *   as Python stored them: tightX = padded placement X + pad,
 *   tightY = padded placement Y + pad. Metrics (advance, bearingX,
 *   bearingY) are display metrics and are NOT flipped.
 *
 * SDF formula (Python, scipy EDT, copied exactly):
 *   sdf = clip(128 + (distIn - distOut) / pad * 128, 0, 255),
 *   truncated (not rounded) to UInt8, where distIn is the exact
 *   Euclidean distance to the nearest outside pixel and distOut the
 *   distance to the nearest inside pixel, both computed on the TIGHT
 *   core only. The padded border around the SDF is 0, not a fading
 *   SDF: it is spacing so texture filtering at a rect edge does not
 *   bleed a neighbouring glyph's ink (TMP samples the tight rect).
 *
 * Right pad (Bendy fix, words only):
 * - Word glyphs get [rightPadPx] blank pixels appended to the RIGHT
 *   inside their tight rect (tightW and padW grow, advance/bearing/
 *   height unchanged). Pen and ink positions do not move; only the
 *   quad extends further right into the inter-word gap (transparent).
 *   Bendy lost a fixed ~15-17 atlas px from every word glyph's right
 *   edge at 84 pt, and 30 px of blank right pad fixed it (header
 *   "fixed" on device, 2026-11-01). Default rightPadPx is 30; for a
 *   different fallback pointSize, scale it with [scaledRightPad].
 * - Letter glyphs never get right pad (Python applied it to words only).
 *
 * Atlas-fit contract:
 * - [bake] returns [BakedAtlas.unpacked] (PUA codes that did not fit).
 *   The caller MUST NOT emit a word-PUA code for an unpacked word in
 *   I2 text (it would render as a square); it must fall back to
 *   letter-PUA rendering for that word (Phase 4, frequency packing).
 *   Returning unpacked codes explicitly, instead of silently dropping
 *   them, is what makes that fallback possible.
 */
object UnitySdfBaker {

    /** A tight letter core, top-down, as supplied by the font renderer. */
    data class LetterInput(
        val pua: Int,
        val core: BooleanArray,
        val coreWidth: Int,
        val coreHeight: Int,
        val advance: Float,
        val bearingX: Float,
        val bearingY: Float,
    )

    /** A word to composite, letters in visual order. */
    data class WordInput(
        val wordPua: Int,
        val letters: List<LetterInput>,
    )

    /** One glyph to bake (letter or already-composited word). */
    data class GlyphToBake(
        val pua: Int,
        val core: BooleanArray,
        val coreWidth: Int,
        val coreHeight: Int,
        val advance: Float,
        val bearingX: Float,
        val bearingY: Float,
        val applyRightPad: Boolean,
    )

    /** A composited word, before SDF/packing. */
    data class CompositedWord(
        val wordPua: Int,
        val core: BooleanArray,
        val coreWidth: Int,
        val coreHeight: Int,
        val advance: Float,
        val bearingX: Float,
        val bearingY: Float,
    )

    /** One baked glyph, ready for a TMP glyph/character table entry. */
    data class BakedGlyph(
        val pua: Int,
        val rectX: Int,
        val rectY: Int,
        val rectWidth: Int,
        val rectHeight: Int,
        val metricsWidth: Int,
        val metricsHeight: Int,
        val bearingX: Float,
        val bearingY: Float,
        val advance: Float,
    )

    /** Result of [bake]. Atlas bytes are in TMP file order (bottom-up). */
    data class BakedAtlas(
        val atlasBytes: ByteArray,
        val atlasSize: Int,
        /** Sorted by PUA ascending, for deterministic table merging. */
        val glyphs: List<BakedGlyph>,
        /** PUA codes that did not fit, sorted ascending. See class doc. */
        val unpacked: List<Int>,
    )

    private const val INF: Double = 1e18
    private const val INF_THRESHOLD: Double = 1e17

    /** Bendy's right pad (30 atlas px at 84 pt), scaled to a target pointSize. */
    fun scaledRightPad(basePadPx: Int = 30, basePointSize: Float = 84f, targetPointSize: Float): Int {
        require(basePadPx >= 0 && basePointSize > 0f && targetPointSize > 0f)
        return (basePadPx * targetPointSize / basePointSize).roundToInt().coerceAtLeast(0)
    }

    /** Python's round (bankers rounding, half to even), for placement parity. */
    fun pythonRound(value: Float): Int {
        val f = floor(value.toDouble())
        val diff = value - f
        return when {
            diff < 0.5 -> f.toInt()
            diff > 0.5 -> (f + 1.0).toInt()
            else -> if (f.toLong() % 2L == 0L) f.toInt() else (f + 1.0).toInt()
        }
    }

    // ---- Exact Euclidean distance transform (Felzenszwalb, separable) ----

    /** 1D squared EDT. f[i] is 0 for a source, INF otherwise. */
    fun edt1d(f: DoubleArray): DoubleArray {
        val n = f.size
        val d = DoubleArray(n)
        if (n == 0) return d
        val finite = ArrayList<Int>(n)
        for (i in 0 until n) if (f[i] < INF_THRESHOLD) finite.add(i)
        if (finite.isEmpty()) {
            for (i in 0 until n) d[i] = INF
            return d
        }
        val v = IntArray(n)
        val z = DoubleArray(n + 1)
        var k = 0
        v[0] = finite[0]
        z[0] = Double.NEGATIVE_INFINITY
        z[1] = Double.POSITIVE_INFINITY
        for (idx in 1 until finite.size) {
            val q = finite[idx]
            var s = ((f[q] + q * q.toDouble()) - (f[v[k]] + v[k] * v[k].toDouble())) /
                (2.0 * q - 2.0 * v[k])
            while (s <= z[k]) {
                k--
                s = ((f[q] + q * q.toDouble()) - (f[v[k]] + v[k] * v[k].toDouble())) /
                    (2.0 * q - 2.0 * v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (i in 0 until n) {
            while (z[k + 1] < i) k++
            val dx = (i - v[k]).toDouble()
            d[i] = dx * dx + f[v[k]]
        }
        return d
    }

    /**
     * Euclidean distance to the nearest source pixel. source[i] true
     * means pixel i is a source (distance 0). Returns INF where no
     * source exists in the whole image (e.g. distIn for a solid core).
     */
    fun edtDistanceTo(source: BooleanArray, width: Int, height: Int): DoubleArray {
        require(source.size == width * height && width > 0 && height > 0)
        val tmp = DoubleArray(width * height)
        for (y in 0 until height) {
            val f = DoubleArray(width) { x -> if (source[y * width + x]) 0.0 else INF }
            val d = edt1d(f)
            for (x in 0 until width) tmp[y * width + x] = d[x]
        }
        val out = DoubleArray(width * height)
        for (x in 0 until width) {
            val f = DoubleArray(height) { y -> tmp[y * width + x] }
            val d = edt1d(f)
            for (y in 0 until height) {
                val v = d[y]
                out[y * width + x] = if (v >= INF_THRESHOLD) Double.POSITIVE_INFINITY else sqrt(v)
            }
        }
        return out
    }

    /** Padded SDF (top-down), size (w+2*pad) x (h+2*pad), unsigned bytes. */
    fun sdfFromCore(core: BooleanArray, coreWidth: Int, coreHeight: Int, pad: Int): ByteArray {
        require(core.size == coreWidth * coreHeight && coreWidth > 0 && coreHeight > 0 && pad >= 1) {
            "SDF needs a positive pad (the TMP atlas padding, e.g. 9); pad must be >= 1"
        }
        val n = coreWidth * coreHeight
        val outside = BooleanArray(n) { !core[it] }
        val distIn = edtDistanceTo(outside, coreWidth, coreHeight)
        val distOut = edtDistanceTo(core, coreWidth, coreHeight)
        val sdf = ByteArray(n)
        for (i in 0 until n) {
            val v = 128.0 + (distIn[i] - distOut[i]) / pad * 128.0
            val clipped = v.coerceIn(0.0, 255.0)
            sdf[i] = clipped.toInt().toByte()
        }
        val pw = coreWidth + 2 * pad
        val ph = coreHeight + 2 * pad
        val padded = ByteArray(pw * ph)
        for (y in 0 until coreHeight) {
            for (x in 0 until coreWidth) {
                padded[(y + pad) * pw + (x + pad)] = sdf[y * coreWidth + x]
            }
        }
        return padded
    }

    // ---- Word compositing (Python formulas, dynamic canvas) ----

    /** Composite one word from its letter cores (visual order). */
    fun compositeWord(word: WordInput, margin: Int = 20): CompositedWord {
        require(word.letters.isNotEmpty()) { "Word U+%04X has no letters".format(word.wordPua) }
        for (letter in word.letters) {
            require(letter.core.size == letter.coreWidth * letter.coreHeight &&
                letter.coreWidth > 0 && letter.coreHeight > 0) {
                "Letter U+%04X has an inconsistent core".format(letter.pua)
            }
            require(letter.core.any { it }) { "Letter U+%04X core is empty".format(letter.pua) }
        }
        require(margin >= 0)
        val maxBearingY = word.letters.maxOf { it.bearingY }
        val minBearingX = word.letters.minOf { it.bearingX }
        val maxBottom = word.letters.maxOf { it.coreHeight - it.bearingY }
        val maxBearingPlusWidth = word.letters.maxOf { it.bearingX + it.coreWidth }
        val totalAdvance = word.letters.sumOf { it.advance.toDouble() }.toFloat()
        // Pen origin: margin, shifted right if any bearingX is negative,
        // so the leftmost placement is never left of the margin.
        val penStart = margin - minOf(0f, minBearingX).toInt()
        // Baseline: margin below the highest glyph top.
        val baseline = margin + maxBearingY.roundToInt()
        val canvasWidth = penStart + totalAdvance.roundToInt() + maxBearingPlusWidth.roundToInt() + margin + 1
        val canvasHeight = baseline + maxOf(0f, maxBottom).roundToInt() + margin + 1
        val canvas = BooleanArray(canvasWidth * canvasHeight)
        var pen = penStart.toFloat()
        for (letter in word.letters) {
            val x0 = pythonRound(pen + letter.bearingX)
            val y0 = pythonRound(baseline - letter.bearingY)
            require(x0 >= 0 && y0 >= 0 && x0 + letter.coreWidth <= canvasWidth &&
                y0 + letter.coreHeight <= canvasHeight) {
                "Word U+%04X letter U+%04X out of canvas".format(word.wordPua, letter.pua)
            }
            for (ly in 0 until letter.coreHeight) {
                for (lx in 0 until letter.coreWidth) {
                    if (letter.core[ly * letter.coreWidth + lx]) {
                        canvas[(y0 + ly) * canvasWidth + (x0 + lx)] = true
                    }
                }
            }
            pen += letter.advance
        }
        val advanceWord = pen - penStart
        var minX = canvasWidth
        var maxX = -1
        var minY = canvasHeight
        var maxY = -1
        for (y in 0 until canvasHeight) {
            for (x in 0 until canvasWidth) {
                if (canvas[y * canvasWidth + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        require(maxX >= 0) { "Word U+%04X composited to an empty canvas".format(word.wordPua) }
        val tightW = maxX - minX + 1
        val tightH = maxY - minY + 1
        val core = BooleanArray(tightW * tightH)
        for (y in 0 until tightH) {
            for (x in 0 until tightW) {
                core[y * tightW + x] = canvas[(minY + y) * canvasWidth + (minX + x)]
            }
        }
        return CompositedWord(
            wordPua = word.wordPua,
            core = core,
            coreWidth = tightW,
            coreHeight = tightH,
            advance = advanceWord,
            bearingX = (minX - penStart).toFloat(),
            bearingY = (baseline - minY).toFloat(),
        )
    }

    /** Convenience: composite words, then bake letters + words together. */
    fun bakeWordsAndLetters(
        letters: List<LetterInput>,
        words: List<WordInput>,
        atlasSize: Int,
        originalAtlasSize: Int,
        originalAtlasBytes: ByteArray?,
        pad: Int = 9,
        rightPadPx: Int = 30,
        margin: Int = 20,
        preserveInputOrder: Boolean = false,
    ): BakedAtlas {
        val glyphs = ArrayList<GlyphToBake>(letters.size + words.size)
        for (letter in letters) {
            glyphs.add(
                GlyphToBake(
                    pua = letter.pua,
                    core = letter.core,
                    coreWidth = letter.coreWidth,
                    coreHeight = letter.coreHeight,
                    advance = letter.advance,
                    bearingX = letter.bearingX,
                    bearingY = letter.bearingY,
                    applyRightPad = false,
                ),
            )
        }
        for (word in words) {
            val c = compositeWord(word, margin)
            glyphs.add(
                GlyphToBake(
                    pua = c.wordPua,
                    core = c.core,
                    coreWidth = c.coreWidth,
                    coreHeight = c.coreHeight,
                    advance = c.advance,
                    bearingX = c.bearingX,
                    bearingY = c.bearingY,
                    applyRightPad = true,
                ),
            )
        }
        return bake(glyphs, atlasSize, originalAtlasSize, originalAtlasBytes, pad, rightPadPx, preserveInputOrder)
    }

    // ---- Packing + atlas (Python pack_strip, sort keys, strip orders) ----

    private data class PreparedGlyph(
        val pua: Int,
        val padded: ByteArray,
        val padWidth: Int,
        val padHeight: Int,
        val tightWidth: Int,
        val tightHeight: Int,
        val bearingX: Float,
        val bearingY: Float,
        val advance: Float,
        val originalIndex: Int,
    )

    private data class FreeRect(val x: Int, val y: Int, val w: Int, val h: Int)

    private data class Placed(val glyph: PreparedGlyph, val tightX: Int, val tightY: Int)

    private fun packStrip(
        items: List<PreparedGlyph>,
        strip: FreeRect,
        pad: Int,
    ): Pair<List<Placed>, List<PreparedGlyph>> {
        val placed = ArrayList<Placed>()
        val remaining = ArrayList<PreparedGlyph>()
        var cx = strip.x
        var cy = strip.y
        var rowH = 0
        for (item in items) {
            val pw = item.padWidth
            val ph = item.padHeight
            if (cx + pw > strip.x + strip.w) {
                cx = strip.x
                cy += rowH
                rowH = 0
            }
            if (cy + ph > strip.y + strip.h) {
                remaining.add(item)
                continue
            }
            placed.add(Placed(item, cx + pad, cy + pad))
            cx += pw
            if (ph > rowH) rowH = ph
        }
        return placed to remaining
    }

    /**
     * Bake glyphs into an atlas, preserving the original atlas area.
     *
     * @param preserveInputOrder when false (default, Phase 2 behaviour),
     *   glyphs are re-sorted by size (Python's sort keys) to maximise
     *   the number packed, which is correct when everything fits.
     *   When true (Phase 4 frequency packing), glyphs are packed in
     *   input order, Right strip first then Bottom strip, with NO
     *   size re-sorting, so earlier glyphs (letters first, then words
     *   in frequency order, as supplied by the caller via
     *   UnityPersianFrequency) win atlas space and later glyphs are
     *   the ones reported as unpacked. Use true for large
     *   vocabularies; size-sorted packing could otherwise evict a
     *   frequent word (or even a letter, which letter fallback depends
     *   on) in favour of a large rare word.
     */
    fun bake(
        glyphs: List<GlyphToBake>,
        atlasSize: Int,
        originalAtlasSize: Int,
        originalAtlasBytes: ByteArray?,
        pad: Int = 9,
        rightPadPx: Int = 30,
        preserveInputOrder: Boolean = false,
    ): BakedAtlas {
        require(atlasSize > 0 && originalAtlasSize >= 0 && originalAtlasSize <= atlasSize &&
            pad >= 1 && rightPadPx >= 0) {
            "Invalid bake parameters (atlasSize=$atlasSize, original=$originalAtlasSize, pad=$pad)"
        }
        if (originalAtlasSize > 0) {
            require(originalAtlasBytes != null &&
                originalAtlasBytes.size == originalAtlasSize * originalAtlasSize) {
                "Original atlas bytes must be supplied (originalAtlasSize x originalAtlasSize) " +
                    "to preserve the original glyphs; a blank original area would break them"
            }
        }
        // Prepare: SDF + pad + optional right pad.
        val prepared = ArrayList<PreparedGlyph>(glyphs.size)
        for ((index, glyph) in glyphs.withIndex()) {
            require(glyph.core.size == glyph.coreWidth * glyph.coreHeight &&
                glyph.coreWidth > 0 && glyph.coreHeight > 0) {
                "Glyph U+%04X has an inconsistent core".format(glyph.pua)
            }
            require(glyph.core.any { it }) { "Glyph U+%04X core is empty".format(glyph.pua) }
            var padded = sdfFromCore(glyph.core, glyph.coreWidth, glyph.coreHeight, pad)
            var tightW = glyph.coreWidth
            var padW = glyph.coreWidth + 2 * pad
            val padH = glyph.coreHeight + 2 * pad
            if (glyph.applyRightPad && rightPadPx > 0) {
                val extended = ByteArray(padH * (padW + rightPadPx))
                for (row in 0 until padH) {
                    padded.copyInto(extended, row * (padW + rightPadPx), row * padW, row * padW + padW)
                }
                padded = extended
                tightW += rightPadPx
                padW += rightPadPx
            }
            prepared.add(
                PreparedGlyph(
                    pua = glyph.pua,
                    padded = padded,
                    padWidth = padW,
                    padHeight = padH,
                    tightWidth = tightW,
                    tightHeight = glyph.coreHeight,
                    bearingX = glyph.bearingX,
                    bearingY = glyph.bearingY,
                    advance = glyph.advance,
                    originalIndex = index,
                ),
            )
        }
        // Strips: Right (x >= orig, full height) then Bottom (y >= orig, x < orig).
        val stripOrders: List<List<FreeRect>>
        if (originalAtlasSize == 0) {
            stripOrders = listOf(listOf(FreeRect(0, 0, atlasSize, atlasSize)))
        } else {
            val right = FreeRect(originalAtlasSize, 0, atlasSize - originalAtlasSize, atlasSize)
            val bottom = FreeRect(0, originalAtlasSize, originalAtlasSize, atlasSize - originalAtlasSize)
            stripOrders = listOf(listOf(right, bottom), listOf(bottom, right))
        }
        // Sort keys, in Python's order (stable, original index as final tie-break).
        val sortKeys: List<Comparator<PreparedGlyph>> = listOf(
            compareByDescending<PreparedGlyph> { it.padHeight }
                .thenByDescending { it.padWidth }.thenBy { it.originalIndex },
            compareByDescending<PreparedGlyph> { it.padWidth }
                .thenByDescending { it.padHeight }.thenBy { it.originalIndex },
            compareByDescending<PreparedGlyph> { it.padHeight * it.padWidth }
                .thenByDescending { it.padHeight }.thenBy { it.originalIndex },
            compareByDescending<PreparedGlyph> { maxOf(it.padHeight, it.padWidth) }
                .thenByDescending { it.padHeight }.thenBy { it.originalIndex },
            compareByDescending<PreparedGlyph> { it.padHeight }
                .thenBy { it.padWidth }.thenBy { it.originalIndex },
        )
        var bestPlaced: List<Placed>? = null
        var bestRemaining: List<PreparedGlyph> = prepared
        if (preserveInputOrder) {
            // Single attempt, input order, Right strip first then
            // Bottom strip (stripOrders[0] is exactly that order, or
            // the single full-atlas strip when original==0). No
            // size re-sorting, no best-by-count search across strip
            // orders: frequency order must decide who fits, not size.
            var placed: List<Placed> = emptyList()
            var remaining: List<PreparedGlyph> = prepared
            for (strip in stripOrders[0]) {
                val (p, r) = packStrip(remaining, strip, pad)
                placed = placed + p
                remaining = r
                if (remaining.isEmpty()) break
            }
            bestPlaced = placed
            bestRemaining = remaining
        } else {
            outer@ for (sortKey in sortKeys) {
                val sorted = prepared.sortedWith(sortKey)
                for (order in stripOrders) {
                    var placed: List<Placed> = emptyList()
                    var remaining: List<PreparedGlyph> = sorted
                    for (strip in order) {
                        val (p, r) = packStrip(remaining, strip, pad)
                        placed = placed + p
                        remaining = r
                        if (remaining.isEmpty()) break
                    }
                    if (bestPlaced == null || placed.size > bestPlaced.size) {
                        bestPlaced = placed
                        bestRemaining = remaining
                    }
                    if (remaining.isEmpty()) break@outer
                }
            }
        }
        val placed = bestPlaced ?: emptyList()
        // Atlas: original area verbatim (row by row, file order), then new glyphs flipped.
        val atlas = ByteArray(atlasSize * atlasSize)
        if (originalAtlasSize > 0 && originalAtlasBytes != null) {
            for (row in 0 until originalAtlasSize) {
                originalAtlasBytes.copyInto(
                    atlas, row * atlasSize, row * originalAtlasSize, row * originalAtlasSize + originalAtlasSize,
                )
            }
        }
        val baked = ArrayList<BakedGlyph>(placed.size)
        for ((glyph, tightX, tightY) in placed) {
            val px = tightX - pad
            val py = tightY - pad
            require(px >= 0 && py >= 0 && px + glyph.padWidth <= atlasSize &&
                py + glyph.padHeight <= atlasSize) {
                "Glyph U+%04X packed out of atlas bounds".format(glyph.pua)
            }
            for (r in 0 until glyph.padHeight) {
                val srcRow = glyph.padHeight - 1 - r
                glyph.padded.copyInto(
                    atlas, (py + r) * atlasSize + px,
                    srcRow * glyph.padWidth, srcRow * glyph.padWidth + glyph.padWidth,
                )
            }
            baked.add(
                BakedGlyph(
                    pua = glyph.pua,
                    rectX = tightX,
                    rectY = tightY,
                    rectWidth = glyph.tightWidth,
                    rectHeight = glyph.tightHeight,
                    metricsWidth = glyph.tightWidth,
                    metricsHeight = glyph.tightHeight,
                    bearingX = glyph.bearingX,
                    bearingY = glyph.bearingY,
                    advance = glyph.advance,
                ),
            )
        }
        return BakedAtlas(
            atlasBytes = atlas,
            atlasSize = atlasSize,
            glyphs = baked.sortedBy { it.pua },
            unpacked = bestRemaining.map { it.pua }.sorted(),
        )
    }
}
