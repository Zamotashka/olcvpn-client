package org.olcbox.app.data.importer

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.olcbox.app.data.model.ProxyCore
import org.olcbox.app.data.model.SnolcConfig

/**
 * Share-link parser and composer for the SNOLC (github.com/owenewans/snolc) modular network engine.
 *
 * Supported URI formats:
 * 1. Standard URI with endpoint, options and fragment:
 *    snolc://[authToken@]host:port?carrier=tcp&protection=noise&dns=1.1.1.1:53&proxy_link=...#LocationName
 *
 * 2. Embedded custom TOML configuration (base64):
 *    snolc://toml/<base64>#LocationName
 *    or snolc://config?toml=<base64>&proxy_link=...#LocationName
 *
 * Also provides [looksLikeSnolcToml] to detect raw TOML configs pasted from snolc_client.toml.
 */
object SnolcUriParser {

    const val SCHEME = "snolc://"

    data class SnolcLink(
        val name: String,
        val config: SnolcConfig,
    )

    fun parse(uri: String): SnolcLink? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null

        val withoutScheme = trimmed.substring(SCHEME.length)
        val name = withoutScheme.substringAfter('#', "").let(UriCodec::percentDecode)
        val beforeFragment = withoutScheme.substringBefore('#')

        // Embedded TOML format: snolc://toml/<base64>
        if (beforeFragment.startsWith("toml/", ignoreCase = true)) {
            val payload = beforeFragment.substring("toml/".length)
            val decoded = decodeBase64(payload) ?: return null
            return SnolcLink(
                name = name.ifBlank { "SNOLC Custom" },
                config = SnolcConfig(customToml = decoded)
            )
        }

        val query = beforeFragment.substringAfter('?', "")
        val authority = beforeFragment.substringBefore('?')
        val params = if (query.isNotBlank()) UriCodec.parseQuery(query) else emptyMap()

        // Embedded TOML via query: snolc://config?toml=<base64>
        params["toml"]?.let { tomlB64 ->
            val decoded = decodeBase64(tomlB64)
            if (decoded != null && decoded.isNotBlank()) {
                val proxyLink = (params["proxy_link"] ?: params["proxy"]).orEmpty().trim().let(UriCodec::percentDecode)
                val proxyCore = params["proxy_core"]?.let { ProxyCore.fromValue(it) } ?: ProxyCore.Auto
                return SnolcLink(
                    name = (params["name"] ?: name).ifBlank { "SNOLC Custom" },
                    config = SnolcConfig(
                        customToml = decoded,
                        proxyLink = proxyLink,
                        proxyCore = proxyCore,
                    )
                )
            }
        }

        var authToken = ""
        val endpoint: String
        val atIndex = authority.lastIndexOf('@')
        if (atIndex >= 0) {
            authToken = UriCodec.percentDecode(authority.substring(0, atIndex))
            endpoint = authority.substring(atIndex + 1)
        } else {
            endpoint = authority
        }

        val finalAuth = authToken.ifBlank { params["token"] ?: params["auth"] ?: "" }
        val carrier = params["carrier"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: SnolcConfig.CARRIER_TCP
        val protection = params["protection"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: SnolcConfig.PROTECTION_NOISE
        val dns = params["dns"]?.trim()?.takeIf { it.isNotBlank() } ?: SnolcConfig.DEFAULT_DNS
        val proxyLink = (params["proxy_link"] ?: params["proxy"]).orEmpty().trim().let(UriCodec::percentDecode)
        val proxyCore = params["proxy_core"]?.let { ProxyCore.fromValue(it) } ?: ProxyCore.Auto
        val debug = params["debug"] == "1" || params["debug"].equals("true", ignoreCase = true)

        val locationName = (params["name"] ?: name).trim().ifBlank {
            if (endpoint.isNotBlank()) "SNOLC $endpoint" else "SNOLC"
        }

        if (endpoint.isBlank() && params["custom_toml"].isNullOrBlank()) return null

        return SnolcLink(
            name = locationName,
            config = SnolcConfig(
                serverEndpoint = endpoint,
                carrier = carrier,
                protection = protection,
                authToken = finalAuth,
                dnsServer = dns,
                proxyLink = proxyLink,
                proxyCore = proxyCore,
                debug = debug,
            )
        )
    }

    /** Re-emits a shareable `snolc://` URI for a given config. */
    @OptIn(ExperimentalEncodingApi::class)
    fun compose(name: String, snolc: SnolcConfig): String {
        val n = name.trim()
        val fragment = if (n.isNotBlank()) "#" + encodeParam(n) else ""

        if (snolc.customToml.isNotBlank()) {
            val encodedToml = Base64.UrlSafe.encode(snolc.customToml.encodeToByteArray())
            val extra = buildList {
                if (snolc.proxyLink.isNotBlank()) add("proxy_link=${encodeParam(snolc.proxyLink)}")
                if (snolc.proxyCore != ProxyCore.Auto) add("proxy_core=${snolc.proxyCore.value}")
            }
            return if (extra.isEmpty()) {
                "${SCHEME}toml/$encodedToml$fragment"
            } else {
                "${SCHEME}config?toml=$encodedToml&${extra.joinToString("&")}$fragment"
            }
        }

        val auth = if (snolc.authToken.isNotBlank()) encodeParam(snolc.authToken) + "@" else ""
        val queryParts = buildList {
            if (snolc.carrier != SnolcConfig.CARRIER_TCP) add("carrier=${snolc.carrier}")
            if (snolc.protection != SnolcConfig.PROTECTION_NOISE) add("protection=${snolc.protection}")
            if (snolc.dnsServer.isNotBlank() && snolc.dnsServer != SnolcConfig.DEFAULT_DNS) {
                add("dns=${encodeParam(snolc.dnsServer)}")
            }
            if (snolc.proxyLink.isNotBlank()) add("proxy_link=${encodeParam(snolc.proxyLink)}")
            if (snolc.proxyCore != ProxyCore.Auto) add("proxy_core=${snolc.proxyCore.value}")
            if (snolc.debug) add("debug=1")
        }
        val query = if (queryParts.isNotEmpty()) "?" + queryParts.joinToString("&") else ""
        return "$SCHEME$auth${snolc.serverEndpoint}$query$fragment"
    }

    /**
     * Checks if the text looks like a snolc client TOML configuration (e.g. pasted directly
     * from snolc_client.toml).
     */
    fun looksLikeSnolcToml(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.contains("=")) return false
        val hasEngineSection = trimmed.contains("[engine]") || trimmed.contains("[paths]") ||
            trimmed.contains("[carrier]") || trimmed.contains("[protection]") || trimmed.contains("[inbound.socks5]")
        val hasSnolcKeywords = (trimmed.contains("carrier") && (trimmed.contains("tcp") || trimmed.contains("ssh"))) ||
            (trimmed.contains("protection") && (trimmed.contains("noise") || trimmed.contains("tls")))
        return hasEngineSection || hasSnolcKeywords
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64(input: String): String? {
        val clean = input.trim().replace("\n", "").replace("\r", "")
        for (codec in listOf(Base64.UrlSafe, Base64.Default)) {
            val res = runCatching { codec.decode(pad(clean)).decodeToString() }.getOrNull()
            if (!res.isNullOrBlank()) return res
        }
        return null
    }

    private fun pad(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }

    private fun encodeParam(v: String): String = buildString {
        for (b in v.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~") append(ch)
            else {
                append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 0x0F])
            }
        }
    }
}
