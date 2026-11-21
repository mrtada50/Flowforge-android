package com.flowforge.android.domain.models

enum class ProxyType { HTTP, HTTPS, SOCKS5 }

data class ProxyConfig(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    /** Never prints the password. */
    override fun toString(): String = "$type://$host:$port"
}
