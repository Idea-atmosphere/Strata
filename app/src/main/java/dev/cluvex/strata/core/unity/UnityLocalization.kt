package dev.cluvex.strata.core.unity

class UnityLocalizationConflictException(message: String) : UnityFormatException(message)

class UnityLocalizationSizeGuardException(
    val sourceBytes: Long,
    val outputBytes: Long,
    val maxGrowthRatio: Double,
) : UnityFormatException(
    "Localization repack grew from $sourceBytes to $outputBytes bytes; limit is ${maxGrowthRatio}x",
)

/** One language descriptor in a structurally detected Unity localization table. */
data class LocalizationLanguage(
    val name: String,
    val code: String,
    val flags: Int,
) {
    init {
        require(name.isNotBlank()) { "Localization language name must not be blank" }
        require(code.matches(CODE_PATTERN)) { "Localization language code '$code' is invalid" }
    }

    private companion object {
        val CODE_PATTERN = Regex("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*$")
    }
}

/** A term whose values are constructor-checked against the table language count. */
class LocalizationTerm private constructor(
    val key: String,
    values: List<String?>,
    expectedLanguageCount: Int,
) {
    val values: List<String?> = values.toList()

    init {
        require(key.isNotBlank()) { "Localization term key must not be blank" }
        require(this.values.size == expectedLanguageCount) {
            "Localization term '$key' has ${this.values.size} values but the table has $expectedLanguageCount languages"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LocalizationTerm && key == other.key && values == other.values

    override fun hashCode(): Int = 31 * key.hashCode() + values.hashCode()

    override fun toString(): String = "LocalizationTerm(key=$key, values=$values)"

    companion object {
        fun aligned(key: String, values: List<String?>, languageCount: Int): LocalizationTerm =
            LocalizationTerm(key, values, languageCount)
    }
}

/** Structural identity/address of a table; no asset or plugin name participates in detection. */
class LocalizationOrigin internal constructor(
    val entryName: String,
    val partIndex: Int,
    val pathId: Long,
    val objectEntryName: String,
    val termsPath: List<String>,
    val languagesPath: List<String>,
    val languageCountPath: List<String>?,
    internal val termKeyField: String,
    internal val termValuesField: String,
    internal val languageNameField: String,
    internal val languageCodeField: String,
    internal val languageFlagsField: String,
) {
    val sourcePathPrefix: List<String> = termsPath.zip(languagesPath)
        .takeWhile { (left, right) -> left == right }
        .map { it.first }

    val tableId: String = buildString {
        append(partIndex).append(':').append(pathId).append(':')
        append(termsPath.joinToString("."))
        append('|').append(languagesPath.joinToString("."))
    }
}

/** Immutable, ordered localization table with an unbreakable alignment invariant. */
class LocalizationSource private constructor(
    val name: String,
    languages: List<LocalizationLanguage>,
    terms: List<LocalizationTerm>,
    val origin: LocalizationOrigin,
) {
    val languages: List<LocalizationLanguage> = languages.toList()
    val terms: List<LocalizationTerm> = terms.toList()

    init {
        require(this.languages.isNotEmpty()) { "Localization table has no languages" }
        val duplicateCode = this.languages.groupingBy { it.code }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicateCode == null) { "Localization table has duplicate language code '${duplicateCode?.key}'" }
        val duplicateKey = this.terms.groupingBy { it.key }.eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicateKey == null) { "Localization table has duplicate term key '${duplicateKey?.key}'" }
        this.terms.forEach { term ->
            require(term.values.size == this.languages.size) {
                "Localization term '${term.key}' has ${term.values.size} values but the table has ${this.languages.size} languages"
            }
        }
    }

    fun languageIndex(code: String): Int = languages.indexOfFirst { it.code == code }

    fun nonEmptyCount(code: String): Int {
        val index = languageIndex(code)
        if (index < 0) return 0
        return terms.count { !it.values[index].isNullOrEmpty() }
    }

    fun addLanguage(language: LocalizationLanguage): LocalizationSource {
        require(languages.none { it.code == language.code }) {
            "Localization language code '${language.code}' already exists"
        }
        val newLanguages = languages + language
        val newTerms = terms.map { term ->
            ensureUnityOperationActive()
            LocalizationTerm.aligned(term.key, term.values + null, newLanguages.size)
        }
        return create(name, newLanguages, newTerms, origin)
    }

    internal fun withValues(valuesByKey: Map<String, List<String?>>): LocalizationSource {
        val unknown = valuesByKey.keys - terms.map { it.key }.toSet()
        require(unknown.isEmpty()) { "Localization update contains unknown key '${unknown.first()}'" }
        val updated = terms.map { term ->
            ensureUnityOperationActive()
            LocalizationTerm.aligned(term.key, valuesByKey[term.key] ?: term.values, languages.size)
        }
        return create(name, languages, updated, origin)
    }

    companion object {
        fun create(
            name: String,
            languages: List<LocalizationLanguage>,
            terms: List<LocalizationTerm>,
            origin: LocalizationOrigin,
        ): LocalizationSource = LocalizationSource(name, languages, terms, origin)
    }
}

