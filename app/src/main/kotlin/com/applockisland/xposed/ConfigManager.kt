package com.applockisland.xposed

import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于 RemotePreferences 的配置管理器。
 * 模块在宿主进程中通过 XposedModule.getRemotePreferences 读取模块配置。
 */
object ConfigManager {
    private const val TAG = "AppLockIsland[ConfigManager]"
    private const val PREFS_NAME = "AppLockIslandPrefs"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var module: XposedModule? = null

    private val cache = ConcurrentHashMap<String, Any>()

    private val changeListeners = mutableListOf<() -> Unit>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        cache.remove(key)
        notifyListeners()
    }

    @Synchronized
    fun init(module: XposedModule) {
        if (initialized) return
        try {
            val p = module.getRemotePreferences(PREFS_NAME)
            p.registerOnSharedPreferenceChangeListener(prefsListener)
            prefs = p
            this.module = module
            initialized = true
        } catch (e: Throwable) {
            module.log { "$TAG: init failed: ${e.message}" }
        }
    }

    fun getBoolean(key: String, def: Boolean): Boolean {
        val p = prefs ?: return def
        cache[key]?.let { return it as Boolean }
        val v = p.getBoolean(key, def)
        cache[key] = v
        return v
    }

    fun getInt(key: String, def: Int): Int {
        val p = prefs ?: return def
        cache[key]?.let { return it as Int }
        val v = p.getInt(key, def)
        cache[key] = v
        return v
    }

    fun getString(key: String, def: String): String {
        val p = prefs ?: return def
        cache[key]?.let { return it as String }
        val v = p.getString(key, def) ?: def
        cache[key] = v
        return v
    }

    fun addChangeListener(listener: () -> Unit) {
        synchronized(changeListeners) {
            changeListeners.add(listener)
        }
    }

    private fun notifyListeners() {
        synchronized(changeListeners) {
            changeListeners.toList()
        }.forEach { runCatching { it() } }
    }
}
