package com.adshield

import android.content.Context

/**
 * Domain blocklist loaded once from assets/blocklist.txt into a HashSet.
 * A domain is blocked if it, or any parent domain, is listed
 * (listing "doubleclick.net" also blocks "ad.doubleclick.net").
 *
 * Accepts plain lines ("example.com") and hosts-file lines ("0.0.0.0 example.com"),
 * so you can paste in bigger public lists.
 */
object Blocklist {
    @Volatile private var domains: Set<String>? = null

    fun get(ctx: Context): Set<String> =
        domains ?: synchronized(this) { domains ?: load(ctx).also { domains = it } }

    private fun load(ctx: Context): Set<String> {
        val set = HashSet<String>(1024)
        ctx.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val domain = line.split(' ', '\t').last { it.isNotEmpty() }
                set.add(domain.lowercase().trimEnd('.'))
            }
        }
        return set
    }

    fun isBlocked(set: Set<String>, name: String): Boolean {
        var d = name
        while (true) {
            if (d in set) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }
}