sealed class UnityLocalizationInspection {
    data class Found(val sources: List<LocalizationSource>) : UnityLocalizationInspection() {
        init {
            require(sources.isNotEmpty()) { "Found localization result must contain at least one table" }
        }
    }

    data class Unavailable(
        val reason: UnityTextUnavailableReason,
        val diagnostic: String,
    ) : UnityLocalizationInspection()
}

data class LocalizationAddLanguagePlan(
    val original: LocalizationSource,
    val updated: LocalizationSource?,
    val termsUpdated: Int,
    val estimatedDataDeltaBytes: Long,
    val reason: UnityTextUnavailableReason? = null,
    val diagnostic: String? = null,
    val rtlFlagInference: UnityRtlFlagInference? = null,
) {
    val canApply: Boolean
        get() = updated != null &&
            reason == null &&
            (rtlFlagInference == null || rtlFlagInference.status == UnityRtlInferenceStatus.DETERMINED)
}

object UnityLocalizationOperations {
    fun verifyBundleGrowth(
        sourceBytes: Long,
        outputBytes: Long,
        maxGrowthRatio: Double = 1.25,
    ) {
        require(sourceBytes >= 0 && outputBytes >= 0) { "Bundle sizes cannot be negative" }
        require(maxGrowthRatio >= 1.0) { "Bundle growth ratio must be at least 1.0" }
        val maximum = (sourceBytes.toDouble() * maxGrowthRatio).toLong()
        if (outputBytes > maximum) {
            throw UnityLocalizationSizeGuardException(sourceBytes, outputBytes, maxGrowthRatio)
        }
    }

    fun planAddLanguage(
        source: LocalizationSource,
        language: LocalizationLanguage,
    ): LocalizationAddLanguagePlan = try {
        val updated = source.addLanguage(language)
        val descriptorBytes = language.name.toByteArray(Charsets.UTF_8).size +
            language.code.toByteArray(Charsets.UTF_8).size + 16L
        LocalizationAddLanguagePlan(
            original = source,
            updated = updated,
            termsUpdated = source.terms.size,
            estimatedDataDeltaBytes = descriptorBytes + source.terms.size * 4L,
        )
    } catch (error: IllegalArgumentException) {
        LocalizationAddLanguagePlan(
            original = source,
            updated = null,
            termsUpdated = 0,
            estimatedDataDeltaBytes = 0,
            reason = UnityTextUnavailableReason.LOCALIZATION_ALIGNMENT_INVALID,
            diagnostic = error.message,
        )
    }
}

internal sealed class LocalizationShapeResult {
    data class Found(val sources: List<LocalizationSource>) : LocalizationShapeResult()
    data class Invalid(val diagnostic: String) : LocalizationShapeResult()
    data object NotFound : LocalizationShapeResult()
}

/** Shape-only detector and writer for decoded type-tree values. */
internal object UnityLocalizationShape {
    private val LANGUAGE_CODE = Regex("^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})*$")
    private const val MAX_STRUCT_DEPTH = 8

    private data class StructAt(val path: List<String>, val value: UVal.Struct)
    private data class TermCandidate(
        val path: List<String>,
        val keyField: String,
        val valuesField: String,
        val keys: List<String>,
        val values: List<List<String?>>,
        val valueCount: Int,
    )
    private data class LanguageCandidate(
        val path: List<String>,
        val nameField: String,
        val codeField: String,
        val flagsField: String,
        val languages: List<LocalizationLanguage>,
    )

