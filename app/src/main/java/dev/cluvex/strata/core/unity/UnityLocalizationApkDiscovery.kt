package dev.cluvex.strata.core.unity

import dev.cluvex.strata.core.archive.ArchiveParent
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Staged APK candidates stay valid until this bounded discovery session is closed. */
class UnityLocalizationApkScanSession internal constructor(
    val apk: File,
    val requests: List<UnityLocalizationScanRequest>,
    val failures: List<UnityLocalizationCandidateResult>,
    val stoppedByBudget: Boolean,
    val budgetStopReason: UnityLocalizationBudgetStopReason?,
    val preparationElapsedMillis: Long,
    private val stagingRoot: File,
    private val ownedApk: File? = null,
) : Closeable {
    init {
        require(stoppedByBudget == (budgetStopReason != null)) {
            "APK discovery budget state and reason must agree"
        }
    }

    override fun close() {
        stagingRoot.deleteRecursively()
        ownedApk?.delete()
    }

    fun merge(scannerReport: UnityLocalizationScanReport): UnityLocalizationScanReport {
        val stop = budgetStopReason ?: scannerReport.budgetStopReason
        return UnityLocalizationScanReport(
            candidates = (scannerReport.candidates + failures).sortedWith(
                compareByDescending<UnityLocalizationCandidateResult> { it.hasLocalizationTables }
                    .thenBy { it.fileSize }
                    .thenBy { it.path },
            ),
            scannedFiles = scannerReport.scannedFiles + failures.size,
            scannedSourceBytes = failures.fold(scannerReport.scannedSourceBytes) { total, failure ->
                if (failure.fileSize > Long.MAX_VALUE - total) Long.MAX_VALUE else total + failure.fileSize
            },
            stoppedByBudget = stop != null,
            budgetStopReason = stop,
        )
    }
}

/** APK/ZIP adapter that lists centrally, sniffs shapes, and stages only bounded candidates. */
object UnityLocalizationApkDiscovery {
    private const val SNIFF_BYTES = 64
    private const val COPY_BUFFER = 64 * 1024

