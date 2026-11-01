package dev.cluvex.strata.core.unity

/**
 * Phase 4 core, part 4a (pure Kotlin, no Android deps): I2
 * placeholder ({directive}) validation for Persian Apply.
 *
 * Why a separate helper, not planImport? Persian Apply builds its
 * PUA terms via [UnityPersianApplyPipeline] (shaping + mapping +
 * baking), NOT via TSV import, so planImport's placeholder check
 * (which runs during TSV planning, skipping mismatched cells) does
 * not run for in-app-edited Persian terms. Without this check, a
 * Persian term missing (or adding) a placeholder (e.g. {0},
 * {player}) would be applied anyway, and the game would substitute
 * into the wrong place (or lose the value) on device, with no clear
 * cause. This helper blocks those terms BEFORE baking (saving atlas
 * space for terms that will actually be applied) and reports them
 * per term, exactly like planImport does for TSV cells.
 *
 * Rules (mirroring UnityLocalizationTsv.planImport):
 * - Directives are `{...}` with no nested braces and no CR/LF
 *   inside, at least one character: Regex `\{[^{}\r\n]+\}`. This
 *   duplicates UnityLocalizationTsv.DIRECTIVE (private there);
 *   keep the two in sync if either changes.
 * - Comparison is by MULTISET (counts, not order): the same
 *   placeholders, same counts, in any order, pass. Order in the
 *   term is the translator's (and the pipeline's Bidi) business,
 *   not the placeholder check's.
 * - Expected directives come from the ORIGINAL source-language
 *   term (e.g. English), not from the target language's old value:
 *   placeholders are game logic, language-independent, and the
 *   source term is the reference, as in planImport (which uses
 *   sourceIndex, not targetIndex, for expected).
 * - A term with no source term (unknown key, e.g. a term added in
 *   the editor but not in the table) has no expected placeholders
 *   to compare against: it is reported as skipped (unknown key),
 *   not silently applied, since Apply cannot place it in the table
 *   anyway (planImport would report UNKNOWN_KEY for it).
 *
 * Result: [ValidationResult.validTerms] are the terms to feed to
 * the pipeline (in input order, with original keys); [issues] are
 * human-readable per-term problems (for the Persian section UI);
 * [skippedKeys] are the keys that will keep their old target values
 * (not applied), so the caller can tell the user what was skipped
 * and why, instead of silently dropping terms.
 */
object UnityPersianPlaceholders {

    private val DIRECTIVE = Regex("\\{[^{}\r\n]+\\}")

    /** One Persian term, as in the pipeline's input. */
    data class TermInput(
        val termKey: String,
        val persianText: String,
    )

    data class ValidationResult(
        val validTerms: List<TermInput>,
        val issues: List<String>,
        val skippedKeys: List<String>,
    )

    /** All `{...}` spans in [value], in order of appearance. */
    fun directives(value: String): List<String> =
        DIRECTIVE.findAll(value).map { it.value }.toList()

    /** Multiset equality (same placeholders, same counts, any order). */
    fun sameDirectiveMultiset(left: List<String>, right: List<String>): Boolean =
        left.groupingBy { it }.eachCount() == right.groupingBy { it }.eachCount()

    /**
     * Validate [persianTerms] against [expectedDirectivesByKey]
     * (termKey -> directives in the original source-language term).
     * Terms whose key is missing from [expectedDirectivesByKey] are
     * skipped as unknown keys (see class doc); terms whose
     * placeholder multiset differs are skipped as mismatches.
     * All other terms pass, in input order.
     */
    fun validateTerms(
        persianTerms: List<TermInput>,
        expectedDirectivesByKey: Map<String, List<String>>,
    ): ValidationResult {
        val valid = ArrayList<TermInput>()
        val issues = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (term in persianTerms) {
            val expected = expectedDirectivesByKey[term.termKey]
            if (expected == null) {
                issues.add(
                    "Term ${term.termKey}: unknown key (not in the original table); skipped, old value kept",
                )
                skipped.add(term.termKey)
                continue
            }
            val actual = directives(term.persianText)
            if (!sameDirectiveMultiset(expected, actual)) {
                fun show(list: List<String>): String =
                    if (list.isEmpty()) "(none)" else list.sorted().joinToString(", ")
                issues.add(
                    "Term ${term.termKey}: placeholder mismatch, expected [${show(expected)}], got [${show(actual)}]; skipped, old value kept",
                )
                skipped.add(term.termKey)
                continue
            }
            valid.add(term)
        }
        return ValidationResult(valid, issues, skipped)
    }
}
