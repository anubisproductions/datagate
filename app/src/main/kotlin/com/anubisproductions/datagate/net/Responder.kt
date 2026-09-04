package com.anubisproductions.datagate.net

/**
 * Crafts the responses that make a blocked app fail *fast* instead of hanging.
 *
 * Every competing firewall discards
 * packets, so the app sits on its own connect timeout - 10 to 60 seconds, sometimes
 * forever. Answering the packet turns that into an immediate ECONNREFUSED or
 * UnknownHostException, which is what an app's offline path is actually written to catch.
 *
 * Nothing here opens a socket: responses are written straight back into the tun fd, so
 * the app still needs no INTERNET permission.
 */
object Responder {

    private const val TTL = 64

    // ---------------------------------------------------------------- TCP reset

    /**
     * A TCP RST for [p], or null if [p] is not a resettable segment.
     *
     * Follows the usual kernel behaviour: if the incoming segment carries ACK we reply
     * with seq = their ack and no ACK flag of our own; otherwise we reply RST+ACK with
     * seq = 0 and ack = their seq advanced past whatever they sent.
     */
    fun tcpReset(p: Packet): ByteArray? {
        if (!p.isTcp || p.transportOffset < 0) return null
        // Never answer a reset with a reset.
        if (p.tcpFlags and TcpFlag.RST != 0) return null

        val hasAck = p.tcpFlags and TcpFlag.ACK != 0
        val seq: Long
        val ack: Long
        val flags: Int
        if (hasAck) {
            seq = p.tcpAck
            ack = 0L
            flags = TcpFlag.RST
        } else {
            var consumed = p.transportPayloadLength.toLong()
            if (p.tcpFlags and TcpFlag.SYN != 0) consumed += 1
            if (p.tcpFlags and TcpFlag.FIN != 0) consumed += 1
            seq = 0L
            ack = (p.tcpSeq + consumed) and 0xFFFFFFFFL
            flags = TcpFlag.RST or TcpFlag.ACK
        }

        val ipHeaderLen = if (p.ipVersion == 4) 20 else 40
        val out = ByteArray(ipHeaderLen + 20)

        writeIpHeader(out, p, Proto.TCP, 20)

        val t = ipHeaderLen
        writeU16(out, t, p.dstPort)          // our source is their destination
        writeU16(out, t + 2, p.srcPort)
        writeU32(out, t + 4, seq)
        writeU32(out, t + 8, ack)
        out[t + 12] = (5 shl 4).toByte()     // data offset 5 words, no options
        out[t + 13] = flags.toByte()
        writeU16(out, t + 14, 0)             // window 0
        writeU16(out, t + 16, 0)             // checksum placeholder
        writeU16(out, t + 18, 0)             // urgent pointer

        finishTransportChecksum(out, p, t, 20, Proto.TCP, checksumOffset = t + 16)
        return out
    }

    // -------------------------------------------------------- ICMP unreachable

    /**
     * ICMP port-unreachable for [p] - the UDP counterpart of a TCP reset, and the reason
     * QUIC on UDP/443 (which most modern games use) fails immediately rather than
     * retrying for half a minute.
     */
    fun portUnreachable(p: Packet): ByteArray? = when (p.ipVersion) {
        4 -> icmpV4Unreachable(p)
        6 -> icmpV6Unreachable(p)
        else -> null
    }

    private fun icmpV4Unreachable(p: Packet): ByteArray? {
        // RFC 792: include the offending IP header plus the first 8 bytes of its payload.
        val quoteLen = minOf(p.ipHeaderLength + 8, p.length)
        if (quoteLen <= 0) return null

        val out = ByteArray(20 + 8 + quoteLen)
        writeIpHeader(out, p, Proto.ICMP, 8 + quoteLen)

        val i = 20
        out[i] = 3          // destination unreachable
        out[i + 1] = 3      // port unreachable
        writeU16(out, i + 2, 0)   // checksum placeholder
        writeU32(out, i + 4, 0L)  // unused
        System.arraycopy(p.raw, 0, out, i + 8, quoteLen)

        // IPv4 ICMP has no pseudo-header.
        Checksums.write(out, i + 2, Checksums.of(out, i, 8 + quoteLen))
        return out
    }

    private fun icmpV6Unreachable(p: Packet): ByteArray? {
        // RFC 4443: as much of the invoking packet as fits without exceeding the minimum MTU.
        val maxQuote = 1280 - 40 - 8
        val quoteLen = minOf(p.length, maxQuote)
        if (quoteLen <= 0) return null

        val out = ByteArray(40 + 8 + quoteLen)
        writeIpHeader(out, p, Proto.ICMPV6, 8 + quoteLen)

        val i = 40
        out[i] = 1          // destination unreachable
        out[i + 1] = 4      // port unreachable
        writeU16(out, i + 2, 0)
        writeU32(out, i + 4, 0L)
        System.arraycopy(p.raw, 0, out, i + 8, quoteLen)

        finishTransportChecksum(out, p, i, 8 + quoteLen, Proto.ICMPV6, checksumOffset = i + 2)
        return out
    }

    // ------------------------------------------------------------ DNS NXDOMAIN

