package dev.cluvex.strata.core.unity

import java.io.File
import java.io.IOException

/**
 * Base-data (shipped APK `assets/bin/Data/`) support for the Strata
 * game-translation mode.
 *
 * Why this exists: shipped Unity games keep a SECOND copy of their
 * fonts and TMP assets in base-data SerializedFiles (not in
 * Addressables bundles). Bendy's subtitles use the base-data copy
 * (TMP_Settings.defaultFontAsset -> base primary -> base dynamic
 * fallback -> base Font), so patching only the Addressables copy
 * never fixes subtitles. Strata previously could not open base
 * files at all, for two separate reasons, both fixed here:
 *
 * 1. Parse failure (fixed in SerializedFileReader): for Unity 2021+
 *    files with enableTypeTree=false (stripped, shipped builds), the
 *    type table has NO dependency list after each type entry. Reading
 *    deps unconditionally misread the next type's classId as a
 *    dependency count (Bendy's base fallback: classId 28 read as
 *    28 dependencies) and ran past the metadata. Bundles store trees
 *    (enableTypeTree=true) and were unaffected.
 * 2. Decode failure (fixed here, not in the reader): stripped files
 *    store NO type trees, so same-file decoding always fails with
 *    NO_TYPE_TREE for MonoBehaviour/Font/etc. The fix is to decode
 *    with an EXTERNAL tree taken from a bundle of the SAME game
 *    (same Unity version, same TMP package version). The tree is
 *    identified by structure, not by pathId:
 *    - TMP_FontAsset: a MonoBehaviour tree whose root fields include
 *      BOTH m_CharacterTable and m_GlyphTable. Root TYPE is useless
 *      here (all script objects report generic "MonoBehaviour").
 *    - Font: a bundle slot with classId 128; its tree is the Font
 *      tree for that Unity version.
 *
 * Scope and limits (v1, documented, not hidden):
 * - Bundle tree resolution scans ONLY the first CAB file of a bundle
 *   (SerializedFileReader.parseEmbedded at offset 0), matching the
 *   proven MainBaseDecode approach. Bendy's TMP objects live in the
 *   first CAB of its scene bundles. If a game's TMP objects live in
 *   a later CAB, this returns null with a clear message; scanning
 *   every CAB via UnityBundleDocument's private embedded API is
 *   future work, not a silent fallback to a wrong tree.
 * - The bundle MUST be from the same game as the base file. A tree
 *   from a different Unity/TMP version may decode with a wrong
 *   consumed size or garbage fields; [decodeWithExternalTree] fails
 *   fast on consumed != size instead of returning a corrupt value,
 *   and [encodeWithExternalTree] re-decodes its own output as a
 *   self-check, mirroring SerializedFileIndex.encode.
 * - Object names via [guessMonoBehaviourName] are a HEURISTIC for
 *   MonoBehaviour only (m_GameObject PPtr 12 + m_Enabled 4 +
 *   m_Script PPtr 12 = 28, then m_Name length at 28, bytes at 32).
 *   It is used only to IDENTIFY an object before decoding (e.g.
 *   "LiberationSans SDF - Fallback"); it is never used as a decode
 *   result. Non-MonoBehaviour objects return null here.
 * - Rebuilding uses SerializedFileIndex.rebuild, which already
 *   handles base files correctly once parsing works: it patches
 *   object starts/sizes in place, updates the v22 header fileSize,
 *   writes objects 8-aligned, preserves metadata (including script
 *   types, externals, ref types and trailing padding) verbatim, and
 *   self-checks by re-parsing the output.
 */
internal data class BaseDataFile(
    val source: File,
    val summary: UnityBundleSummary,
    val index: SerializedFileIndex,
    /** Staged data section (from SerializedFileReader.parse). */
    val dataFile: File,
)

internal data class BaseObjectInfo(
    val pathId: Long,
    val classId: Int,
    val typeIndex: Int,
    val start: Long,
    val size: Long,
    val entryName: String,
    /** MonoBehaviour name heuristic, or null (see class doc). */
    val nameGuess: String?,
)

internal object UnityBaseData {

    /** Opens a raw base-data SerializedFile (not a UnityFS bundle). */
    fun openBaseFile(file: File, stagedDataFile: File): BaseDataFile {
        val (summary, index) = SerializedFileReader.parse(file, stagedDataFile)
        return BaseDataFile(file, summary, index, stagedDataFile)
    }

    /** Lists base objects, with a MonoBehaviour name guess where possible. */
    fun listObjects(base: BaseDataFile): List<BaseObjectInfo> {
        return base.index.slots.map { slot ->
            val raw = try {
                base.index.readObjectBytes(base.dataFile, slot)
            } catch (_: Exception) {
                null
            }
            val nameGuess = if (slot.classId == 114 && raw != null) {
                guessMonoBehaviourName(raw, base.index.bigEndian)
            } else {
                null
            }
            BaseObjectInfo(slot.pathId, slot.classId, slot.typeIndex, slot.start, slot.size, slot.entryName, nameGuess)
        }
    }

    /** Reads one base object's raw bytes (data section, slot.start/size). */
    fun readRawObject(base: BaseDataFile, slot: SerializedFileIndex.Slot): ByteArray {
        return base.index.readObjectBytes(base.dataFile, slot)
    }

