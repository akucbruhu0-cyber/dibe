package app.dibe.android.models

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** The same magics desktop accepts (download.rs): whisper.cpp's 0x67676d6c little-endian, and GGUF. */
private val MODEL_MAGICS = listOf("lmgg", "GGUF").map { it.toByteArray(Charsets.US_ASCII) }

/**
 * Cheapest check first: exact size, then the model magic, then the streamed sha256 when [sha256] is
 * given. Blocking; call on Dispatchers.IO. False on any mismatch or read error.
 */
internal fun verifyModelFile(file: File, expectedSize: Long, sha256: String?): Boolean = try {
    file.length() == expectedSize &&
        hasModelMagic(file) &&
        (sha256 == null || sha256Hex(file).equals(sha256, ignoreCase = true))
} catch (e: IOException) {
    false
}

private fun hasModelMagic(file: File): Boolean {
    val magic = ByteArray(4)
    RandomAccessFile(file, "r").use { it.readFully(magic) }
    return MODEL_MAGICS.any { it.contentEquals(magic) }
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20)
    file.inputStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}
