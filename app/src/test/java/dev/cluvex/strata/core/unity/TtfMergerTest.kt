package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TtfMergerTest {

    private sealed interface TestGlyph {
        data object Empty : TestGlyph
        data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val advance: Int = 500, val lsb: Int = 0) : TestGlyph
        data class Composite(val components: List<Triple<Int, Int, Int>>) : TestGlyph
        data class CompositeScaled(val glyphIndex: Int, val dx: Int, val dy: Int, val sx: Int, val sy: Int) : TestGlyph
    }

    @Test
    fun mergeAddsMissingGlyphsAndKeepsBaseGlyphsByteIdentical() {
        val base = buildFont(
            upm = 2048,
            glyphs = listOf(TestGlyph.Empty, TestGlyph.Rect(0, 0, 100, 200), TestGlyph.Rect(0, 0, 50, 60)),
            cmap = mapOf(0x41 to 1, 0x42 to 2),
            extraTables = mapOf("GSUB" to byteArrayOf(1, 2, 3, 4)),
        )
        val added = buildFont(
            upm = 2048,
            glyphs = listOf(TestGlyph.Empty, TestGlyph.Rect(0, 0, 999, 999), TestGlyph.Rect(10, 20, 30, 40)),
            cmap = mapOf(0x41 to 1, 0xFB50 to 2),
        )
        val merged = TtfMerger.merge(base, added)

        val mergedCmap = readCmap12(merged)
        assertThat(mergedCmap[0x41]).isEqualTo(1)
        assertThat(mergedCmap[0x42]).isEqualTo(2)
        assertThat(mergedCmap[0xFB50]).isEqualTo(3)
        assertThat(readMaxpNumGlyphs(merged)).isEqualTo(4)
        assertThat(readHeadUpm(merged)).isEqualTo(2048)
        // Base glyph bytes are copied verbatim.
        assertThat(glyphBytes(merged, 1)).isEqualTo(glyphBytes(base, 1))
        assertThat(glyphBytes(merged, 2)).isEqualTo(glyphBytes(base, 2))
        // The added glyph for the new codepoint arrives unchanged (same upm).
        assertThat(glyphCoords(merged, 3)).isEqualTo(listOf(10 to 20, 30 to 20, 30 to 40, 10 to 40))
        // Untouched base tables survive verbatim; the name stays the base face's.
        assertThat(readTable(merged, "GSUB")).isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat(readName(merged, 1)).isEqualTo("BaseFace")
        // The merged cmap is usable by the same inspector the patch path uses.
        assertThat(TtfCmapInspector.missingCodepoints(merged, "A\uFB50")).isEmpty()
    }

    @Test
    fun mergeScalesAddedGlyphsAndMetricsToBaseUpm() {
        val base = buildFont(
            upm = 2048,
            glyphs = listOf(TestGlyph.Empty, TestGlyph.Rect(0, 0, 100, 100)),
            cmap = mapOf(0x41 to 1),
        )
        val added = buildFont(
            upm = 1000,
            glyphs = listOf(TestGlyph.Empty, TestGlyph.Rect(10, 20, 110, 220, advance = 500, lsb = 10)),
            cmap = mapOf(0xFB50 to 1),
        )
        val merged = TtfMerger.merge(base, added)
        assertThat(glyphCoords(merged, 2)).isEqualTo(listOf(20 to 41, 225 to 41, 225 to 451, 20 to 451))
        val (advance, lsb) = readHmtx(merged, 2)
        assertThat(advance).isEqualTo(1024)
        assertThat(lsb).isEqualTo(20)
    }

    @Test
    fun mergeRemapsCompositeComponentsToAppendedIds() {
        val base = buildFont(
            upm = 1000,
            glyphs = listOf(TestGlyph.Empty),
            cmap = mapOf(0x41 to 0),
        )
        val added = buildFont(
            upm = 1000,
            glyphs = listOf(
                TestGlyph.Empty,
                TestGlyph.Composite(listOf(Triple(2, 5, 7))),
                TestGlyph.Rect(0, 0, 40, 40),
            ),
            cmap = mapOf(0xFB50 to 1),
        )
        val merged = TtfMerger.merge(base, added)
        val mergedCmap = readCmap12(merged)
        assertThat(mergedCmap[0xFB50]).isEqualTo(1)
        assertThat(readMaxpNumGlyphs(merged)).isEqualTo(3)
        // The composite's component now points at the appended rect glyph.
        assertThat(readCompositeComponent(merged, 1)).isEqualTo(Pair(2, Pair(5, 7)))
        assertThat(glyphCoords(merged, 2)).isEqualTo(listOf(0 to 0, 40 to 0, 40 to 40, 0 to 40))
    }

    @Test
    fun mergePreservesXyScaledCompositeComponents() {
        // Regression: WE_HAVE_AN_X_AND_Y_SCALE is 0x0040 (two F2DOT14 words),
        // not 0x0010. A composite using it must keep its scale words verbatim.
        val base = buildFont(
            upm = 1000,
            glyphs = listOf(TestGlyph.Empty),
            cmap = mapOf(0x41 to 0),
        )
        val added = buildFont(
            upm = 1000,
            glyphs = listOf(
                TestGlyph.Empty,
                TestGlyph.CompositeScaled(glyphIndex = 2, dx = 5, dy = 7, sx = 0x4000, sy = -0x4000),
                TestGlyph.Rect(0, 0, 40, 40),
            ),
            cmap = mapOf(0xFB50 to 1),
        )
        val merged = TtfMerger.merge(base, added)
        assertThat(readCompositeScaleWords(merged, 1)).isEqualTo(listOf(0x4000, -0x4000))
        assertThat(readCompositeComponent(merged, 1)).isEqualTo(Pair(2, Pair(5, 7)))
    }

    @Test
    fun mergeReturnsBaseUnchangedWhenNothingToAdd() {
        val base = buildFont(
            upm = 2048,
            glyphs = listOf(TestGlyph.Empty, TestGlyph.Rect(0, 0, 100, 100)),
            cmap = mapOf(0x41 to 1),
        )
        val added = buildFont(
            upm = 1000,
            glyphs = listOf(TestGlyph.Empty, TestGlyph.Rect(0, 0, 9, 9)),
            cmap = mapOf(0x41 to 1),
        )
        assertThat(TtfMerger.merge(base, added)).isEqualTo(base)
    }

    @Test
    fun mergeRejectsCollectionsAndCffFonts() {
        val base = buildFont(
            upm = 2048,
            glyphs = listOf(TestGlyph.Empty, TestGlyph.Rect(0, 0, 100, 100)),
            cmap = mapOf(0x41 to 1),
        )
        val collection = byteArrayOf(
            't'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(), 'f'.code.toByte(),
            0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 12,
        )
        assertThrows(UnityFormatException::class.java) { TtfMerger.merge(base, collection) }
        val cff = assembleFont(
            sfntVersion = 0x4F54544F,
            tables = mapOf(
                "CFF " to byteArrayOf(1, 2, 3, 4),
                "cmap" to buildCmap12(mapOf(0x41 to 0)),
                "head" to buildHead(1000, 1),
                "maxp" to buildMaxp(1),
                "hhea" to buildHhea(1),
                "hmtx" to buildHmtx(listOf(500 to 0)),
            ),
        )
        assertThrows(UnityFormatException::class.java) { TtfMerger.merge(base, cff) }
    }

    @Test
    fun mergeExtendsPostFormat2GlyphNames() {
        val base = buildFont(
            upm = 1000,
            glyphs = listOf(TestGlyph.Empty, TestGlyph.Rect(0, 0, 100, 100)),
            cmap = mapOf(0x41 to 1),
            postFormat = 2,
        )
        val added = buildFont(
            upm = 1000,
            glyphs = listOf(TestGlyph.Empty, TestGlyph.Rect(0, 0, 10, 10)),
            cmap = mapOf(0xFB50 to 1),
        )
        val merged = TtfMerger.merge(base, added)
        val post = readTable(merged, "post")!!
        val buffer = ByteBuffer.wrap(post).order(ByteOrder.BIG_ENDIAN)
        assertThat(buffer.getInt(0)).isEqualTo(0x00020000)
        assertThat(buffer.getShort(32).toInt() and 0xFFFF).isEqualTo(3)
        // The appended glyph's custom name is "uni0002" (its glyph id in hex).
        val namesStart = 34 + 3 * 2
        val nameLength = post[namesStart].toInt() and 0xFF
        assertThat(post.copyOfRange(namesStart + 1, namesStart + 1 + nameLength).toString(Charsets.US_ASCII))
            .isEqualTo("uni0002")
    }

    // ---------------------------------------------------------- font builder

    private fun buildFont(
        upm: Int,
        glyphs: List<TestGlyph>,
        cmap: Map<Int, Int>,
        postFormat: Int = 3,
        extraTables: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val glyphBytes = glyphs.map { glyph ->
            when (glyph) {
                is TestGlyph.Empty -> ByteArray(0)
                is TestGlyph.Rect -> buildRectGlyph(glyph)
                is TestGlyph.Composite -> buildCompositeGlyph(glyph)
                is TestGlyph.CompositeScaled -> buildCompositeScaledGlyph(glyph)
            }
        }
        val loca = ByteArrayOutputStream()
        val locaOut = DataOutputStream(loca)
        var offset = 0
        for (bytes in glyphBytes) {
            locaOut.writeInt(offset)
            offset += bytes.size
        }
        locaOut.writeInt(offset)
        val glyf = glyphBytes.fold(ByteArrayOutputStream()) { acc, bytes -> acc.apply { write(bytes) } }.toByteArray()
        val tables = LinkedHashMap<String, ByteArray>()
        tables["head"] = buildHead(upm, glyphs.size)
        tables["maxp"] = buildMaxp(glyphs.size)
        tables["hhea"] = buildHhea(glyphs.size)
        tables["hmtx"] = buildHmtx(glyphs.map {
            when (it) {
                is TestGlyph.Empty -> 0 to 0
                is TestGlyph.Rect -> it.advance to it.lsb
                is TestGlyph.Composite -> 500 to 0
                is TestGlyph.CompositeScaled -> 500 to 0
            }
        })
        tables["loca"] = loca.toByteArray()
        tables["glyf"] = glyf
        tables["cmap"] = buildCmap12(cmap)
        tables["name"] = buildName("BaseFace")
        tables["post"] = buildPost(postFormat, glyphs.size)
        tables.putAll(extraTables)
        return assembleFont(0x00010000, tables)
    }

    private fun buildRectGlyph(rect: TestGlyph.Rect): ByteArray {
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        w.writeShort(1)
        w.writeShort(rect.x0)
        w.writeShort(rect.y0)
        w.writeShort(rect.x1)
        w.writeShort(rect.y1)
        w.writeShort(3)
        w.writeShort(0)
        w.writeByte(1)
        w.writeByte(1)
        w.writeByte(1)
        w.writeByte(1)
        val xs = intArrayOf(rect.x0, rect.x1, rect.x1, rect.x0)
        val ys = intArrayOf(rect.y0, rect.y0, rect.y1, rect.y1)
        var prevX = 0
        var prevY = 0
        for (i in xs.indices) {
            w.writeShort(xs[i] - prevX)
            prevX = xs[i]
        }
        for (i in ys.indices) {
            w.writeShort(ys[i] - prevY)
            prevY = ys[i]
        }
        return out.toByteArray()
    }

    private fun buildCompositeGlyph(composite: TestGlyph.Composite): ByteArray {
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        w.writeShort(-1)
        w.writeShort(0)
        w.writeShort(0)
        w.writeShort(100)
        w.writeShort(100)
        composite.components.forEachIndexed { index, (glyphIndex, dx, dy) ->
            var flags = 0x0002 or 0x0004
            if (index < composite.components.size - 1) flags = flags or 0x0020
            w.writeShort(flags)
            w.writeShort(glyphIndex)
            w.writeByte(dx)
            w.writeByte(dy)
        }
        w.writeShort(0)
        return out.toByteArray()
    }

    private fun buildCompositeScaledGlyph(scaled: TestGlyph.CompositeScaled): ByteArray {
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        w.writeShort(-1)
        w.writeShort(0)
        w.writeShort(0)
        w.writeShort(100)
        w.writeShort(100)
        w.writeShort(0x0046)
        w.writeShort(scaled.glyphIndex)
        w.writeByte(scaled.dx)
        w.writeByte(scaled.dy)
        w.writeShort(scaled.sx)
        w.writeShort(scaled.sy)
        w.writeShort(0)
        return out.toByteArray()
    }

    private fun buildHead(upm: Int, glyphCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        w.writeInt(0x00010000)
        w.writeInt(0x00010000)
        w.writeInt(0)
        w.writeInt(0x5F0F3CF5)
        w.writeShort(0)
        w.writeShort(upm)
        w.writeLong(0L)
        w.writeLong(0L)
        w.writeShort(0)
        w.writeShort(0)
        w.writeShort(1000)
        w.writeShort(1000)
        w.writeShort(0)
        w.writeShort(8)
        w.writeShort(2)
        w.writeShort(1)
        w.writeShort(0)
        return out.toByteArray()
    }

    private fun buildMaxp(glyphCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        w.writeInt(0x00010000)
        w.writeShort(glyphCount)
        repeat(13) { w.writeShort(0) }
        return out.toByteArray()
    }

    private fun buildHhea(glyphCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        w.writeInt(0x00010000)
        w.writeShort(800)
        w.writeShort(-200)
        w.writeShort(0)
        w.writeShort(500)
        w.writeShort(0)
        w.writeShort(0)
        w.writeShort(100)
        w.writeShort(1)
        w.writeShort(0)
        w.writeShort(0)
        repeat(4) { w.writeShort(0) }
        w.writeShort(0)
        w.writeShort(glyphCount)
        return out.toByteArray()
    }

    private fun buildHmtx(metrics: List<Pair<Int, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        for ((advance, lsb) in metrics) {
            w.writeShort(advance)
            w.writeShort(lsb)
        }
        return out.toByteArray()
    }

    private fun buildCmap12(cmap: Map<Int, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        val sorted = cmap.entries.sortedBy { it.key }
        val groups = ArrayList<Triple<Int, Int, Int>>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1].key == sorted[j].key + 1 && sorted[j + 1].value == sorted[j].value + 1) j++
            groups += Triple(sorted[i].key, sorted[j].key, sorted[i].value)
            i = j + 1
        }
        w.writeShort(0)
        w.writeShort(1)
        w.writeShort(3)
        w.writeShort(10)
        w.writeInt(12)
        w.writeShort(12)
        w.writeShort(0)
        w.writeInt(16 + groups.size * 12)
        w.writeInt(0)
        w.writeInt(groups.size)
        for ((start, end, glyph) in groups) {
            w.writeInt(start)
            w.writeInt(end)
            w.writeInt(glyph)
        }
        return out.toByteArray()
    }

    private fun buildName(family: String): ByteArray {
        val nameBytes = family.toByteArray(Charsets.UTF_16BE)
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        w.writeShort(0)
        w.writeShort(1)
        w.writeShort(18)
        w.writeShort(3)
        w.writeShort(1)
        w.writeShort(0x0409)
        w.writeShort(1)
        w.writeShort(nameBytes.size)
        w.writeShort(0)
        w.write(nameBytes)
        return out.toByteArray()
    }

    private fun buildPost(format: Int, glyphCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        if (format == 2) {
            w.writeInt(0x00020000)
            w.writeInt(0)
            w.writeShort(-100)
            w.writeShort(50)
            w.writeInt(0)
            w.writeInt(0)
            w.writeInt(0)
            w.writeInt(0)
            w.writeInt(0)
            w.writeShort(glyphCount)
            for (i in 0 until glyphCount) w.writeShort(i)
        } else {
            w.writeInt(0x00030000)
            w.writeInt(0)
            w.writeShort(-100)
            w.writeShort(50)
            w.writeInt(0)
            w.writeInt(0)
            w.writeInt(0)
            w.writeInt(0)
            w.writeInt(0)
        }
        return out.toByteArray()
    }

    private fun assembleFont(sfntVersion: Int, tables: Map<String, ByteArray>): ByteArray {
        val sorted = tables.entries.sortedBy { it.key }
        val out = ByteArrayOutputStream()
        val w = DataOutputStream(out)
        w.writeInt(sfntVersion)
        w.writeShort(sorted.size)
        w.writeShort(16)
        w.writeShort(0)
        w.writeShort(sorted.size * 16 - 16)
        var offset = 12 + sorted.size * 16
        val offsets = LinkedHashMap<String, Int>()
        for ((tag, data) in sorted) {
            val padding = (4 - offset % 4) % 4
            offset += padding
            offsets[tag] = offset
            offset += data.size
        }
        for ((tag, data) in sorted) {
            w.write(tag.toByteArray(Charsets.US_ASCII))
            w.writeInt(0)
            w.writeInt(offsets.getValue(tag))
            w.writeInt(data.size)
        }
        for ((tag, data) in sorted) {
            while (out.size() < offsets.getValue(tag)) out.write(0)
            out.write(data)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------- font readers

    private fun readTable(bytes: ByteArray, tag: String): ByteArray? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getShort(4).toInt() and 0xFFFF
        for (i in 0 until count) {
            val entry = 12 + i * 16
            val entryTag = bytes.copyOfRange(entry, entry + 4).toString(Charsets.US_ASCII)
            if (entryTag == tag) {
                val offset = buffer.getInt(entry + 8)
                val length = buffer.getInt(entry + 12)
                return bytes.copyOfRange(offset, offset + length)
            }
        }
        return null
    }

    private fun readCmap12(bytes: ByteArray): Map<Int, Int> {
        val cmap = readTable(bytes, "cmap")!!
        val buffer = ByteBuffer.wrap(cmap).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getShort(2).toInt() and 0xFFFF
        for (i in 0 until count) {
            val record = 4 + i * 8
            val offset = buffer.getInt(record + 4)
            if (buffer.getShort(offset).toInt() and 0xFFFF == 12) {
                val groups = buffer.getInt(offset + 12)
                val result = LinkedHashMap<Int, Int>()
                for (g in 0 until groups) {
                    val base = offset + 16 + g * 12
                    val start = buffer.getInt(base)
                    val end = buffer.getInt(base + 4)
                    var glyph = buffer.getInt(base + 8)
                    for (cp in start..end) result[cp] = glyph++
                }
                return result
            }
        }
        return emptyMap()
    }

    private fun glyphBytes(fontBytes: ByteArray, glyphId: Int): ByteArray {
        val loca = readTable(fontBytes, "loca")!!
        val glyf = readTable(fontBytes, "glyf")!!
        val buffer = ByteBuffer.wrap(loca).order(ByteOrder.BIG_ENDIAN)
        val start = buffer.getInt(glyphId * 4)
        val end = buffer.getInt((glyphId + 1) * 4)
        return glyf.copyOfRange(start, end)
    }

    private fun glyphCoords(fontBytes: ByteArray, glyphId: Int): List<Pair<Int, Int>> {
        val bytes = glyphBytes(fontBytes, glyphId)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val contours = buffer.getShort(0).toInt()
        val endPts = IntArray(contours) { buffer.getShort(10 + it * 2).toInt() and 0xFFFF }
        val pointCount = if (contours == 0) 0 else endPts.last() + 1
        val instructionLength = buffer.getShort(10 + contours * 2).toInt() and 0xFFFF
        var cursor = 12 + contours * 2 + instructionLength
        val flags = ByteArray(pointCount)
        var point = 0
        while (point < pointCount) {
            val flag = bytes[cursor++].toInt() and 0xFF
            flags[point++] = flag.toByte()
            if (flag and 0x08 != 0) {
                val repeat = bytes[cursor++].toInt() and 0xFF
                repeat(repeat) { flags[point++] = flag.toByte() }
            }
        }
        val xs = IntArray(pointCount)
        val ys = IntArray(pointCount)
        var x = 0
        for (i in 0 until pointCount) {
            val flag = flags[i].toInt() and 0xFF
            x += when {
                flag and 0x02 != 0 -> {
                    val v = bytes[cursor++].toInt() and 0xFF
                    if (flag and 0x10 != 0) v else -v
                }
                flag and 0x10 != 0 -> 0
                else -> {
                    val v = buffer.getShort(cursor).toInt()
                    cursor += 2
                    v
                }
            }
            xs[i] = x
        }
        var y = 0
        for (i in 0 until pointCount) {
            val flag = flags[i].toInt() and 0xFF
            y += when {
                flag and 0x04 != 0 -> {
                    val v = bytes[cursor++].toInt() and 0xFF
                    if (flag and 0x20 != 0) v else -v
                }
                flag and 0x20 != 0 -> 0
                else -> {
                    val v = buffer.getShort(cursor).toInt()
                    cursor += 2
                    v
                }
            }
            ys[i] = y
        }
        return xs.indices.map { xs[it] to ys[it] }
    }

    private fun readCompositeComponent(fontBytes: ByteArray, glyphId: Int): Pair<Int, Pair<Int, Int>> {
        val bytes = glyphBytes(fontBytes, glyphId)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val flags = buffer.getShort(10).toInt() and 0xFFFF
        val glyphIndex = buffer.getShort(12).toInt() and 0xFFFF
        val dx = bytes[14].toInt()
        val dy = bytes[15].toInt()
        assertThat(flags and 0x0020).isEqualTo(0)
        return glyphIndex to (dx to dy)
    }

    private fun readCompositeScaleWords(fontBytes: ByteArray, glyphId: Int): List<Int> {
        val bytes = glyphBytes(fontBytes, glyphId)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val flags = buffer.getShort(10).toInt() and 0xFFFF
        assertThat(flags and 0x0040).isNotEqualTo(0)
        return listOf(
            buffer.getShort(16).toInt(),
            buffer.getShort(18).toInt(),
        )
    }

    private fun readMaxpNumGlyphs(fontBytes: ByteArray): Int {
        val maxp = readTable(fontBytes, "maxp")!!
        return ByteBuffer.wrap(maxp).order(ByteOrder.BIG_ENDIAN).getShort(4).toInt() and 0xFFFF
    }

    private fun readHeadUpm(fontBytes: ByteArray): Int {
        val head = readTable(fontBytes, "head")!!
        return ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN).getShort(18).toInt() and 0xFFFF
    }

    private fun readHmtx(fontBytes: ByteArray, glyphId: Int): Pair<Int, Int> {
        val hmtx = readTable(fontBytes, "hmtx")!!
        val buffer = ByteBuffer.wrap(hmtx).order(ByteOrder.BIG_ENDIAN)
        return (buffer.getShort(glyphId * 4).toInt() and 0xFFFF) to buffer.getShort(glyphId * 4 + 2).toInt()
    }

    private fun readName(fontBytes: ByteArray, nameId: Int): String? {
        val name = readTable(fontBytes, "name")!!
        val buffer = ByteBuffer.wrap(name).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.getShort(2).toInt() and 0xFFFF
        val stringOffset = buffer.getShort(4).toInt() and 0xFFFF
        for (i in 0 until count) {
            val record = 6 + i * 12
            if (buffer.getShort(record + 6).toInt() and 0xFFFF == nameId) {
                val length = buffer.getShort(record + 8).toInt() and 0xFFFF
                val offset = buffer.getShort(record + 10).toInt() and 0xFFFF
                val raw = name.copyOfRange(stringOffset + offset, stringOffset + offset + length)
                return String(raw, Charsets.UTF_16BE)
            }
        }
        return null
    }
}
