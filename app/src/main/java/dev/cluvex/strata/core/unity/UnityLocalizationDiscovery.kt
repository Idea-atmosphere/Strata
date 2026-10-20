package dev.cluvex.strata.core.unity

import dev.cluvex.strata.core.archive.ArchiveParent
import java.io.File
import java.io.IOException

/** A stable open request retained with a discovery result, including every containing archive. */
data class UnityLocalizationOpenRequest(
    val file: File,
    val identity: String,
    val displayPath: String = identity,
    val parents: List<ArchiveParent> = emptyList(),
)

data class UnityLocalizationScanRequest(
    val file: File,
    val identity: String = file.absolutePath,
    val reportedSize: Long = file.length(),
    val reportedLastModified: Long = file.lastModified(),
    val scanFileLastModified: Long = file.lastModified(),
    val scope: String = "default",
    val openRequest: UnityLocalizationOpenRequest = UnityLocalizationOpenRequest(file, identity),
) {
    init {
        require(identity.isNotBlank()) { "Localization discovery identity must not be blank" }
        require(reportedSize >= 0L) { "Localization discovery size cannot be negative" }
        require(reportedLastModified >= 0L) { "Localization discovery timestamp cannot be negative" }
        require(scanFileLastModified >= 0L) { "Localization scan-file timestamp cannot be negative" }
        require(scope.isNotBlank()) { "Localization discovery scope must not be blank" }
    }
}

enum class UnityLocalizationCandidateReason {
    LOCALIZATION_TABLE_NOT_FOUND,
    NOT_A_UNITY_CONTAINER,
    SOURCE_MISSING,
    SOURCE_NOT_A_FILE,
    SOURCE_UNREADABLE,
    SOURCE_METADATA_MISMATCH,
    PROBE_FAILED,
    OPEN_FAILED,
    INSPECTION_FAILED,
    ARCHIVE_ENTRY_EXTRACTION_FAILED,
    ARCHIVE_ENTRY_DUPLICATE,
    ARCHIVE_ENTRY_UNSAFE_PATH,
}

enum class UnityLocalizationBudgetStopReason {
    FILE_LIMIT,
    SOURCE_BYTES,
    ELAPSED_TIME,
}

data class UnityLocalizationCandidateResult(
    val path: String,
    val fileSize: Long,
    val tableCount: Int,
    val termCount: Int,
    val languages: List<String>,
    val reason: UnityLocalizationCandidateReason? = null,
    val diagnostic: String? = null,
    val openRequest: UnityLocalizationOpenRequest? = null,
) {
    init {
        require(path.isNotBlank()) { "Localization candidate path must not be blank" }
        require(fileSize >= 0L) { "Localization candidate size cannot be negative" }
        require(tableCount >= 0 && termCount >= 0) { "Localization discovery counts cannot be negative" }
        require(tableCount > 0 || reason != null) { "A candidate without tables must carry a typed reason" }
    }

    val hasLocalizationTables: Boolean get() = tableCount > 0
}

data class UnityLocalizationScanBudget(
    val maxFiles: Int = 200,
    val maxArchiveEntries: Int = 20_000,
    val maxSourceBytes: Long = 512L * 1024 * 1024,
    val maxElapsedMillis: Long = 30_000,
) {
    init {
        require(maxFiles > 0) { "Localization scan file budget must be positive" }
        require(maxArchiveEntries > 0) { "Localization archive-entry budget must be positive" }
        require(maxSourceBytes > 0) { "Localization scan byte budget must be positive" }
        require(maxElapsedMillis > 0) { "Localization scan time budget must be positive" }
    }
}

data class UnityLocalizationScanProgress(
    val scannedFiles: Int,
    val totalFiles: Int,
    val scannedSourceBytes: Long,
    val currentPath: String?,
    val stoppedByBudget: Boolean = false,
    val budgetStopReason: UnityLocalizationBudgetStopReason? = null,
)

