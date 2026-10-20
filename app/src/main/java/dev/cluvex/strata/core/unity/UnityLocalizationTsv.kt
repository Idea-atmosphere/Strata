package dev.cluvex.strata.core.unity

/** Metadata written ahead of the TSV header. */
data class LocalizationTsvMetadata(
    val version: Int = 1,
    val tableId: String,
    val sourceLanguageCode: String,
    val shapeArabic: Boolean,
    val reverseBidi: Boolean,
)

enum class LocalizationImportIssueKind {
    UNKNOWN_KEY,
    MISSING_KEY,
    EMPTY_CELL,
    UNKNOWN_LANGUAGE,
    DUPLICATE_KEY,
    DUPLICATE_LANGUAGE,
    MALFORMED_ROW,
    PLACEHOLDER_MISMATCH,
    METADATA_MISMATCH,
}

data class LocalizationImportIssue(
    val kind: LocalizationImportIssueKind,
    val key: String? = null,
    val languageCode: String? = null,
    val expectedDirectives: List<String> = emptyList(),
    val actualDirectives: List<String> = emptyList(),
    val row: Int? = null,
    val blocking: Boolean = false,
)

data class LocalizationCellChange(
    val key: String,
    val languageCode: String,
    val oldValue: String?,
    val newValue: String,
)

data class LocalizationImportPlan(
    val original: LocalizationSource,
    val updated: LocalizationSource?,
    val metadata: LocalizationTsvMetadata?,
    val changes: List<LocalizationCellChange>,
    val issues: List<LocalizationImportIssue>,
    val estimatedDataDeltaBytes: Long,
) {
    val canApply: Boolean get() = updated != null && changes.isNotEmpty() && issues.none { it.blocking }
    val unknownKeyCount: Int get() = issues.count { it.kind == LocalizationImportIssueKind.UNKNOWN_KEY }
    val missingKeyCount: Int get() = issues.count { it.kind == LocalizationImportIssueKind.MISSING_KEY }
    val emptyCellCount: Int get() = issues.count { it.kind == LocalizationImportIssueKind.EMPTY_CELL }
    val placeholderFailureCount: Int get() = issues.count { it.kind == LocalizationImportIssueKind.PLACEHOLDER_MISMATCH }
}

/** Correct tab-separated codec with quoted tab/newline/quote handling and explicit-empty semantics. */
object UnityLocalizationTsv {
    private const val METADATA_MARKER = "#@strata-localization"
    private const val FORMAT_VERSION = 1
    private val DIRECTIVE = Regex("\\{[^{}\\r\\n]+}")

    fun exportAll(
        source: LocalizationSource,
        transformOptions: UnityTextTransformOptions = UnityTextTransformOptions(),
        sourceLanguageCode: String = source.languages.first().code,
    ): String = buildString {
        exportAllTo(this, source, transformOptions, sourceLanguageCode)
    }

    fun exportLanguage(
        source: LocalizationSource,
        languageCode: String,
        transformOptions: UnityTextTransformOptions = UnityTextTransformOptions(),
        sourceLanguageCode: String = source.languages.first().code,
    ): String = buildString {
        exportLanguageTo(this, source, languageCode, transformOptions, sourceLanguageCode)
    }

    /** Streams a complete table without first materializing a second full TSV string in memory. */
    fun exportAllTo(
        output: Appendable,
        source: LocalizationSource,
        transformOptions: UnityTextTransformOptions = UnityTextTransformOptions(),
        sourceLanguageCode: String = source.languages.first().code,
    ) {
        write(output, source, source.languages.map { it.code }, transformOptions, sourceLanguageCode)
    }

    /** Streams one language and keeps the same metadata and escaping contract as [exportLanguage]. */
    fun exportLanguageTo(
        output: Appendable,
        source: LocalizationSource,
        languageCode: String,
        transformOptions: UnityTextTransformOptions = UnityTextTransformOptions(),
        sourceLanguageCode: String = source.languages.first().code,
    ) {
        require(source.languageIndex(languageCode) >= 0) { "Unknown localization language '$languageCode'" }
        write(output, source, listOf(languageCode), transformOptions, sourceLanguageCode)
    }

