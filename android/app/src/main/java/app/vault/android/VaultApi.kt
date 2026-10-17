package app.vault.android

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    fun deviceLogin(serverOrigin: String, identifier: String, password: String, deviceName: String): Result<Credentials> = runCatching {
        val origin = serverOrigin.trim().removeSuffix("/")
        val body = JSONObject().apply {
            put("identifier", identifier.trim())
            put("password", password)
            put("deviceName", deviceName.trim())
            put("platform", "android")
        }.toString()
        val result = execute("$origin/api/v1/devices/login", "POST", body)
        val tokens = JSONObject(result)
        require(tokens.getString("tokenType") == "Bearer") { "Unexpected token type." }
        val creds = Credentials(
            origin = origin,
            accessToken = tokens.getString("accessToken"),
            accessExpiry = tokens.getString("accessTokenExpiresAt"),
            refreshToken = tokens.getString("refreshToken"),
            refreshExpiry = tokens.getString("refreshTokenExpiresAt"),
            userId = tokens.getString("userId"),
            deviceId = tokens.getString("deviceId")
        )
        store.saveCredentials(creds)
        creds
    }

    fun revokeCurrentDevice(): Result<Unit> = runCatching {
        val credentials = store.credentials() ?: throw ApiFailure(401, "sign_in_required", "Pair this device again.")
        val current = CredentialRefreshCoordinator.current(store, credentials.origin, credentials.userId, ::refresh)
        try {
            postRevokeCurrent(current)
        } catch (e: ApiFailure) {
            if (e.status != 401) throw e
            val updated = CredentialRefreshCoordinator.afterUnauthorized(store, credentials.origin, credentials.userId, current.accessToken, ::refresh)
            postRevokeCurrent(updated)
        }
        store.clearCredentials()
    }

    private fun postRevokeCurrent(credentials: Credentials) {
        val request = Request.Builder().url(credentials.origin + "/api/v1/devices/revoke-current")
            .header("Authorization", "Bearer ${credentials.accessToken}").post("{}".toRequestBodyJson()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 204) throw failure(response.code, response.body?.string().orEmpty())
        }
    }

    fun ping(): Long? {
        val origin = store.credentials()?.origin ?: return null
        val start = System.currentTimeMillis()
        return try {
            val request = Request.Builder().url("$origin/health/live").get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) System.currentTimeMillis() - start else null
            }
        } catch (_: Exception) {
            null
        }
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

    fun listItems(cursor: String? = null, limit: Int = 30): InboxPage {
        if (limit !in 1..100) throw ApiFailure(400, "invalid_limit", "The inbox page size is invalid.")
        val credentials = store.credentials() ?: throw ApiFailure(401, "sign_in_required", "Pair this device again.")
        val base = apiBase(credentials.origin) ?: throw ApiFailure(401, "invalid_origin", "This device needs to be paired again.")
        fun request(token: String): okhttp3.Response {
            val url = base.newBuilder().addPathSegments("api/v1/items").addQueryParameter("limit", limit.toString())
                .apply { if (cursor != null) addQueryParameter("cursor", cursor) }.build()
            return client.newCall(Request.Builder().url(url).header("Accept", "application/json")
                .header("Cache-Control", "no-store").header("Authorization", "Bearer $token").get().build()).execute()
        }
        val current = CredentialRefreshCoordinator.current(store, credentials.origin, credentials.userId, ::refresh)
        val response = request(current.accessToken)
        val finalResponse = if (response.code == 401) {
            response.close()
            val rotated = CredentialRefreshCoordinator.afterUnauthorized(store, credentials.origin, credentials.userId, current.accessToken, ::refresh)
            request(rotated.accessToken)
        } else response
        return finalResponse.use { res ->
            if (!res.isSuccessful) throw inboxFailure(res.code)
            val json = try { JSONObject(res.body?.string().orEmpty()) } catch (_: Exception) { throw malformedInbox() }
            try {
                val itemsJson = json.getJSONArray("items")
                val items = (0 until itemsJson.length()).map { parseSummary(itemsJson.getJSONObject(it), credentials) }
                val quotaJson = json.getJSONObject("quota")
                InboxPage(items, json.optString("nextCursor").takeUnless { it.isEmpty() || it == "null" }, InboxQuota(
                    quotaJson.getLong("activeBytes"), quotaJson.getLong("maxActiveBytes"), quotaJson.getLong("activeItems"), quotaJson.getInt("maxActiveItems")))
            } catch (_: Exception) { throw malformedInbox() }
        }
    }

    fun getItem(id: String): InboxDetail {
        val itemId = canonicalUuid(id) ?: throw ApiFailure(400, "invalid_item_id", "This inbox item could not be opened.")
        val credentials = store.credentials() ?: throw ApiFailure(401, "sign_in_required", "Pair this device again.")
        val base = apiBase(credentials.origin) ?: throw ApiFailure(401, "invalid_origin", "This device needs to be paired again.")
        fun request(token: String) = client.newCall(Request.Builder().url(base.newBuilder().addPathSegments("api/v1/items").addPathSegment(itemId).build())
            .header("Accept", "application/json").header("Cache-Control", "no-store").header("Authorization", "Bearer $token").get().build()).execute()
        val current = CredentialRefreshCoordinator.current(store, credentials.origin, credentials.userId, ::refresh)
        val response = request(current.accessToken)
        val finalResponse = if (response.code == 401) {
            response.close()
            val rotated = CredentialRefreshCoordinator.afterUnauthorized(store, credentials.origin, credentials.userId, current.accessToken, ::refresh)
            request(rotated.accessToken)
        } else response
        return finalResponse.use { res ->
            if (!res.isSuccessful) throw inboxFailure(res.code)
            val json = try { JSONObject(res.body?.string().orEmpty()) } catch (_: Exception) { throw malformedInbox() }
            try {
                val summary = parseSummary(json, credentials)
                if (summary.id != itemId) throw IllegalArgumentException()
                val text = if (json.isNull("text")) null else json.getString("text")
                val downloadUrl = if (json.isNull("downloadUrl")) null else json.getString("downloadUrl")
                val sha256 = if (json.isNull("sha256")) null else json.getString("sha256")
                summary.asDetail(text, json.getString("contentType"), downloadUrl, sha256)
            } catch (_: Exception) { throw malformedInbox() }
        }
    }

    internal fun executeAuthedRequest(buildRequest: (HttpUrl, String) -> Request): okhttp3.Response {
        val credentials = store.credentials() ?: throw ApiFailure(401, "sign_in_required", "Pair this device again.")
        val base = apiBase(credentials.origin) ?: throw ApiFailure(401, "invalid_origin", "This device needs to be paired again.")
        val current = CredentialRefreshCoordinator.current(store, credentials.origin, credentials.userId, ::refresh)
        val response = client.newCall(buildRequest(base, current.accessToken)).execute()
        return if (response.code == 401) {
            response.close()
            val rotated = CredentialRefreshCoordinator.afterUnauthorized(store, credentials.origin, credentials.userId, current.accessToken, ::refresh)
            client.newCall(buildRequest(base, rotated.accessToken)).execute()
        } else {
            response
        }
    }

    suspend fun deleteItem(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val itemId = canonicalUuid(id) ?: throw ApiFailure(400, "invalid_item_id", "This inbox item could not be deleted.")
            val response = executeAuthedRequest { base, token ->
                val url = base.newBuilder().addPathSegments("api/v1/items").addPathSegment(itemId).build()
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-store")
                    .header("Authorization", "Bearer $token")
                    .delete()
                    .build()
            }
            response.use { res ->
                if (res.code == 200 || res.code == 204) {
                    Unit
                } else {
                    throw inboxFailure(res.code)
                }
            }
        }
    }

    fun downloadToTemp(detail: InboxDetail, onProgress: (Long) -> Unit = {}): File {
        val itemId = canonicalUuid(detail.id) ?: throw ApiFailure(400, "invalid_item_id", "This inbox item could not be downloaded.")
        if (detail.type != "file" || detail.downloadUrl != "/api/v1/items/$itemId/download" || detail.sizeBytes !in 0..MAX_INBOX_FILE_BYTES ||
            detail.sha256?.matches(Regex("[0-9a-fA-F]{64}")) != true) {
            throw ApiFailure(400, "invalid_download", "This file cannot be downloaded safely.")
        }
        val expected = store.credentials() ?: throw ApiFailure(401, "sign_in_required", "Pair this device again.")
        if (detail.ownerOrigin.isBlank() || detail.ownerUserId.isBlank() || !expected.origin.equals(detail.ownerOrigin, true) || expected.userId != detail.ownerUserId) {
            throw ApiFailure(401, "draft_owner_mismatch", "This item belongs to a different Vault account.")
        }
        val base = apiBase(detail.ownerOrigin) ?: throw ApiFailure(401, "invalid_origin", "This device needs to be paired again.")
        val url = base.newBuilder().addPathSegments("api/v1/items").addPathSegment(itemId).addPathSegment("download").build()
        val noRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).build()
        fun request(token: String) = noRedirectClient.newCall(Request.Builder().url(url).header("Accept", "application/octet-stream")
            .header("Cache-Control", "no-store").header("Authorization", "Bearer $token").get().build()).execute()
        val current = CredentialRefreshCoordinator.current(store, detail.ownerOrigin, detail.ownerUserId, ::refresh)
        val response = request(current.accessToken)
        val finalResponse = if (response.code == 401) {
            response.close()
            val rotated = CredentialRefreshCoordinator.afterUnauthorized(store, detail.ownerOrigin, detail.ownerUserId, current.accessToken, ::refresh)
            request(rotated.accessToken)
        } else response
        val temp = try { File.createTempFile("vault-inbox-", ".part", File(System.getProperty("java.io.tmpdir") ?: ".")) }
        catch (_: IOException) { throw ApiFailure(503, "download_storage_unavailable", "There is not enough private storage to download this file.") }
        return try {
            finalResponse.use { res ->
                if (res.code != 200) throw inboxFailure(res.code)
                val disposition = res.header("Content-Disposition")?.trim()
                if (res.header("Content-Range") != null || disposition?.matches(Regex("(?i)^attachment(?:\\s*;.*)?$")) != true ||
                    !res.header("X-Content-Type-Options").equals("nosniff", true) ||
                    !res.header("Cache-Control").orEmpty().contains("no-store", true)) {
                    throw ApiFailure(502, "invalid_download_response", "The server returned an unexpected file response.")
                }
                val body = res.body ?: throw ApiFailure(502, "invalid_download_response", "The server returned an empty file response.")
                if (body.contentLength() >= 0 && body.contentLength() != detail.sizeBytes) throw ApiFailure(502, "invalid_download_size", "The downloaded file did not match its expected size.")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                FileOutputStream(temp).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (received + count > MAX_INBOX_FILE_BYTES || received + count > detail.sizeBytes) {
                                throw ApiFailure(502, "invalid_download_size", "The downloaded file exceeded its expected size.")
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            received += count
                            onProgress(received)
                        }
                    }
                }
                if (received != detail.sizeBytes) throw ApiFailure(502, "invalid_download_size", "The downloaded file did not match its expected size.")
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(detail.sha256, true)) throw ApiFailure(502, "invalid_download_hash", "The downloaded file could not be verified.")
            }
            temp
        } catch (e: Exception) {
            temp.delete()
            if (e is ApiFailure) throw e
            if (e is IOException) throw ApiFailure(503, "download_interrupted", "The file download was interrupted. Try again.")
            throw ApiFailure(502, "download_failed", "The file could not be downloaded safely.")
        }
    }

    private fun apiBase(origin: String): HttpUrl? = "$origin/".toHttpUrlOrNull()?.takeIf { it.encodedPath == "/" && it.query == null && it.fragment == null && it.username.isEmpty() && it.password.isEmpty() }

    private fun parseSummary(json: JSONObject, credentials: Credentials): InboxSummary {
        val id = canonicalUuid(json.getString("id")) ?: throw IllegalArgumentException()
        val type = json.getString("type")
        if (type !in setOf("text", "url", "file")) throw IllegalArgumentException()
        val textContent = if (json.isNull("textContent")) null else json.getString("textContent")
        return InboxSummary(id, type, json.getString("state"), json.getString("displayName"), json.getLong("sizeBytes"),
            json.getString("createdAt"), json.getString("availableAt"), json.getString("expiresAt"), json.getInt("version"),
            json.getJSONObject("sourceDevice").let { InboxSourceDevice(it.getString("id"), it.getString("name"), it.getString("platform")) },
            textContent,
            credentials.origin, credentials.userId)
    }

    private fun canonicalUuid(value: String): String? = try { UUID.fromString(value).toString().takeIf { it.equals(value, true) } } catch (_: Exception) { null }

    private fun inboxFailure(status: Int): ApiFailure = when (status) {
        401, 403 -> ApiFailure(401, "sign_in_required", "Session expired or revoked. Pair this device again.")
        404 -> ApiFailure(404, "item_not_found", "This inbox item was not found.")
        410 -> ApiFailure(410, "item_expired", "This inbox item has expired.")
        503 -> ApiFailure(503, "service_unavailable", "Vault is temporarily unavailable. Try again shortly.")
        else -> ApiFailure(status, "inbox_request_failed", "The inbox request could not be completed.")
    }

    private fun malformedInbox() = ApiFailure(502, "invalid_inbox_response", "Vault returned an invalid inbox response.")

    companion object { private const val MAX_INBOX_FILE_BYTES = 100_000_000L }

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
        val validUrl = when (draft.contentKind) {
            DraftContentKind.TEXT, DraftContentKind.FILE -> false
            DraftContentKind.URL -> isSafeExternalHttpUrl(candidate).also { valid ->
                if (!valid) throw ApiFailure(400, "invalid_url", "Enter a complete HTTP or HTTPS link without spaces or credentials.")
            }
            DraftContentKind.AUTO -> isSafeExternalHttpUrl(candidate)
        }
        val url = if (validUrl) "/api/v1/items/url" else "/api/v1/items/text"
        val jsonPayload = JSONObject().apply {
            if (validUrl) put("url", candidate) else put("text", text)
            put("displayName", draft.displayName)
            if (draft.retentionHours != null) put("retentionHours", draft.retentionHours)
            else put("retentionDays", draft.retentionDays)
        }
        val body = jsonPayload.toString()
        execute(draft.serverOrigin + url, "POST", body, mapOf("Authorization" to "Bearer $token", "Idempotency-Key" to draft.idempotencyKey))
        return draft
    }

    private fun uploadFile(initial: Draft, token: String, onState: (Draft) -> Unit, onProgress: (Long) -> Unit): Draft {
        var draft = initial
        val common = mapOf("Authorization" to "Bearer $token", "Idempotency-Key" to draft.idempotencyKey)
        if (draft.uploadId == null) {
            val initJson = JSONObject().apply {
                put("displayName", draft.displayName)
                put("contentType", draft.contentType)
                put("sizeBytes", draft.sizeBytes)
                put("sha256", draft.sha256)
                if (draft.retentionHours != null) put("retentionHours", draft.retentionHours)
                else put("retentionDays", draft.retentionDays)
            }
            val init = JSONObject(execute(draft.serverOrigin + "/api/v1/uploads/initiate", "POST", initJson.toString(), common))
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
            "invalid_credentials" -> "Email or username and password did not match."
            "invalid_refresh_token" -> "This phone needs to be paired again."
            else -> when (status) { 404 -> "Upload was not found."; 410 -> "Upload expired. Share the item again."; 413 -> "Item exceeds the 100 MB file limit."; 401, 403 -> "Session expired or revoked. Pair this device again."; else -> "Upload could not finish ($code)." }
        }
        return ApiFailure(status, code, safe)
    }

    private fun String.toRequestBodyJson() = toRequestBody("application/json; charset=utf-8".toMediaType())
}

fun vaultApi(context: Context): VaultApi = VaultApi(EncryptedStore(context))
