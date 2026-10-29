package com.applockisland.xposed.hook

import com.applockisland.xposed.ConfigManager
import com.applockisland.xposed.logDebug
import com.applockisland.xposed.logError
import com.applockisland.xposed.logWarn
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Hook 基类，提供统一的配置管理和生命周期。
 */
abstract class BaseHook {

    private val configChangeListener: () -> Unit = { onConfigChanged() }
    private var configListenerRegistered = false

    abstract fun getTag(): String

    abstract fun onInit(module: XposedModule, param: PackageLoadedParam)

    open fun onConfigChanged() {}

    protected fun ensureConfigManager(module: XposedModule) {
        ConfigManager.init(module)
        if (!configListenerRegistered) {
            ConfigManager.addChangeListener(configChangeListener)
            configListenerRegistered = true
        }
    }

    protected inline fun log(module: XposedModule, message: () -> String) =
        module.logDebug(getTag(), message)

    protected fun logWarn(module: XposedModule, message: String) =
        module.logWarn(getTag(), message)

    protected fun logError(module: XposedModule, message: String) =
        module.logError(getTag(), message)

    fun init(module: XposedModule, param: PackageLoadedParam) {
        ensureConfigManager(module)
        log(module) { "initializing for ${param.packageName}" }
        try {
            onInit(module, param)
        } catch (e: Throwable) {
            logError(module, "init failed: ${e.message}")
        }
    }
}
