package dev.cluvex.strata.core.archive

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.File
import java.io.IOException

object ZipEditor {
    fun putAll(zip: File, contents: Map<String, File>) {
        if (contents.isEmpty()) return
        ZipFile(zip).use { z ->
            checkEditable(z)
            val methods = contents.keys.associateWith { entry ->
                val existing = z.getFileHeader(entry)
                existing?.compressionMethod?.takeIf { it != CompressionMethod.AES_INTERNAL_ONLY } ?: defaultMethod(entry)
            }
            val replaced = contents.keys.filter { z.getFileHeader(it) != null }
            if (replaced.isNotEmpty()) z.removeFiles(replaced)
            for ((entry, file) in contents) {
                z.addFile(file, params(entry, methods.getValue(entry)))
            }
        }
    }

    fun put(zip: File, entry: String, content: File) = putAll(zip, mapOf(ArchiveTree.normalize(entry) to content))

    fun replace(zip: File, entry: String, content: File) {
        val normalized = ArchiveTree.normalize(entry)
        ZipFile(zip).use { z ->
            checkEditable(z)
            if (z.getFileHeader(normalized) == null) throw IOException(tr(R.string.archive_not_in_archive, normalized))
        }
        put(zip, normalized, content)
    }

    fun delete(zip: File, paths: Collection<String>): Int = ZipFile(zip).use { z ->
        checkEditable(z)
        val names = matching(z, paths)
        if (names.isNotEmpty()) z.removeFiles(names)
        names.size
    }

    fun rename(zip: File, path: String, newPath: String) {
        val from = ArchiveTree.normalize(path)
        val to = ArchiveTree.normalize(newPath)
        require(from.isNotEmpty() && to.isNotEmpty()) { "Empty name" }
        if (from == to) return
        ZipFile(zip).use { z ->
            checkEditable(z)
            val names = z.fileHeaders.map { it.fileName }
            if (names.any { ArchiveTree.normalize(it) == to || ArchiveTree.normalize(it).startsWith("$to/") }) {
                throw IOException(tr(R.string.common_already_exists, to.substringAfterLast('/')))
            }
            val map = LinkedHashMap<String, String>()
            for (name in names) {
                val clean = ArchiveTree.normalize(name)
                when {
                    clean == from -> map[name] = if (name.endsWith("/")) "$to/" else to
                    clean.startsWith("$from/") -> map[name] = to + clean.removePrefix(from) + if (name.endsWith("/")) "/" else ""
                }
            }
            if (map.isEmpty()) throw IOException(tr(R.string.archive_not_in_archive, from))
            z.renameFiles(map)
        }
    }

    fun add(zip: File, sources: List<File>, intoDir: String) {
        val dir = ArchiveTree.normalize(intoDir)
        val contents = LinkedHashMap<String, File>()
        for (src in sources) {
            val root = src.parentFile ?: continue
            src.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                contents[if (dir.isEmpty()) rel else "$dir/$rel"] = f
            }
        }
        putAll(zip, contents)
    }

    fun defaultMethod(entry: String): CompressionMethod {
        val name = ArchiveTree.normalize(entry)
        return if (name == "resources.arsc" || name.endsWith(".so")) CompressionMethod.STORE else CompressionMethod.DEFLATE
    }

    private fun params(entry: String, method: CompressionMethod) = ZipParameters().apply {
        fileNameInZip = entry
        compressionMethod = method
        isOverrideExistingFilesInZip = true
    }

    private fun matching(z: ZipFile, paths: Collection<String>): List<String> {
        val wanted = paths.map(ArchiveTree::normalize).filter { it.isNotEmpty() }
        return z.fileHeaders.map { it.fileName }.filter { name ->
            val clean = ArchiveTree.normalize(name)
            wanted.any { clean == it || clean.startsWith("$it/") }
        }
    }

    private fun checkEditable(z: ZipFile) {
        if (z.isSplitArchive) throw IOException(tr(R.string.archive_split_not_editable))
        if (z.isEncrypted) throw IOException(tr(R.string.archive_encrypted_not_editable))
    }
}
