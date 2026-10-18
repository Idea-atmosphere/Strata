package dev.cluvex.strata.core.archive

/** One containment edge from an extracted file back to its unchanged parent archive. */
data class ArchiveParent(
    val archivePath: String,
    val entryPath: String,
)
