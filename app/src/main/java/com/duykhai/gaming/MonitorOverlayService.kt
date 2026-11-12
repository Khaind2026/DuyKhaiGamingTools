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
    private var output: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (busy.compareAndSet(false, true)) {
                val packageName = Profiles.load(this@MonitorOverlayService).gamePackage
                worker.execute {
                    val data = try { PerformanceMonitor.sample(packageName, hasRoot) }
                        catch (_: Exception) { MonitorSnapshot(null, null, emptyList(), "Không thể đọc cảm biến") }
                    ui.post {
                        if (active) {
                            output?.text = "FPS: " + PerformanceMonitor.fpsLabel(data.fps) +
                                "\nCPU: " + PerformanceMonitor.temperatureLabel(data.temperatureC) +
                                "\nXUNG: " + PerformanceMonitor.clocksLabel(data.cpuClocks)
                        }
                        busy.set(false)
                    }
                }
            }
            ui.postDelayed(this, 3_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        createNotification()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hasRoot = RootAccess.status()
        if (!hasRoot) CommandRunner.connect(this)
        try {
            showHud()
            active = true
            ui.post(tick)
        } catch (_: Exception) {
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
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("DuyKhaiDex • Gaming HUD")
            .setContentText("FPS ước tính • Nhiệt độ • Xung nhịp CPU")
            .setContentIntent(open)
            .setOngoing(true)
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
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 7, 17, 33))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(0, 215, 255))
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = label("DUYKHAIDEX • HUD", 12f).apply { setTextColor(Color.rgb(0, 220, 255)) }
        val close = label("✕", 17f).apply {
            contentDescription = "Đóng màn hình theo dõi"
            setOnClickListener { stopSelf() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(34), 1f))
        header.addView(close)
        container.addView(header)
        output = label("FPS: —\nCPU: —\nXUNG: —", 12f)
        container.addView(output)
        container.addView(label("FPS ≠ Hz • chỉ là ước tính", 10f).apply {
            setTextColor(Color.rgb(170, 190, 210))
        })
        val lp = WindowManager.LayoutParams(
            dp(288),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(110)
        }
        title.setOnTouchListener(object : View.OnTouchListener {
            private var originX = 0
            private var originY = 0
            private var startX = 0f
            private var startY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        originX = lp.x; originY = lp.y
                        startX = event.rawX; startY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = originX + (event.rawX - startX).toInt()
                        lp.y = originY + (event.rawY - startY).toInt()
                        try { windowManager.updateViewLayout(container, lp) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.performClick()
                        return true
                    }
                }
                return false
            }
        })
        windowManager.addView(container, lp)
        overlay = container
        params = lp
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onDestroy() {
        active = false
        ui.removeCallbacks(tick)
        overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlay = null
        output = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
