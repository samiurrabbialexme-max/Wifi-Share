package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.*
import com.example.server.FtpServer
import com.example.server.HttpWebServer
import com.example.server.NetworkUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FtpServerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var ftpServer: FtpServer? = null
    private var webServer: HttpWebServer? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _serverStatus = MutableStateFlow(ServerStatus.STOPPED)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus

    private val _activeClientsCount = MutableStateFlow(0)
    val activeClientsCount: StateFlow<Int> = _activeClientsCount

    private val _serverConfig = MutableStateFlow(ServerConfig())
    val serverConfig: StateFlow<ServerConfig> = _serverConfig

    val logFlow = MutableSharedFlow<ServerLog>(extraBufferCapacity = 200)

    inner class LocalBinder : Binder() {
        fun getService(): FtpServerService = this@FtpServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private var clientMonitorJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVER -> {
                stopServer()
                stopSelf()
            }
            else -> {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_NOT_STICKY
    }

    fun startServer(config: ServerConfig): Boolean {
        stopServer()

        _serverConfig.value = config
        _serverStatus.value = ServerStatus.STARTING

        acquireLocks(config.keepScreenOn)

        var ftpStarted = true
        var webStarted = true

        if (config.enableFtp) {
            ftpServer = FtpServer(this, config, logFlow)
            ftpStarted = ftpServer?.start() ?: false
        }

        if (config.enableWeb) {
            webServer = HttpWebServer(this, config, logFlow)
            webStarted = webServer?.start() ?: false
        }

        if ((config.enableFtp && ftpStarted) || (config.enableWeb && webStarted)) {
            _serverStatus.value = ServerStatus.RUNNING
            startForeground(NOTIFICATION_ID, buildNotification())
            startMonitoringClients()
            return true
        } else {
            _serverStatus.value = ServerStatus.ERROR
            releaseLocks()
            return false
        }
    }

    fun stopServer() {
        clientMonitorJob?.cancel()
        clientMonitorJob = null

        ftpServer?.stop()
        webServer?.stop()
        ftpServer = null
        webServer = null

        _serverStatus.value = ServerStatus.STOPPED
        _activeClientsCount.value = 0
        releaseLocks()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startMonitoringClients() {
        clientMonitorJob?.cancel()
        clientMonitorJob = serviceScope.launch {
            while (_serverStatus.value == ServerStatus.RUNNING) {
                val total = (ftpServer?.activeClientCount ?: 0) + (webServer?.activeClientCount ?: 0)
                _activeClientsCount.value = total
                delay(1000)
            }
        }
    }

    private fun acquireLocks(keepAwake: Boolean) {
        if (keepAwake) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wi-FiShare::ServerWakeLock").apply {
                acquire(10 * 60 * 1000L /* 10 minutes max default */)
            }
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Wi-FiShare::WifiLock").apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wi-Fi Folder Share Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Wi-Fi share server running state and address"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val ip = NetworkUtils.getWifiIpAddress(this) ?: "127.0.0.1"
        val cfg = _serverConfig.value

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FtpServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )

        val contentText = "HTTP: http://$ip:${cfg.webPort} | FTP: ftp://$ip:${cfg.ftpPort}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wi-Fi Folder Share Running")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "wifi_folder_share_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVER = "com.example.ACTION_STOP_SERVER"
    }
}