    fun detect(
        root: UVal,
        entryName: String,
        partIndex: Int,
        pathId: Long,
        objectEntryName: String,
    ): LocalizationShapeResult = detectInternal(root, entryName, partIndex, pathId, objectEntryName, true)

    internal fun detectTopLevelOnly(root: UVal): Boolean =
        detectInternal(root, "test", 0, 0, "test", false) is LocalizationShapeResult.Found

    private fun detectInternal(
        root: UVal,
        entryName: String,
        partIndex: Int,
        pathId: Long,
        objectEntryName: String,
        recursiveTerms: Boolean,
    ): LocalizationShapeResult {
        val rootStruct = root as? UVal.Struct ?: return LocalizationShapeResult.NotFound
        val structs = collectStructs(rootStruct)
        val termScopes = if (recursiveTerms) structs else listOf(StructAt(emptyList(), rootStruct))
        val terms = ArrayList<TermCandidate>()
        var malformedTerms: String? = null

        termScopes.forEach { scope ->
            ensureUnityOperationActive()
            scope.value.fields.forEach fieldLoop@{ (field, child) ->
                val array = child as? UVal.Arr ?: return@fieldLoop
                val rows = array.items.mapNotNull { it as? UVal.Struct }
                if (rows.isEmpty() || rows.size != array.items.size) return@fieldLoop
                val commonStrings = rows.map { it.fields.filterValues { value -> value is UVal.Str }.keys }
                    .reduce { left, right -> left.intersect(right) }
                val commonArrays = rows.map { it.fields.filterValues { value -> value is UVal.Arr }.keys }
                    .reduce { left, right -> left.intersect(right) }
                commonStrings.forEach keyLoop@{ keyField ->
                    val keys = rows.map { (it.fields.getValue(keyField) as UVal.Str).value }
                    if (keys.any(String::isBlank) || keys.toSet().size != keys.size) return@keyLoop
                    commonArrays.forEach valuesLoop@{ valuesField ->
                        val valueRows = ArrayList<List<String?>>(rows.size)
                        for (row in rows) {
                            val values = (row.fields.getValue(valuesField) as UVal.Arr).items
                            if (values.any { it !is UVal.Str }) return@valuesLoop
                            valueRows += values.map { (it as UVal.Str).value as String? }
                        }
                        val counts = valueRows.map { it.size }.toSet()
                        if (counts.size != 1) {
                            malformedTerms = "Localization term value arrays do not have a consistent length"
                            return@valuesLoop
                        }
                        terms += TermCandidate(
                            path = scope.path + field,
                            keyField = keyField,
                            valuesField = valuesField,
                            keys = keys,
                            values = valueRows,
                            valueCount = counts.single(),
                        )
                    }
                }
            }
        }

        val languages = ArrayList<LanguageCandidate>()
        structs.forEach { scope ->
            ensureUnityOperationActive()
            scope.value.fields.forEach fieldLoop@{ (field, child) ->
                val array = child as? UVal.Arr ?: return@fieldLoop
                val rows = array.items.mapNotNull { it as? UVal.Struct }
                if (rows.isEmpty() || rows.size != array.items.size) return@fieldLoop
                val commonStrings = rows.map { it.fields.filterValues { value -> value is UVal.Str }.keys }
                    .reduce { left, right -> left.intersect(right) }
                val commonNumbers = rows.map { it.fields.filterValues { value -> value is UVal.Num }.keys }
                    .reduce { left, right -> left.intersect(right) }
                if (commonStrings.size < 2 || commonNumbers.isEmpty()) return@fieldLoop
                val codeFields = commonStrings.filter { candidate ->
                    val values = rows.map { (it.fields.getValue(candidate) as UVal.Str).value }
                    values.all { it.matches(LANGUAGE_CODE) } && values.toSet().size == values.size
                }
                if (codeFields.isEmpty()) return@fieldLoop
                val codeField = codeFields.minBy { candidate ->
                    rows.sumOf { (it.fields.getValue(candidate) as UVal.Str).value.length }
                }
                val nameField = (commonStrings - codeField).maxBy { candidate ->
                    rows.sumOf { (it.fields.getValue(candidate) as UVal.Str).value.length }
                }
                val flagsField = commonNumbers.first()
                val descriptors = rows.map { row ->
                    LocalizationLanguage(
                        name = (row.fields.getValue(nameField) as UVal.Str).value,
                        code = (row.fields.getValue(codeField) as UVal.Str).value,
                        flags = (row.fields.getValue(flagsField) as UVal.Num).value.toInt(),
                    )
                }
                languages += LanguageCandidate(scope.path + field, nameField, codeField, flagsField, descriptors)
            }
        }

        if (terms.isEmpty() || languages.isEmpty()) {
            return malformedTerms?.let(LocalizationShapeResult::Invalid) ?: LocalizationShapeResult.NotFound
        }

        val pairs = ArrayList<Pair<TermCandidate, LanguageCandidate>>()
        terms.forEach { term ->
            val matching = languages.filter { it.languages.size == term.valueCount }
            if (matching.size == 1) {
                pairs += term to matching.single()
            } else if (matching.size > 1) {
                val best = matching.groupBy { commonPrefix(term.path, it.path).size }.maxBy { it.key }.value
                if (best.size == 1) pairs += term to best.single()
            }
        }
        if (pairs.isEmpty()) {
            return LocalizationShapeResult.Invalid(
                malformedTerms ?: "Localization language count does not match every term value-array length",
            )
        }
        val duplicate = pairs.groupingBy { it.first.path }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) {
            return LocalizationShapeResult.Invalid("Localization shape is ambiguous for ${duplicate.key.joinToString(".")}")
        }

