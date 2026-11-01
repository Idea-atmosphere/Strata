package dev.cluvex.strata.core.unity

/**
 * Phase 5 (pure Kotlin, no Android deps, public like the Phase 1
 * mapper and the Phase 2 baker): generalise the Bendy static-prefab
 * repoint as a size-preserving serialized-file patch, so any Unity
 * game whose TMP text components point at a static font asset can
 * be pointed at the Persian-patched dynamic fallback instead.
 *
 * What Bendy proved, and what this module reproduces exactly:
 * - A TMP text component's font is a PPtr (fileID, pathID) into an
 *   external serialized file. The external itself is identified in
 *   the prefab file's METADATA by its 32-character lowercase-hex
 *   GUID, stored as ASCII (Bendy subtitle file: headerSize 48,
 *   dataOffset 1200, GUIDs at file offsets 1052 / 1106 / 1160,
 *   all inside [headerSize, dataOffset)).
 * - Repointing the font therefore needs TWO size-preserving edits,
 *   and neither changes any object size, so the object table,
 *   starts and sizes stay valid and the file still parses:
 *   1. Replace the source external GUID with the target fallback
 *      GUID, in the metadata range only. Bendy warnings needed
 *      ONLY this: subtitle GUID 2222297e935fd6d44b4fcd284c9c7093
 *      (CaviarDreams_Bold SDF) -> 2e498d1c8094910479dc3e1b768306a4
 *      (LiberationSans SDF - Fallback) reproduces the delivered
 *      warning prefabs byte-for-byte (CRC FE660554 / 5C9DB0A6).
 *   2. Optionally remap ONE PPtr: the font's MATERIAL. Bendy
 *      subtitles needed this in addition: inside MonoBehaviour
 *      object #16 (632 bytes, the TMP_Text component), the font
 *      PPtr is (3, 3) at object offset 228 and the material PPtr
 *      is (4, 1) at object offset 240; remapping (4, 1) -> (3, 1)
 *      (same path, the fallback file's Material) plus the GUID
 *      replacement reproduces the delivered subtitle prefab
 *      byte-for-byte (CRC C9BB2648). The same GUID replacement
 *      with the primary GUID 8f586378b4e144a9851e7b34d9b748ee
 *      reproduces the chain variant (CRC 23513DA6).
 *
 * Generalisation rules (v1, fail fast, never silently ship a
 * half-repointed prefab):
 * - GUIDs must be exactly 32 lowercase hex characters. Unity
 *   stores external GUIDs lowercase; an uppercase or short GUID
 *   is a caller bug, not a case to normalise silently.
 * - GUID replacement is restricted to the metadata range
 *   [metadataStart, metadataEnd). A GUID-looking string inside
 *   object DATA (for example a text field) must never be touched.
 *   Zero matches in metadata is an error, not a no-op: it means
 *   the picked file does not reference the source font at all.
 * - A PPtr is 12 bytes, little-endian: int32 fileID followed by
 *   int64 pathID, at a 4-byte-aligned offset inside ONE object.
 *   The caller scopes the search to MonoBehaviour objects
 *   (classId 114: TMP_Text is a MonoBehaviour script component)
 *   and this module requires EXACTLY ONE match across all of
 *   them. Zero matches means the material reference is not where
 *   the caller thought; more than one means the pattern is
 *   ambiguous and picking one would repoint the wrong component.
 *   Both are errors. Bendy's subtitle material PPtr (4, 1) is
 *   unique across the whole file, which is why the exactly-one
 *   rule is usable in practice, not just in theory.
 * - This module never parses serialized-file headers itself: the
 *   caller (Phase 3 UnityBaseData / SerializedFileReader) supplies
 *   headerSize, dataOffset and object bytes, and is responsible
 *   for rebuilding the file when a PPtr inside an object changes
 *   (SerializedFileIndex.rebuild, then GUID replacement on the
 *   rebuilt bytes, since rebuild copies the ORIGINAL metadata
 *   verbatim and would otherwise drop the GUID edit). The
 *   combined helper [repointPrefabFile] documents that order
 *   for the pure byte-level case where the caller already has
 *   the full file bytes and the object's file offset.
 *
 * Explicitly NOT in v1:
 * - Repacking the repointed prefab back into a bundle (the user
 *   installs the exported file the same way Bendy prefabs were
 *   installed: MT Manager into the installed game, or Strata's
 *   existing repack flow for bundle entries).
 * - Decoding MonoBehaviour fields via type trees: stripped
 *   MonoBehaviour objects have no tree in the file, so a
 *   semantic "find the font field" decode is impossible without
 *   the game's managed scripts. The PPtr-pattern scan is the
 *   honest replacement, with the exactly-one rule as its guard.
 * - Multi-object / multi-font prefabs where the same PPtr
 *   pattern legitimately appears twice: blocked with a clear
 *   error until a per-object scoping API is added.
 */
