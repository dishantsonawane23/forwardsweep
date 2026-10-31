package com.forwardsweep.data.delete

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.forwardsweep.data.ImageRepository
import com.forwardsweep.data.media.MediaStoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the UI has to do to complete a deletion. */
sealed interface DeleteRequest {
    /** Android 11+: launch once; the system shows one dialog for the whole batch. */
    data class SystemConsent(val intentSender: IntentSender, val uris: List<Uri>, val trash: Boolean) : DeleteRequest

    /** Android 10: call [DeletionManager.deleteLegacy] per item, launching consent when asked. */
    data class PerItem(val uris: List<Uri>) : DeleteRequest

    /** Android 8–9: already deleted (the app holds WRITE_EXTERNAL_STORAGE). */
    data class Done(val result: DeleteResult) : DeleteRequest
}

sealed interface LegacyStep {
    data object Deleted : LegacyStep
    data class NeedsConsent(val intentSender: IntentSender) : LegacyStep
    data class Failed(val reason: String) : LegacyStep
}

data class DeleteResult(
    val removed: Int,
    val requested: Int,
    val bytes: Long,
    /** Items went to the system trash: space is reclaimed after 30 days or when trash is emptied. */
    val trashed: Boolean,
)

/**
 * Batch deletion through MediaStore. Never deletes without a system confirmation on Android 10+,
 * and prefers the system trash so mistakes can be undone from the Gallery / Files app.
 */
class DeletionManager(
    private val resolver: ContentResolver,
    private val media: MediaStoreSource,
    private val images: ImageRepository,
) {
    suspend fun request(uris: List<Uri>, preferTrash: Boolean): DeleteRequest {
        require(uris.size <= MAX_BATCH) { "split deletions into batches of $MAX_BATCH" }
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val sender = if (preferTrash) trashRequest(uris) else deleteRequest(uris)
                DeleteRequest.SystemConsent(sender, uris, trash = preferTrash)
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> DeleteRequest.PerItem(uris)
            else -> withContext(Dispatchers.IO) {
                uris.forEach { runCatching { resolver.delete(it, null, null) } }
                DeleteRequest.Done(finish(uris, trashed = false))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun trashRequest(uris: List<Uri>): IntentSender =
        MediaStore.createTrashRequest(resolver, uris, true).intentSender

    @RequiresApi(Build.VERSION_CODES.R)
    private fun deleteRequest(uris: List<Uri>): IntentSender =
        MediaStore.createDeleteRequest(resolver, uris).intentSender

    /** Android 10: try to delete one item; files owned by other apps need per-item consent. */
    suspend fun deleteLegacy(uri: Uri): LegacyStep = withContext(Dispatchers.IO) {
        try {
            if (resolver.delete(uri, null, null) > 0) LegacyStep.Deleted else LegacyStep.Failed("not found")
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                LegacyStep.NeedsConsent(e.userAction.actionIntent.intentSender)
            } else {
                LegacyStep.Failed(e.message ?: "permission denied")
            }
        }
    }

    /**
     * Call after the system dialog returns (any result) or the per-item loop finishes. Checks which
     * items are really gone, drops them from the cache, and reports the space involved.
     */
    suspend fun finish(uris: List<Uri>, trashed: Boolean): DeleteResult = withContext(Dispatchers.IO) {
        val remaining = media.stillPresent(uris)
        val gone = uris.filter { it !in remaining }.map { it.toString() }
        val bytes = images.rows(gone).sumOf { it.sizeBytes }
        images.recordDeleted(gone)
        images.forget(gone)
        DeleteResult(removed = gone.size, requested = uris.size, bytes = bytes, trashed = trashed)
    }

    companion object {
        /** Keeps the consent request well below the Binder transaction limit. */
        const val MAX_BATCH = 1000
    }
}
