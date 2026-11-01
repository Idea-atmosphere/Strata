package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class UnityPersianTmpPatcherTest {

    private fun num(value: Long, width: Int = 4) = UVal.Num(value, width)
    private fun flt(value: Double) = UVal.Flt(value, single = true)

    private fun glyphTemplate(
        index: Long = 19,
        width: Double = 41.0,
        height: Double = 60.0,
        bX: Double = 3.0,
        bY: Double = 59.0,
        adv: Double = 47.0,
        rectX: Long = 162,
        rectY: Long = 394,
        rectW: Long = 41,
        rectH: Long = 60,
    ): UVal.Struct {
        return UVal.Struct(
            linkedMapOf(
                "m_Index" to num(index),
                "m_Metrics" to UVal.Struct(
                    linkedMapOf(
                        "m_Width" to flt(width),
                        "m_Height" to flt(height),
                        "m_HorizontalBearingX" to flt(bX),
                        "m_HorizontalBearingY" to flt(bY),
                        "m_HorizontalAdvance" to flt(adv),
                    ),
                ),
                "m_GlyphRect" to UVal.Struct(
                    linkedMapOf(
                        "m_X" to num(rectX),
                        "m_Y" to num(rectY),
                        "m_Width" to num(rectW),
                        "m_Height" to num(rectH),
                    ),
                ),
                "m_Scale" to flt(1.0),
                "m_AtlasIndex" to num(0),
                "m_ClassDefinitionType" to num(0),
            ),
        )
    }

    private fun charTemplate(unicode: Long = 48, glyphIndex: Long = 19): UVal.Struct {
        return UVal.Struct(
            linkedMapOf(
                "m_ElementType" to UVal.Num(1, 1),
                "m_Unicode" to num(unicode),
                "m_GlyphIndex" to num(glyphIndex),
                "m_Scale" to flt(1.0),
            ),
        )
    }

    private fun tmpTemplate(
        glyphs: List<UVal.Struct> = listOf(glyphTemplate(19), glyphTemplate(20)),
        chars: List<UVal.Struct> = listOf(charTemplate(48, 19), charTemplate(49, 20)),
        atlasSize: Long = 512,
        pad: Long = 9,
        pointSize: Long = 84,
        creationAtlas: Long = 512,
    ): UVal.Struct {
        return UVal.Struct(
            linkedMapOf(
                "m_Name" to UVal.Str("Test Fallback"),
                "m_AtlasPopulationMode" to num(1),
                "m_FaceInfo" to UVal.Struct(
                    linkedMapOf(
                        "m_PointSize" to num(pointSize),
                        "m_FamilyName" to UVal.Str("Test"),
                    ),
                ),
                "m_GlyphTable" to UVal.Arr(glyphs),
                "m_CharacterTable" to UVal.Arr(chars),
                "m_AtlasTextures" to UVal.Arr(emptyList()),
                "m_UsedGlyphRects" to UVal.Arr(listOf(UVal.Struct(linkedMapOf("m_X" to num(0))))),
                "m_FreeGlyphRects" to UVal.Arr(emptyList()),
                "m_fontInfo" to UVal.Struct(
                    linkedMapOf(
                        "AtlasWidth" to flt(999.0),
                        "PointSize" to flt(86.0),
                    ),
                ),
                "m_AtlasWidth" to num(atlasSize),
                "m_AtlasHeight" to num(atlasSize),
                "m_AtlasPadding" to num(pad),
                "m_CreationSettings" to UVal.Struct(
                    linkedMapOf(
                        "atlasWidth" to num(creationAtlas),
                        "atlasHeight" to num(creationAtlas),
                        "pointSize" to num(pointSize),
                        "characterSequence" to UVal.Str("30-31"),
                    ),
                ),
            ),
        )
    }

    private fun textureTemplate(
        size: Long = 512,
        format: Long = 1,
        mipCount: Long = 1,
        imageCount: Long = 1,
        streamSize: Long = 0,
        streamPath: String = "",
        image: ByteArray = ByteArray(512 * 512),
    ): UVal.Struct {
        return UVal.Struct(
            linkedMapOf(
                "m_Name" to UVal.Str("Test Atlas"),
                "m_Width" to num(size),
                "m_Height" to num(size),
                "m_CompleteImageSize" to num(image.size.toLong()),
                "m_TextureFormat" to num(format),
                "m_MipCount" to num(mipCount),
                "m_ImageCount" to num(imageCount),
                "m_StreamData" to UVal.Struct(
                    linkedMapOf(
                        "offset" to UVal.Num(0, 8),
                        "size" to num(streamSize),
                        "path" to UVal.Str(streamPath),
                    ),
                ),
                "image data" to UVal.Bytes(image),
            ),
        )
    }

    private fun bakedGlyph(
        pua: Int,
        rectX: Int = 512,
        rectY: Int = 0,
        rectW: Int = 20,
        rectH: Int = 30,
        w: Int = 20,
        h: Int = 30,
        bX: Float = 2f,
        bY: Float = 28f,
        adv: Float = 24f,
    ): UnitySdfBaker.BakedGlyph {
        return UnitySdfBaker.BakedGlyph(pua, rectX, rectY, rectW, rectH, w, h, bX, bY, adv)
    }

    @Test
    fun patchTmpFontAssetAddsSortedPuaGlyphsWith30000Indices() {
        val tmp = tmpTemplate()
        val baked = UnitySdfBaker.BakedAtlas(
            ByteArray(1024 * 1024),
            1024,
            listOf(bakedGlyph(0xE200, 512, 0), bakedGlyph(0xE010, 532, 0)),
            emptyList(),
        )
        val patched = UnityPersianTmpPatcher.patchTmpFontAsset(tmp, baked, bakerPad = 9, pointSize = 84)
        assertThat(patched.startGlyphIndex).isEqualTo(30000)
        assertThat(patched.glyphIndexByPua).containsExactly(0xE010, 30000, 0xE200, 30001)
        assertThat(patched.originalGlyphCount).isEqualTo(2)
        assertThat(patched.newGlyphCount).isEqualTo(2)
        val out = patched.tmp.fields
        assertThat((out["m_GlyphTable"] as UVal.Arr).items).hasSize(4)
        assertThat((out["m_CharacterTable"] as UVal.Arr).items).hasSize(4)
        assertThat((out["m_AtlasWidth"] as UVal.Num).value).isEqualTo(1024)
        assertThat((out["m_AtlasHeight"] as UVal.Num).value).isEqualTo(1024)
        val cs = out["m_CreationSettings"] as UVal.Struct
        assertThat((cs.fields["atlasWidth"] as UVal.Num).value).isEqualTo(1024)
        assertThat((cs.fields["atlasHeight"] as UVal.Num).value).isEqualTo(1024)
        // Creation sequence and legacy fontInfo stay verbatim (editor/legacy only).
        assertThat((cs.fields["characterSequence"] as UVal.Str).value).isEqualTo("30-31")
        val fi = out["m_fontInfo"] as UVal.Struct
        assertThat((fi.fields["AtlasWidth"] as UVal.Flt).value).isEqualTo(999.0)
        // New character + glyph entries, by PUA.
        val chars = (out["m_CharacterTable"] as UVal.Arr).items.map { it as UVal.Struct }
        val charE010 = chars.first { (it.fields["m_Unicode"] as UVal.Num).value.toInt() == 0xE010 }
        assertThat((charE010.fields["m_GlyphIndex"] as UVal.Num).value).isEqualTo(30000)
        assertThat((charE010.fields["m_ElementType"] as UVal.Num).value).isEqualTo(1)
        assertThat((charE010.fields["m_Scale"] as UVal.Flt).value).isEqualTo(1.0)
        val glyphs = (out["m_GlyphTable"] as UVal.Arr).items.map { it as UVal.Struct }
        val glyph30000 = glyphs.first { (it.fields["m_Index"] as UVal.Num).value.toInt() == 30000 }
        val mets = glyph30000.fields["m_Metrics"] as UVal.Struct
        assertThat((mets.fields["m_Width"] as UVal.Flt).value).isEqualTo(20.0)
        assertThat((mets.fields["m_HorizontalAdvance"] as UVal.Flt).value).isEqualTo(24.0)
        val rect = glyph30000.fields["m_GlyphRect"] as UVal.Struct
        assertThat((rect.fields["m_X"] as UVal.Num).value).isEqualTo(532)
        // Tables sorted.
        val glyphIdxs = glyphs.map { (it.fields["m_Index"] as UVal.Num).value }
        assertThat(glyphIdxs).isEqualTo(glyphIdxs.sorted())
        val charUnis = chars.map { (it.fields["m_Unicode"] as UVal.Num).value }
        assertThat(charUnis).isEqualTo(charUnis.sorted())
    }

    @Test
    fun patchTmpFontAssetRejectsMismatchedPadPointSizeAndBadGlyphs() {
        val tmp = tmpTemplate()
        val good = UnitySdfBaker.BakedAtlas(ByteArray(1024 * 1024), 1024, listOf(bakedGlyph(0xE010)), emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianTmpPatcher.patchTmpFontAsset(tmp, good, bakerPad = 8, pointSize = 84)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianTmpPatcher.patchTmpFontAsset(tmp, good, bakerPad = 9, pointSize = 60)
        }
        // Duplicate PUA within the baked list.
        assertThrows(IllegalArgumentException::class.java) {
            val dup = UnitySdfBaker.BakedAtlas(
                ByteArray(1024 * 1024), 1024,
                listOf(bakedGlyph(0xE010), bakedGlyph(0xE010, 552, 0)), emptyList(),
            )
            UnityPersianTmpPatcher.patchTmpFontAsset(tmp, dup, bakerPad = 9, pointSize = 84)
        }
        // PUA already in the original table (fresh baking only).
        assertThrows(IllegalArgumentException::class.java) {
            val withPua = tmpTemplate(chars = listOf(charTemplate(48, 19), charTemplate(0xE010, 30000)))
            UnityPersianTmpPatcher.patchTmpFontAsset(withPua, good, bakerPad = 9, pointSize = 84)
        }
        // Non-PUA, out-of-bounds rect, non-positive advance, disallowed atlas size.
        assertThrows(IllegalArgumentException::class.java) {
            val np = UnitySdfBaker.BakedAtlas(ByteArray(1024 * 1024), 1024, listOf(bakedGlyph(65)), emptyList())
            UnityPersianTmpPatcher.patchTmpFontAsset(tmp, np, bakerPad = 9, pointSize = 84)
        }
        assertThrows(IllegalArgumentException::class.java) {
            val oob = UnitySdfBaker.BakedAtlas(
                ByteArray(1024 * 1024), 1024, listOf(bakedGlyph(0xE010, 1010, 0, 20, 30)), emptyList(),
            )
            UnityPersianTmpPatcher.patchTmpFontAsset(tmp, oob, bakerPad = 9, pointSize = 84)
        }
        assertThrows(IllegalArgumentException::class.java) {
            val badAdv = UnitySdfBaker.BakedAtlas(
                ByteArray(1024 * 1024), 1024, listOf(bakedGlyph(0xE010, 512, 0, 20, 30, 20, 30, 2f, 28f, 0f)), emptyList(),
            )
            UnityPersianTmpPatcher.patchTmpFontAsset(tmp, badAdv, bakerPad = 9, pointSize = 84)
        }
        assertThrows(IllegalArgumentException::class.java) {
            // Atlas-size check comes before the bytes-size check, so an
            // empty byte array still proves the disallowed size is rejected.
            val badAtlas = UnitySdfBaker.BakedAtlas(ByteArray(0), 3000, listOf(bakedGlyph(0xE010)), emptyList())
            UnityPersianTmpPatcher.patchTmpFontAsset(tmp, badAtlas, bakerPad = 9, pointSize = 84)
        }
    }

    @Test
    fun patchAtlasTextureUpdatesSizeAndImageOnly() {
        val tex = textureTemplate()
        val newBytes = ByteArray(1024 * 1024) { 7 }
        val patched = UnityPersianTmpPatcher.patchAtlasTexture(tex, newBytes, 1024)
        val out = patched.texture.fields
        assertThat((out["m_Width"] as UVal.Num).value).isEqualTo(1024)
        assertThat((out["m_Height"] as UVal.Num).value).isEqualTo(1024)
        assertThat((out["m_CompleteImageSize"] as UVal.Num).value).isEqualTo(1024L * 1024)
        assertThat((out["image data"] as UVal.Bytes).data).isEqualTo(newBytes)
        assertThat((out["m_TextureFormat"] as UVal.Num).value).isEqualTo(1)
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianTmpPatcher.patchAtlasTexture(textureTemplate(format = 4), newBytes, 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianTmpPatcher.patchAtlasTexture(textureTemplate(mipCount = 2), newBytes, 1024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianTmpPatcher.patchAtlasTexture(textureTemplate(streamSize = 100, streamPath = "stream"), newBytes, 1024)
        }
    }
}
