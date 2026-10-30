package com.phonerouter.hotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.phonerouter.hotspot.hotspot.HotspotManager
import com.phonerouter.hotspot.hotspot.ScheduleManager
import com.phonerouter.hotspot.web.WebServer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that hosts the web management server and, on start, brings the
 * hotspot up (when auto-start is enabled). Kept alive so the web UI stays reachable.
 */
class HotspotService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var webServer: WebServer? = null
    private lateinit var manager: HotspotManager

    override fun onCreate() {
        super.onCreate()
        manager = HotspotManager(this)
        startForegroundSafe(getString(R.string.service_starting))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val autoStart = intent?.getBooleanExtra(EXTRA_AUTOSTART, false) ?: false
        worker.execute {
            val rooted = manager.init()
            if (rooted) {
                startWebServer()
                if ((autoStart || manager.prefs.autoStartHotspot) && !manager.isActive()) {
                    manager.setHotspot(true)
                }
                // re-arm scheduled on/off (alarms are cleared across reboots)
                runCatching { ScheduleManager(applicationContext).rescheduleAll() }
                startUsagePolling()
            }
            updateNotification(statusLine(rooted))
        }
        return START_STICKY
    }

    /** Refresh mobile-data usage from the system every 30 minutes. */
    private fun startUsagePolling() {
        scheduler.scheduleWithFixedDelay({
            runCatching { manager.refreshDataUsage() }
        }, 0, 30, TimeUnit.MINUTES)
    }

    private fun startWebServer() {
        if (webServer != null) return
        try {
            val server = WebServer(
                applicationContext,
                manager,
                manager.prefs.webPort,
                manager.prefs.webPassword,
            )
            server.start(NanoTimeout, false)
            webServer = server
        } catch (t: Throwable) {
            // leave webServer null; status line will reflect it
        }
    }

    private fun statusLine(rooted: Boolean): String {
        if (!rooted) return getString(R.string.no_root_features_unavailable)
        val port = manager.prefs.webPort
        val web = if (webServer != null) {
            "http://${Prefs.GATEWAY}:$port"
        } else {
            getString(R.string.web_not_started)
        }
        return getString(R.string.status_management_address, web)
    }

    private fun startForegroundSafe(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        webServer?.stop()
        webServer = null
        scheduler.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "hotspot_service"
        private const val NOTIF_ID = 1001
        private const val NanoTimeout = 5000
        const val EXTRA_AUTOSTART = "autostart"

        fun start(context: Context, autoStart: Boolean) {
            val intent = Intent(context, HotspotService::class.java).putExtra(EXTRA_AUTOSTART, autoStart)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
