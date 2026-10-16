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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
@Config(sdk = [35])
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
            compose.onNodeWithText("Refresh").assertExists()
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
            compose.onNodeWithText("Copy").performClick()

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
        compose.onNodeWithText("Keep for 7 days · then delete").performClick()
        compose.onNodeWithText("3 days").performClick()
        compose.onNodeWithText("Send to Vault").performClick()
        assertEquals(3, sentRetention)
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

    @Test fun pairingEntryRequiresInputAndExposesContinueAndScanActions() {
        var continued = 0
        var scanned = 0
        compose.setContent {
            MaterialTheme {
                var value by remember { mutableStateOf("") }
                PairingCodeEntry(value, { value = it }, { continued++ }, { scanned++ })
            }
        }

        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Pairing QR data or vault://pair link").performTextInput("vault://pair?data")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Scan QR").performClick()

        assertEquals(1, continued)
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
}
