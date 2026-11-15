package com.duykhai.gaming

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import java.util.Locale

/**
 * Bounded stock-performance profile. No voltage, PMIC, thermal or boot modification.
 * A frequency cap is not an instruction to hold the chip at that frequency continuously.
 */
object StockGpuBoost {
    private const val PREFS = "duykhaidex_gpu_peak_restore"
    private const val RESTORE = "original_gpu_max"

    private val allowedPath = Regex(
        "^/sys/class/(?:kgsl/kgsl-3d0/devfreq|devfreq/[A-Za-z0-9_.-]+)$"
    )

    private fun isAllowed(path: String): Boolean = allowedPath.matches(path) &&
        (path == "/sys/class/kgsl/kgsl-3d0/devfreq" ||
            path.substringAfterLast('/').contains("gpu", true) ||
            path.substringAfterLast('/').contains("mali", true) ||
            path.substringAfterLast('/').contains("3d", true))

    /** All writes require an already-authorized persistent root session. */
    private fun run(command: String): Array<String> =
        if (RootAccess.status()) CommandRunner.run(command, true)
        else arrayOf("1", "Cần cấp quyền Root bằng nút Root trước")

    private val applyCommand = """
        changed=0
        for d in /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*gpu* /sys/class/devfreq/*mali* /sys/class/devfreq/*3d*; do
          [ -d "@d" ] || continue
          [ -r "@d/max_freq" ] && [ -w "@d/max_freq" ] && [ -r "@d/available_frequencies" ] || continue
          max=0
          for f in @(cat "@d/available_frequencies" 2>/dev/null); do
            case "@f" in ""|*[!0-9]*) continue;; esac
            [ "@f" -gt 0 ] && [ "@f" -gt "@max" ] && max=@f
          done
          [ "@max" -gt 0 ] || continue
          original=@(cat "@d/max_freq" 2>/dev/null)
          case "@original" in ""|*[!0-9]*) continue;; esac
          [ "@original" -gt 0 ] || continue
          echo "@max" > "@d/max_freq" 2>/dev/null || continue
          actual=@(cat "@d/max_freq" 2>/dev/null)
          printf 'GPU_APPLIED|%s|%s|%s|%s\n' "@d" "@original" "@max" "@actual"
          changed=@((changed+1))
        done
        [ "@changed" -gt 0 ] || { echo "GPU_UNSUPPORTED"; exit 3; }
    """.trimIndent().replace('@', '$')

    private fun safeBatteryTemperature(ctx: Context): Float? {
        val state = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val raw = state.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return raw.takeIf { it in 1..1500 }?.div(10f)
    }

    fun enable(ctx: Context): String {
        if (!RootAccess.status()) return "Cần cấp Root trước khi thay đổi GPU."
        val temp = safeBatteryTemperature(ctx)
        if (temp != null && temp >= 42f)
            return "Pin đang " + temp + "°C. Không bật cấu hình GPU khi pin quá nóng."
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getString(RESTORE, null).isNullOrBlank())
            return "GPU đã lưu giới hạn trước đó. Khôi phục trước khi áp dụng lại."
        val result = run(applyCommand)
        val lines = result.getOrElse(1) { "" }.lines()
        val restores = mutableListOf<String>()
        val descriptions = mutableListOf<String>()
        for (line in lines) {
            val parts = line.split('|')
            if (parts.size != 5 || parts[0] != "GPU_APPLIED") continue
            val path = parts[1]
            val original = parts[2].toLongOrNull()
            val peak = parts[3].toLongOrNull()
            val actual = parts[4].toLongOrNull()
            if (!isAllowed(path) || original == null || peak == null || actual == null ||
                original !in 1..999_999_999_999L || peak !in 1..999_999_999_999L)
                continue
            restores.add(path + "|" + original)
            descriptions.add(path.substringAfterLast('/') + ": " +
                "trần " + original + " → " + actual + " (yêu cầu " + peak + ")")
        }
        if (restores.isNotEmpty()) {
            prefs.edit().putString(RESTORE, restores.distinct().joinToString("\n")).apply()
        }
        return if (result.getOrNull(0) == "0" && restores.isNotEmpty()) {
            "Đã yêu cầu trần GPU cao nhất trong bảng gốc:\n" + descriptions.joinToString("\n") +
                "\nGovernor và thermal vẫn hoạt động. Xung tức thời có thể thấp hơn."
        } else {
            "Không thể xác minh thay đổi GPU. " +
                result.getOrElse(1) { "" }.take(350) +
                if (restores.isNotEmpty()) "\nĐã lưu bản sao giới hạn GPU để khôi phục." else ""
        }
    }

    fun restore(ctx: Context): String {
        if (!RootAccess.status()) return "Cần cấp Root trước khi khôi phục."
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = prefs.getString(RESTORE, null)?.lines()
            ?.filter { it.isNotBlank() } ?: return "Chưa có giới hạn GPU cũ được lưu."
        val verified = entries.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 2) return@mapNotNull null
            val path = parts[0]
            val original = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (!isAllowed(path) || original !in 1..999_999_999_999L) null
            else path to original
        }
        if (verified.size != entries.size || verified.isEmpty())
            return "Bản sao GPU không hợp lệ. Không thực hiện lệnh khôi phục."
        val cmd = verified.joinToString("\n") { (path, original) ->
            "echo $original > '$path/max_freq' 2>/dev/null && " +
                "[ \"\$(cat '$path/max_freq')\" = '$original' ] || exit 1"
        }
        val result = run(cmd)
        return if (result.getOrNull(0) == "0") {
            prefs.edit().remove(RESTORE).apply()
            "Đã xác minh và khôi phục " + verified.size + " giới hạn GPU đã lưu."
        } else "Không khôi phục được toàn bộ GPU. Giữ bản sao để thử lại: " +
            result.getOrElse(1) { "" }.take(220)
    }

    fun inspect(): String {
        if (!RootAccess.status()) return "Cần cấp Root để đọc đầy đủ giới hạn GPU."
        return GamingPerformanceControl.gpuDiagnostics()
    }
}

object PowerHealthReport {
    /** Battery parameters are NOT direct measurements of PMIC voltage/current at CPU/GPU. */
    fun snapshot(ctx: Context): String {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val manager = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentUa = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        fun raw(value: Int) = if (value > 0) value.toString() else "không đọc được"
        val voltage = if (voltageMv > 0)
            String.format(Locale.US, "%.3f V", voltageMv / 1000f) else "không đọc được"
        val temp = if (tempTenths > 0)
            String.format(Locale.US, "%.1f°C", tempTenths / 10f) else "không đọc được"
        val current = if (currentUa != Int.MIN_VALUE)
            String.format(Locale.US, "%+.0f mA", currentUa / 1000f) else "không đọc được"
        return "Pin: " + raw(level) + "% • " + voltage +
            "\nNhiệt độ pin: " + temp +
            "\nDòng pin (ước tính của hệ thống): " + current +
            "\nTiết kiệm pin: " + if (power.isPowerSaveMode) "ĐANG BẬT" else "Đang tắt" +
            "\nCác số liệu này KHÔNG phải điện áp/dòng trực tiếp vào CPU/GPU." +
            "\nKhông thể tăng công suất PMIC hoặc điện áp chip bằng ứng dụng."
    }
}
