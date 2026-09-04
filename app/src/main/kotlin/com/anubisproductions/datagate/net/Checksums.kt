package com.anubisproductions.datagate.net

/** Internet checksum (RFC 1071) helpers. */
object Checksums {

    /**
     * Accumulate the one's-complement sum over [len] bytes starting at [off].
     * Returns a running sum, not a checksum - fold it with [finish].
     */
    fun sum(buf: ByteArray, off: Int, len: Int, initial: Long = 0L): Long {
        var acc = initial
        var i = off
        var left = len
        while (left > 1) {
            acc += (((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)).toLong()
            i += 2
            left -= 2
        }
        if (left == 1) acc += ((buf[i].toInt() and 0xFF) shl 8).toLong()
        return acc
    }

    /** Fold carries and invert. */
    fun finish(runningSum: Long): Int {
        var acc = runningSum
        while ((acc shr 16) != 0L) acc = (acc and 0xFFFF) + (acc shr 16)
        return (acc.inv() and 0xFFFF).toInt()
    }

    fun of(buf: ByteArray, off: Int, len: Int): Int = finish(sum(buf, off, len))

    /**
     * Pseudo-header sum for TCP/UDP/ICMPv6 over IPv4 or IPv6.
     *
     * @param src source address bytes (4 for v4, 16 for v6)
     * @param dst destination address bytes
     * @param protocol next-header / protocol number
     * @param payloadLength length of the transport header plus payload
     */
    fun pseudoHeaderSum(src: ByteArray, dst: ByteArray, protocol: Int, payloadLength: Int): Long {
        var acc = sum(src, 0, src.size)
        acc = sum(dst, 0, dst.size, acc)
        acc += protocol.toLong()
        acc += payloadLength.toLong()
        return acc
    }

    fun write(buf: ByteArray, off: Int, checksum: Int) {
        buf[off] = ((checksum shr 8) and 0xFF).toByte()
        buf[off + 1] = (checksum and 0xFF).toByte()
    }
}
