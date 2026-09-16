package lab.arl.admin.backup

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocalFileMeta(
    val fileId: String,
    val filename: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val localName: String? = null,
    val bytesStored: Long = 0,
    val checksumState: String = "PENDING",
    val status: String = "DOWNLOADING"
)

@Serializable
data class LocalBackupMeta(
    val backupId: String,
    val deviceId: String,
    val files: List<LocalFileMeta> = emptyList()
)

class InsufficientStorageException(message: String) : java.io.IOException(message)
class ChecksumMismatchException(message: String) : java.io.IOException(message)
class ResumeConflictException(message: String) : java.io.IOException(message)

class LocalBackupStore(
    val root: File,
    private val quotaBytes: Long = DEFAULT_QUOTA,
    private val availableBytes: () -> Long = { root.usableSpace }
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        root.mkdirs()
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun availableBytes(): Long = availableBytes.invoke()

    fun resumeOffset(deviceId: String, backupId: String, fileId: String): Long {
        val part = BackupPaths.partFile(root, deviceId, backupId, fileId)
        return if (part.exists()) part.length() else 0L
    }

    fun writeChunk(
        deviceId: String,
        backupId: String,
        fileId: String,
        filename: String,
        sizeBytes: Long,
        checksum: String,
        offset: Long,
        data: ByteArray
    ): Long {
        BackupPaths.tmpDir(root, deviceId, backupId).mkdirs()
        val part = BackupPaths.partFile(root, deviceId, backupId, fileId)
        if (part.exists() && part.length() != offset) {
            throw ResumeConflictException("Expected offset ${part.length()} but received $offset")
        }
        if (!part.exists() && offset != 0L) {
            throw ResumeConflictException("Missing partial file for offset $offset")
        }
        val needed = data.size.toLong()
        if (usedBytes() + needed > quotaBytes) {
            throw InsufficientStorageException("Backup quota exceeded.")
        }
        if (availableBytes() < needed + 65_536) {
            throw InsufficientStorageException("Not enough free space on the admin device.")
        }
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(offset)
            raf.write(data)
        }
        upsertMeta(
            deviceId,
            backupId,
            LocalFileMeta(
                fileId = fileId,
                filename = FilenameSanitizer.sanitize(filename),
                sizeBytes = sizeBytes,
                checksumSha256 = checksum.lowercase(),
                bytesStored = part.length(),
                checksumState = "PENDING",
                status = "DOWNLOADING"
            )
        )
        return part.length()
    }

    fun finalizeFile(
        deviceId: String,
        backupId: String,
        fileId: String,
        filename: String,
        expectedChecksum: String,
        sizeBytes: Long
    ): File {
        val part = BackupPaths.partFile(root, deviceId, backupId, fileId)
        if (!part.exists()) error("Incomplete transfer")
        if (part.length() != sizeBytes) error("Incomplete transfer")
        val digest = sha256(part)
        if (digest != expectedChecksum.lowercase()) {
            part.delete()
            upsertMeta(
                deviceId,
                backupId,
                LocalFileMeta(
                    fileId = fileId,
                    filename = FilenameSanitizer.sanitize(filename),
                    sizeBytes = sizeBytes,
                    checksumSha256 = expectedChecksum.lowercase(),
                    bytesStored = 0,
                    checksumState = "FAILED",
                    status = "FAILED"
                )
            )
            throw ChecksumMismatchException("Local SHA-256 does not match the staged checksum.")
        }
        val dir = BackupPaths.backupDir(root, deviceId, backupId)
        dir.mkdirs()
        val dest = BackupPaths.uniqueDestination(dir, FilenameSanitizer.sanitize(filename), fileId)
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = false)
            part.delete()
        }
        upsertMeta(
            deviceId,
            backupId,
            LocalFileMeta(
                fileId = fileId,
                filename = FilenameSanitizer.sanitize(filename),
                sizeBytes = sizeBytes,
                checksumSha256 = digest,
                localName = dest.name,
                bytesStored = dest.length(),
                checksumState = "VERIFIED",
                status = "COMPLETE"
            )
        )
        return dest
    }

    fun cancel(deviceId: String, backupId: String) {
        val tmp = BackupPaths.tmpDir(root, deviceId, backupId)
        tmp.deleteRecursively()
    }

    fun deleteBackup(deviceId: String, backupId: String) {
        BackupPaths.backupDir(root, deviceId, backupId).deleteRecursively()
    }

    fun readMeta(deviceId: String, backupId: String): LocalBackupMeta? {
        val file = BackupPaths.metaFile(root, deviceId, backupId)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(LocalBackupMeta.serializer(), file.readText()) }.getOrNull()
    }

    fun fileMeta(deviceId: String, backupId: String, fileId: String): LocalFileMeta? =
        readMeta(deviceId, backupId)?.files?.firstOrNull { it.fileId == fileId }

    private fun upsertMeta(deviceId: String, backupId: String, file: LocalFileMeta) {
        val current = readMeta(deviceId, backupId) ?: LocalBackupMeta(backupId, deviceId)
        val files = current.files.filterNot { it.fileId == file.fileId } + file
        val next = current.copy(files = files)
        val meta = BackupPaths.metaFile(root, deviceId, backupId)
        meta.parentFile?.mkdirs()
        val tmp = File(meta.parentFile, "meta.json.tmp")
        tmp.writeText(json.encodeToString(LocalBackupMeta.serializer(), next))
        if (!tmp.renameTo(meta)) {
            tmp.copyTo(meta, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val DEFAULT_QUOTA = 2L * 1024L * 1024L * 1024L

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