data class UnityLocalizationScanReport(
    val candidates: List<UnityLocalizationCandidateResult>,
    val scannedFiles: Int,
    val scannedSourceBytes: Long,
    val stoppedByBudget: Boolean,
    val budgetStopReason: UnityLocalizationBudgetStopReason? = null,
) {
    init {
        require(stoppedByBudget == (budgetStopReason != null)) {
            "Localization budget stop state and reason must agree"
        }
    }
}

data class UnityLocalizationDirectoryListing(
    val files: List<File>,
    val failures: List<UnityLocalizationCandidateResult>,
    val stoppedByBudget: Boolean,
    val budgetStopReason: UnityLocalizationBudgetStopReason?,
    val elapsedMillis: Long,
) {
    init {
        require(stoppedByBudget == (budgetStopReason != null)) {
            "Directory listing budget stop state and reason must agree"
        }
    }
}

/** Bounded directory walk used before the file scanner; traversal failures remain visible. */
object UnityLocalizationDirectoryDiscovery {
    fun list(
        directory: File,
        budget: UnityLocalizationScanBudget,
        onProgress: (UnityLocalizationScanProgress) -> Unit = {},
    ): UnityLocalizationDirectoryListing {
        if (!directory.exists()) throw IOException("Discovery folder does not exist")
        if (!directory.isDirectory) throw IOException("Discovery source is not a folder")
        val started = System.nanoTime()
        val files = ArrayList<File>(budget.maxFiles.coerceAtMost(1024))
        val failures = ArrayList<UnityLocalizationCandidateResult>()
        var selectedBytes = 0L
        var stopReason: UnityLocalizationBudgetStopReason? = null
        onProgress(UnityLocalizationScanProgress(0, budget.maxFiles, 0L, directory.absolutePath))
        val sequence = directory.walkTopDown().onFail { failed, error ->
            if (files.size + failures.size < budget.maxFiles) {
                failures += UnityLocalizationCandidateResult(
                    path = failed.absolutePath,
                    fileSize = 0L,
                    tableCount = 0,
                    termCount = 0,
                    languages = emptyList(),
                    reason = UnityLocalizationCandidateReason.SOURCE_UNREADABLE,
                    diagnostic = error.message ?: "Directory entry cannot be read",
                )
                onProgress(
                    UnityLocalizationScanProgress(
                        files.size + failures.size,
                        budget.maxFiles,
                        selectedBytes,
                        failed.absolutePath,
                    ),
                )
            } else {
                stopReason = UnityLocalizationBudgetStopReason.FILE_LIMIT
            }
        }
        for (candidate in sequence) {
            ensureUnityOperationActive()
            if (stopReason != null) break
            if (elapsedMillis(started) >= budget.maxElapsedMillis) {
                stopReason = UnityLocalizationBudgetStopReason.ELAPSED_TIME
                break
            }
            if (!candidate.isFile) continue
            if (files.size + failures.size >= budget.maxFiles) {
                stopReason = UnityLocalizationBudgetStopReason.FILE_LIMIT
                break
            }
            files += candidate
            val size = candidate.length().coerceAtLeast(0L)
            selectedBytes = if (size > Long.MAX_VALUE - selectedBytes) Long.MAX_VALUE else selectedBytes + size
            onProgress(
                UnityLocalizationScanProgress(
                    files.size + failures.size,
                    budget.maxFiles,
                    selectedBytes,
                    candidate.absolutePath,
                ),
            )
        }
        onProgress(
            UnityLocalizationScanProgress(
                files.size + failures.size,
                budget.maxFiles,
                selectedBytes,
                null,
                stoppedByBudget = stopReason != null,
                budgetStopReason = stopReason,
            ),
        )
        return UnityLocalizationDirectoryListing(
            files = files,
            failures = failures,
            stoppedByBudget = stopReason != null,
            budgetStopReason = stopReason,
            elapsedMillis = elapsedMillis(started),
        )
    }

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}

