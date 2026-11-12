package com.duykhai.gaming

import android.content.Context
import java.io.File
import java.util.Locale

data class ThermalProbe(val id: String, val celsius: Float, val source: String)
data class FrequencyProbe(val id: String, val mhz: Int, val source: String)
data class HardwareFrame(
    val temperatures: List<ThermalProbe>,
    val frequencies: List<FrequencyProbe>,
    val selectedCpu: ThermalProbe?,
    val timestampMs: Long
)

/** Reads named real CPU/thermal sensors. Never infers temperature from clock load. */
object RealtimeSensors {
    private val explicitCpu = Regex("(^cpu([0-9]+|[_-].*)?$|cpu[_-]?therm|cpu[_-]?temp|cpuss|cpucluster|cpu[_-]?[0-9]+)", RegexOption.IGNORE_CASE)
    private val soc = Regex("(^ap([_-].*)?$|^soc([_-].*)?$|^cluster[_-]?[0-9]+)", RegexOption.IGNORE_CASE)
    private val excluded = Regex("battery|batt|gpu|skin|usb|charger|modem|wlan|wifi|pmic", RegexOption.IGNORE_CASE)

    internal fun normalizeTemperature(raw: Long): Float? {
        val c = when {
            raw in 0L..110L -> raw.toFloat()
            raw in 111L..1099L -> raw / 10f
            raw in 1100L..110000L -> raw / 1000f
            else -> return null
        }
        return c.takeIf { it in 0f..110f }
    }

    internal fun normalizeFrequency(raw: Long): Int? {
        val mhz = when {
            raw in 100000L..10000000L -> raw / 1000L
            raw in 100000000L..10000000000L -> raw / 1000000L
            else -> return null
        }
        return mhz.toInt().takeIf { it in 100..10000 }
    }

    internal fun cpuPriority(id: String): Int = when {
        excluded.containsMatchIn(id) -> -1
        explicitCpu.containsMatchIn(id) -> 3
        soc.containsMatchIn(id) -> 2
        else -> 0
    }

    internal fun chooseCpu(readings: List<ThermalProbe>, preferred: String?): ThermalProbe? {
        if (!preferred.isNullOrEmpty())
            readings.firstOrNull { it.id == preferred }?.let { return it }
        val candidates = readings.filter { cpuPriority(it.id) > 0 }
        if (candidates.isEmpty()) return null
        val level = candidates.maxOf { cpuPriority(it.id) }
        return candidates.filter { cpuPriority(it.id) == level }.maxByOrNull { it.celsius }
    }

    internal fun parseRows(output: String, source: String): HardwareFrame {
        val temps = mutableListOf<ThermalProbe>()
        val clocks = mutableListOf<FrequencyProbe>()
        for (row in output.lineSequence()) {
            val parts = row.trim().split('|')
            if (parts.size < 3) continue
            if (parts[0] == "TEMP") {
                val name = parts[1].trim().take(100)
                val c = parts[2].trim().toLongOrNull()?.let(::normalizeTemperature)
                if (name.isNotEmpty() && c != null)
                    temps.add(ThermalProbe(name, c, parts.getOrNull(3) ?: source))
            } else if (parts[0] == "CLK") {
                val name = parts[1].trim().take(60)
                val mhz = parts[2].trim().toLongOrNull()?.let(::normalizeFrequency)
                if (name.isNotEmpty() && mhz != null)
                    clocks.add(FrequencyProbe(name, mhz, parts.getOrNull(3) ?: "scaling_cur_freq"))
            }
        }
        return HardwareFrame(temps.distinctBy { it.id }, clocks.distinctBy { it.id },
            chooseCpu(temps, null), System.currentTimeMillis())
    }

    private fun read(file: File): String? =
        try { if (file.canRead()) file.readText().trim() else null }
        catch (_: Exception) { null }

