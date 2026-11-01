package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class UnityPersianPrefabRepointTest {

    private val sourceGuid = "2222297e935fd6d44b4fcd284c9c7093"
    private val targetGuid = "2e498d1c8094910479dc3e1b768306a4"

    private fun guidBytes(guid: String): ByteArray = guid.toByteArray(Charsets.US_ASCII)

    private fun pptrBytes(fileId: Int, pathId: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 4) out[i] = ((fileId ushr (8 * i)) and 0xFF).toByte()
        for (i in 0 until 8) out[4 + i] = ((pathId ushr (8 * i)) and 0xFF).toByte()
        return out
    }

    @Test
    fun guidReplacementIsMetadataOnlyAndReportsOffsets() {
        // Layout: [metadata: source GUID at 4] [data: same GUID at 40].
        // Only the metadata copy may change: a GUID-looking string in
        // object data is text, not an external reference.
        val bytes = ByteArray(80)
        guidBytes(sourceGuid).copyInto(bytes, 4)
        guidBytes(sourceGuid).copyInto(bytes, 40)
        val result = UnityPersianPrefabRepoint.repointExternalGuid(bytes, 0, 36, sourceGuid, targetGuid)
        assertThat(result.offsets).containsExactly(4)
        assertThat(String(result.bytes, 4, 32, Charsets.US_ASCII)).isEqualTo(targetGuid)
        assertThat(String(result.bytes, 40, 32, Charsets.US_ASCII)).isEqualTo(sourceGuid)
        assertThat(result.bytes.size).isEqualTo(80)
    }

    @Test
    fun guidReplacementReplacesAllMetadataOccurrences() {
        val bytes = ByteArray(100)
        guidBytes(sourceGuid).copyInto(bytes, 0)
        guidBytes(sourceGuid).copyInto(bytes, 32)
        val result = UnityPersianPrefabRepoint.repointExternalGuid(bytes, 0, 100, sourceGuid, targetGuid)
        assertThat(result.offsets).containsExactly(0, 32).inOrder()
    }

    @Test
    fun guidValidationRejectsBadGuidsAndNoMatch() {
        val bytes = ByteArray(64)
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianPrefabRepoint.validateGuid("ABC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianPrefabRepoint.validateGuid("2222297E935FD6D44B4FCD284C9C7093")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianPrefabRepoint.repointExternalGuid(bytes, 0, 64, sourceGuid, sourceGuid)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianPrefabRepoint.repointExternalGuid(bytes, 0, 64, sourceGuid, targetGuid)
        }
    }

    @Test
    fun pptrReplacementChangesFileIdAndPathId() {
        val obj = ByteArray(64)
        pptrBytes(4, 1L).copyInto(obj, 8)
        val result = UnityPersianPrefabRepoint.repointPptr(
            obj,
            UnityPersianPrefabRepoint.PptrRepoint(4, 1L, 3, 1L),
        )
        assertThat(result.offsets).containsExactly(8)
        assertThat(result.bytes.copyOfRange(8, 20)).isEqualTo(pptrBytes(3, 1L))
        assertThat(result.bytes.size).isEqualTo(64)
    }

    @Test
    fun pptrSearchIsAlignedOnly() {
        // A (4, 1) pattern at offset 9 (not 4-aligned) must not match.
        val obj = ByteArray(64)
        pptrBytes(4, 1L).copyInto(obj, 9)
        assertThat(UnityPersianPrefabRepoint.findPptrOccurrences(obj, 4, 1L)).isEmpty()
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianPrefabRepoint.repointPptr(
                obj,
                UnityPersianPrefabRepoint.PptrRepoint(4, 1L, 3, 1L),
            )
        }
    }

    @Test
    fun combinedRepointRejectsAmbiguousPptr() {
        // Two (4, 1) PPtrs in the object: ambiguous, must fail
        // instead of repointing both (or picking one).
        val file = ByteArray(200)
        guidBytes(sourceGuid).copyInto(file, 4)
        pptrBytes(4, 1L).copyInto(file, 100)
        pptrBytes(4, 1L).copyInto(file, 120)
        assertThrows(IllegalArgumentException::class.java) {
            UnityPersianPrefabRepoint.repointPrefabFile(
                file, 0, 64, 64, 36, 100,
                sourceGuid, targetGuid,
                UnityPersianPrefabRepoint.PptrRepoint(4, 1L, 3, 1L),
            )
        }
    }

    @Test
    fun combinedRepointGuidOnlyKeepsSizes() {
        val file = ByteArray(200)
        guidBytes(sourceGuid).copyInto(file, 4)
        val result = UnityPersianPrefabRepoint.repointPrefabFile(
            file, 0, 64, 64, 36, 100,
            sourceGuid, targetGuid, null,
        )
        assertThat(result.bytes.size).isEqualTo(200)
        assertThat(result.guidOffsets).containsExactly(4)
        assertThat(result.pptrFileOffsets).isEmpty()
    }

    @Test
    fun scanPptrsListsCandidates() {
        val obj = ByteArray(64)
        pptrBytes(3, 3L).copyInto(obj, 8)
        pptrBytes(4, 1L).copyInto(obj, 20)
        val candidates = UnityPersianPrefabRepoint.scanPptrs(obj)
        // A candidate scan reports EVERY plausible 4-byte-aligned
        // window, including windows that merely overlap a real
        // PPtr or its zero padding (on this data the real PPtrs at
        // 8 and 20 also yield windows at 0, 4, 12 and 16, which read
        // path bytes as a new fileID/pathID). That is expected for
        // a discovery aid, not a parser: callers confirm candidates
        // against known references (as repointPptr and the
        // ViewModel's exactly-one rule do). So the assertion is
        // containment at the exact offsets, not equality, and a
        // zero-filled object must yield no candidates at all.
        assertThat(candidates.map { it.offset to (it.fileId to it.pathId) })
            .containsAtLeast(8 to (3 to 3L), 20 to (4 to 1L))
        assertThat(UnityPersianPrefabRepoint.scanPptrs(ByteArray(64))).isEmpty()
    }
}
