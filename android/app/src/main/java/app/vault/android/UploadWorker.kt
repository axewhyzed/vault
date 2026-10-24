package app.vault.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit

object UploadProgressTracker {
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    fun update(id: String, percent: Int) {
        _progress.update { it + (id to percent) }
    }

    fun remove(id: String) {
        _progress.update { it - id }
    }
}

internal object UploadWorkerDependencies {
    var storeFactory: (Context) -> EncryptedStore = { EncryptedStore(it) }
    var apiFactory: (Context) -> VaultApi = ::vaultApi
}

internal class UploadDraftProgress(
    private val store: EncryptedStore,
    initial: Draft,
    private val persistProgressOnBytes: Boolean = true,
) {
    private var latest = initial

    @Synchronized fun saveState(changed: Draft) {
        latest = changed
        store.saveDraft(latest)
    }

    @Synchronized fun saveBytesSent(bytesSent: Long) {
        latest = latest.copy(bytesSent = bytesSent)
        if (persistProgressOnBytes) {
            store.saveDraft(latest)
        }
    }

    @Synchronized fun current(): Draft = latest
}

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_DRAFT_ID) ?: return Result.failure()
        val store = UploadWorkerDependencies.storeFactory(applicationContext)
        val draft = store.draft(id) ?: return Result.failure()
        if (draft.text == null && !store.hasSpool(draft.spoolName)) {
            store.saveDraft(draft.copy(state = DraftState.FAILED, safeError = "The shared file is no longer available. Share it again."))
            return Result.failure()
        }
        var progress: UploadDraftProgress? = null
        return try {
            val uploading = draft.copy(state = DraftState.UPLOADING, safeError = null)
            store.saveDraft(uploading)
            UploadProgressTracker.update(uploading.id, 0)
            val p = UploadDraftProgress(store, uploading, persistProgressOnBytes = false)
            progress = p
            val completed = UploadWorkerDependencies.apiFactory(applicationContext).upload(uploading,
                onState = p::saveState,
                onProgress = { sent ->
                    val pct = if (uploading.sizeBytes <= 0) 0 else ((sent.coerceIn(0, uploading.sizeBytes) * 100) / uploading.sizeBytes).toInt()
                    UploadProgressTracker.update(uploading.id, pct)
                    p.saveBytesSent(sent)
                    setProgressAsync(Data.Builder().putLong("bytesSent", sent).putLong("totalBytes", uploading.sizeBytes).build())
                })
            store.completeDraft(completed)
            Result.success()
        } catch (e: CancellationException) {
            if (store.draft(id) != null) {
                val latest = progress?.current() ?: store.draft(id) ?: draft
                store.saveDraft(latest.copy(state = DraftState.RETRYING, safeError = "Waiting for a network connection."))
            }
            throw e
        } catch (e: ApiFailure) {
            if (store.draft(id) == null) return Result.failure()
            val shouldRetry = e.status == 429 || e.status >= 500
            if (e.status == 401 || e.status == 403) {
                store.clearCredentials()
            }
            val latest = progress?.current() ?: store.draft(id) ?: draft
            store.saveDraft(latest.copy(state = if (shouldRetry) DraftState.RETRYING else DraftState.FAILED, safeError = e.message ?: "Upload failed."))
            if (shouldRetry) Result.retry() else Result.failure()
        } catch (e: java.io.IOException) {
            if (store.draft(id) == null) return Result.failure()
            val latest = progress?.current() ?: store.draft(id) ?: draft
            store.saveDraft(latest.copy(state = DraftState.RETRYING, safeError = "Waiting for a network connection."))
            Result.retry()
        } catch (e: Exception) {
            if (store.draft(id) == null) return Result.failure()
            val latest = progress?.current() ?: store.draft(id) ?: draft
            store.saveDraft(latest.copy(state = DraftState.FAILED, safeError = "Upload could not start. Share the item again."))
            Result.failure()
        } finally {
            UploadProgressTracker.remove(id)
        }
    }

    companion object {
        internal const val KEY_DRAFT_ID = "draftId"
        const val TAG_UPLOAD = "vault-upload"

        fun enqueue(context: Context, id: String, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .addTag(TAG_UPLOAD)
                .setInputData(Data.Builder().putString(KEY_DRAFT_ID, id).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork("vault-upload-$id", policy, request)
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("vault-upload-$id")
        }
    }
}
