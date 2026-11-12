package com.duykhai.gaming

import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.provider.Settings
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.ImageView
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Profile(
    val resolution: Int = 0,
    val ratio: Int = 0,
    val dpiEnabled: Boolean = false,
    val dpi: Int = 420,
    val hz: Int = 0,
    val fps: Int = 0,
    val gamePackage: String = "",
    val cpuMode: Int = 0,
    val saturation: Int = 100,
    val vibrance: Int = 0,
    val contrast: Int = 100,
    val brightness: Int = 0,
    val autoBoot: Boolean = false
)

object Profiles {
    private const val NAME = "gaming_profile"
    fun load(ctx: Context): Profile {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return Profile(
            resolution = p.getInt("resolution", 0),
            ratio = p.getInt("ratio", 0),
            dpiEnabled = p.getBoolean("dpiEnabled", false),
            dpi = p.getInt("dpi", 420),
            hz = p.getInt("hz", 0),
            fps = p.getInt("fps", 0),
            gamePackage = p.getString("gamePackage", "") ?: "",
            cpuMode = p.getInt("cpuMode", 0),
            saturation = p.getInt("saturation", 100),
            vibrance = p.getInt("vibrance", 0),
            contrast = p.getInt("contrast", 100),
            brightness = p.getInt("brightness", 0),
            autoBoot = p.getBoolean("autoBoot", false)
        )
    }
    fun save(ctx: Context, c: Profile) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt("resolution", c.resolution)
            .putInt("ratio", c.ratio)
            .putBoolean("dpiEnabled", c.dpiEnabled)
            .putInt("dpi", c.dpi)
            .putInt("hz", c.hz)
            .putInt("fps", c.fps)
            .putString("gamePackage", c.gamePackage)
            .putInt("cpuMode", c.cpuMode)
            .putInt("saturation", c.saturation)
            .putInt("vibrance", c.vibrance)
            .putInt("contrast", c.contrast)
            .putInt("brightness", c.brightness)
            .putBoolean("autoBoot", c.autoBoot)
            .commit()
    }
}

object DisplayRoot {
    private val hzAllowed = setOf(60, 90, 120, 144, 165, 200)
    private val fpsAllowed = FpsOptions.values.drop(1).toSet()
    private val gamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    data class Result(val ok: Boolean, val info: String)

    fun apply(ctx: Context, p: Profile, boot: Boolean = false): Result {
        if (p.cpuMode !in 0..3) return Result(false, "CPU profile không hợp lệ")
        val root = RootAccess.status()
        if (!root && (boot || !CommandRunner.ready())) return Result(false, "Cần Root hoặc Shizuku shell đã kết nối")
        if (!root && p.cpuMode != 0) return Result(false, "CPU cần Root; chọn CPU Không thay đổi để dùng Shizuku")
        val commands = mutableListOf<String>()
        if (p.resolution != 0 || p.ratio != 0) {
            if (p.resolution !in setOf(0, 720, 1080, 1440) || p.ratio !in 0..3)
                return Result(false, "Độ phân giải hoặc tỷ lệ không hợp lệ")
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val mode = dm.getDisplay(Display.DEFAULT_DISPLAY)?.mode
            val width = mode?.physicalWidth ?: ctx.resources.displayMetrics.widthPixels
            val height = mode?.physicalHeight ?: ctx.resources.displayMetrics.heightPixels
            val originalShort = min(width, height)
            val originalLong = max(width, height)
            val shortEdge = if (p.resolution == 0) originalShort else p.resolution
            val longEdge = when (p.ratio) {
                1 -> (shortEdge * 16f / 9f).roundToInt()
                2 -> (shortEdge * 16f / 10f).roundToInt()
                3 -> (shortEdge * 21f / 9f).roundToInt()
                else -> (shortEdge * originalLong.toFloat() / originalShort).roundToInt()
            }
            val value = if (width <= height) "${shortEdge}x${longEdge}" else "${longEdge}x${shortEdge}"
            commands.add("wm size " + value)
        }
        if (p.dpiEnabled) {
            if (p.dpi !in 160..640) return Result(false, "DPI phải từ 160 đến 640")
            commands.add("wm density " + p.dpi)
        }
        if (p.hz != 0) {
            if (p.hz !in hzAllowed) return Result(false, "Hz không hợp lệ")
            commands.add("settings put system peak_refresh_rate " + p.hz + ".0")
            commands.add("settings put system min_refresh_rate " + p.hz + ".0")
        }
        if (p.fps != 0) {
            if (p.fps !in fpsAllowed || !gamePattern.matches(p.gamePackage))
                return Result(false, "Chọn FPS và nhập đúng package game (com.example.game)")
            if (Build.VERSION.SDK_INT < 33) return Result(false, "FPS GameManager cần Android 13 trở lên")
            commands.add("cmd game set --mode 2 --fps " + p.fps + " " + p.gamePackage)
            commands.add("cmd game mode 2 " + p.gamePackage)
        }
        if (commands.isEmpty() && p.cpuMode == 0) return Result(false, "Chưa chọn thiết lập nào")
        val displayResult = if (commands.isEmpty()) Result(true, "Không đổi màn hình/FPS") else runCommands(commands, root)
        val cpuResult = if (p.cpuMode == 0) Result(true, "CPU giữ nguyên")
                        else runCommands(listOf(CpuProfiles.command(p.cpuMode)), true)
        return Result(displayResult.ok && cpuResult.ok, displayResult.info + " • CPU: " + cpuResult.info)
    }