/** Bounded, cancellable folder-scope scanner with one typed result per visited input. */
object UnityLocalizationDiscovery {
    private data class CacheKey(
        val identity: String,
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
    ): UnityLocalizationScanReport {
        val ordered = requests.sortedWith(compareBy<UnityLocalizationScanRequest> { it.reportedSize }.thenBy { it.identity })
        val started = System.nanoTime()
        var scannedFiles = 0
        var scannedBytes = 0L
        var stopReason: UnityLocalizationBudgetStopReason? = null
        val results = ArrayList<UnityLocalizationCandidateResult>(ordered.size)

        for (request in ordered) {
            ensureUnityOperationActive()
            stopReason = when {
                scannedFiles >= budget.maxFiles -> UnityLocalizationBudgetStopReason.FILE_LIMIT
                elapsedMillis(started) >= budget.maxElapsedMillis -> UnityLocalizationBudgetStopReason.ELAPSED_TIME
                request.reportedSize > budget.maxSourceBytes - scannedBytes -> UnityLocalizationBudgetStopReason.SOURCE_BYTES
                else -> null
            }
            if (stopReason != null) break

            onProgress(
                UnityLocalizationScanProgress(scannedFiles, ordered.size, scannedBytes, request.identity),
            )
            val key = CacheKey(request.identity, request.reportedSize, request.reportedLastModified, request.scope)
            val sourceStillMatches = request.file.isFile &&
                request.file.canRead() &&
                request.file.length() == request.reportedSize &&
                request.file.lastModified() == request.scanFileLastModified
            val cached = if (sourceStillMatches) synchronized(cache) { cache[key] } else null
            val result = if (cached != null) {
                cached.copy(path = request.identity, fileSize = request.reportedSize, openRequest = request.openRequest)
            } else {
                scanOne(request, cacheRoot).also { inspected ->
                    if (inspected.isCacheable()) synchronized(cache) { cache[key] = inspected }
                }
            }
            results += result
            scannedFiles++
            scannedBytes += request.reportedSize
            onProgress(
                UnityLocalizationScanProgress(scannedFiles, ordered.size, scannedBytes, request.identity),
            )
        }

        onProgress(
            UnityLocalizationScanProgress(
                scannedFiles,
                ordered.size,
                scannedBytes,
                null,
                stoppedByBudget = stopReason != null,
                budgetStopReason = stopReason,
            ),
        )
        return UnityLocalizationScanReport(
            candidates = results.sortedWith(
                compareByDescending<UnityLocalizationCandidateResult> { it.hasLocalizationTables }
                    .thenBy { it.fileSize }
                    .thenBy { it.path },
            ),
            scannedFiles = scannedFiles,
            scannedSourceBytes = scannedBytes,
            stoppedByBudget = stopReason != null,
            budgetStopReason = stopReason,
        )
    }

