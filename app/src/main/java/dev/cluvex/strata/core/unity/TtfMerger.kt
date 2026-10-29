package dev.cluvex.strata.core.unity

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Merges two TrueType (glyf-outline) fonts: the result keeps the [baseFontBytes]
 * face exactly — its name, metrics, layout tables and every existing glyph — and
 * adds only the glyphs for codepoints the base face lacks, taken from
 * [addedFontBytes] and scaled to the base face's units-per-em. Codepoints present
 * in both faces keep the base face's glyph, so e.g. merging a Persian font into
 * Noto Sans leaves all Latin text pixel-identical and only adds the missing
 * presentation forms. The added face's GSUB/GPOS/GDEF are dropped (text reaching
 * the game is pre-shaped, so shaping tables are not needed for the new glyphs);
 * the base face's layout tables are copied verbatim and stay valid because
 * existing glyph ids never change.
 *
 * Rejects font collections, CFF outlines, variable fonts and malformed data
 * with [UnityFormatException]. Only cmap formats 4 and 12 are read; the merged
 * cmap is rebuilt from scratch in those formats.
 */
internal object TtfMerger {

    fun merge(baseFontBytes: ByteArray, addedFontBytes: ByteArray): ByteArray {
        val base = parse(baseFontBytes, "base")
        val added = parse(addedFontBytes, "added")

        val newCodepoints = added.cmap.keys.filter { it !in base.cmap }.sorted()
        if (newCodepoints.isEmpty()) return baseFontBytes.copyOf()

        val scale = base.unitsPerEm.toDouble() / added.unitsPerEm.toDouble()
        val newGlyphIdByAddedId = LinkedHashMap<Int, Int>()
        val appended = ArrayList<AppendedGlyph>()
        val queue = ArrayDeque<Int>()
        for (codepoint in newCodepoints) {
            val addedId = added.cmap.getValue(codepoint)
            if (addedId !in newGlyphIdByAddedId) {
                newGlyphIdByAddedId[addedId] = base.glyphCount + appended.size
                appended += AppendedGlyph(addedId, newGlyphIdByAddedId.getValue(addedId))
                queue += addedId
            }
        }
        // Transitive closure: composites may reference glyphs with no cmap entry.
        // Every reference resolves inside the added face, so each one is appended.
        while (queue.isNotEmpty()) {
            val addedId = queue.removeFirst()
            val glyph = parseGlyph(added, addedId)
            if (glyph is Glyph.Composite) {
                for (component in glyph.components) {
                    val ref = component.glyphIndex
                    if (ref in newGlyphIdByAddedId) continue
                    if (ref >= added.glyphCount) malformed("added font composite references glyph $ref out of bounds")
                    newGlyphIdByAddedId[ref] = base.glyphCount + appended.size
                    appended += AppendedGlyph(ref, newGlyphIdByAddedId.getValue(ref))
                    queue += ref
                }
            }
        }

        val newGlyphCount = base.glyphCount + appended.size
        if (newGlyphCount > 0xFFFF) malformed("merged font would exceed 65535 glyphs")

        val encoded = appended.map { pending ->
            val glyph = parseGlyph(added, pending.addedId)
            val raw = glyphBytes(added, pending.addedId)
            val sourceBbox = if (raw.size >= 10) {
                val rawBuffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
                intArrayOf(
                    s16(rawBuffer, 2), s16(rawBuffer, 4), s16(rawBuffer, 6), s16(rawBuffer, 8),
                )
            } else {
                intArrayOf(0, 0, 0, 0)
            }
            val stats = glyphStats(added, pending.addedId, glyph)
            val bytes = encodeGlyph(glyph, sourceBbox, scale, newGlyphIdByAddedId)
            val advance = (added.advances[pending.addedId] * scale).roundToInt()
            val lsb = (added.lsbs[pending.addedId] * scale).roundToInt()
            AppendedGlyphData(pending.newId, bytes, stats, advance, lsb)
        }

        val mergedCmap = LinkedHashMap<Int, Int>()
        mergedCmap.putAll(base.cmap)
        for (codepoint in newCodepoints) {
            mergedCmap[codepoint] = newGlyphIdByAddedId.getValue(added.cmap.getValue(codepoint))
        }

        return buildFont(base, encoded, mergedCmap, newGlyphCount)
    }

    // ------------------------------------------------------------------ parse

    private class ParsedFont(
        val sfntVersion: Int,
        val tables: Map<Int, TableSlice>,
        val unitsPerEm: Int,
        val indexToLocFormat: Int,
        val glyphCount: Int,
        val glyphRanges: List<IntRange>,
        val glyfBytes: ByteArray,
        val cmap: Map<Int, Int>,
        val advances: IntArray,
        val lsbs: IntArray,
        val numberOfHMetrics: Int,
        val headBytes: ByteArray,
        val hheaBytes: ByteArray,
        val maxpBytes: ByteArray,
        val postBytes: ByteArray?,
        /** Every table the merger copies verbatim (name, GSUB, ...), keyed by tag. */
        val verbatimTables: Map<Int, ByteArray>,
    )

    private class TableSlice(val offset: Int, val length: Int)

    private class AppendedGlyph(val addedId: Int, val newId: Int)

    private class AppendedGlyphData(
        val newId: Int,
        val bytes: ByteArray,
        val stats: GlyphStats,
        val advance: Int,
        val lsb: Int,
    )

    private class GlyphStats(
        val points: Int,
        val contours: Int,
        val components: Int,
        val depth: Int,
        val instructionLength: Int,
    )

