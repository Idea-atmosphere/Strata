package dev.cluvex.strata.core.unity

/**
 * Phase 4 core, part 2 (pure Kotlin, no Android deps, internal like
 * UnityBaseData because it exposes UVal, which is internal): patch a decoded
 * TMP_FontAsset (UVal, from the same-file tree or a Phase 3 external
 * tree) and its atlas Texture2D (UVal, from a bundle's Texture2D tree)
 * with baked PUA glyphs from [UnitySdfBaker].
 *
 * This is the Kotlin port of the proven Python builders used for Bendy
 * (build_cipher_wordheader_rightpad_file.py, build_cipher_warning_full_
 * file.py), with the same device-confirmed output shape, generalised
 * to any TMP asset that follows the same table layout. It does NOT
 * splice raw bytes: it builds new UVal objects by COPYING field
 * objects (names, widths, single/double flags) from existing table
 * entries, overriding only the values that must change. That is what
 * keeps the encoded object valid across TMP versions: a hardcoded
 * field width or a double where the template has a single float
 * would silently change the encoded size and corrupt the table.
 *
 * Proven Python build, reproduced exactly here:
 * - New glyph indices start at 30000 (above Bendy's max existing
 *   26860, with a gap for runtime dynamic glyphs), in PUA-sorted
 *   order. For other games, the start is
 *   max(30000, maxExistingIndex + 1), so a game whose glyph
 *   indices already exceed 30000 still gets collision-free indices.
 * - New glyph entry: m_Index = assigned index, m_Metrics =
 *   (width, height, bearingX, bearingY, advance) as single floats,
 *   m_GlyphRect = (rectX, rectY, rectWidth, rectHeight) as ints,
 *   m_Scale / m_AtlasIndex / m_ClassDefinitionType copied from the
 *   template glyph (1.0 / 0 / 0 for Bendy).
 * - New character entry: m_ElementType and m_Scale copied from the
 *   template character (1 / 1.0 for Bendy), m_Unicode = PUA,
 *   m_GlyphIndex = assigned index.
 * - Final tables are SORTED: glyphs by m_Index, characters by
 *   m_Unicode. Original entries are preserved verbatim (field
 *   values, not just positions); sorting is safe because lookup
 *   is by m_Index / m_Unicode fields, not by table position.
 * - m_AtlasWidth / m_AtlasHeight AND m_CreationSettings.atlasWidth /
 *   atlasHeight are updated to the new atlas size. m_fontInfo
 *   (legacy: PointSize 86.0, AtlasWidth 1024.0 for Bendy) is left
 *   VERBATIM, as is m_CreationSettings.characterSequence (editor
 *   metadata, not runtime): Python left both verbatim and the
 *   builds rendered on device.
 * - m_UsedGlyphRects / m_FreeGlyphRects are left VERBATIM:
 *   new glyphs live outside the original atlas, and TMP renders
 *   from the glyph table rects, not from these lists (Python left
 *   them verbatim and cipher / wordheader / warning-full all
 *   rendered on device). Do NOT "fix" them.
 * - Material, FaceInfo, AtlasTextures PPtr, FallbackFontAssetTable,
 *   kerning / font-feature tables, weight tables, and every other
 *   root field are left verbatim. The atlas Texture2D object is
 *   patched separately ([patchAtlasTexture]): only m_Width,
 *   m_Height, m_CompleteImageSize, and the image bytes change.
 *   Its PPtr (file/path) does not change, so the TMP object keeps
 *   pointing at the same texture object, now larger.
 * - Object size growth is newCount * 68 bytes (glyph 52 + char 16)
 *   for the TMP object, plus (newAtlas^2 - oldAtlas^2) bytes for
 *   the texture image. The scratch test checks both against the
 *   real Bendy fallback, matching Python's 4864 + 134*68 = 13976
 *   for wordheader and 4864 + 198*68 = 18328 for warning-full.
 *
 * Fail-fast contract (v1, do not silently ship a broken asset):
 * - Original glyph/character tables must be non-empty (templates
 *   are required; a TMP asset with no glyphs has no field layout
 *   to copy, and baking into it is a different problem).
 * - New PUAs must be in E000..F8FF, unique within the baked list,
 *   and NOT already present in the original character table.
 *   Fresh baking only: start from an original fallback, not from
 *   an already-PUA-baked fallback (incremental baking needs
 *   free-rect packing, not just strip packing; see baker docs).
 * - Baker pad must equal TMP m_AtlasPadding, and caller pointSize
 *   must equal TMP m_FaceInfo.m_PointSize. A pad or pointSize
 *   mismatch would sample or scale glyphs wrong; re-bake with
 *   the correct values instead of patching anyway.
 * - New atlas size must be one of 512 / 1024 / 2048 / 4096 and
 *   >= original atlas size (4096 is the largest atlas proven to
 *   render in-game, via CaviarDreams; larger is unproven and a
 *   mobile GPU risk, so it is rejected, not warned-and-shipped).
 * - Baked glyph rects must lie inside the new atlas, with
 *   positive width/height and positive advance. An out-of-bounds
 *   rect is a baker bug; shipping it would render garbage.
 * - Atlas Texture2D must be Alpha8 (TextureFormat 1), single
 *   image, single mip, embedded (StreamData size 0, path empty).
 *   TMP SDF atlases are Alpha8 by construction; a different
 *   format / mip chain / streamed texture is out of scope for v1
 *   and must be handled explicitly, not by guessing bytes/pixel.
 */
