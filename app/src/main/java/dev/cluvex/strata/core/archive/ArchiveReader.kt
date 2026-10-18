package dev.cluvex.strata.core.archive

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.File
import java.io.InputStream
import java.util.Collections

data class ArchiveEntry(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long = -1,
    val time: Long = 0,
    val encrypted: Boolean = false,
    val stored: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = path.substringBeforeLast('/', "")
}

class ArchiveTree(entries: List<ArchiveEntry>) {

    private val byPath: Map<String, ArchiveEntry>
    private val children: Map<String, List<ArchiveEntry>>

    init {
        val all = LinkedHashMap<String, ArchiveEntry>()
        val synthesized = HashSet<String>()
        for (e in entries) {
            val clean = normalize(e.path)
            if (clean.isEmpty()) continue
            val existing = all[clean]
            if (existing == null || clean in synthesized || (existing.isDirectory && !e.isDirectory)) {
                all[clean] = e.copy(path = clean)
                synthesized -= clean
            }
            var parent = clean.substringBeforeLast('/', "")
            while (parent.isNotEmpty() && parent !in all) {
                all[parent] = ArchiveEntry(parent, isDirectory = true, size = 0)
                synthesized += parent
                parent = parent.substringBeforeLast('/', "")
            }
        }
        byPath = all
        children = all.values.groupBy { it.parent }
    }

    val size: Int get() = byPath.size

    fun list(dir: String): List<ArchiveEntry> = children[normalize(dir)].orEmpty()

    operator fun get(path: String): ArchiveEntry? = byPath[normalize(path)]

    fun isDirectory(path: String): Boolean = normalize(path).isEmpty() || byPath[normalize(path)]?.isDirectory == true

    fun files(path: String): List<ArchiveEntry> {
        val p = normalize(path)
        val prefix = "$p/"
        return byPath.values.filter { !it.isDirectory && (p.isEmpty() || it.path == p || it.path.startsWith(prefix)) }
    }

    fun sizeOf(path: String): Long = files(path).sumOf { it.size.coerceAtLeast(0) }

    companion object {
        fun normalize(path: String): String =
            path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }
}

object ArchiveReader {

    private val zipSuffixes = listOf(".zip", ".jar", ".apk", ".apks", ".xapk", ".apkm", ".obb", ".war", ".aar")
    private val tarballSuffixes = listOf(".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".tar.zst", ".tar.zstd", ".tar.lz4", ".tar")
    private val singleSuffixes = listOf(".gz", ".bz2", ".xz", ".zst", ".zstd", ".lz4")

    fun isZip(name: String) = zipSuffixes.any { name.lowercase().endsWith(it) }

    fun supports(name: String): Boolean {
        val n = name.lowercase()
        return isZip(n) || n.endsWith(".7z") || RarArchive.isRar(n) || tarballSuffixes.any { n.endsWith(it) } || singleSuffixes.any { n.endsWith(it) }
    }

    fun canUpdate(name: String) = isZip(name)

    fun list(file: File, password: CharArray? = null, access: FileAccess = LocalFileAccess): List<ArchiveEntry> {
        val name = file.name.lowercase()
        return when {
            isZip(name) -> listZip(file, access)
            name.endsWith(".7z") -> listSevenZ(file, password, access)
            RarArchive.isRar(name) -> {
                val readable = RarArchive.readable(file, access)
                try {
                    RarArchive.list(readable.path, password, file.name).map { it.toEntry() }
                } finally {
                    readable.cleanup()
                }
            }
            tarballSuffixes.any { name.endsWith(it) } -> listTar(file, name, access)
            singleSuffixes.any { name.endsWith(it) } -> {
                val suffix = singleSuffixes.first { name.endsWith(it) }
                listOf(ArchiveEntry(file.name.dropLast(suffix.length).ifEmpty { "${file.name}.out" }, false, -1, file.length(), file.lastModified()))
            }
            else -> throw IllegalArgumentException(tr(R.string.archive_unsupported, file.name))
        }
    }

    private fun listZip(file: File, access: FileAccess): List<ArchiveEntry> =
        ZipFile.builder().setSeekableByteChannel(access.openReadChannel(file)).get().use { zip ->
            Collections.list(zip.entries).map { e ->
                ArchiveEntry(
                    path = e.name,
                    isDirectory = e.isDirectory,
                    size = e.size,
                    compressedSize = e.compressedSize,
                    time = e.time.coerceAtLeast(0),
                    encrypted = e.generalPurposeBit.usesEncryption(),
                    stored = e.method == ZipArchiveEntry.STORED,
                )
            }
        }

    private fun listSevenZ(file: File, password: CharArray?, access: FileAccess): List<ArchiveEntry> = try {
        SevenZFile.builder().setSeekableByteChannel(access.openReadChannel(file))
            .apply { if (password != null && password.isNotEmpty()) setPassword(password) }
            .get().use { sevenZ ->
                sevenZ.entries.map { e ->
                    ArchiveEntry(
                        path = e.name,
                        isDirectory = e.isDirectory,
                        size = if (e.hasStream()) e.size else 0,
                        time = if (e.hasLastModifiedDate) e.lastModifiedTime.toMillis() else 0,
                    )
                }
            }
    } catch (e: org.apache.commons.compress.PasswordRequiredException) {
        throw PasswordRequiredException(file.name)
    } catch (e: java.io.IOException) {
        if (password != null && password.isNotEmpty()) throw WrongPasswordException(file.name)
        throw e
    } finally {
        SevenZKeys.clear()
    }

    private fun listTar(file: File, name: String, access: FileAccess): List<ArchiveEntry> =
        access.openInput(file).buffered().use { raw ->
            decompress(name, raw).use { input ->
                TarArchiveInputStream(input).use { tar ->
                    val out = ArrayList<ArchiveEntry>()
                    var e = tar.nextEntry
                    while (e != null) {
                        out += ArchiveEntry(e.name, e.isDirectory, e.size, time = e.modTime.time)
                        e = tar.nextEntry
                    }
                    out
                }
            }
        }

    private fun decompress(name: String, input: InputStream): InputStream = when {
        name.endsWith(".gz") || name.endsWith(".tgz") -> GzipCompressorInputStream(input)
        name.endsWith(".bz2") || name.endsWith(".tbz2") -> BZip2CompressorInputStream(input)
        name.endsWith(".xz") || name.endsWith(".txz") -> XZCompressorInputStream(input)
        name.endsWith(".zst") || name.endsWith(".zstd") -> ZstdCompressorInputStream(input)
        name.endsWith(".lz4") -> FramedLZ4CompressorInputStream(input)
        else -> input
    }
}
