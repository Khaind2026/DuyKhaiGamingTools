package com.duykhai.gaming

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** Root is the only execution backend for display/CPU changes in v0.2. */
object RootAccess {
    fun status(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                val result = process.inputStream.bufferedReader().use { it.readText().trim() }
                process.exitValue() == 0 && result.lines().lastOrNull()?.trim() == "0"
            }
        } catch (_: Exception) { false }
    }
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
        "Delta Force",
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
        return """
            changed=0
            failed=0
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
              [ -d "\$p" ] || continue
              [ -r "\$p/cpuinfo_max_freq" ] || continue
              [ -r "\$p/scaling_min_freq" ] || continue
              [ -w "\$p/scaling_max_freq" ] || { failed=\$((failed+1)); continue; }
              stock=\$(cat "\$p/cpuinfo_max_freq")
              minimum=\$(cat "\$p/scaling_min_freq")
              case "\$stock:\$minimum" in *[!0-9:]*|:*|*:) failed=\$((failed+1)); continue;; esac
              target=\$((stock*$percent/100))
              [ "\$target" -ge "\$minimum" ] || target=\$minimum
              if [ -r "\$p/scaling_available_frequencies" ]; then
                best=0
                for f in \$(cat "\$p/scaling_available_frequencies"); do
                  case "\$f" in *[!0-9]*|'') continue;; esac
                  if [ "\$f" -le "\$target" ] && [ "\$f" -ge "\$minimum" ] && [ "\$f" -gt "\$best" ]; then best=\$f; fi
                done
                [ "\$best" -eq 0 ] || target=\$best
              fi
              if echo "\$target" > "\$p/scaling_max_freq" 2>/dev/null; then changed=\$((changed+1)); else failed=\$((failed+1)); fi
            done
            echo "CPU policies applied=\$changed failures=\$failed (stock limits, thermal preserved)"
            [ "\$changed" -gt 0 ] && [ "\$failed" -eq 0 ]
        """.trimIndent()
    }
}
