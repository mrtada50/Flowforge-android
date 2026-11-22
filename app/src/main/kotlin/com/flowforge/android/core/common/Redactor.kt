package com.flowforge.android.core.common

/** Masks secrets before anything is logged, previewed or stored. */
object Redactor {
    private val sensitiveHeaders = setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key")
    private val lineRule = Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\b(\s*[:=]\s*)[^\r\n]*""")
    private val pairRule = Regex("""(?i)\b(password|passwd|pwd|token|secret|api[_-]?key|access[_-]?token)\b(["']?\s*[=:]\s*["']?)([^\s&;,"'}\])]+)""")

    fun redact(text: String): String {
        val a = lineRule.replace(text) { it.groupValues[1] + it.groupValues[2] + "***" }
        return pairRule.replace(a) { it.groupValues[1] + it.groupValues[2] + "***" }
    }

    fun headerValue(name: String, value: String): String =
        if (name.lowercase() in sensitiveHeaders) "***" else redact(value)
}
