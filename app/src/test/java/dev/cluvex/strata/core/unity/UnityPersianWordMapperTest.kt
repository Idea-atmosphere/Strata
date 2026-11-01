package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnityPersianWordMapperTest {

    @Test
    fun cipherTableIsExactlyTheBendyTable() {
        val table = UnityPersianWordMapper.ARABIC_TO_PUA
        assertThat(table).hasSize(128)
        assertThat(table.values.toSet()).hasSize(128)
        assertThat(table.values.min()).isEqualTo(0xE000)
        assertThat(table.values.max()).isEqualTo(0xE07F)
        assertThat(table[0x061B]).isEqualTo(0xE07E)
        assertThat(table[0xFEF5]).isEqualTo(0xE07F)
        assertThat(table[0x064B]).isEqualTo(0xE002)
        assertThat(table[0xFB57]).isEqualTo(0xE006)
    }

    @Test
    fun sameWordAcrossTermsGetsTheSameWordCodeInFirstAppearanceOrder() {
        // Shaped forms: FB57 -> E006, FE80 -> E01B, FE81 -> E01C.
        val batch = UnityPersianWordMapper.mapShapedTerms(
            listOf("\uFB57\uFE80 \uFB57\uFE80", "\uFB57\uFE80 \uFE81"),
        )
        assertThat(batch.mappedTerms[0]).isEqualTo("\uE200 \uE200")
        assertThat(batch.mappedTerms[1]).isEqualTo("\uE200 \uE201")
        assertThat(batch.wordMap).hasSize(2)
        assertThat(batch.wordMap.values.toSet()).containsExactly(0xE200, 0xE201)
        assertThat(batch.unmappedCodepoints).isEmpty()
        assertThat(batch.extensionLetters).isEmpty()
    }

    @Test
    fun diacriticsAreStrippedFromBothArabicAndPuaInputs() {
        // Arabic kasra (0650) and direct PUA kasra (E003) both disappear,
        // so both words map to the same word code.
        val batch = UnityPersianWordMapper.mapShapedTerms(
            listOf("\uFB57\u0650\uFE80 \uFB57\uE003\uFE80"),
        )
        assertThat(batch.mappedTerms[0]).isEqualTo("\uE200 \uE200")
        assertThat(batch.mappedTerms[0]).doesNotContain("\uE003")
        assertThat(batch.mappedTerms[0]).doesNotContain("\u0650")
    }

    @Test
    fun persianDigitsBecomeLatinDigitsAndSplitWordRuns() {
        val batch = UnityPersianWordMapper.mapShapedTerms(
            listOf("\uFB57\u06F3\uFE80"),
        )
        assertThat(batch.mappedTerms[0]).isEqualTo("\uE200" + "3" + "\uE201")
    }

    @Test
    fun punctuationAndParagraphBreaksArePreservedExactly() {
        val batch = UnityPersianWordMapper.mapShapedTerms(
            listOf("\uFB57\uFE80.\n\n\n\uFE81!"),
        )
        assertThat(batch.mappedTerms[0]).isEqualTo("\uE200.\n\n\n\uE201!")
    }

    @Test
    fun presentationFormsOutsideTheBendyTableGetExtensionLetterCodes() {
        // FEF6/FEF7 are Lam-Alef ligatures the Strata shaper produces
        // (see UnityFontAndShapingTest) that Bendy's text never contained,
        // so they are not in the 128-entry cipher table. They must get
        // extension codes (E080..), not stay Arabic (which would be squares).
        val batch = UnityPersianWordMapper.mapShapedTerms(
            listOf("\uFEF6 \uFEF6 \uFEF7"),
        )
        assertThat(batch.extensionLetters[0xFEF6]).isEqualTo(0xE080)
        assertThat(batch.extensionLetters[0xFEF7]).isEqualTo(0xE081)
        assertThat(batch.mappedTerms[0]).isEqualTo("\uE200 \uE200 \uE201")
        assertThat(batch.unmappedCodepoints).isEmpty()
    }

    @Test
    fun logicalArabicIsReportedUnmappedInsteadOfSilentlyBaked() {
        // 0627 (logical Alef) means the input was not shaped. It must be
        // reported, not assigned a glyph (that would bake the wrong,
        // isolated form).
        val batch = UnityPersianWordMapper.mapShapedTerms(
            listOf("\u0627 \uFB57"),
        )
        assertThat(batch.unmappedCodepoints).contains(0x0627)
        assertThat(batch.mappedTerms[0]).startsWith("\u0627 ")
    }

    @Test
    fun emptyAndPunctuationOnlyTermsPassThroughUnchanged() {
        val batch = UnityPersianWordMapper.mapShapedTerms(listOf("", "   ", "..."))
        assertThat(batch.mappedTerms).containsExactly("", "   ", "...").inOrder()
        assertThat(batch.wordMap).isEmpty()
    }
}