    private fun write(
        output: Appendable,
        source: LocalizationSource,
        languageCodes: List<String>,
        transformOptions: UnityTextTransformOptions,
        sourceLanguageCode: String,
    ) {
        require(source.languageIndex(sourceLanguageCode) >= 0) { "Unknown source language '$sourceLanguageCode'" }
        val indexes = languageCodes.map { code ->
            source.languageIndex(code).takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Unknown localization language '$code'")
        }
        appendRow(
            output,
            listOf(
                METADATA_MARKER,
                "version=$FORMAT_VERSION",
                "table=${source.origin.tableId}",
                "source=$sourceLanguageCode",
                "shapeArabic=${transformOptions.shapeArabic}",
                "reverseBidi=${transformOptions.reverseBidi}",
            ).map { TsvOutCell(it, false) },
        )
        appendRow(output, (listOf("key") + languageCodes).map { TsvOutCell(it, false) })
        source.terms.forEach { term ->
            ensureUnityOperationActive()
            val cells = ArrayList<TsvOutCell>(indexes.size + 1)
            cells += TsvOutCell(term.key, false)
            indexes.forEach { index ->
                val value = term.values[index]
                cells += TsvOutCell(value, explicitEmpty = value != null && value.isEmpty())
            }
            appendRow(output, cells)
        }
    }

