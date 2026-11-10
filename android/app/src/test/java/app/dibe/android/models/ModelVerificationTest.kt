package app.dibe.android.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelVerificationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun model(magic: String, size: Int = 64): File =
        tmp.newFile().apply { writeBytes(magic.toByteArray() + ByteArray(size - magic.length)) }

    @Test fun acceptsGgmlAndGgufOfTheExpectedSize() {
        assertTrue(verifyModelFile(model("lmgg"), 64, sha256 = null))
        assertTrue(verifyModelFile(model("GGUF"), 64, sha256 = null))
    }

    @Test fun rejectsWrongSizeForeignHeaderAndMissingFile() {
        assertFalse(verifyModelFile(model("GGUF"), 65, sha256 = null))
        assertFalse(verifyModelFile(model("<htm"), 64, sha256 = null))
        assertFalse(verifyModelFile(tmp.newFile().apply { writeBytes("GG".toByteArray()) }, 2, sha256 = null))
        assertFalse(verifyModelFile(File(tmp.root, "absent.bin"), 0, sha256 = null))
    }

    @Test fun checksSha256WhenKnown() {
        // shasum -a 256 of "GGUF" followed by 60 zero bytes
        val sha = "c7c075888b4eab44984e6a6f310646f381de9c3b03ce0817519910dd057088d1"
        assertTrue(verifyModelFile(model("GGUF"), 64, sha))
        assertTrue(verifyModelFile(model("GGUF"), 64, sha.uppercase()))
        assertFalse(verifyModelFile(model("GGUF"), 64, "00".repeat(32)))
    }
}
