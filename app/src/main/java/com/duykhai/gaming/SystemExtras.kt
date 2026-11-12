package com.duykhai.gaming

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** Root is required for CPU; display commands can also use the Shizuku service. */
object RootAccess {
    fun status(): Boolean = RootSession.status()
    fun request(): Boolean = RootSession.request()
    fun invalidate() = RootSession.invalidate()
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
    /**
     * The selected profile writes min+max CPU frequency caps (within stock limits)
     * and reads both back. The kernel governor and all thermal protections remain.
     * A Samsung kernel can deny sysfs writes: report that instead of claiming success.
     */
    fun command(mode: Int): String {
        require(mode in 1..3)
        val ceiling = when (mode) { 1 -> 70; 2 -> 90; else -> 100 }
        val floor = when (mode) { 1 -> 0; 2 -> 30; else -> 45 }
        val d = 36.toChar()
        return """
            changed=0; failed=0; blocked=0
            for p in /sys/devices/system/cpu/cpufreq/policy*; do
              [ -r "${d}p/cpuinfo_max_freq" ] || continue
              [ -r "${d}p/cpuinfo_min_freq" ] || continue
              stock=${d}(cat "${d}p/cpuinfo_max_freq")
              lower=${d}(cat "${d}p/cpuinfo_min_freq")
              case "${d}stock:${d}lower" in *[!0-9:]*|:*|*:) failed=${d}((failed+1)); continue;; esac
              top=${d}((stock*$ceiling/100))
              bottom=${d}((stock*$floor/100))
              [ "${d}bottom" -ge "${d}lower" ] || bottom=${d}lower
              [ "${d}bottom" -le "${d}top" ] || bottom=${d}top
              if [ -r "${d}p/scaling_available_frequencies" ]; then
                maxBest=0; minBest=0
                for f in ${d}(cat "${d}p/scaling_available_frequencies"); do
                  case "${d}f" in *[!0-9]*|'') continue;; esac
                  if [ "${d}f" -le "${d}top" ] && [ "${d}f" -gt "${d}maxBest" ]; then maxBest=${d}f; fi
                  if [ "${d}f" -ge "${d}bottom" ] && { [ "${d}minBest" -eq 0 ] || [ "${d}f" -lt "${d}minBest" ]; }; then minBest=${d}f; fi
                done
                [ "${d}maxBest" -gt 0 ] && top=${d}maxBest
                [ "${d}minBest" -gt 0 ] && bottom=${d}minBest
              fi
              [ "${d}bottom" -le "${d}top" ] || bottom=${d}top
              if [ ! -w "${d}p/scaling_max_freq" ] || [ ! -w "${d}p/scaling_min_freq" ]; then
                echo "${d}{p##*/}: kernel denies writes"; blocked=${d}((blocked+1)); continue
              fi
              echo "${d}lower" > "${d}p/scaling_min_freq" 2>/dev/null
              echo "${d}top" > "${d}p/scaling_max_freq" 2>/dev/null
              echo "${d}bottom" > "${d}p/scaling_min_freq" 2>/dev/null
              actualMax=${d}(cat "${d}p/scaling_max_freq")
              actualMin=${d}(cat "${d}p/scaling_min_freq")
              echo "${d}{p##*/}: actual=${d}actualMax min=${d}actualMin target=${d}top / ${d}bottom kHz"
              if [ "${d}actualMax" = "${d}top" ] && [ "${d}actualMin" = "${d}bottom" ]; then
                changed=${d}((changed+1))
              else failed=${d}((failed+1)); fi
            done
            echo "CPU verified=${d}changed denied=${d}blocked failed=${d}failed (thermal policy enabled)"
            [ "${d}changed" -gt 0 ] && [ "${d}failed" -eq 0 ] && [ "${d}blocked" -eq 0 ]
        """.trimIndent()
    }
}
