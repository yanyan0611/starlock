package com.applockisland.xposed.utils

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hook 工具类。
 * 提供从 ClassLoader 获取 Context、方法去重 Hook 等通用能力。
 */
object HookUtils {

    private val hookedMethods = Collections.newSetFromMap(WeakHashMap<Method, Boolean>())

    /**
     * 从 ClassLoader 中尝试获取 Application Context。
     * 优先通过 ActivityThread.currentApplication() 反射获取。
     */
    fun getContext(classLoader: ClassLoader?): Context? {
        return try {
            val atClass = classLoader?.loadClass("android.app.ActivityThread")
                ?: Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val getApp = atClass.getDeclaredMethod("getApplication")
            getApp.invoke(currentAT) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 对类中所有匹配名称的方法进行 Hook（自动去重）。
     */
    fun hookAllMethods(
        module: XposedModule,
        clazz: Class<*>,
        name: String,
        callback: (chain: XposedInterface.Chain) -> Any?,
    ) {
        clazz.declaredMethods
            .filter { it.name == name }
            .forEach { method ->
                synchronized(hookedMethods) {
                    if (!hookedMethods.add(method)) return@forEach
                }
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    try {
                        callback(chain) ?: chain.proceed()
                    } catch (e: Throwable) {
                        chain.proceed()
                    }
                }
            }
    }

    /**
     * Hook 单个指定方法（自动去重）。
     */
    fun hookMethod(
        module: XposedModule,
        method: Method,
        callback: (chain: XposedInterface.Chain) -> Any?,
    ) {
        synchronized(hookedMethods) {
            if (!hookedMethods.add(method)) return
        }
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            try {
                callback(chain) ?: chain.proceed()
            } catch (e: Throwable) {
                chain.proceed()
            }
        }
    }
}
