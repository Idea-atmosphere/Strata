package dev.cluvex.strata.core.unity

/**
 * Unified multi-table editing: one edit map (term key -> new target
 * value) applied to every localization table that has the key.
 *
 * The plan for each table is built by reusing
 * [UnityLocalizationTsv.planImport] on a TSV that contains only the
 * edited keys present in that table, so placeholder validation,
 * empty-cell handling and unknown-key handling are exactly the
 * import flow's (a placeholder mismatch skips that term, it never
 * blocks the other tables). A table with no edited key present, or
 * without the target language, gets no plan. Plans can therefore
 * never carry a blocking issue from construction: no metadata row,
 * one row per key, header always valid.
 *
 * Edits are written as-is (plain values). Persian shaping/mapping
 * to PUA is NOT done here; that stays with the Persian Apply
 * pipeline, which also owns the baked fallback the PUA text needs
 * to render. A unified apply of plain Persian text renders as
 * squares until that fallback exists, exactly like a plain
 * per-table import.
 *
 * Pure Kotlin, no Android dependency.
 */
data class UnifiedTablePlan(
    val entryName: String,
    val source: LocalizationSource,
    val plan: LocalizationImportPlan,
)

object UnityUnifiedLocalization {

    /**
     * Builds one plan per table that has the target language and at
     * least one edited key. [edits] is keyed by term key only, so a
     * key shared by several tables is edited in all of them at once.
     */
    fun buildPlans(
        sourcesByEntry: Map<String, List<LocalizationSource>>,
        edits: Map<String, String>,
        targetCode: String,
        sourceCode: String?,
        options: UnityTextTransformOptions = UnityTextTransformOptions(),
    ): List<UnifiedTablePlan> {
        if (edits.isEmpty()) return emptyList()
        val plans = ArrayList<UnifiedTablePlan>()
        for ((entryName, sources) in sourcesByEntry) {
            for (source in sources) {
                if (source.languageIndex(targetCode) < 0) continue
                val editedTerms = source.terms.filter { it.key in edits }
                if (editedTerms.isEmpty()) continue
                val tsv = buildString {
                    appendRow(listOf("key", targetCode))
                    for (term in editedTerms) {
                        appendRow(listOf(term.key, edits.getValue(term.key)))
                    }
                }
                plans += UnifiedTablePlan(
                    entryName = entryName,
                    source = source,
                    plan = UnityLocalizationTsv.planImport(source, tsv, options, sourceCode),
                )
            }
        }
        return plans
    }

    /** Counts, per term key, in how many tables the key appears (for shared-key badges). */
    fun sharedTableCounts(sources: List<LocalizationSource>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (source in sources) {
            for (term in source.terms) {
                if (term.key !in counts) {
                    counts[term.key] = sources.count { other -> other.terms.any { it.key == term.key } }
                }
            }
        }
        return counts
    }

    /** Same quoting rule as the TSV writer: quote on tab/CR/LF/quote, doubling quotes. */
    private fun tsvCell(value: String): String {
        if (value.none { it == '\t' || it == '\r' || it == '\n' || it == '"' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun StringBuilder.appendRow(cells: List<String>) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) append('\t')
            append(tsvCell(cell))
        }
        append('\n')
    }
}
