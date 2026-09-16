package lab.arl.admin.backup

import java.io.File

object BackupPaths {
    fun root(filesDir: File): File = File(filesDir, "backups")

    fun targetDir(root: File, deviceId: String): File =
        File(root, "target-${BackupIds.requireId(deviceId, "deviceId")}")

    fun backupDir(root: File, deviceId: String, backupId: String): File =
        File(targetDir(root, deviceId), "backup-${BackupIds.requireId(backupId, "backupId")}")

    fun tmpDir(root: File, deviceId: String, backupId: String): File =
        File(backupDir(root, deviceId, backupId), ".tmp")

    fun partFile(root: File, deviceId: String, backupId: String, fileId: String): File =
        File(tmpDir(root, deviceId, backupId), "${BackupIds.requireId(fileId, "fileId")}.part")

    fun metaFile(root: File, deviceId: String, backupId: String): File =
        File(backupDir(root, deviceId, backupId), "meta.json")

    fun uniqueDestination(dir: File, sanitizedName: String, fileId: String): File {
        val primary = File(dir, sanitizedName)
        if (!primary.exists()) return primary
        val dot = sanitizedName.lastIndexOf('.')
        val stem = if (dot > 0) sanitizedName.substring(0, dot) else sanitizedName
        val ext = if (dot > 0) sanitizedName.substring(dot) else ""
        return File(dir, "$stem-${fileId.take(8)}$ext")
    }
}
