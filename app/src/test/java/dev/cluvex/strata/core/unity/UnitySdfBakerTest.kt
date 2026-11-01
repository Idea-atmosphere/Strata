package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

class UnitySdfBakerTest {

    private fun bruteEdt(source: BooleanArray, w: Int, h: Int): DoubleArray {
        val out = DoubleArray(w * h)
        val sources = ArrayList<Pair<Int, Int>>()
        for (y in 0 until h) for (x in 0 until w) if (source[y * w + x]) sources.add(x to y)
        for (y in 0 until h) for (x in 0 until w) {
            var best = Double.POSITIVE_INFINITY
            for ((sx, sy) in sources) {
                val dx = (x - sx).toDouble()
                val dy = (y - sy).toDouble()
                val d = sqrt(dx * dx + dy * dy)
                if (d < best) best = d
            }
            out[y * w + x] = best
        }
        return out
    }

    @Test
    fun edtMatchesBruteForce() {
        // 7x5 pattern, deterministic, no Random (Android-safe).
        val rows = listOf("1010101", "0101010", "1100110", "0011001", "1010101")
        val w = 7
        val h = 5
        val src = BooleanArray(w * h) { i -> rows[i / w][i % w] == '1' }
        val fast = UnitySdfBaker.edtDistanceTo(src, w, h)
        val brute = bruteEdt(src, w, h)
        for (i in src.indices) {
            assertThat(fast[i]).isWithin(0.01).of(brute[i])
        }
    }

    @Test
    fun sdfFormulaOnCenterPixel() {
        // 5x5 core, 3x3 ink in the centre. Centre pixel: distIn=2, distOut=0,
        // pad=9 -> 128 + 2/9*128 = 156 (truncated). Edge ink pixel (1,2):
        // distIn=1 -> 128 + 1/9*128 = 142. Outside corner (0,0): distIn=0,
        // distOut=sqrt(2) -> 128 - 1.414/9*128 = 107 (truncated).
        val core = BooleanArray(25) { i ->
            val x = i % 5
            val y = i / 5
            x in 1..3 && y in 1..3
        }
        val padded = UnitySdfBaker.sdfFromCore(core, 5, 5, 9)
        val pw = 5 + 2 * 9
        fun at(x: Int, y: Int): Int = padded[(y + 9) * pw + (x + 9)].toInt() and 0xFF
        assertThat(at(2, 2)).isEqualTo(156)
        assertThat(at(1, 2)).isEqualTo(142)
        assertThat(at(0, 0)).isEqualTo(107)
        // Padded border is 0.
        assertThat(padded[0].toInt() and 0xFF).isEqualTo(0)
        assertThat(padded[padded.size - 1].toInt() and 0xFF).isEqualTo(0)
    }

    @Test
    fun compositeWordUsesPythonFormulas() {
        fun letter(pua: Int, w: Int, h: Int, adv: Float, bx: Float, by: Float) =
            UnitySdfBaker.LetterInput(pua, BooleanArray(w * h) { true }, w, h, adv, bx, by)
        val word = UnitySdfBaker.compositeWord(
            UnitySdfBaker.WordInput(
                0xE200,
                listOf(
                    letter(0xE000, 10, 20, 12f, 1f, 15f),
                    letter(0xE001, 12, 20, 14f, 0f, 15f),
                ),
            ),
            margin = 20,
        )
        // A at x 21..30, B at x 32..43: 1 px gap at x=31, tight 23x20.
        assertThat(word.coreWidth).isEqualTo(23)
        assertThat(word.coreHeight).isEqualTo(20)
        assertThat(word.bearingX).isEqualTo(1f)
        assertThat(word.bearingY).isEqualTo(15f)
        assertThat(word.advance).isEqualTo(26f)
        assertThat(word.core.count { it }).isEqualTo(440)
    }

    @Test
    fun pythonRoundIsBankersRounding() {
        assertThat(UnitySdfBaker.pythonRound(2.5f)).isEqualTo(2)
        assertThat(UnitySdfBaker.pythonRound(3.5f)).isEqualTo(4)
        assertThat(UnitySdfBaker.pythonRound(-1.5f)).isEqualTo(-2)
    }

