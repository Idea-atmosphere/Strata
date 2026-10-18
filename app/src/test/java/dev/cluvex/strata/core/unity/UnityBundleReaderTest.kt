package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class UnityBundleReaderTest {
    private data class TestNode(val name: String, val offset: Long, val size: Long)

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readsAndRebuildsUncompressedUnityFs() {
        val contents = "small text asset".toByteArray()
        val source = writeBundle(temp.root, "source.bundle", contents, compression = 0)
        val cache = File(temp.root, "cache")

        assertThat(UnityBundleReader.probe(source).kind).isEqualTo(UnityFileKind.UNITY_FS)
        UnityBundleReader.open(source, cache).use { document ->
            assertThat(document.summary.entries.map { it.name }).containsExactly("assets/message.txt")
            val extracted = File(temp.root, "extracted.txt")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)

            val replacement = File(temp.root, "replacement.txt").apply { writeText("updated") }
            val output = File(temp.root, "rebuilt.bundle")
            document.repack(output, mapOf("assets/message.txt" to replacement))
            UnityBundleReader.open(output, cache).use { rebuilt ->
                val result = File(temp.root, "rebuilt-entry.txt")
                rebuilt.copyEntry(rebuilt.summary.entries.single(), result)
                assertThat(result.readText()).isEqualTo("updated")
                assertThat(rebuilt.summary.entries.single().size).isEqualTo(7L)
            }
        }
    }

    @Test
    fun refusesToOverwriteExistingExtractionOrRepackOutput() {
        val source = writeBundle(temp.root, "source.bundle", "content".toByteArray(), compression = 0)
        val extraction = File(temp.root, "existing.txt").apply { writeText("keep me") }
        val output = File(temp.root, "existing.bundle").apply { writeText("keep this too") }
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            assertThrows(java.io.IOException::class.java) {
                document.copyEntry(document.summary.entries.single(), extraction)
            }
            assertThrows(java.io.IOException::class.java) { document.repack(output) }
        }
        assertThat(extraction.readText()).isEqualTo("keep me")
        assertThat(output.readText()).isEqualTo("keep this too")
    }

    @Test
    fun preservesGapsAndFollowingEntriesWhenReplacementGrows() {
        val source = writeBundle(
            temp.root,
            "gapped.bundle",
            "headGAPoldTAIL".toByteArray(),
            compression = 0,
            nodes = listOf(
                TestNode("assets/head.bin", 0L, 4L),
                TestNode("assets/text.txt", 7L, 3L),
                TestNode("assets/tail.bin", 10L, 4L),
            ),
        )
        val replacement = File(temp.root, "larger.txt").apply { writeText("updated") }
        val output = File(temp.root, "gapped-rebuilt.bundle")
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            document.repack(output, mapOf("assets/text.txt" to replacement))
        }
        UnityBundleReader.open(output, File(temp.root, "cache")).use { rebuilt ->
            assertThat(rebuilt.summary.entries[2].offset).isEqualTo(14L)
            val tail = File(temp.root, "tail.bin")
            rebuilt.copyEntry(rebuilt.summary.entries[2], tail)
            assertThat(tail.readText()).isEqualTo("TAIL")
            assertThat(rebuilt.summary.dataSize).isEqualTo(18L)
        }
    }

    @Test
    fun decodesRawLz4LiteralBlock() {
        val contents = "unity".toByteArray()
        val source = writeBundle(temp.root, "lz4.bundle", contents, compression = 2)
        UnityBundleReader.open(source, File(temp.root, "cache")).use { document ->
            val extracted = File(temp.root, "lz4-output")
            document.copyEntry(document.summary.entries.single(), extracted)
            assertThat(extracted.readBytes()).isEqualTo(contents)
        }
    }

    private fun writeBundle(
        root: File,
        name: String,
        contents: ByteArray,
        compression: Int,
        nodes: List<TestNode> = listOf(TestNode("assets/message.txt", 0L, contents.size.toLong())),
    ): File {
        val compressed = if (compression == 2) {
            byteArrayOf((contents.size shl 4).toByte()) + contents
        } else {
            contents
        }
        val info = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MessageDigest.getInstance("MD5").digest(contents))
                out.writeInt(1)
                out.writeInt(contents.size)
                out.writeInt(compressed.size)
                out.writeShort(compression)
                out.writeInt(nodes.size)
                nodes.forEach { node ->
                    out.writeLong(node.offset)
                    out.writeLong(node.size)
                    out.writeInt(0)
                    out.write(node.name.toByteArray(Charsets.UTF_8))
                    out.writeByte(0)
                }
            }
            bytes.toByteArray()
        }
        val headerPrefix = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("UnityFS".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
                out.writeInt(6)
                out.write("2020.3.0f1".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
                out.write("2020.3.0f1".toByteArray(Charsets.UTF_8))
                out.writeByte(0)
            }
            bytes.toByteArray()
        }
        val headerSize = headerPrefix.size + 8 + 4 + 4 + 4
        val output = File(root, name)
        FileOutputStream(output).use { stream ->
            DataOutputStream(stream).use { out ->
                out.write(headerPrefix)
                out.writeLong((headerSize + info.size + compressed.size).toLong())
                out.writeInt(info.size)
                out.writeInt(info.size)
                out.writeInt(0x40)
                out.write(info)
                out.write(compressed)
            }
        }
        return output
    }
}
