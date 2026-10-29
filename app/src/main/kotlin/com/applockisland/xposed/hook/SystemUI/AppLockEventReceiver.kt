package com.applockisland.xposed.hook.SystemUI

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.applockisland.xposed.hook.SecurityCenter.AppLockAuthHook

/**
 * 接收来自 SecurityCenter 进程的应用锁认证事件广播，
 * 并转发给 [AppLockFocusController] 驱动超级岛动画。
 *
 * 由于应用锁验证逻辑运行在 com.miui.securitycenter 进程，
 * 而超级岛通知由 com.android.systemui 进程负责渲染，
 * 因此需要通过广播跨进程传递事件。
 */
object AppLockEventReceiver {

    private var registered = false
    private var receiver: BroadcastReceiver? = null

    fun register(context: Context): Boolean {
        if (registered) return true
        synchronized(this) {
            if (registered) return true
            val appContext = context.applicationContext ?: context

            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        AppLockAuthHook.ACTION_APP_LOCK_AUTH_START -> {
                            val appName = intent.getStringExtra(AppLockAuthHook.EXTRA_APP_NAME)
                            AppLockBiometricHook.setCurrentAppName(appName)
                            AppLockFocusController.onAuthenticating(context)
                        }

                        AppLockAuthHook.ACTION_APP_LOCK_AUTH_SUCCESS -> {
                            val appName = intent.getStringExtra(AppLockAuthHook.EXTRA_APP_NAME)
                            AppLockFocusController.onSuccess(context, appName)
                        }

                        AppLockAuthHook.ACTION_APP_LOCK_AUTH_FAILED -> {
                            val reason = intent.getStringExtra(AppLockAuthHook.EXTRA_REASON)
                            AppLockFocusController.onFailed(context, reason)
                        }

                        AppLockAuthHook.ACTION_APP_LOCK_AUTH_STOP -> {
                            AppLockFocusController.onStopped(context)
                        }

                        AppLockAuthHook.ACTION_APP_LOCK_TEST -> {
                            // 手动测试：跳过配置开关，直接验证超级岛链路
                            AppLockFocusController.onSuccess(context, "测试应用", force = true)
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(AppLockAuthHook.ACTION_APP_LOCK_AUTH_START)
                addAction(AppLockAuthHook.ACTION_APP_LOCK_AUTH_SUCCESS)
                addAction(AppLockAuthHook.ACTION_APP_LOCK_AUTH_FAILED)
                addAction(AppLockAuthHook.ACTION_APP_LOCK_AUTH_STOP)
                addAction(AppLockAuthHook.ACTION_APP_LOCK_TEST)
            }

            // Android 14+ 要求显式声明导出标志，否则 registerReceiver 抛 SecurityException
            return try {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                registered = true
                true
            } catch (e: Throwable) {
                receiver = null
                false
            }
        }
    }

    fun unregister(context: Context) {
        if (!registered) return
        synchronized(this) {
            if (!registered) return
            receiver?.let {
                runCatching { context.unregisterReceiver(it) }
            }
            receiver = null
            registered = false
        }
    }
}
