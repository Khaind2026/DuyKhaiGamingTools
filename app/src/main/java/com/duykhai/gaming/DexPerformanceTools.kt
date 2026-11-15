package com.duykhai.gaming

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import java.util.Locale
import kotlin.math.abs

/**
 * External display control based on actual Android Display.Mode data.
 * Mode selection is a REQUEST to DisplayManager/HWC, not an HDMI timing hack.
 * Never interprets the success of a shell command as proof of physical 120 Hz.
 */
object DexDisplayControl {
    data class Output(
        val displayId: Int,
        val name: String,
        val activeWidth: Int,
        val activeHeight: Int,
        val activeHz: Float,
        val supported: List<OutputMode>
    )
    data class OutputMode(val width: Int, val height: Int, val hz: Float) {
        fun label(): String = width.toString() + "×" + height + " @ " +
            String.format(Locale.US, "%.1f", hz) + "Hz"
    }

    private fun privileged(command: String): Array<String> = when {
        RootAccess.status() -> CommandRunner.run(command, true)
        CommandRunner.ready() -> CommandRunner.run(command, false)
        else -> arrayOf("1", "Cần cấp quyền Root hoặc kết nối Shizuku")
    }

    fun snapshot(context: Context): Output? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = FoldDisplayTools.activeExternalDisplay(context) ?: return null
        if (!display.isValid) return null
        val active = display.mode
        return Output(display.displayId, display.name,
            active.physicalWidth, active.physicalHeight, active.refreshRate,
            display.supportedModes.map {
                OutputMode(it.physicalWidth, it.physicalHeight, it.refreshRate)
            }.distinctBy { Triple(it.width, it.height, it.hz) }
                .sortedWith(compareByDescending<OutputMode> { it.width.toLong() * it.height }
                    .thenByDescending { it.hz }))
    }

    fun describe(context: Context): String {
        val output = snapshot(context)
            ?: return "Không tìm thấy màn hình HDMI/DeX. Cắm màn ngoài và kiểm tra hub/cáp."
        val list = output.supported.take(30).joinToString("\n") { "• " + it.label() }
        return "HDMI: " + output.name + " (ID " + output.displayId + ")" +
            "\nMODE ĐANG HOẠT ĐỘNG: " + output.activeWidth + "×" +
            output.activeHeight + " @ " + String.format(Locale.US, "%.1f", output.activeHz) +
            "Hz\nMODE ROM CÔNG BỐ:\n" + list +
            "\nChỉ kết luận 120Hz khi MODE ĐANG HOẠT ĐỘNG báo 120Hz. " +
            "Không lấy FPS game hoặc giá trị Settings làm Hz HDMI."
    }

    internal fun chooseMode(output: Output, hz: Int): OutputMode? {
        if (hz !in setOf(60, 90, 120, 144, 165, 180, 200)) return null
        val candidates = output.supported.filter { abs(it.hz - hz) < 0.6f }
        return candidates.firstOrNull {
            it.width == output.activeWidth && it.height == output.activeHeight
        } ?: candidates.firstOrNull()
    }

    fun request(context: Context, hz: Int): String {
        val before = snapshot(context) ?: return "Không thấy màn HDMI/DeX."
        val target = chooseMode(before, hz) ?: return "HDMI không công bố mode " + hz +
            "Hz. Không áp dụng cấu hình vượt khả năng nhận diện."
        val help = privileged("cmd display help")
        if (help.getOrNull(0) != "0" ||
            !help.getOrElse(1) { "" }.contains("set-user-preferred-display-mode")) {
            return "ROM không hỗ trợ lệnh đặt mode theo display ID, hoặc chưa có quyền."
        }
        val command = "cmd display set-user-preferred-display-mode " +
            target.width + " " + target.height + " " +
            String.format(Locale.US, "%.2f", target.hz) + " " + before.displayId
        val result = privileged(command)
        if (result.getOrNull(0) != "0") return "ROM từ chối yêu cầu: " +
            result.getOrElse(1) { "" }.take(260)
        try { Thread.sleep(450) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val after = snapshot(context) ?: return "Đã gửi lệnh nhưng HDMI đã ngắt; không xác minh được."
        val ok = after.displayId == before.displayId &&
            abs(after.activeHz - target.hz) < 0.6f &&
            after.activeWidth == target.width && after.activeHeight == target.height
        return if (ok) "ĐÃ XÁC NHẬN MODE: " + target.label() +
            "\nLưu ý: FPS game có thể thấp hơn Hz màn ngoài."
        else "ĐÃ GỬI LỆNH: " + target.label() +
            "\nChưa xác nhận mode vật lý. Hiện tại: " +
            after.activeWidth + "×" + after.activeHeight + " @ " +
            String.format(Locale.US, "%.1f", after.activeHz) +
            "Hz. Samsung HWC có thể bỏ qua yêu cầu."
    }

    fun restore(context: Context): String {
        val display = snapshot(context) ?: return "Không có HDMI/DeX để khôi phục."
        val help = privileged("cmd display help")
        if (help.getOrNull(0) != "0" ||
            !help.getOrElse(1) { "" }.contains("clear-user-preferred-display-mode")) {
            return "ROM không hỗ trợ khôi phục mode theo display ID hoặc chưa cấp quyền."
        }
        val response = privileged("cmd display clear-user-preferred-display-mode " + display.displayId)
        return if (response.getOrNull(0) == "0")
            "Đã bỏ mode ưu tiên riêng cho HDMI ID " + display.displayId +
                ". Android/Samsung tự chọn lại mode."
        else "Không thể khôi phục: " + response.getOrElse(1) { "" }.take(200)
    }
}

