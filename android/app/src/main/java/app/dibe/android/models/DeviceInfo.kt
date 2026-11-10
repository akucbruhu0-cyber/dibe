package app.dibe.android.models

import android.app.ActivityManager
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.IOException

/** Hardware facts that decide which models are safe to run. */
class DeviceInfo(private val context: android.content.Context) {
    private fun memoryInfo() = ActivityManager.MemoryInfo().also {
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }

    val totalRamBytes: Long by lazy { memoryInfo().totalMem }
    fun availableRamBytes(): Long = memoryInfo().availMem

    /** Free bytes on the volume the models are kept on (internal storage). */
    fun freeStorageBytes(): Long = try {
        StatFs(context.filesDir.path).availableBytes
    } catch (e: IllegalArgumentException) {
        0L
    }

    /** Number of cores whose max frequency is the top tier (big + prime). */
    val fastCores: Int by lazy {
        fastCoreCount(readMaxFrequencies()) ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    }

    val socName: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != Build.UNKNOWN) {
            Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
}

private fun readMaxFrequencies(): List<Long> =
    File("/sys/devices/system/cpu").listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
        .orEmpty()
        .mapNotNull { cpu ->
            try {
                File(cpu, "cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }

/**
 * Tri-cluster (prime + big + LITTLE, e.g. Tensor G4 1+3+4) → the top two tiers; two clusters → the
 * top one; one cluster → all. Null when nothing was readable.
 * ponytail: tiers by exact frequency undercount all-big two-cluster chips (Snapdragon 8 Elite 2+6 → 2);
 * switch to a ratio of the top frequency if the engine measures that as slow.
 */
internal fun fastCoreCount(maxFrequencies: List<Long>): Int? {
    if (maxFrequencies.isEmpty()) return null
    val tiers = maxFrequencies.distinct().sortedDescending()
    val slowestFast = tiers[if (tiers.size >= 3) 1 else 0]
    return maxFrequencies.count { it >= slowestFast }
}
