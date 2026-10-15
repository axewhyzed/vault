package app.vault.android

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int, val code: String, message: String) : Exception(message)

internal object CredentialRefreshCoordinator {
    private val gate = Any()

    fun current(store: EncryptedStore, expectedOrigin: String, expectedUserId: String, refresh: (Credentials) -> Credentials): Credentials = synchronized(gate) {
        val latest = store.credentials() ?: throw ApiFailure(401, "sign_in_required", "Pair this device again.")
        requireOwner(latest, expectedOrigin, expectedUserId)
        if (hasFreshAccess(latest)) latest else rotate(store, latest, expectedOrigin, expectedUserId, refresh)
    }

    fun afterUnauthorized(
        store: EncryptedStore,
        expectedOrigin: String,
        expectedUserId: String,
        rejectedAccessToken: String,
        refresh: (Credentials) -> Credentials,
    ): Credentials = synchronized(gate) {
        val latest = store.credentials() ?: throw ApiFailure(401, "sign_in_required", "Pair this device again.")
        requireOwner(latest, expectedOrigin, expectedUserId)
        // A concurrent worker may have already rotated the exact credential that was rejected.
        if (latest.accessToken != rejectedAccessToken && hasFreshAccess(latest)) latest
        else rotate(store, latest, expectedOrigin, expectedUserId, refresh)
    }

    private fun rotate(store: EncryptedStore, current: Credentials, origin: String, userId: String, refresh: (Credentials) -> Credentials): Credentials {
        val rotated = refresh(current)
        requireOwner(rotated, origin, userId)
        store.saveCredentials(rotated)
        return rotated
    }

    private fun requireOwner(credentials: Credentials, origin: String, userId: String) {
        if (!credentials.origin.equals(origin, ignoreCase = true) || credentials.userId != userId) {
            throw ApiFailure(401, "draft_owner_mismatch", "This queued item belongs to a different Vault account. Share it again.")
        }
    }

    private fun hasFreshAccess(credentials: Credentials): Boolean =
        runCatching { Instant.parse(credentials.accessExpiry).isAfter(Instant.now().plusSeconds(20)) }.getOrDefault(false)
}

class VaultApi(private val store: EncryptedStore, private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.MINUTES).build()) {
    fun pair(payload: PairingPayload, secret: String, deviceName: String): Credentials {
        val key = java.util.UUID.randomUUID().toString()
        val json = JSONObject().put("pairingSessionId", payload.pairingSessionId).put("pairingSecret", secret).put("deviceName", deviceName)
        val result = execute(payload.serverOrigin + "/api/v1/devices/pair", "POST", json.toString(), mapOf("Idempotency-Key" to key))
        val tokens = JSONObject(result)
        require(tokens.getString("tokenType") == "Bearer") { "Unexpected token type." }
        return Credentials(payload.serverOrigin, tokens.getString("accessToken"), tokens.getString("accessTokenExpiresAt"), tokens.getString("refreshToken"), tokens.getString("refreshTokenExpiresAt"), tokens.getString("userId"), tokens.getString("deviceId"))
    }

    fun revokeAll(): Boolean {
        val credentials = store.credentials() ?: throw ApiFailure(401, "sign_in_required", "Pair this device again.")
        val current = CredentialRefreshCoordinator.current(store, credentials.origin, credentials.userId, ::refresh)
        try {
            postRevokeAll(current)
        } catch (e: ApiFailure) {
            if (e.status != 401) throw e
            val updated = CredentialRefreshCoordinator.afterUnauthorized(store, credentials.origin, credentials.userId, current.accessToken, ::refresh)
            postRevokeAll(updated)
        }
        store.clearCredentials()
        return true
    }

    private fun postRevokeAll(credentials: Credentials) {
        val request = Request.Builder().url(credentials.origin + "/api/v1/auth/sessions/revoke-all")
            .header("Authorization", "Bearer ${credentials.accessToken}").post("{}".toRequestBodyJson()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw failure(response.code, response.body?.string().orEmpty())
        }
    }

    fun upload(draft: Draft, onState: (Draft) -> Unit = {}, onProgress: (Long) -> Unit = {}): Draft {
        val credentials = store.credentials() ?: throw ApiFailure(401, "sign_in_required", "Pair this device again.")
        if (!credentials.origin.equals(draft.serverOrigin, ignoreCase = true) || credentials.userId != draft.ownerUserId) {
            throw ApiFailure(401, "draft_owner_mismatch", "This queued item belongs to a different Vault account. Share it again.")
        }
        val current = CredentialRefreshCoordinator.current(store, draft.serverOrigin, draft.ownerUserId, ::refresh)
        try {
            val result = if (draft.text != null) uploadText(draft, current.accessToken) else uploadFile(draft, current.accessToken, onState, onProgress)
            return result.copy(state = DraftState.AVAILABLE, safeError = null)
        } catch (e: ApiFailure) {
            if (e.status == 404 || e.status == 410) throw e
            if (e.status != 401) throw e
            val rotated = CredentialRefreshCoordinator.afterUnauthorized(store, draft.serverOrigin, draft.ownerUserId, current.accessToken, ::refresh)
            val result = if (draft.text != null) uploadText(draft, rotated.accessToken) else uploadFile(draft, rotated.accessToken, onState, onProgress)
            return result.copy(state = DraftState.AVAILABLE, safeError = null)
        }
    }