internal object UnityPersianTmpPatcher {

    /** Result of [patchTmpFontAsset]. */
    data class PatchedTmpFontAsset(
        val tmp: UVal.Struct,
        /** PUA code -> assigned glyph index, in PUA-sorted order. */
        val glyphIndexByPua: Map<Int, Int>,
        val originalGlyphCount: Int,
        val originalCharCount: Int,
        val newGlyphCount: Int,
        val atlasSize: Int,
        val startGlyphIndex: Int,
    )

    /** Result of [patchAtlasTexture]. */
    data class PatchedAtlasTexture(
        val texture: UVal.Struct,
        val atlasSize: Int,
        val imageSize: Int,
    )

    private val ALLOWED_ATLAS_SIZES = setOf(512, 1024, 2048, 4096)

    private fun requireStruct(value: UVal?, field: String): UVal.Struct {
        require(value is UVal.Struct) { "TMP patcher: expected Struct for $field, got ${value?.javaClass?.simpleName}" }
        return value
    }

    private fun requireArr(value: UVal?, field: String): UVal.Arr {
        require(value is UVal.Arr) { "TMP patcher: expected Arr for $field, got ${value?.javaClass?.simpleName}" }
        return value
    }

    private fun requireNum(value: UVal?, field: String): UVal.Num {
        require(value is UVal.Num) { "TMP patcher: expected Num for $field, got ${value?.javaClass?.simpleName}" }
        return value
    }

    private fun requireFlt(value: UVal?, field: String): UVal.Flt {
        require(value is UVal.Flt) { "TMP patcher: expected Flt for $field, got ${value?.javaClass?.simpleName}" }
        return value
    }

    /** Copy a struct, overriding only [overrides], in template order. */
    private fun copyStructWithOverrides(
        template: UVal.Struct,
        overrides: Map<String, UVal>,
        what: String,
    ): UVal.Struct {
        for (key in overrides.keys) {
            require(template.fields.containsKey(key)) {
                "TMP patcher: $what has no field '$key' in this TMP version " +
                    "(available: ${template.fields.keys}); refusing to add a field the encoder would ignore"
            }
        }
        val out = LinkedHashMap<String, UVal>(template.fields.size)
        for ((key, value) in template.fields) {
            out[key] = overrides[key] ?: value
        }
        return UVal.Struct(out)
    }

    private fun numWithWidth(template: UVal.Num, newValue: Long): UVal.Num {
        return UVal.Num(newValue, template.width)
    }

    private fun fltSingle(template: UVal.Flt, newValue: Float): UVal.Flt {
        // Template metrics are single floats; the encoder uses the
        // single flag to choose 4 vs 8 bytes, so it must be preserved.
        require(template.single) {
            "TMP patcher: expected single-float template field, got double; refusing to guess the encoding"
        }
        return UVal.Flt(newValue.toDouble(), single = true)
    }

