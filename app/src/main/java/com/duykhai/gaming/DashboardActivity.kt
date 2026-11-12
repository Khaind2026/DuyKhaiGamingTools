package com.duykhai.gaming

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/**
 * Interactive multi-screen interface matching the user's neon/space reference.
 * All controls remain native Android Views; none of the UI screens are screenshots.
 */
class DashboardActivity : Activity() {
    private val ui = NeonUi
    private lateinit var container: FrameLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val sampling = AtomicBoolean(false)
    private var page = "home"
    private var fpsValue: TextView? = null
    private var tempValue: TextView? = null
    private var clockValue: TextView? = null
    private var feedback: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            if (page != "home") return
            if (sampling.compareAndSet(false, true)) {
                val pkg = Profiles.load(this@DashboardActivity).gamePackage
                worker.execute {
                    val sample = try { PerformanceMonitor.sample(pkg, RootAccess.status()) }
                        catch (_: Exception) { MonitorSnapshot(null, null, emptyList(), "Không có dữ liệu") }
                    runOnUiThread {
                        if (page == "home") {
                            fpsValue?.text = sample.fps?.let {
                                String.format(Locale.US, "%.0f", it)
                            } ?: "—"
                            tempValue?.text = sample.temperatureC?.let {
                                String.format(Locale.US, "%.0f°C", it)
                            } ?: "—"
                            clockValue?.text = sample.cpuClocks.maxOfOrNull { it.second }?.let {
                                String.format(Locale.US, "%.2f GHz", it / 1000f)
                            } ?: "—"
                        }
                        sampling.set(false)
                    }
                }
            }
            handler.postDelayed(this, 3500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 9, 31)
        window.navigationBarColor = Color.rgb(9, 8, 33)
        container = FrameLayout(this)
        setContentView(container)
        try { Sui.init(packageName); CommandRunner.connect(this) } catch (_: Throwable) { }
        renderHome()
    }

    override fun onResume() {
        super.onResume()
        if (page == "home") resumeStats()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (page != "home") renderHome() else super.onBackPressed()
    }

    private fun dp(n: Int) = ui.dp(this, n)
    private fun label(value: String, size: Float = 14f, color: Int = ui.white, bold: Boolean = false) =
        ui.text(this, value, size, color, bold)

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun blank(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun begin(which: String, heading: String = "", color: Int = ui.cyan): LinearLayout {
        page = which
        handler.removeCallbacks(tick)
        fpsValue = null
        tempValue = null
        clockValue = null
        feedback = null
        container.removeAllViews()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val outer = vertical().apply {
            setPadding(dp(13), dp(16), dp(13), dp(30))
            background = SpaceBackdropDrawable()
        }
        scroll.addView(outer)
        container.addView(scroll)
        if (which != "home") {
            val bar = row()
            val back = label("❮", 28f, ui.white, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(12), 0)
                setOnClickListener { renderHome() }
            }
            bar.addView(back, LinearLayout.LayoutParams(dp(45), dp(48)))
            bar.addView(CrewFigureView(this, color), LinearLayout.LayoutParams(dp(48), dp(56)))
            bar.addView(label(heading, 21f, ui.white, true).apply {
                setPadding(dp(8), 0, 0, 0)
                maxLines = 2
            }, LinearLayout.LayoutParams(0, dp(54), 1f))
            outer.addView(bar)
            outer.addView(blank(14))
        }
        return outer
    }

    private fun panel(into: LinearLayout, edge: Int = ui.purple): LinearLayout {
        val card = vertical().apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ui.card(this@DashboardActivity, edge)
            elevation = dp(4).toFloat()
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(13)
        }
        into.addView(card, lp)
        return card
    }

    private fun groupTitle(into: LinearLayout, title: String, color: Int = ui.white) {
        into.addView(label(title, 16f, color, true))
        into.addView(blank(8))
    }

    private fun note(into: LinearLayout, str: String) {
        into.addView(label(str, 11.5f, ui.muted).apply { setPadding(0, dp(6), 0, dp(6)) })
    }

    private fun action(into: LinearLayout, text: String, run: () -> Unit): TextView {
        val button = ui.glowButton(this, text)
        into.addView(button, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        button.setOnClickListener { run() }
        return button
    }

    private fun spinner(options: Array<String>, value: Int = 0): Spinner {
        val adapt = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        return Spinner(this).apply {
            adapter = adapt
            setSelection(value.coerceIn(0, options.lastIndex))
            minimumHeight = dp(48)
        }
    }

    private fun number(value: Int, hint: String): EditText =
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setTextColor(ui.white)
            setHintTextColor(ui.muted)
            this.hint = hint
            setText(if (value > 0) value.toString() else "")
            textSize = 16f
            gravity = Gravity.CENTER
        }

    private fun slider(into: LinearLayout, title: String, min: Int, max: Int, value: Int,
                       changed: (Int) -> Unit): SeekBar {
        val titleText = label(title + ": " + value, 13f, ui.white)
        into.addView(titleText)
        val control = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            progressTintList = android.content.res.ColorStateList.valueOf(ui.purple)
            thumbTintList = android.content.res.ColorStateList.valueOf(ui.cyan)
        }
        into.addView(control)
        control.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                val selected = progress + min
                titleText.text = title + ": " + selected
                if (fromUser) changed(selected)
            }
        })
        return control
    }

    private fun status(into: LinearLayout) {
        feedback = label("", 12f, ui.cyan)
        into.addView(feedback)
    }

    private fun applyProfile(profile: Profile) {
        val current = feedback
        current?.text = "Đang áp dụng cấu hình…"
        worker.execute {
            val result = DisplayRoot.apply(this, profile)
            if (result.ok) Profiles.save(this, profile)
            runOnUiThread {
                current?.text = (if (result.ok) "✓ " else "⚠ ") + result.info.take(850)
            }
        }
    }

    private fun rootGrants(into: LinearLayout) {
        val r = row()
        val rootBox = vertical().apply {
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = ui.card(this@DashboardActivity, ui.cyan)
        }
        val rootState = label(if (RootAccess.status()) "✓ ROOT" else "○ ROOT", 16f,
            if (RootAccess.status()) ui.green else ui.muted, true)
        rootBox.addView(rootState)
        rootBox.addView(label(if (RootAccess.status()) "Đã kết nối" else "Nhấn để cấp quyền", 11f, ui.muted))
        rootBox.setOnClickListener {
            rootState.text = "ĐANG YÊU CẦU..."
            worker.execute {
                RootAccess.request()
                runOnUiThread {
                    rootState.text = if (RootAccess.status()) "✓ ROOT" else "○ ROOT"
                    rootState.setTextColor(if (RootAccess.status()) ui.green else ui.muted)
                }
            }
        }
        val shizukuBox = vertical().apply {
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = ui.card(this@DashboardActivity, ui.purple)
        }
        val shizukuValue = ShizukuAccess.status()
        shizukuBox.addView(label("○ SHIZUKU", 16f,
            if (shizukuValue.startsWith("ĐÃ CẤP")) ui.green else ui.muted, true))
        shizukuBox.addView(label(shizukuValue.take(26), 10f, ui.muted))
        shizukuBox.setOnClickListener {
            try {
                ShizukuAccess.request()
                CommandRunner.connect(this)
                toast("Kiểm tra quyền Shizuku trong ứng dụng Shizuku")
            } catch (_: Exception) { toast("Chưa kết nối Shizuku") }
        }
        r.addView(rootBox, LinearLayout.LayoutParams(0, dp(66), 1f).apply { rightMargin = dp(6) })
        r.addView(shizukuBox, LinearLayout.LayoutParams(0, dp(66), 1f).apply { leftMargin = dp(6) })
        into.addView(r)
    }

    private fun renderHome() {
        val root = begin("home")
        val header = row()
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_duykhaidex) }
        header.addView(logo, LinearLayout.LayoutParams(dp(65), dp(75)))
        val name = vertical().apply {
            addView(label("DuyKhaiDex", 26f, ui.white, true).apply { setTypeface(null, 3) })
            addView(label("Gaming Tools    v0.5.0", 17f, ui.purple, true))
        }
        header.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("⚙", 27f, ui.white, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { renderSettings() }
        }, LinearLayout.LayoutParams(dp(44), dp(54)))
        root.addView(header)

        val illustration = CrewHeroView(this)
        root.addView(illustration, LinearLayout.LayoutParams(-1, dp(208)))
        root.addView(blank(5))
        rootGrants(root)
        root.addView(blank(10))

        val statPanel = panel(root, ui.cyan)
        val statRow = row()
        fun statItem(title: String, initially: String): LinearLayout = vertical().apply {
            gravity = Gravity.CENTER
            addView(label(title, 12f, ui.white, true).apply { gravity = Gravity.CENTER })
            addView(label(initially, 23f, ui.green, true).apply { gravity = Gravity.CENTER })
        }
        val a = statItem("FPS", "—")
        val b = statItem("CPU", "—")
        val c = statItem("CPU CLOCK", "—")
        fpsValue = a.getChildAt(1) as TextView
        tempValue = b.getChildAt(1) as TextView
        clockValue = c.getChildAt(1) as TextView
        statRow.addView(a, LinearLayout.LayoutParams(0, dp(57), 1f))
        statRow.addView(b, LinearLayout.LayoutParams(0, dp(57), 1f))
        statRow.addView(c, LinearLayout.LayoutParams(0, dp(57), 1f))
        statPanel.addView(statRow)
        root.addView(label("FPS game đo từ layer (nếu được hỗ trợ) • không phải Hz", 10.5f, ui.muted))
        root.addView(blank(9))

        val features = arrayOf(
            Triple("Tối Ưu\nGame", 0xFF9A4DFF.toInt(), "game"),
            Triple("Khóa Hz", 0xFFFF902F.toInt(), "hz"),
            Triple("Tỷ Lệ Màn\nHình", 0xFF04C3FC.toInt(), "display"),
            Triple("CPU &\nHiệu Năng", 0xFF23D781.toInt(), "cpu"),
            Triple("Màu Sắc &\nHiển Thị", 0xFFF057D5.toInt(), "color"),
            Triple("HUD Theo Dõi", 0xFFE8EFFF.toInt(), "hud"),
            Triple("Quản Lý\nGame", 0xFF287EFF.toInt(), "games"),
            Triple("Công Cụ\nKhác", 0xFF8A38FF.toInt(), "other"),
            Triple("Cài Đặt", 0xFF15C4FA.toInt(), "settings")
        )
        features.toList().chunked(3).forEach { pack ->
            val gridRow = row()
            pack.forEach { (title, color, key) ->
                val tile = vertical().apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(6), dp(2), dp(5))
                    background = ui.card(this@DashboardActivity, ui.purple)
                    elevation = dp(3).toFloat()
                }
                val emblem = when (key) {
                    "hz" -> "Hz"
                    "cpu" -> "◆"
                    "hud" -> "⌁"
                    "color" -> "✿"
                    "settings" -> "⚙"
                    else -> ""
                }
                tile.addView(CrewFigureView(this, color, emblem), LinearLayout.LayoutParams(-1, dp(75)))
                tile.addView(label(title, 12.5f, ui.white, true).apply {
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    minHeight = dp(34)
                })
                tile.setOnClickListener { openPage(key) }
                gridRow.addView(tile, LinearLayout.LayoutParams(0, dp(120), 1f).apply {
                    leftMargin = dp(3)
                    rightMargin = dp(3)
                })
            }
            root.addView(gridRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
        val sales = panel(root, ui.purple)
        sales.addView(label("🚀  Samsung DeX • Android Box • Gaming", 15f, ui.white, true))
        sales.addView(label("Mua máy và hỗ trợ kỹ thuật", 12f, ui.muted))
        sales.addView(label("Zalo  0862134811", 24f, Color.rgb(255, 218, 60), true))
        sales.setOnClickListener { openZalo() }
        resumeStats()
    }

    private fun resumeStats() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun openPage(id: String) {
        when (id) {
            "game" -> renderGame()
            "hz" -> renderHz()
            "display" -> renderDisplay()
            "cpu" -> renderCpu()
            "color" -> renderColor()
            "hud" -> renderHud()
            "games" -> renderGames()
            "other" -> renderOther()
            "settings" -> renderSettings()
        }
    }

    private fun renderGame() {
        val body = begin("game", "Tối Ưu Game", ui.purple)
        val choose = panel(body, ui.cyan)
        groupTitle(choose, "GAME CẦN TỐI ƯU", ui.cyan)
        val p = Profiles.load(this)
        val game = spinner(GameCatalog.labels, GameCatalog.position(p.gamePackage))
        choose.addView(game)
        val packageInput = EditText(this).apply {
            setSingleLine(true); setTextColor(Color.WHITE); setText(p.gamePackage)
            hint = "com.example.game"; setHintTextColor(ui.muted)
        }
        choose.addView(packageInput)
        game.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) packageInput.setText(GameCatalog.packages[position])
            }
        }
        val options = panel(body, ui.purple)
        groupTitle(options, "CHẾ ĐỘ TỐI ƯU")
        val mode = spinner(arrayOf("Không đổi CPU", "🍃 Tiết kiệm pin", "⚖ Ổn định", "🔥 SUPER (trần xung gốc)"),
            p.cpuMode)
        options.addView(mode)
        note(options, "Giới hạn CPU trong mức nhà sản xuất và giữ bảo vệ nhiệt.")
        options.addView(blank(10))
        groupTitle(options, "FPS GAME (YÊU CẦU)")
        val fps = spinner(arrayOf("Không đổi", "30 FPS", "45 FPS", "60 FPS", "90 FPS",
            "120 FPS", "144 FPS*", "165 FPS*"), FpsOptions.values.indexOf(p.fps).coerceAtLeast(0))
        options.addView(fps)
        note(options, "FPS phụ thuộc game và ROM; 144/165 không chắc đạt được.")
        status(options)
        action(options, "ÁP DỤNG CẤU HÌNH GAME") {
            val pkg = packageInput.text.toString().trim()
            val current = Profiles.load(this)
            applyProfile(current.copy(gamePackage = pkg, cpuMode = mode.selectedItemPosition,
                fps = FpsOptions.values[fps.selectedItemPosition],
                resolution = 0, ratio = 0, customSize = false, dpiEnabled = false, hz = 0))
        }
    }

    private fun renderHz() {
        val body = begin("hz", "Khóa Hz (Tần số quét)", ui.purple)
        val hzCard = panel(body, ui.cyan)
        groupTitle(hzCard, "TẦN SỐ QUÉT VẬT LÝ")
        val p = Profiles.load(this)
        val supported = try {
            val display = (getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            display?.supportedModes?.map { it.refreshRate.toInt() }?.distinct()?.sorted()?.joinToString(" / ")
                ?: "Không đọc được"
        } catch (_: Exception) { "Không đọc được" }
        note(hzCard, "Màn hình báo hỗ trợ: " + supported + " Hz")
        val hz = spinner(arrayOf("Không đổi", "60 Hz", "90 Hz", "120 Hz", "144 Hz", "165 Hz", "200 Hz"),
            intArrayOf(0, 60, 90, 120, 144, 165, 200).indexOf(p.hz).coerceAtLeast(0))
        hzCard.addView(hz)
        val methods = panel(body, ui.purple)
        groupTitle(methods, "CHẾ ĐỘ KHÓA")
        val method = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val a = RadioButton(this).apply {
            text = "Thích ứng thông minh • Yêu cầu min/peak Hz"
            setTextColor(ui.white); id = View.generateViewId()
        }
        val b = RadioButton(this).apply {
            text = "Ưu tiên mode vật lý (mạnh hơn)"
            setTextColor(ui.white); id = View.generateViewId()
        }
        method.addView(a)
        method.addView(b)
        method.check(if (p.hardHz) b.id else a.id)
        methods.addView(method)
        note(methods, "Không thể khóa vượt tần số phần cứng; Samsung vẫn có thể giảm Hz theo chính sách nhiệt và pin.")
        val auto = Switch(this).apply {
            text = "Nhớ cấu hình sau khi khởi động"
            setTextColor(ui.white)
            isChecked = p.autoBoot
        }
        methods.addView(auto)
        status(methods)
        action(methods, "ÁP DỤNG KHÓA HZ") {
            val chosen = intArrayOf(0, 60, 90, 120, 144, 165, 200)[hz.selectedItemPosition]
            val current = Profiles.load(this)
            applyProfile(current.copy(hz = chosen, hardHz = method.checkedRadioButtonId == b.id,
                fps = 0, cpuMode = 0, resolution = 0, ratio = 0, customSize = false,
                dpiEnabled = false, autoBoot = auto.isChecked))
        }
    }

    private fun renderDisplay() {
        val body = begin("display", "Tỷ Lệ Màn Hình (Tùy Biến)", ui.cyan)
        val p = Profiles.load(this)
        val settings = panel(body, ui.purple)
        val custom = Switch(this).apply {
            text = "Tùy biến chiều rộng × chiều cao"
            setTextColor(ui.white); isChecked = p.customSize
        }
        settings.addView(custom)
        groupTitle(settings, "ĐỘ RỘNG (WIDTH)")
        val width = number(p.customWidth.takeIf { it > 0 } ?: 1920, "1920")
        settings.addView(width)
        groupTitle(settings, "CHIỀU CAO (HEIGHT)")
        val height = number(p.customHeight.takeIf { it > 0 } ?: 1080, "1080")
        settings.addView(height)
        groupTitle(settings, "DPI")
        val dpi = number(p.dpi, "320")
        settings.addView(dpi)
        val presets = panel(body, ui.cyan)
        groupTitle(presets, "TỶ LỆ NHANH", ui.cyan)
        val ratioRow = row()
        val ratios = arrayOf("16:9" to 16f/9f, "18:9" to 2f, "20:9" to 20f/9f)
        ratios.forEach { (name, value) ->
            val option = label(name, 12f, ui.white, true).apply {
                gravity = Gravity.CENTER
                background = ui.card(this@DashboardActivity, ui.purple)
                setOnClickListener {
                    val w = width.text.toString().toIntOrNull() ?: 1920
                    width.setText(w.toString())
                    height.setText((w/value).toInt().toString())
                    custom.isChecked = true
                }
            }
            ratioRow.addView(option, LinearLayout.LayoutParams(0, dp(43), 1f).apply { rightMargin = dp(4) })
        }
        presets.addView(ratioRow)
        groupTitle(presets, "PRESET CÓ SẴN")
        val preset = spinner(arrayOf("Theo màn gốc", "720p", "1080p", "1440p"),
            intArrayOf(0, 720, 1080, 1440).indexOf(p.resolution).coerceAtLeast(0))
        presets.addView(preset)
        note(presets, "Nếu chọn tùy biến, số Width/Height sẽ được ưu tiên. Đây là độ phân giải logic, không tăng điểm ảnh vật lý.")
        status(presets)
        action(presets, "ÁP DỤNG TỶ LỆ MÀN HÌNH") {
            val current = Profiles.load(this)
            applyProfile(current.copy(
                customSize = custom.isChecked,
                customWidth = width.text.toString().toIntOrNull() ?: 0,
                customHeight = height.text.toString().toIntOrNull() ?: 0,
                resolution = intArrayOf(0, 720, 1080, 1440)[preset.selectedItemPosition],
                ratio = 0,
                dpi = dpi.text.toString().toIntOrNull() ?: 0,
                dpiEnabled = true,
                hz = 0, fps = 0, cpuMode = 0))
        }
        action(presets, "KHÔI PHỤC KÍCH THƯỚC MẶC ĐỊNH") {
            if (!RootAccess.status() && !CommandRunner.ready()) {
                feedback?.text = "Cần Root hoặc Shizuku để khôi phục."
                return@action
            }
            worker.execute {
                val result = CommandRunner.run("wm size reset; wm density reset", RootAccess.status())
                runOnUiThread { feedback?.text = result.getOrElse(1) { "" }.ifBlank { "Đã gửi lệnh reset" } }
            }
        }
    }

    private fun renderCpu() {
        val body = begin("cpu", "CPU & Hiệu Năng", ui.green)
        val p = Profiles.load(this)
        val card = panel(body, ui.cyan)
        groupTitle(card, "CHẾ ĐỘ XUNG NHỊP")
        val modes = spinner(arrayOf("Không thay đổi",
            "🍃 ECO • giới hạn 70% trần gốc",
            "⚖  BALANCED • 90% trần gốc",
            "🔥 SUPER • 100% trần gốc"), p.cpuMode)
        card.addView(modes)
        note(card, "Đọc lại min/max sau khi áp dụng. Một số kernel Samsung không cho thay đổi; app sẽ báo lỗi thay vì khẳng định khóa thành công.")
        val readings = panel(body, ui.purple)
        groupTitle(readings, "XUNG NHỊP CPU THEO CỤM", ui.cyan)
        val clocks = label("Chưa đọc thông số", 14f, ui.white)
        readings.addView(clocks)
        action(readings, "ĐỌC THÔNG SỐ CPU") {
            clocks.text = "Đang đọc..."
            worker.execute {
                val snapshot = PerformanceMonitor.sample("", RootAccess.status())
                runOnUiThread { clocks.text = PerformanceMonitor.clocksLabel(snapshot.cpuClocks) }
            }
        }
        status(readings)
        action(readings, "ÁP DỤNG CHẾ ĐỘ CPU") {
            val current = Profiles.load(this)
            applyProfile(current.copy(cpuMode = modes.selectedItemPosition, fps = 0, hz = 0,
                resolution = 0, ratio = 0, customSize = false, dpiEnabled = false))
        }
    }

    private fun renderHud() {
        val body = begin("hud", "HUD Theo Dõi", ui.cyan)
        val pref = getSharedPreferences("hud_prefs", MODE_PRIVATE)
        val c = panel(body, ui.purple)
        groupTitle(c, "THÔNG SỐ HIỂN THỊ")
        fun toggle(key: String, title: String): CheckBox = CheckBox(this).apply {
            text = title; setTextColor(ui.white)
            isChecked = pref.getBoolean(key, true)
            setOnCheckedChangeListener { _, value -> pref.edit().putBoolean(key, value).apply() }
        }
        c.addView(toggle("fps", "FPS Game"))
        c.addView(toggle("temp", "Nhiệt độ CPU"))
        c.addView(toggle("clock", "Xung nhịp CPU"))
        note(c, "GPU/load và nhiệt độ khác sẽ chỉ hiển thị nếu có nguồn dữ liệu tương thích.")
        val config = panel(body, ui.cyan)
        groupTitle(config, "KÍCH THƯỚC VÀ ĐỘ TRONG SUỐT")
        slider(config, "Kích thước HUD (%)", 60, 160, pref.getInt("scale", 100)) {
            pref.edit().putInt("scale", it).apply()
        }
        slider(config, "Độ trong suốt HUD (%)", 40, 100, pref.getInt("alpha", 85)) {
            pref.edit().putInt("alpha", it).apply()
        }
        groupTitle(config, "VỊ TRÍ HIỂN THỊ")
        val positions = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val cornerLabels = arrayOf("Góc trên trái", "Góc trên phải", "Góc dưới trái", "Góc dưới phải")
        val positionButtons = cornerLabels.mapIndexed { i, name ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = name
                setTextColor(ui.white)
                positions.addView(this)
                if (pref.getInt("corner", 1) == i) isChecked = true
            }
        }
        positions.setOnCheckedChangeListener { _, checkedId ->
            val i = positionButtons.indexOfFirst { it.id == checkedId }
            if (i >= 0) pref.edit().putInt("corner", i).apply()
        }
        config.addView(positions)
        note(config, "Có thể kéo HUD đến vị trí khác bằng cách giữ thanh tiêu đề.")
        status(config)
        action(config, if (MonitorOverlayService.active) "TẮT HUD NỔI" else "BẬT HUD NỔI") {
            toggleHud()
        }
    }

    private fun toggleHud() {
        if (MonitorOverlayService.active) {
            stopService(Intent(this, MonitorOverlayService::class.java))
            feedback?.text = "Đã tắt HUD"
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Cần quyền hiển thị trên ứng dụng khác")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)))
            return
        }
        getSharedPreferences("hud_prefs", MODE_PRIVATE).edit()
            .putString("game", Profiles.load(this).gamePackage).apply()
        try {
            startForegroundService(Intent(this, MonitorOverlayService::class.java)
                .setAction(MonitorOverlayService.ACTION_START))
            feedback?.text = "Đã yêu cầu mở HUD; bấm × trên HUD để đóng."
        } catch (e: Exception) {
            feedback?.text = "Không mở được HUD: " + e.message
        }
    }

    private fun renderColor() {
        val body = begin("color", "Màu Sắc & Hiển Thị", 0xFFFF4CC9.toInt())
        val p = Profiles.load(this)
        val box = panel(body, ui.purple)
        groupTitle(box, "HIỆU CHỈNH ẢNH MẪU")
        val preview = TextView(this).apply {
            text = "DUYKHAIDEX    ✦    COLOR LAB"
            setTextColor(ui.white)
            gravity = Gravity.CENTER
            textSize = 17f
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1458D7.toInt(), 0xFF1FE7DA.toInt(), 0xFFE61A9E.toInt())).apply {
                cornerRadius = dp(14).toFloat()
            }
        }
        box.addView(preview, LinearLayout.LayoutParams(-1, dp(150)))
        val values = intArrayOf(p.saturation, p.vibrance, p.contrast, p.brightness)
        slider(box, "Bão hòa (Saturation)", 0, 200, values[0]) { values[0] = it }
        slider(box, "Độ tươi (Vibrance)", 0, 100, values[1]) { values[1] = it }
        slider(box, "Tương phản (Contrast)", 50, 150, values[2]) { values[2] = it }
        slider(box, "Độ sáng (Brightness)", -50, 50, values[3]) { values[3] = it }
        note(box, "Các thanh màu hiện chỉ là công cụ lưu cấu hình thử nghiệm. Android/Samsung không cho mọi app thay màu toàn màn hình bằng API công khai.")
        action(box, "LƯU CẤU HÌNH MÀU") {
            Profiles.save(this, Profiles.load(this).copy(
                saturation = values[0], vibrance = values[1], contrast = values[2], brightness = values[3]))
            toast("Đã lưu thông số màu (chưa áp dụng toàn hệ thống)")
        }
        action(box, "MỞ CÀI ĐẶT MÀN HÌNH SAMSUNG") {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
    }

    private fun renderGames() {
        val body = begin("games", "Quản Lý Game", ui.cyan)
        val p = Profiles.load(this)
        val c = panel(body, ui.purple)
        groupTitle(c, "CHỌN GAME ĐỂ THEO DÕI FPS", ui.cyan)
        val game = spinner(GameCatalog.labels, GameCatalog.position(p.gamePackage))
        c.addView(game)
        val custom = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setText(p.gamePackage)
            hint = "com.example.game"
            setHintTextColor(ui.muted)
        }
        c.addView(custom)
        game.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) custom.setText(GameCatalog.packages[position])
            }
        }
        status(c)
        action(c, "LƯU GAME") {
            val pkg = custom.text.toString().trim()
            Profiles.save(this, Profiles.load(this).copy(gamePackage = pkg))
            getSharedPreferences("hud_prefs", MODE_PRIVATE).edit().putString("game", pkg).apply()
            feedback?.text = "✓ Đã lưu package: " + pkg
        }
        action(c, "MỞ GAME ĐÃ CHỌN") {
            val pkg = custom.text.toString().trim()
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) toast("Không tìm thấy game này trên máy") else startActivity(launch)
        }
    }

    private fun renderOther() {
        val body = begin("other", "Công Cụ Khác", ui.purple)
        val c = panel(body)
        groupTitle(c, "TÙY CHỈNH NÂNG CAO")
        note(c, "Các màn điều khiển nâng cao trước đây vẫn được giữ lại.")
        action(c, "MỞ GIAO DIỆN CŨ / NÂNG CAO") {
            startActivity(Intent(this, MainActivity::class.java))
        }
        action(c, "MỞ GITHUB DỰ ÁN") {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/Khaind2026/DuyKhaiGamingTools")))
        }
        action(c, "LIÊN HỆ ZALO • 0862134811") { openZalo() }
    }

    private fun renderSettings() {
        val body = begin("settings", "Cài Đặt", ui.cyan)
        val rights = panel(body, ui.purple)
        groupTitle(rights, "QUYỀN & KẾT NỐI")
        rootGrants(rights)
        note(rights, "Root chỉ được hỏi khi mày chủ động nhấn cấp quyền. Chọn ghi nhớ quyền trong Magisk/KernelSU để tránh hỏi lại.")
        val boot = panel(body, ui.cyan)
        groupTitle(boot, "KHỞI ĐỘNG CÙNG HỆ THỐNG")
        val toggle = Switch(this).apply {
            text = "Tự áp dụng cấu hình (AUTO BOOT)"
            setTextColor(ui.white)
            isChecked = Profiles.load(this@DashboardActivity).autoBoot
        }
        boot.addView(toggle)
        action(boot, "LƯU CÀI ĐẶT") {
            val saved = Profiles.load(this).copy(autoBoot = toggle.isChecked)
            Profiles.save(this, saved)
            if (toggle.isChecked) BootJobs.schedule(this) else BootJobs.cancel(this)
            toast("Đã lưu tùy chọn AUTO BOOT")
        }
        val store = panel(body, ui.purple)
        groupTitle(store, "KHÔI PHỤC & SAO LƯU")
        action(store, "SAO LƯU CẤU HÌNH") {
            val p = Profiles.load(this)
            val json = JSONObject().apply {
                put("customSize", p.customSize); put("customWidth", p.customWidth)
                put("customHeight", p.customHeight); put("dpi", p.dpi)
                put("hz", p.hz); put("hardHz", p.hardHz); put("fps", p.fps)
                put("gamePackage", p.gamePackage); put("cpuMode", p.cpuMode)
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DuyKhaiDex config", json.toString()))
            toast("Đã sao chép cấu hình vào Clipboard")
        }
        action(store, "KHÔI PHỤC CẤU HÌNH TỪ JSON") {
            val edit = EditText(this).apply {
                minLines = 4; setTextColor(ui.white)
                hint = "Dán JSON đã sao lưu"
            }
            AlertDialog.Builder(this).setTitle("Nhập cấu hình đã sao lưu")
                .setView(edit)
                .setPositiveButton("Khôi phục") { _, _ ->
                    try {
                        val json = JSONObject(edit.text.toString())
                        val current = Profiles.load(this)
                        Profiles.save(this, current.copy(
                            customSize = json.optBoolean("customSize", current.customSize),
                            customWidth = json.optInt("customWidth", current.customWidth),
                            customHeight = json.optInt("customHeight", current.customHeight),
                            dpi = json.optInt("dpi", current.dpi),
                            hz = json.optInt("hz", current.hz),
                            hardHz = json.optBoolean("hardHz", current.hardHz),
                            fps = json.optInt("fps", current.fps),
                            gamePackage = json.optString("gamePackage", current.gamePackage),
                            cpuMode = json.optInt("cpuMode", current.cpuMode)
                        ))
                        toast("Đã nhập cấu hình, cần áp dụng lại")
                    } catch (_: Exception) { toast("JSON không hợp lệ") }
                }.setNegativeButton("Hủy", null).show()
        }
        val about = panel(body, ui.cyan)
        groupTitle(about, "THÔNG TIN ỨNG DỤNG")
        about.addView(label("DuyKhaiDex Gaming Tools", 16f, ui.white, true))
        about.addView(label("Phiên bản 0.5.0 • Giao diện Space Neon", 12f, ui.muted))
        about.addView(label("Samsung DeX • Zalo 0862134811", 14f, ui.green, true))
        action(about, "MỞ ZALO") { openZalo() }
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    private fun openZalo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://zalo.me/0862134811")))
        } catch (_: Exception) {
            toast("Không mở được Zalo")
        }
    }
}
