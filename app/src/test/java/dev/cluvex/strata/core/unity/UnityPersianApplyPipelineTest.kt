package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Repo JUnit/Truth tests for the Persian Apply pipeline, with
 * synthetic TMP/Texture templates (CI has no game files; the VM
 * scratch test MainPipelineTest proves the same pipeline end to
 * end with the real Bendy fallback). Templates mirror
 * UnityPersianTmpPatcherTest's templates intentionally: each test
 * file is self-contained, with no shared test utilities, so the
 * pipeline test does not depend on the patcher test file.
 */
class UnityPersianApplyPipelineTest {

    private fun num(value: Long, width: Int = 4) = UVal.Num(value, width)
    private fun flt(value: Double) = UVal.Flt(value, single = true)

    private fun glyphTemplate(index: Long = 19): UVal.Struct {
        return UVal.Struct(
            linkedMapOf(
                "m_Index" to num(index),
                "m_Metrics" to UVal.Struct(
                    linkedMapOf(
                        "m_Width" to flt(41.0),
                        "m_Height" to flt(60.0),
                        "m_HorizontalBearingX" to flt(3.0),
                        "m_HorizontalBearingY" to flt(59.0),
                        "m_HorizontalAdvance" to flt(47.0),
                    ),
                ),
                "m_GlyphRect" to UVal.Struct(
                    linkedMapOf(
                        "m_X" to num(162),
                        "m_Y" to num(394),
                        "m_Width" to num(41),
                        "m_Height" to num(60),
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
        chars: List<UVal.Struct> = listOf(charTemplate(48, 19), charTemplate(49, 20)),
    ): UVal.Struct {
        return UVal.Struct(
            linkedMapOf(
                "m_Name" to UVal.Str("Test Fallback"),
                "m_AtlasPopulationMode" to num(1),
                "m_FaceInfo" to UVal.Struct(
                    linkedMapOf(
                        "m_PointSize" to num(84),
                        "m_FamilyName" to UVal.Str("Test"),
                    ),
                ),
                "m_GlyphTable" to UVal.Arr(listOf(glyphTemplate(19), glyphTemplate(20))),
                "m_CharacterTable" to UVal.Arr(chars),
                "m_AtlasTextures" to UVal.Arr(emptyList()),
                "m_UsedGlyphRects" to UVal.Arr(emptyList()),
                "m_FreeGlyphRects" to UVal.Arr(emptyList()),
                "m_fontInfo" to UVal.Struct(linkedMapOf("AtlasWidth" to flt(999.0))),
                "m_AtlasWidth" to num(512),
                "m_AtlasHeight" to num(512),
                "m_AtlasPadding" to num(9),
                "m_CreationSettings" to UVal.Struct(
                    linkedMapOf(
                        "atlasWidth" to num(512),
                        "atlasHeight" to num(512),
                        "pointSize" to num(84),
                    ),
                ),
            ),
        )
    }

    private fun textureTemplate(): UVal.Struct {
        return UVal.Struct(
            linkedMapOf(
                "m_Name" to UVal.Str("Test Atlas"),
                "m_Width" to num(512),
                "m_Height" to num(512),
                "m_CompleteImageSize" to num(512L * 512),
                "m_TextureFormat" to num(1),
                "m_MipCount" to num(1),
                "m_ImageCount" to num(1),
                "m_StreamData" to UVal.Struct(
                    linkedMapOf(
                        "offset" to UVal.Num(0, 8),
                        "size" to num(0),
                        "path" to UVal.Str(""),
                    ),
                ),
                "image data" to UVal.Bytes(ByteArray(512 * 512)),
            ),
        )
    }

    private class SyntheticRasterizer : UnityPersianApplyPipeline.LetterRasterizer {
        override fun rasterize(
            letterPua: Int,
            originalCodepoint: Int,
            pointSize: Float,
        ): UnitySdfBaker.LetterInput {
            return UnitySdfBaker.LetterInput(
                pua = letterPua,
                core = BooleanArray(14 * 32) { true },
                coreWidth = 14,
                coreHeight = 32,
                advance = 20f,
                bearingX = 2f,
                bearingY = 30f,
            )
        }
    }

    @Test
    fun preparePersianTermsReturnsReadyWithPuaTermsAndPatchedObjects() {
        val terms = listOf(
            UnityPersianApplyPipeline.PersianTermInput("HEADER", "هشدار: خطر نور"),
            UnityPersianApplyPipeline.PersianTermInput("BODY", "خطر نور است"),
            UnityPersianApplyPipeline.PersianTermInput("EMPTY", ""),
        )
        val result = UnityPersianApplyPipeline.preparePersianTerms(
            terms, tmpTemplate(), textureTemplate(), SyntheticRasterizer(),
        )
        assertThat(result).isInstanceOf(UnityPersianApplyPipeline.PipelineResult.Ready::class.java)
        val ready = result as UnityPersianApplyPipeline.PipelineResult.Ready
        assertThat(ready.finalTerms).hasSize(3)
        assertThat(ready.finalTerms[2]).isEmpty()
        assertThat(ready.issues).isEmpty()
        for (term in ready.finalTerms) {
            for (ch in term) {
                val code = ch.code
                assertThat(code !in 0x0600..0x06FF).isTrue()
                assertThat(code !in 0xE002..0xE005).isTrue()
            }
        }
        assertThat(ready.stats.atlasSize).isEqualTo(2048)
        assertThat(ready.stats.unpackedWordPuas).isEmpty()
        assertThat(ready.stats.letterFallbackOccurrences).isEqualTo(0)
        assertThat(ready.stats.bakedWordOccurrences).isEqualTo(ready.stats.totalWordOccurrences)
        val patchedGlyphs = (ready.patchedTmp.fields["m_GlyphTable"] as UVal.Arr).items
        assertThat(patchedGlyphs).hasSize(2 + ready.stats.bakedGlyphCount)
        assertThat((ready.patchedTmp.fields["m_AtlasWidth"] as UVal.Num).value).isEqualTo(2048)
        assertThat((ready.patchedTexture.fields["m_Width"] as UVal.Num).value).isEqualTo(2048)
        assertThat((ready.patchedTexture.fields["image data"] as UVal.Bytes).data.size).isEqualTo(2048 * 2048)
    }

    @Test
    fun preparePersianTermsReturnsNotReadyForFixableProblems() {
        val tmp = tmpTemplate()
        val tex = textureTemplate()
        // PUA in input.
        val pua = UnityPersianApplyPipeline.preparePersianTerms(
            listOf(UnityPersianApplyPipeline.PersianTermInput("BAD", "سلام \uE010")),
            tmp, tex, SyntheticRasterizer(),
        )
        assertThat(pua).isInstanceOf(UnityPersianApplyPipeline.PipelineResult.NotReady::class.java)
        assertThat((pua as UnityPersianApplyPipeline.PipelineResult.NotReady).issues).isNotEmpty()
        // Unmapped supplement Arabic.
        val unmapped = UnityPersianApplyPipeline.preparePersianTerms(
            listOf(UnityPersianApplyPipeline.PersianTermInput("BAD2", "سلام \u0750")),
            tmp, tex, SyntheticRasterizer(),
        )
        assertThat(unmapped).isInstanceOf(UnityPersianApplyPipeline.PipelineResult.NotReady::class.java)
        assertThat(
            (unmapped as UnityPersianApplyPipeline.PipelineResult.NotReady).issues.any { it.contains("0750") },
        ).isTrue()
        // Base already PUA-baked.
        val already = UnityPersianApplyPipeline.preparePersianTerms(
            listOf(UnityPersianApplyPipeline.PersianTermInput("T", "سلام")),
            tmpTemplate(chars = listOf(charTemplate(48, 19), charTemplate(0xE010, 30000))),
            tex, SyntheticRasterizer(),
        )
        assertThat(already).isInstanceOf(UnityPersianApplyPipeline.PipelineResult.NotReady::class.java)
        assertThat(
            (already as UnityPersianApplyPipeline.PipelineResult.NotReady).issues.any { it.contains("original fallback") },
        ).isTrue()
        // Missing glyph: a rasterizer that always throws the
        // pipeline's exception type must yield NotReady, not a throw.
        // (The VM scratch test proves the same conversion with a
        // recorded first-letter PUA; here an always-fail rasterizer
        // proves it deterministically, without depending on which
        // letters a specific term contains.)
        val alwaysFail = object : UnityPersianApplyPipeline.LetterRasterizer {
            override fun rasterize(
                letterPua: Int,
                originalCodepoint: Int,
                pointSize: Float,
            ): UnitySdfBaker.LetterInput {
                throw UnityPersianApplyPipeline.PersianRasterizerException("test missing glyph")
            }
        }
        val missing2 = UnityPersianApplyPipeline.preparePersianTerms(
            listOf(UnityPersianApplyPipeline.PersianTermInput("T", "سلام")),
            tmp, tex, alwaysFail,
        )
        assertThat(missing2).isInstanceOf(UnityPersianApplyPipeline.PipelineResult.NotReady::class.java)
        assertThat(
            (missing2 as UnityPersianApplyPipeline.PipelineResult.NotReady).issues.any { it.contains("Font has no glyph") },
        ).isTrue()
    }
}
