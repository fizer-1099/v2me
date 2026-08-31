package com.example.v2rayconfig.util

import android.content.Context
import com.example.v2rayconfig.model.ServerConfig
import com.example.v2rayconfig.model.TargetApp
import libv2ray.Libv2ray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object SmartAppTester {

    /**
     * Hard cap per (server, site) test. measureOutboundDelay is a native
     * blocking call with no timeout of its own — without this, a single
     * unresponsive server could hang the entire Smart Test indefinitely
     * (this was a real bug: the progress dialog would never dismiss).
     */
    private const val PER_TEST_TIMEOUT_SEC = 12L

    private val timeoutExecutor = Executors.newSingleThreadExecutor()

    fun testConfigAgainstApps(context: Context, config: ServerConfig, apps: List<TargetApp>): Map<String, Long> {
        XrayEnv.ensureInitialized(context)
        return apps.associate { app ->
            app.id to measureOne(config, app.testUrl)
        }
    }

    private fun measureOne(config: ServerConfig, url: String): Long {
        val future = timeoutExecutor.submit<Long> {
            try {
                Libv2ray.measureOutboundDelay(config.xrayConfigJson, url)
            } catch (e: Exception) {
                -1L
            }
        }
        return try {
            future.get(PER_TEST_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            -1L
        } catch (e: Exception) {
            -1L
        }
    }

    fun testAllConfigs(
        context: Context,
        configs: List<ServerConfig>,
        apps: List<TargetApp>,
        onProgress: (configIndex: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<String, Map<String, Long>> {
        val all = mutableMapOf<String, Map<String, Long>>()
        configs.forEachIndexed { index, config ->
            onProgress(index + 1, configs.size)
            all[config.id] = testConfigAgainstApps(context, config, apps)
        }
        return all
    }

    fun bestConfigPerApp(
        configs: List<ServerConfig>,
        apps: List<TargetApp>,
        results: Map<String, Map<String, Long>>
    ): Map<String, Pair<ServerConfig, Long>?> {
        return apps.associate { app ->
            val best = configs
                .mapNotNull { c -> results[c.id]?.get(app.id)?.takeIf { it >= 0 }?.let { c to it } }
                .minByOrNull { it.second }
            app.id to best
        }
    }
}
