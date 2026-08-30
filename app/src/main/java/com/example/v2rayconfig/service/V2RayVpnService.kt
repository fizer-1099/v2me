package com.example.v2rayconfig.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.v2rayconfig.model.ServerConfig
import com.example.v2rayconfig.ui.MainActivity
import com.example.v2rayconfig.util.ConfigRepository
import com.example.v2rayconfig.util.FailoverSelector
import com.example.v2rayconfig.util.XrayEnv
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class V2RayVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val ACTION_START = "com.example.v2rayconfig.START"
        const val ACTION_STOP = "com.example.v2rayconfig.STOP"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_CONFIG_ID = "config_id"
        const val NOTIFICATION_CHANNEL_ID = "v2ray_vpn_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_ACTIVE_CONFIG_CHANGED = "com.example.v2rayconfig.ACTIVE_CONFIG_CHANGED"
        const val EXTRA_NEW_CONFIG_ID = "new_config_id"
        const val EXTRA_NEW_CONFIG_REMARK = "new_config_remark"
        const val EXTRA_REASON = "reason"

        private const val LOCAL_PORT = 10808
        private const val HEALTH_CHECK_INTERVAL_SEC = 20L
        private const val HEALTH_CHECK_TIMEOUT_MS = 6000
        private const val MAX_CONSECUTIVE_FAILURES = 2
        private const val FAILOVER_COOLDOWN_MS = 5 * 60 * 1000L
        private const val HEALTH_CHECK_URL = "https://cp.cloudflare.com/generate_204"

        var isRunning = false
            private set
        var activeConfigId: String? = null
            private set
        var activeConfigRemark: String? = null
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var consecutiveFailures = 0
    private var monitorExecutor: ScheduledExecutorService? = null
    private lateinit var repo: ConfigRepository

    override fun onCreate() {
        super.onCreate()
        repo = ConfigRepository(this)
        XrayEnv.ensureInitialized(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: return START_NOT_STICKY
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
                startVpn(configJson, configId)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(configJson: String, configId: String?) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        val builder = Builder()
            .setSession("V2RayConfigApp")
            .addAddress("10.10.14.1", 30)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
        }

        vpnInterface = builder.establish()
        val tunFd = vpnInterface?.fd ?: run {
            updateNotification("Failed to establish VPN interface")
            return
        }

        coreController = Libv2ray.newCoreController(this)
        try {
            coreController?.startLoop(configJson, tunFd)
        } catch (e: Exception) {
            updateNotification("Failed to start: ${e.message}")
            return
        }

        activeConfigId = configId
        activeConfigRemark = configId?.let { id -> repo.getAll().find { it.id == id }?.remark }
        isRunning = true
        consecutiveFailures = 0

        startForeground(NOTIFICATION_ID, buildNotification("Connected — ${activeConfigRemark ?: ""}"))
        startHealthMonitor()
    }

    private fun restartEngine(configJson: String): Boolean {
        val tunFd = vpnInterface?.fd ?: return false
        return try {
            coreController?.stopLoop()
            coreController = Libv2ray.newCoreController(this)
            coreController?.startLoop(configJson, tunFd)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun stopVpn() {
        stopHealthMonitor()
        try { coreController?.stopLoop() } catch (e: Exception) { }
        coreController = null
        vpnInterface?.close()
        vpnInterface = null
        isRunning = false
        activeConfigId = null
        activeConfigRemark = null
        stopForeground(true)
        stopSelf()
    }

    private fun startHealthMonitor() {
        stopHealthMonitor()
        monitorExecutor = Executors.newSingleThreadScheduledExecutor().also { exec ->
            exec.scheduleWithFixedDelay(
                { runHealthCheck() },
                HEALTH_CHECK_INTERVAL_SEC, HEALTH_CHECK_INTERVAL_SEC, TimeUnit.SECONDS
            )
        }
    }

    private fun stopHealthMonitor() {
        monitorExecutor?.shutdownNow()
        monitorExecutor = null
    }

    private fun runHealthCheck() {
        if (!isRunning) return
        val healthy = checkHealthOnce()
        if (healthy) {
            consecutiveFailures = 0
            activeConfigId?.let { repo.clearConfigFailure(it) }
            return
        }
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            consecutiveFailures = 0
            attemptFailover()
        }
    }

    private fun checkHealthOnce(): Boolean {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", LOCAL_PORT))
            val conn = URL(HEALTH_CHECK_URL).openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = HEALTH_CHECK_TIMEOUT_MS
            conn.readTimeout = HEALTH_CHECK_TIMEOUT_MS
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            false
        }
    }

    private fun attemptFailover() {
        val failedId = activeConfigId
        if (failedId != null) repo.markConfigFailed(failedId)

        val candidates = repo.getAll().filter {
            it.id != failedId && !repo.isInFailureCooldown(it.id, FAILOVER_COOLDOWN_MS)
        }
        if (candidates.isEmpty()) {
            updateNotification("Connection unstable — no alternate server available")
            return
        }

        val best = FailoverSelector.pickBestCandidate(this, candidates, repo) ?: run {
            updateNotification("Connection unstable — retrying...")
            return
        }

        switchTo(best, reason = "failover")
    }

    private fun switchTo(config: ServerConfig, reason: String) {
        val ok = restartEngine(config.xrayConfigJson)
        if (!ok) {
            updateNotification("Switch to ${config.remark} failed — retrying next cycle")
            return
        }
        activeConfigId = config.id
        activeConfigRemark = config.remark
        repo.setActive(config.id)
        consecutiveFailures = 0

        updateNotification("Connected — ${config.remark}" + if (reason == "failover") " (auto-switched)" else "")

        val intent = Intent(ACTION_ACTIVE_CONFIG_CHANGED).apply {
            putExtra(EXTRA_NEW_CONFIG_ID, config.id)
            putExtra(EXTRA_NEW_CONFIG_REMARK, config.remark)
            putExtra(EXTRA_REASON, reason)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun startup(): Long = 0

    override fun shutdown(): Long {
        if (isRunning) {
            mainThreadHandler().post { updateNotification("Core stopped unexpectedly") }
        }
        return 0
    }

    override fun onEmitStatus(code: Long, message: String?): Long = 0

    private fun mainThreadHandler() = android.os.Handler(android.os.Looper.getMainLooper())

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("V2Ray Config App")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
