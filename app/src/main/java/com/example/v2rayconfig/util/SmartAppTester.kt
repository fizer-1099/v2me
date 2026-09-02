package com.example.v2rayconfig.util

import android.content.Context
import com.example.v2rayconfig.model.ServerConfig
import com.example.v2rayconfig.model.TargetApp
import libv2ray.Libv2ray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object SmartAppTester {

    private const val PER_TEST_TIMEOUT_SEC = 8L
    private val nativeCallExecutor = Executors.newCachedThreadPool()

    private fun measureOne(config: ServerConfig, url: String): Long {
        val future = nativeCallExecutor.submit<Long> {
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

    fun testConfigAgainstApps(context: Context, config: ServerConfig, apps: List<TargetApp>): Map<String, Long> {
        XrayEnv.ensureInitialized(context)
        return apps.associate { app -> app.id to measureOne(config, app.testUrl) }
    }

    fun testAllConfigs(
        context: Context,
        configs: List<ServerConfig>,
        apps: List<TargetApp>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<String, Map<String, Long>> {
        XrayEnv.ensureInitialized(context)

        val total = configs.size * apps.size
        var done = 0
        val grouped = mutableMapOf<String, MutableMap<String, Long>>()

        for (config in configs) {
            val perApp = mutableMapOf<String, Long>()
            for (app in apps) {
                perApp[app.id] = measureOne(config, app.testUrl)
                done++
                onProgress(done, total)
            }
            grouped[config.id] = perApp
        }
        return grouped
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
