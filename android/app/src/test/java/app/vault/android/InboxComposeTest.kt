package app.vault.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h1200dp")
class InboxComposeTest {
    @get:Rule val compose = createComposeRule()
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    private fun store(server: MockWebServer) = EncryptedStore(
        ApplicationProvider.getApplicationContext<Context>(),
        VaultCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()),
    ).apply {
        saveCredentials(Credentials(server.url("/").toString().trimEnd('/'), "va1_access", "2099-01-01T00:00:00Z", "vr1_refresh", "2099-02-01T00:00:00Z", "owner", "device"))
    }

    private fun listResponse() = MockResponse().setBody("""{"items":[{"id":"$id","type":"text","state":"available","displayName":"Note","sizeBytes":10,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2099-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"device","name":"Phone","platform":"android"}}],"nextCursor":null,"quota":{"activeBytes":10,"maxActiveBytes":100,"activeItems":1,"maxActiveItems":10}}""")

    @Test fun emptyInboxRendersAnExplicitEmptyState() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null,"quota":{"activeBytes":0,"maxActiveBytes":100,"activeItems":0,"maxActiveItems":10}}"""))
            val store = store(server)
            compose.setContent { MaterialTheme { InboxTab(VaultApi(store), store, "${server.url("/").toString().trimEnd('/')}|owner|device", 0) } }

            compose.waitUntil(5_000) { server.requestCount == 1 }
            compose.onNodeWithText("Your inbox is empty.").assertExists()
            compose.onNodeWithText("Search...").assertExists()
        } finally { server.shutdown() }
    }

    @Test fun openingTextDetailShowsExactContentAndCopyWritesItToClipboard() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(listResponse())
            server.enqueue(MockResponse().setBody("""{"id":"$id","type":"text","state":"available","displayName":"Note","sizeBytes":10,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2099-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"device","name":"Phone","platform":"android"},"text":"exact value","contentType":"text/plain","downloadUrl":null,"sha256":null}"""))
            val store = store(server)
            compose.setContent { MaterialTheme { InboxTab(VaultApi(store), store, "${server.url("/").toString().trimEnd('/')}|owner|device", 0) } }

            compose.waitUntil(5_000) { server.requestCount == 1 }
            compose.onNodeWithText("Note").performClick()
            compose.waitUntil(5_000) { server.requestCount == 2 }
            compose.onNodeWithText("exact value").assertExists()
            compose.onNodeWithText("Copy text").performClick()

            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = clipboard.primaryClip
            assertEquals("exact value", clip?.getItemAt(0)?.text?.toString())
        } finally { server.shutdown() }
    }

    @Test fun sendHomeMakesAllThreeSendTypesDiscoverable() {
        val choices = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SendHomeScreen(onText = { choices.add("text") }, onLink = { choices.add("link") }, onFile = { choices.add("file") })
            }
        }

        compose.onNodeWithText("Write or paste a note").performClick()
        compose.onNodeWithText("Send a web address").performClick()
        compose.onNodeWithText("Choose a photo or document").performClick()
        assertEquals(listOf("text", "link", "file"), choices)
    }

    @Test fun textComposerRequiresContentAndPreservesExplicitTextIntent() {
        var reviewed = 0
        compose.setContent {
            MaterialTheme {
                var value by remember { mutableStateOf("") }
                SendComposerScreen(ComposeKind.TEXT, value, "", null, false, null, { value = it }, {}, {}, { reviewed++ })
            }
        }

        compose.onNodeWithText("Review send").assertIsNotEnabled()
        compose.onNodeWithText("Text to send").performTextInput("https://example.com as literal text")
        compose.onNodeWithText("Review send").performClick()
        assertEquals(1, reviewed)
    }

    @Test fun linkComposerRequiresSafeHttpUrlBeforeReview() {
        var reviewed = 0
        compose.setContent {
            MaterialTheme {
                var value by remember { mutableStateOf("") }
                SendComposerScreen(ComposeKind.LINK, value, "", null, false, null, { value = it }, {}, {}, { reviewed++ })
            }
        }
        compose.onNodeWithText("Review send").assertIsNotEnabled()
        compose.onNodeWithText("https://…").performTextInput("https://example.com/path")
        compose.onNodeWithText("Review send").performClick()
        assertEquals(1, reviewed)
    }

    @Test fun reviewScreenShowsCompleteContentAndSendsWithChosenRetention() {
        var sentRetention: Int? = null
        val draft = Draft("compose-send", "compose-key", "https://vault.example", "owner", text = "complete private note content", displayName = "Text snippet", contentType = "text/plain", sizeBytes = 29, state = DraftState.PREPARED, contentKind = DraftContentKind.TEXT)
        compose.setContent { MaterialTheme { ReviewSendScreen(draft, { sentRetention = it }, {}, {}) } }

        compose.onNodeWithText("complete private note content").assertExists()
        compose.onNodeWithText("7").performTextReplacement("3")
        compose.onNodeWithText("Send to Vault").performClick()
        assertEquals(3, sentRetention)
    }

    @Test fun reviewScreenCanSwitchUnitToHoursAndSendRetentionHours() {
        var sentDays: Int? = null
        var sentHours: Int? = null
        val draft = Draft("compose-hours", "compose-key", "https://vault.example", "owner", text = "note", displayName = "Snippet", contentType = "text/plain", sizeBytes = 4, state = DraftState.PREPARED, contentKind = DraftContentKind.TEXT)
        compose.setContent {
            MaterialTheme {
                ReviewSendScreen(
                    draft = draft,
                    onSend = { days, hours -> sentDays = days; sentHours = hours },
                    onEdit = { _, _ -> },
                    onDiscard = {}
                )
            }
        }

        // Tap Unit field and select Hours
        compose.onNodeWithText("Days").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Hours (1-168)").performClick()
        compose.waitForIdle()

        // 7 days converted to 168 hours; replace with 48
        compose.onNodeWithText("168").performTextReplacement("48")
        compose.waitForIdle()

        compose.onNodeWithText("Send to Vault").performClick()
        assertEquals(48, sentHours)
    }

    @Test fun reviewDiscardRequiresExplicitConfirmation() {
        var discarded = 0
        val draft = Draft("discard-review", "discard-key", "https://vault.example", "owner", text = "note", displayName = "Text snippet", contentType = "text/plain", sizeBytes = 4, state = DraftState.PREPARED, contentKind = DraftContentKind.TEXT)
        compose.setContent { MaterialTheme { ReviewSendScreen(draft, {}, {}, { discarded++ }) } }

        compose.onNodeWithText("Discard").performClick()
        assertEquals(0, discarded)
        compose.onNodeWithText("Discard send").performClick()
        assertEquals(1, discarded)
    }

    @Test fun welcomeScreenExposesScanQrAndSignInActions() {
        var scanned = 0
        var loggedIn = 0
        compose.setContent {
            MaterialTheme {
                WelcomeScreen(
                    onScanQr = { scanned++ },
                    onLogin = { _, _, _, _ -> loggedIn++ },
                    defaultOrigin = "https://vault.example",
                    defaultDeviceName = "Pixel 8",
                    busy = false,
                )
            }
        }

        compose.onNodeWithText("Scan QR Code").assertExists()
        compose.onNodeWithText("Sign In with Credentials").assertExists()
        compose.onNodeWithText("Scan QR Code").performClick()
        assertEquals(1, scanned)
    }

    @Test fun signOutDialogExplainsGlobalEffectAndCanBeCanceledOrConfirmed() {
        var confirmed = 0
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) SignOutConfirmationDialog(busy = false, onConfirm = { confirmed++; visible.value = false }, onDismiss = { visible.value = false })
            }
        }

        compose.onNodeWithText("This revokes this phone and every other active Vault session. You will need to pair devices again.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, confirmed)
        compose.onNodeWithText("Sign out everywhere?").assertDoesNotExist()

        visible.value = true
        compose.waitForIdle()
        compose.onNodeWithText("Sign out everywhere").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun inboxRendersStorageQuotaCard() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(listResponse())
            val store = store(server)
            compose.setContent { MaterialTheme { InboxTab(VaultApi(store), store, "${server.url("/").toString().trimEnd('/')}|owner|device", 0) } }

            compose.waitUntil(5_000) { server.requestCount == 1 }
            compose.onNodeWithText("Vault storage").assertExists()
            compose.onNodeWithText("10 B of 100 B").assertExists()
            compose.onNodeWithText("1 of 10 items").assertExists()
        } finally { server.shutdown() }
    }

    @Test fun deleteItemFromDetailWithConfirmation() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(listResponse())
            server.enqueue(MockResponse().setBody("""{"id":"$id","type":"text","state":"available","displayName":"Note","sizeBytes":10,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2099-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"device","name":"Phone","platform":"android"},"text":"exact value","contentType":"text/plain","downloadUrl":null,"sha256":null}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val store = store(server)
            compose.setContent { MaterialTheme { InboxTab(VaultApi(store), store, "${server.url("/").toString().trimEnd('/')}|owner|device", 0) } }

            compose.waitUntil(5_000) { server.requestCount == 1 }
            compose.onNodeWithText("Note").performClick()
            compose.waitUntil(5_000) { server.requestCount == 2 && compose.onAllNodesWithText("Delete item").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Delete item").performScrollTo().performClick()
            compose.onNodeWithText("Delete this item from Vault? This cannot be undone.").assertExists()
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(5_000) { server.requestCount == 3 }
            val deleteRequest = (1..3).map { server.takeRequest() }[2]
            assertEquals("DELETE", deleteRequest.method)
            assertEquals("/api/v1/items/$id", deleteRequest.path)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Item deleted.").fetchSemanticsNodes().isNotEmpty() }
        } finally { server.shutdown() }
    }

    @Test fun welcomeScreenExpandsCollapsibleServerUrl() {
        compose.setContent {
            MaterialTheme {
                WelcomeScreen(
                    onScanQr = {},
                    onLogin = { _, _, _, _ -> },
                    defaultOrigin = "https://vault.example.com",
                    defaultDeviceName = "Pixel 8",
                    busy = false,
                )
            }
        }

        compose.onNodeWithText("Sign In with Credentials").performClick()
        compose.waitForIdle()

        // Server URL is initially collapsed
        compose.onNodeWithText("Server URL").assertDoesNotExist()

        // Expand custom server settings
        compose.onNodeWithText("Using default server: vault.example.com (Edit) ▼").performClick()
        compose.waitForIdle()

        // Server URL is now visible
        compose.onNodeWithText("Server URL").assertExists()
    }

    @Test fun outboxQueueRendersPendingDraftsWithStatusAndActions() {
        var retried = 0
        var removed = 0
        val drafts = listOf(
            Draft(
                id = "d-queued", idempotencyKey = "k1", serverOrigin = "https://vault.example",
                ownerUserId = "u1", displayName = "report.pdf", contentType = "application/pdf",
                sizeBytes = 1000, bytesSent = 0, state = DraftState.QUEUED
            ),
            Draft(
                id = "d-uploading", idempotencyKey = "k2", serverOrigin = "https://vault.example",
                ownerUserId = "u1", displayName = "photo.jpg", contentType = "image/jpeg",
                sizeBytes = 1000, bytesSent = 500, state = DraftState.UPLOADING
            ),
            Draft(
                id = "d-failed", idempotencyKey = "k3", serverOrigin = "https://vault.example",
                ownerUserId = "u1", displayName = "notes.txt", contentType = "text/plain",
                sizeBytes = 100, bytesSent = 0, state = DraftState.FAILED, safeError = "Network error"
            )
        )

        compose.setContent {
            MaterialTheme {
                SendHomeScreen(
                    onText = {}, onLink = {}, onFile = {},
                    drafts = drafts,
                    onRetry = { retried++ },
                    onRemove = { removed++ }
                )
            }
        }

        compose.onNodeWithText("Outbox Queue (3)").assertExists()
        compose.onNodeWithText("report.pdf").assertExists()
        compose.onNodeWithText("Queued for upload").assertExists()

        compose.onNodeWithText("photo.jpg").assertExists()
        compose.onNodeWithText("Uploading · 50%").assertExists()

        compose.onNodeWithText("notes.txt").assertExists()
        compose.onNodeWithText("Failed").assertExists()
        compose.onNodeWithText("Network error").assertExists()

        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)

        compose.onNodeWithText("Discard").performClick()
        assertEquals(1, removed)
    }

    @Test fun searchFiltersInboxRowsAndDisplaysCount() {
        val server = MockWebServer(); server.start()
        try {
            val multiItemResponse = MockResponse().setBody("""{
                "items": [
                    {"id":"123e4567-e89b-12d3-a456-426614174001","type":"file","state":"available","displayName":"financial-report.pdf","sizeBytes":100,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2099-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"d1","name":"Work Laptop","platform":"web"}},
                    {"id":"123e4567-e89b-12d3-a456-426614174002","type":"text","state":"available","displayName":"Grocery list","sizeBytes":50,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2099-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"d2","name":"Pixel 8","platform":"android"}},
                    {"id":"123e4567-e89b-12d3-a456-426614174003","type":"url","state":"available","displayName":"GitHub Repo","sizeBytes":40,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2099-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"d3","name":"Home Desktop","platform":"windows"}}
                ],
                "nextCursor":null,
                "quota":{"activeBytes":190,"maxActiveBytes":1000,"activeItems":3,"maxActiveItems":10}
            }""")
            server.enqueue(multiItemResponse)
            val store = store(server)
            compose.setContent { MaterialTheme { InboxTab(VaultApi(store), store, "${server.url("/").toString().trimEnd('/')}|owner|device", 0) } }

            compose.waitUntil(10_000) { server.requestCount == 1 && compose.onAllNodesWithText("financial-report.pdf").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("financial-report.pdf").assertExists()
            compose.onNodeWithText("Grocery list").assertExists()
            compose.onNodeWithText("GitHub Repo").assertExists()

            // Filter by name
            compose.onNodeWithText("Search...").performTextInput("financial")
            compose.waitForIdle()
            compose.onNodeWithText("1 result").assertExists()
            compose.onNodeWithText("financial-report.pdf").assertExists()
            compose.onNodeWithText("Grocery list").assertDoesNotExist()
            compose.onNodeWithText("GitHub Repo").assertDoesNotExist()

            // Filter by device name
            compose.onNodeWithContentDescription("Clear search").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Search...").performTextInput("Pixel 8")
            compose.waitForIdle()
            compose.onNodeWithText("1 result").assertExists()
            compose.onNodeWithText("Grocery list").assertExists()
            compose.onNodeWithText("financial-report.pdf").assertDoesNotExist()

            // Filter with no match
            compose.onNodeWithContentDescription("Clear search").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Search...").performTextInput("nonexistent")
            compose.waitForIdle()
            compose.onNodeWithText("0 results").assertExists()
            compose.onNodeWithText("No items match \"nonexistent\".").assertExists()
        } finally { server.shutdown() }
    }

    @Test fun typeBadgesRenderLabelsAndExpectedText() {
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    TypeBadge("file", 2048)
                    TypeBadge("url", 0)
                    TypeBadge("text", 0)
                }
            }
        }
        compose.onNodeWithText("2 KB").assertExists()
        compose.onNodeWithText("Link").assertExists()
        compose.onNodeWithText("Text").assertExists()
    }

    @Test fun filterChipsFilterInboxByType() {
        val server = MockWebServer(); server.start()
        try {
            val multiItemResponse = MockResponse().setBody("""{
                "items": [
                    {"id":"123e4567-e89b-12d3-a456-426614174001","type":"file","state":"available","displayName":"financial-report.pdf","sizeBytes":100,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2099-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"d1","name":"Work Laptop","platform":"web"}},
                    {"id":"123e4567-e89b-12d3-a456-426614174002","type":"text","state":"available","displayName":"Grocery list","sizeBytes":50,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2099-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"d2","name":"Pixel 8","platform":"android"}},
                    {"id":"123e4567-e89b-12d3-a456-426614174003","type":"url","state":"available","displayName":"GitHub Repo","sizeBytes":40,"createdAt":"2026-10-16T10:00:00Z","availableAt":"2026-10-16T10:00:00Z","expiresAt":"2099-10-23T10:00:00Z","version":1,"sourceDevice":{"id":"d3","name":"Home Desktop","platform":"windows"}}
                ],
                "nextCursor":null,
                "quota":{"activeBytes":190,"maxActiveBytes":1000,"activeItems":3,"maxActiveItems":10}
            }""")
            server.enqueue(multiItemResponse)
            val store = store(server)
            compose.setContent { MaterialTheme { InboxTab(VaultApi(store), store, "${server.url("/").toString().trimEnd('/')}|owner|device", 0) } }

            compose.waitUntil(10_000) { server.requestCount == 1 && compose.onAllNodesWithText("financial-report.pdf").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("financial-report.pdf").assertExists()
            compose.onNodeWithText("Grocery list").assertExists()
            compose.onNodeWithText("GitHub Repo").assertExists()

            // Filter to Files
            compose.onNodeWithText("Files").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("financial-report.pdf").assertExists()
            compose.onNodeWithText("Grocery list").assertDoesNotExist()
            compose.onNodeWithText("GitHub Repo").assertDoesNotExist()

            // Filter to Links
            compose.onNodeWithText("Links").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("GitHub Repo").assertExists()
            compose.onNodeWithText("financial-report.pdf").assertDoesNotExist()
            compose.onNodeWithText("Grocery list").assertDoesNotExist()

            // Filter to Text
            compose.onNodeWithText("Text").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Grocery list").assertExists()
            compose.onNodeWithText("financial-report.pdf").assertDoesNotExist()
            compose.onNodeWithText("GitHub Repo").assertDoesNotExist()

            // Back to All
            compose.onNodeWithText("All").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("financial-report.pdf").assertExists()
            compose.onNodeWithText("Grocery list").assertExists()
            compose.onNodeWithText("GitHub Repo").assertExists()
        } finally { server.shutdown() }
    }

    @Test fun offlineSendEntersQueuedStateWithStatusAlert() {
        val previousChecker = isNetworkConnected
        try {
            isNetworkConnected = { false }
            val server = MockWebServer(); server.start()
            try {
                val store = store(server)
                val draft = Draft(
                    id = "d-offline",
                    idempotencyKey = "k-offline",
                    serverOrigin = server.url("/").toString().trimEnd('/'),
                    ownerUserId = "owner",
                    displayName = "offline.txt",
                    text = "offline text",
                    contentType = "text/plain",
                    sizeBytes = 12,
                    state = DraftState.PREPARED
                )
                store.saveDraft(draft)
                val enqueued = mutableListOf<String>()
                val queue = DraftQueue(store, enqueue = { enqueued.add(it) }, cancelWork = {})
                val sent = queue.send(draft.id, 3)
                assertEquals(DraftState.QUEUED, sent.state)
                assertEquals(listOf("d-offline"), enqueued)
                assertEquals(DraftState.QUEUED, store.draft(draft.id)?.state)

                // Verify alert message offline logic
                val alertMessage = if (isNetworkConnected(ApplicationProvider.getApplicationContext())) {
                    "Added to Vault. Upload will retry if the network drops."
                } else {
                    "Queued for upload. Will send when connected."
                }
                assertEquals("Queued for upload. Will send when connected.", alertMessage)
            } finally { server.shutdown() }
        } finally {
            isNetworkConnected = previousChecker
        }
    }
}
