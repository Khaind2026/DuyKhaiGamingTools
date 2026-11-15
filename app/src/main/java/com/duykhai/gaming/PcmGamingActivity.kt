package com.duykhai.gaming

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import rikka.sui.Sui

/** Original, native Android starship/crewmate UI for the PCM Gaming rebuild. */
class PcmGamingActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var active = false
    private var rootView: FrameLayout? = null
    private var cpuLabel: TextView? = null
    private var fpsLabel: TextView? = null
    private var hdmiLabel: TextView? = null
    private var rootLabel: TextView? = null
    private var shizukuLabel: TextView? = null
    private var hudButton: TextView? = null

    private val white = Color.WHITE
    private val muted = Color.rgb(165, 186, 211)
    private val sky = Color.rgb(93, 228, 255)
    private val orange = Color.rgb(255, 189, 75)
    private val red = Color.rgb(255, 76, 97)
    private val green = Color.rgb(101, 241, 167)
    private val purple = Color.rgb(178, 141, 255)
    private val ink = Color.rgb(8, 15, 33)
    private val panelColor = Color.rgb(18, 30, 53)

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun line() = View(this).apply {
        setBackgroundColor(Color.argb(95, 97, 128, 162))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        }
    }
    private fun title(s: String, sp: Float = 18f, color: Int = white): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
    private fun caption(s: String, sp: Float = 12f, color: Int = muted): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
        }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }
    private fun gap(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }
    private fun shape(fill: Int, edge: Int, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(2), edge)
        }
    private fun card(color: Int = sky) = column().apply {
        setPadding(dp(15), dp(13), dp(15), dp(15))
        background = shape(panelColor, color)
        elevation = dp(4).toFloat()
    }
    private fun button(text: String, color: Int = red, click: () -> Unit): TextView =
        title(text, 13f).apply {
            gravity = Gravity.CENTER
            minHeight = dp(46)
            background = shape(Color.rgb(39, 51, 77), color, 12)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { click() }
        }
    private fun addCard(into: LinearLayout, view: View) {
        into.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(13)
        })
    }
    private fun statusPill(label: String, color: Int): TextView =
        title(label, 11f, color).apply {
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = shape(Color.rgb(10, 22, 40), color, 30)
            gravity = Gravity.CENTER
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        try { Sui.init(packageName); CommandRunner.connect(this) } catch (_: Throwable) {}
        renderHome()
    }

    override fun onResume() {
        super.onResume()
        active = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    override fun onPause() {
        active = false
        handler.removeCallbacks(tick)
        super.onPause()
    }
    override fun onDestroy() {
        active = false
        handler.removeCallbacks(tick)
        worker.shutdownNow()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            rootLabel?.text = if (RootAccess.status()) "ROOT • ONLINE" else "ROOT • CHƯA CẤP"
            shizukuLabel?.text = if (CommandRunner.ready()) "SHIZUKU • ONLINE"
                else "SHIZUKU • CHƯA KẾT NỐI"
            hudButton?.text = if (MonitorOverlayService.active) "HUD ĐANG CHẠY"
                else "MỞ FPS HUD"
            if (busy.compareAndSet(false, true)) {
                worker.execute {
                    val sample = try { PerformanceMonitor.sample("", RootAccess.status(), this@PcmGamingActivity) }
                        catch (_: Exception) { null }
                    val external = try { DexDisplayControl.snapshot(this@PcmGamingActivity) }
                        catch (_: Exception) { null }
                    runOnUiThread {
                        if (active) {
                            cpuLabel?.text = PerformanceMonitor.temperatureLabel(sample?.temperatureC)
                            fpsLabel?.text = sample?.cpuClocks?.maxOfOrNull { it.second }?.let {
                                String.format(Locale.US, "%.2f GHz", it / 1000f)
                            } ?: "—"
                            hdmiLabel?.text = external?.let {
                                String.format(Locale.US, "%.0f Hz", it.activeHz)
                            } ?: "OFFLINE"
                        }
                        busy.set(false)
                    }
                }
            }
            handler.postDelayed(this, 3000L)
        }
    }

    private fun screen(): LinearLayout {
        val container = FrameLayout(this)
        rootView = container
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val outer = column().apply {
            setPadding(dp(16), dp(20), dp(16), dp(26))
            background = SpaceBackdropDrawable()
        }
        scroll.addView(outer)
        container.addView(scroll)
        setContentView(container)
        return outer
    }

    private fun renderHome() {
        val body = screen()
        val banner = card(red)
        val top = row()
        top.addView(CrewFigureView(this, red, "PCM", crowned = true),
            LinearLayout.LayoutParams(dp(72), dp(99)))
        val brand = column().apply {
            addView(title("PCM GAMING", 28f, white))
            addView(title("CREW CONTROL // v1.0", 12f, sky))
            addView(caption("BY DUY KHẢI  •  0862134811", 11f, white))
        }
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        banner.addView(top)
        banner.addView(CrewHeroView(this),
            LinearLayout.LayoutParams(-1, dp(130)))
        banner.addView(caption("DE X • PERFORMANCE • MONITORING • ROOT", 11f, muted))
        addCard(body, banner)

        val status = card(sky)
        status.addView(title("PHÒNG ĐIỀU KHIỂN TÀU", 14f, sky))
        status.addView(gap(8))
        val numbers = row()
        fun metric(name: String): TextView {
            val cell = column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(6), dp(3), dp(6))
            }
            val value = title("—", 18f, white).apply { gravity = Gravity.CENTER }
            cell.addView(value)
            cell.addView(caption(name, 10f).apply { gravity = Gravity.CENTER })
            numbers.addView(cell, LinearLayout.LayoutParams(0, dp(57), 1f))
            return value
        }
        cpuLabel = metric("CPU NHIỆT")
        fpsLabel = metric("XUNG CPU")
        hdmiLabel = metric("HDMI MODE")
        status.addView(numbers)
        status.addView(line())
        val rights = row()
        rootLabel = statusPill("ROOT • CHƯA CẤP", orange)
        shizukuLabel = statusPill("SHIZUKU • CHƯA KẾT NỐI", sky)
        rights.addView(rootLabel, LinearLayout.LayoutParams(0, -2, 1f).apply {
            rightMargin = dp(5)
        })
        rights.addView(shizukuLabel, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(5)
        })
        status.addView(rights)
        status.addView(gap(9))
        val access = row()
        access.addView(button("CẤP ROOT", red) { requestRoot() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
        access.addView(button("SHIZUKU", sky) { requestShizuku() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        status.addView(access)
        addCard(body, status)

        val quick = card(green)
        quick.addView(title("NHIỆM VỤ NHANH", 15f, green))
        quick.addView(gap(9))
        quick.addView(button("KÍCH HOẠT MAX STOCK CPU / GPU", red) { openTool("gpu") })
        quick.addView(gap(7))
        val quickRow = row()
        hudButton = button("MỞ FPS HUD", sky) { openTool("hud") }
        quickRow.addView(hudButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
        quickRow.addView(button("DEX 120HZ", orange) { openTool("dex") },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        quick.addView(quickRow)
        addCard(body, quick)

        section(body, "PHÒNG DEX • MÀN HÌNH", sky, listOf(
            Tool("DeX / HDMI", "Mode thật • 60–200Hz • DPI", "dex", sky),
            Tool("KHÓA HZ", "Màn trong • tần số quét", "hz", orange),
            Tool("ĐỘ PHÂN GIẢI", "Kích thước • DPI • tỷ lệ", "display", purple),
            Tool("XOAY MÀN", "0° / 90° / 180° / 270°", "rotate", green),
            Tool("FOLD / FLIP", "Màn trong • màn phụ", "fold", red),
            Tool("MULTIWINDOW", "Cài đặt màn hình Samsung", "multiwindow", sky)
        ))
        section(body, "PHÒNG MÁY • HIỆU NĂNG", red, listOf(
            Tool("CPU BOOST", "ECO • Balanced • Max Stock", "cpu", red),
            Tool("GPU BOOST", "MAX Stock • Devfreq • Game", "gpu", purple),
            Tool("TỐI ƯU GAME", "FPS • preset game", "game", orange),
            Tool("GIÁM SÁT FPS", "HUD • CPU • HDMI", "hud", sky),
            Tool("MÀU SẮC", "RGB • Saturation", "color", green),
            Tool("PIN / NGUỒN", "Volt • Ampere • nhiệt độ", "battery", orange)
        ))
        section(body, "THIẾT BỊ • HỆ THỐNG", purple, listOf(
            Tool("CÔNG CỤ", "Tiện ích Android", "other", purple),
            Tool("AUTO BOOT", "Lưu cấu hình & tự chạy", "settings", green),
            Tool("THÔNG TIN", "PCM Gaming • tác giả", "about", sky)
        ))
        val contact = card(orange)
        contact.addView(title("SUPPORT • DUY KHẢI", 18f, white))
        contact.addView(caption("Điện thoại: 0862134811"))
        contact.addView(caption("Zalo: 0862134811"))
        contact.addView(gap(9))
        contact.addView(button("MỞ ZALO 0862134811", orange) { openZalo() })
        addCard(body, contact)
    }

    private data class Tool(val name: String, val detail: String, val page: String, val color: Int)
    private fun section(parent: LinearLayout, heading: String, edge: Int, tools: List<Tool>) {
        parent.addView(title(heading, 15f, edge),
            LinearLayout.LayoutParams(-1, dp(38)))
        for (pair in tools.chunked(2)) {
            val strip = row().apply { gravity = Gravity.TOP }
            for (tool in pair) {
                val tile = column().apply {
                    background = shape(panelColor, tool.color)
                    setPadding(dp(5), dp(9), dp(5), dp(8))
                    gravity = Gravity.CENTER
                    contentDescription = "Mở " + tool.name
                    setOnClickListener { openTool(tool.page) }
                }
                tile.addView(CrewFigureView(this, tool.color),
                    LinearLayout.LayoutParams(-1, dp(76)))
                tile.addView(title(tool.name, 12f).apply { gravity = Gravity.CENTER; maxLines = 2 },
                    LinearLayout.LayoutParams(-1, dp(35)))
                tile.addView(caption(tool.detail, 10f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                }, LinearLayout.LayoutParams(-1, dp(39)))
                strip.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (tool == pair.first()) rightMargin = dp(6) else leftMargin = dp(6)
                })
            }
            parent.addView(strip, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun openTool(page: String) {
        when (page) {
            "battery" -> renderBattery()
            "about" -> renderAbout()
            "multiwindow" -> renderMultiwindow()
            else -> startActivity(Intent(this, DashboardActivity::class.java)
                .putExtra("pcm_page", page))
        }
    }

    private fun backBar(parent: LinearLayout, name: String, color: Int) {
        val header = row()
        header.addView(button("←", color) { renderHome() },
            LinearLayout.LayoutParams(dp(48), dp(47)).apply { rightMargin = dp(8) })
        header.addView(CrewFigureView(this, color), LinearLayout.LayoutParams(dp(49), dp(57)))
        header.addView(title(name, 20f, white),
            LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(header)
        parent.addView(gap(15))
    }

    private fun renderBattery() {
        val body = screen()
        backBar(body, "PIN / NGUỒN", orange)
        val power = card(orange)
        power.addView(title("CHẨN ĐOÁN NGUỒN ĐIỆN", 16f, orange))
        val report = caption(PowerHealthReport.snapshot(this), 14f, white)
        power.addView(gap(10))
        power.addView(report)
        power.addView(gap(12))
        power.addView(button("LÀM MỚI SỐ LIỆU", orange) {
            report.text = PowerHealthReport.snapshot(this)
        })
        addCard(body, power)
        val simulation = card(sky)
        simulation.addView(title("CHẾ ĐỘ PIN DEMO", 16f, sky))
        simulation.addView(caption("Mô phỏng hiển thị trong PCM Gaming. Không giả trạng thái pin " +
            "của Android hoặc can thiệp hệ thống quản lý nguồn."))
        simulation.addView(gap(10))
        simulation.addView(button("BẬT / TẮT NHÃN PIN 999%", sky) {
            val prefs = getSharedPreferences("visual_demo", MODE_PRIVATE)
            val on = !prefs.getBoolean("battery999", false)
            prefs.edit().putBoolean("battery999", on).apply()
            toast(if (on) "Đã bật nhãn PIN DEMO 999%" else "Đã tắt nhãn demo")
        })
        addCard(body, simulation)
    }

    private fun renderMultiwindow() {
        val body = screen()
        backBar(body, "DEX • MULTIWINDOW", sky)
        val p = card(sky)
        p.addView(title("SAMSUNG MULTIWINDOW / GOOD LOCK", 17f, sky))
        p.addView(caption("Các tính năng MultiStar, tối ưu nhiều cửa sổ và DeX phụ thuộc bản " +
            "One UI. PCM không sửa giá trị hệ thống chưa được ROM công bố."))
        p.addView(gap(12))
        p.addView(button("MỞ CÀI ĐẶT MÀN HÌNH", sky) {
            startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS))
        })
        p.addView(gap(8))
        p.addView(button("KIỂM TRA HDMI 120HZ", orange) { openTool("dex") })
        addCard(body, p)
    }

    private fun renderAbout() {
        val body = screen()
        backBar(body, "VỀ PCM GAMING", purple)
        val card = card(purple)
        card.addView(CrewFigureView(this, red, "PCM", crowned = true),
            LinearLayout.LayoutParams(-1, dp(155)))
        card.addView(title("PCM GAMING", 25f, white).apply { gravity = Gravity.CENTER })
        card.addView(caption("TÁC GIẢ: DUY KHẢI", 14f, sky).apply { gravity = Gravity.CENTER })
        card.addView(caption("SĐT: 0862134811", 14f, white))
        card.addView(caption("ZALO: 0862134811", 14f, white))
        card.addView(caption("Ứng dụng điều khiển màn hình • DeX • gaming • monitor"))
        card.addView(gap(15))
        card.addView(button("LIÊN HỆ ZALO", orange) { openZalo() })
        addCard(body, card)
    }

    private fun requestRoot() {
        rootLabel?.text = "ROOT • ĐANG KIỂM TRA"
        worker.execute {
            val ok = RootAccess.request(this@PcmGamingActivity)
            runOnUiThread {
                rootLabel?.text = if (ok) "ROOT • ONLINE" else "ROOT • CHƯA CẤP"
                toast(if (ok) "Đã kết nối phiên Root" else "Chưa có quyền Root")
            }
        }
    }
    private fun requestShizuku() {
        try {
            ShizukuAccess.request()
            CommandRunner.connect(this)
            toast("Đã yêu cầu quyền Shizuku nếu được hỗ trợ")
        } catch (e: Exception) { toast("Shizuku chưa sẵn sàng: " + e.message) }
    }
    private fun openZalo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://zalo.me/0862134811")))
        } catch (_: Exception) { toast("Không mở được Zalo") }
    }
    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
    }
    override fun onBackPressed() {
        renderHome()
    }
}
