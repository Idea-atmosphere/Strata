package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnityPersianFrequencyTest {

    @Test
    fun rankWordsByFrequencyWithFirstAppearanceTieBreak() {
        val wordMap = mapOf("aa" to 0xE200, "bb" to 0xE201, "cc" to 0xE202)
        val terms = listOf(
            "\uE200 \uE201",
            "\uE200 \uE200 \uE201",
            "\uE201 \uE202",
            "hello \uE000!",
        )
        val ranked = UnityPersianFrequency.rankWords(terms, wordMap)
        assertThat(ranked).hasSize(3)
        // E200 and E201 both occur 3 times; first-appearance order wins the tie.
        assertThat(ranked[0].wordPua).isEqualTo(0xE200)
        assertThat(ranked[0].frequency).isEqualTo(3)
        assertThat(ranked[1].wordPua).isEqualTo(0xE201)
        assertThat(ranked[1].frequency).isEqualTo(3)
        assertThat(ranked[2].wordPua).isEqualTo(0xE202)
        assertThat(ranked[2].frequency).isEqualTo(1)
    }

    @Test
    fun applyLetterFallbackReplacesOnlyUnpackedWords() {
        val wordMap = mapOf("aa" to 0xE200, "bb" to 0xE201, "cc" to 0xE202)
        val terms = listOf("\uE200 \uE201", "\uE201 \uE202")
        val hybrid = UnityPersianFrequency.applyLetterFallback(terms, setOf(0xE202), wordMap)
        assertThat(hybrid[0]).isEqualTo("\uE200 \uE201")
        assertThat(hybrid[1]).isEqualTo("\uE201 cc")
        assertThat(UnityPersianFrequency.applyLetterFallback(terms, emptySet(), wordMap)).isEqualTo(terms)
        // Unknown unpacked PUA (legacy, no core in wordMap) stays as-is, never crashes.
        assertThat(UnityPersianFrequency.applyLetterFallback(listOf("\uE100"), setOf(0xE100), wordMap))
            .containsExactly("\uE100")
    }

    @Test
    fun hybridStatsCountsBakedVersusFallbackOccurrences() {
        val wordMap = mapOf("aa" to 0xE200, "bb" to 0xE201, "cc" to 0xE202)
        val terms = listOf("\uE200 \uE201", "\uE200 \uE200 \uE201", "\uE201 \uE202")
        val stats = UnityPersianFrequency.hybridStats(terms, setOf(0xE202), wordMap)
        assertThat(stats.bakedWordOccurrences).isEqualTo(6)
        assertThat(stats.letterFallbackOccurrences).isEqualTo(1)
    }

    @Test
    fun checkBakeCoverageFlagsMissingGlyphsDiacriticsAndLogicalArabic() {
        // Real letter-PUA cores: E200 word core = E022 E026, E201 word core = E027.
        val wordMap = mapOf("\uE022\uE026" to 0xE200, "\uE027" to 0xE201)
        val hybrid = UnityPersianFrequency.applyLetterFallback(
            listOf("\uE200 \uE201", "\uE200"), setOf(0xE201), wordMap,
        )
        assertThat(hybrid[0]).isEqualTo("\uE200 \uE027")
        val ready = UnityPersianFrequency.checkBakeCoverage(hybrid, setOf(0xE200, 0xE027), wordMap)
        assertThat(ready.isReady).isTrue()
        assertThat(ready.bakedWordOccurrences).isEqualTo(2)
        // Missing letter glyph -> not ready.
        assertThat(UnityPersianFrequency.checkBakeCoverage(hybrid, setOf(0xE200), wordMap).isReady).isFalse()
        // Missing word glyph -> not ready.
        assertThat(UnityPersianFrequency.checkBakeCoverage(listOf("\uE200"), setOf(0xE027), wordMap).isReady).isFalse()
        // Diacritic PUA is an issue even when baked (user rule: strip diacritics).
        assertThat(UnityPersianFrequency.checkBakeCoverage(listOf("\uE003"), setOf(0xE003), wordMap).isReady).isFalse()
        // Logical Arabic -> issue.
        assertThat(UnityPersianFrequency.checkBakeCoverage(listOf("\u0627"), emptySet(), wordMap).isReady).isFalse()
        // Unused PUA without a glyph -> issue, not silently fine.
        assertThat(UnityPersianFrequency.checkBakeCoverage(listOf("\uE150"), emptySet(), wordMap).isReady).isFalse()
    }

    @Test
    fun atlasGrowthRecommendationAndRetryAreSaneHeuristics() {
        assertThat(UnityPersianFrequency.estimateAtlasGrowthBytes(2048, 512))
            .isEqualTo(2048L * 2048 - 512L * 512)
        assertThat(UnityPersianFrequency.formatBytes(16777216)).isEqualTo("16.0 MB")
        assertThat(UnityPersianFrequency.recommendAtlasSize(70)).isEqualTo(2048)
        assertThat(UnityPersianFrequency.recommendAtlasSize(500)).isEqualTo(4096)
        assertThat(UnityPersianFrequency.shouldRetryAt4096(2048, 30, 100)).isTrue()
        assertThat(UnityPersianFrequency.shouldRetryAt4096(2048, 10, 100)).isFalse()
        assertThat(UnityPersianFrequency.shouldRetryAt4096(4096, 90, 100)).isFalse()
        assertThat(UnityPersianFrequency.shouldRetryAt4096(2048, 0, 0)).isFalse()
    }
}
