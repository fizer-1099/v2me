package com.example.v2rayconfig.util

import android.content.Context
import com.example.v2rayconfig.model.ServerConfig
import libv2ray.Libv2ray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Measures how fast each saved server actually is when proxying real
 * traffic, so the app can rank/auto-connect to the best one.
 *
 * This used to measure raw TCP connect time to the server's host:port,
 * which only tells you the entrypoint is reachable — not whether the
 * proxy protocol/TLS handshake/routing actually works end-to-end. Now
 * that AndroidLibXrayLite exposes Libv2ray.measureOutboundDelay(configJson,
 * url) — a proper round-trip through the configured outbound — we use
 * that instead: it's a small, real HTTP request through the actual proxy
 * (TLS handshake and all), so the numbers reflect real usable latency.
 */
object PingTester {

    /** A neutral, fast, always-up endpoint — avoids biasing rankings toward any one destination. */
    private const val DEFAULT_TEST_URL = "https://cp.cloudflare.com/generate_204"

    /** Latency in ms for a single server (real proxied round-trip), or -1 if it failed. */
    fun testLatency(context: Context, config: ServerConfig, testUrl: String = DEFAULT_TEST_URL): Long {
        XrayEnv.ensureInitialized(context)
        return try {
            Libv2ray.measureOutboundDelay(config.xrayConfigJson, testUrl)
        } catch (e: Exception) {
            -1L
        }
    }

    /** Tests every config in parallel. Returns configId -> latencyMs (-1 = unreachable). */
    fun testAll(context: Context, configs: List<ServerConfig>, testUrl: String = DEFAULT_TEST_URL): Map<String, Long> {
        if (configs.isEmpty()) return emptyMap()
        XrayEnv.ensureInitialized(context)
        val results = ConcurrentHashMap<String, Long>()
        val pool = Executors.newFixedThreadPool(minOf(configs.size, 8))
        try {
            val futures = configs.map { config ->
                pool.submit { results[config.id] = testLatency(context, config, testUrl) }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return results
    }

    /** Returns the reachable config with the lowest latency, or null if none responded. */
    fun pickBest(configs: List<ServerConfig>, latencies: Map<String, Long>): ServerConfig? {
        return configs
            .mapNotNull { c -> latencies[c.id]?.takeIf { it >= 0 }?.let { c to it } }
            .minByOrNull { it.second }
            ?.first
    }
}
