package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnityPersianPlaceholdersTest {

    @Test
    fun directivesExtractsPlaceholdersInOrder() {
        assertThat(UnityPersianPlaceholders.directives("سلام {player} و {0}"))
            .containsExactly("{player}", "{0}").inOrder()
        assertThat(UnityPersianPlaceholders.directives("سلام خداحافظ")).isEmpty()
        assertThat(UnityPersianPlaceholders.directives("{player.name} و {a_b-c}"))
            .containsExactly("{player.name}", "{a_b-c}").inOrder()
    }

    @Test
    fun directivesIgnoresEmptyBracesAndNewlineSpans() {
        assertThat(UnityPersianPlaceholders.directives("{}")).isEmpty()
        assertThat(UnityPersianPlaceholders.directives("{a\nb}")).isEmpty()
    }

    @Test
    fun sameDirectiveMultisetIsOrderIndependentButCountSensitive() {
        assertThat(
            UnityPersianPlaceholders.sameDirectiveMultiset(
                listOf("{0}", "{player}"),
                listOf("{player}", "{0}"),
            ),
        ).isTrue()
        assertThat(
            UnityPersianPlaceholders.sameDirectiveMultiset(
                listOf("{0}"),
                listOf("{0}", "{0}"),
            ),
        ).isFalse()
        assertThat(
            UnityPersianPlaceholders.sameDirectiveMultiset(
                listOf("{0}"),
                listOf("{1}"),
            ),
        ).isFalse()
    }

    @Test
    fun validateTermsKeepsOnlyMatchingTerms() {
        val terms = listOf(
            UnityPersianPlaceholders.TermInput("A", "سلام {player}"),
            UnityPersianPlaceholders.TermInput("B", "سلام {player} {0}"),
            UnityPersianPlaceholders.TermInput("C", "سلام"),
            UnityPersianPlaceholders.TermInput("D", "سلام {0}"),
            UnityPersianPlaceholders.TermInput("E", "سلام {player}"),
        )
        val expected = mapOf(
            "A" to listOf("{player}"),
            "B" to listOf("{0}", "{player}"),
            "C" to listOf("{0}"),
            "D" to listOf("{player}"),
        )
        val result = UnityPersianPlaceholders.validateTerms(terms, expected)
        assertThat(result.validTerms.map { it.termKey }).containsExactly("A", "B").inOrder()
        assertThat(result.skippedKeys).containsExactly("C", "D", "E").inOrder()
        assertThat(result.issues).hasSize(3)
        assertThat(result.issues[2]).contains("unknown")
    }

    @Test
    fun validateTermsEmptyExpectedRequiresEmptyActual() {
        val passing = UnityPersianPlaceholders.validateTerms(
            listOf(UnityPersianPlaceholders.TermInput("X", "سلام")),
            mapOf("X" to emptyList()),
        )
        assertThat(passing.validTerms).hasSize(1)
        assertThat(passing.issues).isEmpty()
        val failing = UnityPersianPlaceholders.validateTerms(
            listOf(UnityPersianPlaceholders.TermInput("X", "سلام {0}")),
            mapOf("X" to emptyList()),
        )
        assertThat(failing.validTerms).isEmpty()
        assertThat(failing.skippedKeys).containsExactly("X")
    }
}
