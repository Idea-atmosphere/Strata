package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class UnityLocalizationJsonTest {

    private fun source(
        keys: List<String>,
        valuesByCode: Map<String, String>,
    ): LocalizationSource {
        val codes = listOf("en", "fr", "it")
        val languages = codes.map { LocalizationLanguage(it, it, 0) }
        val terms = keys.map { key ->
            LocalizationTerm.aligned(key, codes.map { valuesByCode[it] ?: "X" }, codes.size)
        }
        return sourceWithTerms(terms, languages)
    }

    private fun sourceWithTerms(
        terms: List<LocalizationTerm>,
        languages: List<LocalizationLanguage>,
    ): LocalizationSource {
        val origin = LocalizationOrigin(
            entryName = "test-entry",
            partIndex = 0,
            pathId = 1L,
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
        return LocalizationSource.create("I2Test", languages, terms, origin)
    }

    @Test
    fun convertsFlatJsonToTsvInTermOrder() {
        val json = """{
            "m_Name": "I2Test",
            "mSource.mTerms[1].Term": "K2",
            "mSource.mTerms[1].Languages[1]": "deux",
            "mSource.mTerms[0].Term": "K1",
            "mSource.mTerms[0].Languages[0]": "one",
            "mSource.mTerms[0].Languages[1]": "un"
        }"""
        val tsv = UnityLocalizationJson.toTsv(source(emptyList(), emptyMap()), json)
        assertThat(tsv).isEqualTo("key\ten\tfr\tit\nK1\tone\tun\t\nK2\t\tdeux\t\n")
    }

    @Test
    fun quotesTabNewlineAndQuoteCells() {
        val json = """{
            "mSource.mTerms[0].Term": "K1",
            "mSource.mTerms[0].Languages[1]": "a\tb\"c",
            "mSource.mTerms[1].Term": "K2",
            "mSource.mTerms[1].Languages[1]": "x\ny"
        }"""
        val tsv = UnityLocalizationJson.toTsv(source(emptyList(), emptyMap()), json)
        val plan = UnityLocalizationTsv.planImport(
            source(listOf("K1", "K2"), mapOf("fr" to "OLD")),
            tsv,
            UnityTextTransformOptions(),
            "en",
        )
        assertThat(plan.canApply).isTrue()
        assertThat(plan.changes).hasSize(2)
        assertThat(plan.changes.first { it.key == "K1" }.newValue).isEqualTo("a\tb\"c")
        assertThat(plan.changes.first { it.key == "K2" }.newValue).isEqualTo("x\ny")
    }

    @Test
    fun coercesNonStringScalarsAndDropsOutOfRangeLanguages() {
        val json = """{
            "mSource.mTerms[0].Term": "K1",
            "mSource.mTerms[0].Languages[1]": 42,
            "mSource.mTerms[0].Languages[5]": "dropped",
            "mSource.mTerms[0].Languages[2]": true,
            "mSource.mTerms[0].Languages[0]": null
        }"""
        val tsv = UnityLocalizationJson.toTsv(source(emptyList(), emptyMap()), json)
        assertThat(tsv).isEqualTo("key\ten\tfr\tit\nK1\t\t42\ttrue\n")
    }

    @Test
    fun convertedTsvImportsThroughTheSharedPlan() {
        val json = """{
            "mSource.mTerms[0].Term": "K1",
            "mSource.mTerms[0].Languages[0]": "one",
            "mSource.mTerms[0].Languages[1]": "un",
            "mSource.mTerms[1].Term": "K2",
            "mSource.mTerms[1].Languages[0]": "two",
            "mSource.mTerms[1].Languages[1]": "deux"
        }"""
        val languages = listOf("en", "fr", "it").map { LocalizationLanguage(it, it, 0) }
        val src = sourceWithTerms(
            listOf(
                LocalizationTerm.aligned("K1", listOf("one", "OLD", "X"), 3),
                LocalizationTerm.aligned("K2", listOf("two", "OLD", "X"), 3),
            ),
            languages,
        )
        val tsv = UnityLocalizationJson.toTsv(src, json)
        val plan = UnityLocalizationTsv.planImport(src, tsv, UnityTextTransformOptions(), "en")
        assertThat(plan.canApply).isTrue()
        assertThat(plan.issues.none { it.blocking }).isTrue()
        assertThat(plan.changes).hasSize(2)
        assertThat(plan.changes.map { it.key }).containsExactly("K1", "K2")
        assertThat(plan.changes.map { it.languageCode }).containsExactly("fr", "fr")
    }

    @Test
    fun malformedJsonIsRejected() {
        val src = source(emptyList(), emptyMap())
        assertThrows(IllegalArgumentException::class.java) {
            UnityLocalizationJson.toTsv(src, "{not json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnityLocalizationJson.toTsv(src, "[1, 2]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnityLocalizationJson.toTsv(src, """{"m_Name": "I2Test"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnityLocalizationJson.toTsv(src, """{"mSource": {"mTerms": []}}""")
        }
    }

    @Test
    fun parserHandlesUnicodeEscapesAndSurrogatePairs() {
        val parsed = UnityLocalizationJson.parse("""{"a": "caf\u00e9 \uD83D\uDE00"}""")
        val obj = parsed as UnityLocalizationJson.JsonValue.Obj
        assertThat((obj.entries.getValue("a") as UnityLocalizationJson.JsonValue.Str).value)
            .isEqualTo("café 😀")
    }
}
