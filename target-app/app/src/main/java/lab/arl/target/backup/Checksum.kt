package lab.arl.target.backup

import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun hex(stream: InputStream, bufferSize: Int = 64 * 1024): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class ChunkPlan(
    val offset: Long,
    val size: Int
)

object ChunkPlanner {
    fun plan(sizeBytes: Long, chunkSize: Int, alreadyUploaded: Long = 0L): List<ChunkPlan> {
        require(chunkSize > 0)
        require(alreadyUploaded >= 0)
        val plans = ArrayList<ChunkPlan>()
        var offset = alreadyUploaded
        while (offset < sizeBytes) {
            val remaining = sizeBytes - offset
            val take = minOf(chunkSize.toLong(), remaining).toInt()
            plans += ChunkPlan(offset, take)
            offset += take
        }
        return plans
    }
}
