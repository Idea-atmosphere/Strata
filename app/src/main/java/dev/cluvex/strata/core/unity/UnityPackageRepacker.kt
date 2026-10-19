package dev.cluvex.strata.core.unity

import dev.cluvex.strata.core.archive.ArchiveParent
import dev.cluvex.strata.core.archive.ZipEditor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Rebuilds a new Unity bundle and, when nested, copies each containing archive outward. */
object UnityPackageRepacker {
    /** Source-compatible entry point retained for callers that do not need a growth guard. */
    fun repack(
        document: UnityBundleDocument,
        replacements: Map<String, File>,
        parents: List<ArchiveParent>,
        cacheDir: File,
    ): File = repack(document, replacements, parents, cacheDir, null)

    fun repack(
        document: UnityBundleDocument,
        replacements: Map<String, File>,
        parents: List<ArchiveParent>,
        cacheDir: File,
        maxBundleGrowthRatio: Double?,
    ): File {
        cacheDir.mkdirs()
        val tempDir = File(cacheDir, "unity-repack-${System.nanoTime()}")
        if (!tempDir.mkdir()) throw IOException("Could not create a unique Unity staging directory")
        var cleanupFile: File? = null
        try {
            val bundle = if (parents.isEmpty()) {
                uniqueSibling(document.source, "-unity-edited")
            } else {
                File(tempDir, "rebuilt-bundle.${document.source.extension.ifEmpty { "bundle" }}")
            }
            document.repack(bundle, replacements)
            if (maxBundleGrowthRatio != null) {
                try {
                    UnityLocalizationOperations.verifyBundleGrowth(
                        sourceBytes = document.source.length(),
                        outputBytes = bundle.length(),
                        maxGrowthRatio = maxBundleGrowthRatio,
                    )
                } catch (error: UnityLocalizationSizeGuardException) {
                    bundle.delete()
                    throw error
                }
            }
            var current = bundle
            cleanupFile = bundle

            for (index in parents.indices.reversed()) {
                val parent = parents[index]
                val source = File(parent.archivePath)
                if (!source.isFile) throw IOException("Parent archive is missing: ${source.name}")
                val isFinal = index == 0
                val destination = if (isFinal) uniqueSibling(source, "-unity-edited") else {
                    File(tempDir, "parent-${index}-${System.nanoTime()}-${source.name}")
                }
                if (UnityBundleReader.probe(source).kind == UnityFileKind.UNITY_FS) {
                    // The parent is itself a Unity bundle: rebuild it with the child entry replaced.
                    UnityBundleReader.open(source, File(tempDir, "parent-cache-$index")).use { parentDoc ->
                        parentDoc.repack(destination, mapOf(parent.entryPath to current))
                    }
                } else {
                    copyExclusive(source, destination)
                    try {
                        ZipEditor.replace(destination, parent.entryPath, current)
                    } catch (e: Throwable) {
                        destination.delete()
                        throw e
                    }
                }
                if (current != bundle) current.delete()
                current = destination
                cleanupFile = destination
            }
            if (bundle != current) bundle.delete()
            return current
        } catch (e: Throwable) {
            cleanupFile?.delete()
            throw e
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun copyExclusive(source: File, destination: File) {
        if (!destination.createNewFile()) throw IOException("Output already exists: ${destination.name}")
        try {
            FileInputStream(source).buffered().use { input ->
                FileOutputStream(destination).buffered().use { output -> input.copyTo(output) }
            }
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }

    private fun uniqueSibling(source: File, suffix: String): File {
        val parent = source.parentFile ?: throw IOException("Source has no parent directory")
        val extension = source.extension
        val base = source.nameWithoutExtension + suffix
        var candidate = File(parent, if (extension.isEmpty()) base else "$base.$extension")
        var index = 1
        while (candidate.exists()) {
            candidate = File(parent, if (extension.isEmpty()) "$base ($index)" else "$base ($index).$extension")
            index++
        }
        return candidate
    }
}
