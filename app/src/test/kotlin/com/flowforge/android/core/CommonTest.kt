package com.flowforge.android.core

import com.flowforge.android.core.common.Redactor
import com.flowforge.android.core.common.TextParsing
import com.flowforge.android.execution.UnresolvedVariableException
import com.flowforge.android.execution.VariableResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonTest {
    @Test fun resolvesVariables() =
        assertEquals("hi bob.x", VariableResolver.resolve("hi \${user.name}.x", mapOf("user.name" to "bob")))

    @Test fun unknownVariableIsAnError() {
        assertThrows(UnresolvedVariableException::class.java) { VariableResolver.resolve("\${nope}", emptyMap()) }
    }

    @Test fun replacementIsLiteral() =
        assertEquals("\$1 \\ x", VariableResolver.resolve("\${a} x", mapOf("a" to "\$1 \\")))

    @Test fun redactsPasswordsAndTokens() {
        assertEquals("password=***&x=1", Redactor.redact("password=abc123&x=1"))
        assertEquals("{\"token\":\"***\"}", Redactor.redact("{\"token\":\"abc\"}"))
        assertEquals("Authorization: ***", Redactor.redact("Authorization: Bearer abc.def"))
        assertFalse(Redactor.redact("Cookie: sid=1; other=2").contains("sid"))
    }

    @Test fun redactsSensitiveHeaderValues() {
        assertEquals("***", Redactor.headerValue("Authorization", "Bearer x"))
        assertEquals("text/html", Redactor.headerValue("Content-Type", "text/html"))
    }

    @Test fun parsesHeadersAndPairs() {
        assertEquals(listOf("A" to "1", "B" to "x: y"), TextParsing.headers("A: 1\n\n B : x: y "))
        assertEquals(listOf("a" to "1", "b" to "2=3"), TextParsing.pairs("a=1\nb=2=3"))
    }

    @Test fun malformedLinesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TextParsing.headers("nocolon") }
        assertThrows(IllegalArgumentException::class.java) { TextParsing.pairs("noequals") }
        assertTrue(TextParsing.pairs("").isEmpty())
    }
}