    /**
     * A synthetic NXDOMAIN answer for a UDP DNS query.
     *
     * NXDOMAIN rather than SERVFAIL: it becomes UnknownHostException, the exception an
     * app's offline branch actually catches, whereas SERVFAIL sends the resolver off to
     * retry other servers first.
     *
     * No SOA record is included in the authority section, and that is deliberate - without
     * one there is no negative-caching TTL, so unblocking an app takes effect immediately
     * instead of leaving it stuck on a cached failure.
     */
    fun dnsNxdomain(p: Packet): ByteArray? {
        if (!p.isUdp || p.dstPort != 53) return null
        val qOff = p.udpPayloadOffset
        if (qOff < 0 || qOff + 12 > p.length) return null

        val questionLen = dnsQuestionLength(p.raw, qOff, p.length) ?: return null
        val dnsLen = 12 + questionLen

        val ipHeaderLen = if (p.ipVersion == 4) 20 else 40
        val udpLen = 8 + dnsLen
        val out = ByteArray(ipHeaderLen + udpLen)

        writeIpHeader(out, p, Proto.UDP, udpLen)

        val u = ipHeaderLen
        writeU16(out, u, p.dstPort)      // from port 53
        writeU16(out, u + 2, p.srcPort)
        writeU16(out, u + 4, udpLen)
        writeU16(out, u + 6, 0)          // checksum placeholder

        val d = u + 8
        // Transaction ID, echoed.
        out[d] = p.raw[qOff]
        out[d + 1] = p.raw[qOff + 1]
        // QR=1, opcode copied, AA=0, TC=0, RD mirrored from the query.
        val queryRd = p.raw[qOff + 2].toInt() and 0x01
        val queryOpcode = (p.raw[qOff + 2].toInt() shr 3) and 0x0F
        out[d + 2] = (0x80 or (queryOpcode shl 3) or queryRd).toByte()
        // RA=1, Z=0, RCODE=3 (NXDOMAIN).
        out[d + 3] = (0x80 or 0x03).toByte()
        writeU16(out, d + 4, 1)   // qdcount
        writeU16(out, d + 6, 0)   // ancount
        writeU16(out, d + 8, 0)   // nscount
        writeU16(out, d + 10, 0)  // arcount
        System.arraycopy(p.raw, qOff + 12, out, d + 12, questionLen)

        finishTransportChecksum(out, p, u, udpLen, Proto.UDP, checksumOffset = u + 6)
        return out
    }

    /** Length of the first question record (QNAME + qtype + qclass), or null if malformed. */
    private fun dnsQuestionLength(buf: ByteArray, dnsOff: Int, limit: Int): Int? {
        var i = dnsOff + 12
        var guard = 0
        while (i < limit && guard++ < 128) {
            val len = buf[i].toInt() and 0xFF
            if (len == 0) {
                val end = i + 1 + 4        // null label + qtype + qclass
                if (end > limit) return null
                return end - (dnsOff + 12)
            }
            // Compression pointers are not legal in a question QNAME.
            if (len and 0xC0 != 0) return null
            i += len + 1
        }
        return null
    }

    // ------------------------------------------------------------------ helpers

    /** Writes an IP header whose source/destination are [p]'s reversed. */
    private fun writeIpHeader(out: ByteArray, p: Packet, protocol: Int, payloadLength: Int) {
        if (p.ipVersion == 4) {
            out[0] = 0x45
            out[1] = 0
            writeU16(out, 2, 20 + payloadLength)
            writeU16(out, 4, 0)          // identification
            writeU16(out, 6, 0x4000)     // don't fragment
            out[8] = TTL.toByte()
            out[9] = protocol.toByte()
            writeU16(out, 10, 0)         // checksum placeholder
            System.arraycopy(p.dstAddr, 0, out, 12, 4)
            System.arraycopy(p.srcAddr, 0, out, 16, 4)
            Checksums.write(out, 10, Checksums.of(out, 0, 20))
        } else {
            out[0] = 0x60                // version 6, traffic class 0
            out[1] = 0
            out[2] = 0
            out[3] = 0
            writeU16(out, 4, payloadLength)
            out[6] = protocol.toByte()
            out[7] = TTL.toByte()        // hop limit
            System.arraycopy(p.dstAddr, 0, out, 8, 16)
            System.arraycopy(p.srcAddr, 0, out, 24, 16)
        }
    }

    /**
     * Computes and writes the transport checksum over the pseudo-header plus [length]
     * bytes starting at [transportOffset].
     *
     * UDP over IPv4 may legally carry a zero checksum, but we always compute one - a
     * wrong checksum would be dropped silently by the kernel and the app would hang,
     * which is exactly the failure this class exists to avoid.
     */
    private fun finishTransportChecksum(
        out: ByteArray,
        p: Packet,
        transportOffset: Int,
        length: Int,
        protocol: Int,
        checksumOffset: Int,
    ) {
        // Source/destination are reversed relative to the packet we are answering.
        var acc = Checksums.pseudoHeaderSum(p.dstAddr, p.srcAddr, protocol, length)
        acc = Checksums.sum(out, transportOffset, length, acc)
        var cs = Checksums.finish(acc)
        // A zero UDP checksum means "not computed"; RFC 768 says transmit 0xFFFF instead.
        if (cs == 0 && protocol == Proto.UDP) cs = 0xFFFF
        Checksums.write(out, checksumOffset, cs)
    }

    private fun writeU16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun writeU32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v shr 24) and 0xFF).toByte()
        b[off + 1] = ((v shr 16) and 0xFF).toByte()
        b[off + 2] = ((v shr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }
}
