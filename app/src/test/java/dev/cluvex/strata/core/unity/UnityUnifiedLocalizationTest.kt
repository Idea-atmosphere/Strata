package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnityUnifiedLocalizationTest {

    private fun source(
        name: String,
        entryName: String,
        pathId: Long,
        keys: List<String>,
        valuesByCode: Map<String, String>,
        codes: List<String> = listOf("en", "fr", "it"),
    ): LocalizationSource {
        val languages = codes.map { LocalizationLanguage(it, it, 0) }
        val terms = keys.map { key ->
            LocalizationTerm.aligned(key, codes.map { valuesByCode[it] ?: "X" }, codes.size)
        }
        val origin = LocalizationOrigin(
            entryName = entryName,
            partIndex = 0,
            pathId = pathId,
            objectEntryName = "test",
            termsPath = listOf("mSource", "mTerms"),
            languagesPath = listOf("mSource", "mLanguages"),
            languageCountPath = null,
            termKeyField = "Term",
            termValuesField = "Languages",
            languageNameField = "Name",
            languageCodeField = "Code",
            languageFlagsField = "Flags",
        )
        return LocalizationSource.create(name, languages, terms, origin)
    }

    @Test
    fun sharedKeyEditAppliesToAllTables() {
        val menu = source("I2Menu", "entry-a", 1L, listOf("SHARED", "MENU_ONLY"), mapOf("en" to "EN", "fr" to "FR"))
        val sub = source("I2Sub", "entry-b", 2L, listOf("SHARED", "SUB_ONLY"), mapOf("en" to "EN", "fr" to "FR"))
        val plans = UnityUnifiedLocalization.buildPlans(
            mapOf("entry-a" to listOf(menu), "entry-b" to listOf(sub)),
            mapOf("SHARED" to "NOUVEAU"),
            "fr",
            "en",
        )
        assertThat(plans).hasSize(2)
        for (plan in plans) {
            assertThat(plan.plan.canApply).isTrue()
            assertThat(plan.plan.changes).hasSize(1)
            assertThat(plan.plan.changes.single().key).isEqualTo("SHARED")
            assertThat(plan.plan.changes.single().newValue).isEqualTo("NOUVEAU")
            val updated = plan.plan.updated!!
            val shared = updated.terms.first { it.key == "SHARED" }
            assertThat(shared.values[1]).isEqualTo("NOUVEAU")
        }
    }

    @Test
    fun placeholderMismatchSkipsOnlyThatTerm() {
        val table = source(
            "I2Menu", "entry-a", 1L, listOf("GOOD", "BAD"),
            mapOf("en" to "Hello {player}", "fr" to "Bonjour {player}"),
        )
        val plans = UnityUnifiedLocalization.buildPlans(
            mapOf("entry-a" to listOf(table)),
            mapOf("GOOD" to "Salut {player}", "BAD" to "Salut"),
            "fr",
            "en",
        )
        assertThat(plans).hasSize(1)
        val plan = plans.single().plan
        assertThat(plan.canApply).isTrue()
        assertThat(plan.changes.map { it.key }).containsExactly("GOOD")
        assertThat(plan.placeholderFailureCount).isEqualTo(1)
    }

    @Test
    fun emptyEditIsSkipped() {
        val table = source("I2Menu", "entry-a", 1L, listOf("K1"), mapOf("en" to "One", "fr" to "Un"))
        val plans = UnityUnifiedLocalization.buildPlans(
            mapOf("entry-a" to listOf(table)),
            mapOf("K1" to ""),
            "fr",
            "en",
        )
        assertThat(plans).hasSize(1)
        assertThat(plans.single().plan.canApply).isFalse()
        assertThat(plans.single().plan.emptyCellCount).isEqualTo(1)
    }

    @Test
    fun tableWithoutTargetLanguageGetsNoPlan() {
        val table = source("I2Menu", "entry-a", 1L, listOf("K1"), mapOf("en" to "One"), codes = listOf("en"))
        val plans = UnityUnifiedLocalization.buildPlans(
            mapOf("entry-a" to listOf(table)),
            mapOf("K1" to "Un"),
            "fr",
            "en",
        )
        assertThat(plans).isEmpty()
    }

    @Test
    fun quotingRoundTripsThroughBuiltTsv() {
        val table = source("I2Menu", "entry-a", 1L, listOf("K1"), mapOf("en" to "One", "fr" to "Un"))
        val edited = "a\tb\"c\nline2"
        val plans = UnityUnifiedLocalization.buildPlans(
            mapOf("entry-a" to listOf(table)),
            mapOf("K1" to edited),
            "fr",
            "en",
        )
        assertThat(plans.single().plan.canApply).isTrue()
        assertThat(plans.single().plan.changes.single().newValue).isEqualTo(edited)
    }

    @Test
    fun sharedTableCounts() {
        val menu = source("I2Menu", "entry-a", 1L, listOf("SHARED", "MENU_ONLY"), mapOf("en" to "EN"))
        val sub = source("I2Sub", "entry-b", 2L, listOf("SHARED"), mapOf("en" to "EN"))
        val counts = UnityUnifiedLocalization.sharedTableCounts(listOf(menu, sub))
        assertThat(counts["SHARED"]).isEqualTo(2)
        assertThat(counts["MENU_ONLY"]).isEqualTo(1)
    }
}