        val objectName = rootStruct.fields.values.filterIsInstance<UVal.Str>()
            .map { it.value }
            .firstOrNull { it.isNotBlank() }
            ?: "#$pathId"
        return LocalizationShapeResult.Found(pairs.map { (term, language) ->
            val commonPath = commonPrefix(term.path.dropLast(1), language.path.dropLast(1))
            val origin = LocalizationOrigin(
                entryName = entryName,
                partIndex = partIndex,
                pathId = pathId,
                objectEntryName = objectEntryName,
                termsPath = term.path,
                languagesPath = language.path,
                languageCountPath = findLanguageCountPath(rootStruct, commonPath, language.languages.size),
                termKeyField = term.keyField,
                termValuesField = term.valuesField,
                languageNameField = language.nameField,
                languageCodeField = language.codeField,
                languageFlagsField = language.flagsField,
            )
            val modelTerms = term.keys.indices.map { index ->
                LocalizationTerm.aligned(term.keys[index], term.values[index], language.languages.size)
            }
            LocalizationSource.create(objectName, language.languages, modelTerms, origin)
        })
    }

    fun apply(root: UVal, source: LocalizationSource) {
        val rootStruct = root as? UVal.Struct ?: throw UnityFormatException("Localization object root is not a struct")
        val origin = source.origin
        val languageArray = valueAt(rootStruct, origin.languagesPath) as? UVal.Arr
            ?: throw UnityFormatException("Localization language array is missing at ${origin.languagesPath.joinToString(".")}")
        val termArray = valueAt(rootStruct, origin.termsPath) as? UVal.Arr
            ?: throw UnityFormatException("Localization term array is missing at ${origin.termsPath.joinToString(".")}")
        if (languageArray.items.isEmpty()) throw UnityFormatException("Localization table has no language descriptor template")
        if (source.languages.size < languageArray.items.size) {
            throw UnityFormatException("Localization write cannot remove language columns")
        }

        languageArray.items.forEachIndexed { index, item ->
            val descriptor = item as? UVal.Struct
                ?: throw UnityFormatException("Localization language descriptor $index is not a struct")
            val expected = source.languages[index]
            val currentCode = (descriptor.fields[origin.languageCodeField] as? UVal.Str)?.value
                ?: throw UnityFormatException("Localization language code field is missing")
            if (currentCode != expected.code) throw UnityFormatException("Localization language order changed at column $index")
            setString(descriptor, origin.languageNameField, expected.name)
            setString(descriptor, origin.languageCodeField, expected.code)
            setNumber(descriptor, origin.languageFlagsField, expected.flags.toLong())
        }
        val template = languageArray.items.last()
        while (languageArray.items.size < source.languages.size) {
            val descriptor = cloneValue(template) as? UVal.Struct
                ?: throw UnityFormatException("Localization language descriptor template is invalid")
            val language = source.languages[languageArray.items.size]
            setString(descriptor, origin.languageNameField, language.name)
            setString(descriptor, origin.languageCodeField, language.code)
            setNumber(descriptor, origin.languageFlagsField, language.flags.toLong())
            languageArray.items += descriptor
        }

        val termsByKey = source.terms.associateBy { it.key }
        if (termsByKey.size != source.terms.size) throw UnityFormatException("Localization term keys are not unique")
        if (termArray.items.size != source.terms.size) {
            throw UnityFormatException("Localization term count changed from ${termArray.items.size} to ${source.terms.size}")
        }
        val seen = HashSet<String>()
        termArray.items.forEachIndexed { rowIndex, item ->
            ensureUnityOperationActive()
            val row = item as? UVal.Struct ?: throw UnityFormatException("Localization term row $rowIndex is not a struct")
            val key = (row.fields[origin.termKeyField] as? UVal.Str)?.value
                ?: throw UnityFormatException("Localization term key field is missing")
            val model = termsByKey[key] ?: throw UnityFormatException("Localization write is missing term key '$key'")
            seen += key
            val values = row.fields[origin.termValuesField] as? UVal.Arr
                ?: throw UnityFormatException("Localization values for '$key' are not an array")
            if (values.items.size > source.languages.size || values.items.any { it !is UVal.Str }) {
                throw UnityFormatException("Localization values for '$key' have an invalid shape")
            }
            model.values.forEachIndexed { column, value ->
                if (column < values.items.size) {
                    if (value != null) (values.items[column] as UVal.Str).value = value
                } else {
                    values.items += UVal.Str(value ?: "")
                }
            }
            if (values.items.size != source.languages.size) {
                throw UnityFormatException("Localization term '$key' is not aligned after write")
            }
        }
        if (seen.size != source.terms.size) throw UnityFormatException("Localization write did not visit every term")

        origin.languageCountPath?.let { path ->
            val parent = valueAt(rootStruct, path.dropLast(1)) as? UVal.Struct
                ?: throw UnityFormatException("Localization language-count parent is missing")
            setNumber(parent, path.last(), source.languages.size.toLong())
        }
    }

    private fun collectStructs(root: UVal.Struct): List<StructAt> {
        val result = ArrayList<StructAt>()
        fun walk(value: UVal.Struct, path: List<String>, depth: Int) {
            if (depth > MAX_STRUCT_DEPTH) return
            result += StructAt(path, value)
            value.fields.forEach { (field, child) ->
                if (child is UVal.Struct) walk(child, path + field, depth + 1)
            }
        }
        walk(root, emptyList(), 0)
        return result
    }

    private fun findLanguageCountPath(root: UVal.Struct, commonPath: List<String>, count: Int): List<String>? {
        val scope = valueAt(root, commonPath) as? UVal.Struct ?: root
        val matches = scope.fields.filterValues { it is UVal.Num && it.value.toInt() == count }.keys
        return if (matches.size == 1) commonPath + matches.single() else null
    }

    private fun valueAt(root: UVal.Struct, path: List<String>): UVal? {
        var current: UVal = root
        path.forEach { field -> current = (current as? UVal.Struct)?.fields?.get(field) ?: return null }
        return current
    }

    private fun commonPrefix(left: List<String>, right: List<String>): List<String> {
        var end = 0
        while (end < minOf(left.size, right.size) && left[end] == right[end]) end++
        return left.subList(0, end)
    }

    private fun setString(struct: UVal.Struct, field: String, value: String) {
        val target = struct.fields[field] as? UVal.Str
            ?: throw UnityFormatException("Localization string field '$field' is missing")
        target.value = value
    }

    private fun setNumber(struct: UVal.Struct, field: String, value: Long) {
        val target = struct.fields[field] as? UVal.Num
            ?: throw UnityFormatException("Localization numeric field '$field' is missing")
        struct.fields[field] = UVal.Num(value, target.width)
    }

    private fun cloneValue(value: UVal): UVal = when (value) {
        is UVal.Struct -> UVal.Struct(LinkedHashMap<String, UVal>().apply {
            value.fields.forEach { (key, child) -> put(key, cloneValue(child)) }
        })
        is UVal.Arr -> UVal.Arr(ArrayList<UVal>().apply { value.items.forEach { add(cloneValue(it)) } })
        is UVal.Str -> UVal.Str(value.value)
        is UVal.Num -> UVal.Num(value.value, value.width)
        is UVal.Flt -> UVal.Flt(value.value, value.single)
        is UVal.Bool -> UVal.Bool(value.raw)
        is UVal.Bytes -> UVal.Bytes(value.data.copyOf())
    }
}
