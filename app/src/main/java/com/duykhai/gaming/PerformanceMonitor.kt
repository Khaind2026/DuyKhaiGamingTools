package com.duykhai.gaming

import java.util.Locale
import android.content.Context
import android.os.PowerManager

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
    private val cpuTypeRegex = Regex("cpu|ap|soc|cluster|tsens|silver|gold|little|big|cpuss|cpu[0-9]", RegexOption.IGNORE_CASE)
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

    private val packagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")
    @Volatile private var captureEnabled = false
    @Volatile private var capturePermission = false

    internal fun averageFpsForPackage(output: String, pkg: String): Float? {
        if (!packagePattern.matches(pkg)) return null
        var layer = ""
        var selected: Float? = null
        var score = -1
        for (line in output.lineSequence()) {
            val text = line.trim()
            if (text.startsWith("layerName =", true)) layer = text.substringAfter('=').trim()
            if (text.startsWith("averageFPS =", true) && layer.contains(pkg, true)) {
                val fps = text.substringAfter('=').trim().toFloatOrNull()
                val quality = when {
                    layer.contains("SurfaceView", true) -> 3
                    layer.contains("BLAST", true) -> 2
                    else -> 1
                }
                if (fps != null && fps in 1f..300f && quality >= score) {
                    selected = fps
                    score = quality
                }
            }
        }
        return selected
    }

    private fun readFps(pkg: String, root: Boolean): Pair<Float?, String> {
        if (!packagePattern.matches(pkg))
            return null to "Chọn package game trước khi đo FPS."
        if (!root && !CommandRunner.ready())
            return null to "FPS game cần Root được cấp quyền hoặc Shizuku đã kết nối."
        // Android's documented frame-rate measurement uses SurfaceFlinger timestats.
        // No superuser prompt is made here; only previously-authorized sessions.
        if (!captureEnabled || capturePermission != root) {
            val start = command("dumpsys SurfaceFlinger --timestats -clear -enable", root)
            if (start.getOrNull(0) != "0")
                return null to "SurfaceFlinger từ chối bật timestats."
            captureEnabled = true
            capturePermission = root
            return null to "Đang tích lũy mẫu FPS; mở game rồi xem lại."
        }
        val results = command(
            "dumpsys SurfaceFlinger --timestats -dump | grep -E 'layerName = |averageFPS = '",
            root
        )
        if (results.getOrNull(0) != "0")
            return null to "Không đọc được timestats từ SurfaceFlinger."
        val fps = averageFpsForPackage(results.getOrNull(1).orEmpty(), pkg)
        if (fps == null) return null to "Chưa có layer đang render cho game; mở game và chọn đúng package."
        return fps to "FPS trung bình từ layer game (SurfaceFlinger)."
    }

    internal fun parseThermalService(output: String): Float? {
        // Android's thermalservice dump includes Temperature{mValue=..., mType=0, mName=...}
        // mType=0 is CPU, in Celsius; battery / skin sensors must not be mislabeled.
        val values = Regex("""(?i)(?:mValue|value)\s*=\s*([0-9]+(?:\.[0-9]+)?)[^\n]{0,150}?(?:mType|type)\s*=\s*(?:0|CPU)\b""")
            .findAll(output).mapNotNull { it.groupValues[1].toFloatOrNull() }
            .filter { it in 0f..110f }.toList()
        return values.maxOrNull()
    }

    fun sample(gamePackage: String, hasRoot: Boolean): MonitorSnapshot {
        val first = command(sensorScript(), false)
        var readings = readSensorData(first.getOrNull(1).orEmpty())
        if (readings.first.isEmpty() || readings.second == null) {
            // With a previously granted session, try privileged sysfs once.
            if (hasRoot || CommandRunner.ready()) {
                val extra = command(sensorScript(), hasRoot)
                val elevated = readSensorData(extra.getOrNull(1).orEmpty())
                readings = (if (elevated.first.isNotEmpty()) elevated.first else readings.first) to
                    (elevated.second ?: readings.second)
            }
        }
        if (readings.second == null) {
            val thermal = command("dumpsys thermalservice", hasRoot)
            val fallback = parseThermalService(thermal.getOrNull(1).orEmpty())
            readings = readings.first to fallback
        }
        val frame = try { readFps(gamePackage.trim(), hasRoot) }
            catch (_: Exception) { null to "SurfaceFlinger không hỗ trợ thiết bị này." }
        val sensorText = if (readings.second == null) "Không đọc được cảm biến CPU." else ""
        return MonitorSnapshot(
            fps = frame.first,
            temperatureC = readings.second,
            cpuClocks = readings.first,
            diagnostic = frame.second + if (sensorText.isNotEmpty()) " " + sensorText else ""
        )
    }


    data class ExternalReadout(
        val displayName: String,
        val displayId: Int,
        val resolution: String,
        val refreshHz: Float?,
        val presentedLayerFps: Float?,
        val diagnostic: String
    )

    internal fun parseExternalLayerFps(stats: String, displayName: String): Float? {
        // Android exposes layer timestats, not a guaranteed HDMI-wide compositor FPS.
        // Use only clearly external/HDMI/DeX-tagged layers, never substitute display Hz.
        val keys = listOf("external", "hdmi", "dex", displayName.lowercase())
        var name = ""
        var matched: Float? = null
        var bestScore = -1
        for (line in stats.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("layerName =", ignoreCase = true)) name = l.substringAfter('=').trim()
            if (l.startsWith("averageFPS =", ignoreCase = true)) {
                val fps = l.substringAfter('=').trim().toFloatOrNull()
                val score = when {
                    name.contains(displayName, ignoreCase = true) && displayName.isNotBlank() -> 3
                    name.contains("hdmi", ignoreCase = true) ||
                        name.contains("external", ignoreCase = true) -> 2
                    name.contains("dex", ignoreCase = true) -> 1
                    else -> -1
                }
                if (fps != null && fps in 1f..300f && score > bestScore) {
                    matched = fps
                    bestScore = score
                }
            }
        }
        return matched
    }

    fun external(context: Context, hasRoot: Boolean): ExternalReadout {
        val display = try { FoldDisplayTools.activeExternalDisplay(context) }
            catch (_: Exception) { null }
            ?: return ExternalReadout("Chưa kết nối HDMI/DeX", -1, "—", null, null,
                "Không phát hiện màn hình ngoài")
        val mode = display.mode
        val size = mode.physicalWidth.toString() + " × " + mode.physicalHeight
        if (!hasRoot && !CommandRunner.ready())
            return ExternalReadout(display.name, display.displayId, size, display.refreshRate, null,
                "Muốn đo khung hình layer cần Root đã cấp hoặc Shizuku")
        // Enable once; timestamps are later sampled independently of whether a game is opened.
        if (!captureEnabled || capturePermission != hasRoot) {
            val enabled = command("dumpsys SurfaceFlinger --timestats -clear -enable", hasRoot)
            if (enabled.getOrNull(0) != "0")
                return ExternalReadout(display.name, display.displayId, size, display.refreshRate, null,
                    "Không bật được SurfaceFlinger timestats")
            captureEnabled = true
            capturePermission = hasRoot
        }
        val result = command("dumpsys SurfaceFlinger --timestats -dump", hasRoot)
        val fps = if (result.getOrNull(0) == "0")
            parseExternalLayerFps(result.getOrElse(1) { "" }, display.name) else null
        return ExternalReadout(display.name, display.displayId, size, display.refreshRate, fps,
            if (fps == null) "HDMI đã kết nối; ROM không xuất FPS layer màn ngoài" else
                "FPS trung bình layer gắn nhãn HDMI/DeX, không phải FPS toàn màn")
    }

    fun fpsLabel(value: Float?): String =
        value?.let { String.format(Locale.US, "%.1f FPS", it) } ?: "—"

    fun temperatureLabel(value: Float?): String =
        value?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"

    fun clocksLabel(clocks: List<Pair<String, Int>>): String =
        if (clocks.isEmpty()) "—"
        else clocks.take(6).joinToString("  •  ") { it.first.removePrefix("policy") + ": " + it.second + " MHz" }
}
