package app.dibe.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuSupportTest {
    private fun cpuinfo(vararg cores: String) = cores.withIndex().joinToString("\n\n") { (i, features) ->
        "processor\t: $i\nBogoMIPS\t: 49.15\nFeatures\t: $features\nCPU implementer\t: 0x41"
    }

    @Test
    fun theFastBuildNeedsI8mmAndBf16OnEveryCore() {
        val v9 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm asimddp i8mm bf16 sve2"
        assertEquals("dibe", engineLibrary(cpuFeatures(cpuinfo(v9, v9))))
        // One core without i8mm: a thread that lands on it would die, so the whole phone gets compat.
        assertEquals("dibe_compat", engineLibrary(cpuFeatures(cpuinfo(v9, v9.replace(" i8mm", "")))))
    }

    @Test
    fun dotProductAloneGetsTheCompatBuild() {
        // Snapdragon 888 (Cortex-X1/A78/A55).
        val armv82 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"
        assertEquals("dibe_compat", engineLibrary(cpuFeatures(cpuinfo(armv82, armv82))))
    }

    @Test
    fun withoutDotProductNothingLoads() {
        // Cortex-A53/A73, and a /proc/cpuinfo that could not be read.
        assertNull(engineLibrary(cpuFeatures(cpuinfo("fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"))))
        assertNull(engineLibrary(cpuFeatures("")))
    }
}
