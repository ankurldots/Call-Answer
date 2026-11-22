package com.callanswer.app.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.callanswer.core.recording.RecordingCandidate
import com.callanswer.core.recording.RecordingMatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of a folder operation. [AccessLost] maps to RecordingStatus.FOLDER_ACCESS_LOST (12.11). */
sealed interface FolderResult<out T> {
    data class Ok<T>(val value: T) : FolderResult<T>
    data object AccessLost : FolderResult<Nothing>
}

/**
 * Reads the User-selected recordings folder through the Storage Access Framework.
 * Access is read-only for listing and playback; files are never copied. The only write is
 * [delete], which the caller invokes after the User has confirmed (12.9, 12.10).
 */
class RecordingFolderRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    /** Contract for the folder picker; the result is the tree Uri (null when cancelled). */
    fun selectFolderContract(): ActivityResultContracts.OpenDocumentTree = ActivityResultContracts.OpenDocumentTree()

    /** Keeps read access (and delete rights granted by the tree) across restarts (9.11). Returns false on failure. */
    fun persistAccess(treeUri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    } catch (_: SecurityException) {
        try {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** True while the persisted grant for [treeUri] is still held. */
    fun hasAccess(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    /** Lists the files directly inside the folder as matcher candidates. */
    suspend fun listCandidates(treeUri: Uri): FolderResult<List<RecordingCandidate>> = withContext(io) {
        try {
            if (!hasAccess(treeUri)) return@withContext FolderResult.AccessLost
            val dir = DocumentFile.fromTreeUri(appContext, treeUri)
            if (dir == null || !dir.exists() || !dir.isDirectory) return@withContext FolderResult.AccessLost
            val list = dir.listFiles().filter { it.isFile }.map { f ->
                val name = f.name.orEmpty()
                RecordingCandidate(
                    uri = f.uri.toString(),
                    name = name,
                    lastModifiedMs = f.lastModified(),
                    sizeBytes = f.length(),
                    startMsFromName = RecordingMatcher.parseStartMsFromName(name),
                    mimeType = f.type,
                )
            }
            FolderResult.Ok(list)
        } catch (_: SecurityException) {
            FolderResult.AccessLost
        }
    }

    /** True when the file still exists (12.10). Access loss is reported as [FolderResult.AccessLost]. */
    suspend fun exists(fileUri: Uri): FolderResult<Boolean> = withContext(io) {
        try {
            FolderResult.Ok(DocumentFile.fromSingleUri(appContext, fileUri)?.exists() == true)
        } catch (_: SecurityException) {
            FolderResult.AccessLost
        }
    }

    /** Deletes the file. Call only after the User confirmed. Returns Ok(false) if it could not be deleted. */
    suspend fun delete(fileUri: Uri): FolderResult<Boolean> = withContext(io) {
        try {
            FolderResult.Ok(DocumentsContract.deleteDocument(resolver, fileUri))
        } catch (_: SecurityException) {
            FolderResult.AccessLost
        } catch (_: java.io.FileNotFoundException) {
            FolderResult.Ok(false)
        } catch (_: UnsupportedOperationException) {
            FolderResult.Ok(false)
        } catch (_: IllegalArgumentException) {
            FolderResult.Ok(false)
        }
    }
}
