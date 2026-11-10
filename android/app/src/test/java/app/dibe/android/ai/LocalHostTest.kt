package app.dibe.android.ai

import app.dibe.android.ai.AiClient.Companion.exposesKey
import app.dibe.android.ai.AiClient.Companion.isLocalHost
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHostTest {
    @Test
    fun localAndPrivateAddressesOnly() {
        listOf(
            "localhost", "LOCALHOST", "nas.local", "nas.local.", "127.0.0.1", "127.8.9.10", "10.0.0.5", "172.16.0.1", "172.31.255.255",
            "192.168.1.10", "169.254.3.4", "[::1]", "::1", "[fd12:3456::1]", "fc00::1", "[fe80::1%wlan0]", "febf::1",
        ).forEach { assertTrue(it, isLocalHost(it)) }
        listOf(
            "api.openai.com", "example.com", "local", "evil.local.example.com", "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1",
            "11.0.0.1", "010.0.0.1", "0x7f.0.0.1", "127.1", "1.2.3.4.5", "256.1.1.1", "", "[2001:db8::1]", "fec0::1", "::ffff:192.168.0.1",
        ).forEach { assertFalse(it, isLocalHost(it)) }
    }

    @Test
    fun exposesKey_onlyPlainHttpOffTheLanWithAKey() {
        assertTrue(exposesKey("http://api.example.com/v1", "sk"))
        assertTrue(exposesKey(" HTTP://8.8.8.8:8080/v1 ", "sk"))
        assertFalse(exposesKey("http://api.example.com/v1", " "))
        assertFalse(exposesKey("https://api.example.com/v1", "sk"))
        assertFalse(exposesKey("http://192.168.1.4:8080/v1", "sk"))
        assertFalse(exposesKey("http://[::1]:8080/v1", "sk"))
        assertFalse(exposesKey("not a url", "sk"))
    }

    @Test
    fun keyIsNotSentOverRemotePlainHttp() = runTest {
        val kind = try {
            // Refused before connecting: .invalid never resolves, so a NETWORK error would mean it tried.
            AiClient().test(AiSettings(platform = AiPlatform.OPENAI, openaiBaseUrl = "http://dibe.invalid/v1", openaiApiKey = "sk"))
            null
        } catch (e: AiException) {
            e.kind
        }
        assertEquals(AiException.Kind.INSECURE, kind)
    }
}