    private fun parse(bytes: ByteArray, role: String): ParsedFont {
        if (bytes.size < 12) malformed("$role font is truncated")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.getInt(0)
        if (magic == 0x74746366) malformed("$role font is a TrueType collection, which cannot be merged")
        if (magic != 0x00010000 && magic != 0x4F54544F && magic != 0x74727565) {
            malformed("$role font is not a valid TrueType/OpenType (sfnt) font")
        }
        val tableCount = u16(buffer, 4)
        if (tableCount !in 1..256 || !fits(12L, tableCount.toLong() * 16L, bytes.size)) {
            malformed("$role font table directory is out of bounds")
        }
        val tables = LinkedHashMap<Int, TableSlice>()
        repeat(tableCount) { index ->
            val entry = 12 + index * 16
            val tag = buffer.getInt(entry)
            val offset = buffer.getInt(entry + 8)
            val length = buffer.getInt(entry + 12)
            if (offset < 0 || length < 0 || !fits(offset.toLong(), length.toLong(), bytes.size)) {
                malformed("$role font table ${tagName(tag)} is out of bounds")
            }
            tables[tag] = TableSlice(offset, length)
        }
        val cmapSlice = tables[TAG_CMAP] ?: malformed("$role font has no cmap table")
        val headSlice = tables[TAG_HEAD] ?: malformed("$role font has no head table")
        val maxpSlice = tables[TAG_MAXP] ?: malformed("$role font has no maxp table")
        val hheaSlice = tables[TAG_HHEA] ?: malformed("$role font has no hhea table")
        val hmtxSlice = tables[TAG_HMTX] ?: malformed("$role font has no hmtx table")
        val locaSlice = tables[TAG_LOCA] ?: malformed("$role font has no loca table")
        val glyfSlice = tables[TAG_GLYF] ?: malformed("$role font has no glyf table (CFF outlines cannot be merged)")
        if (TAG_FVAR in tables || TAG_CFF in tables || TAG_CFF2 in tables) {
            malformed("$role font is a variable or CFF font, which cannot be merged")
        }
        if (headSlice.length < 54) malformed("$role font head table is truncated")
        val unitsPerEm = u16(buffer, headSlice.offset + 18)
        if (unitsPerEm == 0) malformed("$role font has zero units-per-em")
        val indexToLocFormat = s16(buffer, headSlice.offset + 50)
        if (indexToLocFormat !in 0..1) malformed("$role font has an unsupported loca format")
        if (maxpSlice.length < 6) malformed("$role font maxp table is truncated")
        val glyphCount = u16(buffer, maxpSlice.offset + 4)
        if (glyphCount == 0) malformed("$role font has no glyphs")
        if (glyphCount > MAX_GLYPHS) malformed("$role font glyph count exceeds the limit")
        if (hheaSlice.length < 36) malformed("$role font hhea table is truncated")
        val numberOfHMetrics = u16(buffer, hheaSlice.offset + 34)
        if (numberOfHMetrics == 0 || numberOfHMetrics > glyphCount) {
            malformed("$role font hhea numberOfHMetrics is invalid")
        }
        val locaCount = glyphCount + 1
        val locaEntrySize = if (indexToLocFormat == 0) 2 else 4
        if (!fits(locaSlice.offset.toLong(), locaCount.toLong() * locaEntrySize, bytes.size)) {
            malformed("$role font loca table is truncated")
        }
        val offsets = IntArray(locaCount)
        for (i in 0 until locaCount) {
            offsets[i] = if (indexToLocFormat == 0) {
                u16(buffer, locaSlice.offset + i * 2) * 2
            } else {
                buffer.getInt(locaSlice.offset + i * 4)
            }
            if (offsets[i] < 0 || offsets[i] > glyfSlice.length) {
                malformed("$role font loca offset $i is out of bounds")
            }
            if (i > 0 && offsets[i] < offsets[i - 1]) malformed("$role font loca offsets are not monotonic")
        }
        val glyphRanges = ArrayList<IntRange>(glyphCount)
        for (i in 0 until glyphCount) glyphRanges += offsets[i] until offsets[i + 1]
        val glyfBytes = bytes.copyOfRange(glyfSlice.offset, glyfSlice.offset + glyfSlice.length)

        val cmap = readCmap(buffer, cmapSlice, bytes.size)
        val advances = IntArray(glyphCount)
        val lsbs = IntArray(glyphCount)
        val hmtxNeed = numberOfHMetrics * 4 + (glyphCount - numberOfHMetrics) * 2
        if (!fits(hmtxSlice.offset.toLong(), hmtxNeed.toLong(), bytes.size)) {
            malformed("$role font hmtx table is truncated")
        }
        for (i in 0 until glyphCount) {
            if (i < numberOfHMetrics) {
                advances[i] = u16(buffer, hmtxSlice.offset + i * 4)
                lsbs[i] = s16(buffer, hmtxSlice.offset + i * 4 + 2)
            } else {
                advances[i] = advances[numberOfHMetrics - 1]
                lsbs[i] = s16(buffer, hmtxSlice.offset + numberOfHMetrics * 4 + (i - numberOfHMetrics) * 2)
            }
        }
        return ParsedFont(
            sfntVersion = magic,
            tables = tables,
            unitsPerEm = unitsPerEm,
            indexToLocFormat = indexToLocFormat,
            glyphCount = glyphCount,
            glyphRanges = glyphRanges,
            glyfBytes = glyfBytes,
            cmap = cmap,
            advances = advances,
            lsbs = lsbs,
            numberOfHMetrics = numberOfHMetrics,
            headBytes = bytes.copyOfRange(headSlice.offset, headSlice.offset + headSlice.length),
            hheaBytes = bytes.copyOfRange(hheaSlice.offset, hheaSlice.offset + hheaSlice.length),
            maxpBytes = bytes.copyOfRange(maxpSlice.offset, maxpSlice.offset + maxpSlice.length),
            postBytes = tables[TAG_POST]?.let { bytes.copyOfRange(it.offset, it.offset + it.length) },
            verbatimTables = tables
                .filterKeys {
                    it != TAG_CMAP && it != TAG_HEAD && it != TAG_MAXP && it != TAG_HHEA &&
                        it != TAG_HMTX && it != TAG_LOCA && it != TAG_GLYF && it != TAG_POST
                }
                .mapValues { (_, slice) -> bytes.copyOfRange(slice.offset, slice.offset + slice.length) },
        )
    }

    /** Reads cmap formats 4 and 12 into a codepoint→glyph map; format 12 wins over 4. */
    private fun readCmap(buffer: ByteBuffer, cmapSlice: TableSlice, fileSize: Int): Map<Int, Int> {
        val cmapOffset = cmapSlice.offset
        val cmapEnd = cmapOffset + cmapSlice.length
        if (cmapSlice.length < 4 || !fits(cmapOffset.toLong(), cmapSlice.length.toLong(), fileSize)) {
            malformed("cmap table is out of bounds")
        }
        val subtableCount = u16(buffer, cmapOffset + 2)
        if (subtableCount !in 1..MAX_CMAP_SUBTABLES ||
            !fits((cmapOffset + 4).toLong(), subtableCount.toLong() * 8L, cmapEnd)
        ) {
            malformed("cmap encoding records are invalid")
        }
        val format4 = LinkedHashMap<Int, Int>()
        val format12 = LinkedHashMap<Int, Int>()
        repeat(subtableCount) { index ->
            val record = cmapOffset + 4 + index * 8
            val relative = u32(buffer, record + 4)
            val subtable = cmapOffset.toLong() + relative
            if (subtable < cmapOffset || !fits(subtable, 4L, cmapEnd)) malformed("cmap subtable offset is out of bounds")
            val offset = subtable.toInt()
            when (u16(buffer, offset)) {
                4 -> readFormat4(buffer, offset, cmapEnd, format4)
                12 -> readFormat12(buffer, offset, cmapEnd, format12)
            }
        }
        val merged = LinkedHashMap<Int, Int>()
        merged.putAll(format4)
        merged.putAll(format12)
        return merged
    }

