package com.adshield

import android.content.Context
import java.io.File

/**
 * Ad-block filter engine for the built-in browser. Understands the common subset of
 * Adblock Plus / EasyList syntax (the format Brave and uBlock Origin use):
 *
 *   ||ads.example.com^        block a domain and its subdomains
 *   /banner/ads/              block URLs containing this text (`*` and `^` act as wildcards)
 *   @@||good.example.com^     exception (never block)
 *   ##.ad-box                 hide matching elements on every site (bundled list only)
 *   youtube.com##.ytp-ad-module   hide elements on one site
 *
 * Rules with $options, regex rules and procedural/scriptlet cosmetics are skipped:
 * they can't be applied faithfully here, and guessing would break pages.
 */
class FilterEngine internal constructor() {

    private class Pattern(private val parts: Array<String>, private val anchored: Boolean) {
        fun matches(url: String): Boolean {
            var from = 0
            for (i in parts.indices) {
                val j = url.indexOf(parts[i], from)
                if (j < 0 || (i == 0 && anchored && j != 0)) return false
                from = j + parts[i].length
            }
            return true
        }

        /** Longest whole word (letters/digits, >=3 chars) the URL must contain, used as index key. */
        val token: String? = parts.flatMap { part ->
            tokens(part).filter { (start, word) -> start > 0 && start + word.length < part.length }
                .map { it.second }
        }.maxByOrNull { it.length }
    }

    /**
     * Rules indexed by a keyword (the same trick Brave's adblock engine uses): a URL is only
     * tested against rules whose keyword appears in it, instead of against every rule.
     */
    private class PatternSet {
        private val byToken = HashMap<String, ArrayList<Pattern>>()
        private val noToken = ArrayList<Pattern>()
        val size get() = byToken.values.sumOf { it.size } + noToken.size
        val unindexed get() = noToken.size

        fun add(p: Pattern) {
            val t = p.token
            if (t == null) noToken.add(p) else byToken.getOrPut(t) { ArrayList(1) }.add(p)
        }

        fun matches(url: String, urlTokens: Collection<String>): Boolean {
            for (t in urlTokens) byToken[t]?.let { list -> if (list.any { it.matches(url) }) return true }
            return noToken.any { it.matches(url) }
        }
    }

    private val blockHosts = HashSet<String>(8192)
    private val allowHosts = HashSet<String>()
    private val blockPatterns = PatternSet()
    private val allowPatterns = PatternSet()

    internal fun stats() = "hosts=${blockHosts.size} patterns=${blockPatterns.size} unindexed=${blockPatterns.unindexed}"
    private val genericCss = ArrayList<String>()
    private val siteCss = HashMap<String, MutableList<String>>()
    private val cssCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 32
    }
    var loadedAt = 0L
        private set

    companion object {
        /** Downloadable lists (saved in filesDir by MainActivity). */
        val REMOTE_LISTS = mapOf(
            "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
            "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
        )
        private val UNSUPPORTED_CSS = listOf(
            ":-abp-", ":has-text(", ":upward(", ":xpath(", ":matches-", ":remove(", ":style(",
            ":min-text-length(", ":watch-attr(", ":others(", ":if(", ":if-not(", "{", "}"
        )

        /** (startIndex, word) for every run of >=3 letters/digits in s. */
        private fun tokens(s: String): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>()
            var start = -1
            for (i in 0..s.length) {
                val word = i < s.length && s[i].isLetterOrDigit()
                if (word && start < 0) start = i
                if (!word && start >= 0) {
                    if (i - start >= 3) out.add(start to s.substring(start, i))
                    start = -1
                }
            }
            return out
        }

        @Volatile private var instance: FilterEngine? = null

        fun get(ctx: Context): FilterEngine =
            instance ?: synchronized(this) { instance ?: build(ctx).also { instance = it } }

        /** Reload if the downloaded lists changed since this engine was built. */
        fun reloadIfStale(ctx: Context) {
            val e = instance ?: return
            val newest = REMOTE_LISTS.keys.maxOf { File(ctx.filesDir, it).lastModified() }
            if (newest > e.loadedAt) synchronized(this) { instance = null }
        }

        private fun build(ctx: Context): FilterEngine {
            val e = FilterEngine()
            e.blockHosts.addAll(Blocklist.get(ctx))
            ctx.assets.open("filters.txt").bufferedReader().useLines { s -> s.forEach { e.add(it, true) } }
            for (name in REMOTE_LISTS.keys) {
                val f = File(ctx.filesDir, name)
                if (f.exists()) f.bufferedReader().useLines { s -> s.forEach { e.add(it, false) } }
            }
            e.loadedAt = System.currentTimeMillis()
            return e
        }
    }

    /** @param trusted bundled list: its generic (all-site) cosmetic rules are used too. */
    internal fun add(raw: String, trusted: Boolean) {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return

        val cosmetic = line.indexOf("##")
        if (cosmetic >= 0) { addCosmetic(line.substring(0, cosmetic), line.substring(cosmetic + 2), trusted); return }
        if ("#@#" in line || "#?#" in line || "#$#" in line || "#%#" in line || '$' in line) return

        var r = line
        val allow = r.startsWith("@@")
        if (allow) r = r.substring(2)
        if (r.length > 2 && r.startsWith("/") && r.endsWith("/")) return // regex rule

        if (r.startsWith("||")) {
            val body = r.substring(2).removeSuffix("^")
            if (body.isNotEmpty() && body.all { it.isLetterOrDigit() || it == '.' || it == '-' }) {
                (if (allow) allowHosts else blockHosts).add(body.lowercase())
                return
            }
            r = body
        }
        val anchored = r.startsWith("|")
        if (anchored) r = r.substring(1)
        r = r.removeSuffix("|")
        val parts = r.lowercase().split('*', '^').filter { it.isNotEmpty() }
        if (parts.sumOf { it.length } < 5) return // too short: would over-block
        (if (allow) allowPatterns else blockPatterns).add(Pattern(parts.toTypedArray(), anchored))
    }

    private fun addCosmetic(domains: String, selector: String, trusted: Boolean) {
        val sel = selector.trim()
        if (sel.isEmpty() || sel.startsWith("+js") || UNSUPPORTED_CSS.any { it in sel }) return
        if (domains.isEmpty()) { if (trusted) genericCss.add(sel); return }
        val list = domains.split(',').map { it.trim().lowercase() }
        if (list.any { it.startsWith("~") || it.isEmpty() }) return
        for (d in list) siteCss.getOrPut(d) { ArrayList(2) }.add(sel)
    }

    fun shouldBlock(url: String, host: String): Boolean {
        val u = url.lowercase()
        val h = host.lowercase()
        val words = tokens(u).mapTo(HashSet()) { it.second }
        if (Blocklist.isBlocked(allowHosts, h) || allowPatterns.matches(u, words)) return false
        return Blocklist.isBlocked(blockHosts, h) || blockPatterns.matches(u, words)
    }

    /** CSS that hides ad elements on this host (one rule per selector, so a bad one can't break the rest). */
    fun cssFor(host: String): String = synchronized(cssCache) {
        cssCache.getOrPut(host.lowercase()) {
            val sb = StringBuilder()
            fun emit(s: String) { sb.append(s).append("{display:none!important}\n") }
            genericCss.forEach(::emit)
            var d = host.lowercase()
            while (true) {
                siteCss[d]?.forEach(::emit)
                val dot = d.indexOf('.')
                if (dot < 0) break
                d = d.substring(dot + 1)
            }
            sb.toString()
        }
    }
}
