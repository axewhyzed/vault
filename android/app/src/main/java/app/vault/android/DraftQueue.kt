package app.vault.android

class DraftQueue(
    private val store: EncryptedStore,
    private val enqueue: (String) -> Unit,
    private val cancelWork: (String) -> Unit,
) {
    fun prepare(draft: Draft): Draft {
        if (draft.retentionHours != null) {
            require(draft.retentionHours in 1..168) { "Retention must be between 1 and 168 hours." }
        } else {
            require(draft.retentionDays in 1..7) { "Retention must be between 1 and 7 days." }
        }
        val prepared = draft.copy(state = DraftState.PREPARED, safeError = null)
        store.saveDraft(prepared)
        return prepared
    }

    fun send(id: String, retentionDays: Int, retentionHours: Int? = null): Draft {
        if (retentionHours != null) {
            require(retentionHours in 1..168) { "Retention must be between 1 and 168 hours." }
        } else {
            require(retentionDays in 1..7) { "Retention must be between 1 and 7 days." }
        }
        val draft = store.draft(id) ?: throw IllegalArgumentException("Prepared send is unavailable.")
        require(draft.state == DraftState.PREPARED || draft.state == DraftState.FAILED) { "Only a prepared or failed send can be sent." }
        val queued = draft.copy(state = DraftState.QUEUED, retentionDays = retentionDays, retentionHours = retentionHours, safeError = null, bytesSent = 0)
        store.saveDraft(queued)
        enqueue(id)
        return queued
    }

    fun cancel(id: String) {
        cancelWork(id)
        store.deleteDraft(id)
    }
}