    /**
     * Patch a decoded TMP_FontAsset with baked glyphs.
     *
     * @param originalTmp decoded TMP object (same-file tree or Phase 3
     *   external tree; big-endian flag is handled by the caller at
     *   encode time, not here).
     * @param baked baker result; its glyphs are used as-is (rects,
     *   metrics, advances). [baked.unpacked] is ignored here: the
     *   caller must already have applied letter fallback for unpacked
     *   words, so [baked.glyphs] is exactly the set to patch in.
     * @param bakerPad pad used by the baker (must equal TMP padding).
     * @param pointSize pointSize used to render letters / scale the
     *   right pad (must equal TMP FaceInfo pointSize).
     */
    fun patchTmpFontAsset(
        originalTmp: UVal.Struct,
        baked: UnitySdfBaker.BakedAtlas,
        bakerPad: Int,
        pointSize: Int,
    ): PatchedTmpFontAsset {
        val fields = originalTmp.fields
        val glyphTable = requireArr(fields["m_GlyphTable"], "m_GlyphTable")
        val charTable = requireArr(fields["m_CharacterTable"], "m_CharacterTable")
        require(glyphTable.items.isNotEmpty()) {
            "TMP patcher: original glyph table is empty; no template glyph to copy field layout from"
        }
        require(charTable.items.isNotEmpty()) {
            "TMP patcher: original character table is empty; no template character to copy field layout from"
        }
        val templateGlyph = requireStruct(glyphTable.items[0], "glyph[0]")
        val templateChar = requireStruct(charTable.items[0], "character[0]")
        val templateMetrics = requireStruct(templateGlyph.fields["m_Metrics"], "glyph.m_Metrics")
        val templateRect = requireStruct(templateGlyph.fields["m_GlyphRect"], "glyph.m_GlyphRect")

        val origAtlasW = requireNum(fields["m_AtlasWidth"], "m_AtlasWidth").value.toInt()
        val origAtlasH = requireNum(fields["m_AtlasHeight"], "m_AtlasHeight").value.toInt()
        val origPad = requireNum(fields["m_AtlasPadding"], "m_AtlasPadding").value.toInt()
        require(origAtlasW == origAtlasH) {
            "TMP patcher: original atlas is not square ($origAtlasW x $origAtlasH); v1 only handles square atlases"
        }
        require(bakerPad == origPad) {
            "TMP patcher: baker pad ($bakerPad) != TMP atlas padding ($origPad); " +
                "re-bake with pad=$origPad, else SDF sampling will be off"
        }
        val faceInfo = requireStruct(fields["m_FaceInfo"], "m_FaceInfo")
        val facePointSize = requireNum(faceInfo.fields["m_PointSize"], "m_FaceInfo.m_PointSize").value.toInt()
        require(pointSize == facePointSize) {
            "TMP patcher: caller pointSize ($pointSize) != TMP FaceInfo pointSize ($facePointSize); " +
                "re-bake at pointSize=$facePointSize, else glyph metrics will be at the wrong scale"
        }
        val newAtlasSize = baked.atlasSize
        require(newAtlasSize in ALLOWED_ATLAS_SIZES) {
            "TMP patcher: new atlas size $newAtlasSize is not one of $ALLOWED_ATLAS_SIZES " +
                "(4096 is the largest atlas proven to render in-game)"
        }
        require(newAtlasSize >= origAtlasW) {
            "TMP patcher: new atlas size $newAtlasSize < original atlas size $origAtlasW; " +
                "baked atlas must not shrink the original atlas"
        }
        require(baked.atlasBytes.size == newAtlasSize * newAtlasSize) {
            "TMP patcher: atlas bytes size ${baked.atlasBytes.size} != $newAtlasSize x $newAtlasSize; baker output is inconsistent"
        }

        // Original unicodes and glyph indices, for duplicate / start-index checks.
        val origUnicodes = HashSet<Int>()
        for (item in charTable.items) {
            val c = requireStruct(item, "character")
            origUnicodes.add(requireNum(c.fields["m_Unicode"], "character.m_Unicode").value.toInt())
        }
        var maxExistingIndex = Long.MIN_VALUE
        for (item in glyphTable.items) {
            val g = requireStruct(item, "glyph")
            val idx = requireNum(g.fields["m_Index"], "glyph.m_Index").value
            if (idx > maxExistingIndex) maxExistingIndex = idx
        }

        // Validate baked glyphs, then sort by PUA (baker already sorts,
        // but sorting here makes the table order deterministic even
        // if a caller passes frequency-ordered glyphs directly).
        val seenPua = HashSet<Int>()
        for (g in baked.glyphs) {
            require(g.pua in 0xE000..0xF8FF) {
                "TMP patcher: baked glyph PUA 0x${g.pua.toString(16)} is outside E000..F8FF; " +
                    "v1 only bakes PUA glyphs (letters E000..E0FF, words E200..F8FF)"
            }
            require(seenPua.add(g.pua)) {
                "TMP patcher: duplicate baked glyph PUA 0x${g.pua.toString(16)}; baker output must be unique per PUA"
            }
            require(!origUnicodes.contains(g.pua)) {
                "TMP patcher: PUA 0x${g.pua.toString(16)} already exists in the original character table; " +
                    "fresh baking only (start from an original fallback, not an already-PUA-baked one)"
            }
            require(g.rectWidth > 0 && g.rectHeight > 0) {
                "TMP patcher: baked glyph 0x${g.pua.toString(16)} has non-positive rect ${g.rectWidth}x${g.rectHeight}; baker bug"
            }
            require(g.rectX >= 0 && g.rectY >= 0 &&
                g.rectX + g.rectWidth <= newAtlasSize &&
                g.rectY + g.rectHeight <= newAtlasSize) {
                "TMP patcher: baked glyph 0x${g.pua.toString(16)} rect " +
                    "(${g.rectX},${g.rectY},${g.rectWidth},${g.rectHeight}) lies outside the $newAtlasSize atlas; baker bug"
            }
            require(g.metricsWidth > 0 && g.metricsHeight > 0) {
                "TMP patcher: baked glyph 0x${g.pua.toString(16)} has non-positive metrics ${g.metricsWidth}x${g.metricsHeight}; baker bug"
            }
            require(g.advance > 0f) {
                "TMP patcher: baked glyph 0x${g.pua.toString(16)} has non-positive advance ${g.advance}; baker bug"
            }
        }
        val sorted = baked.glyphs.sortedBy { it.pua }
        val startIndex = maxOf(30000L, maxExistingIndex + 1)
        require(startIndex + sorted.size - 1 <= Int.MAX_VALUE) {
            "TMP patcher: glyph index overflow (start $startIndex + ${sorted.size} glyphs)"
        }

        // Build new glyph + character entries, by template copy.
        val glyphIndexByPua = LinkedHashMap<Int, Int>()
        val newGlyphEntries = ArrayList<UVal.Struct>(sorted.size)
        val newCharEntries = ArrayList<UVal.Struct>(sorted.size)
        for ((i, g) in sorted.withIndex()) {
            val gi = (startIndex + i).toInt()
            glyphIndexByPua[g.pua] = gi
            val metricsOverrides = mapOf<String, UVal>(
                "m_Width" to fltSingle(
                    requireFlt(templateMetrics.fields["m_Width"], "glyph.m_Metrics.m_Width"),
                    g.metricsWidth.toFloat(),
                ),
                "m_Height" to fltSingle(
                    requireFlt(templateMetrics.fields["m_Height"], "glyph.m_Metrics.m_Height"),
                    g.metricsHeight.toFloat(),
                ),
                "m_HorizontalBearingX" to fltSingle(
                    requireFlt(templateMetrics.fields["m_HorizontalBearingX"], "glyph.m_Metrics.m_HorizontalBearingX"),
                    g.bearingX,
                ),
                "m_HorizontalBearingY" to fltSingle(
                    requireFlt(templateMetrics.fields["m_HorizontalBearingY"], "glyph.m_Metrics.m_HorizontalBearingY"),
                    g.bearingY,
                ),
                "m_HorizontalAdvance" to fltSingle(
                    requireFlt(templateMetrics.fields["m_HorizontalAdvance"], "glyph.m_Metrics.m_HorizontalAdvance"),
                    g.advance,
                ),
            )
            val newMetrics = copyStructWithOverrides(templateMetrics, metricsOverrides, "glyph.m_Metrics")
            val rectOverrides = mapOf<String, UVal>(
                "m_X" to numWithWidth(
                    requireNum(templateRect.fields["m_X"], "glyph.m_GlyphRect.m_X"), g.rectX.toLong(),
                ),
                "m_Y" to numWithWidth(
                    requireNum(templateRect.fields["m_Y"], "glyph.m_GlyphRect.m_Y"), g.rectY.toLong(),
                ),
                "m_Width" to numWithWidth(
                    requireNum(templateRect.fields["m_Width"], "glyph.m_GlyphRect.m_Width"), g.rectWidth.toLong(),
                ),
                "m_Height" to numWithWidth(
                    requireNum(templateRect.fields["m_Height"], "glyph.m_GlyphRect.m_Height"), g.rectHeight.toLong(),
                ),
            )
            val newRect = copyStructWithOverrides(templateRect, rectOverrides, "glyph.m_GlyphRect")
            val glyphOverrides = mapOf<String, UVal>(
                "m_Index" to numWithWidth(
                    requireNum(templateGlyph.fields["m_Index"], "glyph.m_Index"), gi.toLong(),
                ),
                "m_Metrics" to newMetrics,
                "m_GlyphRect" to newRect,
            )
            newGlyphEntries.add(copyStructWithOverrides(templateGlyph, glyphOverrides, "glyph"))
            val charOverrides = mapOf<String, UVal>(
                "m_Unicode" to numWithWidth(
                    requireNum(templateChar.fields["m_Unicode"], "character.m_Unicode"), g.pua.toLong(),
                ),
                "m_GlyphIndex" to numWithWidth(
                    requireNum(templateChar.fields["m_GlyphIndex"], "character.m_GlyphIndex"), gi.toLong(),
                ),
            )
            newCharEntries.add(copyStructWithOverrides(templateChar, charOverrides, "character"))
        }

        // Final tables, sorted (glyphs by index, characters by unicode),
        // matching the proven Python build. Original entries verbatim.
        val allGlyphs = ArrayList<UVal>(glyphTable.items.size + newGlyphEntries.size)
        allGlyphs.addAll(glyphTable.items)
        allGlyphs.addAll(newGlyphEntries)
        allGlyphs.sortBy { (requireStruct(it, "glyph").fields["m_Index"] as UVal.Num).value }
        val allChars = ArrayList<UVal>(charTable.items.size + newCharEntries.size)
        allChars.addAll(charTable.items)
        allChars.addAll(newCharEntries)
        allChars.sortBy { (requireStruct(it, "character").fields["m_Unicode"] as UVal.Num).value }

        // Root overrides: atlas size (runtime) + creation-settings
        // atlas size (editor metadata, updated to match Python).
        // Everything else (FaceInfo, fontInfo, Used/Free rects,
        // fallback table, kerning, weights, PPtrs) stays verbatim.
        val rootOverrides = HashMap<String, UVal>()
        rootOverrides["m_AtlasWidth"] = numWithWidth(
            requireNum(fields["m_AtlasWidth"], "m_AtlasWidth"), newAtlasSize.toLong(),
        )
        rootOverrides["m_AtlasHeight"] = numWithWidth(
            requireNum(fields["m_AtlasHeight"], "m_AtlasHeight"), newAtlasSize.toLong(),
        )
        rootOverrides["m_GlyphTable"] = UVal.Arr(allGlyphs)
        rootOverrides["m_CharacterTable"] = UVal.Arr(allChars)
        val creationSettings = fields["m_CreationSettings"]
        if (creationSettings is UVal.Struct &&
            creationSettings.fields.containsKey("atlasWidth") &&
            creationSettings.fields.containsKey("atlasHeight")
        ) {
            val csOverrides = mapOf<String, UVal>(
                "atlasWidth" to numWithWidth(
                    requireNum(creationSettings.fields["atlasWidth"], "m_CreationSettings.atlasWidth"),
                    newAtlasSize.toLong(),
                ),
                "atlasHeight" to numWithWidth(
                    requireNum(creationSettings.fields["atlasHeight"], "m_CreationSettings.atlasHeight"),
                    newAtlasSize.toLong(),
                ),
            )
            rootOverrides["m_CreationSettings"] =
                copyStructWithOverrides(creationSettings, csOverrides, "m_CreationSettings")
        }
        // If CreationSettings is missing or has no atlas fields, it is
        // left verbatim (editor-only; runtime atlas fields above are
        // the ones that matter). No error: older TMP versions may not
        // have creation settings at all.
        val newTmp = copyStructWithOverrides(originalTmp, rootOverrides, "TMP_FontAsset")
        return PatchedTmpFontAsset(
            tmp = newTmp,
            glyphIndexByPua = glyphIndexByPua,
            originalGlyphCount = glyphTable.items.size,
            originalCharCount = charTable.items.size,
            newGlyphCount = sorted.size,
            atlasSize = newAtlasSize,
            startGlyphIndex = startIndex.toInt(),
        )
    }

