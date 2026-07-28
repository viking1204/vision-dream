package io.github.xororz.localdream.mcp

import android.content.Context
import java.net.InetAddress
import java.util.Locale

/**
 * Persisted allowlist for the optional LAN MCP listener. Entries are hostnames
 * or literal IP addresses only: URLs, ports, wildcard values and loopback
 * hosts are rejected so the HTTP Host/Origin checks remain unambiguous.
 */
class McpLanHostAllowlist(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hosts(): Set<String> = canonicalize(preferences.getStringSet(HOSTS, emptySet()).orEmpty())

    fun replace(hosts: Collection<String>): Set<String> = canonicalize(hosts).also { normalized ->
        preferences.edit().putStringSet(HOSTS, normalized).apply()
    }

    companion object {
        fun canonicalize(hosts: Collection<String>): Set<String> = hosts.mapNotNull(::normalize).toSet()

        fun normalize(value: String): String? {
            val host = value.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty) ?: return null
            if (host in REJECTED_HOSTS || (':' in host && !isBracketedIpv6(host))) return null
            if (host.startsWith("http://") || host.startsWith("https://") || '*' in host || '/' in host) return null
            val unbracketed = host.removePrefix("[").removeSuffix("]")
            if (isIpv4Literal(unbracketed)) return unbracketed.takeIf(::isAllowedIpv4)
            return when {
                isAllowedIpv4(unbracketed) -> unbracketed
                isIpv6(unbracketed) && !isLoopback(unbracketed) -> "[$unbracketed]"
                DNS_NAME.matches(host) -> host
                else -> null
            }
        }

        private fun isBracketedIpv6(host: String): Boolean = host.startsWith('[') && host.endsWith(']')

        private fun isAllowedIpv4(host: String): Boolean {
            val parts = host.split('.')
            return parts.size == 4 &&
                parts.all { part -> part.toIntOrNull() in 0..255 } &&
                parts.first() != "127"
        }

        private fun isIpv4Literal(host: String): Boolean = host.split('.').let { parts ->
            parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }
        }

        private fun isIpv6(host: String): Boolean = runCatching {
            InetAddress.getByName(host).hostAddress?.contains(':') == true
        }.getOrDefault(false)

        private fun isLoopback(host: String): Boolean = runCatching {
            InetAddress.getByName(host).isLoopbackAddress
        }.getOrDefault(true)

        private val DNS_NAME = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
        private val REJECTED_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0", "::", "[::]")
        private const val PREFERENCES = "mcp_lan_allowlist"
        private const val HOSTS = "hosts"
    }
}
