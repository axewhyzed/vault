package app.vault.android

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

data class PairingPayload(
    val serverOrigin: String,
    val pairingSessionId: String,
    val pairingSecret: String? = null,
    val expiresAt: String? = null,
) {
    fun toDeepLink(): String = "vault://pair?serverOrigin=${enc(serverOrigin)}&pairingSessionId=${enc(pairingSessionId)}"

    companion object {
        fun parse(raw: String, expectedServerOrigin: String): PairingPayload {
            val value = raw.trim()
            require(value.isNotEmpty()) { "Paste a pairing QR or link." }
            val payload = if (value.startsWith("vault://", true)) parseLink(value) else parseQr(value)
            val uri = URI(payload.serverOrigin)
            require(uri.isAbsolute && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")) { "Server address must be an origin." }
            require(uri.scheme.equals("https", true)) { "Use an HTTPS server address." }
            require(payload.pairingSessionId.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid pairing session." }
            require(payload.pairingSecret == null || payload.pairingSecret.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid pairing secret." }
            val normalizedOrigin = uri.toASCIIString().trimEnd('/')
            val configured = URI(expectedServerOrigin)
            require(configured.scheme == "https" && configured.host != null && configured.userInfo == null && configured.path.orEmpty() in listOf("", "/") && configured.query == null && configured.fragment == null) { "App server origin is misconfigured." }
            require(normalizedOrigin.equals(configured.toASCIIString().trimEnd('/'), ignoreCase = true)) { "This pairing code belongs to a different Vault server." }
            payload.expiresAt?.let { expiry ->
                val expires = try { Instant.parse(expiry) } catch (_: Exception) { throw IllegalArgumentException("Pairing QR has an invalid expiry time.") }
                val now = Instant.now()
                require(expires.isAfter(now) && !expires.isAfter(now.plusSeconds(660))) { "Pairing QR has expired. Create a fresh QR code." }
            }
            return payload.copy(serverOrigin = normalizedOrigin)
        }

        private fun parseQr(raw: String): PairingPayload {
            val json = JSONObject(raw)
            require(json.optInt("version") == 1) { "Unsupported pairing QR version." }
            return PairingPayload(json.getString("serverOrigin"), json.getString("pairingSessionId"), json.getString("pairingSecret"), json.getString("expiresAt"))
        }

        private fun parseLink(raw: String): PairingPayload {
            val uri = URI(raw)
            require(uri.scheme == "vault" && uri.host == "pair" && uri.userInfo == null && uri.fragment == null) { "Invalid pairing link." }
            val entries = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) null else dec(pieces[0]) to dec(pieces[1])
            }
            require(entries.size == 2 && entries.map { it.first }.toSet() == setOf("serverOrigin", "pairingSessionId")) { "Pairing link must contain only one server and session." }
            val query = entries.toMap()
            return PairingPayload(query.getValue("serverOrigin"), query.getValue("pairingSessionId"))
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name())
        private fun dec(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8.name())
    }
}