    /**
     * Decodes raw object bytes with an external tree. Fails fast if
     * the tree does not consume exactly the object's bytes (wrong
     * game/Unity/TMP version, or wrong tree for this object).
     */
    fun decodeWithExternalTree(tree: TypeTree, bytes: ByteArray, bigEndian: Boolean): UVal {
        val (value, consumed) = try {
            TypeTreeCodec.decodeChecked(tree, bytes, bigEndian)
        } catch (e: Exception) {
            throw UnityFormatException(
                "External-tree decode failed (bundle must be from the same game/Unity version): ${e.message}",
                e,
            )
        }
        if (consumed != bytes.size) {
            throw UnityFormatException(
                "External-tree decode consumed $consumed of ${bytes.size} object bytes " +
                    "(wrong tree for this object, or bundle from a different game/Unity version)",
            )
        }
        return value
    }

    /**
     * Encodes a value with an external tree, then self-checks by
     * decoding the output again (consumed == size, byte round-trip
     * of the re-decoded value is not required here; callers that
     * need bit-identical unmodified re-encoding should verify it
     * explicitly, as the Phase 3 harness does).
     */
    fun encodeWithExternalTree(tree: TypeTree, value: UVal, bigEndian: Boolean): ByteArray {
        val encoded = TypeTreeCodec.encode(tree, value, bigEndian)
        val (_, consumed) = TypeTreeCodec.decodeChecked(tree, encoded, bigEndian)
        if (consumed != encoded.size) {
            throw UnityFormatException(
                "External-tree re-encode failed its self-check: decoded $consumed of ${encoded.size} bytes",
            )
        }
        return encoded
    }

    /**
     * Finds a TMP_FontAsset tree in the FIRST CAB file of a bundle's
     * staged data stream (see class doc for the first-CAB limit).
     * [bundleDataFile] must be UnityBundleDocument.dataFile from
     * UnityBundleReader.open(bundle, cacheDir), NOT the bundle file
     * itself (which is UnityFS, not a SerializedFile).
     * Returns null when no TMP tree is in the first CAB.
     */
    fun findTmpFontAssetTree(bundleDataFile: File): TypeTree? {
        val (_, index) = SerializedFileReader.parseEmbedded(bundleDataFile, 0L, bundleDataFile.length())
        for (tree in index.trees) {
            if (tree == null) continue
            val fieldNames = tree.root.children.map { it.name }.toSet()
            if ("m_CharacterTable" in fieldNames && "m_GlyphTable" in fieldNames) {
                return tree
            }
        }
        return null
    }

    /**
     * Finds a Font tree (classId 128) in the FIRST CAB file of a
     * bundle's staged data stream. Returns null when no Font object
     * is in the first CAB (e.g. scene bundles, whose Fonts live in
     * the defaultlocalgroup bundle instead).
     */
    fun findFontTree(bundleDataFile: File): TypeTree? {
        val (_, index) = SerializedFileReader.parseEmbedded(bundleDataFile, 0L, bundleDataFile.length())
        for (slot in index.slots) {
            if (slot.classId != 128) continue
            val tree = index.trees.getOrNull(slot.typeIndex) ?: continue
            return tree
        }
        return null
    }

    /**
     * Finds a Texture2D tree (classId 28) in the FIRST CAB file of a
     * bundle's staged data stream, mirroring [findFontTree]. Texture2D
     * trees are per class, not per object, so the first Texture2D
     * tree in the scene bundle is the tree for the fallback atlas
     * Texture2D in the base file (same Unity version). Returns null
     * when no Texture2D object is in the first CAB.
     */
    fun findTextureTree(bundleDataFile: File): TypeTree? {
        val (_, index) = SerializedFileReader.parseEmbedded(bundleDataFile, 0L, bundleDataFile.length())
        for (slot in index.slots) {
            if (slot.classId != 28) continue
            val tree = index.trees.getOrNull(slot.typeIndex) ?: continue
            return tree
        }
        return null
    }

    /**
     * Rebuilds a base file with replaced objects (keys are exact
     * entry names, e.g. "#3 MonoBehaviour", from summary.entries).
     * See SerializedFileIndex.rebuild for alignment, header and
     * self-check behaviour; it already handles base files.
     */
    fun rebuildBaseFile(base: BaseDataFile, output: File, replacements: Map<String, File>) {
        base.index.rebuild(output, base.dataFile, replacements, base.summary.entries)
    }

    /** MonoBehaviour name heuristic (see class doc). Null when it does not parse cleanly. */
    fun guessMonoBehaviourName(raw: ByteArray, bigEndian: Boolean): String? {
        if (raw.size < 32) return null
        val length = readI32At(raw, 28, bigEndian)
        if (length <= 0 || length > 512) return null
        if (32 + length > raw.size) return null
        val bytes = raw.copyOfRange(32, 32 + length)
        if (bytes.any { it == 0.toByte() }) return null
        return try {
            val s = bytes.toString(Charsets.UTF_8)
            if (s.any { it.code < 0x20 || it.code == 0x7F }) null else s
        } catch (_: Exception) {
            null
        }
    }

    private fun readI32At(bytes: ByteArray, offset: Int, bigEndian: Boolean): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return if (bigEndian) {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        } else {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }
}
