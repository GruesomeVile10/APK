package com.example.shizukumonitor

import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

@Keep
class MetricsUserService : IMetricsService.Stub() {

    private var prevWork = 0L
    private var prevTotal = 0L
    private var workingGpuPath: String? = null

    // Exact paths for Qualcomm Snapdragon 695 (Adreno 619) on OxygenOS
    private val adreno619Paths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpubusy"
    )

    override fun destroy() {
        exitProcess(0)
    }

    override fun getMetrics(): FloatArray {
        return floatArrayOf(readCpuUsage(), readAdreno619Gpu())
    }

    private fun readCpuUsage(): Float {
        try {
            val file = File("/proc/stat")
            if (file.canRead()) {
                BufferedReader(FileReader(file)).use { reader ->
                    val line = reader.readLine() ?: return@use
                    if (line.startsWith("cpu ")) {
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size >= 8) {
                            val user = parts[1].toLong()
                            val nice = parts[2].toLong()
                            val system = parts[3].toLong()
                            val idle = parts[4].toLong()
                            val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                            val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                            val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L

                            val work = user + nice + system + irq + softirq
                            val total = work + idle + iowait

                            val workDelta = (work - prevWork).coerceAtLeast(0L)
                            val totalDelta = (total - prevTotal).coerceAtLeast(1L)

                            prevWork = work
                            prevTotal = total

                            return ((workDelta.toFloat() / totalDelta.toFloat()) * 100f).coerceIn(0f, 100f)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return 0f
    }

    private fun readAdreno619Gpu(): Float {
        // Fast path: use already cached working path
        workingGpuPath?.let { path ->
            val value = readNode(path)
            if (value >= 0f) return value
            workingGpuPath = null
        }

        // Search through Snapdragon 695 candidate nodes
        for (path in adreno619Paths) {
            val value = readNode(path)
            if (value >= 0f) {
                workingGpuPath = path
                return value
            }
        }

        // Secondary fallback for Adreno: calculate clock load (cur_freq / max_freq)
        return readAdrenoClockLoad()
    }

    private fun readNode(path: String): Float {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("cat", path))
            val text = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText().trim() }
            p.waitFor()

            if (text.isEmpty()) return -1f

            // Format 1: "busy_cycles total_cycles" (from gpubusy)
            val parts = text.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val busy = parts[0].toFloatOrNull() ?: -1f
                val total = parts[1].toFloatOrNull() ?: -1f
                if (busy >= 0f && total > 0f) {
                    return ((busy / total) * 100f).coerceIn(0f, 100f)
                }
            }

            // Format 2: Direct percentage number (e.g., "45" or "45%")
            val cleaned = text.replace("%", "").trim()
            val parsed = cleaned.toFloatOrNull()
            if (parsed != null && parsed in 0f..100f) {
                return parsed
            }
        } catch (_: Exception) {}
        return -1f
    }

    private fun readAdrenoClockLoad(): Float {
        try {
            val cur = File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq").readText().trim().toFloatOrNull() ?: 0f
            val max = File("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq").readText().trim().toFloatOrNull() ?: 0f
            if (max > 0f) {
                return ((cur / max) * 100f).coerceIn(0f, 100f)
            }
        } catch (_: Exception) {}
        return -1f
    }
}
