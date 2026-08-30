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

/**
 * Establishes the local VPN interface and routes its traffic into the
 * Xray-core engine via AndroidLibXrayLite's CoreController — the current
 * (as of Aug 2026) API for this library. Also owns continuous health
 * monitoring + automatic failover: if the active server stops responding,
 * this service picks the next best reachable one and switches the running
 * engine over to it WITHOUT tearing down the TUN interface, so there's no
 * VPN-permission prompt and no visible drop for apps using the tunnel.
 *
 * ARCHITECTURE NOTE (verified against live docs, Aug 2026): the library's
 * public API changed from the older V2RayPoint/configureFileContent/
 * runLoop/V2RayVPNServiceSupportsSet(protect/setup) shape to:
 *   CoreController.startLoop(configJson, tunFd): Int32 fd passed directly
 *   CoreController.stopLoop()
 *   CoreCallbackHandler { startup(), shutdown(), onEmitStatus() }  — NOTE:
 *     no protect() callback exists anymore. Since the Go core's own
 *     outbound socket (to your real proxy server) runs in this app's
 *     process, it would otherwise get captured by our own 0.0.0.0/0 route
 *     and loop back into itself. We prevent that the same way modern
 *     Xray-core Android integration expects: excluding this app's own
 *     package from the VPN via Builder.addDisallowedApplication(), so any
 *     socket this process opens (including the native core's) bypasses
 *     the tunnel automatically — no per-socket protect() needed.
 *   The Xray JSON config itself must declare a "tun" protocol inbound
 *     (port/listen are ignored for it) for Xray to actually attach to the
 *     fd — see ServerConfig.kt's buildRootConfig.
 *
 * If a future library version changes this again, the methods below
 * (startLoop/stopLoop/startup/shutdown/onEmitStatus) are the places to
 * update — check https://github.com/2dust/AndroidLibXrayLite before
 * assuming this still matches.
 */
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
        const val EXTRA_REASON = "reason" // "manual" or "failover"

        private const val LOCAL_PORT = 10808 // the diagnostics-only socks-in from ServerConfig.kt
        private const val HEALTH_CHECK_INTERVAL_SEC = 20L
        private const val HEALTH_CHECK_TIMEOUT_MS = 6000
        private const val MAX_CONSECUTIVE_FAILURES = 2
        private const val FAILOVER_COOLDOWN_MS = 5 * 60 * 1000L // don't retry a just-failed server for 5 min
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

    // ---------- Start / stop / switch ----------

    private fun startVpn(configJson: String, configId: String?) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

        val builder = Builder()
            .setSession("V2RayConfigApp")
            .addAddress("10.10.14.1", 30)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)

        // Critical: exclude THIS app from the tunnel, or Xray's own
        // outbound connection to your proxy server (opened from inside
        // this same process) gets captured by our own 0.0.0.0/0 route and
        // loops back into itself instead of reaching the real internet.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            // Shouldn't happen for our own package, but don't hard-fail startup over it.
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

    /** Switches the running engine to a different config's JSON, reusing the same TUN fd. */
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
        try { coreController?.stopLoop() } catch (e: Exception) { /* best effort */ }
        coreController = null
        vpnInterface?.close()
        vpnInterface = null
        isRunning = false
        activeConfigId = null
        activeConfigRemark = null
        stopForeground(true)
        stopSelf()
    }

    // ---------- Health monitoring + automatic failover ----------

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

    /** Runs on the monitor's background thread. */
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

    /** Goes through the app's own diagnostics socks-in (see ServerConfig.kt), not the TUN itself. */
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

    /** Picks the next best reachable server (excluding ones in cooldown) and switches over in-place. */
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

    /** Switches the running engine to a different config without tearing down the TUN interface. */
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

    // --- CoreCallbackHandler: callbacks the Go core uses to talk back to us ---
    // (No protect()/setup() here anymore — see the class-level ARCHITECTURE NOTE.)

    override fun startup(): Int = 0

    /** Called if the core shuts itself down unexpectedly (e.g. fatal error) — not on our own stopLoop() calls. */
    override fun shutdown(): Int {
        if (isRunning) {
            mainThreadHandler().post { updateNotification("Core stopped unexpectedly") }
        }
        return 0
    }

    override fun onEmitStatus(code: Int, message: String?): Int = 0

    private fun mainThreadHandler() = android.os.Handler(android.os.Looper.getMainLooper())

    // ---------- Notification ----------

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
