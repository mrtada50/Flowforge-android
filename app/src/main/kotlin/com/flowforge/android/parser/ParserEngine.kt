package com.flowforge.android.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

sealed interface ParseResult {
    data class Success(val values: List<String>) : ParseResult {
        val first: String get() = values.first()
    }

    data class Failure(val reason: String) : ParseResult
}

class RegexTimeoutException : RuntimeException("Regex took too long")

private class DeadlineCharSequence(private val inner: CharSequence, private val deadlineNs: Long) : CharSequence {
    override val length: Int get() = inner.length
    override fun get(index: Int): Char {
        if (System.nanoTime() > deadlineNs) throw RegexTimeoutException()
        return inner[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineCharSequence(inner.subSequence(startIndex, endIndex), deadlineNs)
    override fun toString(): String = inner.toString()
}

/** Pure extraction functions. "No match" is always a Failure, never an empty success. */
object ParserEngine {
    private const val REGEX_BUDGET_MS = 1500L

    private sealed interface Seg {
        data class Key(val name: String) : Seg
        data class Index(val i: Int) : Seg
        data object Wildcard : Seg
    }

    private fun tokenize(path: String): List<Seg>? {
        val p = path.trim()
        if (!p.startsWith("$")) return null
        val s = p.substring(1)
        val out = mutableListOf<Seg>()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '.' -> {
                    i++
                    val st = i
                    while (i < s.length && s[i] != '.' && s[i] != '[') i++
                    val name = s.substring(st, i)
                    if (name.isEmpty()) return null
                    out.add(if (name == "*") Seg.Wildcard else Seg.Key(name))
                }
                '[' -> {
                    val end = s.indexOf(']', i)
                    if (end < 0) return null
                    val inner = s.substring(i + 1, end).trim()
                    i = end + 1
                    val quoted = inner.length >= 2 &&
                        ((inner.startsWith("'") && inner.endsWith("'")) || (inner.startsWith("\"") && inner.endsWith("\"")))
                    val idx = inner.toIntOrNull()
                    when {
                        inner == "*" -> out.add(Seg.Wildcard)
                        quoted -> out.add(Seg.Key(inner.substring(1, inner.length - 1)))
                        idx != null -> out.add(Seg.Index(idx))
                        else -> return null
                    }
                }
                else -> return null
            }
        }
        return out
    }

    fun jsonPath(json: String, path: String): ParseResult {
        val segs = tokenize(path) ?: return ParseResult.Failure("Invalid JSONPath: $path")
        val root: JsonElement = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return ParseResult.Failure("Invalid JSON: ${e.message?.lineSequence()?.firstOrNull() ?: "parse error"}")
        }
        var current: List<JsonElement> = listOf(root)
        for (seg in segs) {
            current = current.flatMap { el ->
                when (seg) {
                    is Seg.Key -> (el as? JsonObject)?.get(seg.name)?.let { listOf(it) } ?: emptyList()
                    is Seg.Index -> (el as? JsonArray)?.getOrNull(seg.i)?.let { listOf(it) } ?: emptyList()
                    Seg.Wildcard -> when (el) {
                        is JsonArray -> el.toList()
                        is JsonObject -> el.values.toList()
                        else -> emptyList()
                    }
                }
            }
        }
        val values = current.filter { it !is JsonNull }.map { if (it is JsonPrimitive) it.content else it.toString() }
        return if (values.isEmpty()) ParseResult.Failure("JSONPath matched nothing: $path") else ParseResult.Success(values)
    }

    fun cssSelector(html: String, selector: String, attr: String?): ParseResult {
        val elements = try {
            Jsoup.parse(html).select(selector)
        } catch (e: Exception) {
            return ParseResult.Failure("Invalid selector: ${e.message}")
        }
        val values = elements.map { if (attr.isNullOrBlank()) it.text() else it.attr(attr) }.filter { it.isNotEmpty() }
        return if (values.isEmpty()) ParseResult.Failure("Selector matched nothing: $selector") else ParseResult.Success(values)
    }

    /** group == null: group 1 when the pattern has one, otherwise the whole match. */
    fun regex(text: String, pattern: String, group: Int? = null): ParseResult {
        val re = try {
            Regex(pattern)
        } catch (e: Exception) {
            return ParseResult.Failure("Invalid regex: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val input = DeadlineCharSequence(text, System.nanoTime() + REGEX_BUDGET_MS * 1_000_000)
        val match = try {
            re.find(input)
        } catch (e: RegexTimeoutException) {
            return ParseResult.Failure("Regex took too long (possible catastrophic backtracking)")
        } ?: return ParseResult.Failure("Regex did not match")
        val g = group ?: if (match.groupValues.size > 1) 1 else 0
        if (g < 0 || g >= match.groupValues.size) return ParseResult.Failure("Regex has no group $g")
        return ParseResult.Success(listOf(match.groupValues[g]))
    }

    fun header(headers: List<Pair<String, String>>, name: String): ParseResult {
        val values = headers.filter { it.first.equals(name.trim(), ignoreCase = true) }.map { it.second }
        return if (values.isEmpty()) ParseResult.Failure("Header not found: $name") else ParseResult.Success(values)
    }

    fun between(text: String, left: String, right: String): ParseResult {
        if (left.isEmpty() || right.isEmpty()) return ParseResult.Failure("Both markers are required")
        val s = text.indexOf(left)
        if (s < 0) return ParseResult.Failure("Left marker not found")
        val from = s + left.length
        val e = text.indexOf(right, from)
        if (e < 0) return ParseResult.Failure("Right marker not found")
        return ParseResult.Success(listOf(text.substring(from, e)))
    }
}
