package com.applockisland.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule

private const val DEFAULT_TAG = "AppLockIsland"

/** 调试日志（受配置开关控制），message 为 lambda 以实现零开销短路。 */
inline fun XposedModule.logDebug(tag: String, message: () -> String) {
    log(Log.DEBUG, tag, message())
}

fun XposedModule.logWarn(tag: String, message: String) {
    log(Log.WARN, tag, message)
}

fun XposedModule.logError(tag: String, message: String) {
    log(Log.ERROR, tag, message)
}

fun XposedModule.log(message: () -> String) {
    log(Log.DEBUG, DEFAULT_TAG, message())
}