    private fun readFormat4(buffer: ByteBuffer, offset: Int, parentEnd: Int, out: MutableMap<Int, Int>) {
        if (!fits(offset.toLong(), 14L, parentEnd)) malformed("truncated cmap format 4 header")
        val length = u16(buffer, offset + 2)
        if (length < 16 || !fits(offset.toLong(), length.toLong(), parentEnd)) malformed("cmap format 4 length is invalid")
        val end = offset + length
        val segmentCountX2 = u16(buffer, offset + 6)
        if (segmentCountX2 == 0 || segmentCountX2 and 1 != 0) malformed("cmap format 4 segment count is invalid")
        val segmentCount = segmentCountX2 / 2
        val endCodesOffset = offset + 14
        val startCodesOffset = endCodesOffset + segmentCountX2 + 2
        val deltasOffset = startCodesOffset + segmentCountX2
        val rangeOffsetsOffset = deltasOffset + segmentCountX2
        if (!fits(rangeOffsetsOffset.toLong(), segmentCountX2.toLong(), end)) malformed("cmap format 4 arrays are out of bounds")
        var visits = 0L
        repeat(segmentCount) { index ->
            val start = u16(buffer, startCodesOffset + index * 2)
            val finish = u16(buffer, endCodesOffset + index * 2)
            if (start == 0xFFFF && finish == 0xFFFF) return@repeat
            if (start > finish) malformed("cmap format 4 segment is reversed")
            visits += finish.toLong() - start + 1L
            if (visits > MAX_FORMAT4_VISITS) malformed("cmap format 4 exceeds the visit budget")
            val delta = buffer.getShort(deltasOffset + index * 2).toInt()
            val rangeOffsetPosition = rangeOffsetsOffset + index * 2
            val rangeOffset = u16(buffer, rangeOffsetPosition)
            for (codepoint in start..finish) {
                val glyph = if (rangeOffset == 0) {
                    (codepoint + delta) and 0xFFFF
                } else {
                    val glyphAddress = rangeOffsetPosition.toLong() + rangeOffset + 2L * (codepoint - start)
                    if (!fits(glyphAddress, 2L, end)) malformed("cmap format 4 glyph address is out of bounds")
                    val raw = u16(buffer, glyphAddress.toInt())
                    if (raw == 0) 0 else (raw + delta) and 0xFFFF
                }
                if (glyph != 0) out[codepoint] = glyph
            }
        }
    }

    private fun readFormat12(buffer: ByteBuffer, offset: Int, parentEnd: Int, out: MutableMap<Int, Int>) {
        if (!fits(offset.toLong(), 16L, parentEnd)) malformed("truncated cmap format 12 header")
        val length = u32(buffer, offset + 4)
        if (length < 16L || !fits(offset.toLong(), length, parentEnd)) malformed("cmap format 12 length is invalid")
        val groupCountLong = u32(buffer, offset + 12)
        if (groupCountLong !in 0L..MAX_FORMAT12_GROUPS.toLong()) malformed("cmap format 12 group count is invalid")
        val groupCount = groupCountLong.toInt()
        val groupsOffset = offset + 16
        if (!fits(groupsOffset.toLong(), groupCountLong * 12L, offset.toLong() + length)) {
            malformed("cmap format 12 groups are out of bounds")
        }
        repeat(groupCount) { index ->
            val base = groupsOffset + index * 12
            val rawStart = u32(buffer, base)
            val rawEnd = u32(buffer, base + 4)
            val startGlyph = u32(buffer, base + 8)
            if (rawStart > rawEnd) malformed("cmap format 12 group is reversed")
            if (rawEnd > Character.MAX_CODE_POINT.toLong()) malformed("cmap format 12 group exceeds Unicode")
            if (startGlyph > 0xFFFFL) malformed("cmap format 12 glyph id is out of range")
            var glyph = startGlyph
            var codepoint = rawStart
            while (codepoint <= rawEnd) {
                if (glyph != 0L) out[codepoint.toInt()] = glyph.toInt()
                glyph++
                codepoint++
                if (codepoint - rawStart > MAX_FORMAT12_VISITS) malformed("cmap format 12 exceeds the visit budget")
            }
        }
    }

