package dev.cluvex.strata.core.unity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class UnityBaseDataTest {

    private fun monoBytes(name: String, bigEndian: Boolean): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(32 + nameBytes.size + 4)
        val len = nameBytes.size
        if (bigEndian) {
            out[28] = ((len shr 24) and 0xFF).toByte()
            out[29] = ((len shr 16) and 0xFF).toByte()
            out[30] = ((len shr 8) and 0xFF).toByte()
            out[31] = (len and 0xFF).toByte()
        } else {
            out[28] = (len and 0xFF).toByte()
            out[29] = ((len shr 8) and 0xFF).toByte()
            out[30] = ((len shr 16) and 0xFF).toByte()
            out[31] = ((len shr 24) and 0xFF).toByte()
        }
        nameBytes.copyInto(out, 32)
        return out
    }

    @Test
    fun guessMonoBehaviourNameHandlesEndiannessAndBadInput() {
        assertThat(UnityBaseData.guessMonoBehaviourName(monoBytes("Hello", false), false)).isEqualTo("Hello")
        assertThat(UnityBaseData.guessMonoBehaviourName(monoBytes("Hello", true), true)).isEqualTo("Hello")
        // Wrong endianness reads a huge length, so it must return null, not garbage.
        assertThat(UnityBaseData.guessMonoBehaviourName(monoBytes("Hello", true), false)).isNull()
        assertThat(UnityBaseData.guessMonoBehaviourName(ByteArray(20), false)).isNull()
        assertThat(UnityBaseData.guessMonoBehaviourName(ByteArray(32), false)).isNull()
        val withNull = monoBytes("Hi", false)
        withNull[33] = 0
        assertThat(UnityBaseData.guessMonoBehaviourName(withNull, false)).isNull()
    }

    @Test
    fun externalTreeDecodeEncodeRoundTripsSyntheticObject() {
        val tree = TypeTree(
            TTNode(
                1, "TestObject", "", 0, 0,
                mutableListOf(
                    TTNode(1, "string", "m_Name", 0, 0),
                    TTNode(1, "int", "m_Value", 4, 0),
                ),
            ),
        )
        val value = UVal.Struct(
            linkedMapOf(
                "m_Name" to UVal.Str("Test"),
                "m_Value" to UVal.Num(42, 4),
            ),
        )
        val encoded = UnityBaseData.encodeWithExternalTree(tree, value, false)
        val decoded = UnityBaseData.decodeWithExternalTree(tree, encoded, false) as UVal.Struct
        assertThat((decoded.fields["m_Name"] as? UVal.Str)?.value).isEqualTo("Test")
        assertThat((decoded.fields["m_Value"] as? UVal.Num)?.value).isEqualTo(42L)
        assertThat(UnityBaseData.encodeWithExternalTree(tree, decoded, false)).isEqualTo(encoded)
    }

    @Test
    fun externalTreeDecodeFailsFastOnTruncatedBytes() {
        val tree = TypeTree(
            TTNode(
                1, "TestObject", "", 0, 0,
                mutableListOf(
                    TTNode(1, "string", "m_Name", 0, 0),
                    TTNode(1, "int", "m_Value", 4, 0),
                ),
            ),
        )
        val value = UVal.Struct(
            linkedMapOf(
                "m_Name" to UVal.Str("Test"),
                "m_Value" to UVal.Num(42, 4),
            ),
        )
        val encoded = UnityBaseData.encodeWithExternalTree(tree, value, false)
        assertThrows(UnityFormatException::class.java) {
            UnityBaseData.decodeWithExternalTree(tree, encoded.copyOf(encoded.size - 1), false)
        }
    }
}
