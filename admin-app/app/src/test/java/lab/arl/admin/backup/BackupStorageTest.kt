package lab.arl.admin.backup

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private val deviceA = "11111111-1111-4111-8111-111111111111"
    private val deviceB = "22222222-2222-4222-8222-222222222222"
    private val backupA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val backupB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val fileA = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val fileB = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

    @Test
    fun sanitizesTraversalAndCollisions() {
        assertEquals("notes.txt", FilenameSanitizer.sanitize("../notes.txt"))
        assertEquals("_", FilenameSanitizer.sanitize(".."))
        val dir = tmp.newFolder("dest")
        dir.resolve("notes.txt").writeText("one")
        val second = BackupPaths.uniqueDestination(dir, "notes.txt", fileA)
        assertEquals("notes-cccccccc.txt", second.name)
        assertFalse(second.exists())
    }

    @Test
    fun isolatesDevicesAndResumesChunks() {
        val store = LocalBackupStore(tmp.newFolder("backups"), quotaBytes = 10_000)
        val payload = "hello-lab".toByteArray()
        store.writeChunk(deviceA, backupA, fileA, "notes.txt", payload.size.toLong(), "00".repeat(32), 0, payload.copyOf(5))
        assertEquals(5, store.resumeOffset(deviceA, backupA, fileA))
        store.writeChunk(deviceA, backupA, fileA, "notes.txt", payload.size.toLong(), "00".repeat(32), 5, payload.copyOfRange(5, payload.size))
        assertFailsWith<ResumeConflictException> {
            store.writeChunk(deviceA, backupA, fileA, "notes.txt", payload.size.toLong(), "00".repeat(32), 0, payload)
        }
        assertTrue(BackupPaths.backupDir(store.root, deviceA, backupA).exists())
        val other = BackupPaths.backupDir(store.root, deviceB, backupB)
        assertFalse(other.exists())
    }

    @Test
    fun verifiesChecksumAndCleansCancel() {
        val store = LocalBackupStore(tmp.newFolder("backups2"), quotaBytes = 10_000)
        val bytes = "abc".toByteArray()
        val sum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        store.writeChunk(deviceA, backupA, fileA, "a.txt", 3, sum, 0, bytes)
        val dest = store.finalizeFile(deviceA, backupA, fileA, "a.txt", sum, 3)
        assertTrue(dest.exists())
        assertEquals("VERIFIED", store.fileMeta(deviceA, backupA, fileA)?.checksumState)
        store.writeChunk(deviceA, backupA, fileB, "b.txt", 3, sum, 0, bytes)
        store.cancel(deviceA, backupA)
        assertFalse(BackupPaths.partFile(store.root, deviceA, backupA, fileB).exists())
        store.deleteBackup(deviceA, backupA)
        assertFalse(BackupPaths.backupDir(store.root, deviceA, backupA).exists())
    }

    @Test
    fun insufficientQuotaAndCorruptChecksum() {
        val store = LocalBackupStore(tmp.newFolder("tiny"), quotaBytes = 4)
        assertFailsWith<InsufficientStorageException> {
            store.writeChunk(deviceA, backupA, fileA, "big.bin", 8, "00".repeat(32), 0, ByteArray(8))
        }
        val roomy = LocalBackupStore(tmp.newFolder("roomy"), quotaBytes = 10_000)
        val bytes = "abc".toByteArray()
        roomy.writeChunk(deviceA, backupA, fileA, "a.txt", 3, "11".repeat(32), 0, bytes)
        assertFailsWith<ChecksumMismatchException> {
            roomy.finalizeFile(deviceA, backupA, fileA, "a.txt", "11".repeat(32), 3)
        }
    }
}
