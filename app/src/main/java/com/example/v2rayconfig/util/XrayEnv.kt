package com.example.v2rayconfig.util

import android.content.Context
import libv2ray.Libv2ray

/**
 * AndroidLibXrayLite requires InitCoreEnv(envPath, key) to be called once
 * before any CoreController is created or any MeasureOutboundDelay call is
 * made — it sets up the asset path (for geoip.dat/geosite.dat routing
 * data) and an internal XUDP key. This wraps that in a one-time guard so
 * every entry point (the VPN service, ping tests, smart tests) can call
 * ensureInitialized() safely without double-initializing.
 */
object XrayEnv {
    @Volatile private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        // geoip.dat / geosite.dat are NOT bundled by this template — Xray's
        // domain/IP routing rules (if you add any to the config) need them.
        // Download current ones from https://github.com/Loyalsoldier/v2ray-rules-dat
        // (or the official Xray-core assets) and place them in
        // app/src/main/assets/, then they'll be copied here on first run.
        // Without them, plain proxying (no routing rules) still works.
        val assetDir = context.filesDir.absolutePath
        Libv2ray.initCoreEnv(assetDir, "")
        initialized = true
    }
}
