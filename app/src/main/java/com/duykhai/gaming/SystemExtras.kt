package com.duykhai.gaming

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** Root is required for CPU; display commands can also use the Shizuku service. */
object RootAccess {
    // Explicit, cached user authorization. No automatic su prompts on each poll/resume.
    @Volatile private var granted = false
    fun status(): Boolean = granted
    fun request(): Boolean {
        val ok = try {
            val r = ShellProcess.execute(listOf("su", "-c", "id -u"))
            r.getOrNull(0) == "0" && r.getOrNull(1)?.trim() == "0"
        } catch (_: Exception) { false }
        granted = ok
        return ok
    }
    fun invalidate() { granted = false }
}

object ShizukuAccess {
    fun status(): String = try {
        if (!Shizuku.pingBinder()) "CHƯA KẾT NỐI"
        else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
            "ĐÃ CẤP QUYỀN (UID ${Shizuku.getUid()})"
        else "ĐÃ KẾT NỐI • CHƯA CẤP QUYỀN"
    } catch (_: Exception) { "CHƯA KẾT NỐI" }

    fun request() {
        if (Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1102)
        }
    }
}

object GameCatalog {
    val labels = arrayOf(
        "Tùy chỉnh package",
        "PUBG Mobile VN",
        "PUBG Mobile Global",
        "Free Fire",
        "Free Fire MAX",
        "Delta Force Global",
        "Delta Force Garena",
        "Call of Duty Mobile VN",
        "Call of Duty Mobile Global"
    )
    val packages = arrayOf(
        "",
        "com.vng.pubgmobile",
        "com.tencent.ig",
        "com.dts.freefireth",
        "com.dts.freefiremax",
        "com.proxima.dfm",
        "com.garena.game.df",
        "com.vng.codmvn",
        "com.activision.callofduty.shooter"
    )
    fun position(value: String): Int =
        packages.indexOf(value).takeIf { it > 0 } ?: 0
}

/** Uses only stock cpuinfo_max_freq and leaves thermal and min frequencies alone.
 * CPU sysfs is not writable on many Samsung kernels, so report failures accurately.
 */
object CpuProfiles {
    fun command(mode: Int): String {
        require(mode in 1..3)
        val percent = when (mode) { 1 -> 70; 2 -> 90; else -> 100 }
        val dollar = 36.toChar()
        return """
            changed=0
            failed=0
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
              [ -d "${dollar}p" ] || continue
              [ -r "${dollar}p/cpuinfo_max_freq" ] || continue
              [ -r "${dollar}p/scaling_min_freq" ] || continue
              [ -w "${dollar}p/scaling_max_freq" ] || { failed=${dollar}((failed+1)); continue; }
              stock=${dollar}(cat "${dollar}p/cpuinfo_max_freq")
              minimum=${dollar}(cat "${dollar}p/scaling_min_freq")
              case "${dollar}stock:${dollar}minimum" in *[!0-9:]*|:*|*:) failed=${dollar}((failed+1)); continue;; esac
              target=${dollar}((stock*$percent/100))
              [ "${dollar}target" -ge "${dollar}minimum" ] || target=${dollar}minimum
              if [ -r "${dollar}p/scaling_available_frequencies" ]; then
                best=0
                for f in ${dollar}(cat "${dollar}p/scaling_available_frequencies"); do
                  case "${dollar}f" in *[!0-9]*|'') continue;; esac
                  if [ "${dollar}f" -le "${dollar}target" ] && [ "${dollar}f" -ge "${dollar}minimum" ] && [ "${dollar}f" -gt "${dollar}best" ]; then best=${dollar}f; fi
                done
                [ "${dollar}best" -eq 0 ] || target=${dollar}best
              fi
              if echo "${dollar}target" > "${dollar}p/scaling_max_freq" 2>/dev/null; then
                actual=${dollar}(cat "${dollar}p/scaling_max_freq")
                echo "${dollar}p: requested=${dollar}target actual=${dollar}actual kHz"
                if [ "${dollar}actual" = "${dollar}target" ]; then changed=${dollar}((changed+1)); else failed=${dollar}((failed+1)); fi
              else failed=${dollar}((failed+1)); fi
            done
            echo "CPU policies applied=${dollar}changed failures=${dollar}failed (stock limits, thermal preserved)"
            [ "${dollar}changed" -gt 0 ] && [ "${dollar}failed" -eq 0 ]
        """.trimIndent()
    }
}
