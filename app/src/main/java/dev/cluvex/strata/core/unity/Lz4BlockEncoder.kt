package dev.cluvex.strata.core.unity

import java.io.ByteArrayOutputStream

/**
 * Small raw LZ4 block encoder for UnityFS output.
 *
 * UnityFS stores raw LZ4 blocks: there is no frame magic, checksum or content-size header. The
 * encoder uses a bounded hash chain (similar to an LZ4HC search) and deliberately works on one
 * small UnityFS block at a time, keeping memory independent of the full bundle size.
 */
internal object Lz4BlockEncoder {
    private const val MIN_MATCH = 4
    private const val LAST_LITERALS = 5
    private const val MATCH_FIND_LIMIT = 12
    private const val MAX_DISTANCE = 65_535
    private const val HASH_BITS = 16
    private const val HASH_SIZE = 1 shl HASH_BITS
    private const val MAX_CHAIN_ATTEMPTS = 64

    /** Returns one raw LZ4 block. [length] may be smaller than [input].size for a final block. */
    fun encode(input: ByteArray, length: Int = input.size): ByteArray {
        require(length in 0..input.size) { "Invalid LZ4 input length" }
        if (length == 0) return byteArrayOf(0)
        if (length < MATCH_FIND_LIMIT) return literalOnly(input, length)

        val heads = IntArray(HASH_SIZE) { -1 }
        val previous = IntArray(length) { -1 }
        val inserted = BooleanArray(length)
        val out = ByteArrayOutputStream(length)
        var anchor = 0
        var position = 0
        val matchLimit = length - LAST_LITERALS
        val searchLimit = length - MATCH_FIND_LIMIT

        fun insert(at: Int): Int {
            if (at + MIN_MATCH > length) return -1
            if (inserted[at]) return previous[at]
            val hash = hash(readInt(input, at))
            val candidate = heads[hash]
            previous[at] = candidate
            heads[hash] = at
            inserted[at] = true
            return candidate
        }

        while (position <= searchLimit) {
            if (position and 0x3FF == 0) ensureUnityOperationActive()
            var candidate = insert(position)
            var bestPosition = -1
            var bestLength = 0
            var attempts = MAX_CHAIN_ATTEMPTS
            val oldest = maxOf(0, position - MAX_DISTANCE)
            while (candidate >= oldest && attempts-- > 0) {
                if (readInt(input, candidate) == readInt(input, position)) {
                    var count = MIN_MATCH
                    val maximum = matchLimit - position
                    while (count < maximum && input[candidate + count] == input[position + count]) count++
                    if (count > bestLength) {
                        bestLength = count
                        bestPosition = candidate
                        if (count == maximum) break
                    }
                }
                candidate = previous[candidate]
            }

            if (bestLength < MIN_MATCH) {
                position++
                continue
            }

            // A one-byte lazy check avoids committing to a short match when the next position has
            // a substantially longer one. The next position is inserted here exactly once.
            if (position + 1 <= searchLimit) {
                var nextCandidate = insert(position + 1)
                var nextBest = 0
                var nextAttempts = MAX_CHAIN_ATTEMPTS / 2
                val nextOldest = maxOf(0, position + 1 - MAX_DISTANCE)
                while (nextCandidate >= nextOldest && nextAttempts-- > 0) {
                    if (readInt(input, nextCandidate) == readInt(input, position + 1)) {
                        var count = MIN_MATCH
                        val maximum = matchLimit - position - 1
                        while (count < maximum && input[nextCandidate + count] == input[position + 1 + count]) count++
                        if (count > nextBest) nextBest = count
                    }
                    nextCandidate = previous[nextCandidate]
                }
                if (nextBest > bestLength + 1) {
                    position++
                    continue
                }
            }

            writeSequence(out, input, anchor, position, position - bestPosition, bestLength)
            val matchEnd = position + bestLength
            var insertAt = position + 1
            while (insertAt < matchEnd && insertAt <= searchLimit) {
                // position + 1 may already have been inserted by the lazy check.
                if (!inserted[insertAt]) insert(insertAt)
                insertAt++
            }
            position = matchEnd
            anchor = matchEnd
        }

        writeLastLiterals(out, input, anchor, length)
        return out.toByteArray()
    }

    private fun writeSequence(
        out: ByteArrayOutputStream,
        input: ByteArray,
        anchor: Int,
        matchPosition: Int,
        distance: Int,
        matchLength: Int,
    ) {
        check(distance in 1..MAX_DISTANCE) { "LZ4 match distance is out of range" }
        val literalLength = matchPosition - anchor
        val encodedMatchLength = matchLength - MIN_MATCH
        out.write((minOf(literalLength, 15) shl 4) or minOf(encodedMatchLength, 15))
        writeLength(out, literalLength - 15)
        out.write(input, anchor, literalLength)
        out.write(distance and 0xFF)
        out.write(distance ushr 8)
        writeLength(out, encodedMatchLength - 15)
    }

    private fun writeLastLiterals(out: ByteArrayOutputStream, input: ByteArray, anchor: Int, length: Int) {
        val literalLength = length - anchor
        out.write(minOf(literalLength, 15) shl 4)
        writeLength(out, literalLength - 15)
        out.write(input, anchor, literalLength)
    }

    private fun literalOnly(input: ByteArray, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length + 8)
        writeLastLiterals(out, input, 0, length)
        return out.toByteArray()
    }

    /** LZ4 writes an extension byte even when the nibble is 15 and the extra length is zero. */
    private fun writeLength(out: ByteArrayOutputStream, extra: Int) {
        if (extra < 0) return
        var left = extra
        while (left >= 255) {
            out.write(255)
            left -= 255
        }
        out.write(left)
    }

    private fun readInt(input: ByteArray, offset: Int): Int =
        (input[offset].toInt() and 0xFF) or
            ((input[offset + 1].toInt() and 0xFF) shl 8) or
            ((input[offset + 2].toInt() and 0xFF) shl 16) or
            ((input[offset + 3].toInt() and 0xFF) shl 24)

    private fun hash(value: Int): Int = (value * -1_640_531_535).ushr(32 - HASH_BITS)
}
