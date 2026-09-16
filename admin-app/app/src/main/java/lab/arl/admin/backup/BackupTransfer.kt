package lab.arl.admin.backup

import java.util.concurrent.atomic.AtomicBoolean
import lab.arl.admin.domain.BackupFileView
import lab.arl.admin.network.AdminApi
import lab.arl.admin.network.IngestFileRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupTransfer(
    private val api: AdminApi,
    private val store: LocalBackupStore,
    private val chunkSize: Int = 1_048_576
) {
    suspend fun pullReadyFiles(
        deviceId: String,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (BackupFileView) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val listed = api.backups(deviceId).backups
        for (header in listed) {
            val detail = if (header.files.isEmpty()) {
                runCatching { api.backup(header.id) }.getOrNull()
            } else {
                null
            }
            val files = header.files.ifEmpty { detail?.files.orEmpty() }
            val backupId = header.id
            val targetId = header.deviceId
            if (targetId != deviceId) continue
            for (file in files) {
                if (cancel.get()) {
                    store.cancel(targetId, backupId)
                    return@withContext
                }
                if (file.uploadState != "COMPLETE") continue
                if (file.payloadState == "LOCAL_ADMIN") continue
                val size = file.sizeBytes.toLongOrNull() ?: continue
                var offset = store.resumeOffset(targetId, backupId, file.id)
                val started = System.nanoTime()
                var lastBytes = offset
                try {
                    while (offset < size) {
                        if (cancel.get()) {
                            store.cancel(targetId, backupId)
                            return@withContext
                        }
                        val take = minOf(chunkSize.toLong(), size - offset).toInt()
                        val body = api.downloadChunk(backupId, file.id, offset, take)
                        val bytes = body.bytes()
                        offset = store.writeChunk(
                            deviceId = targetId,
                            backupId = backupId,
                            fileId = file.id,
                            filename = file.filename,
                            sizeBytes = size,
                            checksum = file.checksumSha256,
                            offset = offset,
                            data = bytes
                        )
                        val elapsed = (System.nanoTime() - started).coerceAtLeast(1) / 1_000_000.0
                        val speed = ((offset - lastBytes).coerceAtLeast(0) / (elapsed / 1000.0)).toLong()
                        onProgress(
                            BackupFileView(
                                backupId = backupId,
                                fileId = file.id,
                                deviceId = targetId,
                                filename = file.filename,
                                sizeBytes = size,
                                bytesStored = offset,
                                status = "DOWNLOADING",
                                checksumState = "PENDING",
                                createdAt = header.createdAt,
                                speedBps = speed,
                                error = null
                            )
                        )
                    }
                    store.finalizeFile(targetId, backupId, file.id, file.filename, file.checksumSha256, size)
                    api.ingestFile(backupId, file.id, IngestFileRequest(file.checksumSha256, size))
                    onProgress(
                        BackupFileView(
                            backupId = backupId,
                            fileId = file.id,
                            deviceId = targetId,
                            filename = file.filename,
                            sizeBytes = size,
                            bytesStored = size,
                            status = "COMPLETE",
                            checksumState = "VERIFIED",
                            createdAt = header.createdAt,
                            speedBps = null,
                            error = null
                        )
                    )
                } catch (err: Exception) {
                    onProgress(
                        BackupFileView(
                            backupId = backupId,
                            fileId = file.id,
                            deviceId = targetId,
                            filename = file.filename,
                            sizeBytes = size,
                            bytesStored = store.resumeOffset(targetId, backupId, file.id),
                            status = "FAILED",
                            checksumState = "FAILED",
                            createdAt = header.createdAt,
                            speedBps = null,
                            error = err.message
                        )
                    )
                }
            }
        }
    }
}