    /**
     * Patch a decoded atlas Texture2D with new atlas bytes.
     *
     * Only m_Width, m_Height, m_CompleteImageSize, and the image data
     * change. Format, mip count, filter/wrap settings, platform blob,
     * stream data, and every other field stay verbatim. The image
     * data field is named "image data" in the Unity 2022.3 Texture2D
     * tree; if a different Unity version names it differently, this
     * fails fast with the available field names instead of patching
     * the wrong field.
     */
    fun patchAtlasTexture(
        originalTexture: UVal.Struct,
        newAtlasBytes: ByteArray,
        newAtlasSize: Int,
    ): PatchedAtlasTexture {
        val fields = originalTexture.fields
        require(newAtlasSize in ALLOWED_ATLAS_SIZES) {
            "TMP patcher: new atlas size $newAtlasSize is not one of $ALLOWED_ATLAS_SIZES"
        }
        require(newAtlasBytes.size == newAtlasSize * newAtlasSize) {
            "TMP patcher: atlas bytes size ${newAtlasBytes.size} != $newAtlasSize x $newAtlasSize; " +
                "Alpha8 atlas must be exactly one byte per pixel"
        }
        val format = requireNum(fields["m_TextureFormat"], "m_TextureFormat").value
        require(format == 1L) {
            "TMP patcher: atlas TextureFormat is $format, not 1 (Alpha8); " +
                "v1 only handles Alpha8 SDF atlases (1 byte/pixel)"
        }
        val mipCount = requireNum(fields["m_MipCount"], "m_MipCount").value
        require(mipCount == 1L) {
            "TMP patcher: atlas MipCount is $mipCount, not 1; v1 does not handle mipmapped atlases"
        }
        val imageCount = requireNum(fields["m_ImageCount"], "m_ImageCount").value
        require(imageCount == 1L) {
            "TMP patcher: atlas ImageCount is $imageCount, not 1; v1 does not handle cubemap/array textures"
        }
        val streamData = requireStruct(fields["m_StreamData"], "m_StreamData")
        val streamSize = requireNum(streamData.fields["size"], "m_StreamData.size").value
        val streamPath = streamData.fields["path"]
        require(streamSize == 0L && streamPath is UVal.Str && streamPath.value.isEmpty()) {
            "TMP patcher: atlas texture is streamed (StreamData size=$streamSize, path=$streamPath), " +
                "not embedded; v1 only handles embedded atlas textures"
        }
        val imageField = fields["image data"]
        require(imageField is UVal.Bytes) {
            "TMP patcher: Texture2D has no 'image data' Bytes field " +
                "(available: ${fields.keys}); this Unity version's Texture2D tree differs from 2022.3"
        }
        val overrides = mapOf<String, UVal>(
            "m_Width" to numWithWidth(
                requireNum(fields["m_Width"], "m_Width"), newAtlasSize.toLong(),
            ),
            "m_Height" to numWithWidth(
                requireNum(fields["m_Height"], "m_Height"), newAtlasSize.toLong(),
            ),
            "m_CompleteImageSize" to numWithWidth(
                requireNum(fields["m_CompleteImageSize"], "m_CompleteImageSize"),
                newAtlasBytes.size.toLong(),
            ),
            "image data" to UVal.Bytes(newAtlasBytes.copyOf()),
        )
        val newTexture = copyStructWithOverrides(originalTexture, overrides, "Texture2D")
        return PatchedAtlasTexture(
            texture = newTexture,
            atlasSize = newAtlasSize,
            imageSize = newAtlasBytes.size,
        )
    }
}
