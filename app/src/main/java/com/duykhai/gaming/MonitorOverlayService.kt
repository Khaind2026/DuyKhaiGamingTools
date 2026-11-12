package com.duykhai.gaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-started foreground overlay. Stops when the user closes it or Android
 * revokes overlay permission. Samples only while the overlay is visible.
 */
class MonitorOverlayService : Service() {
    companion object {
        const val ACTION_START = "com.duykhai.gaming.START_MONITOR"
        const val ACTION_STOP = "com.duykhai.gaming.STOP_MONITOR"
        private const val CHANNEL = "duykhaidex_monitor"
        private const val NOTIFICATION_ID = 711
        @Volatile var active = false
            private set
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var hasRoot = false
    private var externalCached: PerformanceMonitor.ExternalReadout? = null
    private var externalAt = 0L
    private var output: TextView? = null
    private var titleView: TextView? = null
    private var detailView: TextView? = null
    private var currentScale = -1

    private fun scalePreference(): Int =
        getSharedPreferences("hud_prefs", MODE_PRIVATE).getInt("scale", 100).coerceIn(60, 160)

    private fun changeScale(step: Int) {
        val value = (scalePreference() + step).coerceIn(60, 160)
        getSharedPreferences("hud_prefs", MODE_PRIVATE).edit().putInt("scale", value).apply()
        updateScale()
    }

    private fun updateScale() {
        val scale = scalePreference()
        if (currentScale == scale && params?.alpha == getSharedPreferences("hud_prefs", MODE_PRIVATE)
                .getInt("alpha", 85).coerceIn(40, 100) / 100f) return
        val layout = params ?: return
        val view = overlay ?: return
        currentScale = scale
        val factor = scale / 100f
        val screenWidth = resources.displayMetrics.widthPixels
        layout.width = (dp(265) * factor).roundToInt().coerceAtMost(screenWidth - dp(16))
        layout.alpha = getSharedPreferences("hud_prefs", MODE_PRIVATE).getInt("alpha", 85)
            .coerceIn(40, 100) / 100f
        output?.textSize = 14f * factor
        titleView?.textSize = 12f * factor
        detailView?.textSize = 10f * factor
        try { windowManager.updateViewLayout(view, layout) } catch (_: Exception) {}
    }


    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (busy.compareAndSet(false, true)) {
                worker.execute {
                    val pref = getSharedPreferences("hud_prefs", MODE_PRIVATE)
                    val source = pref.getString("source", "external") ?: "external"
                    val pkg = pref.getString("game", Profiles.load(this@MonitorOverlayService).gamePackage).orEmpty()
                    val root = RootAccess.status()
                    val cpu = try {
                        PerformanceMonitor.sample(if (source == "game") pkg else "",
                            root, this@MonitorOverlayService)
                    } catch (_: Exception) { MonitorSnapshot(null, null, emptyList(), "Không đọc được CPU") }
                    val ext = if (source == "external") try {
                        val now = System.currentTimeMillis()
                        if (externalCached == null || now - externalAt >= 3500L) {
                            externalCached = PerformanceMonitor.external(this@MonitorOverlayService, root)
                            externalAt = now
                        }
                        externalCached
                    } catch (_: Exception) { externalCached } else null
                    ui.post {
                        if (active) {
                            val lines = mutableListOf<String>()
                            if (pref.getBoolean("fps", true)) {
                                lines.add(if (source == "external") {
                                    "FPS HDMI: " + PerformanceMonitor.fpsLabel(ext?.presentedLayerFps)
                                } else "FPS GAME: " + PerformanceMonitor.fpsLabel(cpu.fps))
                            }
                            if (source == "external") {
                                lines.add("HDMI Hz: " +
                                    (ext?.refreshHz?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "—"))
                            }
                            if (pref.getBoolean("temp", true)) {
                                lines.add("CPU hotspot: " + PerformanceMonitor.temperatureLabel(cpu.temperatureC))
                                cpu.sensorName?.let { lines.add("Nhiệt từ: " + it.take(34)) }
                            }
                            if (pref.getBoolean("clock", true)) {
                                lines.add("XUNG: " + PerformanceMonitor.clocksLabel(cpu.cpuClocks))
                                cpu.clockSource?.let { lines.add("Nguồn xung: " + it.take(32)) }
                            }
                            if (cpu.temperatureC == null && cpu.cpuClocks.isEmpty())
                                lines.add("Chưa đọc được cảm biến • kiểm tra quyền ROM")
                            if (source == "external" && ext?.presentedLayerFps == null)
                                lines.add("FPS HDMI: ROM không cấp dữ liệu layer")
                            output?.text = lines.joinToString("\n").ifBlank { "Chưa chọn thông số" }
                        }
                        busy.set(false)
                    }
                }
            }
            updateScale()
            ui.postDelayed(this, 1500L)
        }
    }

    private fun persistStatus(message: String) {
        getSharedPreferences("hud_diagnostics", MODE_PRIVATE).edit()
            .putString("last_status", message)
            .putLong("status_at", System.currentTimeMillis())
            .apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            persistStatus("Đã tắt HUD theo yêu cầu")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            persistStatus("Thiếu quyền Hiển thị trên ứng dụng khác")
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        try {
            createNotification()
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            hasRoot = RootAccess.status()
            if (!hasRoot) CommandRunner.connect(this)
            showHud()
            active = true
            persistStatus("HUD đang chạy • 1,5 giây/lần • chạm giữ chữ để di chuyển")
            ui.post(tick)
        } catch (error: Exception) {
            persistStatus("Không bật được HUD: " + error.javaClass.simpleName + " • " +
                (error.message ?: "Không có thông tin").take(220))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "DuyKhaiDex gaming monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MonitorOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("DuyKhaiDex • Gaming HUD")
            .setContentText("FPS ước tính • Nhiệt độ • Xung nhịp CPU")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TẮT HUD", stop)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun label(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(3), dp(10), dp(3))
    }

    private fun showHud() {
        val text = label("FPS HDMI: —\nHDMI Hz: —\nCPU: —\nXUNG: —", 12f).apply {
            // Floating text only: no frame, panel, border, or caption.
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setTextColor(Color.rgb(99, 250, 120))
            setShadowLayer(5f, 1f, 1f, Color.rgb(9, 2, 28))
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            contentDescription = "Thông số hiệu năng. Kéo để đổi vị trí."
        }
        output = text
        val lp = WindowManager.LayoutParams(
            dp(265),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (getSharedPreferences("hud_prefs", MODE_PRIVATE).getInt("corner", 1)) {
                0 -> Gravity.TOP or Gravity.START
                1 -> Gravity.TOP or Gravity.END
                2 -> Gravity.BOTTOM or Gravity.START
                else -> Gravity.BOTTOM or Gravity.END
            }
            x = dp(16)
            y = dp(110)
        }
        text.setOnTouchListener(object : View.OnTouchListener {
            private var x = 0
            private var y = 0
            private var firstX = 0f
            private var firstY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        x = lp.x; y = lp.y
                        firstX = event.rawX; firstY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (x + (event.rawX - firstX)).toInt().coerceAtLeast(0)
                        lp.y = (y + (event.rawY - firstY)).toInt().coerceAtLeast(0)
                        try { windowManager.updateViewLayout(text, lp) } catch (_: Exception) { }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.performClick()
                        return true
                    }
                }
                return false
            }
        })
        windowManager.addView(text, lp)
        overlay = text
        params = lp
        updateScale()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onDestroy() {
        active = false
        ui.removeCallbacks(tick)
        overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlay = null
        output = null
        titleView = null
        detailView = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
