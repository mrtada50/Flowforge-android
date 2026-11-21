package com.flowforge.android.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainAllowlistTest {
    private val list = DomainAllowlist(listOf("example.com", "*.api.test.org", "  "))

    @Test fun exactHostAllowed() = assertTrue(list.isAllowed("https://example.com/path?q=1"))
    @Test fun hostCaseInsensitive() = assertTrue(list.isAllowed("https://EXAMPLE.com"))
    @Test fun wildcardAllowsSubdomain() = assertTrue(list.isAllowed("https://v1.api.test.org/x"))
    @Test fun wildcardRejectsApex() = assertFalse(list.isAllowed("https://api.test.org"))
    @Test fun exactRuleRejectsSubdomain() = assertFalse(list.isAllowed("https://sub.example.com"))
    @Test fun lookalikeRejected() = assertFalse(list.isAllowed("https://evil-example.com"))
    @Test fun suffixTrickRejected() = assertFalse(list.isAllowed("https://example.com.evil.net"))
    @Test fun invalidUrlRejected() = assertFalse(list.isAllowed("not a url"))
    @Test fun nonHttpSchemeRejected() = assertFalse(list.isAllowed("ftp://example.com"))
    @Test fun emptyListAllowsNothing() = assertFalse(DomainAllowlist(emptyList()).isAllowed("https://example.com"))
}
