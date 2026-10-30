package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One-byte vector arrays (notably Font.m_FontData) can legitimately exceed the
 * element-count guard: a 22 MB font is 22M one-byte elements. The codec reads
 * such arrays in bulk as bytes instead of one UVal per byte.
 */
class TypeTreeCodecTest {

    private fun vectorTree(elementType: String, vectorAligns: Boolean): TypeTree {
        val sizeNode = TTNode(0, "int", "size", 4, 0)
        val dataNode = TTNode(0, elementType, "data", 1, 0)
        val arrayNode = TTNode(0, "Array", "Array", -1, TTNode.ALIGN_FLAG, mutableListOf(sizeNode, dataNode))
        val vectorNode = TTNode(
            0, "vector", "m_Data", -1,
            if (vectorAligns) TTNode.ALIGN_FLAG else 0,
            mutableListOf(arrayNode),
        )
        return TypeTree(TTNode(0, "Base", "Base", -1, 0, mutableListOf(vectorNode)))
    }

    private fun byteVectorData(payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(4 + payload.size + 3).order(ByteOrder.BIG_ENDIAN)
        out.putInt(payload.size)
        out.put(payload)
        // Vector aligns: pad to a 4-byte boundary, mirroring the encoder.
        while (out.position() % 4 != 0) out.put(0)
        return out.array().copyOf(out.position())
    }

    @Test
    fun largeByteVectorDecodesInBulkInsteadOfThrowing() {
        val payload = ByteArray(5_000_000) { (it % 251).toByte() }
        val data = byteVectorData(payload)
        val value = TypeTreeCodec.decode(vectorTree("char", vectorAligns = true), data, true)
        val struct = value as UVal.Struct
        val bytes = struct.fields["m_Data"] as UVal.Bytes
        assertThat(bytes.data).isEqualTo(payload)
    }

    @Test
    fun byteVectorRoundTripsBitIdentically() {
        val payload = ByteArray(5_000_000) { (it % 251).toByte() }
        val data = byteVectorData(payload)
        val tree = vectorTree("char", vectorAligns = true)
        val value = TypeTreeCodec.decode(tree, data, true)
        assertThat(TypeTreeCodec.encode(tree, value, true)).isEqualTo(data)
    }

    @Test
    fun smallByteVectorDecodesAsBytesAndRoundTrips() {
        val payload = byteArrayOf(1, 2, 3)
        val data = byteVectorData(payload)
        val tree = vectorTree("char", vectorAligns = true)
        val value = TypeTreeCodec.decode(tree, data, true)
        val bytes = (value as UVal.Struct).fields["m_Data"] as UVal.Bytes
        assertThat(bytes.data).isEqualTo(payload)
        assertThat(TypeTreeCodec.encode(tree, value, true)).isEqualTo(data)
    }

    @Test
    fun largeNonByteArrayIsStillRejected() {
        val data = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(5_000_000).array()
        assertThrows(UnityFormatException::class.java) {
            TypeTreeCodec.decode(vectorTree("int", vectorAligns = false), data, true)
        }
    }

    @Test
    fun smallIntArrayStillDecodesAsElements() {
        val out = ByteBuffer.allocate(4 + 3 * 4).order(ByteOrder.BIG_ENDIAN)
        out.putInt(3)
        out.putInt(10)
        out.putInt(20)
        out.putInt(30)
        val data = out.array()
        val tree = vectorTree("int", vectorAligns = false)
        val value = TypeTreeCodec.decode(tree, data, true)
        val items = ((value as UVal.Struct).fields["m_Data"] as UVal.Arr).items
        assertThat(items.map { (it as UVal.Num).value }).containsExactly(10L, 20L, 30L)
        assertThat(TypeTreeCodec.encode(tree, value, true)).isEqualTo(data)
    }
}