    private fun tagName(tag: Int): String {
        val bytes = byteArrayOf(
            (tag ushr 24).toByte(), (tag ushr 16).toByte(), (tag ushr 8).toByte(), tag.toByte(),
        )
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun malformed(detail: String): Nothing =
        throw UnityFormatException("Invalid font for merging: $detail")

    private fun fits(offset: Long, length: Long, limit: Int): Boolean =
        offset >= 0L && length >= 0L && offset <= limit.toLong() && length <= limit.toLong() - offset

    private fun fits(offset: Long, length: Long, limit: Long): Boolean =
        offset >= 0L && length >= 0L && offset <= limit && length <= limit - offset

    private fun u16(buffer: ByteBuffer, offset: Int): Int =
        buffer.getShort(offset).toInt() and 0xFFFF

    private fun s16(buffer: ByteBuffer, offset: Int): Int =
        buffer.getShort(offset).toInt()

    private fun u32(buffer: ByteBuffer, offset: Int): Long =
        buffer.getInt(offset).toLong() and 0xFFFF_FFFFL

    // ------------------------------------------------------------------ glyphs

    private sealed interface Glyph {
        data object Empty : Glyph
        data class Simple(
            val endPts: IntArray,
            val instructions: ByteArray,
            val flags: ByteArray,
            val xs: IntArray,
            val ys: IntArray,
        ) : Glyph

        data class Composite(
            val components: List<Component>,
            val instructions: ByteArray,
        ) : Glyph
    }

    private class Component(
        val flags: Int,
        val glyphIndex: Int,
        val arg1: Int,
        val arg2: Int,
        val argsAreXy: Boolean,
        /** Raw scale words (F2DOT14): null, 1, 2 or 4 entries, copied verbatim. */
        val scaleWords: IntArray,
    )

    private fun glyphBytes(font: ParsedFont, glyphId: Int): ByteArray {
        val range = font.glyphRanges[glyphId]
        // IntRange is inclusive on both ends; copyOfRange's end is exclusive.
        return font.glyfBytes.copyOfRange(range.first, range.last + 1)
    }

    private fun parseGlyph(font: ParsedFont, glyphId: Int): Glyph {
        val bytes = glyphBytes(font, glyphId)
        if (bytes.isEmpty()) return Glyph.Empty
        if (bytes.size < 10) malformed("glyph $glyphId is truncated")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val numberOfContours = s16(buffer, 0)
        if (numberOfContours >= 0) {
            val endPts = IntArray(numberOfContours) { u16(buffer, 10 + it * 2) }
            val instructionLength = u16(buffer, 10 + numberOfContours * 2)
            val instructionStart = 12 + numberOfContours * 2
            if (!fits(instructionStart.toLong(), instructionLength.toLong(), bytes.size)) {
                malformed("glyph $glyphId instructions are out of bounds")
            }
            val instructions = bytes.copyOfRange(instructionStart, instructionStart + instructionLength)
            var cursor = instructionStart + instructionLength
            val pointCount = if (numberOfContours == 0) 0 else endPts.last() + 1
            if (pointCount > MAX_GLYPH_POINTS) malformed("glyph $glyphId exceeds the point limit")
            val flags = ByteArray(pointCount)
            var point = 0
            while (point < pointCount) {
                if (cursor >= bytes.size) malformed("glyph $glyphId flags are truncated")
                val flag = bytes[cursor++].toInt() and 0xFF
                flags[point++] = flag.toByte()
                if (flag and FLAG_REPEAT != 0) {
                    if (cursor >= bytes.size) malformed("glyph $glyphId repeat count is truncated")
                    val repeat = bytes[cursor++].toInt() and 0xFF
                    if (point + repeat > pointCount) malformed("glyph $glyphId repeat overruns its points")
                    repeat(repeat) { flags[point++] = flag.toByte() }
                }
            }
            val xs = IntArray(pointCount)
            val ys = IntArray(pointCount)
            var x = 0
            for (i in 0 until pointCount) {
                val flag = flags[i].toInt() and 0xFF
                x += when {
                    flag and FLAG_X_SHORT != 0 -> {
                        if (cursor >= bytes.size) malformed("glyph $glyphId x coordinates are truncated")
                        val value = bytes[cursor++].toInt() and 0xFF
                        if (flag and FLAG_X_SAME != 0) value else -value
                    }
                    flag and FLAG_X_SAME != 0 -> 0
                    else -> {
                        if (cursor + 1 >= bytes.size) malformed("glyph $glyphId x coordinates are truncated")
                        val value = s16(buffer, cursor)
                        cursor += 2
                        value
                    }
                }
                xs[i] = x
            }
            var y = 0
            for (i in 0 until pointCount) {
                val flag = flags[i].toInt() and 0xFF
                y += when {
                    flag and FLAG_Y_SHORT != 0 -> {
                        if (cursor >= bytes.size) malformed("glyph $glyphId y coordinates are truncated")
                        val value = bytes[cursor++].toInt() and 0xFF
                        if (flag and FLAG_Y_SAME != 0) value else -value
                    }
                    flag and FLAG_Y_SAME != 0 -> 0
                    else -> {
                        if (cursor + 1 >= bytes.size) malformed("glyph $glyphId y coordinates are truncated")
                        val value = s16(buffer, cursor)
                        cursor += 2
                        value
                    }
                }
                ys[i] = y
            }
            return Glyph.Simple(endPts, instructions, flags, xs, ys)
        }
        // Composite glyph.
        val components = ArrayList<Component>()
        var cursor = 10
        var instructions = ByteArray(0)
        while (true) {
            if (cursor + 4 > bytes.size) malformed("composite glyph $glyphId is truncated")
            val flags = u16(buffer, cursor)
            val glyphIndex = u16(buffer, cursor + 2)
            cursor += 4
            val words = flags and FLAG_ARG_WORDS != 0
            val arg1: Int
            val arg2: Int
            if (words) {
                if (cursor + 4 > bytes.size) malformed("composite glyph $glyphId arguments are truncated")
                arg1 = s16(buffer, cursor)
                arg2 = s16(buffer, cursor + 2)
                cursor += 4
            } else {
                if (cursor + 2 > bytes.size) malformed("composite glyph $glyphId arguments are truncated")
                arg1 = bytes[cursor].toInt()
                arg2 = bytes[cursor + 1].toInt()
                cursor += 2
            }
            val scaleWords = when {
                flags and FLAG_WE_HAVE_SCALE != 0 -> {
                    if (cursor + 2 > bytes.size) malformed("composite glyph $glyphId scale is truncated")
                    val word = s16(buffer, cursor)
                    cursor += 2
                    intArrayOf(word)
                }
                flags and FLAG_WE_HAVE_XY_SCALE != 0 -> {
                    if (cursor + 4 > bytes.size) malformed("composite glyph $glyphId scale is truncated")
                    val words = intArrayOf(s16(buffer, cursor), s16(buffer, cursor + 2))
                    cursor += 4
                    words
                }
                flags and FLAG_WE_HAVE_2X2 != 0 -> {
                    if (cursor + 8 > bytes.size) malformed("composite glyph $glyphId scale is truncated")
                    val words = IntArray(4) { s16(buffer, cursor + it * 2) }
                    cursor += 8
                    words
                }
                else -> intArrayOf()
            }
            components += Component(flags, glyphIndex, arg1, arg2, flags and FLAG_ARGS_XY != 0, scaleWords)
            if (flags and FLAG_MORE_COMPONENTS == 0) {
                if (flags and FLAG_WE_HAVE_INSTRUCTIONS != 0) {
                    if (cursor + 2 > bytes.size) malformed("composite glyph $glyphId instructions are truncated")
                    val length = u16(buffer, cursor)
                    if (!fits((cursor + 2).toLong(), length.toLong(), bytes.size)) {
                        malformed("composite glyph $glyphId instructions are out of bounds")
                    }
                    instructions = bytes.copyOfRange(cursor + 2, cursor + 2 + length)
                }
                break
            }
            if (components.size > MAX_COMPONENTS) malformed("composite glyph $glyphId exceeds the component limit")
        }
        return Glyph.Composite(components, instructions)
    }

    /** Stats for maxp, computed over the added face's glyph graph. */
    private fun glyphStats(font: ParsedFont, glyphId: Int, glyph: Glyph): GlyphStats {
        return glyphStatsRecursive(font, glyphId, glyph, LinkedHashSet(), 0)
    }

    private fun glyphStatsRecursive(
        font: ParsedFont,
        glyphId: Int,
        glyph: Glyph,
        visiting: LinkedHashSet<Int>,
        depth: Int,
    ): GlyphStats {
        if (depth > MAX_COMPONENT_DEPTH) malformed("composite glyph nesting exceeds the limit")
        return when (glyph) {
            is Glyph.Empty -> GlyphStats(0, 0, 0, depth, 0)
            is Glyph.Simple -> GlyphStats(
                points = glyph.xs.size,
                contours = glyph.endPts.size,
                components = 0,
                depth = depth,
                instructionLength = glyph.instructions.size,
            )
            is Glyph.Composite -> {
                if (!visiting.add(glyphId)) malformed("composite glyph cycle detected at glyph $glyphId")
                var points = 0
                var contours = 0
                var maxDepth = depth
                for (component in glyph.components) {
                    val ref = component.glyphIndex
                    if (ref >= font.glyphCount) malformed("composite references glyph $ref out of bounds")
                    val stats = glyphStatsRecursive(
                        font, ref, parseGlyph(font, ref), visiting, depth + 1,
                    )
                    points += stats.points
                    contours += stats.contours
                    if (stats.depth > maxDepth) maxDepth = stats.depth
                }
                visiting.remove(glyphId)
                GlyphStats(points, contours, glyph.components.size, maxDepth, glyph.instructions.size)
            }
        }
    }

    private fun encodeGlyph(
        glyph: Glyph,
        sourceBbox: IntArray,
        scale: Double,
        remap: Map<Int, Int>,
    ): ByteArray {
        val out = Writer()
        when (glyph) {
            is Glyph.Empty -> return ByteArray(0)
            is Glyph.Simple -> {
                val scaledXs = IntArray(glyph.xs.size) { clampS16((glyph.xs[it] * scale).roundToInt()) }
                val scaledYs = IntArray(glyph.ys.size) { clampS16((glyph.ys[it] * scale).roundToInt()) }
                out.s16(glyph.endPts.size)
                out.s16(bboxMin(scaledXs))
                out.s16(bboxMin(scaledYs))
                out.s16(bboxMax(scaledXs))
                out.s16(bboxMax(scaledYs))
                for (endPt in glyph.endPts) out.u16(endPt)
                out.u16(glyph.instructions.size)
                out.bytes(glyph.instructions)
                // Canonical flag encoding from deltas.
                val flags = ByteArray(glyph.xs.size)
                var prevX = 0
                var prevY = 0
                for (i in glyph.xs.indices) {
                    var flag = 0
                    val dx = scaledXs[i] - prevX
                    val dy = scaledYs[i] - prevY
                    prevX = scaledXs[i]
                    prevY = scaledYs[i]
                    flag = flag or deltaFlag(dx, FLAG_X_SHORT, FLAG_X_SAME)
                    flag = flag or deltaFlag(dy, FLAG_Y_SHORT, FLAG_Y_SAME)
                    // Preserve the on-curve bit from the source glyph.
                    flag = flag or (glyph.flags[i].toInt() and FLAG_ON_CURVE)
                    flags[i] = flag.toByte()
                }
                // Emit flags with repeat compression.
                var i = 0
                while (i < flags.size) {
                    val flag = flags[i].toInt() and 0xFF
                    var repeat = 0
                    while (i + 1 + repeat < flags.size && (flags[i + 1 + repeat].toInt() and 0xFF) == flag && repeat < 255) {
                        repeat++
                    }
                    if (repeat > 0) {
                        out.u8(flag or FLAG_REPEAT)
                        out.u8(repeat)
                    } else {
                        out.u8(flag)
                    }
                    i += 1 + repeat
                }
                prevX = 0
                for (idx in glyph.xs.indices) {
                    val dx = scaledXs[idx] - prevX
                    prevX = scaledXs[idx]
                    writeDelta(out, dx, FLAG_X_SHORT, FLAG_X_SAME)
                }
                prevY = 0
                for (idx in glyph.ys.indices) {
                    val dy = scaledYs[idx] - prevY
                    prevY = scaledYs[idx]
                    writeDelta(out, dy, FLAG_Y_SHORT, FLAG_Y_SAME)
                }
            }
            is Glyph.Composite -> {
                out.s16(-1)
                // Scaled copy of the source glyph's stored bounding box.
                for (value in sourceBbox) out.s16(clampS16((value * scale).roundToInt()))
                for (component in glyph.components) {
                    val target = remap[component.glyphIndex]
                        ?: malformed("composite references unmapped glyph ${component.glyphIndex}")
                    out.u16(component.flags)
                    out.u16(target)
                    val words = component.flags and FLAG_ARG_WORDS != 0
                    if (component.argsAreXy) {
                        val ax = clampArg((component.arg1 * scale).roundToInt(), words)
                        val ay = clampArg((component.arg2 * scale).roundToInt(), words)
                        if (words) {
                            out.s16(ax)
                            out.s16(ay)
                        } else {
                            out.u8(ax)
                            out.u8(ay)
                        }
                    } else {
                        if (words) {
                            out.s16(component.arg1)
                            out.s16(component.arg2)
                        } else {
                            out.u8(component.arg1)
                            out.u8(component.arg2)
                        }
                    }
                    for (word in component.scaleWords) out.s16(word)
                }
                out.u16(glyph.instructions.size)
                out.bytes(glyph.instructions)
            }
        }
        return out.toByteArray()
    }

    /** A delta's flag bits: short form when it fits in a byte, "same" when zero. */
    private fun deltaFlag(delta: Int, shortBit: Int, sameBit: Int): Int {
        return when {
            delta == 0 -> sameBit
            delta in -255..255 -> shortBit or (if (delta > 0) sameBit else 0)
            else -> 0
        }
    }

    private fun writeDelta(out: Writer, delta: Int, shortBit: Int, sameBit: Int) {
        val flag = deltaFlag(delta, shortBit, sameBit)
        when {
            flag and shortBit != 0 -> out.u8(kotlin.math.abs(delta))
            flag and sameBit != 0 -> Unit // zero delta: no bytes
            else -> out.s16(delta)
        }
    }

    private fun bboxMin(values: IntArray): Int = if (values.isEmpty()) 0 else values.min()
    private fun bboxMax(values: IntArray): Int = if (values.isEmpty()) 0 else values.max()
    private fun clampS16(value: Int): Int = value.coerceIn(-32768, 32767)
    private fun clampArg(value: Int, words: Boolean): Int =
        if (words) clampS16(value) else value.coerceIn(-128, 127)

    private const val FLAG_ON_CURVE = 0x01
    private const val FLAG_X_SHORT = 0x02
    private const val FLAG_Y_SHORT = 0x04
    private const val FLAG_REPEAT = 0x08
    private const val FLAG_X_SAME = 0x10
    private const val FLAG_Y_SAME = 0x20
    private const val FLAG_ARG_WORDS = 0x0001
    private const val FLAG_ARGS_XY = 0x0002
    private const val FLAG_WE_HAVE_SCALE = 0x0008
    private const val FLAG_MORE_COMPONENTS = 0x0020
    private const val FLAG_WE_HAVE_XY_SCALE = 0x0040
    private const val FLAG_WE_HAVE_2X2 = 0x0080
    private const val FLAG_WE_HAVE_INSTRUCTIONS = 0x0100
    private const val MAX_GLYPH_POINTS = 10_000
    private const val MAX_COMPONENTS = 1_024
    // ------------------------------------------------------------------ build

    private fun buildFont(
        base: ParsedFont,
        appended: List<AppendedGlyphData>,
        mergedCmap: Map<Int, Int>,
        newGlyphCount: Int,
    ): ByteArray {
        // Glyph byte streams: base glyphs verbatim, appended glyphs encoded.
        val glyphStreams = ArrayList<ByteArray>(newGlyphCount)
        for (i in 0 until base.glyphCount) glyphStreams += glyphBytes(base, i)
        val appendedById = appended.associateBy { it.newId }
        for (id in base.glyphCount until newGlyphCount) {
            glyphStreams += appendedById.getValue(id).bytes
        }
        // Pad every glyph to an even length so short loca offsets stay integral.
        val padded = glyphStreams.map { bytes ->
            if (bytes.size and 1 == 0) bytes else bytes + byteArrayOf(0)
        }
        val glyfData: ByteArray
        val locaOffsets: IntArray
        run {
            val out = Writer()
            locaOffsets = IntArray(newGlyphCount + 1)
            for (i in padded.indices) {
                locaOffsets[i] = out.size
                out.bytes(padded[i])
            }
            locaOffsets[newGlyphCount] = out.size
            glyfData = out.toByteArray()
        }

        // Stats over every glyph in the merged face.
        var maxPoints = 0
        var maxContours = 0
        var maxCompositePoints = 0
        var maxCompositeContours = 0
        var maxComponentElements = 0
        var maxComponentDepth = 0
        var maxInstructionLength = 0
        var headXMin = 0
        var headYMin = 0
        var headXMax = 0
        var headYMax = 0
        var firstBox = true
        val baseStats = ArrayList<GlyphStats>(base.glyphCount)
        for (i in 0 until base.glyphCount) {
            val stats = glyphStats(base, i, parseGlyph(base, i))
            baseStats += stats
            val box = glyphBox(base, i)
            if (box != null) {
                if (firstBox) {
                    headXMin = box[0]
                    headYMin = box[1]
                    headXMax = box[2]
                    headYMax = box[3]
                    firstBox = false
                } else {
                    if (box[0] < headXMin) headXMin = box[0]
                    if (box[1] < headYMin) headYMin = box[1]
                    if (box[2] > headXMax) headXMax = box[2]
                    if (box[3] > headYMax) headYMax = box[3]
                }
            }
        }
        for (data in appended) {
            val stats = data.stats
            if (stats.components == 0) {
                if (stats.points > maxPoints) maxPoints = stats.points
                if (stats.contours > maxContours) maxContours = stats.contours
            } else {
                if (stats.points > maxCompositePoints) maxCompositePoints = stats.points
                if (stats.contours > maxCompositeContours) maxCompositeContours = stats.contours
                if (stats.components > maxComponentElements) maxComponentElements = stats.components
                if (stats.depth > maxComponentDepth) maxComponentDepth = stats.depth
            }
            if (stats.instructionLength > maxInstructionLength) maxInstructionLength = stats.instructionLength
            val box = glyphBoxFromBytes(data.bytes)
            if (box != null) {
                if (firstBox) {
                    headXMin = box[0]
                    headYMin = box[1]
                    headXMax = box[2]
                    headYMax = box[3]
                    firstBox = false
                } else {
                    if (box[0] < headXMin) headXMin = box[0]
                    if (box[1] < headYMin) headYMin = box[1]
                    if (box[2] > headXMax) headXMax = box[2]
                    if (box[3] > headYMax) headYMax = box[3]
                }
            }
        }
        for (stats in baseStats) {
            if (stats.components == 0) {
                if (stats.points > maxPoints) maxPoints = stats.points
                if (stats.contours > maxContours) maxContours = stats.contours
            } else {
                if (stats.points > maxCompositePoints) maxCompositePoints = stats.points
                if (stats.contours > maxCompositeContours) maxCompositeContours = stats.contours
                if (stats.components > maxComponentElements) maxComponentElements = stats.components
                if (stats.depth > maxComponentDepth) maxComponentDepth = stats.depth
            }
            if (stats.instructionLength > maxInstructionLength) maxInstructionLength = stats.instructionLength
        }

        // hmtx: one metric per glyph.
        val hmtxData: ByteArray
        var advanceWidthMax = 0
        var minLeftSideBearing = 0
        var minRightSideBearing = 0
        var xMaxExtent = 0
        var firstMetric = true
        run {
            val out = Writer()
            for (i in 0 until newGlyphCount) {
                val advance: Int
                val lsb: Int
                if (i < base.glyphCount) {
                    advance = base.advances[i]
                    lsb = base.lsbs[i]
                } else {
                    val data = appendedById.getValue(i)
                    advance = data.advance
                    lsb = data.lsb
                }
                out.u16(advance)
                out.s16(lsb)
                val box = if (i < base.glyphCount) glyphBox(base, i) else glyphBoxFromBytes(appendedById.getValue(i).bytes)
                val extent = if (box == null) lsb else lsb + (box[2] - box[0])
                val rightBearing = advance - lsb - (if (box == null) 0 else box[2] - box[0])
                if (firstMetric) {
                    advanceWidthMax = advance
                    minLeftSideBearing = lsb
                    minRightSideBearing = rightBearing
                    xMaxExtent = extent
                    firstMetric = false
                } else {
                    if (advance > advanceWidthMax) advanceWidthMax = advance
                    if (lsb < minLeftSideBearing) minLeftSideBearing = lsb
                    if (rightBearing < minRightSideBearing) minRightSideBearing = rightBearing
                    if (extent > xMaxExtent) xMaxExtent = extent
                }
            }
            hmtxData = out.toByteArray()
        }

        val tables = LinkedHashMap<Int, ByteArray>()
        tables.putAll(base.verbatimTables)
        tables[TAG_CMAP] = buildCmap(mergedCmap, newGlyphCount)
        tables[TAG_GLYF] = glyfData
        tables[TAG_LOCA] = buildLoca(locaOffsets, base.indexToLocFormat)
        tables[TAG_HEAD] = buildHead(base, headXMin, headYMin, headXMax, headYMax)
        tables[TAG_HHEA] = buildHhea(base, newGlyphCount, advanceWidthMax, minLeftSideBearing, minRightSideBearing, xMaxExtent)
        tables[TAG_HMTX] = hmtxData
        tables[TAG_MAXP] = buildMaxp(base, newGlyphCount, maxPoints, maxContours, maxCompositePoints, maxCompositeContours, maxComponentElements, maxComponentDepth, maxInstructionLength)
        base.postBytes?.let { tables[TAG_POST] = buildPost(it, base.glyphCount, newGlyphCount) }
        return assembleSfnt(base.sfntVersion, tables)
    }

    /** Base glyph bounding box from its stored header, or null when empty. */
    private fun glyphBox(font: ParsedFont, glyphId: Int): IntArray? {
        val bytes = glyphBytes(font, glyphId)
        return glyphBoxFromBytes(bytes)
    }

    private fun glyphBoxFromBytes(bytes: ByteArray): IntArray? {
        if (bytes.size < 10) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return intArrayOf(s16(buffer, 2), s16(buffer, 4), s16(buffer, 6), s16(buffer, 8))
    }

    private fun buildCmap(mergedCmap: Map<Int, Int>, glyphCount: Int): ByteArray {
        val sorted = mergedCmap.entries.sortedBy { it.key }
        val useFormat4 = sorted.all { it.key <= 0xFFFF } && glyphCount <= 0xFFFF
        val format4 = if (useFormat4) {
            buildCmapFormat4(sorted).takeIf { it.size <= 0xFFFF }
        } else {
            null
        }
        val format12 = buildCmapFormat12(sorted)
        val records = ArrayList<Triple<Int, Int, ByteArray>>()
        if (format4 != null) records += Triple(3, 1, format4)
        records += Triple(3, 10, format12)
        val out = Writer()
        out.u16(0)
        out.u16(records.size)
        var offset = 4 + records.size * 8
        for ((platform, encoding, data) in records) {
            out.u16(platform)
            out.u16(encoding)
            out.u32(offset.toLong())
            offset += data.size
        }
        for ((_, _, data) in records) out.bytes(data)
        return out.toByteArray()
    }

    private fun buildCmapFormat4(sorted: List<Map.Entry<Int, Int>>): ByteArray {
        // Segments: maximal runs of consecutive codepoints. A run becomes one
        // delta segment when glyphId - codepoint is constant, else a glyph-array
        // segment. A final 0xFFFF..0xFFFF terminator segment is required.
        data class Segment(val start: Int, val end: Int, val delta: Int, val glyphs: IntArray?)
        val segments = ArrayList<Segment>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            val firstDelta = sorted[i].value - sorted[i].key
            while (j + 1 < sorted.size &&
                sorted[j + 1].key == sorted[j].key + 1 &&
                sorted[j + 1].value - sorted[j + 1].key == firstDelta
            ) {
                j++
            }
            if (j > i) {
                segments += Segment(sorted[i].key, sorted[j].key, firstDelta, null)
                i = j + 1
            } else {
                // Single codepoint, or a run with varying deltas: gather the
                // maximal consecutive run into a glyph-array segment.
                var k = i
                while (k + 1 < sorted.size && sorted[k + 1].key == sorted[k].key + 1) k++
                val glyphs = IntArray(k - i + 1) { sorted[i + it].value }
                segments += Segment(sorted[i].key, sorted[k].key, 0, glyphs)
                i = k + 1
            }
        }
        segments += Segment(0xFFFF, 0xFFFF, 1, null)
        val segCount = segments.size
        val segCountX2 = segCount * 2
        var searchRange = 2
        var entrySelector = 0
        while (searchRange * 2 <= segCountX2) {
            searchRange *= 2
            entrySelector++
        }
        val rangeShift = segCountX2 - searchRange
        val glyphArraySize = segments.sumOf { it.glyphs?.size ?: 0 } * 2
        val length = 16 + segCountX2 * 4 + glyphArraySize
        val out = Writer()
        out.u16(4)
        out.u16(length)
        out.u16(0)
        out.u16(segCountX2)
        out.u16(searchRange)
        out.u16(entrySelector)
        out.u16(rangeShift)
        for (segment in segments) out.u16(segment.end)
        out.u16(0)
        for (segment in segments) out.u16(segment.start)
        for (segment in segments) out.s16(segment.delta)
        val rangeOffsetsPosition = out.size
        for ((index, segment) in segments.withIndex()) {
            if (segment.glyphs == null) {
                out.u16(0)
            } else {
                val arrayStart = rangeOffsetsPosition + segCountX2 + segments.take(index).sumOf { it.glyphs?.size ?: 0 } * 2
                val ownPosition = rangeOffsetsPosition + index * 2
                out.u16(arrayStart - ownPosition)
            }
        }
        for (segment in segments) {
            segment.glyphs?.forEach { out.u16(it) }
        }
        return out.toByteArray()
    }

