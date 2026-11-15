package com.duykhai.gaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Opt-in DeX mode maintenance. Only re-requests modes advertised by the
 * Android display manager, and reports when Samsung refuses the mode.
 */
class PcmDexMaintainService : Service() {
    companion object {
        const val ACTION_START = "com.duykhai.gaming.action.PCM_DEX_START"
        const val ACTION_STOP = "com.duykhai.gaming.action.PCM_DEX_STOP"
        private const val CHANNEL = "pcm_dex_hold"
        private const val NOTIFICATION_ID = 6112
        @Volatile var active = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var targetHz = 120
    private var failures = 0
    private val work = object : Runnable {
        override fun run() {
            if (!active) return
            if (busy.compareAndSet(false, true)) {
                worker.execute {
                    val message = try {
                        val output = DexDisplayControl.snapshot(this@PcmDexMaintainService)
                        if (output == null) {
                            "Đang chờ HDMI / DeX"
                        } else if (abs(output.activeHz - targetHz) <= 0.6f) {
                            failures = 0
                            "HDMI " + output.activeWidth + "×" + output.activeHeight + " • " + targetHz + "Hz"
                        } else if (!RootAccess.status() && !CommandRunner.ready()) {
                            "Cần Root / Shizuku còn hoạt động"
                        } else {
                            val result = DexDisplayControl.request(this@PcmDexMaintainService, targetHz)
                            if (result.startsWith("ĐÃ XÁC NHẬN")) failures = 0 else failures++
                            if (failures >= 3) "ROM chưa chấp nhận " + targetHz + "Hz; kiểm tra HDMI"
                            else result.lineSequence().firstOrNull().orEmpty().take(75)
                        }
                    } catch (e: Exception) {
                        failures++
                        "Không kiểm tra được HDMI: " + (e.message ?: "lỗi")
                    }
                    getSharedPreferences("pcm_dex", MODE_PRIVATE).edit()
                        .putString("last_status", message).apply()
                    handler.post {
                        if (active) {
                            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, notification(message))
                        }
                        busy.set(false)
                    }
                }
            }
            handler.postDelayed(this, 9000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel(CHANNEL,
            "PCM Gaming • DeX HDMI", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(message: String): Notification {
        val exit = Intent(this, PcmDexMaintainService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 99, exit,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = Intent(this, PcmGamingActivity::class.java)
        val launch = PendingIntent.getActivity(this, 98, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pcm_gaming)
            .setContentTitle("PCM Gaming • Duy Khải • DeX " + targetHz + "Hz")
            .setContentText(message)
            .setContentIntent(launch)
            .addAction(Notification.Action.Builder(null, "DỪNG", pending).build())
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val target = intent?.getIntExtra("target_hz", 120) ?: 120
        if (target !in setOf(60, 90, 120, 144, 165, 180, 200)) {
            stopSelf()
            return START_NOT_STICKY
        }
        targetHz = target
        active = true
        failures = 0
        getSharedPreferences("pcm_dex", MODE_PRIVATE).edit()
            .putInt("target_hz", target)
            .putString("last_status", "Đang yêu cầu DeX " + target + "Hz").apply()
        try {
            startForeground(NOTIFICATION_ID, notification("Đang kiểm tra HDMI thực tế"))
        } catch (_: Exception) {
            active = false
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(work)
        handler.post(work)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        handler.removeCallbacks(work)
        worker.shutdownNow()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        super.onDestroy()
    }
}
