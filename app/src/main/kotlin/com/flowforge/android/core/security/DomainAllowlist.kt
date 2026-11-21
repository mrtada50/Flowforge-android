package com.flowforge.android.core.security

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Only hosts explicitly listed are allowed. An empty list allows nothing.
 * Entries: "example.com" (exact host) or "*.example.com" (subdomains only, not the apex).
 */
class DomainAllowlist(entries: Collection<String>) {
    private val rules: Set<String> = entries
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun isAllowed(url: String): Boolean {
        val host = url.trim().toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return rules.any { rule ->
            if (rule.startsWith("*.")) {
                val suffix = rule.substring(1) // ".example.com"
                host.endsWith(suffix) && host.length > suffix.length
            } else {
                host == rule
            }
        }
    }
}