    private fun buildCmapFormat12(sorted: List<Map.Entry<Int, Int>>): ByteArray {
        data class Group(val start: Int, val end: Int, val startGlyph: Int)
        val groups = ArrayList<Group>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size &&
                sorted[j + 1].key == sorted[j].key + 1 &&
                sorted[j + 1].value == sorted[j].value + 1
            ) {
                j++
            }
            groups += Group(sorted[i].key, sorted[j].key, sorted[i].value)
            i = j + 1
        }
        val out = Writer()
        out.u16(12)
        out.u16(0)
        out.u32((16 + groups.size * 12).toLong())
        out.u32(0L)
        out.u32(groups.size.toLong())
        for (group in groups) {
            out.u32(group.start.toLong())
            out.u32(group.end.toLong())
            out.u32(group.startGlyph.toLong())
        }
        return out.toByteArray()
    }

    private fun buildLoca(offsets: IntArray, indexToLocFormat: Int): ByteArray {
        val out = Writer()
        if (indexToLocFormat == 0) {
            for (offset in offsets) out.u16(offset / 2)
        } else {
            for (offset in offsets) out.u32(offset.toLong())
        }
        return out.toByteArray()
    }

    private fun buildHead(base: ParsedFont, xMin: Int, yMin: Int, xMax: Int, yMax: Int): ByteArray {
        val bytes = base.headBytes.copyOf()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(8, 0) // checkSumAdjustment: fixed up by assembleSfnt
        buffer.putShort(36, xMin.toShort())
        buffer.putShort(38, yMin.toShort())
        buffer.putShort(40, xMax.toShort())
        buffer.putShort(42, yMax.toShort())
        return bytes
    }

    private fun buildHhea(
        base: ParsedFont,
        glyphCount: Int,
        advanceWidthMax: Int,
        minLeftSideBearing: Int,
        minRightSideBearing: Int,
        xMaxExtent: Int,
    ): ByteArray {
        val bytes = base.hheaBytes.copyOf()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(10, advanceWidthMax.toShort())
        buffer.putShort(12, minLeftSideBearing.toShort())
        buffer.putShort(14, minRightSideBearing.toShort())
        buffer.putShort(16, xMaxExtent.toShort())
        buffer.putShort(34, glyphCount.toShort())
        return bytes
    }

    private fun buildMaxp(
        base: ParsedFont,
        glyphCount: Int,
        maxPoints: Int,
        maxContours: Int,
        maxCompositePoints: Int,
        maxCompositeContours: Int,
        maxComponentElements: Int,
        maxComponentDepth: Int,
        maxInstructionLength: Int,
    ): ByteArray {
        val bytes = base.maxpBytes.copyOf()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.getInt(0)
        buffer.putShort(4, glyphCount.toShort())
        if (version == 0x00010000 && bytes.size >= 32) {
            buffer.putShort(6, maxPoints.toShort())
            buffer.putShort(8, maxContours.toShort())
            buffer.putShort(10, maxCompositePoints.toShort())
            buffer.putShort(12, maxCompositeContours.toShort())
            // 14..25 maxZones/twilight/storage/function/instruction/stack: keep base values.
            buffer.putShort(26, maxInstructionLength.toShort())
            buffer.putShort(28, maxComponentElements.toShort())
            buffer.putShort(30, maxComponentDepth.toShort())
        }
        return bytes
    }

    private fun buildPost(postBytes: ByteArray, oldGlyphCount: Int, newGlyphCount: Int): ByteArray {
        val buffer = ByteBuffer.wrap(postBytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.getInt(0)
        if (version == 0x00030000) return postBytes.copyOf()
        if (version != 0x00020000 || postBytes.size < 34) {
            // Formats 1.0/2.5 cannot be extended safely: keep the 32-byte
            // header fields and rewrite as 3.0 (no glyph names), which every
            // loader accepts.
            val rewritten = postBytes.copyOfRange(0, 32)
            ByteBuffer.wrap(rewritten).order(ByteOrder.BIG_ENDIAN).putInt(0, 0x00030000)
            return rewritten
        }
        // Format 2.0: 32-byte header + numGlyphs + glyphNameIndex[] + names.
        val header = postBytes.copyOfRange(0, 32)
        val indexStart = 34
        val indexEnd = indexStart + oldGlyphCount * 2
        if (indexEnd > postBytes.size) malformed("post table is truncated")
        val namesBlob = postBytes.copyOfRange(indexEnd, postBytes.size)
        val out = Writer()
        out.bytes(header)
        out.u16(newGlyphCount)
        for (i in 0 until oldGlyphCount) {
            out.u16(u16(buffer, indexStart + i * 2))
        }
        val customNames = ArrayList<String>()
        for (i in oldGlyphCount until newGlyphCount) {
            out.u16(258 + customNames.size)
            customNames += "uni%04X".format(i)
        }
        out.bytes(namesBlob)
        for (name in customNames) {
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            out.u8(nameBytes.size)
            out.bytes(nameBytes)
        }
        return out.toByteArray()
    }

    private fun assembleSfnt(sfntVersion: Int, tables: Map<Int, ByteArray>): ByteArray {
        val sortedTags = tables.keys.sorted()
        val numTables = sortedTags.size
        var entrySelector = 0
        var searchRange = 16
        while (searchRange * 2 <= numTables * 16) {
            searchRange *= 2
            entrySelector++
        }
        val rangeShift = numTables * 16 - searchRange
        // First pass: lay out offsets with head's checkSumAdjustment zeroed.
        val headerSize = 12 + numTables * 16
        var cursor = headerSize
        val records = ArrayList<TableRecord>()
        for (tag in sortedTags) {
            val data = tables.getValue(tag)
            val padding = (4 - cursor % 4) % 4
            cursor += padding
            val offset = cursor
            val checksum = checksum(data)
            records += TableRecord(tag, checksum, offset, data.size)
            cursor += data.size
        }
        val totalSize = cursor
        val out = ByteArray(totalSize)
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(0, sfntVersion)
        buffer.putShort(4, numTables.toShort())
        buffer.putShort(6, searchRange.toShort())
        buffer.putShort(8, entrySelector.toShort())
        buffer.putShort(10, rangeShift.toShort())
        var recordCursor = 12
        for (record in records) {
            buffer.putInt(recordCursor, record.tag)
            buffer.putInt(recordCursor + 4, record.checksum)
            buffer.putInt(recordCursor + 8, record.offset)
            buffer.putInt(recordCursor + 12, record.length)
            recordCursor += 16
            val data = tables.getValue(record.tag)
            System.arraycopy(data, 0, out, record.offset, data.size)
        }
        // checkSumAdjustment: whole-font checksum must equal 0xB1B0AFBA.
        val adjustment = (0xB1B0AFBAL - checksum(out)).toInt()
        val headRecord = records.first { it.tag == TAG_HEAD }
        buffer.putInt(headRecord.offset + 8, adjustment)
        return out
    }

    private class TableRecord(val tag: Int, val checksum: Int, val offset: Int, val length: Int)

    private fun checksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i + 4 <= data.size) {
            sum += (ByteBuffer.wrap(data, i, 4).order(ByteOrder.BIG_ENDIAN).getInt().toLong() and 0xFFFF_FFFFL)
            i += 4
        }
        if (i < data.size) {
            var tail = 0L
            var shift = 24
            while (i < data.size) {
                tail += (data[i].toLong() and 0xFFL) shl shift
                shift -= 8
                i++
            }
            sum += tail
        }
        return (sum and 0xFFFF_FFFFL).toInt()
    }

    /** Big-endian byte writer. */
    private class Writer {
        private val out = java.io.ByteArrayOutputStream()

        val size: Int get() = out.size()

        fun u8(value: Int) {
            out.write(value and 0xFF)
        }

        fun u16(value: Int) {
            out.write(value ushr 8 and 0xFF)
            out.write(value and 0xFF)
        }

        fun s16(value: Int) {
            u16(value and 0xFFFF)
        }

        fun u32(value: Long) {
            out.write((value ushr 24 and 0xFF).toInt())
            out.write((value ushr 16 and 0xFF).toInt())
            out.write((value ushr 8 and 0xFF).toInt())
            out.write((value and 0xFF).toInt())
        }

        fun bytes(data: ByteArray) {
            out.write(data)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private const val MAX_COMPONENT_DEPTH = 8

    private const val TAG_CMAP = 0x636D6170
    private const val TAG_HEAD = 0x68656164
    private const val TAG_MAXP = 0x6D617870
    private const val TAG_HHEA = 0x68686561
    private const val TAG_HMTX = 0x686D7478
    private const val TAG_LOCA = 0x6C6F6361
    private const val TAG_GLYF = 0x676C7966
    private const val TAG_POST = 0x706F7374
    private const val TAG_FVAR = 0x66766172
    private const val TAG_CFF = 0x43464620
    private const val TAG_CFF2 = 0x43464632
    private const val MAX_GLYPHS = 100_000
    private const val MAX_CMAP_SUBTABLES = 4_096
    private const val MAX_FORMAT4_VISITS = 1_000_000L
    private const val MAX_FORMAT12_GROUPS = 100_000
    private const val MAX_FORMAT12_VISITS = 10_000_000L
}
