package com.duykhai.gaming

import java.util.Locale

/**
 * Android SurfaceFlinger supports a global saturation Binder call (1022) on AOSP.
 * OEM implementations may reject/ignore it; returning success means command
 * accepted by Binder, NOT a verified visual change on Samsung One UI.
 *
 * It modifies color on the default compositor, not HDMI/DeX independently.
 */
object SystemColorControl {
    fun saturationFactor(value: Int): Float = value.coerceIn(0, 200) / 100f

    fun acceptedBinderReply(output: String): Boolean {
        val parcel = Regex("""(?i)Parcel\(\s*([0-9a-f]{8})""").find(output)
        return parcel != null && parcel.groupValues[1].equals("00000000", true)
    }

    fun applySaturation(value: Int): String {
        if (!RootAccess.status() && !CommandRunner.ready()) {
            return "Chưa có quyền Root/Shizuku. Cấp quyền rồi áp dụng lại."
        }
        if (value !in 0..200) return "Giá trị bão hòa phải nằm trong 0–200%."
        val factor = String.format(Locale.US, "%.2f", saturationFactor(value))
        val privileged = RootAccess.status()
        val response = CommandRunner.run("service call SurfaceFlinger 1022 f " + factor, privileged)
        val body = response.getOrElse(1) { "" }
        if (response.getOrNull(0) != "0" || !acceptedBinderReply(body)) {
            return "SurfaceFlinger không xác nhận lệnh (" + response.getOrElse(0) { "?" } +
                "). ROM Samsung có thể không hỗ trợ hoặc chặn quyền. " + body.take(300)
        }
        return "SurfaceFlinger đã nhận yêu cầu bão hòa " + value +
            "%. Kiểm tra màu thực tế trên màn hình; Samsung có thể bỏ qua hoặc ghi đè lệnh này."
    }

    fun reset(): String = applySaturation(100)
}
