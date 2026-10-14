package com.example.shizukumonitor

import android.os.SystemClock
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

@Keep
class MetricsUserService : IMetricsService.Stub() {

    private var prevCpuTotal = 0L
    private var prevCpuIdle = 0L

    // FPS calculation tracking
    private var lastFpsSampleTimeNs = 0L
    private var lastFrameTimestampNs = 0L
    private var cachedFps = 0f

    // Comprehensive list of vendor GPU utilization nodes across OEMs
    private val gpuPaths = listOf(
        // Qualcomm Adreno paths
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
        // MediaTek Dimensity / Helio paths
        "/sys/devices/platform/13040000.mali/utilization",
        "/sys/devices/platform/13000000.mali/utilization",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/module/pvrsrvkm/parameters/gpu_loading",
        "/sys/kernel/debug/ged/hal/gpu_utilization",
        "/sys/devices/platform/gpufreq/gpu_usage",
        // Samsung Exynos / Google Tensor / Generic Linux
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/platform/mali.0/utilization"
    )

    override fun destroy() {
        exitProcess(0)
    }

    override fun getMetrics(): FloatArray {
        val cpu = readCpuUsage()
        val gpu = readGpuUsage()
        val fps = readSurfaceFlingerFps()
        return floatArrayOf(cpu, gpu, fps)
    }

    private fun readCpuUsage(): Float {
        try {
            val file = File("/proc/stat")
            if (file.canRead()) {
                BufferedReader(FileReader(file)).use { reader ->
                    val line = reader.readLine() ?: return@use
                    if (line.startsWith("cpu ")) {
                        val parts = line.trim().split("\\s+".toRegex())
                        val user = parts[1].toLong()
                        val nice = parts[2].toLong()
                        val system = parts[3].toLong()
                        val idle = parts[4].toLong()
                        val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
                        val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
                        val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L

                        val idleTime = idle + iowait
                        val totalTime = user + nice + system + idleTime + irq + softirq

                        val totalDelta = (totalTime - prevCpuTotal).coerceAtLeast(1L)
                        val idleDelta = (idleTime - prevCpuIdle).coerceAtLeast(0L)

                        prevCpuTotal = totalTime
                        prevCpuIdle = idleTime

                        return (((totalDelta - idleDelta).toFloat() / totalDelta) * 100f).coerceIn(0f, 100f)
                    }
                }
            }
        } catch (_: Exception) {}
        return 0f
    }

    private fun readGpuUsage(): Float {
        // Method 1: Check known vendor sysfs paths
        for (path in gpuPaths) {
            try {
                val file = File(path)
                if (!file.canRead()) continue
                val raw = file.readText().trim()
                if (raw.isEmpty()) continue

                // 1a. Adreno "gpubusy" format: "   12450    98420" (busy_cycles total_cycles)
                val parts = raw.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val busy = parts[0].toFloatOrNull() ?: 0f
                    val total = parts[1].toFloatOrNull() ?: 0f
                    if (total > 0f) {
                        return ((busy / total) * 100f).coerceIn(0f, 100f)
                    }
                }

                // 1b. Single number or percentage format (e.g. "45" or "45 %")
                val cleaned = raw.replace("%", "").trim()
                cleaned.toFloatOrNull()?.let {
                    return it.coerceIn(0f, 100f)
                }
            } catch (_: Exception) {}
        }

        // Method 2: Fallback via dumpsys gpu
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys gpu --gpustats"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()

            val match = Regex("""utilization:\s*(\d+)""").find(output)
            if (match != null) {
                return match.groupValues[1].toFloat().coerceIn(0f, 100f)
            }
        } catch (_: Exception) {}

        return -1f // Still unreadable (vendor locked sysfs to root only)
    }

    private fun readSurfaceFlingerFps(): Float {
        try {
            val nowNs = System.nanoTime()
            val elapsedSec = (nowNs - lastFpsSampleTimeNs) / 1_000_000_000.0f

            // Only recalculate every ~500ms to avoid excessive shell calls
            if (lastFpsSampleTimeNs != 0L && elapsedSec < 0.5f) {
                return cachedFps
            }

            // Query latency timestamps for the active display/layer
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys SurfaceFlinger --latency"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val periodLine = reader.readLine() // Line 1: refresh period in nanoseconds (e.g. 16666666 for 60Hz)
            if (periodLine == null || periodLine.trim().isEmpty()) {
                process.destroy()
                return cachedFps
            }

            var frameCount = 0
            var newestTimestampNs = lastFrameTimestampNs

            reader.forEachLine { line ->
                val tokens = line.trim().split("\\s+".toRegex())
                // Each line has 3 nanosecond timestamps: [app_draw, vsync_present, ready]
                if (tokens.size >= 3) {
                    val actualPresentTimeNs = tokens[1].toLongOrNull() ?: 0L
                    // Skip frames that are pending or dropped (marked as 0 or Long.MAX_VALUE)
                    if (actualPresentTimeNs in 1 until Long.MAX_VALUE) {
                        if (actualPresentTimeNs > lastFrameTimestampNs) {
                            frameCount++
                            if (actualPresentTimeNs > newestTimestampNs) {
                                newestTimestampNs = actualPresentTimeNs
                            }
                        }
                    }
                }
            }
            process.waitFor()

            if (lastFpsSampleTimeNs != 0L && elapsedSec > 0.05f) {
                cachedFps = (frameCount / elapsedSec).coerceIn(0f, 240f)
            }

            lastFpsSampleTimeNs = nowNs
            if (newestTimestampNs > lastFrameTimestampNs) {
                lastFrameTimestampNs = newestTimestampNs
            }

            return cachedFps
        } catch (_: Exception) {}
        return 0f
    }
}
