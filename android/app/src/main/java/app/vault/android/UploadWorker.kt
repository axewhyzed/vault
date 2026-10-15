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
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_DRAFT_ID) ?: return Result.failure()
        val store = EncryptedStore(applicationContext)
        val draft = store.draft(id) ?: return Result.failure()
        if (draft.text == null && !store.hasSpool(draft.spoolName)) {
            store.saveDraft(draft.copy(state = DraftState.FAILED, safeError = "The shared file is no longer available. Share it again."))
            return Result.failure()
        }
        return try {
            val uploading = draft.copy(state = if (runAttemptCount == 0) DraftState.UPLOADING else DraftState.RETRYING, safeError = null)
            store.saveDraft(uploading)
            val completed = vaultApi(applicationContext).upload(uploading,
                onState = { changed -> store.saveDraft(changed) },
                onProgress = { sent ->
                    store.saveDraft(uploading.copy(bytesSent = sent))
                    setProgressAsync(Data.Builder().putLong("bytesSent", sent).putLong("totalBytes", uploading.sizeBytes).build())
                })
            store.completeDraft(completed)
            Result.success()
        } catch (e: ApiFailure) {
            val shouldRetry = e.status == 429 || e.status >= 500
            store.saveDraft(draft.copy(state = if (shouldRetry) DraftState.RETRYING else DraftState.FAILED, safeError = e.message ?: "Upload failed."))
            if (shouldRetry) Result.retry() else Result.failure()
        } catch (e: java.io.IOException) {
            store.saveDraft(draft.copy(state = DraftState.RETRYING, safeError = "Waiting for a network connection."))
            Result.retry()
        } catch (e: Exception) {
            store.saveDraft(draft.copy(state = DraftState.FAILED, safeError = "Upload could not start. Share the item again."))
            Result.failure()
        }
    }

    companion object {
        private const val KEY_DRAFT_ID = "draftId"
        fun enqueue(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(Data.Builder().putString(KEY_DRAFT_ID, id).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("vault-upload-$id", ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork("vault-upload-$id")
        }
    }
}
