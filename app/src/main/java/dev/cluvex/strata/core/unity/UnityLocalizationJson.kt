package dev.cluvex.strata.core.unity

/**
 * Reads the flat I2 JSON shape (the shape Strata's own deliveries and
 * the game's JSON exports use) into TSV text that
 * [UnityLocalizationTsv.planImport] accepts, so JSON files import
 * through exactly the same validation as TSV files: placeholder
 * multisets, duplicate keys, unknown languages/keys and malformed
 * rows are all reported by the shared plan, never by a second
 * implementation.
 *
 * Accepted shape: a JSON object whose entries are
 * `mSource.mTerms[N].Term` (the term key) and
 * `mSource.mTerms[N].Languages[i]` (the value for the i-th language
 * of the selected table, in the table's own language order).
 * `m_Name` and any other entry are ignored. A language index beyond
 * the selected table's languages is dropped; a term without a key is
 * skipped; a missing language value becomes an empty cell (which the
 * plan skips for that term, as a non-blocking empty-cell issue).
 *
 * This is intentionally NOT a general I2 JSON reader: nested shapes
 * (for example `{"mSource": {"mTerms": [...]}}`) are rejected with a
 * clear error instead of being guessed at.
 *
 * Pure Kotlin, no Android dependency, so the VM scratch harness can
 * exercise it exactly like the repo tests do.
 */
object UnityLocalizationJson {

    /** Minimal JSON value model, sufficient for flat localization files. */
    sealed interface JsonValue {
        data class Obj(val entries: Map<String, JsonValue>) : JsonValue
        data class Arr(val items: List<JsonValue>) : JsonValue
        data class Str(val value: String) : JsonValue
        data class Num(val raw: String) : JsonValue
        data class Bool(val value: Boolean) : JsonValue
        object Null : JsonValue
    }