public object UnityPersianPrefabRepoint {

    /** One PPtr remap: from (fileID, pathID) to (fileID, pathID). */
    public data class PptrRepoint(
        val fromFileId: Int,
        val fromPathId: Long,
        val toFileId: Int,
        val toPathId: Long,
    )

    /** Result of a GUID replacement: patched bytes + file offsets. */
    public data class GuidRepointResult(
        val bytes: ByteArray,
        /** File offsets of the replaced GUIDs (metadata range). */
        val offsets: List<Int>,
    )

    /** Result of a PPtr replacement inside one object's bytes. */
    public data class PptrRepointResult(
        val bytes: ByteArray,
        /** Offsets inside the OBJECT bytes (not file offsets). */
        val offsets: List<Int>,
    )

    /** A candidate PPtr found by [scanPptrs] (discovery only). */
    public data class PptrCandidate(
        val offset: Int,
        val fileId: Int,
        val pathId: Long,
    )

    /** Combined file-level result (GUID + optional PPtr). */
    public data class PrefabRepointResult(
        val bytes: ByteArray,
        val guidOffsets: List<Int>,
        /** File offsets of the repointed PPtrs (empty: GUID only). */
        val pptrFileOffsets: List<Int>,
    )

    private const val GUID_LENGTH = 32
    private const val PPTR_SIZE = 12

    /** Validates a Unity external GUID: exactly 32 lowercase hex chars. */
    public fun validateGuid(guid: String) {
        require(guid.length == GUID_LENGTH) {
            "External GUID must be exactly 32 characters, got ${guid.length}"
        }
        require(guid.all { it in '0'..'9' || it in 'a'..'f' }) {
            "External GUID must be lowercase hex (0-9, a-f): $guid"
        }
    }

    /**
     * Finds non-overlapping ASCII occurrences of [guid] inside
     * [bytes] restricted to [rangeStart, rangeEnd). The range is
     * normally the metadata range [headerSize, dataOffset).
     */
    public fun findGuidOccurrences(
        bytes: ByteArray,
        rangeStart: Int,
        rangeEnd: Int,
        guid: String,
    ): List<Int> {
        validateGuid(guid)
        require(rangeStart in 0..bytes.size) { "GUID range start out of bounds: $rangeStart" }
        require(rangeEnd in rangeStart..bytes.size) { "GUID range end out of bounds: $rangeEnd" }
        val needle = guid.toByteArray(Charsets.US_ASCII)
        val found = ArrayList<Int>()
        var i = rangeStart
        val last = rangeEnd - needle.size
        while (i <= last) {
            var match = true
            for (k in needle.indices) {
                if (bytes[i + k] != needle[k]) {
                    match = false
                    break
                }
            }
            if (match) {
                found += i
                i += needle.size
            } else {
                i++
            }
        }
        return found
    }

    /**
     * Replaces every occurrence of [sourceGuid] with [targetGuid]
     * inside the metadata range. Throws [IllegalArgumentException]
     * when the GUIDs are invalid, identical, or the source GUID
     * does not occur in the range at all.
     */
    public fun repointExternalGuid(
        bytes: ByteArray,
        metadataStart: Int,
        metadataEnd: Int,
        sourceGuid: String,
        targetGuid: String,
    ): GuidRepointResult {
        validateGuid(sourceGuid)
        validateGuid(targetGuid)
        require(sourceGuid != targetGuid) {
            "Source and target external GUIDs are identical: $sourceGuid"
        }
        val offsets = findGuidOccurrences(bytes, metadataStart, metadataEnd, sourceGuid)
        require(offsets.isNotEmpty()) {
            "Source external GUID $sourceGuid not found in metadata range [$metadataStart, $metadataEnd)"
        }
        val out = bytes.copyOf()
        val replacement = targetGuid.toByteArray(Charsets.US_ASCII)
        for (offset in offsets) {
            replacement.copyInto(out, offset)
        }
        return GuidRepointResult(out, offsets)
    }

    /**
     * Finds 12-byte little-endian PPtr patterns (int32 fileID,
     * int64 pathID) equal to ([fromFileId], [fromPathId]) at
     * 4-byte-aligned offsets inside [objectBytes].
     */
    public fun findPptrOccurrences(
        objectBytes: ByteArray,
        fromFileId: Int,
        fromPathId: Long,
    ): List<Int> {
        val found = ArrayList<Int>()
        var offset = 0
        while (offset + PPTR_SIZE <= objectBytes.size) {
            if (readI32(objectBytes, offset) == fromFileId &&
                readI64(objectBytes, offset + 4) == fromPathId
            ) {
                found += offset
            }
            offset += 4
        }
        return found
    }

