package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnityLocalizationJsonImportTest {

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
    fun toJsonForLanguageEmitsOnlyThatLanguage() {
        val codes = listOf("en", "fr", "it")
        val table = source(
            "I2Menu", "entry-a", 1L,
            listOf("K1" to listOf("Hello", "Bonjour", "Ciao")),
            codes,
        )
        val json = UnityLocalizationJson.toJsonForLanguage(table, "fr")
        assertThat(json).contains("\"mSource.mTerms[0].Languages[1]\": \"Bonjour\"")
        assertThat(json).doesNotContain("Languages[0]")
        assertThat(json).doesNotContain("Languages[2]")
        assertThat(json).contains("\"#languages\": [\"en\", \"fr\", \"it\"]")
        assertThat(UnityLocalizationJson.toJsonForLanguage(table, "de")).isNull()
    }

    @Test
    fun toJsonAllWithLanguageSkipsTablesWithoutIt() {
        val codes = listOf("en", "fr")
        val withFr = source("I2Menu", "entry-a", 1L, listOf("K1" to listOf("Hello", "Bonjour")), codes)
        val withoutFr = source("I2OnlyEn", "entry-b", 2L, listOf("K1" to listOf("Hello")), listOf("en"))
        val all = UnityLocalizationJson.toJsonAll(
            mapOf("entry-a" to listOf(withFr), "entry-b" to listOf(withoutFr)),
            "fr",
        )
        assertThat(all).contains("\"m_Name\": \"I2Menu\"")
        assertThat(all).doesNotContain("I2OnlyEn")
    }

    @Test
    fun parseAllReadsMultipleObjects() {
        val codes = listOf("en", "fr")
        val a = source("I2Menu", "entry-a", 1L, listOf("K1" to listOf("Hello", "Bonjour")), codes)
        val b = source("I2Sub", "entry-b", 2L, listOf("S1" to listOf("One", "Un")), codes)
        val file = UnityLocalizationJson.toJsonAll(mapOf("entry-a" to listOf(a), "entry-b" to listOf(b)), "fr")
        val parsed = UnityLocalizationJson.parsedTables(UnityLocalizationJson.parseAll(file))
        assertThat(parsed).hasSize(2)
        assertThat(parsed[0].name).isEqualTo("I2Menu")
        assertThat(parsed[0].languageCodes).containsExactly("en", "fr")
        assertThat(parsed[0].terms[0].valuesByIndex).containsExactly(1, "Bonjour")
    }

    @Test
    fun planImportAllReplacesOnlyItsLanguage() {
        val codes = listOf("en", "fr", "it")
        val menu = source(
            "I2Menu", "entry-a", 1L,
            listOf("K1" to listOf("Hello", "Bonjour", "Ciao")),
            codes,
        )
        val file = UnityLocalizationJson.toJsonForLanguage(menu, "fr")!!
            .replace("Bonjour", "Bonjour EDIT")
        val result = UnityLocalizationJson.planImportAll(
            sourcesByEntry = mapOf("entry-a" to listOf(menu)),
            parsed = UnityLocalizationJson.parsedTables(UnityLocalizationJson.parseAll(file)),
            sourceCode = "en",
        )
        assertThat(result.plans).hasSize(1)
        val updated = result.plans[0].plan.updated!!
        val term = updated.terms.first { it.key == "K1" }
        assertThat(term.values[1]).isEqualTo("Bonjour EDIT")
        assertThat(term.values[0]).isEqualTo("Hello")
        assertThat(term.values[2]).isEqualTo("Ciao")
        assertThat(result.unknownTables).isEmpty()
        assertThat(result.unknownLanguages).isEmpty()
    }

    @Test
    fun planImportAllReportsUnknownsAndPlaceholderSkips() {
        val codes = listOf("en", "fr")
        val menu = source(
            "I2Menu", "entry-a", 1L,
            listOf("K1" to listOf("Hello", "Bonjour")),
            codes,
        )
        val file = """
            {
              "m_Name": "I2Nope",
              "#languages": ["en", "fr"],
              "mSource.mTerms[0].Term": "X1",
              "mSource.mTerms[0].Languages[1]": "Y"
            }
            {
              "m_Name": "I2Menu",
              "#languages": ["en", "xx"],
              "mSource.mTerms[0].Term": "K1",
              "mSource.mTerms[0].Languages[1]": "Zz"
            }
            {
              "m_Name": "I2Menu",
              "#languages": ["en", "fr"],
              "mSource.mTerms[0].Term": "K1",
              "mSource.mTerms[0].Languages[1]": "Keep {x} here"
            }
        """.trimIndent()
        val result = UnityLocalizationJson.planImportAll(
            sourcesByEntry = mapOf("entry-a" to listOf(menu)),
            parsed = UnityLocalizationJson.parsedTables(UnityLocalizationJson.parseAll(file)),
            sourceCode = "en",
        )
        assertThat(result.unknownTables).containsExactly("I2Nope")
        assertThat(result.unknownLanguages).containsExactly("I2Menu:xx")
        assertThat(result.plans).isEmpty()
        assertThat(result.placeholderSkippedTerms).isEqualTo(1)
    }
}
