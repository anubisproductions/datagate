package com.anubisproductions.datagate.net

/** Protocol / next-header numbers we care about. */
object Proto {
    const val ICMP = 1
    const val TCP = 6
    const val UDP = 17
    const val ICMPV6 = 58
}

object TcpFlag {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

/**
 * A minimally-parsed outbound IP packet read off the tun interface.
 *
 * We only decode what a block decision needs. No reassembly, no options parsing beyond
 * skipping IPv6 extension headers.
 */
class Packet private constructor(
    val raw: ByteArray,
    val length: Int,
    val ipVersion: Int,
    val protocol: Int,
    val ipHeaderLength: Int,
    val srcAddr: ByteArray,
    val dstAddr: ByteArray,
    /** Offset of the transport header within [raw]; -1 if we could not locate it. */
    val transportOffset: Int,
    val srcPort: Int,
    val dstPort: Int,
    val tcpFlags: Int,
    val tcpSeq: Long,
    val tcpAck: Long,
    /** Payload length of the transport layer (TCP data, or UDP payload). */
    val transportPayloadLength: Int,
) {

    val isTcp get() = protocol == Proto.TCP
    val isUdp get() = protocol == Proto.UDP
    val hasPorts get() = (isTcp || isUdp) && transportOffset >= 0
    val isDnsQuery get() = hasPorts && dstPort == 53

    /** TCP data offset in bytes, valid only when [isTcp]. */
    val tcpHeaderLength: Int
        get() = if (isTcp && transportOffset >= 0) {
            ((raw[transportOffset + 12].toInt() shr 4) and 0x0F) * 4
        } else 0

    val udpPayloadOffset: Int
        get() = if (isUdp && transportOffset >= 0) transportOffset + 8 else -1

    fun describe(): String {
        val s = addrToString(srcAddr)
        val d = addrToString(dstAddr)
        return when {
            isTcp -> "TCP $s:$srcPort -> $d:$dstPort flags=0x${Integer.toHexString(tcpFlags)}"
            isUdp -> "UDP $s:$srcPort -> $d:$dstPort len=$transportPayloadLength"
            else -> "proto=$protocol $s -> $d"
        }
    }

    companion object {

        fun addrToString(a: ByteArray): String = if (a.size == 4) {
            a.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } else {
            (a.indices step 2).joinToString(":") { i ->
                Integer.toHexString(
                    ((a[i].toInt() and 0xFF) shl 8) or (a[i + 1].toInt() and 0xFF)
                )
            }
        }

        /** Returns null when the buffer is too short or the version is unrecognised. */
        fun parse(buf: ByteArray, length: Int): Packet? {
            if (length < 20) return null
            val version = (buf[0].toInt() shr 4) and 0x0F
            return when (version) {
                4 -> parseV4(buf, length)
                6 -> parseV6(buf, length)
                else -> null
            }
        }

        private fun parseV4(buf: ByteArray, length: Int): Packet? {
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if (ihl < 20 || length < ihl) return null

            // A non-zero fragment offset means this is not the first fragment, so there is
            // no transport header here to answer. Treat it as portless.
            val fragOffset = (((buf[6].toInt() and 0x1F) shl 8) or (buf[7].toInt() and 0xFF))

            val protocol = buf[9].toInt() and 0xFF
            val totalLength = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            val src = buf.copyOfRange(12, 16)
            val dst = buf.copyOfRange(16, 20)
            val effective = minOf(length, if (totalLength in 1..length) totalLength else length)

            return finishParse(buf, effective, 4, protocol, ihl, src, dst, fragOffset == 0)
        }

        private fun parseV6(buf: ByteArray, length: Int): Packet? {
            if (length < 40) return null
            val payloadLength = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val src = buf.copyOfRange(8, 24)
            val dst = buf.copyOfRange(24, 40)
            val effective = minOf(length, 40 + payloadLength)

            // Walk extension headers to reach the transport header.
            var next = buf[6].toInt() and 0xFF
            var off = 40
            var guard = 0
            while (guard++ < 8) {
                when (next) {
                    0, 43, 60 -> { // hop-by-hop, routing, destination options
                        if (off + 2 > effective) return null
                        val hdrLen = ((buf[off + 1].toInt() and 0xFF) + 1) * 8
                        next = buf[off].toInt() and 0xFF
                        off += hdrLen
                    }
                    51 -> { // authentication header
                        if (off + 2 > effective) return null
                        val hdrLen = ((buf[off + 1].toInt() and 0xFF) + 2) * 4
                        next = buf[off].toInt() and 0xFF
                        off += hdrLen
                    }
                    44 -> return null // fragment header: not the place to answer
                    else -> return finishParse(buf, effective, 6, next, off, src, dst, true)
                }
            }
            return null
        }

        private fun finishParse(
            buf: ByteArray,
            length: Int,
            version: Int,
            protocol: Int,
            headerLength: Int,
            src: ByteArray,
            dst: ByteArray,
            transportPresent: Boolean,
        ): Packet {
            var transportOffset = -1
            var srcPort = 0
            var dstPort = 0
            var flags = 0
            var seq = 0L
            var ack = 0L
            var payloadLen = 0

            if (transportPresent && (protocol == Proto.TCP || protocol == Proto.UDP)) {
                val off = headerLength
                val minHdr = if (protocol == Proto.TCP) 20 else 8
                if (off + minHdr <= length) {
                    transportOffset = off
                    srcPort = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
                    dstPort = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
                    if (protocol == Proto.TCP) {
                        seq = readU32(buf, off + 4)
                        ack = readU32(buf, off + 8)
                        flags = buf[off + 13].toInt() and 0xFF
                        val dataOff = ((buf[off + 12].toInt() shr 4) and 0x0F) * 4
                        payloadLen = (length - off - dataOff).coerceAtLeast(0)
                    } else {
                        val udpLen = ((buf[off + 4].toInt() and 0xFF) shl 8) or (buf[off + 5].toInt() and 0xFF)
                        payloadLen = (minOf(udpLen, length - off) - 8).coerceAtLeast(0)
                    }
                }
            }

            return Packet(
                raw = buf,
                length = length,
                ipVersion = version,
                protocol = protocol,
                ipHeaderLength = headerLength,
                srcAddr = src,
                dstAddr = dst,
                transportOffset = transportOffset,
                srcPort = srcPort,
                dstPort = dstPort,
                tcpFlags = flags,
                tcpSeq = seq,
                tcpAck = ack,
                transportPayloadLength = payloadLen,
            )
        }

        private fun readU32(buf: ByteArray, off: Int): Long =
            ((buf[off].toLong() and 0xFF) shl 24) or
                ((buf[off + 1].toLong() and 0xFF) shl 16) or
                ((buf[off + 2].toLong() and 0xFF) shl 8) or
                (buf[off + 3].toLong() and 0xFF)
    }
}