    fun planImport(
        source: LocalizationSource,
        tsv: String,
        transformOptions: UnityTextTransformOptions = UnityTextTransformOptions(),
        sourceLanguageCode: String? = null,
    ): LocalizationImportPlan {
        val parsed = try {
            parseRows(tsv)
        } catch (_: IllegalArgumentException) {
            return malformedPlan(source, 1)
        }
        if (parsed.isEmpty()) return malformedPlan(source, 1)
        var rowIndex = 0
        val hasMetadataRow = parsed.first().firstOrNull()?.value == METADATA_MARKER
        val metadata = if (hasMetadataRow) {
            rowIndex++
            parseMetadata(parsed.first())
        } else {
            null
        }
        if (hasMetadataRow && metadata == null) return malformedPlan(source, 1)
        if (rowIndex >= parsed.size) return malformedPlan(source, rowIndex + 1, metadata)
        val header = parsed[rowIndex++]
        if (header.size < 2 || header[0].value != "key") return malformedPlan(source, rowIndex, metadata)

        val issues = ArrayList<LocalizationImportIssue>()
        val languageCodes = header.drop(1).map { it.value }
        languageCodes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { code ->
            issues += LocalizationImportIssue(
                LocalizationImportIssueKind.DUPLICATE_LANGUAGE,
                languageCode = code,
                row = rowIndex,
                blocking = true,
            )
        }
        val languageIndexes = languageCodes.map { code ->
            val index = source.languageIndex(code)
            if (index < 0) issues += LocalizationImportIssue(LocalizationImportIssueKind.UNKNOWN_LANGUAGE, languageCode = code)
            index
        }
        if (metadata != null && (
                metadata.version != FORMAT_VERSION ||
                    metadata.tableId != source.origin.tableId ||
                    source.languageIndex(metadata.sourceLanguageCode) < 0 ||
                    metadata.shapeArabic != transformOptions.shapeArabic ||
                    metadata.reverseBidi != transformOptions.reverseBidi
                )
        ) {
            issues += LocalizationImportIssue(
                kind = LocalizationImportIssueKind.METADATA_MISMATCH,
                blocking = true,
            )
        }

        val effectiveSourceCode = sourceLanguageCode
            ?: metadata?.sourceLanguageCode?.takeIf { source.languageIndex(it) >= 0 }
            ?: source.languages.first().code
        val sourceIndex = source.languageIndex(effectiveSourceCode).coerceAtLeast(0)
        val sourceByKey = source.terms.associateBy { it.key }
        val rowsByKey = LinkedHashMap<String, List<TsvCell>>()
        while (rowIndex < parsed.size) {
            ensureUnityOperationActive()
            val rowNumber = rowIndex + 1
            val row = parsed[rowIndex++]
            if (row.size == 1 && row[0].value.isEmpty() && !row[0].quoted) continue
            if (row.size != header.size) {
                issues += LocalizationImportIssue(
                    LocalizationImportIssueKind.MALFORMED_ROW,
                    key = row.firstOrNull()?.value,
                    row = rowNumber,
                    blocking = true,
                )
                continue
            }
            val key = row[0].value
            if (key.isBlank()) {
                issues += LocalizationImportIssue(LocalizationImportIssueKind.MALFORMED_ROW, row = rowNumber, blocking = true)
                continue
            }
            if (rowsByKey.putIfAbsent(key, row) != null) {
                issues += LocalizationImportIssue(
                    LocalizationImportIssueKind.DUPLICATE_KEY,
                    key = key,
                    row = rowNumber,
                    blocking = true,
                )
            }
            if (key !in sourceByKey) {
                issues += LocalizationImportIssue(LocalizationImportIssueKind.UNKNOWN_KEY, key = key, row = rowNumber)
            }
        }
        source.terms.forEach { term ->
            if (term.key !in rowsByKey) issues += LocalizationImportIssue(LocalizationImportIssueKind.MISSING_KEY, key = term.key)
        }
        if (issues.any { it.blocking }) {
            return LocalizationImportPlan(source, null, metadata, emptyList(), issues, 0)
        }

        val changes = ArrayList<LocalizationCellChange>()
        val updatedValues = LinkedHashMap<String, List<String?>>()
        source.terms.forEach termLoop@{ term ->
            ensureUnityOperationActive()
            val row = rowsByKey[term.key] ?: return@termLoop
            val values = term.values.toMutableList()
            languageCodes.indices.forEach columnLoop@{ column ->
                val languageIndex = languageIndexes[column]
                if (languageIndex < 0) return@columnLoop
                val cell = row[column + 1]
                if (cell.value.isEmpty()) {
                    issues += LocalizationImportIssue(
                        LocalizationImportIssueKind.EMPTY_CELL,
                        key = term.key,
                        languageCode = languageCodes[column],
                    )
                    return@columnLoop
                }
                val oldValue = values[languageIndex]
                if (oldValue != null && cell.value == oldValue) return@columnLoop
                val expected = directives(term.values[sourceIndex].orEmpty())
                val actual = directives(cell.value)
                if (!sameDirectiveMultiset(expected, actual)) {
                    issues += LocalizationImportIssue(
                        kind = LocalizationImportIssueKind.PLACEHOLDER_MISMATCH,
                        key = term.key,
                        languageCode = languageCodes[column],
                        expectedDirectives = expected,
                        actualDirectives = actual,
                    )
                    return@columnLoop
                }
                val transformed = transformPreservingDirectives(cell.value, transformOptions)
                values[languageIndex] = transformed
                changes += LocalizationCellChange(term.key, languageCodes[column], oldValue, transformed)
            }
            updatedValues[term.key] = values
        }
        val updated = source.withValues(updatedValues)
        val delta = changes.sumOf { change ->
            change.newValue.toByteArray(Charsets.UTF_8).size.toLong() -
                (change.oldValue?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
        }
        return LocalizationImportPlan(source, updated, metadata, changes, issues, delta)
    }

    private fun malformedPlan(
        source: LocalizationSource,
        row: Int,
        metadata: LocalizationTsvMetadata? = null,
    ) = LocalizationImportPlan(
        original = source,
        updated = null,
        metadata = metadata,
        changes = emptyList(),
        issues = listOf(LocalizationImportIssue(LocalizationImportIssueKind.MALFORMED_ROW, row = row, blocking = true)),
        estimatedDataDeltaBytes = 0,
    )

    private fun parseMetadata(row: List<TsvCell>): LocalizationTsvMetadata? {
        val values = row.drop(1).mapNotNull { cell ->
            val split = cell.value.indexOf('=')
            if (split <= 0) null else cell.value.substring(0, split) to cell.value.substring(split + 1)
        }.toMap()
        return LocalizationTsvMetadata(
            version = values["version"]?.toIntOrNull() ?: return null,
            tableId = values["table"] ?: return null,
            sourceLanguageCode = values["source"] ?: return null,
            shapeArabic = values["shapeArabic"]?.toBooleanStrictOrNull() ?: false,
            reverseBidi = values["reverseBidi"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun directives(value: String): List<String> = DIRECTIVE.findAll(value).map { it.value }.toList()

    private fun sameDirectiveMultiset(left: List<String>, right: List<String>): Boolean =
        left.groupingBy { it }.eachCount() == right.groupingBy { it }.eachCount()

    private fun transformPreservingDirectives(text: String, options: UnityTextTransformOptions): String {
        if (!options.isEnabled) return text
        val output = StringBuilder(text.length)
        var cursor = 0
        DIRECTIVE.findAll(text).forEach { match ->
            output.append(UnityTextShaper.transform(text.substring(cursor, match.range.first), options))
            output.append(match.value)
            cursor = match.range.last + 1
        }
        output.append(UnityTextShaper.transform(text.substring(cursor), options))
        return output.toString()
    }

    private data class TsvOutCell(val value: String?, val explicitEmpty: Boolean)
    private data class TsvCell(val value: String, val quoted: Boolean)

    private fun appendRow(output: Appendable, cells: List<TsvOutCell>) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) output.append('\t')
            val value = cell.value ?: return@forEachIndexed
            val quote = cell.explicitEmpty || value.any { it == '\t' || it == '\r' || it == '\n' || it == '"' }
            if (!quote) {
                output.append(value)
            } else {
                output.append('"')
                value.forEach { character -> if (character == '"') output.append("\"\"") else output.append(character) }
                output.append('"')
            }
        }
        output.append('\n')
    }

    private fun parseRows(rawText: String): List<List<TsvCell>> {
        val text = rawText.removePrefix("\uFEFF")
        val rows = ArrayList<List<TsvCell>>()
        var row = ArrayList<TsvCell>()
        val field = StringBuilder()
        var quoted = false
        var fieldWasQuoted = false
        var quoteClosed = false
        var atFieldStart = true
        var index = 0

        fun finishField() {
            row += TsvCell(field.toString(), fieldWasQuoted)
            field.setLength(0)
            fieldWasQuoted = false
            quoteClosed = false
            atFieldStart = true
        }
        fun finishRow() {
            finishField()
            rows += row
            row = ArrayList()
        }

        while (index < text.length) {
            if ((index and 0x3FFF) == 0) ensureUnityOperationActive()
            val character = text[index]
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index += 2
                    } else {
                        quoted = false
                        quoteClosed = true
                        index++
                    }
                } else {
                    field.append(character)
                    index++
                }
                continue
            }
            if (quoteClosed && character != '\t' && character != '\r' && character != '\n') {
                throw IllegalArgumentException("Characters after closing quote in TSV field")
            }
            when (character) {
                '"' -> {
                    if (!atFieldStart) throw IllegalArgumentException("Quote inside unquoted TSV field")
                    quoted = true
                    fieldWasQuoted = true
                    atFieldStart = false
                    index++
                }
                '\t' -> {
                    finishField()
                    index++
                }
                '\r', '\n' -> {
                    if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    finishRow()
                    index++
                }
                else -> {
                    field.append(character)
                    atFieldStart = false
                    index++
                }
            }
        }
        if (quoted) throw IllegalArgumentException("TSV ends inside a quoted field")
        if (field.isNotEmpty() || fieldWasQuoted || row.isNotEmpty()) finishRow()
        return rows
    }
}