    private fun directRead(): HardwareFrame {
        val clocks = mutableListOf<FrequencyProbe>()
        val thermal = mutableListOf<ThermalProbe>()
        val folders = try {
            File("/sys/devices/system/cpu/cpufreq").listFiles()?.filter {
                it.isDirectory && it.name.matches(Regex("policy[0-9]+"))
            }?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: 99 } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        for (folder in folders.take(16)) {
            val actual = read(File(folder, "cpuinfo_cur_freq"))?.toLongOrNull()
                ?.let(::normalizeFrequency)
            val scaling = read(File(folder, "scaling_cur_freq"))?.toLongOrNull()
                ?.let(::normalizeFrequency)
            val hz = actual ?: scaling ?: continue
            clocks.add(FrequencyProbe(folder.name, hz,
                if (actual != null) "cpuinfo_cur_freq" else "scaling_cur_freq"))
        }
        if (clocks.isEmpty()) {
            for (core in 0..15) {
                val dir = File("/sys/devices/system/cpu/cpu" + core + "/cpufreq")
                val actual = read(File(dir, "cpuinfo_cur_freq"))?.toLongOrNull()?.let(::normalizeFrequency)
                val scaling = read(File(dir, "scaling_cur_freq"))?.toLongOrNull()?.let(::normalizeFrequency)
                val hz = actual ?: scaling ?: continue
                clocks.add(FrequencyProbe("cpu" + core, hz,
                    if (actual != null) "cpuinfo_cur_freq" else "scaling_cur_freq"))
            }
        }
        val zones = try {
            File("/sys/class/thermal").listFiles()?.filter {
                it.isDirectory && it.name.startsWith("thermal_zone")
            }?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 999 } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        for (zone in zones.take(200)) {
            val name = read(File(zone, "type"))?.take(100) ?: continue
            val raw = read(File(zone, "temp"))?.toLongOrNull() ?: continue
            val c = normalizeTemperature(raw) ?: continue
            thermal.add(ThermalProbe(name, c, zone.name))
        }
        return HardwareFrame(thermal, clocks, chooseCpu(thermal, null), System.currentTimeMillis())
    }

    private val shellRead = """
      for p in /sys/devices/system/cpu/cpufreq/policy*; do
        [ -d "@@p" ] || continue
        if [ -r "@@p/cpuinfo_cur_freq" ]; then
          printf 'CLK|%s|%s|cpuinfo_cur_freq\n' "@@{p##*/}" "@@(cat "@@p/cpuinfo_cur_freq")"
        elif [ -r "@@p/scaling_cur_freq" ]; then
          printf 'CLK|%s|%s|scaling_cur_freq\n' "@@{p##*/}" "@@(cat "@@p/scaling_cur_freq")"
        fi
      done
      for p in /sys/class/thermal/thermal_zone*; do
        [ -r "@@p/type" ] && [ -r "@@p/temp" ] || continue
        printf 'TEMP|%s|%s|%s\n' "@@(cat "@@p/type")" "@@(cat "@@p/temp")" "@@{p##*/}"
      done
    """.trimIndent().replace("@@", 36.toChar().toString())

    @Volatile private var lastPrivileged: HardwareFrame? = null
    @Volatile private var lastPrivilegedAt = 0L
    private fun privileged(): HardwareFrame? {
        val now = System.currentTimeMillis()
        if (now - lastPrivilegedAt < 2500L) return lastPrivileged
        val root = RootAccess.status()
        if (!root && !CommandRunner.ready()) return lastPrivileged
        val response = CommandRunner.run(shellRead, root)
        lastPrivilegedAt = now
        if (response.getOrNull(0) == "0") {
            val data = parseRows(response.getOrElse(1) { "" }, "privileged-sysfs")
            lastPrivileged = data
            return data
        }
        return lastPrivileged
    }

    fun sample(context: Context? = null): HardwareFrame {
        val direct = directRead()
        var temps = direct.temperatures
        var clocks = direct.frequencies
        if (clocks.isEmpty() || temps.none { cpuPriority(it.id) > 0 }) {
            val extra = privileged()
            if (extra != null) {
                if (clocks.isEmpty()) clocks = extra.frequencies
                temps = (temps + extra.temperatures).distinctBy { it.id }
            }
        }
        val selection = context?.getSharedPreferences("monitor_sensor", Context.MODE_PRIVATE)
            ?.getString("cpu_sensor", null)
        return HardwareFrame(temps, clocks, chooseCpu(temps, selection), System.currentTimeMillis())
    }

    fun clockLabel(frame: HardwareFrame): String =
        if (frame.frequencies.isEmpty()) "—"
        else frame.frequencies.take(6).joinToString("  •  ") {
            it.id.removePrefix("policy") + ": " + it.mhz + " MHz"
        }

    fun detail(frame: HardwareFrame): String {
        val cpu = frame.selectedCpu?.let {
            String.format(Locale.US, "CPU hotspot %.1f°C • %s (%s)", it.celsius, it.id, it.source)
        } ?: "Không nhận diện được cảm biến CPU"
        val source = frame.frequencies.firstOrNull()?.source ?: "chưa đọc được xung"
        return cpu + " | Xung: " + source +
            if (source == "scaling_cur_freq") " (xung cpufreq yêu cầu)" else ""
    }
}