    fun reset(p: Profile): Result {
        val root = RootAccess.status()
        if (!root && p.cpuMode != 0) return Result(false, "Cần Root để reset CPU")
        val commands = mutableListOf(
            "wm size reset",
            "wm density reset",
            "settings delete system peak_refresh_rate",
            "settings delete system min_refresh_rate"
        )
        if (gamePattern.matches(p.gamePackage)) commands.add("cmd game reset " + p.gamePackage)
        if (p.cpuMode != 0) commands.add(CpuProfiles.command(3))
        return runCommands(commands, root)
    }

    private fun runCommands(commands: List<String>, root: Boolean): Result {
        val details = commands.map { command ->
            if (Thread.currentThread().isInterrupted) return Result(false, "Đã hủy")
            val result = CommandRunner.run(command, root)
            val text = result.getOrElse(1) { "Không có phản hồi" }
            val ok = result[0] == "0" && !Regex("(?i)(error:|exception|not supported|invalid|unknown command)").containsMatchIn(text)
            ok to ((if (ok) "✓ " else "✗ ") + command.lineSequence().first().take(65) +
                (if (text.isBlank()) "" else ": " + text.take(400)))
        }
        return Result(details.all { it.first }, details.joinToString("\n") { it.second })
    }

}

object BootJobs {
    private const val JOB_ID = 261112
    fun schedule(ctx: Context) {
        val service = ComponentName(ctx, ApplyJobService::class.java)
        val job = JobInfo.Builder(JOB_ID, service)
            .setMinimumLatency(15_000L)
            .setOverrideDeadline(90_000L)
            .build()
        (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(job)
    }
    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Profiles.load(context).autoBoot) BootJobs.schedule(context)
    }
}

class ApplyJobService : JobService() {
    private var worker: Thread? = null
    @Volatile private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        worker = Thread {
            try {
                val config = Profiles.load(this)
                if (config.autoBoot) {
                    val result = DisplayRoot.apply(this, config, boot = true)
                    getSharedPreferences("boot_result", MODE_PRIVATE).edit()
                        .putString("last", java.text.DateFormat.getDateTimeInstance().format(java.util.Date()) + " • " + result.info)
                        .apply()
                    Log.i("DuyKhaiBoot", "AutoBoot success=" + result.ok + " " + result.info)
                }
            } catch (ex: Exception) {
                Log.e("DuyKhaiBoot", "AutoBoot error", ex)
            } finally {
                if (!stopped) jobFinished(params, false)
            }
        }.also { it.start() }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        worker?.interrupt()
        return false
    }
}