    private fun refresh(old: Credentials): Credentials {
        val body = JSONObject().put("refreshToken", old.refreshToken).toString()
        val text = try { execute(old.origin + "/api/v1/auth/refresh", "POST", body) } catch (e: IOException) {
            store.clearCredentials()
            throw ApiFailure(401, "refresh_response_lost", "Pair this device again because the refresh response was lost.")
        } catch (e: ApiFailure) {
            if (e.status == 401 || e.code == "invalid_refresh_token") {
                store.clearCredentials()
                throw ApiFailure(401, "sign_in_required", "This phone needs to be paired again.")
            }
            throw e
        }
        val t = JSONObject(text)
        return old.copy(accessToken = t.getString("accessToken"), accessExpiry = t.getString("accessTokenExpiresAt"), refreshToken = t.getString("refreshToken"), refreshExpiry = t.getString("refreshTokenExpiresAt"))
    }

    private fun uploadText(draft: Draft, token: String): Draft {
        val text = draft.text!!
        val candidate = text.trim()
        val validUrl = runCatching { java.net.URI(candidate).let { it.isAbsolute && (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrBlank() && it.rawUserInfo == null && candidate.none(Char::isWhitespace) && candidate.none { ch -> Character.isISOControl(ch) } } }.getOrDefault(false)
        val url = if (validUrl) "/api/v1/items/url" else "/api/v1/items/text"
        val body = if (validUrl) JSONObject().put("url", candidate).put("displayName", draft.displayName).put("retentionDays", draft.retentionDays).toString() else JSONObject().put("text", text).put("displayName", draft.displayName).put("retentionDays", draft.retentionDays).toString()
        execute(draft.serverOrigin + url, "POST", body, mapOf("Authorization" to "Bearer $token", "Idempotency-Key" to draft.idempotencyKey))
        return draft
    }

    private fun uploadFile(initial: Draft, token: String, onState: (Draft) -> Unit, onProgress: (Long) -> Unit): Draft {
        var draft = initial
        val common = mapOf("Authorization" to "Bearer $token", "Idempotency-Key" to draft.idempotencyKey)
        if (draft.uploadId == null) {
            val initBody = JSONObject().put("displayName", draft.displayName).put("contentType", draft.contentType).put("sizeBytes", draft.sizeBytes).put("sha256", draft.sha256).put("retentionDays", draft.retentionDays).toString()
            val init = JSONObject(execute(draft.serverOrigin + "/api/v1/uploads/initiate", "POST", initBody, common))
            draft = draft.copy(uploadId = init.getString("uploadId")); onState(draft)
        }
        val id = draft.uploadId
        val request = Request.Builder().url(draft.serverOrigin + "/api/v1/uploads/$id/content").header("Authorization", "Bearer $token")
            .header("Idempotency-Key", draft.idempotencyKey).put(object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = draft.sizeBytes
                override fun writeTo(sink: BufferedSink) { store.spool(draft.spoolName!!).use { input -> val buffer = ByteArray(64 * 1024); var sent = 0L; var reported = 0L; while (true) { val n = input.read(buffer); if (n < 0) break; sink.write(buffer, 0, n); sent += n; if (sent - reported >= 1_048_576 || sent == draft.sizeBytes) { reported = sent; onProgress(sent) } } } }
            }).build()
        client.newCall(request).execute().use { if (!it.isSuccessful) throw failure(it.code, it.body?.string().orEmpty()) }
        execute(draft.serverOrigin + "/api/v1/uploads/$id/complete", "POST", "{}", common)
        return draft
    }

    private fun execute(url: String, method: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = when (method) { "POST" -> builder.post(body.toRequestBodyJson()).build(); else -> error("Unsupported method") }
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw failure(response.code, text)
            return text
        }
    }

    private fun failure(status: Int, body: String): ApiFailure {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val code = json?.optString("code")?.takeIf { it.isNotBlank() } ?: "http_$status"
        val safe = when (code) {
            "pairing_consumed" -> "This pairing code was already used. Create a fresh QR code and try again."
            "pairing_invalid" -> "This pairing code is invalid or expired. Create a fresh QR code and try again."
            "invalid_refresh_token" -> "This phone needs to be paired again."
            else -> when (status) { 404 -> "Upload was not found."; 410 -> "Upload expired. Share the item again."; 413 -> "Item exceeds the 100 MB file limit."; 401, 403 -> "Session expired or revoked. Pair this device again."; else -> "Upload could not finish ($code)." }
        }
        return ApiFailure(status, code, safe)
    }

    private fun String.toRequestBodyJson() = toRequestBody("application/json; charset=utf-8".toMediaType())
}

fun vaultApi(context: Context): VaultApi = VaultApi(EncryptedStore(context))
