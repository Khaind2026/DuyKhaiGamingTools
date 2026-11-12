package com.duykhai.gaming

import java.util.Locale
import java.util.concurrent.TimeUnit

data class MonitorSnapshot(
    val fps: Float?,
    val temperatureC: Float?,
    val cpuClocks: List<Pair<String, Int>>,
    val diagnostic: String
)

/**
 * Read-only system telemetry.
 * FPS is a best-effort measurement of *presented frames* on the selected game's
 * SurfaceFlinger layer. It is NOT the panel refresh rate or the game FPS setting.
 */
object PerformanceMonitor {
    private val packageRegex = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val cpuTypeRegex = Regex("cpu|ap|soc|cluster|tsens|silver|gold|little|big", RegexOption.IGNORE_CASE)
    private val ignoreTypeRegex = Regex("battery|batt|charger|skin|usb|modem|gpu", RegexOption.IGNORE_CASE)

    private fun sensorScript(): String {
        val d = '$'
        return "for p in /sys/devices/system/cpu/cpufreq/policy*; do " +
            "if [ -r " + d + "p/scaling_cur_freq ]; then " +
            "printf 'CLK|%s|%s\\n' " + d + "{p##*/} " + d + "(cat " + d + "p/scaling_cur_freq); fi; done; " +
            "for p in /sys/class/thermal/thermal_zone*; do " +
            "if [ -r " + d + "p/type ] && [ -r " + d + "p/temp ]; then " +
            "printf 'TEMP|%s|%s\\n' " + d + "(cat " + d + "p/type) " + d + "(cat " + d + "p/temp); fi; done"
    }

    internal fun normalizeTemperature(raw: Long): Float? {
        val c = when {
            raw >= 1000 -> raw / 1000f
            raw >= 200 -> raw / 10f
            else -> raw.toFloat()
        }
        return c.takeIf { it in 0f..110f }
    }

    internal fun readSensorData(output: String): Pair<List<Pair<String, Int>>, Float?> {
        val clocks = mutableListOf<Pair<String, Int>>()
        val temps = mutableListOf<Float>()
        output.lineSequence().forEach { line ->
            val p = line.trim().split('|')
            if (p.size < 3) return@forEach
            when (p[0]) {
                "CLK" -> {
                    val khz = p[2].trim().toLongOrNull()
                    if (khz != null && khz in 100_000L..10_000_000L && p[1].startsWith("policy")) {
                        clocks.add(p[1] to (khz / 1000).toInt())
                    }
                }
                "TEMP" -> {
                    val type = p[1].trim()
                    if (cpuTypeRegex.containsMatchIn(type) && !ignoreTypeRegex.containsMatchIn(type)) {
                        val v = p[2].trim().toLongOrNull()?.let(::normalizeTemperature)
                        if (v != null) temps.add(v)
                    }
                }
            }
        }
        return clocks.distinctBy { it.first }.sortedBy { it.first.removePrefix("policy").toIntOrNull() ?: 9999 } to
            temps.maxOrNull()
    }

    internal fun calculateFps(latency: String, nowNanos: Long): Float? {
        // First line is VSYNC period; following rows are 3 frame timestamps (ns).
        val recent = latency.lineSequence().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size != 3) null else parts[2].toLongOrNull()
        }.filter { it > 0 && it <= nowNanos && nowNanos - it < 3_000_000_000L }
            .distinct().sorted().toList()
        if (recent.size < 4) return null
        val ns = recent.last() - recent.first()
        if (ns <= 0L) return null
        val fps = (recent.size - 1) * 1_000_000_000.0 / ns
        return fps.toFloat().takeIf { it in 1f..300f }
    }

    private fun command(shell: String, root: Boolean): Array<String> {
        return if (root) CommandRunner.run(shell, true)
        else if (CommandRunner.ready()) CommandRunner.run(shell, false)
        else ShellProcess.execute(listOf("sh", "-c", shell))
    }

    private fun singleQuoted(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

    private fun gameFps(pkg: String, root: Boolean): Float? {
        if (!packageRegex.matches(pkg) || (!root && !CommandRunner.ready())) return null
        val search = command("dumpsys SurfaceFlinger --list | grep -F " + singleQuoted(pkg) + " | head -n 8", root)
        if (search.getOrNull(0) != "0") return null
        val layers = search.getOrNull(1)?.lineSequence()?.map { it.trim() }
            ?.filter { it.contains(pkg, true) && it.isNotEmpty() }?.toList().orEmpty()
        val layer = layers.firstOrNull { it.contains("SurfaceView", true) }
            ?: layers.firstOrNull { it.contains("BLAST", true) }
            ?: layers.firstOrNull() ?: return null
        val latency = command("dumpsys SurfaceFlinger --latency " + singleQuoted(layer), root)
        if (latency.getOrNull(0) != "0") return null
        return calculateFps(latency.getOrNull(1).orEmpty(), System.nanoTime())
    }

    fun sample(gamePackage: String, hasRoot: Boolean): MonitorSnapshot {
        val initial = command(sensorScript(), false)
        var readings = readSensorData(initial.getOrNull(1).orEmpty())
        if (hasRoot && (readings.first.isEmpty() || readings.second == null)) {
            val privileged = command(sensorScript(), true)
            val elevated = readSensorData(privileged.getOrNull(1).orEmpty())
            readings = (if (elevated.first.isNotEmpty()) elevated.first else readings.first) to
                (elevated.second ?: readings.second)
        }
        val fps = try { gameFps(gamePackage.trim(), hasRoot) } catch (_: Exception) { null }
        return MonitorSnapshot(
            fps = fps,
            temperatureC = readings.second,
            cpuClocks = readings.first,
            diagnostic = if (fps == null) "FPS chưa có dữ liệu layer game" else "FPS ước tính từ SurfaceFlinger"
        )
    }

    fun fpsLabel(value: Float?): String =
        value?.let { String.format(Locale.US, "%.1f FPS", it) } ?: "—"

    fun temperatureLabel(value: Float?): String =
        value?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"

    fun clocksLabel(clocks: List<Pair<String, Int>>): String =
        if (clocks.isEmpty()) "—"
        else clocks.take(6).joinToString("  •  ") { it.first.removePrefix("policy") + ": " + it.second + " MHz" }
}
