package dev.cluvex.strata.feature.unity

import dev.cluvex.strata.core.unity.LocalizationSource
import dev.cluvex.strata.core.unity.UnityLocalizationTsv
import dev.cluvex.strata.core.unity.UnityTextTransformOptions
import dev.cluvex.strata.core.unity.ensureUnityOperationActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val MAX_IMPORTED_TSV_BYTES = 64L * 1024L * 1024L
private const val COPY_BUFFER_BYTES = 64 * 1024

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

/** Stages a user-selected TSV with a hard byte bound before parsing it. */
internal fun stageImportedLocalizationTsv(
    directory: File,
    maxBytes: Long = MAX_IMPORTED_TSV_BYTES,
    open: () -> InputStream,
): File {
    require(maxBytes > 0L) { "Localization TSV import limit must be positive" }
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw IOException("Localization draft directory is unavailable")
    }
    val nonce = System.nanoTime().toULong().toString(16)
    val output = File(directory, "localization-import-$nonce.tsv")
    val temporary = File(directory, ".${output.name}.tmp")
    try {
        var copied = 0L
        open().use { input ->
            FileOutputStream(temporary).use { target ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    ensureUnityOperationActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (copied > maxBytes - read.toLong()) {
                        throw IOException("Localization TSV exceeds the $maxBytes-byte import limit")
                    }
                    target.write(buffer, 0, read)
                    copied += read
                }
                target.flush()
                target.fd.sync()
            }
        }
        if (copied <= 0L || temporary.length() != copied) {
            throw IOException("Imported localization TSV is empty or incomplete")
        }
        if (output.exists() || !temporary.renameTo(output)) {
            throw IOException("Could not atomically stage localization TSV")
        }
        if (!output.isFile || output.length() != copied) {
            throw IOException("Staged localization TSV failed verification")
        }
        return output
    } catch (error: Throwable) {
        temporary.delete()
        output.delete()
        throw error
    }
}

/** Decodes imported TSV strictly; malformed UTF-8 is a visible failure, never replacement text. */
internal fun readLocalizationTsvUtf8(file: File): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        InputStreamReader(file.inputStream().buffered(), decoder).use { it.readText() }
    } catch (error: CharacterCodingException) {
        throw IOException("Localization TSV is not valid UTF-8", error)
    }
}
