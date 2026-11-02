package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class UnityUnifiedPersianTest {

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
    fun gatherUsesEditsOverCurrentValuesAndKeepsTableOrder() {
        val menu = source("I2Menu", "entry-a", 1L, listOf("SHARED", "MENU_ONLY"), mapOf("en" to "EN", "fr" to "FR"))
        val sub = source("I2Sub", "entry-b", 2L, listOf("SHARED"), mapOf("en" to "EN", "fr" to "FR"))
        val gathered = UnityUnifiedPersian.gatherTerms(
            mapOf("entry-a" to listOf(menu), "entry-b" to listOf(sub)),
            mapOf("SHARED" to "EDITED"),
            "fr",
            "en",
        )
        assertThat(gathered.pipelineTerms.map { it.persianText })
            .containsExactly("EDITED", "FR", "EDITED").inOrder()
        assertThat(gathered.termRefs.map { it.tableId })
            .containsExactly(menu.origin.tableId, menu.origin.tableId, sub.origin.tableId).inOrder()
        assertThat(gathered.placeholderIssues).isEmpty()
    }

    @Test
    fun gatherSkipsTablesWithoutTargetLanguage() {
        val table = source("I2Menu", "entry-a", 1L, listOf("K1"), mapOf("en" to "One"), codes = listOf("en"))
        val gathered = UnityUnifiedPersian.gatherTerms(
            mapOf("entry-a" to listOf(table)),
            mapOf("K1" to "Un"),
            "fr",
            "en",
        )
        assertThat(gathered.pipelineTerms).isEmpty()
    }

    @Test
    fun placeholderMismatchExcludesOnlyThatTerm() {
        val table = source(
            "I2Menu", "entry-a", 1L, listOf("GOOD", "BAD"),
            mapOf("en" to "Hello {player}", "fr" to "Bonjour {player}"),
        )
        val gathered = UnityUnifiedPersian.gatherTerms(
            mapOf("entry-a" to listOf(table)),
            mapOf("GOOD" to "Salut {player}", "BAD" to "Salut"),
            "fr",
            "en",
        )
        assertThat(gathered.pipelineTerms.map { it.termKey }).containsExactly("GOOD")
        assertThat(gathered.placeholderIssues).hasSize(1)
        assertThat(gathered.placeholderIssues.single()).contains("I2Menu")
    }

    @Test
    fun distributeMapsByPositionNotByKey() {
        val menu = source("I2Menu", "entry-a", 1L, listOf("SHARED"), mapOf("en" to "EN", "fr" to "FR"))
        val sub = source("I2Sub", "entry-b", 2L, listOf("SHARED"), mapOf("en" to "EN", "fr" to "FR"))
        val gathered = UnityUnifiedPersian.gatherTerms(
            mapOf("entry-a" to listOf(menu), "entry-b" to listOf(sub)),
            mapOf("SHARED" to "EDITED"),
            "fr",
            "en",
        )
        // Same key, different final texts per table: position decides.
        val byTable = UnityUnifiedPersian.distributeFinalTerms(gathered, listOf("PUA-MENU", "PUA-SUB"))
        assertThat(byTable[menu.origin.tableId]!!["SHARED"]).isEqualTo("PUA-MENU")
        assertThat(byTable[sub.origin.tableId]!!["SHARED"]).isEqualTo("PUA-SUB")
    }

    @Test
    fun distributeRejectsSizeMismatch() {
        val table = source("I2Menu", "entry-a", 1L, listOf("K1"), mapOf("en" to "One", "fr" to "Un"))
        val gathered = UnityUnifiedPersian.gatherTerms(
            mapOf("entry-a" to listOf(table)),
            emptyMap(),
            "fr",
            "en",
        )
        assertThrows(IllegalArgumentException::class.java) {
            UnityUnifiedPersian.distributeFinalTerms(gathered, listOf("ONLY-ONE", "EXTRA"))
        }
    }
}
