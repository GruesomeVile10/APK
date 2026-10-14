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
    private var prevFlipCount = -1L
    private var prevFlipTimeMs = 0L

    private val gpuPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/class/misc/mali0/device/utilization"
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
        return readCpuFreqLoadFallback()
    }

    private fun readCpuFreqLoadFallback(): Float {
        var totalRatio = 0f
        var coreCount = 0
        for (i in 0..15) {
            val curFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            val maxFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (!curFile.exists() || !maxFile.exists()) break
            try {
                val cur = curFile.readText().trim().toFloat()
                val max = maxFile.readText().trim().toFloat()
                if (max > 0) {
                    totalRatio += (cur / max)
                    coreCount++
                }
            } catch (_: Exception) {}
        }
        return if (coreCount > 0) ((totalRatio / coreCount) * 100f).coerceIn(0f, 100f) else 0f
    }

    private fun readGpuUsage(): Float {
        for (path in gpuPaths) {
            try {
                val file = File(path)
                if (!file.canRead()) continue
                val raw = file.readText().trim()
                if (raw.isEmpty()) continue

                val parts = raw.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val busy = parts[0].toFloatOrNull() ?: 0f
                    val total = parts[1].toFloatOrNull() ?: 0f
                    return if (total > 0f) ((busy / total) * 100f).coerceIn(0f, 100f) else 0f
                }

                val cleaned = raw.replace("%", "").trim()
                cleaned.toFloatOrNull()?.let { return it.coerceIn(0f, 100f) }
            } catch (_: Exception) {}
        }
        return -1f
    }

    private fun readSurfaceFlingerFps(): Float {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "service call SurfaceFlinger 1013"))
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()

            val regex = Regex("""Parcel\(\s*[0-9a-fA-F]{8}\s+([0-9a-fA-F]{8})""")
            val match = regex.find(output)
            if (match != null) {
                val currentFlips = match.groupValues[1].toLong(16)
                val nowMs = SystemClock.elapsedRealtime()

                if (prevFlipCount >= 0L && nowMs > prevFlipTimeMs) {
                    val deltaFlips = currentFlips - prevFlipCount
                    val deltaSeconds = (nowMs - prevFlipTimeMs) / 1000f
                    prevFlipCount = currentFlips
                    prevFlipTimeMs = nowMs
                    return (deltaFlips / deltaSeconds).coerceIn(0f, 240f)
                }
                prevFlipCount = currentFlips
                prevFlipTimeMs = nowMs
            }
        } catch (_: Exception) {}
        return 0f
    }
}