class MainActivity : Activity() {
    private val bgColor = Color.rgb(9, 16, 30)
    private val surface = Color.rgb(22, 35, 57)
    private val cyan = Color.rgb(0, 215, 255)
    private val violet = Color.rgb(169, 130, 255)
    private val muted = Color.rgb(161, 180, 205)
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var resolution: Spinner
    private lateinit var ratio: Spinner
    private lateinit var dpiEnabled: Switch
    private lateinit var dpiSeek: SeekBar
    private lateinit var dpiText: TextView
    private lateinit var hz: Spinner
    private lateinit var fps: Spinner
    private lateinit var gamePackage: EditText
    private lateinit var gamePreset: Spinner
    private lateinit var cpuPreset: Spinner
    private lateinit var saturationSeek: SeekBar
    private lateinit var vibranceSeek: SeekBar
    private lateinit var contrastSeek: SeekBar
    private lateinit var brightnessSeek: SeekBar
    private lateinit var colorPreview: ImageView
    private lateinit var rootLed: TextView
    private lateinit var shizukuLed: TextView
    private lateinit var autoBoot: Switch
    private var restoring = false

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            runOnUiThread { updatePrivilegeIndicators() }
        }
    private val shizukuBinderListener =
        Shizuku.OnBinderReceivedListener {
            runOnUiThread { updatePrivilegeIndicators() }
        }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(26))
            setBackgroundColor(bgColor)
        }
        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)

        val brandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        brandRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_duykhaidex)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76))
        })
        val brandLabel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("DUYKHAIDEX", 25f, cyan, true))
            addView(label("GAMING CONTROL • ROOT", 12f, violet, true))
        }
        brandRow.addView(brandLabel)
        layout.addView(brandRow)
        layout.addView(label("SAMSUNG ROOT DISPLAY ENGINE  •  v0.2.1", 11f, muted))
        space(layout, 18)
        layout.addView(stat("DEVICE ONLINE", Build.MANUFACTURER.uppercase() + "  " + Build.MODEL))
        space(layout, 10)
        val modes = try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes
                ?.map { it.refreshRate.roundToInt() }?.distinct()?.sorted()
                ?.joinToString(" / ") ?: "Unknown"
        } catch (e: Exception) { "Unknown" }
        layout.addView(stat("PANEL REPORTS", modes + " Hz"))
        space(layout, 14)

        val privilegeCard = panel()
        layout.addView(privilegeCard)
        privilegeCard.addView(label("00  QUYỀN ĐIỀU KHIỂN", 16f, cyan, true))
        space(privilegeCard, 8)
        rootLed = label("● ROOT: ĐANG KIỂM TRA", 14f, muted, true)
        shizukuLed = label("● SHIZUKU: ĐANG KIỂM TRA", 14f, muted, true)
        privilegeCard.addView(rootLed)
        space(privilegeCard, 5)
        privilegeCard.addView(shizukuLed)
        space(privilegeCard, 9)
        privilegeCard.addView(action("YÊU CẦU QUYỀN ROOT", cyan).apply {
            setOnClickListener { probeRoot() }
        })
        privilegeCard.addView(action("CẤP QUYỀN SHIZUKU", violet).apply {
            setOnClickListener {
                try {
                    if (Shizuku.pingBinder()) ShizukuAccess.request()
                    else status.text = "Hãy mở Shizuku và khởi chạy dịch vụ trước."
                } catch (e: Exception) {
                    status.text = "Chưa kết nối Shizuku: " + e.message
                }
                updatePrivilegeIndicators()
            }
        })
        privilegeCard.addView(label("Root: màn hình/FPS/CPU. Shizuku: màn hình/FPS; CPU và AUTO BOOT cần Root.", 11f, muted))
        space(layout, 14)

        val screen = panel()
        layout.addView(screen)
        screen.addView(label("01  DISPLAY OVERRIDE", 16f, cyan, true))
        space(screen, 10)
        screen.addView(label("Độ phân giải logic / cạnh ngắn", 13f, muted))
        resolution = selector(arrayOf("Không đổi", "720p", "1080p", "1440p"))
        screen.addView(resolution)
        space(screen, 10)
        screen.addView(label("Tỷ lệ màn hình", 13f, muted))
        ratio = selector(arrayOf("Theo màn gốc", "16:9", "16:10", "21:9"))
        screen.addView(ratio)
        space(screen, 10)
        dpiEnabled = Switch(this).apply {
            text = "Điều chỉnh DPI"
            setTextColor(Color.WHITE)
        }
        screen.addView(dpiEnabled)
        dpiText = label("420 DPI", 14f, cyan, true)
        screen.addView(dpiText)
        dpiSeek = SeekBar(this).apply {
            max = 48
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    dpiText.text = (160 + progress * 10).toString() + " DPI"
                }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) {}
            })
        }
        screen.addView(dpiSeek)
        space(layout, 13)

        val performance = panel()
        layout.addView(performance)
        performance.addView(label("02  GAMING PERFORMANCE", 16f, violet, true))
        space(performance, 10)
        performance.addView(label("Hz yêu cầu (giới hạn bởi màn hình/ROM)", 13f, muted))
        hz = selector(arrayOf("Không đổi", "60 Hz", "90 Hz", "120 Hz", "144 Hz", "165 Hz", "200 Hz"))
        performance.addView(hz)
        space(performance, 10)
        performance.addView(label("FPS GameManager (chỉ game/ROM hỗ trợ)", 13f, muted))
        fps = selector(arrayOf("Không đổi", "30 FPS", "45 FPS", "60 FPS", "90 FPS", "120 FPS", "144 FPS*", "165 FPS*"))
        performance.addView(fps)
        space(performance, 10)
        performance.addView(label("* 144/165 FPS là yêu cầu thử nghiệm, không bảo đảm game đạt tốc độ này.", 11f, muted))
        space(performance, 9)
        performance.addView(label("Chọn game cài sẵn", 13f, muted))
        gamePreset = selector(GameCatalog.labels)
        performance.addView(gamePreset)
        space(performance, 8)
        performance.addView(label("Package game (có thể sửa thủ công)", 13f, muted))
        gamePackage = EditText(this).apply {
            hint = "com.example.game"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            textSize = 14f
        }
        performance.addView(gamePackage)
        gamePreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) gamePackage.setText(GameCatalog.packages[position])
            }
        }
        space(layout, 13)

        val cpuCard = panel()
        layout.addView(cpuCard)
        cpuCard.addView(label("03  CPU PERFORMANCE PROFILES", 16f, violet, true))
        space(cpuCard, 8)
        cpuPreset = selector(arrayOf(
            "Không thay đổi xung nhịp",
            "ECO • Tiết kiệm • ≤70% mức xung gốc",
            "BALANCED • Ổn định • ≤90% mức xung gốc",
            "SUPER • Tối đa xung gốc • 100%"
        ))
        cpuCard.addView(cpuPreset)
        cpuCard.addView(label("Chỉ giới hạn trần CPU trong dải nhà sản xuất. KHÔNG ép vượt xung gốc, không bỏ bảo vệ nhiệt. Một số kernel sẽ khóa thao tác.", 11f, muted))
        space(layout, 13)

        val colorCard = panel()
        layout.addView(colorCard)
        colorCard.addView(label("04  IMAGE COLOR LAB (PREVIEW)", 16f, cyan, true))
        space(colorCard, 7)
        colorPreview = ImageView(this).apply {
            setImageBitmap(createColorSample())
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(-1, dp(120))
        }
        colorCard.addView(colorPreview)
        colorCard.addView(label("Bão hòa • 0–200%", 12f, muted))
        saturationSeek = SeekBar(this).apply { max = 200 }
        colorCard.addView(saturationSeek)
        colorCard.addView(label("Độ tươi (tăng màu) • 0–100", 12f, muted))
        vibranceSeek = SeekBar(this).apply { max = 100 }
        colorCard.addView(vibranceSeek)
        colorCard.addView(label("Tương phản • 50–150%", 12f, muted))
        contrastSeek = SeekBar(this).apply { max = 100 }
        colorCard.addView(contrastSeek)
        colorCard.addView(label("Độ sáng • -50 đến +50", 12f, muted))
        brightnessSeek = SeekBar(this).apply { max = 100 }
        colorCard.addView(brightnessSeek)
        val colorListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { updateColorPreview() }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }
        for (seek in arrayOf(saturationSeek, vibranceSeek, contrastSeek, brightnessSeek))
            seek.setOnSeekBarChangeListener(colorListener)
        colorCard.addView(action("MỞ CÀI ĐẶT MÀU MÀN HÌNH", violet).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }
        })
        colorCard.addView(label("Thanh chỉnh màu chỉ mô phỏng trong preview. Chưa chỉnh màu toàn hệ thống vì API Samsung khác theo ROM.", 11f, muted))
        space(layout, 13)

        val boot = panel()
        layout.addView(boot)
        boot.addView(label("05  AUTO BOOT", 16f, cyan, true))
        autoBoot = Switch(this).apply {
            text = "Tự áp dụng sau khi khởi động lại"
            setTextColor(Color.WHITE)
        }
        boot.addView(autoBoot)
        boot.addView(label("Dùng BOOT_COMPLETED + JobScheduler; yêu cầu cấp SU vĩnh viễn. Không mở UI lúc khởi động.", 12f, muted))
        space(layout, 13)

        status = label(getSharedPreferences("boot_result", MODE_PRIVATE).getString("last", "READY • Chưa có kết quả AUTO BOOT") ?: "READY", 12f, muted)
        layout.addView(status)
        space(layout, 12)
        val apply = action("⚡ APPLY GAMING PROFILE", cyan)
        val reset = action("↶ RESET DISPLAY OVERRIDES", violet)
        layout.addView(apply)
        space(layout, 10)
        layout.addView(reset)
        space(layout, 12)
        layout.addView(label("Lưu ý: Đây là điều khiển màn hình logic mặc định, không phải chế độ HDMI/DeX hay mở khóa FPS game.", 11f, muted))

        restoreFields(Profiles.load(this))
        try {
            Sui.init(packageName)
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) { }
        updatePrivilegeIndicators()
        autoBoot.setOnCheckedChangeListener { _, checked ->
            if (!restoring && !checked) {
                Profiles.save(this, Profiles.load(this).copy(autoBoot = false))
                BootJobs.cancel(this)
            }
            if (!restoring) status.text = if (checked) "AUTO BOOT: bấm APPLY thành công để kích hoạt" else "AUTO BOOT OFF"
        }
        apply.setOnClickListener {
            val config = readFields()
            apply.isEnabled = false
            reset.isEnabled = false
            status.text = "Đang áp dụng • Root / Shizuku..."
            executor.execute {
                val result = DisplayRoot.apply(this, config)
                val saved = config.copy(autoBoot = config.autoBoot && RootAccess.status())
                if (result.ok) Profiles.save(this, saved)
                runOnUiThread {
                    apply.isEnabled = true
                    reset.isEnabled = true
                    if (result.ok) { restoring = true; autoBoot.isChecked = saved.autoBoot; restoring = false }
                    status.text = (if (result.ok) "✓ " else "⚠ ") + result.info +
                        if (config.autoBoot && !saved.autoBoot) "\nAUTO BOOT cần Root." else ""
                }
            }
        }
        reset.setOnClickListener {
            val last = Profiles.load(this)
            Profiles.save(this, last.copy(autoBoot = false))
            BootJobs.cancel(this)
            autoBoot.isChecked = false
            apply.isEnabled = false
            reset.isEnabled = false
            status.text = "Đang reset..."
            executor.execute {
                val result = DisplayRoot.reset(last)
                if (result.ok) Profiles.save(this, Profile())
                runOnUiThread {
                    apply.isEnabled = true
                    reset.isEnabled = true
                    if (result.ok) restoreFields(Profile())
                    status.text = (if (result.ok) "✓ " else "⚠ ") + result.info
                }
            }
        }
    }

    private fun readFields(): Profile = Profile(
        resolution = intArrayOf(0, 720, 1080, 1440)[resolution.selectedItemPosition],
        ratio = ratio.selectedItemPosition,
        dpiEnabled = dpiEnabled.isChecked,
        dpi = 160 + dpiSeek.progress * 10,
        hz = intArrayOf(0, 60, 90, 120, 144, 165, 200)[hz.selectedItemPosition],
        fps = FpsOptions.values[fps.selectedItemPosition],
        gamePackage = gamePackage.text.toString().trim(),
        cpuMode = cpuPreset.selectedItemPosition,
        saturation = saturationSeek.progress,
        vibrance = vibranceSeek.progress,
        contrast = contrastSeek.progress + 50,
        brightness = brightnessSeek.progress - 50,
        autoBoot = autoBoot.isChecked
    )

    private fun restoreFields(p: Profile) {
        restoring = true
        resolution.setSelection(intArrayOf(0, 720, 1080, 1440).indexOf(p.resolution).coerceAtLeast(0))
        ratio.setSelection(p.ratio.coerceIn(0, 3))
        dpiEnabled.isChecked = p.dpiEnabled
        dpiSeek.progress = (p.dpi.coerceIn(160, 640) - 160) / 10
        hz.setSelection(intArrayOf(0, 60, 90, 120, 144, 165, 200).indexOf(p.hz).coerceAtLeast(0))
        fps.setSelection(FpsOptions.values.indexOf(p.fps).coerceAtLeast(0))
        gamePreset.setSelection(GameCatalog.position(p.gamePackage))
        gamePackage.setText(p.gamePackage)
        cpuPreset.setSelection(p.cpuMode.coerceIn(0, 3))
        saturationSeek.progress = p.saturation.coerceIn(0, 200)
        vibranceSeek.progress = p.vibrance.coerceIn(0, 100)
        contrastSeek.progress = (p.contrast - 50).coerceIn(0, 100)
        brightnessSeek.progress = (p.brightness + 50).coerceIn(0, 100)
        updateColorPreview()
        autoBoot.isChecked = p.autoBoot
        restoring = false
    }

    override fun onResume() {
        super.onResume()
        if (::rootLed.isInitialized) {
            updatePrivilegeIndicators()
            probeRoot()
        }
    }

    private fun updatePrivilegeIndicators() {
        if (!::shizukuLed.isInitialized) return
        CommandRunner.connect(this)
        val state = ShizukuAccess.status()
        shizukuLed.text = "● SHIZUKU: " + state
        shizukuLed.setTextColor(if (state.startsWith("ĐÃ CẤP")) Color.rgb(68, 235, 160) else muted)
    }

    private fun probeRoot() {
        if (!::rootLed.isInitialized) return
        rootLed.text = "● ROOT: ĐANG YÊU CẦU QUYỀN..."
        rootLed.setTextColor(muted)
        executor.execute {
            val granted = RootAccess.status()
            runOnUiThread {
                if (::rootLed.isInitialized) {
                    rootLed.text = if (granted) "● ROOT: ĐÃ KẾT NỐI" else "● ROOT: CHƯA CẤP QUYỀN"
                    rootLed.setTextColor(if (granted) Color.rgb(68, 235, 160) else Color.rgb(255, 145, 145))
                }
            }
        }
    }

    private fun createColorSample(): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, 640f, 240f,
            intArrayOf(Color.rgb(14, 44, 110), Color.rgb(15, 212, 245), Color.rgb(255, 113, 170)),
            null, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, 640f, 240f, paint)
        paint.shader = null
        paint.color = Color.rgb(251, 185, 51)
        canvas.drawCircle(120f, 94f, 70f, paint)
        paint.color = Color.rgb(58, 33, 110)
        canvas.drawCircle(480f, 158f, 86f, paint)
        paint.color = Color.WHITE
        paint.textSize = 43f
        paint.isFakeBoldText = true
        canvas.drawText("DUYKHAIDEX", 174f, 126f, paint)
        paint.textSize = 23f
        canvas.drawText("COLOR LAB", 232f, 156f, paint)
        return bitmap
    }

    private fun updateColorPreview() {
        if (!::colorPreview.isInitialized || !::saturationSeek.isInitialized ||
            !::vibranceSeek.isInitialized || !::contrastSeek.isInitialized ||
            !::brightnessSeek.isInitialized) return

        // Preview-only approximation: vibrance acts as a gentle saturation boost.
        val saturation = (saturationSeek.progress / 100f + vibranceSeek.progress / 500f)
            .coerceIn(0f, 2.2f)
        val contrast = (contrastSeek.progress + 50) / 100f
        val brightness = (brightnessSeek.progress - 50).toFloat()
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        val offset = (1f - contrast) * 128f + brightness
        matrix.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )))
        colorPreview.colorFilter = ColorMatrixColorFilter(matrix)
    }

    private fun selector(values: Array<String>) = Spinner(this).apply {
        val adapt = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, values)
        adapt.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        adapter = adapt
        minimumHeight = dp(48)
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.rgb(38, 69, 105))
        }
    }
    private fun stat(head: String, value: String): View = panel().apply {
        addView(label(head, 11f, cyan, true))
        addView(label(value, 16f, Color.WHITE, true))
    }
    private fun action(value: String, color: Int) = Button(this).apply {
        text = value
        textSize = 13f
        isAllCaps = false
        setTextColor(bgColor)
        setTypeface(null, Typeface.BOLD)
        minimumHeight = dp(54)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(13).toFloat()
        }
    }
    private fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        try {
            CommandRunner.disconnect()
            Shizuku.removeBinderReceivedListener(shizukuBinderListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}
        executor.shutdown()
        super.onDestroy()
    }
}
