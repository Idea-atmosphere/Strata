package dev.cluvex.strata.core.unity

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class UnityFontPopulationMode {
    STATIC,
    DYNAMIC,
    STATIC_WITH_SOURCE,
    UNKNOWN,
}

data class UnicodeBlockCoverage(
    val arabicBaseCount: Int = 0,
    val presentationFormsACount: Int = 0,
    val presentationFormsBCount: Int = 0,
    val basicLatinCount: Int = 0,
    val latinSupplementCount: Int = 0,
    val cyrillicCount: Int = 0,
    val generalPunctuationCount: Int = 0,
    val cjkCount: Int = 0,
    val otherCount: Int = 0,
) {
    val arabicTotalCount: Int
        get() = arabicBaseCount + presentationFormsACount + presentationFormsBCount

    val latinTotalCount: Int
        get() = basicLatinCount + latinSupplementCount

    val totalCount: Int
        get() = arabicTotalCount + latinTotalCount + cyrillicCount + generalPunctuationCount + cjkCount + otherCount

    fun coveredScriptNames(): List<String> {
        val scripts = ArrayList<String>(5)
        if (latinTotalCount > 0) scripts += "Latin"
        if (cyrillicCount > 0) scripts += "Cyrillic"
        if (arabicBaseCount > 0) scripts += "Arabic"
        if (presentationFormsACount > 0 || presentationFormsBCount > 0) scripts += "Arabic Presentation Forms"
        if (cjkCount > 0) scripts += "CJK"
        if (generalPunctuationCount > 0 && scripts.isEmpty()) scripts += "Punctuation"
        if (otherCount > 0 && scripts.isEmpty()) scripts += "Symbols"
        return scripts
    }
}

data class UnityTmpFontAssetReport(
    val entryName: String,
    val partIndex: Int,
    val pathId: Long,
    val name: String,
    val glyphCount: Int,
    val characterCount: Int,
    val populationModeRaw: Int,
    val populationMode: UnityFontPopulationMode,
    val sourceFontFileId: Int,
    val sourceFontPathId: Long,
    val resolvedSourceFontName: String?,
    val sourceFontSupportsArabic: Boolean,
    val atlasTextureCount: Int,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val coverage: UnicodeBlockCoverage,
    val codepoints: Set<Int>,
    val statusReason: UnityTextUnavailableReason?,
    val capabilityStatement: String,
) {
    val canRenderPersian: Boolean
        get() = statusReason == null && (
            coverage.arabicTotalCount > 0 ||
                (populationMode == UnityFontPopulationMode.DYNAMIC && sourceFontPathId != 0L && sourceFontSupportsArabic)
            )
}

data class UnityRawFontReport(
    val entryName: String,
    val partIndex: Int,
    val pathId: Long,
    val name: String,
    val embeddedFontBytes: Int,
    val fontFormat: String?,
    val supportsArabicCmap: Boolean,
    val characterRectCount: Int,
)

data class UnityFontReport(
    val inspectedEntries: Int,
    val tmpFontAssets: List<UnityTmpFontAssetReport>,
    val rawFonts: List<UnityRawFontReport>,
    val strippedTypeTreeObjects: Int = 0,
    val unavailableReason: UnityTextUnavailableReason? = null,
    val summaryStatement: String,
) {
    val canRenderPersian: Boolean
        get() = tmpFontAssets.any { it.canRenderPersian } ||
            (tmpFontAssets.isEmpty() && rawFonts.any { it.supportsArabicCmap })

    /**
     * Returns all non-whitespace codepoints from [text] that are missing across every static
     * `TMP_FontAsset` when no dynamic Arabic-capable `TMP_FontAsset` is present.
     */
    fun missingCodepointsFor(text: String): Set<Int> {
        if (tmpFontAssets.any { it.populationMode == UnityFontPopulationMode.DYNAMIC && it.sourceFontSupportsArabic }) {
            return emptySet()
        }
        val available = HashSet<Int>()
        tmpFontAssets.forEach { available.addAll(it.codepoints) }
        val missing = LinkedHashSet<Int>()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            if (!Character.isWhitespace(cp) && cp !in available) {
                missing += cp
            }
        }
        return missing
    }
}

