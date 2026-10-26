package com.magnity.viewer.ui

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * CPU and GPU load for the Debug overlay, sampled at most once a second.
 *
 * CPU: this app's own share of all cores, from `/proc/self/stat` — Android keeps the
 * system-wide `/proc/stat` from apps. GPU: Android has no API for it; the first vendor
 * counter that turns out readable (Adreno kgsl, MediaTek GED, Mali), probed once, else
 * "n/a" — many phones' SELinux policy keeps them from apps.
 */
class SystemLoad {
    private class GpuNode(val name: String, val path: String, val parse: (String) -> Int?)

    private val cores = Runtime.getRuntime().availableProcessors()
    private val tickHz = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
        .coerceAtLeast(1L)
    private val gpuNodes = listOf(
        GpuNode("Adreno", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", ::firstInt),
        GpuNode("Adreno", "/sys/class/kgsl/kgsl-3d0/gpubusy") { s ->   // "busy total"
            val v = s.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (v.size >= 2 && v[1] > 0) (100 * v[0] / v[1]).toInt() else null
        },
        GpuNode("GED", "/sys/kernel/ged/hal/gpu_utilization", ::firstInt),  // MediaTek
        GpuNode("Mali", "/sys/kernel/gpu/gpu_busy", ::firstInt),
        GpuNode("Mali", "/sys/class/misc/mali0/device/utilization", ::firstInt),
    )
    private var gpuNode: GpuNode? = null
    private var gpuProbed = false
    private var lastTicks = -1L
    private var lastAt = 0L
    private var text = ""

    /** "CPU 23% of 8 cores (app) · GPU 41% (GED)" — the last second's figures. */
    fun text(): String {
        val now = SystemClock.elapsedRealtime()
        if (lastAt != 0L && now - lastAt < 1000) return text
        val ticks = cpuTicks()
        val cpu = if (ticks >= 0 && lastTicks >= 0 && now > lastAt) {
            100f * (ticks - lastTicks) / tickHz / ((now - lastAt) / 1000f * cores)
        } else null
        lastTicks = ticks; lastAt = now
        val gpu = gpuPercent()
        text = "CPU " + (cpu?.let { "%.0f%%".format(it) } ?: "…") + " of $cores cores (app) · GPU " +
            (gpu?.let { "$it% (${gpuNode?.name})" } ?: "n/a")
        return text
    }

    /** utime + stime of this process, in clock ticks; −1 if unreadable. Fields 14 and 15
     *  of /proc/self/stat, counted after the parenthesised name (which may hold spaces). */
    private fun cpuTicks(): Long = runCatching {
        val s = File("/proc/self/stat").readText()
        val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
        f[11].toLong() + f[12].toLong()
    }.getOrDefault(-1L)

    private fun gpuPercent(): Int? {
        if (!gpuProbed) {
            gpuProbed = true
            gpuNode = gpuNodes.firstOrNull { read(it) != null }
        }
        return gpuNode?.let { read(it) }
    }

    private fun read(n: GpuNode): Int? =
        runCatching { n.parse(File(n.path).readText()) }.getOrNull()?.takeIf { it in 0..100 }
}

private fun firstInt(s: String): Int? = Regex("\\d+").find(s)?.value?.toIntOrNull()
