package com.adshield

import org.junit.Assert.*
import org.junit.Test

class DnsPacketTest {
    // Build an IPv4/UDP DNS query for `name` from 10.111.222.1:40000 -> 10.111.222.2:53, with an EDNS OPT record.
    private fun query(name: String): ByteArray {
        val q = mutableListOf<Byte>()
        q += listOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1).map { it.toByte() }
        for (label in name.split('.')) { q += label.length.toByte(); q += label.toByteArray().toList() }
        q += listOf(0, 0, 1, 0, 1).map { it.toByte() }                       // root, A, IN
        q += listOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0).map { it.toByte() } // OPT RR
        val dns = q.toByteArray()
        val total = 28 + dns.size
        val ip = byteArrayOf(0x45, 0, (total shr 8).toByte(), total.toByte(), 0, 0, 0x40, 0, 64, 17, 0, 0,
            10, 111, 222.toByte(), 1, 10, 111, 222.toByte(), 2,
            (40000 shr 8).toByte(), 40000.toByte(), 0, 53, ((8 + dns.size) shr 8).toByte(), (8 + dns.size).toByte(), 0, 0)
        return ip + dns
    }

    private fun checksumOk(p: ByteArray): Boolean {
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum == 0xFFFF
    }

    @Test fun parsesName() {
        val pkt = query("PageAd2.GoogleSyndication.com")
        assertEquals("pagead2.googlesyndication.com", DnsQuery.parse(pkt, pkt.size)!!.name)
    }

    @Test fun blockedResponseIsValidNxdomain() {
        val pkt = query("ad.doubleclick.net")
        val r = DnsQuery.parse(pkt, pkt.size)!!.blockedResponse()
        assertTrue(checksumOk(r))
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), r.copyOfRange(12, 16)) // src = DNS addr
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), r.copyOfRange(16, 20)) // dst = client
        assertEquals(53, ((r[20].toInt() and 0xFF) shl 8) or (r[21].toInt() and 0xFF))
        assertEquals(40000, ((r[22].toInt() and 0xFF) shl 8) or (r[23].toInt() and 0xFF))
        val dns = r.copyOfRange(28, r.size)
        assertEquals(0x12, dns[0].toInt()); assertEquals(0x34, dns[1].toInt())   // same id
        assertEquals(0x81, dns[2].toInt() and 0xFF)                              // QR + RD
        assertEquals(3, dns[3].toInt() and 0x0F)                                 // NXDOMAIN
        assertEquals(0, dns[11].toInt())                                         // OPT dropped
        assertEquals(12 + 20 + 4, dns.size)                                      // header + qname + type/class
    }

    @Test fun matchesParentDomains() {
        val set = setOf("doubleclick.net", "an.facebook.com")
        assertTrue(Blocklist.isBlocked(set, "googleads.g.doubleclick.net"))
        assertTrue(Blocklist.isBlocked(set, "an.facebook.com"))
        assertFalse(Blocklist.isBlocked(set, "www.facebook.com"))
        assertFalse(Blocklist.isBlocked(set, "notdoubleclick.net"))
    }

    @Test fun ignoresNonDns() {
        val pkt = query("example.com"); pkt[23] = 80 // dst port 80
        assertNull(DnsQuery.parse(pkt, pkt.size))
    }
}
