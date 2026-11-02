package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnityLocalizationJsonExportTest {

    private fun source(
        name: String,
        entryName: String,
        pathId: Long,
        terms: List<Pair<String, List<String?>>>,
        codes: List<String>,
    ): LocalizationSource {
        val languages = codes.map { LocalizationLanguage(it, it, 0) }
        val ts = terms.map { (key, values) -> LocalizationTerm.aligned(key, values, codes.size) }
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
        return LocalizationSource.create(name, languages, ts, origin)
    }

    @Test
    fun toJsonEmitsFlatShapeWithLanguageMap() {
        val codes = listOf("en", "fr", "it")
        val table = source(
            "I2Menu", "entry-a", 1L,
            listOf("K1" to listOf("Hello", "Bonjour", "Ciao")),
            codes,
        )
        val json = UnityLocalizationJson.toJson(table)
        assertThat(json).contains("\"m_Name\": \"I2Menu\"")
        assertThat(json).contains("\"#languages\": [\"en\", \"fr\", \"it\"]")
        assertThat(json).contains("\"mSource.mTerms[0].Term\": \"K1\"")
        assertThat(json).contains("\"mSource.mTerms[0].Languages[1]\": \"Bonjour\"")
    }

    @Test
    fun toJsonEscapesWithoutUnicodeEscapingPersian() {
        val codes = listOf("en", "fr")
        val table = source(
            "I2Menu", "entry-a", 1L,
            listOf("K1" to listOf("Hello \"x\"\nline2\ttab", "سلام فارسی")),
            codes,
        )
        val json = UnityLocalizationJson.toJson(table)
        assertThat(json).contains("سلام فارسی")
        assertThat(json).doesNotContain("\\u0633")
        assertThat(json).contains("Hello \\\"x\\\"\\nline2\\ttab")
    }

    @Test
    fun toJsonRoundTripsThroughImportReader() {
        val codes = listOf("en", "fr", "it")
        val table = source(
            "I2Menu", "entry-a", 1L,
            listOf(
                "K1" to listOf("One", "Un", null),
                "K2" to listOf("Two", "", "Due"),
            ),
            codes,
        )
        val tsv = UnityLocalizationJson.toTsv(table, UnityLocalizationJson.toJson(table))
        val lines = tsv.split("\n")
        assertThat(lines[0]).isEqualTo("key\ten\tfr\tit")
        assertThat(lines[1]).isEqualTo("K1\tOne\tUn\t")
        assertThat(lines[2]).isEqualTo("K2\tTwo\t\tDue")
    }

    @Test
    fun toJsonAllJoinsTablesWithBlankLine() {
        val codes = listOf("en", "fr")
        val a = source("I2Menu", "entry-a", 1L, listOf("K1" to listOf("One", "Un")), codes)
        val b = source("I2Sub", "entry-b", 2L, listOf("S1" to listOf("Two", "Deux")), codes)
        val all = UnityLocalizationJson.toJsonAll(mapOf("entry-a" to listOf(a), "entry-b" to listOf(b)))
        assertThat(all).contains("\"m_Name\": \"I2Menu\"")
        assertThat(all).contains("\"m_Name\": \"I2Sub\"")
        assertThat(all).contains("\n\n")
    }
}
