package com.flowforge.android.domain.models

enum class BodyType { NONE, JSON, FORM, URL_ENCODED, RAW }

data class HttpRequestSpec(
    val method: String = "GET",
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val query: List<Pair<String, String>> = emptyList(),
    val bodyType: BodyType = BodyType.NONE,
    val body: String = "",
    /** Used for FORM and URL_ENCODED. */
    val fields: List<Pair<String, String>> = emptyList(),
    val followRedirects: Boolean = true,
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 15_000,
    val callTimeoutMs: Long = 30_000,
)

data class HttpResult(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val body: String,
    val bodyTruncated: Boolean,
    val durationMs: Long,
    val finalUrl: String,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

class HttpEngineException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { BLOCKED, INVALID_REQUEST, NETWORK, TIMEOUT }
}
