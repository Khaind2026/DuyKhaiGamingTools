package com.duykhai.gaming

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

data class FoldState(val id: Int, val name: String)

object FoldDisplayTools {
    internal fun parseStates(raw: String): List<FoldState> {
        val rx = Regex("""identifier\s*=\s*(\d+)\s*,\s*name\s*=\s*['"]?([A-Za-z0-9_-]+)""")
        return rx.findAll(raw).mapNotNull {
            val id = it.groupValues[1].toIntOrNull()
            if (id == null || id !in 0..255) null else FoldState(id, it.groupValues[2])
        }.distinctBy { it.id }.toList()
    }

    private fun shell(command: String): Array<String> {
        return when {
            RootAccess.status() -> CommandRunner.run(command, true)
            CommandRunner.ready() -> CommandRunner.run(command, false)
            else -> ShellProcess.execute(listOf("sh", "-c", command))
        }
    }

    fun getStates(): Pair<List<FoldState>, String> {
        val raw = shell("cmd device_state print-states")
        if (raw.getOrNull(0) != "0") return emptyList<FoldState>() to
            ("Không đọc được trạng thái Fold/Flip: " + raw.getOrElse(1) { "" }.take(180))
        val list = parseStates(raw.getOrElse(1) { "" })
        return list to if (list.isEmpty()) "ROM không xuất danh sách trạng thái. Không áp dụng cưỡng bức." else
            "Chỉ hoạt động nếu Samsung cho phép ghi đè device_state"
    }

    fun findCover(states: List<FoldState>): FoldState? = states.firstOrNull {
        val x = it.name.uppercase()
        x == "CLOSE" || x == "CLOSED" || x == "FOLDED" || x == "COVER" || x == "OUTER"
    }

    fun findInner(states: List<FoldState>): FoldState? = states.firstOrNull {
        val x = it.name.uppercase()
        x == "OPEN" || x == "OPENED" || x == "UNFOLDED"
    }

    fun forceState(state: FoldState): String {
        val supported = getStates().first
        if (supported.none { it.id == state.id && it.name == state.name })
            return "Không hỗ trợ trạng thái này trên ROM hiện tại"
        if (!RootAccess.status() && !CommandRunner.ready()) return "Cần Root hoặc Shizuku"
        val result = shell("cmd device_state state " + state.id)
        if (result.getOrNull(0) != "0")
            return "ROM từ chối chuyển trạng thái: " + result.getOrElse(1) { "" }.take(250)
        val check = shell("cmd device_state state")
        return "Đã yêu cầu " + state.name + ". Đọc lại: " + check.getOrElse(1) { "Không có phản hồi" }.take(220)
    }

    fun reset(): String {
        if (!RootAccess.status() && !CommandRunner.ready()) return "Cần Root hoặc Shizuku"
        val result = shell("cmd device_state state reset")
        return if (result.getOrNull(0) == "0") "Đã yêu cầu trả về trạng thái gập tự động" else
            "Reset bị từ chối: " + result.getOrElse(1) { "" }.take(190)
    }

    fun activeExternalDisplay(context: Context): Display? {
        val mgr = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val presentation = mgr.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val direct = presentation.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
        return direct ?: mgr.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && it.isValid &&
                Regex("HDMI|DP|external|dex|monitor", RegexOption.IGNORE_CASE).containsMatchIn(it.name)
        }
    }
}

object RotationTools {
    private fun privilege(command: String): Array<String> =
        when {
            RootAccess.status() -> CommandRunner.run(command, true)
            CommandRunner.ready() -> CommandRunner.run(command, false)
            else -> arrayOf("1", "Cần Root hoặc Shizuku")
        }

    fun lock(displayId: Int, degrees: Int): String {
        if (displayId !in 0..10 || degrees !in setOf(0, 90, 180, 270))
            return "Thông số xoay không hợp lệ"
        val quarter = (degrees / 90).toString()
        val cmd = "wm set-ignore-orientation-request -d " + displayId +
            " true && wm user-rotation -d " + displayId + " lock " + quarter
        val r = privilege(cmd)
        return if (r.getOrNull(0) == "0") "Đã yêu cầu xoay " + degrees + "° cho display " +
            displayId else "Không thể xoay: " + r.getOrElse(1) { "" }.take(240)
    }

    fun release(displayId: Int): String {
        if (displayId !in 0..10) return "Display ID không hợp lệ"
        val r = privilege("wm user-rotation -d " + displayId +
            " free; wm set-ignore-orientation-request -d " + displayId + " false")
        return if (r.getOrNull(0) == "0") "Đã trả lại xoay tự động cho display " + displayId
            else "Không khôi phục được: " + r.getOrElse(1) { "" }.take(240)
    }
}