    /** Parses a JSON document. Throws [IllegalArgumentException] on malformed input. */
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        return parser.parseDocument()
    }

    /**
     * Parses a file holding one or more flat-I2 JSON objects (the
     * shape [toJsonAll] writes: objects separated by whitespace, a
     * blank line in practice). A leading BOM is tolerated. AI
     * replies are tolerated too: a surrounding Markdown code
     * fence is stripped, and a top-level array of objects is
     * accepted as well. Throws [IllegalArgumentException] on
     * malformed input or a non-object value.
     */
    fun parseAll(text: String): List<JsonValue.Obj> {
        var cleaned = text.removePrefix("\uFEFF").trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```").removePrefix("json").removePrefix("JSON")
                .trimStart('\n', '\r')
            if (cleaned.endsWith("```")) cleaned = cleaned.removeSuffix("```").trimEnd()
        }
        if (cleaned.startsWith("[")) {
            val top = Parser(cleaned).parseDocument()
            if (top !is JsonValue.Arr) throw IllegalArgumentException("Invalid localization JSON: expected an array of objects")
            if (top.items.any { it !is JsonValue.Obj }) {
                throw IllegalArgumentException("Invalid localization JSON: array items must be objects")
            }
            return top.items.filterIsInstance<JsonValue.Obj>()
        }
        val parser = Parser(cleaned)
        return parser.parseDocuments()
    }

    /** One flat-I2 JSON object, as found in an export file. */
    data class ParsedJsonTable(
        /** m_Name, null when the object has none (it then cannot be routed to a table). */
        val name: String?,
        /** #languages index -> language-code mapping, null when the object has none (positional fallback). */
        val languageCodes: List<String>?,
        /** Terms in file order; values are keyed by language index, not code. */
        val terms: List<ParsedJsonTerm>,
    )

    data class ParsedJsonTerm(
        val key: String,
        val valuesByIndex: Map<Int, String>,
    )

    /**
     * Extracts the tables of a multi-object file. Objects without
     * any mSource.mTerms term are kept with an empty term list
     * (the import planner skips them); a non-scalar term/language
     * value throws, like [toTsv].
     */
    fun parsedTables(objects: List<JsonValue.Obj>): List<ParsedJsonTable> {
        val termPattern = Regex("""mSource\.mTerms\[(\d+)\]\.Term""")
        val languagePattern = Regex("""mSource\.mTerms\[(\d+)\]\.Languages\[(\d+)\]""")
        return objects.map { obj ->
            val name = (obj.entries["m_Name"] as? JsonValue.Str)?.value
            // A malformed #languages (not an array of strings) falls
            // back to positional mapping, like a file without one.
            val languageCodes = (obj.entries["#languages"] as? JsonValue.Arr)?.let { arr ->
                val codes = arr.items.map { (it as? JsonValue.Str)?.value }
                if (codes.any { it == null }) null else codes.filterNotNull()
            }
            val keys = HashMap<Int, String>()
            val values = HashMap<Int, MutableMap<Int, String>>()
            for ((entryKey, entryValue) in obj.entries) {
                termPattern.matchEntire(entryKey)?.let { match ->
                    keys[match.groupValues[1].toInt()] = scalar(entryKey, entryValue)
                }
                languagePattern.matchEntire(entryKey)?.let { match ->
                    val termIndex = match.groupValues[1].toInt()
                    val languageIndex = match.groupValues[2].toInt()
                    values.getOrPut(termIndex) { HashMap() }[languageIndex] = scalar(entryKey, entryValue)
                }
            }
            ParsedJsonTable(
                name = name,
                languageCodes = languageCodes,
                terms = keys.keys.sorted().mapNotNull { termIndex ->
                    keys[termIndex]?.let { ParsedJsonTerm(it, values[termIndex].orEmpty()) }
                },
            )
        }
    }

    /**
     * Converts a flat I2 JSON document to TSV text for [UnityLocalizationTsv.planImport].
     * Throws [IllegalArgumentException] when the document is not a JSON object, has no
     * `mSource.mTerms` entries, or holds a non-scalar term/language value.
     */
    fun toTsv(source: LocalizationSource, jsonText: String): String {
        val top = parse(jsonText)
        if (top !is JsonValue.Obj) {
            throw IllegalArgumentException(
                "Localization JSON must be a JSON object with mSource.mTerms[N] entries",
            )
        }
        val termPattern = Regex("""mSource\.mTerms\[(\d+)\]\.Term""")
        val languagePattern = Regex("""mSource\.mTerms\[(\d+)\]\.Languages\[(\d+)\]""")
        val keys = HashMap<Int, String>()
        val values = HashMap<Int, MutableMap<Int, String>>()
        for ((entryKey, entryValue) in top.entries) {
            termPattern.matchEntire(entryKey)?.let { match ->
                keys[match.groupValues[1].toInt()] = scalar(entryKey, entryValue)
            }
            languagePattern.matchEntire(entryKey)?.let { match ->
                val termIndex = match.groupValues[1].toInt()
                val languageIndex = match.groupValues[2].toInt()
                if (languageIndex < source.languages.size) {
                    values.getOrPut(termIndex) { HashMap() }[languageIndex] = scalar(entryKey, entryValue)
                }
            }
        }
        if (keys.isEmpty()) {
            throw IllegalArgumentException(
                "Localization JSON has no mSource.mTerms[N].Term entries",
            )
        }
        val languageCodes = source.languages.map { it.code }
        return buildString {
            appendRow(listOf("key") + languageCodes)
            for (termIndex in keys.keys.sorted()) {
                val rowValues = values[termIndex].orEmpty()
                appendRow(
                    listOf(keys.getValue(termIndex)) +
                        languageCodes.indices.map { rowValues[it].orEmpty() },
                )
            }
        }
    }

    private fun scalar(entryKey: String, value: JsonValue): String = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.raw
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Null -> ""
        is JsonValue.Obj, is JsonValue.Arr -> throw IllegalArgumentException(
            "Localization JSON value for '$entryKey' must be a string",
        )
    }

    /** Same quoting rule as UnityLocalizationTsv's writer: quote on tab/CR/LF/quote, doubling quotes. */
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

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != text.length) fail("Trailing characters after the JSON document")
            return value
        }

        fun parseDocuments(): List<JsonValue.Obj> {
            val documents = ArrayList<JsonValue.Obj>()
            while (true) {
                skipWhitespace()
                if (pos >= text.length) break
                val value = parseValue()
                if (value !is JsonValue.Obj) fail("Expected a JSON object")
                documents += value
            }
            return documents
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("Invalid localization JSON: $message at offset $pos")

        private fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= text.length) fail("Unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> {
                    expectLiteral("true")
                    JsonValue.Bool(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonValue.Bool(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonValue.Null
                }
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Unexpected character '$c'")
            }
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, pos)) fail("Expected '$literal'")
            pos += literal.length
        }

        private fun parseObject(): JsonValue.Obj {
            pos++ // '{'
            skipWhitespace()
            val entries = LinkedHashMap<String, JsonValue>()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (pos >= text.length || text[pos] != '"') fail("Expected an object key string")
                val key = parseString()
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') fail("Expected ':' after an object key")
                pos++
                entries[key] = parseValue()
                skipWhitespace()
                when {
                    pos < text.length && text[pos] == ',' -> pos++
                    pos < text.length && text[pos] == '}' -> {
                        pos++
                        return JsonValue.Obj(entries)
                    }
                    else -> fail("Expected ',' or '}' in an object")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            pos++ // '['
            skipWhitespace()
            val items = ArrayList<JsonValue>()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when {
                    pos < text.length && text[pos] == ',' -> pos++
                    pos < text.length && text[pos] == ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("Expected ',' or ']' in an array")
                }
            }
        }

        private fun parseString(): String {
            // Caller guarantees text[pos] == '"'.
            pos++
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("Unterminated string")
                val c = text[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("Unterminated escape sequence")
                        when (val escape = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("Truncated \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("Invalid \\u escape")
                                pos += 4
                                if (code in 0xD800..0xDBFF &&
                                    pos + 6 <= text.length &&
                                    text[pos] == '\\' && text[pos + 1] == 'u'
                                ) {
                                    val lowHex = text.substring(pos + 2, pos + 6)
                                    val low = lowHex.toIntOrNull(16)
                                    if (low != null && low in 0xDC00..0xDFFF) {
                                        out.append(code.toChar())
                                        out.append(low.toChar())
                                        pos += 6
                                    } else {
                                        out.append(code.toChar())
                                    }
                                } else {
                                    out.append(code.toChar())
                                }
                            }
                            else -> fail("Invalid escape '\\$escape'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            if (pos >= text.length || text[pos] !in '0'..'9') fail("Invalid number")
            if (text[pos] == '0') {
                pos++
            } else {
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                if (pos >= text.length || text[pos] !in '0'..'9') fail("Invalid number fraction")
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || text[pos] !in '0'..'9') fail("Invalid number exponent")
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            return JsonValue.Num(text.substring(start, pos))
        }
    }

    /**
     * Serializes one table to the flat I2 JSON shape the import
     * accepts: m_Name plus mSource.mTerms[N].Term and
     * mSource.mTerms[N].Languages[i] for every language index, in
     * term order. A "#languages" entry documents the index ->
     * language-code mapping of the source table (import ignores
     * unknown top-level keys, so it round-trips harmlessly); it
     * exists so a reader of a copied file knows which index is
     * which language. Null values become "" (import treats an
     * empty cell as "keep old value"). Keys and values are
     * JSON-escaped; other characters (including Persian/Arabic)
     * are emitted as-is (UTF-8), never as \u escapes, so the text
     * stays readable.
     */
    fun toJson(source: LocalizationSource): String =
        buildJson(source, source.languages.indices.toList())

    /**
     * The same table with only one language's values (Term keys
     * plus that language's index only). Returns null when the
     * table has no such language. The full #languages list is
     * still emitted, so the index stays mappable on import.
     */
    fun toJsonForLanguage(source: LocalizationSource, languageCode: String): String? {
        val index = source.languageIndex(languageCode)
        if (index < 0) return null
        return buildJson(source, listOf(index))
    }

    private fun buildJson(source: LocalizationSource, languageIndices: List<Int>): String {
        val entries = ArrayList<String>()
        entries += "\"m_Name\": " + escapeJson(source.name)
        entries += "\"#languages\": [" + source.languages.joinToString(", ") { escapeJson(it.code) } + "]"
        for ((n, term) in source.terms.withIndex()) {
            entries += "\"mSource.mTerms[$n].Term\": " + escapeJson(term.key)
            for (i in languageIndices) {
                entries += "\"mSource.mTerms[$n].Languages[$i]\": " +
                    escapeJson(term.values.getOrNull(i) ?: "")
            }
        }
        return "{\n  " + entries.joinToString(",\n  ") + "\n}"
    }

    /**
     * All tables, each a standalone flat JSON object, separated by
     * a blank line. With [languageCode] set, only that language's
     * values are emitted, and tables without that language are
     * skipped entirely.
     */
    fun toJsonAll(
        sourcesByEntry: Map<String, List<LocalizationSource>>,
        languageCode: String? = null,
    ): String {
        val tables = sourcesByEntry.values.flatten()
        return if (languageCode == null) {
            tables.joinToString("\n\n") { toJson(it) }
        } else {
            tables.mapNotNull { toJsonForLanguage(it, languageCode) }.joinToString("\n\n")
        }
    }

    /** One importable plan: a discovered table plus its validated plan. */
    data class JsonImportPlan(
        val entryName: String,
        val source: LocalizationSource,
        val plan: LocalizationImportPlan,
    )

    /** Planning outcome for a multi-table JSON import. */
    data class JsonImportResult(
        val plans: List<JsonImportPlan>,
        val placeholderSkippedTerms: Int,
        val unknownTables: List<String>,
        val unknownLanguages: List<String>,
        val unknownKeyTerms: Int,
    )

    /**
     * Plans a multi-table JSON import. Each parsed table is routed
     * by m_Name to every discovered table with that name; each
     * value is routed by language CODE (#languages mapping,
     * positional fallback when absent), so an edited value
     * replaces only its own language column, never the others.
     * One plan per table covers all its edited codes at once, so
     * a table edited in two languages still writes once.
     * Placeholder validation uses [sourceCode] (or the table's
     * first language when null), exactly like the per-table
     * import. Empty values mean "keep old value" and are not
     * planned. Only plans that can apply are returned.
     */
    fun planImportAll(
        sourcesByEntry: Map<String, List<LocalizationSource>>,
        parsed: List<ParsedJsonTable>,
        sourceCode: String?,
        options: UnityTextTransformOptions = UnityTextTransformOptions(),
    ): JsonImportResult {
        val plans = ArrayList<JsonImportPlan>()
        val unknownTables = ArrayList<String>()
        val unknownLanguages = ArrayList<String>()
        var placeholderSkipped = 0
        var unknownKeys = 0
        for (table in parsed) {
            if (table.terms.isEmpty()) continue
            val name = table.name
            if (name == null) {
                unknownTables += "(no m_Name)"
                continue
            }
            val matched = ArrayList<Pair<String, LocalizationSource>>()
            for ((entryName, sources) in sourcesByEntry) {
                for (source in sources) {
                    if (source.name == name) matched += entryName to source
                }
            }
            if (matched.isEmpty()) {
                unknownTables += name
                continue
            }
            for ((entryName, source) in matched) {
                val editsByCode = LinkedHashMap<String, LinkedHashMap<String, String>>()
                val knownKeys = source.terms.map { it.key }.toSet()
                for (term in table.terms) {
                    if (term.key !in knownKeys) {
                        unknownKeys++
                        continue
                    }
                    for ((languageIndex, value) in term.valuesByIndex) {
                        if (value.isEmpty()) continue
                        val code = table.languageCodes?.getOrNull(languageIndex)
                            ?: source.languages.getOrNull(languageIndex)?.code
                        if (code == null) continue
                        if (source.languageIndex(code) < 0) {
                            unknownLanguages += "$name:$code"
                            continue
                        }
                        editsByCode.getOrPut(code) { LinkedHashMap() }[term.key] = value
                    }
                }
                if (editsByCode.isEmpty()) continue
                val codes = editsByCode.keys.toList()
                val tsv = buildString {
                    appendRow(listOf("key") + codes)
                    for (term in source.terms) {
                        val rowEdits = codes.map { editsByCode.getValue(it)[term.key] }
                        if (rowEdits.all { it == null }) continue
                        appendRow(listOf(term.key) + rowEdits.map { it.orEmpty() })
                    }
                }
                val plan = UnityLocalizationTsv.planImport(source, tsv, options, sourceCode)
                placeholderSkipped += plan.placeholderFailureCount
                if (plan.canApply) {
                    plans += JsonImportPlan(entryName, source, plan)
                }
            }
        }
        return JsonImportResult(
            plans = plans,
            placeholderSkippedTerms = placeholderSkipped,
            unknownTables = unknownTables.distinct(),
            unknownLanguages = unknownLanguages.distinct(),
            unknownKeyTerms = unknownKeys,
        )
    }

    /** Minimal JSON string escaper: quote, backslash, control chars; nothing else escaped. */
    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