    private fun scanOne(request: UnityLocalizationScanRequest, cacheRoot: File): UnityLocalizationCandidateResult {
        val file = request.file
        val inputFailure = when {
            !file.exists() -> UnityLocalizationCandidateReason.SOURCE_MISSING to "Source does not exist"
            !file.isFile -> UnityLocalizationCandidateReason.SOURCE_NOT_A_FILE to "Source is not a regular file"
            !file.canRead() -> UnityLocalizationCandidateReason.SOURCE_UNREADABLE to "Source cannot be read"
            file.length() != request.reportedSize -> UnityLocalizationCandidateReason.SOURCE_METADATA_MISMATCH to
                "Source size changed from ${request.reportedSize} to ${file.length()} bytes"
            file.lastModified() != request.scanFileLastModified -> UnityLocalizationCandidateReason.SOURCE_METADATA_MISMATCH to
                "Source timestamp changed during discovery"
            else -> null
        }
        if (inputFailure != null) return failed(request, inputFailure.first, inputFailure.second)

        val probe = try {
            UnityBundleReader.probe(file)
        } catch (error: Exception) {
            ensureUnityOperationActive()
            return failed(request, UnityLocalizationCandidateReason.PROBE_FAILED, error.message ?: "Unity probe failed")
        }
        if (!probe.bundleSupported) {
            return failed(
                request,
                UnityLocalizationCandidateReason.NOT_A_UNITY_CONTAINER,
                probe.note ?: "Content is not a supported Unity container",
            )
        }

        val scanCache = File(
            cacheRoot,
            "discovery-${request.identity.hashCode().toUInt().toString(16)}-${System.nanoTime()}",
        )
        if (!scanCache.mkdirs() && !scanCache.isDirectory) {
            return failed(request, UnityLocalizationCandidateReason.OPEN_FAILED, "Discovery cache could not be created")
        }
        val document = try {
            UnityBundleReader.open(file, scanCache)
        } catch (error: UnityOperationCancelledException) {
            scanCache.deleteRecursively()
            throw error
        } catch (error: Exception) {
            scanCache.deleteRecursively()
            ensureUnityOperationActive()
            return failed(
                request,
                UnityLocalizationCandidateReason.OPEN_FAILED,
                error.message ?: "Unity container could not be opened",
            )
        }
        return try {
            val inspected = document.use {
                var tableCount = 0
                var termCount = 0
                val languages = LinkedHashSet<String>()
                var firstUnavailable: UnityLocalizationInspection.Unavailable? = null
                document.summary.entries.forEach { entry ->
                    ensureUnityOperationActive()
                    when (val inspection = document.inspectLocalizations(entry)) {
                        is UnityLocalizationInspection.Found -> inspection.sources.forEach { source ->
                            tableCount++
                            termCount += source.terms.size
                            source.languages.forEach { languages += it.code }
                        }
                        is UnityLocalizationInspection.Unavailable -> if (firstUnavailable == null) firstUnavailable = inspection
                    }
                }
                if (tableCount > 0) {
                    UnityLocalizationCandidateResult(
                        path = request.identity,
                        fileSize = request.reportedSize,
                        tableCount = tableCount,
                        termCount = termCount,
                        languages = languages.toList(),
                        openRequest = request.openRequest,
                    )
                } else {
                    UnityLocalizationCandidateResult(
                        path = request.identity,
                        fileSize = request.reportedSize,
                        tableCount = 0,
                        termCount = 0,
                        languages = emptyList(),
                        reason = UnityLocalizationCandidateReason.LOCALIZATION_TABLE_NOT_FOUND,
                        diagnostic = firstUnavailable?.diagnostic ?: "No structurally valid localization table was found",
                        openRequest = request.openRequest,
                    )
                }
            }
            if (
                !file.isFile ||
                file.length() != request.reportedSize ||
                file.lastModified() != request.scanFileLastModified
            ) {
                failed(
                    request,
                    UnityLocalizationCandidateReason.SOURCE_METADATA_MISMATCH,
                    "Source changed while localization discovery was reading it",
                )
            } else {
                inspected
            }
        } catch (error: UnityOperationCancelledException) {
            throw error
        } catch (error: Exception) {
            ensureUnityOperationActive()
            failed(
                request,
                UnityLocalizationCandidateReason.INSPECTION_FAILED,
                error.message ?: "Localization inspection failed",
            )
        } finally {
            scanCache.deleteRecursively()
        }
    }

    private fun UnityLocalizationCandidateResult.isCacheable(): Boolean =
        hasLocalizationTables ||
            reason == UnityLocalizationCandidateReason.LOCALIZATION_TABLE_NOT_FOUND ||
            reason == UnityLocalizationCandidateReason.NOT_A_UNITY_CONTAINER

    private fun failed(
        request: UnityLocalizationScanRequest,
        reason: UnityLocalizationCandidateReason,
        diagnostic: String,
    ) = UnityLocalizationCandidateResult(
        path = request.identity,
        fileSize = request.reportedSize,
        tableCount = 0,
        termCount = 0,
        languages = emptyList(),
        reason = reason,
        diagnostic = diagnostic,
        openRequest = request.openRequest,
    )

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
