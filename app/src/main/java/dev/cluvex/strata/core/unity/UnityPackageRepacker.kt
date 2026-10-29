package dev.cluvex.strata.core.unity

import dev.cluvex.strata.core.archive.ArchiveParent
import dev.cluvex.strata.core.archive.ZipEditor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Rebuilds a new Unity bundle and, when nested, copies each containing archive outward. */
object UnityPackageRepacker {
    private val publishLock = Any()

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
        storeUncompressed: Boolean = false,
    ): File {
        cacheDir.mkdirs()
        val tempDir = File(cacheDir, "unity-repack-${System.nanoTime()}")
        if (!tempDir.mkdir()) throw IOException("Could not create a unique Unity staging directory")
        var current: File? = null
        var unpublishedFinal: File? = null
        try {
            val standaloneOutput = if (parents.isEmpty()) uniqueSibling(document.source, "-unity-edited") else null
            val bundle = if (standaloneOutput != null) {
                temporarySibling(standaloneOutput).also { unpublishedFinal = it }
            } else {
                File(tempDir, "rebuilt-bundle.${document.source.extension.ifEmpty { "bundle" }}")
            }
            document.repack(bundle, replacements, storeUncompressed)
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
            current = if (standaloneOutput != null) {
                RandomAccessFile(bundle, "rw").use { it.fd.sync() }
                publishTemporary(bundle, standaloneOutput)
                unpublishedFinal = null
                standaloneOutput
            } else {
                bundle
            }

            for (index in parents.indices.reversed()) {
                ensureUnityOperationActive()
                val parent = parents[index]
                val source = File(parent.archivePath)
                if (!source.isFile) throw IOException("Parent archive is missing: ${source.name}")
                val child = current ?: throw IOException("Nested Unity replacement is missing")
                val isFinal = index == 0
                val finalOutput = if (isFinal) {
                    uniqueSibling(source, if (source.extension.equals("apk", true)) "-edited" else "-unity-edited")
                } else {
                    null
                }
                if (source.extension.equals("apk", ignoreCase = true) && maxBundleGrowthRatio != null) {
                    verifyApkGrowthPreflight(
                        source = source,
                        entryPath = parent.entryPath,
                        replacement = child,
                        maxGrowthRatio = maxBundleGrowthRatio,
                    )
                }
                val destination = if (finalOutput != null) {
                    temporarySibling(finalOutput).also { unpublishedFinal = it }
                } else {
                    File(tempDir, "parent-${index}-${System.nanoTime()}-${source.name}")
                }

                if (UnityBundleReader.probe(source).kind == UnityFileKind.UNITY_FS) {
                    UnityBundleReader.open(source, File(tempDir, "parent-cache-$index")).use { parentDoc ->
                        parentDoc.repack(destination, mapOf(parent.entryPath to child))
                    }
                } else {
                    val originalMethod = zipCompressionMethod(source, parent.entryPath)
                    copyExclusive(source, destination)
                    try {
                        ZipEditor.replace(destination, parent.entryPath, child)
                        verifyZipReplacement(destination, parent.entryPath, child, originalMethod)
                    } catch (error: Throwable) {
                        destination.delete()
                        throw error
                    }
                }

                if (source.extension.equals("apk", ignoreCase = true) && maxBundleGrowthRatio != null) {
                    try {
                        UnityLocalizationOperations.verifyBundleGrowth(
                            sourceBytes = source.length(),
                            outputBytes = destination.length(),
                            maxGrowthRatio = maxBundleGrowthRatio,
                        )
                    } catch (error: UnityLocalizationSizeGuardException) {
                        destination.delete()
                        throw error
                    }
                }

                if (current != bundle) current.delete()
                if (finalOutput != null) {
                    RandomAccessFile(destination, "rw").use { it.fd.sync() }
                    publishTemporary(destination, finalOutput)
                    unpublishedFinal = null
                    current = finalOutput
                } else {
                    current = destination
                }
            }
            if (bundle != current) bundle.delete()
            return current ?: throw IOException("Unity repack did not produce an output")
        } catch (error: Throwable) {
            unpublishedFinal?.delete()
            current?.takeIf { it.parentFile == tempDir }?.delete()
            throw error
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Cheap conservative check before copying a potentially large APK; exact output is checked too. */
    private fun verifyApkGrowthPreflight(
        source: File,
        entryPath: String,
        replacement: File,
        maxGrowthRatio: Double,
    ) {
        val normalized = entryPath.replace('\\', '/').trimStart('/')
        val originalUncompressed = try {
            ZipFile(source).use { archive ->
                val entry = archive.getEntry(normalized)
                    ?: throw IOException("Parent APK is missing entry: $normalized")
                entry.size.takeIf { it >= 0L }
                    ?: throw IOException("Parent APK entry has no declared size: $normalized")
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Parent APK could not be read: ${source.name}", error)
        }
        val positiveDelta = (replacement.length() - originalUncompressed).coerceAtLeast(0L)
        val projectedMinimum = if (positiveDelta > Long.MAX_VALUE - source.length()) {
            Long.MAX_VALUE
        } else {
            source.length() + positiveDelta
        }
        UnityLocalizationOperations.verifyBundleGrowth(
            sourceBytes = source.length(),
            outputBytes = projectedMinimum,
            maxGrowthRatio = maxGrowthRatio,
        )
    }

    private fun verifyZipReplacement(zip: File, entryPath: String, replacement: File, expectedMethod: Int) {
        val normalized = entryPath.replace('\\', '/').trimStart('/')
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry(normalized)
                ?: throw IOException("Repacked archive is missing replacement entry: $normalized")
            if (entry.method != expectedMethod) {
                throw IOException("Replacement compression method changed for $normalized")
            }
            if (entry.size != replacement.length()) {
                throw IOException("Replacement size verification failed for $normalized")
            }
            val expected = sha256(replacement)
            val actual = MessageDigest.getInstance("SHA-256")
            archive.getInputStream(entry).buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureUnityOperationActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    actual.update(buffer, 0, read)
                }
            }
            if (!actual.digest().contentEquals(expected)) {
                throw IOException("Replacement content verification failed for $normalized")
            }
        }
    }

    private fun zipCompressionMethod(zip: File, entryPath: String): Int {
        val normalized = entryPath.replace('\\', '/').trimStart('/')
        return try {
            ZipFile(zip).use { archive ->
                archive.getEntry(normalized)?.method
                    ?: throw IOException("Parent archive is missing entry: $normalized")
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Parent archive could not be read: ${zip.name}", error)
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun copyExclusive(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (!destination.createNewFile()) throw IOException("Output already exists: ${destination.name}")
        try {
            FileInputStream(source).buffered().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun publishTemporary(temporary: File, output: File) {
        synchronized(publishLock) {
            if (output.exists()) throw IOException("Output already exists: ${output.name}")
            if (!temporary.renameTo(output)) throw IOException("Could not atomically publish ${output.name}")
        }
    }

    private fun temporarySibling(output: File): File {
        val parent = output.parentFile ?: throw IOException("Output directory is missing")
        repeat(100) {
            val candidate = File(parent, ".${output.name}.${System.nanoTime()}.tmp")
            if (!candidate.exists()) return candidate
        }
        throw IOException("Could not allocate temporary APK output")
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
