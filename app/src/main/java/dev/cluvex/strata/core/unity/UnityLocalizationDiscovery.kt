package dev.cluvex.strata.core.unity

import java.io.File

/** File candidate for an explicit localization-table discovery scan. */
data class UnityLocalizationScanRequest(
    val file: File,
    val identity: String = file.canonicalPath,
    val reportedSize: Long = file.length(),
    val reportedLastModified: Long = file.lastModified(),
    val scope: String = "localization",
)

data class UnityLocalizationEntrySummary(
    val entryName: String,
    val tableCount: Int,
    val termCount: Int,
    val languageCodes: List<String>,
)

data class UnityLocalizationCandidateResult(
    val path: String,
    val displayName: String,
    val size: Long,
    val parsed: Boolean,
    val entries: List<UnityLocalizationEntrySummary>,
    val reason: UnityTextUnavailableReason?,
) {
    val tableCount: Int get() = entries.sumOf { it.tableCount }
    val termCount: Int get() = entries.sumOf { it.termCount }
    val languageCodes: List<String> get() = entries.flatMap { it.languageCodes }.distinct()
}

data class UnityLocalizationScanProgress(
    val scannedFiles: Int,
    val totalFiles: Int,
    val scannedBytes: Long,
    val currentPath: String?,
    val elapsedMillis: Long,
)

data class UnityLocalizationDiscoveryReport(
    val candidates: List<UnityLocalizationCandidateResult>,
    val scannedFiles: Int,
    val totalFiles: Int,
    val scannedBytes: Long,
    val elapsedMillis: Long,
    val stoppedByBudget: Boolean,
)

data class UnityLocalizationScanBudget(
    val maxFiles: Int = 200,
    val maxSourceBytes: Long = 512L * 1024 * 1024,
    val maxElapsedMillis: Long = 120_000L,
)

/** Explicit, size-ranked and interrupt-cancellable scanner. */
object UnityLocalizationDiscovery {
    private data class CacheKey(
        val path: String,
        val size: Long,
        val lastModified: Long,
        val scope: String,
    )

    private val cache = object : LinkedHashMap<CacheKey, UnityLocalizationCandidateResult>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, UnityLocalizationCandidateResult>?,
        ): Boolean = size > 64
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    fun scan(
        requests: List<UnityLocalizationScanRequest>,
        cacheRoot: File,
        budget: UnityLocalizationScanBudget = UnityLocalizationScanBudget(),
        onProgress: (UnityLocalizationScanProgress) -> Unit = {},
    ): UnityLocalizationDiscoveryReport {
        require(budget.maxFiles > 0) { "Localization scan maxFiles must be positive" }
        require(budget.maxSourceBytes > 0) { "Localization scan byte budget must be positive" }
        require(budget.maxElapsedMillis > 0) { "Localization scan time budget must be positive" }
        cacheRoot.mkdirs()
        val ordered = requests.filter { it.file.isFile }.sortedBy { it.reportedSize }
        val started = System.currentTimeMillis()
        val results = ArrayList<UnityLocalizationCandidateResult>()
        var scannedBytes = 0L
        var stopped = false

        for (request in ordered) {
            ensureActive()
            val elapsed = System.currentTimeMillis() - started
            if (results.size >= budget.maxFiles ||
                elapsed >= budget.maxElapsedMillis ||
                request.reportedSize.coerceAtLeast(0L) > budget.maxSourceBytes - scannedBytes
            ) {
                stopped = true
                break
            }
            onProgress(
                UnityLocalizationScanProgress(
                    scannedFiles = results.size,
                    totalFiles = ordered.size,
                    scannedBytes = scannedBytes,
                    currentPath = request.identity,
                    elapsedMillis = elapsed,
                ),
            )
            val key = CacheKey(request.identity, request.reportedSize, request.reportedLastModified, request.scope)
            val result = synchronized(cache) { cache[key] } ?: scanOne(request, cacheRoot).also {
                synchronized(cache) { cache[key] = it }
            }
            results += result
            scannedBytes += request.reportedSize.coerceAtLeast(0L)
        }
        val elapsed = System.currentTimeMillis() - started
        onProgress(
            UnityLocalizationScanProgress(
                scannedFiles = results.size,
                totalFiles = ordered.size,
                scannedBytes = scannedBytes,
                currentPath = null,
                elapsedMillis = elapsed,
            ),
        )
        return UnityLocalizationDiscoveryReport(
            candidates = results,
            scannedFiles = results.size,
            totalFiles = ordered.size,
            scannedBytes = scannedBytes,
            elapsedMillis = elapsed,
            stoppedByBudget = stopped,
        )
    }

    private fun scanOne(
        request: UnityLocalizationScanRequest,
        cacheRoot: File,
    ): UnityLocalizationCandidateResult {
        ensureActive()
        val probe = UnityBundleReader.probe(request.file)
        if (!probe.bundleSupported) {
            return UnityLocalizationCandidateResult(
                path = request.identity,
                displayName = request.file.name,
                size = request.reportedSize,
                parsed = false,
                entries = emptyList(),
                reason = UnityTextUnavailableReason.NOT_SERIALIZED_FILE,
            )
        }
        val stage = File(
            cacheRoot,
            "localization-scan-${request.identity.hashCode().toUInt().toString(16)}-${System.nanoTime()}",
        )
        return try {
            UnityBundleReader.open(request.file, stage).use { document ->
                val found = ArrayList<UnityLocalizationEntrySummary>()
                var firstReason: UnityTextUnavailableReason? = null
                document.summary.entries.forEach { entry ->
                    ensureActive()
                    when (val inspection = document.inspectLocalizations(entry)) {
                        is UnityLocalizationInspection.Found -> found += UnityLocalizationEntrySummary(
                            entryName = entry.name,
                            tableCount = inspection.sources.size,
                            termCount = inspection.sources.sumOf { it.terms.size },
                            languageCodes = inspection.sources.flatMap { source ->
                                source.languages.map { it.code }
                            }.distinct(),
                        )
                        is UnityLocalizationInspection.Unavailable -> if (firstReason == null) {
                            firstReason = inspection.reason
                        }
                    }
                }
                UnityLocalizationCandidateResult(
                    path = request.identity,
                    displayName = request.file.name,
                    size = request.reportedSize,
                    parsed = true,
                    entries = found,
                    reason = if (found.isEmpty()) {
                        firstReason ?: UnityTextUnavailableReason.LOCALIZATION_TABLE_NOT_FOUND
                    } else {
                        null
                    },
                )
            }
        } catch (_: UnityOperationCancelledException) {
            throw UnityOperationCancelledException()
        } catch (_: UnityUnsupportedVersionException) {
            UnityLocalizationCandidateResult(
                request.identity,
                request.file.name,
                request.reportedSize,
                false,
                emptyList(),
                UnityTextUnavailableReason.UNSUPPORTED_VERSION,
            )
        } catch (_: Exception) {
            ensureActive()
            UnityLocalizationCandidateResult(
                request.identity,
                request.file.name,
                request.reportedSize,
                false,
                emptyList(),
                UnityTextUnavailableReason.DECODE_FAILED,
            )
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun ensureActive() {
        if (Thread.currentThread().isInterrupted) throw UnityOperationCancelledException()
    }
}
