package com.adshield

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FilterEngineTest {
    private fun engine(vararg rules: String, trusted: Boolean = true) =
        FilterEngine().apply { rules.forEach { add(it, trusted) } }

    @Test fun hostRules() {
        val e = engine("||ads.example.com^", "@@||ok.ads.example.com^")
        assertTrue(e.shouldBlock("https://x.ads.example.com/a.js", "x.ads.example.com"))
        assertFalse(e.shouldBlock("https://ok.ads.example.com/a.js", "ok.ads.example.com"))
        assertFalse(e.shouldBlock("https://example.com/a.js", "example.com"))
    }

    @Test fun pathAndWildcardRules() {
        val e = engine("||youtube.com/pagead/", "/banner/*/ads.js", "|https://track.")
        assertTrue(e.shouldBlock("https://www.youtube.com/pagead/conversion", "www.youtube.com"))
        assertTrue(e.shouldBlock("https://cdn.site.com/banner/v2/ads.js", "cdn.site.com"))
        assertTrue(e.shouldBlock("https://track.site.com/x", "track.site.com"))
        assertFalse(e.shouldBlock("https://site.com/?u=https://track.x", "site.com"))
        assertFalse(e.shouldBlock("https://www.youtube.com/watch?v=1", "www.youtube.com"))
    }

    @Test fun skipsUnsupportedRules() {
        val e = engine("||site.com^\$third-party", "/ad[0-9]+/", "example.com##+js(nobab)",
            "example.com##div:has-text(Ad)", "~example.com##.x", "##.a{}")
        assertFalse(e.shouldBlock("https://site.com/x", "site.com"))
        assertEquals("", e.cssFor("example.com"))
    }

    @Test fun cosmeticRules() {
        val e = engine("##ins.adsbygoogle", "youtube.com##ytd-ad-slot-renderer")
        val yt = e.cssFor("m.youtube.com")
        assertTrue(yt.contains("ins.adsbygoogle{display:none!important}"))
        assertTrue(yt.contains("ytd-ad-slot-renderer{display:none!important}"))
        assertFalse(e.cssFor("example.com").contains("ytd-ad-slot-renderer"))
        // generic rules from downloaded (untrusted) lists are not applied
        assertEquals("", engine("##.ad", trusted = false).cssFor("x.com"))
    }

    @Test fun bundledFiltersParse() {
        val e = FilterEngine()
        File("src/main/assets/filters.txt").forEachLine { e.add(it, true) }
        assertTrue(e.shouldBlock("https://www.youtube.com/api/stats/ads?ver=2", "www.youtube.com"))
        assertTrue(e.shouldBlock("https://www.facebook.com/tr/?id=1", "www.facebook.com"))
        assertTrue(e.cssFor("m.youtube.com").contains("ytm-promoted-video-renderer"))
        assertTrue(e.cssFor("www.google.co.in").contains("#tads"))
    }

    /** Optional: run with -Peasylist=path to measure a real EasyList. */
    @Test fun realEasyList() {
        val path = System.getProperty("easylist") ?: return
        val e = FilterEngine()
        val t = System.nanoTime()
        File(path).forEachLine { e.add(it, false) }
        val parseMs = (System.nanoTime() - t) / 1_000_000
        val t2 = System.nanoTime()
        repeat(1000) { e.shouldBlock("https://cdn.example.org/assets/js/app.bundle.$it.js", "cdn.example.org") }
        val perReqUs = (System.nanoTime() - t2) / 1000 / 1000
        println("EASYLIST ${e.stats()} parse=${parseMs}ms perRequest=${perReqUs}us youtubeCss=${e.cssFor("www.youtube.com").length}B")
        assertTrue(e.shouldBlock("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "pagead2.googlesyndication.com"))
        assertFalse(e.shouldBlock("https://www.wikipedia.org/static/app.js", "www.wikipedia.org"))
    }
}
