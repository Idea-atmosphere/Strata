package dev.cluvex.strata.core.unity

import java.io.File
import java.io.IOException

/** Applies localization tables over an already-staged entry (for example, a patched font entry). */
object UnityLocalizationOverlay {
    fun write(
        baseEntry: File,
        desiredSources: List<LocalizationSource>,
        output: File,
        cacheRoot: File,
    ) {
        require(desiredSources.isNotEmpty()) { "No localization tables were supplied" }
        if (output.exists()) throw IOException("Output already exists: ${output.name}")
        val cache = File(cacheRoot, "localization-overlay-${System.nanoTime()}")
        val replacements = LinkedHashMap<String, File>()
        try {
            UnityBundleReader.open(baseEntry, cache).use { document ->
                if (!document.repackSupported) {
                    throw UnityFormatException("Staged entry cannot be rewritten for localization")
                }
                val actualByEntry = LinkedHashMap<UnityBundleEntry, List<LocalizationSource>>()
                document.summary.entries.forEach { entry ->
                    ensureUnityOperationActive()
                    val found = document.inspectLocalizations(entry) as? UnityLocalizationInspection.Found
                    if (found != null) actualByEntry[entry] = found.sources
                }

                val mapped = LinkedHashMap<UnityBundleEntry, MutableList<LocalizationSource>>()
                desiredSources.forEach { desired ->
                    val matches = actualByEntry.flatMap { (entry, sources) ->
                        sources.filter { actual -> structurallySameTable(actual, desired) }.map { entry to it }
                    }
                    if (matches.size != 1) {
                        throw UnityFormatException(
                            if (matches.isEmpty()) {
                                "Staged entry is missing localization table ${desired.origin.tableId}"
                            } else {
                                "Staged entry has an ambiguous localization table ${desired.origin.tableId}"
                            },
                        )
                    }
                    val (entry, actual) = matches.single()
                    mapped.getOrPut(entry, ::ArrayList) += LocalizationSource.create(
                        name = desired.name,
                        languages = desired.languages,
                        terms = desired.terms,
                        origin = actual.origin,
                    )
                }

                mapped.forEach { (entry, sources) ->
                    val replacement = File(
                        cache,
                        "entry-${entry.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}.bin",
                    )
                    document.writeLocalizationReplacement(entry, sources, replacement)
                    replacements[entry.name] = replacement
                }
                document.repack(output, replacements)
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun structurallySameTable(actual: LocalizationSource, desired: LocalizationSource): Boolean =
        actual.origin.partIndex == desired.origin.partIndex &&
            actual.origin.pathId == desired.origin.pathId &&
            actual.origin.termsPath == desired.origin.termsPath &&
            actual.origin.languagesPath == desired.origin.languagesPath &&
            actual.terms.map { it.key } == desired.terms.map { it.key }
}
