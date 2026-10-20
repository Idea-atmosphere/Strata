package dev.cluvex.strata.feature.unity

import dev.cluvex.strata.core.unity.LocalizationSource
import dev.cluvex.strata.core.unity.UnityLocalizationTsv
import dev.cluvex.strata.core.unity.UnityTextTransformOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Publishes an editable TSV draft through a verified temporary file. The source bundle is never
 * touched, and a failed or cancelled export leaves neither a partial draft nor a stale handoff.
 */
internal fun writeLocalizationTsvDraft(
    directory: File,
    source: LocalizationSource,
    languageCode: String?,
    transformOptions: UnityTextTransformOptions,
    sourceLanguageCode: String,
): File {
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IOException("Localization draft directory is unavailable")
    }
    require(source.languageIndex(sourceLanguageCode) >= 0) {
        "Unknown source language '$sourceLanguageCode'"
    }
    if (languageCode != null) {
        require(source.languageIndex(languageCode) >= 0) {
            "Unknown localization language '$languageCode'"
        }
    }

    val tableTag = source.origin.tableId.hashCode().toUInt().toString(16)
    val languageTag = (languageCode ?: "all")
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(40)
        .ifEmpty { "language" }
    val nonce = System.nanoTime().toULong().toString(16)
    val output = File(directory, "localization-$tableTag-$languageTag-$nonce.tsv")
    val temporary = File(directory, ".${output.name}.tmp")

    try {
        FileOutputStream(temporary).use { stream ->
            stream.writer(Charsets.UTF_8).buffered().use { writer ->
                if (languageCode == null) {
                    UnityLocalizationTsv.exportAllTo(
                        output = writer,
                        source = source,
                        transformOptions = transformOptions,
                        sourceLanguageCode = sourceLanguageCode,
                    )
                } else {
                    UnityLocalizationTsv.exportLanguageTo(
                        output = writer,
                        source = source,
                        languageCode = languageCode,
                        transformOptions = transformOptions,
                        sourceLanguageCode = sourceLanguageCode,
                    )
                }
                writer.flush()
                stream.fd.sync()
            }
        }
        val expectedBytes = temporary.length()
        if (!temporary.isFile || expectedBytes <= 0L) {
            throw IOException("Localization TSV draft is empty")
        }
        if (output.exists() || !temporary.renameTo(output)) {
            throw IOException("Could not atomically publish localization TSV draft")
        }
        if (!output.isFile || output.length() != expectedBytes) {
            throw IOException("Published localization TSV draft failed verification")
        }
        return output
    } catch (error: Throwable) {
        temporary.delete()
        output.delete()
        throw error
    }
}