    /**
     * Replaces every ([repoint.fromFileId], [repoint.fromPathId])
     * PPtr inside [objectBytes] with the target pair. Throws when
     * there is no match: a PPtr repoint that changes nothing is
     * a caller error, not a successful GUID-only repoint (use
     * [repointExternalGuid] alone for that case).
     */
    public fun repointPptr(
        objectBytes: ByteArray,
        repoint: PptrRepoint,
    ): PptrRepointResult {
        val offsets = findPptrOccurrences(objectBytes, repoint.fromFileId, repoint.fromPathId)
        require(offsets.isNotEmpty()) {
            "PPtr (${repoint.fromFileId}, ${repoint.fromPathId}) not found in object bytes"
        }
        val out = objectBytes.copyOf()
        for (offset in offsets) {
            writeI32(out, offset, repoint.toFileId)
            writeI64(out, offset + 4, repoint.toPathId)
        }
        return PptrRepointResult(out, offsets)
    }

    /**
     * Discovery helper for a MonoBehaviour object's bytes: lists
     * every 4-byte-aligned 12-byte pattern whose fileID is in
     * [0, 1000] and whose pathID is positive. This is a CANDIDATE
     * list only: arbitrary int+long pairs in script data can look
     * like PPtrs, so the caller must confirm a candidate against
     * a known font/material reference (for example the game's
     * static font asset fileID/pathID) before repointing it.
     * Never repoint every candidate: that would corrupt fields
     * that merely resemble PPtrs.
     */
    public fun scanPptrs(objectBytes: ByteArray): List<PptrCandidate> {
        val found = ArrayList<PptrCandidate>()
        var offset = 0
        while (offset + PPTR_SIZE <= objectBytes.size) {
            val fileId = readI32(objectBytes, offset)
            val pathId = readI64(objectBytes, offset + 4)
            if (fileId in 0..1000 && pathId > 0L) {
                found += PptrCandidate(offset, fileId, pathId)
            }
            offset += 4
        }
        return found
    }

    /**
     * Combined file-level repoint for the pure byte-level case:
     * GUID replacement in [metadataStart, metadataEnd), plus,
     * when [pptr] is non-null, a PPtr replacement inside the
     * single object whose file range is
     * [dataOffset + objectStart, dataOffset + objectStart +
     * objectSize). The PPtr replacement inside that object must
     * match exactly once; callers scanning several objects must
     * enforce the exactly-one rule across all of them themselves
     * (the ViewModel does: it collects matches over every
     * MonoBehaviour object and requires exactly one in total).
     * Sizes never change, so [dataOffset] and the object table
     * stay valid in the result.
     */
    public fun repointPrefabFile(
        fileBytes: ByteArray,
        metadataStart: Int,
        metadataEnd: Int,
        dataOffset: Int,
        objectStart: Int,
        objectSize: Int,
        sourceGuid: String,
        targetGuid: String,
        pptr: PptrRepoint?,
    ): PrefabRepointResult {
        require(metadataEnd in metadataStart..fileBytes.size) {
            "Metadata range [$metadataStart, $metadataEnd) is outside the file"
        }
        require(dataOffset in 0..fileBytes.size) { "Data offset out of bounds: $dataOffset" }
        val objectFileStart = dataOffset + objectStart
        require(objectFileStart in 0..fileBytes.size) { "Object start out of bounds: $objectFileStart" }
        require(objectSize >= 0 && objectFileStart + objectSize <= fileBytes.size) {
            "Object range [$objectFileStart, ${objectFileStart + objectSize}) is outside the file"
        }
        var bytes = fileBytes
        var pptrFileOffsets = emptyList<Int>()
        if (pptr != null) {
            val objectBytes = bytes.copyOfRange(objectFileStart, objectFileStart + objectSize)
            val occurrences = findPptrOccurrences(objectBytes, pptr.fromFileId, pptr.fromPathId)
            require(occurrences.size == 1) {
                "PPtr (${pptr.fromFileId}, ${pptr.fromPathId}) must match exactly once in the object, matched ${occurrences.size} times"
            }
            val patched = repointPptr(objectBytes, pptr)
            val out = bytes.copyOf()
            patched.bytes.copyInto(out, objectFileStart)
            bytes = out
            pptrFileOffsets = patched.offsets.map { objectFileStart + it }
        }
        val guidResult = repointExternalGuid(bytes, metadataStart, metadataEnd, sourceGuid, targetGuid)
        return PrefabRepointResult(guidResult.bytes, guidResult.offsets, pptrFileOffsets)
    }

    private fun readI32(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readI64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    private fun writeI32(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun writeI64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }
}