    @Test
    fun bakePreservesOriginalPackWithoutOverlapAndRightPadsWords() {
        val origSize = 64
        val atlasSize = 256
        val orig = ByteArray(origSize * origSize) { 0xAB.toByte() }
        val sizes = listOf(20 to 30, 40 to 20, 15 to 15, 60 to 40, 30 to 50, 25 to 25, 50 to 30, 18 to 60)
        val glyphs = sizes.mapIndexed { i, (w, h) ->
            UnitySdfBaker.GlyphToBake(
                0xE300 + i, BooleanArray(w * h) { true }, w, h,
                (w + 4).toFloat(), 1f, 10f, i % 2 == 0,
            )
        }
        val baked = UnitySdfBaker.bake(glyphs, atlasSize, origSize, orig, pad = 9, rightPadPx = 30)
        assertThat(baked.unpacked).isEmpty()
        assertThat(baked.glyphs).hasSize(8)
        assertThat(baked.atlasBytes).hasLength(atlasSize * atlasSize)
        for (y in 0 until origSize) for (x in 0 until origSize) {
            assertThat(baked.atlasBytes[y * atlasSize + x].toInt() and 0xFF).isEqualTo(0xAB)
        }
        data class PR(val x: Int, val y: Int, val w: Int, val h: Int)
        val prs = baked.glyphs.map { g ->
            PR(g.rectX - 9, g.rectY - 9, g.rectWidth + 18, g.rectHeight + 18)
        }
        for (i in prs.indices) for (j in i + 1 until prs.size) {
            val a = prs[i]
            val b = prs[j]
            val overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
            assertThat(overlap).isFalse()
        }
        for (g in baked.glyphs) {
            val idx = g.pua - 0xE300
            val (w, _) = sizes[idx]
            if (idx % 2 == 0) {
                assertThat(g.rectWidth).isEqualTo(w + 30)
                assertThat(g.advance).isEqualTo((w + 4).toFloat())
                for (y in 0 until g.rectHeight) {
                    for (x in g.rectWidth - 30 until g.rectWidth) {
                        assertThat(
                            baked.atlasBytes[(g.rectY + y) * atlasSize + (g.rectX + x)].toInt() and 0xFF,
                        ).isEqualTo(0)
                    }
                }
            } else {
                assertThat(g.rectWidth).isEqualTo(w)
            }
        }
    }

    @Test
    fun unpackedGlyphsAreReportedNotDropped() {
        val big = (0 until 20).map { i ->
            UnitySdfBaker.GlyphToBake(0xE400 + i, BooleanArray(80 * 80) { true }, 80, 80, 84f, 0f, 40f, false)
        }
        val baked = UnitySdfBaker.bake(big, 256, 0, null, pad = 9, rightPadPx = 0)
        assertThat(baked.unpacked).isNotEmpty()
        assertThat(baked.glyphs.size + baked.unpacked.size).isEqualTo(20)
    }

    @Test
    fun preserveInputOrderLetsFrequentSmallGlyphWinOverRareLargeGlyph() {
        // 64x64 atlas, pad 9: small 10x10 -> padded 28x28, large 40x40
        // -> padded 58x58. Together they do not fit (28+58=86 > 64
        // width, wrapped height 28+58=86 > 64 height), so only one
        // packs. Size-sorted packing (default) packs the large glyph
        // first; frequency-ordered packing (preserveInputOrder=true,
        // input = frequent small first) packs the frequent glyph.
        fun solid(pua: Int, w: Int, h: Int) =
            UnitySdfBaker.GlyphToBake(pua, BooleanArray(w * h) { true }, w, h, (w + 2).toFloat(), 0f, 10f, false)
        val glyphs = listOf(solid(0xE200, 10, 10), solid(0xE201, 40, 40))
        val freq = UnitySdfBaker.bake(glyphs, 64, 0, null, pad = 9, rightPadPx = 0, preserveInputOrder = true)
        assertThat(freq.glyphs.map { it.pua }).containsExactly(0xE200)
        assertThat(freq.unpacked).containsExactly(0xE201)
        val sizeSorted = UnitySdfBaker.bake(glyphs, 64, 0, null, pad = 9, rightPadPx = 0, preserveInputOrder = false)
        assertThat(sizeSorted.glyphs.map { it.pua }).containsExactly(0xE201)
        assertThat(sizeSorted.unpacked).containsExactly(0xE200)
    }
}