sealed class UnityFontPatchResult {
    data class Success(
        val rebuiltEntryBytes: ByteArray,
        val patchedTmpAssets: Int,
        val boundFontPathId: Long,
        val updatedReport: UnityFontReport,
    ) : UnityFontPatchResult()

    data class Unavailable(
        val reason: UnityTextUnavailableReason,
        val diagnostic: String,
    ) : UnityFontPatchResult()
}

data class UnityBundleRewriteEstimate(
    val bundleName: String,
    val uncompressedBytes: Long,
    val temporaryDiskBytes: Long,
    val estimatedMinSeconds: Int,
    val estimatedMaxSeconds: Int,
    val isLargeBundle: Boolean,
)

internal object UnityFontInspector {
    private const val FAST_REPACK_BYTES_PER_SEC = 105L * 1024 * 1024 // ~105 MiB/s midrange fast bound
    private const val SLOW_REPACK_BYTES_PER_SEC = 35L * 1024 * 1024 // ~35 MiB/s midrange conservative bound
    private const val LARGE_BUNDLE_THRESHOLD_BYTES = 50L * 1024 * 1024 // 50 MiB

    private data class CacheKey(
        val path: String,
        val size: Long,
        val lastModified: Long,
        val scope: String,
    )

    private val reportCache = object : LinkedHashMap<CacheKey, UnityFontReport>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, UnityFontReport>?): Boolean = size > 32
    }

    fun getCachedReport(path: String, size: Long, lastModified: Long, scope: String): UnityFontReport? =
        synchronized(reportCache) { reportCache[CacheKey(path, size, lastModified, scope)] }

    fun putCachedReport(path: String, size: Long, lastModified: Long, scope: String, report: UnityFontReport) {
        synchronized(reportCache) { reportCache[CacheKey(path, size, lastModified, scope)] = report }
    }

    fun clearCache() {
        synchronized(reportCache) { reportCache.clear() }
    }

    fun estimateRewrite(bundleName: String, uncompressedBytes: Long): UnityBundleRewriteEstimate {
        val safeBytes = uncompressedBytes.coerceAtLeast(0L)
        val minSec = ((safeBytes + FAST_REPACK_BYTES_PER_SEC / 2) / FAST_REPACK_BYTES_PER_SEC).toInt().coerceAtLeast(1)
        val maxSec = ((safeBytes + SLOW_REPACK_BYTES_PER_SEC - 1) / SLOW_REPACK_BYTES_PER_SEC).toInt().coerceAtLeast(minSec + 1)
        return UnityBundleRewriteEstimate(
            bundleName = bundleName,
            uncompressedBytes = safeBytes,
            temporaryDiskBytes = safeBytes,
            estimatedMinSeconds = minSec,
            estimatedMaxSeconds = maxSec,
            isLargeBundle = safeBytes >= LARGE_BUNDLE_THRESHOLD_BYTES,
        )
    }

    /**
     * Cheap type-tree check on a `MonoBehaviour` (`classId = 114`) so non-font `MonoBehaviour`
     * objects are skipped without reading or decoding their payload bytes from disk.
     */
    fun hasTmpFontAssetShape(root: TTNode): Boolean {
        var hasGlyphTable = false
        var hasCharTable = false
        var hasFontMeta = false
        root.children.forEach { child ->
            when (child.name) {
                "m_GlyphTable", "m_glyphInfoList" -> hasGlyphTable = true
                "m_CharacterTable", "m_characterDictionary" -> hasCharTable = true
                "m_AtlasPopulationMode", "m_SourceFontFile", "m_FaceInfo", "m_fontInfo", "m_AtlasTextures", "atlas" -> {
                    hasFontMeta = true
                }
            }
        }
        return hasGlyphTable && hasCharTable && hasFontMeta
    }

    /** Shape-based check on a decoded [UVal.Struct] (never matches by asset name). */
    fun isTmpFontAsset(struct: UVal.Struct): Boolean {
        val fields = struct.fields
        val glyphTable = (fields["m_GlyphTable"] ?: fields["m_glyphInfoList"]) as? UVal.Arr
        val charTable = (fields["m_CharacterTable"] ?: fields["m_characterDictionary"]) as? UVal.Arr
        if (glyphTable == null || charTable == null) return false
        return fields.containsKey("m_AtlasPopulationMode") ||
            fields.containsKey("m_SourceFontFile") ||
            fields.containsKey("m_FaceInfo") ||
            fields.containsKey("m_fontInfo") ||
            fields.containsKey("m_AtlasTextures") ||
            fields.containsKey("atlas")
    }

    /**
     * Enforces structural invariants on a decoded `TMP_FontAsset` before or after modification:
     * - `m_GlyphTable` and `m_CharacterTable` must be parallel arrays (`size` must match).
     * - Every `m_CharacterTable` entry's `m_GlyphIndex` must index `0 until m_GlyphTable.size`.
     * - Every glyph's `m_GlyphRect` must lie within `0..m_AtlasWidth` × `0..m_AtlasHeight` (when positive).
     * - Every glyph's `m_AtlasIndex` must lie within `0 until maxOf(1, m_AtlasTextures.size)`.
     * - When `m_AtlasPopulationMode == 1` (Dynamic), `m_SourceFontFile.m_PathID` must be non-zero
     *   and (when `m_FileID == 0` and [sameFileSlots] is provided) resolve to a `Font` (class 128) slot.
     */
    fun validateTmpFontAssetInvariants(
        value: UVal,
        sameFileSlots: List<SerializedFileIndex.Slot>? = null,
    ) {
        val struct = value as? UVal.Struct
            ?: throw UnityFormatException("TMP_FontAsset root value is not a struct")
        if (!isTmpFontAsset(struct)) {
            throw UnityFormatException("Decoded MonoBehaviour does not have TMP_FontAsset field shape")
        }
        val fields = struct.fields
        val glyphs = ((fields["m_GlyphTable"] ?: fields["m_glyphInfoList"]) as? UVal.Arr)?.items
            ?: throw UnityFormatException("TMP_FontAsset is missing m_GlyphTable")
        val characters = ((fields["m_CharacterTable"] ?: fields["m_characterDictionary"]) as? UVal.Arr)?.items
            ?: throw UnityFormatException("TMP_FontAsset is missing m_CharacterTable")

        if (glyphs.size != characters.size) {
            throw UnityFormatException(
                "TMP_FontAsset parallel table invariant violated: m_GlyphTable.size (${glyphs.size}) != m_CharacterTable.size (${characters.size})",
            )
        }

        val (atlasWidth, atlasHeight) = extractAtlasDimensions(fields)
        val atlasCount = ((fields["m_AtlasTextures"] as? UVal.Arr)?.items?.size ?: 1).coerceAtLeast(1)

        glyphs.forEachIndexed { idx, glyphVal ->
            val glyph = glyphVal as? UVal.Struct
                ?: throw UnityFormatException("m_GlyphTable[$idx] is not a struct")
            val atlasIndex = (glyph.fields["m_AtlasIndex"] as? UVal.Num)?.value?.toInt() ?: 0
            if (atlasIndex !in 0 until atlasCount) {
                throw UnityFormatException("m_GlyphTable[$idx].m_AtlasIndex ($atlasIndex) is outside 0 until $atlasCount")
            }
            val rect = glyph.fields["m_GlyphRect"] as? UVal.Struct
            if (rect != null && atlasWidth > 0 && atlasHeight > 0) {
                val x = (rect.fields["m_X"] as? UVal.Num)?.value?.toInt() ?: 0
                val y = (rect.fields["m_Y"] as? UVal.Num)?.value?.toInt() ?: 0
                val w = (rect.fields["m_Width"] as? UVal.Num)?.value?.toInt() ?: 0
                val h = (rect.fields["m_Height"] as? UVal.Num)?.value?.toInt() ?: 0
                if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > atlasWidth || y + h > atlasHeight) {
                    throw UnityFormatException(
                        "m_GlyphTable[$idx].m_GlyphRect ($x,$y,${w}x$h) exceeds atlas bounds ${atlasWidth}x$atlasHeight",
                    )
                }
            }
        }

        characters.forEachIndexed { idx, charVal ->
            val ch = charVal as? UVal.Struct
                ?: throw UnityFormatException("m_CharacterTable[$idx] is not a struct")
            val glyphIndex = (ch.fields["m_GlyphIndex"] as? UVal.Num)?.value?.toInt()
            if (glyphIndex != null && glyphs.isNotEmpty() && glyphIndex !in 0 until glyphs.size) {
                throw UnityFormatException(
                    "m_CharacterTable[$idx].m_GlyphIndex ($glyphIndex) is outside 0 until ${glyphs.size}",
                )
            }
        }

        val popMode = (fields["m_AtlasPopulationMode"] as? UVal.Num)?.value?.toInt() ?: 0
        if (popMode == 1) {
            val (fileId, pathId) = extractSourceFontPPtr(fields)
            if (pathId == 0L) {
                throw UnityFormatException("Dynamic TMP_FontAsset (m_AtlasPopulationMode=1) has unbound m_SourceFontFile (m_PathID=0)")
            }
            if (fileId == 0 && sameFileSlots != null) {
                val targetSlot = sameFileSlots.firstOrNull { it.pathId == pathId }
                    ?: throw UnityFormatException("Dynamic TMP_FontAsset references missing same-file m_SourceFontFile #$pathId")
                if (targetSlot.classId != FONT_CLASS_ID) {
                    throw UnityFormatException(
                        "Dynamic TMP_FontAsset m_SourceFontFile #$pathId points to classID ${targetSlot.classId}, expected Font ($FONT_CLASS_ID)",
                    )
                }
            }
        }
    }

    fun inspectRawFont(
        entryName: String,
        partIndex: Int,
        pathId: Long,
        struct: UVal.Struct,
    ): UnityRawFontReport {
        val name = (struct.fields["m_Name"] as? UVal.Str)?.value ?: ""
        val fontBytes = extractFontDataBytes(struct)
        val format = detectFontFormat(fontBytes)
        val supportsArabic = if (fontBytes.isNotEmpty()) {
            TtfCmapInspector.supportsArabicScript(fontBytes)
        } else {
            false
        }
        val charRects = ((struct.fields["m_CharacterRects"] as? UVal.Arr)?.items?.size) ?: 0
        return UnityRawFontReport(
            entryName = entryName,
            partIndex = partIndex,
            pathId = pathId,
            name = name,
            embeddedFontBytes = fontBytes.size,
            fontFormat = format,
            supportsArabicCmap = supportsArabic,
            characterRectCount = charRects,
        )
    }

    fun inspectTmpFontAsset(
        entryName: String,
        partIndex: Int,
        pathId: Long,
        struct: UVal.Struct,
        rawFontsByPartAndId: Map<Pair<Int, Long>, UnityRawFontReport> = emptyMap(),
    ): UnityTmpFontAssetReport {
        val fields = struct.fields
        val name = (fields["m_Name"] as? UVal.Str)?.value ?: ""
        val glyphs = ((fields["m_GlyphTable"] ?: fields["m_glyphInfoList"]) as? UVal.Arr)?.items ?: emptyList()
        val characters = ((fields["m_CharacterTable"] ?: fields["m_characterDictionary"]) as? UVal.Arr)?.items ?: emptyList()
        val popModeRaw = (fields["m_AtlasPopulationMode"] as? UVal.Num)?.value?.toInt() ?: 0
        val (sourceFileId, sourcePathId) = extractSourceFontPPtr(fields)
        val resolvedRawFont = if (sourceFileId == 0 && sourcePathId != 0L) {
            rawFontsByPartAndId[partIndex to sourcePathId]
        } else {
            null
        }
        val populationMode = when {
            popModeRaw == 1 || popModeRaw == 2 -> UnityFontPopulationMode.DYNAMIC
            popModeRaw == 0 && sourcePathId != 0L -> UnityFontPopulationMode.STATIC_WITH_SOURCE
            popModeRaw == 0 -> UnityFontPopulationMode.STATIC
            else -> UnityFontPopulationMode.UNKNOWN
        }
        val (atlasWidth, atlasHeight) = extractAtlasDimensions(fields)
        val atlasTextures = ((fields["m_AtlasTextures"] as? UVal.Arr)?.items ?: emptyList()).count { item ->
            val pptr = item as? UVal.Struct ?: return@count false
            val pid = (pptr.fields["m_PathID"] as? UVal.Num)?.value ?: 0L
            pid != 0L
        }

        val codepoints = LinkedHashSet<Int>(characters.size)
        var arabicBase = 0
        var formsA = 0
        var formsB = 0
        var basicLatin = 0
        var latinSupp = 0
        var cyrillic = 0
        var punctuation = 0
        var cjk = 0
        var other = 0

        characters.forEach { item ->
            val charStruct = item as? UVal.Struct ?: return@forEach
            val cp = ((charStruct.fields["m_Unicode"] ?: charStruct.fields["id"]) as? UVal.Num)?.value?.toInt()
                ?: return@forEach
            if (!codepoints.add(cp)) return@forEach
            when (cp) {
                in 0x0600..0x06FF, in 0x0750..0x077F, in 0x08A0..0x08FF -> arabicBase++
                in 0xFB50..0xFDFF -> formsA++
                in 0xFE70..0xFEFF -> formsB++
                in 0x0020..0x007E -> basicLatin++
                in 0x00A0..0x024F -> latinSupp++
                in 0x0400..0x04FF -> cyrillic++
                in 0x2000..0x206F -> punctuation++
                in 0x3000..0x30FF, in 0x4E00..0x9FFF, in 0xAC00..0xD7AF -> cjk++
                else -> if (cp > 0x0020) other++
            }
        }

        val coverage = UnicodeBlockCoverage(
            arabicBaseCount = arabicBase,
            presentationFormsACount = formsA,
            presentationFormsBCount = formsB,
            basicLatinCount = basicLatin,
            latinSupplementCount = latinSupp,
            cyrillicCount = cyrillic,
            generalPunctuationCount = punctuation,
            cjkCount = cjk,
            otherCount = other,
        )

        val sourceSupportsArabic = when {
            resolvedRawFont != null -> resolvedRawFont.supportsArabicCmap
            sourceFileId != 0 && sourcePathId != 0L -> true // external dependency bundle reference
            else -> false
        }

        val statusReason = when (populationMode) {
            UnityFontPopulationMode.DYNAMIC -> when {
                sourcePathId == 0L && coverage.arabicTotalCount == 0 -> UnityTextUnavailableReason.FONT_STATIC_NO_SOURCE
                resolvedRawFont != null && !resolvedRawFont.supportsArabicCmap && coverage.arabicTotalCount == 0 -> {
                    UnityTextUnavailableReason.FONT_NO_GLYPHS
                }
                else -> null
            }
            UnityFontPopulationMode.STATIC -> when {
                coverage.arabicTotalCount > 0 -> null
                characters.isEmpty() -> UnityTextUnavailableReason.FONT_NO_GLYPHS
                else -> UnityTextUnavailableReason.FONT_STATIC_NO_SOURCE
            }
            UnityFontPopulationMode.STATIC_WITH_SOURCE -> when {
                coverage.arabicTotalCount > 0 -> null
                else -> UnityTextUnavailableReason.FONT_NO_GLYPHS
            }
            UnityFontPopulationMode.UNKNOWN -> if (coverage.arabicTotalCount > 0) null else UnityTextUnavailableReason.FONT_NO_GLYPHS
        }

        val modeLabel = when (populationMode) {
            UnityFontPopulationMode.STATIC -> "Static"
            UnityFontPopulationMode.DYNAMIC -> "Dynamic"
            UnityFontPopulationMode.STATIC_WITH_SOURCE -> "Static + SourceFont"
            UnityFontPopulationMode.UNKNOWN -> "Mode($popModeRaw)"
        }
        val scriptsLabel = coverage.coveredScriptNames().joinToString(", ").ifEmpty { "none" }
        val sourceLabel = when {
            sourcePathId == 0L -> "no source TTF attached"
            resolvedRawFont != null -> {
                val fontLabel = resolvedRawFont.name.ifEmpty { "#$sourcePathId" }
                val cmapNote = if (resolvedRawFont.supportsArabicCmap) "Arabic cmap" else "no Arabic cmap"
                "source TTF '$fontLabel' (#$sourcePathId, $cmapNote) attached"
            }
            else -> "external source Font (fileID=$sourceFileId, pathID=$sourcePathId) attached"
        }
        val displayName = name.ifEmpty { "#$pathId" }
        val statement = "TMP_FontAsset '$displayName' is $modeLabel with ${glyphs.size} glyphs ($scriptsLabel); " +
            "${coverage.arabicTotalCount} Arabic/Persian glyphs; $sourceLabel."

        return UnityTmpFontAssetReport(
            entryName = entryName,
            partIndex = partIndex,
            pathId = pathId,
            name = name,
            glyphCount = glyphs.size,
            characterCount = characters.size,
            populationModeRaw = popModeRaw,
            populationMode = populationMode,
            sourceFontFileId = sourceFileId,
            sourceFontPathId = sourcePathId,
            resolvedSourceFontName = resolvedRawFont?.name,
            sourceFontSupportsArabic = sourceSupportsArabic,
            atlasTextureCount = atlasTextures,
            atlasWidth = atlasWidth,
            atlasHeight = atlasHeight,
            coverage = coverage,
            codepoints = codepoints,
            statusReason = statusReason,
            capabilityStatement = statement,
        )
    }

    fun buildSummaryStatement(
        tmpAssets: List<UnityTmpFontAssetReport>,
        rawFonts: List<UnityRawFontReport>,
        strippedTypeTreeObjects: Int,
    ): String {
        if (tmpAssets.isEmpty() && rawFonts.isEmpty()) {
            return if (strippedTypeTreeObjects > 0) {
                "No readable font assets ($strippedTypeTreeObjects objects lack type trees)."
            } else {
                "0 TMP_FontAsset objects and 0 raw Font objects in this scope."
            }
        }
        val statements = ArrayList<String>(tmpAssets.size + 1)
        statements += "${tmpAssets.size} TMP_FontAsset(s), ${rawFonts.size} raw Font(s)."
        tmpAssets.forEach { statements += it.capabilityStatement }
        return statements.joinToString(" ")
    }

    /**
     * Modifies a decoded `TMP_FontAsset` ([tmpStruct]) in place to use Dynamic population mode
     * (`m_AtlasPopulationMode = 1`) and binds `m_SourceFontFile` to `(m_FileID = sourceFileId, m_PathID = sourcePathId)`.
     */
    fun applyDynamicFontBinding(
        tmpStruct: UVal.Struct,
        sourceFileId: Int,
        sourcePathId: Long,
        sameFileSlots: List<SerializedFileIndex.Slot>? = null,
    ) {
        val fields = tmpStruct.fields
        val currentMode = fields["m_AtlasPopulationMode"] as? UVal.Num
            ?: throw UnityFormatException("TMP_FontAsset is missing m_AtlasPopulationMode")
        fields["m_AtlasPopulationMode"] = UVal.Num(1L, currentMode.width)

        val sourceFont = fields["m_SourceFontFile"] as? UVal.Struct
            ?: throw UnityFormatException("TMP_FontAsset is missing m_SourceFontFile")
        val fileIdField = sourceFont.fields["m_FileID"] as? UVal.Num
            ?: throw UnityFormatException("m_SourceFontFile is missing m_FileID")
        val pathIdField = sourceFont.fields["m_PathID"] as? UVal.Num
            ?: throw UnityFormatException("m_SourceFontFile is missing m_PathID")
        sourceFont.fields["m_FileID"] = UVal.Num(sourceFileId.toLong(), fileIdField.width)
        sourceFont.fields["m_PathID"] = UVal.Num(sourcePathId, pathIdField.width)

        val multiAtlas = fields["m_IsMultiAtlasTexturesEnabled"]
        if (multiAtlas is UVal.Bool) {
            fields["m_IsMultiAtlasTexturesEnabled"] = UVal.Bool(1)
        }

        validateTmpFontAssetInvariants(tmpStruct, sameFileSlots)
    }

    /** Replaces `m_FontData` inside a decoded raw `Font` (`classId = 128`) [fontStruct]. */
    fun replaceRawFontData(fontStruct: UVal.Struct, ttfBytes: ByteArray) {
        if (!TtfCmapInspector.isValidFontHeader(ttfBytes)) {
            throw UnityFormatException("Supplied font file is not a valid TrueType/OpenType (sfnt) font")
        }
        if (!TtfCmapInspector.supportsArabicScript(ttfBytes)) {
            throw UnityFormatException("Supplied font file does not contain Arabic/Persian glyphs in its cmap table")
        }
        val existing = fontStruct.fields["m_FontData"]
            ?: throw UnityFormatException("Font object is missing m_FontData")
        when (existing) {
            is UVal.Bytes -> fontStruct.fields["m_FontData"] = UVal.Bytes(ttfBytes.copyOf())
            is UVal.Arr -> {
                val items = ArrayList<UVal>(ttfBytes.size)
                for (b in ttfBytes) {
                    items += UVal.Num(b.toLong() and 0xFFL, 1)
                }
                fontStruct.fields["m_FontData"] = UVal.Arr(items)
            }
            else -> throw UnityFormatException("Font.m_FontData has unexpected decoded type ${existing::class.simpleName}")
        }
    }

    fun extractFontDataBytes(fontStruct: UVal.Struct): ByteArray {
        return when (val fontData = fontStruct.fields["m_FontData"]) {
            is UVal.Bytes -> fontData.data
            is UVal.Arr -> {
                val out = ByteArray(fontData.items.size)
                for (i in fontData.items.indices) {
                    val num = fontData.items[i] as? UVal.Num ?: return ByteArray(0)
                    out[i] = num.value.toByte()
                }
                out
            }
            else -> ByteArray(0)
        }
    }

    private fun detectFontFormat(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return when {
            b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00 -> "TTF"
            b0 == 't'.code && b1 == 'r'.code && b2 == 'u'.code && b3 == 'e'.code -> "TTF"
            b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code -> "OTF"
            b0 == 't'.code && b1 == 't'.code && b2 == 'c'.code && b3 == 'f'.code -> "TTC"
            else -> null
        }
    }

    private fun extractSourceFontPPtr(fields: Map<String, UVal>): Pair<Int, Long> {
        val pptr = (fields["m_SourceFontFile"] ?: fields["m_fontAsset"]) as? UVal.Struct
            ?: return 0 to 0L
        val fileId = (pptr.fields["m_FileID"] as? UVal.Num)?.value?.toInt() ?: 0
        val pathId = (pptr.fields["m_PathID"] as? UVal.Num)?.value ?: 0L
        return fileId to pathId
    }

    private fun extractAtlasDimensions(fields: Map<String, UVal>): Pair<Int, Int> {
        val directW = (fields["m_AtlasWidth"] as? UVal.Num)?.value?.toInt()
        val directH = (fields["m_AtlasHeight"] as? UVal.Num)?.value?.toInt()
        if (directW != null && directH != null) return directW to directH
        val face = (fields["m_FaceInfo"] ?: fields["m_fontInfo"]) as? UVal.Struct
        val faceW = (face?.fields?.get("m_AtlasWidth") as? UVal.Num)?.value?.toInt()
            ?: (face?.fields?.get("AtlasWidth") as? UVal.Num)?.value?.toInt()
            ?: 0
        val faceH = (face?.fields?.get("m_AtlasHeight") as? UVal.Num)?.value?.toInt()
            ?: (face?.fields?.get("AtlasHeight") as? UVal.Num)?.value?.toInt()
            ?: 0
        return faceW to faceH
    }

    internal const val MONO_BEHAVIOUR_CLASS_ID = 114
    internal const val FONT_CLASS_ID = 128
}

