package com.flowforge.android.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserEngineTest {
    private val json = """{"data":{"items":[{"id":1,"name":"a"},{"id":2,"name":"b"}],"token":"xyz","none":null}}"""

    private fun ok(r: ParseResult) = (r as ParseResult.Success).values

    @Test fun jsonPathNested() = assertEquals(listOf("xyz"), ok(ParserEngine.jsonPath(json, "$.data.token")))
    @Test fun jsonPathIndex() = assertEquals(listOf("b"), ok(ParserEngine.jsonPath(json, "$.data.items[1].name")))
    @Test fun jsonPathWildcard() = assertEquals(listOf("1", "2"), ok(ParserEngine.jsonPath(json, "$.data.items[*].id")))
    @Test fun jsonPathQuotedKey() = assertEquals(listOf("xyz"), ok(ParserEngine.jsonPath(json, "$['data']['token']")))
    @Test fun jsonPathMissingIsFailure() = assertTrue(ParserEngine.jsonPath(json, "$.data.nope") is ParseResult.Failure)
    @Test fun jsonPathNullIsFailure() = assertTrue(ParserEngine.jsonPath(json, "$.data.none") is ParseResult.Failure)
    @Test fun jsonPathBadPathIsFailure() = assertTrue(ParserEngine.jsonPath(json, "data.token") is ParseResult.Failure)
    @Test fun jsonPathInvalidJsonIsFailure() = assertTrue(ParserEngine.jsonPath("{oops", "$.a") is ParseResult.Failure)

    private val html = """<html><body><a class="l" href="/x">First</a><a class="l" href="/y">Second</a></body></html>"""

    @Test fun cssText() = assertEquals(listOf("First", "Second"), ok(ParserEngine.cssSelector(html, "a.l", null)))
    @Test fun cssAttr() = assertEquals(listOf("/x", "/y"), ok(ParserEngine.cssSelector(html, "a.l", "href")))
    @Test fun cssNoMatchIsFailure() = assertTrue(ParserEngine.cssSelector(html, "table", null) is ParseResult.Failure)
    @Test fun cssBadSelectorIsFailure() = assertTrue(ParserEngine.cssSelector(html, "a[", null) is ParseResult.Failure)

    @Test fun regexDefaultsToGroupOne() = assertEquals(listOf("42"), ok(ParserEngine.regex("id=42;", "id=(\\d+)")))
    @Test fun regexWholeMatchWithoutGroups() = assertEquals(listOf("id=42"), ok(ParserEngine.regex("id=42;", "id=\\d+")))
    @Test fun regexExplicitGroup() = assertEquals(listOf("id"), ok(ParserEngine.regex("id=42;", "(\\w+)=(\\d+)", 1)))
    @Test fun regexNoMatchIsFailure() = assertTrue(ParserEngine.regex("abc", "\\d+") is ParseResult.Failure)
    @Test fun regexInvalidIsFailure() = assertTrue(ParserEngine.regex("abc", "(") is ParseResult.Failure)
    @Test fun regexBadGroupIsFailure() = assertTrue(ParserEngine.regex("abc", "a", 5) is ParseResult.Failure)

    @Test fun regexCatastrophicBacktrackingIsStopped() {
        val start = System.nanoTime()
        val r = ParserEngine.regex("a".repeat(60) + "!", "^(a+)+$")
        assertTrue(r is ParseResult.Failure)
        assertTrue("took too long, ${(System.nanoTime() - start) / 1_000_000}ms", (System.nanoTime() - start) < 10_000_000_000L)
    }

    @Test fun headerCaseInsensitive() =
        assertEquals(listOf("abc"), ok(ParserEngine.header(listOf("Set-Cookie" to "abc"), "set-cookie")))
    @Test fun headerMissingIsFailure() = assertTrue(ParserEngine.header(emptyList(), "X") is ParseResult.Failure)

    @Test fun betweenMarkers() = assertEquals(listOf("mid"), ok(ParserEngine.between("a[mid]b", "[", "]")))
    @Test fun betweenMissingMarkerIsFailure() = assertTrue(ParserEngine.between("abc", "[", "]") is ParseResult.Failure)
}
