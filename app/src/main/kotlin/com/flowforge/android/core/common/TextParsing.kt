package com.flowforge.android.core.common

object TextParsing {
    /** "Name: Value" per line. Blank lines ignored; malformed lines are an error, never silently dropped. */
    fun headers(text: String): List<Pair<String, String>> = lines(text).map { line ->
        val i = line.indexOf(':')
        require(i > 0) { "Invalid header line: $line" }
        line.substring(0, i).trim() to line.substring(i + 1).trim()
    }

    /** "key=value" per line. */
    fun pairs(text: String): List<Pair<String, String>> = lines(text).map { line ->
        val i = line.indexOf('=')
        require(i > 0) { "Invalid key=value line: $line" }
        line.substring(0, i).trim() to line.substring(i + 1)
    }

    private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
