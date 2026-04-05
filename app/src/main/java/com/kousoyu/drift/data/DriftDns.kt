package com.kousoyu.drift.data

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress

// ─── DoH-Backed DNS Resolver ──────────────────────────────────────────────────
//
// Problem: Chinese ISPs (联通/电信/移动) perform aggressive DNS poisoning:
//   • manhuagui.com → returns a blocked mainland IP (connection refused)
//   • 18comic domains  → returns a fake IPv6 address (connection timeout)
//
// Solution: Resolve hostnames through Cloudflare DoH (DNS-over-HTTPS) at
//   1.1.1.1/dns-query which bypasses the local ISP's corrupt resolver.
//
// Fallback 1: Alibaba cloud DNS (223.5.5.5) for better mainland routing.
// Fallback 2: System DNS — in case both DoH services are unreachable.
//
// Extra guard: IPv6 addresses from system DNS are filtered out to prevent
//   the poisoned-IPv6 trap common on *.18comic.* mirrors.
//

object DriftDns : Dns {

    // Minimal OkHttp client that ONLY handles DoH queries.
    // Uses system DNS here intentionally — 1.1.1.1 resolves correctly everywhere
    // because the IP address is hardcoded, not looked up via the poisoned resolver.
    private val doHClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .dns(Dns.SYSTEM)  // Use system DNS only to reach the DoH server itself
        .build()

    // DoH providers (tried in order)
    private val doHProviders = listOf(
        "https://1.1.1.1/dns-query",    // Cloudflare
        "https://223.5.5.5/dns-query"   // Alibaba Cloud (better ping in mainland)
    )

    override fun lookup(hostname: String): List<InetAddress> {
        // ── Step 1: Try DoH providers ─────────────────────────────────────────
        for (provider in doHProviders) {
            try {
                val url = "$provider?name=${hostname}&type=A"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", "Drift/1.0")
                    .build()

                val resp = doHClient.newCall(req).execute()
                if (!resp.isSuccessful) continue

                val body = resp.body?.string() ?: continue

                // Parse "Answer":[...{"data":"1.2.3.4"}...]  via simple regex
                // Avoids a Gson/Moshi dependency just for two fields.
                val ipPattern = Regex(""""data"\s*:\s*"([\d.]+)"""")
                val ips = ipPattern.findAll(body)
                    .map { it.groupValues[1] }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
                    .toList()

                if (ips.isNotEmpty()) return ips
            } catch (_: Exception) {
                // Try next provider
            }
        }

        // ── Step 2: System DNS filtered to IPv4 only ──────────────────────────
        // This prevents the poisoned-IPv6 trap (e.g. 2001::xxx for 18comic mirrors).
        return try {
            Dns.SYSTEM.lookup(hostname)
                .filter { it is java.net.Inet4Address }
                .ifEmpty { Dns.SYSTEM.lookup(hostname) }  // if all IPv6 somehow, allow them
        } catch (e: Exception) {
            throw e
        }
    }
}