    fun prepare(
        apk: File,
        cacheRoot: File,
        scope: String,
        budget: UnityLocalizationScanBudget = UnityLocalizationScanBudget(),
        deleteSourceOnClose: Boolean = false,
        onProgress: (UnityLocalizationScanProgress) -> Unit = {},
    ): UnityLocalizationApkScanSession {
        require(scope.isNotBlank()) { "APK discovery scope must not be blank" }
        if (!apk.exists()) throw IOException("APK source does not exist: ${apk.name}")
        if (!apk.isFile) throw IOException("APK source is not a regular file: ${apk.name}")
        if (!apk.canRead()) throw IOException("APK source cannot be read: ${apk.name}")

        val canonicalApk = apk.canonicalFile
        val sourceSize = canonicalApk.length()
        val sourceLastModified = canonicalApk.lastModified()
        val root = File(
            cacheRoot,
            "unity-apk-discovery-${apk.name.hashCode().toUInt().toString(16)}-${System.nanoTime()}",
        )
        if (!root.mkdirs()) throw IOException("Could not create APK discovery staging directory")
        val started = System.nanoTime()
        val requests = ArrayList<UnityLocalizationScanRequest>()
        val failures = ArrayList<UnityLocalizationCandidateResult>()
        var stagedBytes = 0L
        var stopReason: UnityLocalizationBudgetStopReason? = null

        try {
            ZipFile(canonicalApk).use { zip ->
                val candidates = ArrayList<ZipEntry>()
                val seenEntryNames = HashSet<String>()
                val duplicateEntryNames = HashSet<String>()
                var visitedEntries = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    ensureUnityOperationActive()
                    if (elapsedMillis(started) >= budget.maxElapsedMillis) {
                        stopReason = UnityLocalizationBudgetStopReason.ELAPSED_TIME
                        break
                    }
                    if (visitedEntries >= budget.maxArchiveEntries) {
                        stopReason = UnityLocalizationBudgetStopReason.FILE_LIMIT
                        break
                    }
                    val entry = entries.nextElement()
                    visitedEntries++
                    if (visitedEntries == 1 || visitedEntries % 32 == 0) {
                        onProgress(
                            UnityLocalizationScanProgress(
                                visitedEntries,
                                budget.maxArchiveEntries,
                                stagedBytes,
                                "${canonicalApk.path}!/${normalizeEntry(entry.name)}",
                            ),
                        )
                    }
                    if (entry.isDirectory || entry.size <= 0L) continue
                    val normalizedName = normalizeEntry(entry.name)
                    val safePath = isSafeArchiveEntryPath(entry.name)
                    if (!seenEntryNames.add(normalizedName)) {
                        candidates.removeAll { normalizeEntry(it.name) == normalizedName }
                        if (duplicateEntryNames.add(normalizedName)) {
                            if (candidates.size + failures.size >= budget.maxFiles) {
                                stopReason = UnityLocalizationBudgetStopReason.FILE_LIMIT
                                break
                            }
                            failures += UnityLocalizationCandidateResult(
                                path = "${canonicalApk.path}!/$normalizedName",
                                fileSize = safeSize(entry),
                                tableCount = 0,
                                termCount = 0,
                                languages = emptyList(),
                                reason = UnityLocalizationCandidateReason.ARCHIVE_ENTRY_DUPLICATE,
                                diagnostic = "APK contains a duplicate entry path",
                            )
                        }
                        continue
                    }

                    var sniffFailure: IOException? = null
                    val contentMatch = try {
                        zip.getInputStream(entry).buffered().use { input ->
                            val prefix = ByteArray(SNIFF_BYTES)
                            var count = 0
                            while (count < prefix.size) {
                                val read = input.read(prefix, count, prefix.size - count)
                                if (read < 0) break
                                count += read
                            }
                            looksLikeUnityContent(prefix, count, entry.size)
                        }
                    } catch (error: IOException) {
                        sniffFailure = error
                        false
                    }
                    if (contentMatch || sniffFailure != null) {
                        if (candidates.size + failures.size >= budget.maxFiles) {
                            stopReason = UnityLocalizationBudgetStopReason.FILE_LIMIT
                            break
                        }
                        val identity = "${canonicalApk.path}!/$normalizedName"
                        when {
                            contentMatch && safePath -> candidates += entry
                            contentMatch -> failures += UnityLocalizationCandidateResult(
                                path = identity,
                                fileSize = safeSize(entry),
                                tableCount = 0,
                                termCount = 0,
                                languages = emptyList(),
                                reason = UnityLocalizationCandidateReason.ARCHIVE_ENTRY_UNSAFE_PATH,
                                diagnostic = "APK entry path is unsafe or ambiguous",
                            )
                            else -> failures += UnityLocalizationCandidateResult(
                                path = identity,
                                fileSize = safeSize(entry),
                                tableCount = 0,
                                termCount = 0,
                                languages = emptyList(),
                                reason = UnityLocalizationCandidateReason.ARCHIVE_ENTRY_EXTRACTION_FAILED,
                                diagnostic = sniffFailure?.message ?: "APK entry could not be inspected",
                            )
                        }
                    }
                }
                candidates.sortWith(compareBy<ZipEntry> { safeSize(it) }.thenBy { it.name })
                val listingStopReason = stopReason
                var stagingStopReason = listingStopReason
                    .takeIf { it == UnityLocalizationBudgetStopReason.ELAPSED_TIME }

                onProgress(UnityLocalizationScanProgress(0, candidates.size + failures.size, 0L, null))
                for (entry in candidates) {
                    ensureUnityOperationActive()
                    if (stagingStopReason != null) break
                    val size = safeSize(entry)
                    stagingStopReason = when {
                        requests.size + failures.size >= budget.maxFiles -> UnityLocalizationBudgetStopReason.FILE_LIMIT
                        elapsedMillis(started) >= budget.maxElapsedMillis -> UnityLocalizationBudgetStopReason.ELAPSED_TIME
                        size > budget.maxSourceBytes - stagedBytes -> UnityLocalizationBudgetStopReason.SOURCE_BYTES
                        else -> null
                    }
                    if (stagingStopReason != null) break

                    val identity = "${canonicalApk.path}!/${normalizeEntry(entry.name)}"
                    onProgress(
                        UnityLocalizationScanProgress(
                            requests.size + failures.size,
                            candidates.size + failures.size,
                            stagedBytes,
                            identity,
                        ),
                    )
                    val staged = File(root, stagedName(entry))
                    try {
                        stageEntry(zip, entry, staged, size)
                        val parent = ArchiveParent(canonicalApk.path, normalizeEntry(entry.name))
                        val open = UnityLocalizationOpenRequest(
                            file = staged,
                            identity = identity,
                            displayPath = identity,
                            parents = listOf(parent),
                        )
                        requests += UnityLocalizationScanRequest(
                            file = staged,
                            identity = identity,
                            reportedSize = size,
                            reportedLastModified = sourceLastModified,
                            scanFileLastModified = staged.lastModified(),
                            scope = scope,
                            openRequest = open,
                        )
                    } catch (error: Exception) {
                        staged.delete()
                        ensureUnityOperationActive()
                        failures += UnityLocalizationCandidateResult(
                            path = identity,
                            fileSize = size,
                            tableCount = 0,
                            termCount = 0,
                            languages = emptyList(),
                            reason = UnityLocalizationCandidateReason.ARCHIVE_ENTRY_EXTRACTION_FAILED,
                            diagnostic = error.message ?: "APK entry extraction failed",
                            openRequest = null,
                        )
                    }
                    stagedBytes += size
                    onProgress(
                        UnityLocalizationScanProgress(
                            requests.size + failures.size,
                            candidates.size + failures.size,
                            stagedBytes,
                            identity,
                        ),
                    )
                }
                stopReason = listingStopReason ?: stagingStopReason
            }
            if (
                !canonicalApk.isFile ||
                canonicalApk.length() != sourceSize ||
                canonicalApk.lastModified() != sourceLastModified
            ) {
                throw IOException("APK source changed during localization discovery")
            }
            onProgress(
                UnityLocalizationScanProgress(
                    requests.size + failures.size,
                    requests.size + failures.size,
                    stagedBytes,
                    null,
                    stoppedByBudget = stopReason != null,
                    budgetStopReason = stopReason,
                ),
            )
            return UnityLocalizationApkScanSession(
                apk = canonicalApk,
                requests = requests,
                failures = failures,
                stoppedByBudget = stopReason != null,
                budgetStopReason = stopReason,
                preparationElapsedMillis = elapsedMillis(started),
                stagingRoot = root,
                ownedApk = canonicalApk.takeIf { deleteSourceOnClose },
            )
        } catch (error: Throwable) {
            root.deleteRecursively()
            if (deleteSourceOnClose) canonicalApk.delete()
            throw error
        }
    }

    private fun stageEntry(zip: ZipFile, entry: ZipEntry, output: File, expectedSize: Long) {
        if (expectedSize <= 0L) throw IOException("APK entry has an invalid uncompressed size")
        val temporary = File(output.parentFile, ".${output.name}.${System.nanoTime()}.tmp")
        var written = 0L
        try {
            zip.getInputStream(entry).buffered(COPY_BUFFER).use { input ->
                FileOutputStream(temporary).use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        ensureUnityOperationActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > expectedSize) throw IOException("APK entry exceeded its declared size")
                        out.write(buffer, 0, read)
                    }
                    out.fd.sync()
                }
            }
            if (written != expectedSize || temporary.length() != expectedSize) {
                throw IOException("APK entry failed staged size verification")
            }
            if (output.exists() || !temporary.renameTo(output)) {
                throw IOException("Could not atomically stage APK entry")
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    internal fun looksLikeUnityContent(prefix: ByteArray, count: Int, declaredSize: Long): Boolean {
        if (count <= 0 || declaredSize <= 0) return false
        val signature = prefix.copyOf(count).toString(Charsets.ISO_8859_1)
        if (
            signature.startsWith("UnityFS\u0000") ||
            signature.startsWith("UnityRaw\u0000") ||
            signature.startsWith("UnityWeb\u0000")
        ) {
            return true
        }
        if (count < 20) return false
        return try {
            val input = DataInputStream(prefix.copyOf(count).inputStream())
            val metadataSize = input.readInt().toLong() and 0xffff_ffffL
            val fileSize = input.readInt().toLong() and 0xffff_ffffL
            val version = input.readInt()
            val dataOffset = input.readInt().toLong() and 0xffff_ffffL
            metadataSize in 1..declaredSize &&
                version in 5..100 &&
                dataOffset in 16..declaredSize &&
                (fileSize == 0L || fileSize == declaredSize || version >= 22)
        } catch (_: Exception) {
            false
        }
    }

    private fun stagedName(entry: ZipEntry): String {
        val clean = normalizeEntry(entry.name)
        val extension = clean.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        val suffix = extension.takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }?.let { ".$it" } ?: ".bin"
        return "candidate-${clean.hashCode().toUInt().toString(16)}$suffix"
    }

    private fun safeSize(entry: ZipEntry): Long = entry.size.takeIf { it >= 0L } ?: Long.MAX_VALUE

    private fun isSafeArchiveEntryPath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            '\\' !in path &&
            '\u0000' !in path &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun normalizeEntry(name: String): String = name.replace('\\', '/').trimStart('/')
    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
