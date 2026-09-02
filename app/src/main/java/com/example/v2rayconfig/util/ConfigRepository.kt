package com.example.v2rayconfig.util

import android.content.Context
import com.example.v2rayconfig.model.ServerConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences("v2ray_configs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val KEY_LIST = "configs"
    private val KEY_ACTIVE = "active_id"
    private val KEY_SUB_URL = "subscription_url"
    private val KEY_AUTO_CONNECT = "auto_connect"

    fun getAll(): MutableList<ServerConfig> {
        val json = prefs.getString(KEY_LIST, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<ServerConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    fun save(all: List<ServerConfig>) {
        prefs.edit().putString(KEY_LIST, gson.toJson(all)).apply()
    }

    fun add(config: ServerConfig) {
        val all = getAll()
        all.add(config)
        save(all)
    }

    fun remove(id: String) {
        val all = getAll().filterNot { it.id == id }
        save(all)
    }

    fun clearAll() {
        save(emptyList())
        setActive(null)
    }

    fun update(oldId: String, newConfig: ServerConfig) {
        val all = getAll()
        val index = all.indexOfFirst { it.id == oldId }
        if (index >= 0) {
            all[index] = newConfig.copy(id = oldId, source = all[index].source)
            save(all)
        }
    }

    fun setActive(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun getActiveId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun getSubscriptionUrl(): String? = prefs.getString(KEY_SUB_URL, null)

    fun setSubscriptionUrl(url: String) {
        prefs.edit().putString(KEY_SUB_URL, url).apply()
    }

    fun isAutoConnectEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    fun replaceSubscriptionConfigs(newConfigs: List<ServerConfig>) {
        val manual = getAll().filter { it.source != "subscription" }
        save(manual + newConfigs)
    }

    private val KEY_CUSTOM_APPS = "custom_target_apps"
    private val KEY_DISABLED_DEFAULT_APPS = "disabled_default_apps"

    fun getCustomTargetApps(): MutableList<com.example.v2rayconfig.model.TargetApp> {
        val json = prefs.getString(KEY_CUSTOM_APPS, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<com.example.v2rayconfig.model.TargetApp>>() {}.type
        return gson.fromJson(json, type)
    }

    fun addCustomTargetApp(app: com.example.v2rayconfig.model.TargetApp) {
        val all = getCustomTargetApps()
        all.add(app)
        prefs.edit().putString(KEY_CUSTOM_APPS, gson.toJson(all)).apply()
    }

    fun removeCustomTargetApp(id: String) {
        val all = getCustomTargetApps().filterNot { it.id == id }
        prefs.edit().putString(KEY_CUSTOM_APPS, gson.toJson(all)).apply()
    }

    fun getDisabledDefaultAppIds(): Set<String> =
        prefs.getStringSet(KEY_DISABLED_DEFAULT_APPS, emptySet()) ?: emptySet()

    fun setDefaultAppEnabled(id: String, enabled: Boolean) {
        val current = getDisabledDefaultAppIds().toMutableSet()
        if (enabled) current.remove(id) else current.add(id)
        prefs.edit().putStringSet(KEY_DISABLED_DEFAULT_APPS, current).apply()
    }

    fun getActiveTargetApps(): List<com.example.v2rayconfig.model.TargetApp> {
        val disabled = getDisabledDefaultAppIds()
        val defaults = com.example.v2rayconfig.model.TargetAppCatalog.defaults.filterNot { it.id in disabled }
        return defaults + getCustomTargetApps()
    }

    private val KEY_FAILURE_TIMESTAMPS = "config_failure_timestamps"

    private fun getFailureTimestamps(): MutableMap<String, Long> {
        val json = prefs.getString(KEY_FAILURE_TIMESTAMPS, null) ?: return mutableMapOf()
        val type = object : TypeToken<MutableMap<String, Long>>() {}.type
        return gson.fromJson(json, type)
    }

    fun markConfigFailed(id: String) {
        val map = getFailureTimestamps()
        map[id] = System.currentTimeMillis()
        prefs.edit().putString(KEY_FAILURE_TIMESTAMPS, gson.toJson(map)).apply()
    }

    fun clearConfigFailure(id: String) {
        val map = getFailureTimestamps()
        if (map.remove(id) != null) {
            prefs.edit().putString(KEY_FAILURE_TIMESTAMPS, gson.toJson(map)).apply()
        }
    }

    fun isInFailureCooldown(id: String, cooldownMs: Long): Boolean {
        val failedAt = getFailureTimestamps()[id] ?: return false
        return System.currentTimeMillis() - failedAt < cooldownMs
    }

    private val KEY_SMART_TEST_RESULTS = "smart_test_results"
    private val KEY_SMART_TEST_TIMESTAMP = "smart_test_timestamp"
    private val KEY_PRIORITY_APP_ID = "priority_app_id"

    fun saveSmartTestResults(results: Map<String, Map<String, Long>>) {
        prefs.edit()
            .putString(KEY_SMART_TEST_RESULTS, gson.toJson(results))
            .putLong(KEY_SMART_TEST_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun getSmartTestResults(): Map<String, Map<String, Long>> {
        val json = prefs.getString(KEY_SMART_TEST_RESULTS, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, Map<String, Long>>>() {}.type
        return try { gson.fromJson(json, type) } catch (e: Exception) { emptyMap() }
    }

    fun getSmartTestTimestamp(): Long = prefs.getLong(KEY_SMART_TEST_TIMESTAMP, 0L)

    fun getPriorityAppId(): String? = prefs.getString(KEY_PRIORITY_APP_ID, null)

    fun setPriorityAppId(id: String?) {
        prefs.edit().putString(KEY_PRIORITY_APP_ID, id).apply()
    }
}
