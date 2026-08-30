package com.example.v2rayconfig.util

import android.content.Context
import com.example.v2rayconfig.model.ServerConfig
import com.example.v2rayconfig.model.TargetApp
import libv2ray.Libv2ray

/**
 * Tests every saved server against a list of specific filtered
 * sites/apps and reports latency per (server, app) pair, so the app can
 * recommend "for Claude, server X in country Y works best" instead of a
 * single generic ranking.
 *
 * As of the current AndroidLibXrayLite API (verified Aug 2026), the
 * library exposes a purpose-built Libv2ray.measureOutboundDelay(configJson,
 * url) function that spins up the given config's outbound just long enough
 * to time a request to url — no TUN, no manual local-proxy juggling
 * required on our side. This replaced an earlier, much more roundabout
 * implementation of ours that manually started a whole separate engine
 * instance per test.
 */
object SmartAppTester {

    /** appId -> latencyMs (-1 = failed/unreachable) */
    fun testConfigAgainstApps(context: Context, config: ServerConfig, apps: List<TargetApp>): Map<String, Long> {
        XrayEnv.ensureInitialized(context)
        return apps.associate { app ->
            app.id to measureOne(config, app.testUrl)
        }
    }

    private fun measureOne(config: ServerConfig, url: String): Long {
        return try {
            Libv2ray.measureOutboundDelay(config.xrayConfigJson, url)
        } catch (e: Exception) {
            -1L
        }
    }

    /** Runs testConfigAgainstApps sequentially over every config. configId -> (appId -> latencyMs) */
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

    /** For each app, the config with the lowest latency across all tested configs. */
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
