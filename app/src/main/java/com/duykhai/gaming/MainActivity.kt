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
            .putBoolean("autoBoot", c.autoBoot)
            .commit()
    }
}

object DisplayRoot {
    private val hzAllowed = setOf(60, 90, 120, 144, 165, 200)
    private val fpsAllowed = setOf(30, 45, 60, 90, 120)
    private val gamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    data class Result(val ok: Boolean, val info: String)

    fun apply(ctx: Context, p: Profile): Result {
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
            commands.add("cmd game set --fps " + p.fps + " " + p.gamePackage)
        }
        if (commands.isEmpty()) return Result(false, "Chưa chọn thiết lập nào")
        return runCommands(commands)
    }

    fun reset(p: Profile): Result {
        val commands = mutableListOf(
            "wm size reset",
            "wm density reset",
            "settings delete system peak_refresh_rate",
            "settings delete system min_refresh_rate"
        )
        if (gamePattern.matches(p.gamePackage)) commands.add("cmd game reset " + p.gamePackage)
        return runCommands(commands)
    }

    private fun runCommands(commands: List<String>): Result {
        val failures = mutableListOf<String>()
        commands.forEach { command ->
            try {
                val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                if (!process.waitFor(8, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    failures.add(command.substringBefore(" ") + ": timeout/SU")
                } else if (process.exitValue() != 0) {
                    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    failures.add(command.substringBefore(" ") + ": " + output.take(120))
                }
            } catch (error: Exception) {
                failures.add("SU: " + (error.message ?: "không có quyền"))
            }
        }
        return if (failures.isEmpty())
            Result(true, "Đã gửi các lệnh. Hãy kiểm tra DPI/Hz/FPS thực tế.")
        else Result(false, "Có lệnh không thành công: " + failures.joinToString("; ").take(300))
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
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                val config = Profiles.load(this)
                if (config.autoBoot) {
                    val result = DisplayRoot.apply(this, config)
                    Log.i("DuyKhaiBoot", "AutoBoot success=" + result.ok + " " + result.info)
                }
            } catch (ex: Exception) {
                Log.e("DuyKhaiBoot", "AutoBoot error", ex)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
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
    private lateinit var autoBoot: Switch
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

        layout.addView(label("DUYKHAI // GAMING", 25f, cyan, true))
        layout.addView(label("SAMSUNG ROOT DISPLAY ENGINE  •  v0.1", 11f, muted))
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
        performance.addView(label("FPS GameManager (chỉ game hỗ trợ)", 13f, muted))
        fps = selector(arrayOf("Không đổi", "30 FPS", "45 FPS", "60 FPS", "90 FPS", "120 FPS"))
        performance.addView(fps)
        space(performance, 10)
        performance.addView(label("Package game", 13f, muted))
        gamePackage = EditText(this).apply {
            hint = "com.example.game"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            textSize = 14f
        }
        performance.addView(gamePackage)
        space(layout, 13)

        val boot = panel()
        layout.addView(boot)
        boot.addView(label("03  AUTO BOOT", 16f, cyan, true))
        autoBoot = Switch(this).apply {
            text = "Tự áp dụng sau khi khởi động lại"
            setTextColor(Color.WHITE)
        }
        boot.addView(autoBoot)
        boot.addView(label("Dùng BOOT_COMPLETED + JobScheduler; yêu cầu cấp SU vĩnh viễn. Không mở UI lúc khởi động.", 12f, muted))
        space(layout, 13)

        status = label("READY  •  Chưa áp dụng", 12f, muted)
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
        autoBoot.setOnCheckedChangeListener { _, checked ->
            Profiles.save(this, readFields())
            if (!checked) BootJobs.cancel(this)
            status.text = if (checked) "AUTO BOOT ON  •  Nhớ bấm APPLY" else "AUTO BOOT OFF"
        }
        apply.setOnClickListener {
            val config = readFields()
            Profiles.save(this, config)
            status.text = "Đang áp dụng bằng quyền SU..."
            executor.execute {
                val result = DisplayRoot.apply(this, config)
                runOnUiThread { status.text = (if (result.ok) "✓ " else "⚠ ") + result.info }
            }
        }
        reset.setOnClickListener {
            val last = Profiles.load(this)
            Profiles.save(this, last.copy(autoBoot = false))
            BootJobs.cancel(this)
            status.text = "Đang reset..."
            executor.execute {
                val result = DisplayRoot.reset(last)
                if (result.ok) Profiles.save(this, Profile())
                runOnUiThread {
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
        fps = intArrayOf(0, 30, 45, 60, 90, 120)[fps.selectedItemPosition],
        gamePackage = gamePackage.text.toString().trim(),
        autoBoot = autoBoot.isChecked
    )

    private fun restoreFields(p: Profile) {
        resolution.setSelection(intArrayOf(0, 720, 1080, 1440).indexOf(p.resolution).coerceAtLeast(0))
        ratio.setSelection(p.ratio.coerceIn(0, 3))
        dpiEnabled.isChecked = p.dpiEnabled
        dpiSeek.progress = (p.dpi.coerceIn(160, 640) - 160) / 10
        hz.setSelection(intArrayOf(0, 60, 90, 120, 144, 165, 200).indexOf(p.hz).coerceAtLeast(0))
        fps.setSelection(intArrayOf(0, 30, 45, 60, 90, 120).indexOf(p.fps).coerceAtLeast(0))
        gamePackage.setText(p.gamePackage)
        autoBoot.isChecked = p.autoBoot
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
        executor.shutdown()
        super.onDestroy()
    }
}
