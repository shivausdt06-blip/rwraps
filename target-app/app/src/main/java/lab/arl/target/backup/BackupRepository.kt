package lab.arl.target.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lab.arl.target.domain.BackupFileRecord
import lab.arl.target.domain.BackupInitResult
import lab.arl.target.domain.BackupRecord
import lab.arl.target.network.CreateBackupFileRequest
import lab.arl.target.network.TargetApi
import lab.arl.target.network.toDomain
import lab.arl.target.network.toInit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class BackupRepository(
    private val context: Context,
    private val api: TargetApi
) {
    data class SelectedFile(
        val uri: Uri,
        val filename: String,
        val mimeType: String,
        val sizeBytes: Long
    )

    data class UploadProgress(
        val bytesUploaded: Long,
        val sizeBytes: Long
    )

    fun inspect(uri: Uri): SelectedFile {
        val resolver = context.contentResolver
        var name = "file"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return SelectedFile(uri, name.take(255), mime.take(127), size)
    }

    suspend fun createBackup(): BackupRecord = withContext(Dispatchers.IO) {
        api.createBackup().backup.toDomain()
    }

    suspend fun checksum(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { Sha256.hex(it) }
            ?: error("Unable to read the selected file.")
    }

    suspend fun upload(
        backupId: String,
        file: SelectedFile,
        checksum: String,
        cancel: AtomicBoolean,
        onProgress: (UploadProgress) -> Unit
    ): BackupFileRecord = withContext(Dispatchers.IO) {
        val created: BackupInitResult = api.createBackupFile(
            backupId,
            CreateBackupFileRequest(
                filename = file.filename,
                sizeBytes = file.sizeBytes,
                mimeType = file.mimeType,
                checksumSha256 = checksum
            )
        ).toInit()
        val chunkSize = created.chunkSize.coerceAtLeast(16 * 1024)
        val plans = ChunkPlanner.plan(file.sizeBytes, chunkSize, created.file.bytesUploaded)
        val stream = context.contentResolver.openInputStream(file.uri)
            ?: error("Unable to open the selected file.")
        stream.use { input ->
            if (created.file.bytesUploaded > 0) {
                var skipped = 0L
                while (skipped < created.file.bytesUploaded) {
                    val ignored = input.skip(created.file.bytesUploaded - skipped)
                    if (ignored <= 0) break
                    skipped += ignored
                }
            }
            for (plan in plans) {
                if (cancel.get()) {
                    api.cancelBackup(backupId)
                    error("Upload cancelled")
                }
                val buffer = ByteArray(plan.size)
                var filled = 0
                while (filled < plan.size) {
                    val read = input.read(buffer, filled, plan.size - filled)
                    if (read < 0) break
                    filled += read
                }
                val body = buffer.copyOf(filled).toRequestBody("application/octet-stream".toMediaType())
                val updated = api.uploadChunk(backupId, created.file.id, plan.offset, body = body)
                onProgress(UploadProgress(updated.file.bytesUploaded.toLongOrNull() ?: (plan.offset + filled), file.sizeBytes))
            }
        }
        api.completeBackupFile(backupId, created.file.id).file.toDomain()
    }

    suspend fun cancel(backupId: String) {
        api.cancelBackup(backupId)
    }
}
