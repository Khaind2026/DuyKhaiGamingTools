package com.duykhai.gaming

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * PCM Gaming firmware diagnostics.
 * Never writes an Android partition or patches GPU voltage tables.
 */
class PcmFirmwareLabActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ink = Color.rgb(9, 15, 32)
    private val sky = Color.rgb(93, 228, 255)
    private val red = Color.rgb(255, 76, 97)
    private val green = Color.rgb(101, 241, 167)
    private val white = Color.WHITE
    private val muted = Color.rgb(175, 194, 213)
    private var report: TextView? = null
    private var backup: File? = null
    private var isBusy = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun text(value: String, sp: Float = 13f, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(12), dp(13), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.rgb(19, 31, 53))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(2), sky)
        }
    }
    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(text(label, 13f, white, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(45)
            background = GradientDrawable().apply {
                setColor(Color.rgb(43, 49, 75))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), red)
            }
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private fun section(parent: LinearLayout, view: LinearLayout) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(22), dp(15), dp(22))
            setBackgroundColor(ink)
        }
        scroll.addView(root)
        setContentView(scroll)
        val header = card()
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hero.addView(CrewFigureView(this, red, "PCM"),
            LinearLayout.LayoutParams(dp(65), dp(80)))
        hero.addView(text("PCM GAMING\nGPU / BOOT LAB", 20f, white, true))
        header.addView(hero)
        header.addView(text("Duy Khải • 0862134811", 12f, sky))
        header.addView(text("Môi trường kiểm tra firmware: sao lưu, nhận diện GPU DTS, kiểm tra checksum và khôi phục dữ liệu pin thật.", 12f, muted))
        button(header, "← VỀ PCM GAMING") { finish() }
        section(root, header)

        val status = card()
        status.addView(text("TRẠNG THÁI THỰC TẾ", 16f, sky, true))
        report = text("Chưa có kết quả. Cấp Root để đọc phân vùng.", 12f, white)
        status.addView(report)
        button(status, "CẤP QUYỀN ROOT") {
            task { if (RootAccess.request(this)) "Root đã được cấp."
                else "Root chưa sẵn sàng." }
        }
        button(status, "PHÁT HIỆN BOOT / VENDOR_BOOT / INIT_BOOT") {
            task { PcmFirmwareTools.report() }
        }
        section(root, status)

        val archive = card()
        archive.addView(text("SAO LƯU PHÂN VÙNG GỐC", 16f, green, true))
        archive.addView(text("Chỉ đọc phân vùng hiện tại; không ghi đè boot. Từ chối khi không xác định được kích thước hoặc không đủ bộ nhớ.", 12f, muted))
        for (partition in listOf("boot", "vendor_boot", "init_boot")) {
            button(archive, "SAO LƯU " + partition.uppercase()) {
                AlertDialog.Builder(this)
                    .setTitle("Sao lưu " + partition + "?")
                    .setMessage("Chỉ tạo ảnh dự phòng, không flash. Có thể tốn bộ nhớ nội bộ.")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Sao lưu") { _, _ ->
                        task {
                            val (file, info) = PcmFirmwareTools.backup(this, partition)
                            backup = file
                            info
                        }
                    }.show()
            }
        }
        button(archive, "XUẤT FILE BACKUP + SHA-256") {
            val file = backup
            if (file == null || !file.exists()) {
                show("Chưa có bản sao được kiểm tra.")
            } else {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, file.name)
                }, 2002)
            }
        }
        section(root, archive)

        val parser = card()
        parser.addView(text("GPU DTB / DTS INSPECTOR", 16f, sky, true))
        parser.addView(text("Mở file DTS để đọc các thuộc tính xung và mức điện áp được công bố. Khác SoC có cách mã hóa khác nhau, vì vậy không tự động thay đổi voltage corner.", 12f, muted))
        button(parser, "MỞ FILE DTS (CHỈ ĐỌC)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, 2003)
        }
        button(parser, "KIỂM TRA HEADER BOOT.IMG") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, 2004)
        }
        section(root, parser)

        val battery = card()
        battery.addView(text("PIN ANDROID • CHẨN ĐOÁN / KHÔI PHỤC", 16f, green, true))
        battery.addView(text("Mức pin hiển thị giả không tạo thêm dung lượng hay dòng điện. Khôi phục để Android tiếp tục đọc cảm biến pin thực tế.", 12f, muted))
        button(battery, "ĐỌC BATTERYSERVICE") { task { PcmBatteryRecovery.inspect() } }
        button(battery, "KHÔI PHỤC PIN THẬT (RESET)") { task { PcmBatteryRecovery.reset() } }
        section(root, battery)

        val limits = card()
        limits.addView(text("GIỚI HẠN AN TOÀN", 15f, red, true))
        limits.addView(text("Chưa có trình biên dịch DTB, chỉnh bảng điện áp hoặc flash boot tích hợp trong PCM. Không giả báo chức năng đã hoạt động. Cần định dạng DTB đúng SoC và bản sao firmware kiểm chứng trước khi hỗ trợ thay đổi.", 12f, muted))
        section(root, limits)
    }

    private fun show(message: String) {
        runOnUiThread { report?.text = message }
    }
    private fun task(run: () -> String) {
        if (isBusy) return
        isBusy = true
        show("Đang kiểm tra…")
        worker.execute {
            val out = try { run() } catch (e: Exception) { "Lỗi: " + (e.message ?: "unknown") }
            runOnUiThread {
                report?.text = out
                isBusy = false
            }
        }
    }

    @Deprecated("Compatibility with Android Activity result on API 28+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            2002 -> {
                val file = backup ?: return
                task {
                    val out = contentResolver.openOutputStream(uri) ?: return@task "Không xuất được."
                    out.use { output -> file.inputStream().use { it.copyTo(output) } }
                    "Đã xuất bản sao.\n" + PcmFirmwareTools.inspect(file)
                }
            }
            2003 -> task { PcmGpuDtsReport.inspect(this, uri) }
            2004 -> task { PcmFirmwareTools.inspectUri(this, uri) }
        }
    }
    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
