package dev.cluvex.strata.core.unity

/**
 * Unified Persian Apply: one bake for every word in every table.
 *
 * Gathering: for each table that has the target language, the
 * effective term text is the unified edit for the key if present,
 * else the table's current target value. Each table's terms are
 * placeholder-validated against that table's own source-language
 * values (same rule as planImport); failing terms are excluded
 * with an issue, never silently baked, and never block the other
 * tables. The surviving terms of all tables are concatenated into
 * ONE pipeline input list, so the pipeline's single
 * [UnityPersianWordMapper.mapShapedTerms] batch assigns the SAME
 * word-PUA code to the same word everywhere (a shared word must
 * not get one code in the menu and another in the subtitles: the
 * baked atlas holds one glyph per code).
 *
 * Distribution is purely positional: [PipelineResult.finalTerms]
 * is order-aligned with the pipeline input, so final term i
 * belongs to [GatheredTerms.termRefs][i] regardless of key
 * collisions across tables (the same key may legitimately appear
 * in several tables with different values).
 *
 * This helper does no I/O and no Android calls; the caller does
 * file / bundle / I2 work. Pure Kotlin.
 */
object UnityUnifiedPersian {

    /** One pipeline input term plus where its final text belongs. */
    data class TermRef(
        val entryName: String,
        val tableId: String,
        val key: String,
    )

    // Internal: PersianTermInput is internal to the pipeline, so
    // anything exposing it (this holder, and both functions using
    // it) must be internal too. Same-module callers (ViewModel,
    // unit tests, VM scratch mains compiled with the sources) can
    // still use them; only cross-module exposure is barred.
    internal data class GatheredTerms(
        val pipelineTerms: List<UnityPersianApplyPipeline.PersianTermInput>,
        val termRefs: List<TermRef>,
        /** Table name -> placeholder issues (already prefixed for display). */
        val placeholderIssues: List<String>,
        /** Valid (placeholder-passing) logical texts, per table id, per key. */
        val validLogicalByTable: Map<String, Map<String, String>>,
    )

    /**
     * Gathers the pipeline input for all tables. [edits] is the
     * unified edit map (term key -> new target value); [targetCode]
     * is the language being baked; [sourceCode] is the placeholder
     * reference language (per table, falls back to the table's
     * first language when the table lacks it).
     */
    internal fun gatherTerms(
        sourcesByEntry: Map<String, List<LocalizationSource>>,
        edits: Map<String, String>,
        targetCode: String,
        sourceCode: String?,
    ): GatheredTerms {
        val pipelineTerms = ArrayList<UnityPersianApplyPipeline.PersianTermInput>()
        val termRefs = ArrayList<TermRef>()
        val placeholderIssues = ArrayList<String>()
        val validLogicalByTable = HashMap<String, Map<String, String>>()
        for ((entryName, tables) in sourcesByEntry) {
            for (table in tables) {
                val targetIndex = table.languageIndex(targetCode)
                if (targetIndex < 0) continue
                val tableSourceCode = sourceCode?.takeIf { table.languageIndex(it) >= 0 }
                    ?: table.languages.first().code
                val sourceIndex = table.languageIndex(tableSourceCode).coerceAtLeast(0)
                val expectedByKey = table.terms.associate { term ->
                    term.key to UnityPersianPlaceholders.directives(term.values[sourceIndex].orEmpty())
                }
                val termInputs = table.terms.map { term ->
                    val text = edits[term.key] ?: term.values[targetIndex].orEmpty()
                    UnityPersianPlaceholders.TermInput(term.key, text)
                }
                val result = UnityPersianPlaceholders.validateTerms(termInputs, expectedByKey)
                for (issue in result.issues) placeholderIssues += "${table.name}: $issue"
                val valid = result.validTerms
                if (valid.isEmpty()) continue
                validLogicalByTable[table.origin.tableId] =
                    valid.associate { it.termKey to it.persianText }
                for (term in valid) {
                    pipelineTerms += UnityPersianApplyPipeline.PersianTermInput(term.termKey, term.persianText)
                    termRefs += TermRef(entryName, table.origin.tableId, term.termKey)
                }
            }
        }
        return GatheredTerms(pipelineTerms, termRefs, placeholderIssues, validLogicalByTable)
    }

    /**
     * Distributes order-aligned pipeline output back to tables:
     * table id -> key -> final (PUA) text. [finalTerms] must be
     * the pipeline's finalTerms for [gathered]'s pipelineTerms,
     * in the same order.
     */
    internal fun distributeFinalTerms(
        gathered: GatheredTerms,
        finalTerms: List<String>,
    ): Map<String, Map<String, String>> {
        require(finalTerms.size == gathered.termRefs.size) {
            "Unified Persian: final terms (${finalTerms.size}) do not match gathered terms (${gathered.termRefs.size})"
        }
        val byTable = HashMap<String, MutableMap<String, String>>()
        for (i in gathered.termRefs.indices) {
            val ref = gathered.termRefs[i]
            byTable.getOrPut(ref.tableId) { HashMap() }[ref.key] = finalTerms[i]
        }
        return byTable
    }
}
