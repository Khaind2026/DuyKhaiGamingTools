package com.duykhai.gaming

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Read-only boot partition inspection / verified image backup.
 * No flashing, voltage modification, or PMIC changes.
 */
object PcmFirmwareTools {
    private val allowed = setOf("boot", "vendor_boot", "init_boot")
    private fun privileged(command: String): Array<String> =
        if (RootAccess.status()) CommandRunner.run(command, true)
        else arrayOf("1", "Cần Root để sao lưu firmware.")

    fun slot(): String {
        val r = privileged("getprop ro.boot.slot_suffix")
        val suffix = r.getOrElse(1) { "" }.trim()
        return if (r.getOrNull(0) == "0" && suffix in setOf("_a", "_b")) suffix else ""
    }

    private fun path(part: String): String? =
        if (part in allowed) "/dev/block/by-name/" + part + slot() else null

    fun report(): String {
        if (!RootAccess.status()) return "Cần cấp Root trước khi kiểm tra phân vùng."
        val lines = allowed.sorted().map { part ->
            val p = path(part)!!
            val r = privileged("if [ -b '$p' ]; then blockdev --getsize64 '$p'; else echo unavailable; fi")
            part + ": " + if (r.getOrNull(0) == "0")
                r.getOrElse(1) { "" }.trim() else "Không đọc được"
        }
        return "Model: " + Build.MODEL + "\nROM: " + Build.FINGERPRINT.take(120) +
            "\nSlot: " + slot().ifBlank { "Không có/không xác định" } +
            "\nKích thước phân vùng (byte):\n" + lines.joinToString("\n")
    }

    private fun digest(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (n > 0) md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }
    }

    fun inspect(file: File): String {
        if (!file.exists() || file.length() == 0L) return "Không có bản sao."
        return try {
            val magic = file.inputStream().use {
                ByteArray(8).let { bytes -> val n = it.read(bytes); String(bytes, 0, n.coerceAtLeast(0), Charsets.US_ASCII) }
            }
            val kind = when {
                magic.startsWith("ANDROID!") -> "Android Boot/Init Boot"
                magic.startsWith("VNDRBOOT") -> "Android Vendor Boot"
                else -> "Định dạng không xác định"
            }
            val hash = file.inputStream().use(::digest)
            "File: " + file.name + "\nDung lượng: " + file.length() +
                " byte\nHeader: " + kind + "\nSHA-256: " + hash
        } catch (e: Exception) { "Không kiểm tra được: " + e.message }
    }

    fun inspectUri(context: Context, uri: Uri): String {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return "Không mở được file."
            val magic = input.use { ByteArray(8).let { bytes -> val n = it.read(bytes); String(bytes, 0, n.coerceAtLeast(0), Charsets.US_ASCII) } }
            if (magic.startsWith("ANDROID!") || magic.startsWith("VNDRBOOT"))
                "Header Boot hợp lệ về mặt định dạng cơ bản. CHƯA xác nhận tương thích model/firmware."
            else "Không nhận diện được Boot image. Không dùng làm firmware để flash."
        } catch (e: Exception) { "Không đọc được: " + e.message }
    }

    fun backup(ctx: Context, part: String): Pair<File?, String> {
        val partition = path(part) ?: return null to "Chỉ chấp nhận boot/vendor_boot/init_boot."
        if (!RootAccess.status()) return null to "Cần Root; app không tự gọi SU khi chưa cấp quyền."
        val check = privileged("test -b '$partition' && blockdev --getsize64 '$partition'")
        val expected = check.getOrElse(1) { "" }.trim().toLongOrNull()
            ?: return null to "Không đọc được kích thước $part."
        if (expected !in 1L..(192L * 1024 * 1024))
            return null to "Kích thước phân vùng không hợp lệ hoặc vượt 192 MB."
        if (ctx.filesDir.usableSpace < expected + 32L * 1024 * 1024)
            return null to "Không đủ chỗ để sao lưu an toàn."
        val folder = File(ctx.filesDir, "pcm-boot-backups")
        if (!folder.exists() && !folder.mkdirs()) return null to "Không tạo được thư mục backup."
        val name = part + slot().ifEmpty { "_current" } + ".img"
        val target = File(folder, name)
        val partial = File(folder, name + ".partial")
        if (partial.exists()) partial.delete()
        val cmd = "dd if='$partition' of='" + partial.absolutePath +
            "' bs=1048576 2>/dev/null && chown " + android.os.Process.myUid() +
            " '" + partial.absolutePath + "' && chmod 600 '" + partial.absolutePath + "'"
        val copied = privileged(cmd)
        if (copied.getOrNull(0) != "0" || !partial.exists() || partial.length() != expected) {
            partial.delete()
            return null to "Sao lưu không đầy đủ, đã bỏ bản tạm."
        }
        if (target.exists() && !target.delete()) {
            partial.delete()
            return null to "Không xóa bản cũ được, giữ nguyên bản cũ."
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            return null to "Không thể hoàn tất bản sao lưu."
        }
        return target to "Đã sao lưu phân vùng " + part + ":\n" + inspect(target) +
            "\nĐây là bản gốc; chưa sửa đổi hay flash."
    }
}

/** Interprets common names only; different SoCs use different DTS layouts. */
object PcmGpuDtsReport {
    private val rx = Regex("""(?im)^\s*([\w,+.-]*(?:gpu[-_]?freq|opp-hz|gpu[-_]?volt|opp-microvolt|gpu-pwrlevel)[\w,+.-]*)\s*=\s*([^;]+);""")
    fun parse(text: String): String {
        if (text.length > 4_000_000) return "DTS quá lớn, giới hạn 4 MB."
        val found = rx.findAll(text).take(50).toList()
        if (found.isEmpty()) return "Không nhận ra thuộc tính GPU/OPP thông dụng trong DTS."
        return found.joinToString("\n") {
            it.groupValues[1] + " = " + it.groupValues[2].trim().take(160)
        } + "\nCác con số có thể là Hz, microvolt hoặc voltage corner ID; không tự diễn giải thành mV."
    }
    fun inspect(ctx: Context, uri: Uri): String {
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return "Không mở được DTS."
            val bytes = input.use { it.readBytes() }
            if (bytes.size > 4_000_000) "DTS lớn hơn 4 MB."
            else parse(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) { "Không đọc được DTS: " + e.message }
    }
}

/** Restores physical battery reporting after any external developer battery test. */
object PcmBatteryRecovery {
    private fun run(cmd: String): Array<String> = when {
        RootAccess.status() -> CommandRunner.run(cmd, true)
        CommandRunner.ready() -> CommandRunner.run(cmd, false)
        else -> arrayOf("1", "Cần Root hoặc Shizuku.")
    }
    fun inspect(): String {
        val result = run("dumpsys battery")
        return if (result.getOrNull(0) == "0") result.getOrElse(1) { "" }.take(1300)
        else "Không đọc được: " + result.getOrElse(1) { "" }.take(180)
    }
    fun reset(): String {
        val result = run("cmd battery reset")
        return if (result.getOrNull(0) == "0")
            "Đã yêu cầu Android sử dụng lại dữ liệu pin thật."
        else "Không khôi phục được: " + result.getOrElse(1) { "" }.take(180)
    }
}