/** Does not modify thermal policy, voltage, boot partitions, or GPU clock tables. */
object GamingPerformanceControl {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private fun execute(cmd: String): Array<String> = when {
        RootAccess.status() -> CommandRunner.run(cmd, true)
        CommandRunner.ready() -> CommandRunner.run(cmd, false)
        else -> arrayOf("1", "Cần Root hoặc Shizuku")
    }

    fun gpuDiagnostics(): String {
        val script = "for f in " +
            "/sys/class/kgsl/kgsl-3d0/gpuclk " +
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq " +
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq " +
            "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq " +
            "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies " +
            "/sys/class/kgsl/kgsl-3d0/gpubusy " +
            "/sys/class/devfreq/*gpu*/cur_freq " +
            "/sys/class/devfreq/*gpu*/available_frequencies " +
            "/sys/class/devfreq/*3d*/cur_freq " +
            "/sys/class/devfreq/*mali*/cur_freq; do " +
            "if [ -r \"\$f\" ]; then printf '%s: ' \"\$f\"; " +
            "head -c 160 \"\$f\" 2>/dev/null; echo; fi; done"
        val read = execute(script)
        if (read.getOrNull(0) != "0") return "Không đọc được GPU: " +
            read.getOrElse(1) { "" }.take(170)
        val lines = read.getOrElse(1) { "" }.trim()
        return if (lines.isBlank()) "Không tìm thấy node GPU có thể đọc. " +
            "Samsung/ROM có thể ẩn devfreq. Không hiển thị xung giả."
        else lines.take(2000) +
            "\nĐây là xung/giới hạn kernel báo cáo; không phải bằng chứng GPU luôn chạy tối đa."
    }

    fun setGameMode(packageName: String, mode: String): String {
        val pkg = packageName.trim()
        if (pkg.length > 200 || !packagePattern.matches(pkg)) return "Tên package game không hợp lệ."
        if (mode !in setOf("standard", "performance", "battery")) return "Game Mode không hợp lệ."
        val help = execute("cmd game help")
        if (help.getOrNull(0) != "0" ||
            !help.getOrElse(1) { "" }.contains("mode")) {
            return "ROM không hỗ trợ Game Mode shell hoặc chưa cấp quyền."
        }
        val result = execute("cmd game mode " + mode + " " + pkg)
        if (result.getOrNull(0) != "0") return "Không áp dụng được: " +
            result.getOrElse(1) { "" }.take(250)
        val check = execute("cmd game list-modes " + pkg)
        return "Đã gửi yêu cầu Game Mode " + mode + " cho " + pkg +
            if (check.getOrNull(0) == "0") "\nROM báo: " + check.getOrElse(1) { "" }.take(280)
            else "\nROM không cung cấp xác nhận; kiểm tra trong game."
    }
}
