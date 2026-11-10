package com.adshield

import java.nio.ByteBuffer

/**
 * One DNS query pulled out of an IPv4/UDP packet read from the TUN interface.
 * Only what's needed to answer it is kept, so no large buffers stay alive.
 */
class DnsQuery(
    private val srcIp: ByteArray,
    private val dstIp: ByteArray,
    private val srcPort: Int,
    private val dstPort: Int,
    val payload: ByteArray,     // raw DNS message
    val name: String,           // queried domain, lower-case, no trailing dot
    private val questionEnd: Int // offset just past the first question
) {
    /** NXDOMAIN answer: tells the app the ad domain doesn't exist, so it fails instantly. */
    fun blockedResponse(): ByteArray {
        val dns = payload.copyOf(questionEnd)
        dns[2] = (0x80 or (dns[2].toInt() and 0x79)).toByte() // QR=1, keep opcode + RD
        dns[3] = 0x83.toByte()                                  // RA=1, RCODE=3 (NXDOMAIN)
        dns[4] = 0; dns[5] = 1                                  // QDCOUNT=1
        for (i in 6..11) dns[i] = 0                             // AN/NS/AR = 0
        return wrapResponse(dns, dns.size)
    }

    /** Wrap a DNS answer in IPv4+UDP headers addressed back to the asking app. */
    fun wrapResponse(dns: ByteArray, len: Int): ByteArray {
        val total = 20 + 8 + len
        val b = ByteBuffer.allocate(total)
        // IPv4 header
        b.put(0x45.toByte()).put(0)
        b.putShort(total.toShort())
        b.putShort(0).putShort(0x4000.toShort())  // id, flags=DF
        b.put(64).put(17).putShort(0)             // TTL, proto=UDP, checksum (filled below)
        b.put(dstIp).put(srcIp)                   // swap src/dst
        // UDP header (checksum 0 = "not computed", valid for IPv4)
        b.putShort(dstPort.toShort()).putShort(srcPort.toShort())
        b.putShort((8 + len).toShort()).putShort(0)
        b.put(dns, 0, len)
        val arr = b.array()
        val sum = ipChecksum(arr)
        arr[10] = (sum shr 8).toByte(); arr[11] = sum.toByte()
        return arr
    }

    companion object {
        /** Returns null for anything that isn't a well-formed IPv4 UDP DNS query. */
        fun parse(buf: ByteArray, len: Int): DnsQuery? {
            if (len < 28 || (buf[0].toInt() shr 4) != 4) return null
            val ihl = (buf[0].toInt() and 0x0F) * 4
            if (buf[9].toInt() != 17 || len < ihl + 8 + 12) return null
            val dstPort = u16(buf, ihl + 2)
            if (dstPort != 53) return null
            val srcPort = u16(buf, ihl)
            val udpLen = u16(buf, ihl + 4)
            val dnsStart = ihl + 8
            val dnsLen = minOf(udpLen - 8, len - dnsStart)
            if (dnsLen < 12) return null
            val dns = buf.copyOfRange(dnsStart, dnsStart + dnsLen)
            if (dns[2].toInt() and 0x80 != 0 || u16(dns, 4) < 1) return null // not a query

            // Read QNAME (questions are never compressed)
            val sb = StringBuilder(64)
            var p = 12
            while (true) {
                if (p >= dns.size) return null
                val l = dns[p].toInt() and 0xFF
                if (l == 0) { p++; break }
                if (l and 0xC0 != 0 || p + 1 + l > dns.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                for (i in p + 1..p + l) sb.append(Char(dns[i].toInt() and 0xFF).lowercaseChar())
                p += 1 + l
            }
            val qEnd = p + 4 // QTYPE + QCLASS
            if (qEnd > dns.size) return null
            return DnsQuery(
                buf.copyOfRange(12, 16), buf.copyOfRange(16, 20),
                srcPort, dstPort, dns, sb.toString(), qEnd
            )
        }

        private fun u16(b: ByteArray, o: Int) =
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        private fun ipChecksum(p: ByteArray): Int {
            var sum = 0
            for (i in 0 until 20 step 2) sum += u16(p, i)
            while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            return sum.inv() and 0xFFFF
        }
    }
}
