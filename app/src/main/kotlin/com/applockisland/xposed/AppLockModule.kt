package com.applockisland.xposed

import com.applockisland.xposed.hook.SystemUI.AppLockBiometricHook
import com.applockisland.xposed.hook.SecurityCenter.AppLockAuthHook
import com.applockisland.xposed.hook.SystemUI.AppLockFocusController
import com.applockisland.xposed.hook.XMSF.UnlockFocusAuthHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 模块主入口，继承 XposedModule。
 * 框架在各目标进程加载时回调 [onPackageLoaded]，由此分发到各子 Hook。
 *
 * 作用域：
 * - com.android.systemui：监听生物识别 / 人脸识别认证状态，上超级岛
 * - com.miui.securitycenter：监听应用锁验证流程
 * - com.xiaomi.xmsf：可选，解除焦点通知签名校验
 */
class AppLockModule : XposedModule() {

    private var configManagerInitialized = false

    override fun onPackageLoaded(param: PackageLoadedParam) {
        initializeConfigManager()
        log { "onPackageLoaded: pkg=${param.packageName}" }

        when (param.packageName) {
            "com.android.systemui" -> {
                // 生物识别 / 人脸识别认证状态监听，驱动超级岛动画
                AppLockBiometricHook.init(this, param)
                // 初始化焦点通知控制器（在 SystemUI 进程中）
                AppLockFocusController.init(this, param)
            }

            "com.miui.securitycenter" -> {
                // 应用锁验证流程监听
                AppLockAuthHook.init(this, param)
            }

            "com.xiaomi.xmsf" -> {
                // 解除焦点通知签名校验，否则超级岛不显示模块发出的通知
                UnlockFocusAuthHook.init(this, param)
            }
        }
    }

    private fun initializeConfigManager() {
        if (!configManagerInitialized) {
            ConfigManager.init(this)
            configManagerInitialized = true
        }
    }
}