/**
 * Lightweight, zero-dependency TrueType/OpenType `cmap` inspector for verifying whether an
 * embedded or user-supplied font file contains Arabic/Persian Unicode mappings.
 */
internal object TtfCmapInspector {
    fun isValidFontHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        return magic == 0x00010000 || // TrueType
            magic == 0x4F54544F || // 'OTTO' OpenType CFF
            magic == 0x74727565 || // 'true'
            magic == 0x74746366 // 'ttcf' TrueType Collection
    }

    fun supportsArabicScript(bytes: ByteArray): Boolean {
        if (!isValidFontHeader(bytes)) return false
        return runCatching {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val sfntOffset = if (buf.getInt(0) == 0x74746366) {
                if (bytes.size < 16) return false
                val numFonts = buf.getInt(8)
                if (numFonts <= 0) return false
                buf.getInt(12)
            } else {
                0
            }
            if (sfntOffset < 0 || sfntOffset + 12 > bytes.size) return false
            val numTables = buf.getShort(sfntOffset + 4).toInt() and 0xFFFF
            var cmapOffset = -1
            var cmapLength = 0
            val tableDir = sfntOffset + 12
            for (i in 0 until numTables) {
                val entry = tableDir + i * 16
                if (entry + 16 > bytes.size) break
                val tag = buf.getInt(entry)
                if (tag == 0x636D6170) { // 'cmap'
                    cmapOffset = buf.getInt(entry + 8)
                    cmapLength = buf.getInt(entry + 12)
                    break
                }
            }
            if (cmapOffset <= 0 || cmapLength <= 4 || cmapOffset + cmapLength > bytes.size) return false
            val numSubtables = buf.getShort(cmapOffset + 2).toInt() and 0xFFFF
            for (i in 0 until numSubtables) {
                val rec = cmapOffset + 4 + i * 8
                if (rec + 8 > cmapOffset + cmapLength) break
                val subOffset = cmapOffset + buf.getInt(rec + 4)
                if (subOffset < cmapOffset || subOffset + 4 > bytes.size) continue
                val format = buf.getShort(subOffset).toInt() and 0xFFFF
                if (format == 4 && subtableFormat4CoversArabic(buf, subOffset, bytes.size)) return true
                if (format == 12 && subtableFormat12CoversArabic(buf, subOffset, bytes.size)) return true
            }
            false
        }.getOrDefault(false)
    }

    private fun subtableFormat4CoversArabic(buf: ByteBuffer, offset: Int, limit: Int): Boolean {
        if (offset + 14 > limit) return false
        val segCountX2 = buf.getShort(offset + 6).toInt() and 0xFFFF
        val segCount = segCountX2 / 2
        val endCodesOffset = offset + 14
        val startCodesOffset = endCodesOffset + segCountX2 + 2
        if (startCodesOffset + segCountX2 > limit) return false
        for (s in 0 until segCount) {
            val endCode = buf.getShort(endCodesOffset + s * 2).toInt() and 0xFFFF
            val startCode = buf.getShort(startCodesOffset + s * 2).toInt() and 0xFFFF
            if (startCode <= 0x064A && endCode >= 0x0627) return true
            if (startCode <= 0xFEFC && endCode >= 0xFE80) return true
        }
        return false
    }

    private fun subtableFormat12CoversArabic(buf: ByteBuffer, offset: Int, limit: Int): Boolean {
        if (offset + 16 > limit) return false
        val numGroups = buf.getInt(offset + 12)
        if (numGroups <= 0 || numGroups > 100_000) return false
        val groupsOffset = offset + 16
        if (groupsOffset + numGroups * 12 > limit) return false
        for (g in 0 until numGroups) {
            val base = groupsOffset + g * 12
            val startCode = buf.getInt(base)
            val endCode = buf.getInt(base + 4)
            if (startCode <= 0x064A && endCode >= 0x0627) return true
            if (startCode <= 0xFEFC && endCode >= 0xFE80) return true
        }
        return false
    }
}
