package com.applockisland.xposed.hook.SecurityCenter

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.applockisland.xposed.ConfigManager
import com.applockisland.xposed.hook.BaseHook
import com.applockisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 监听 SecurityCenter（安全中心）中的应用锁验证流程。
 *
 * Hook 点：
 * - 应用锁验证 Activity（AppLockVerifyActivity 等）的生命周期
 * - AppLockManager 的认证回调
 *
 * 检测到应用锁事件后，通过广播通知 SystemUI 进程中的 [com.applockisland.xposed.hook.SystemUI.AppLockFocusController]。
 */
object AppLockAuthHook : BaseHook() {

    private const val TAG = "AppLockIsland[AuthHook]"

    // 广播 Action
    const val ACTION_APP_LOCK_AUTH_START = "com.applockisland.action.APP_LOCK_AUTH_START"
    const val ACTION_APP_LOCK_AUTH_SUCCESS = "com.applockisland.action.APP_LOCK_AUTH_SUCCESS"
    const val ACTION_APP_LOCK_AUTH_FAILED = "com.applockisland.action.APP_LOCK_AUTH_FAILED"
    const val ACTION_APP_LOCK_AUTH_STOP = "com.applockisland.action.APP_LOCK_AUTH_STOP"

    /** 从模块 App 手动触发一次测试通知（用于排查超级岛链路） */
    const val ACTION_APP_LOCK_TEST = "com.applockisland.action.APP_LOCK_TEST"

    /** 是否隐藏验证页自带解锁动效 */
    private const val PREF_HIDE_ORIGINAL_ANIM = "pref_app_lock_hide_original_dialog"

    /** 动效视图特征关键字（类名或资源 id 名命中即视为装饰性动画） */
    private val animKeyWords = arrayOf(
        "anim", "lottie", "svga", "rive", "gif", "scan", "pulse", "glow",
        "wave", "ripple", "particle", "spark", "breath", "aurora",
    )

    const val EXTRA_APP_NAME = "app_name"
    const val EXTRA_REASON = "reason"

    // 应用锁相关类名（兼容不同 MIUI / HyperOS 版本）
    private val appLockActivityNames = listOf(
        "com.miui.securitycenter.ui.applock.AppLockVerifyActivity",
        "com.miui.securitycenter.applock.AppLockActivity",
        "com.miui.securitycenter.applock.AppLockVerifyActivity",
        "com.miui.apps.AppLockActivity",
    )

    private val appLockManagerNames = listOf(
        "com.miui.apps.AppLockManager",
        "com.miui.securitycenter.applock.AppLockManager",
        "com.miui.securitycenter.service.AppLockService",
    )

    private val hookedMethods = Collections.newSetFromMap(WeakHashMap<Method, Boolean>())

    /** 当前是否有应用锁验证界面在前台（由 Activity 生命周期判断） */
    @Volatile
    private var appLockForeground = false

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        if (param.packageName != "com.miui.securitycenter") return

        hookAppLockActivities(module, param.defaultClassLoader)
        hookAppLockManager(module, param.defaultClassLoader)
        // 通用兜底：不依赖具体类名，按 Activity 类名关键字 + 生物识别调用识别应用锁
        hookActivityByKeyword(module, param.defaultClassLoader)
        hookBiometricAuthenticate(module, param.defaultClassLoader)
    }

    /** 记录应用锁期间系统自己发出的焦点通知，便于定位原生解锁动画 */
    private fun hookNativeNotify(module: XposedModule, classLoader: ClassLoader) {
        val nm = runCatching { classLoader.loadClass("android.app.NotificationManager") }.getOrNull()
            ?: return
        nm.declaredMethods.filter { it.name == "notify" }.forEach { method ->
            synchronized(hookedMethods) {
                if (!hookedMethods.add(method)) return@forEach
            }
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (appLockForeground) {
                    val args = chain.args
                    runCatching {
                        val id = args.filterIsInstance<Int>().firstOrNull()
                        val notif = args.filterIsInstance<android.app.Notification>().firstOrNull()
                        val tag = args.filterIsInstance<String>().firstOrNull()
                        val focus = notif?.extras?.getString("miui.focus.param")
                        log(module) {
                            "native notify: tag=$tag id=$id channel=${notif?.channelId} " +
                                "focus=${focus?.take(220)}"
                        }
                    }
                }
                result
            }
        }
        log(module) { "native notify hook installed" }
    }

    // ---------- 屏蔽系统解锁大浮窗 ----------

    /**
     * 应用锁解锁时会弹出系统级浮窗（灰色圆角方块，内含笑脸 / 对勾动画）。
     * 应用锁在前台时，隐藏所有系统弹窗级（type ≥ TYPE_SYSTEM_ALERT）的新建窗口。
     */
    private fun hookFloatingWindows(module: XposedModule, classLoader: ClassLoader) {
        val wmImpl = runCatching { classLoader.loadClass("android.view.WindowManagerImpl") }.getOrNull()
            ?: return
        wmImpl.declaredMethods.filter { it.name == "addView" }.forEach { method ->
            synchronized(hookedMethods) {
                if (!hookedMethods.add(method)) return@forEach
            }
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                if (appLockForeground) {
                    val view = chain.args.getOrNull(0) as? View
                    val lp = chain.args.getOrNull(1) as? android.view.WindowManager.LayoutParams
                    if (view != null && lp != null) {
                        val cls = view.javaClass.name
                        val title = runCatching { lp.title?.toString() }.getOrNull().orEmpty()
                        log(module) { "sc window add: $cls type=${lp.type} title=$title" }
                        val hit = FLOATING_KEYWORDS.any {
                            cls.contains(it, true) || title.contains(it, true)
                        }
                        if (hit || lp.type >= android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT) {
                            lp.alpha = 0f
                            view.alpha = 0f
                            log(module) { "overlay window suppressed: $cls type=${lp.type} title=$title" }
                        }
                    }
                }
                chain.proceed()
            }
        }
        log(module) { "floating window hooks installed" }
    }

    private val FLOATING_KEYWORDS = listOf(
        "smile", "face", "biometric", "fingerprint", "anim", "verify", "scan", "unlock"
    )

    // ---------- 应用锁验证 Activity ----------

    private fun hookAppLockActivities(module: XposedModule, classLoader: ClassLoader) {
        appLockActivityNames.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull()
                ?: return@forEach

            // onCreate / onResume —— 验证开始
            hookLifecycleMethod(module, clazz, "onCreate", ACTION_APP_LOCK_AUTH_START)
            hookLifecycleMethod(module, clazz, "onResume", ACTION_APP_LOCK_AUTH_START)

            // onDestroy / onPause —— 验证结束
            hookLifecycleMethod(module, clazz, "onDestroy", ACTION_APP_LOCK_AUTH_STOP)
            hookLifecycleMethod(module, clazz, "onPause", ACTION_APP_LOCK_AUTH_STOP)

            log(module) { "app lock activity hooked: ${clazz.name}" }
        }
    }

    private fun hookLifecycleMethod(
        module: XposedModule,
        clazz: Class<*>,
        methodName: String,
        action: String,
    ) {
        clazz.declaredMethods
            .filter { it.name == methodName }
            .forEach { method ->
                synchronized(hookedMethods) {
                    if (!hookedMethods.add(method)) return@forEach
                }
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? android.app.Activity
                    val context = activity ?: HookUtils.getContext(clazz.classLoader)
                    if (context != null) {
                        val appName = extractAppName(activity)
                        sendBroadcast(context, action, appName)
                        log(module) { "app lock lifecycle: $methodName, app=$appName" }
                    }
                    result
                }
            }
    }

    // ---------- 应用锁管理器 ----------

    private fun hookAppLockManager(module: XposedModule, classLoader: ClassLoader) {
        appLockManagerNames.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull()
                ?: return@forEach

            // 认证成功方法
            val successNames = setOf(
                "onVerifySuccess",
                "onAuthenticationSucceeded",
                "onUnlockSuccess",
                "unlockSuccess",
                "verifySuccess",
            )
            // 认证失败方法
            val failedNames = setOf(
                "onVerifyFailed",
                "onAuthenticationFailed",
                "onUnlockFailed",
                "unlockFailed",
                "verifyFailed",
            )

            hookManagerCallback(module, clazz, successNames, ACTION_APP_LOCK_AUTH_SUCCESS)
            hookManagerCallback(module, clazz, failedNames, ACTION_APP_LOCK_AUTH_FAILED)

            log(module) { "app lock manager hooked: ${clazz.name}" }
        }
    }

    private fun hookManagerCallback(
        module: XposedModule,
        clazz: Class<*>,
        names: Set<String>,
        action: String,
    ) {
        clazz.declaredMethods
            .filter { it.name in names }
            .forEach { method ->
                synchronized(hookedMethods) {
                    if (!hookedMethods.add(method)) return@forEach
                }
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val context = HookUtils.getContext(clazz.classLoader)
                    if (context != null) {
                        val reason = if (action == ACTION_APP_LOCK_AUTH_FAILED) {
                            chain.args.firstOrNull { it is String } as? String
                        } else null
                        sendBroadcast(context, action, null, reason)
                        log(module) { "app lock manager callback: ${method.name}" }
                    }
                    result
                }
            }

        // 同时 hook 内部回调类
        collectDeclaredClasses(clazz).forEach { inner ->
            val superName = inner.superclass?.name.orEmpty()
            val isCallback = superName.contains("Callback") ||
                inner.name.contains("Callback", ignoreCase = true) ||
                inner.name.contains("Listener", ignoreCase = true)
            if (!isCallback) return@forEach

            inner.declaredMethods
                .filter { it.name in names }
                .forEach { method ->
                    synchronized(hookedMethods) {
                        if (!hookedMethods.add(method)) return@forEach
                    }
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        val context = HookUtils.getContext(inner.classLoader)
                        if (context != null) {
                            sendBroadcast(context, action)
                        }
                        result
                    }
                }
        }
    }

    // ---------- 通用兜底：Activity 类名识别 ----------

    /**
     * Hook 基类 [android.app.Activity] 的 onResume / onPause，
     * 通过实际 Activity 类名关键字判断是否为应用锁验证界面。
     * 这样不依赖 HyperOS 具体版本里的应用锁 Activity 类名。
     */
    private fun hookActivityByKeyword(module: XposedModule, classLoader: ClassLoader) {
        val activityClass = runCatching { classLoader.loadClass("android.app.Activity") }.getOrNull()
            ?: return
        hookKeywordLifecycle(module, activityClass, "onResume", start = true)
        hookKeywordLifecycle(module, activityClass, "onPause", start = false)
        log(module) { "generic activity keyword hook installed" }
    }

    private fun hookKeywordLifecycle(
        module: XposedModule,
        activityClass: Class<*>,
        methodName: String,
        start: Boolean,
    ) {
        val method = runCatching { activityClass.getDeclaredMethod(methodName) }.getOrNull() ?: return
        synchronized(hookedMethods) {
            if (!hookedMethods.add(method)) return
        }
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as? android.app.Activity
            val className = activity?.javaClass?.name.orEmpty()
            val isAppLock = className.contains("applock", ignoreCase = true) ||
                className.contains("app_lock", ignoreCase = true)

            if (isAppLock) {
                appLockForeground = start
                val context = activity ?: HookUtils.getContext(activityClass.classLoader)
                if (context != null) {
                    val appName = extractAppName(activity)
                    sendBroadcast(
                        context,
                        if (start) ACTION_APP_LOCK_AUTH_START else ACTION_APP_LOCK_AUTH_STOP,
                        appName,
                    )
                }
                if (start && activity != null) hideVerificationAnimations(activity)
                log(module) { "app lock activity $methodName: $className (start=$start)" }
            } else if (className.contains("lock", true) || className.contains("verify", true)) {
                // 仅记录可能相关的界面，便于真机排查类名
                log(module) { "activity $methodName: $className" }
            }
            result
        }
    }

    // ---------- 通用兜底：生物识别调用 ----------

    /**
     * Hook 生物识别 authenticate，配合 [appLockForeground] 判断应用锁场景，
     * 并动态 hook 传入的回调对象，把成功 / 失败事件广播给 SystemUI。
     */
    private fun hookBiometricAuthenticate(module: XposedModule, classLoader: ClassLoader) {
        listOf(
            "android.hardware.biometrics.BiometricPrompt",
            "android.hardware.face.FaceManager",
            "android.hardware.fingerprint.FingerprintManager",
        ).forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull()
                ?: return@forEach

            clazz.declaredMethods.filter { it.name == "authenticate" }.forEach { method ->
                synchronized(hookedMethods) {
                    if (!hookedMethods.add(method)) return@forEach
                }
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val isAppLock = appLockForeground
                    log(module) { "biometric authenticate (${clazz.simpleName}), appLockForeground=$isAppLock" }
                    if (isAppLock) {
                        hookCallbackArgs(module, chain.args)
                        val context = HookUtils.getContext(clazz.classLoader)
                        if (context != null) {
                            sendBroadcast(context, ACTION_APP_LOCK_AUTH_START)
                        }
                    }
                    chain.proceed()
                }
            }
            log(module) { "biometric hook ready: ${clazz.name}" }
        }

        hookStandardCallbacks(module, classLoader)
    }

    /**
     * Hook 系统标准生物识别回调基类（BiometricPrompt / FaceManager / FingerprintManager 及 androidx 版本）。
     * 应用锁多数走这些回调，成功后才能及时把"已解锁"推上岛。
     */
    private fun hookStandardCallbacks(module: XposedModule, classLoader: ClassLoader) {
        listOf(
            "android.hardware.biometrics.BiometricPrompt\$AuthenticationCallback",
            "android.hardware.fingerprint.FingerprintManager\$AuthenticationCallback",
            "android.hardware.face.FaceManager\$AuthenticationCallback",
            "androidx.biometric.BiometricPrompt\$AuthenticationCallback",
        ).forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull()
                ?: return@forEach
            hookCallbackMethod(module, clazz, "onAuthenticationSucceeded", ACTION_APP_LOCK_AUTH_SUCCESS, true)
            hookCallbackMethod(module, clazz, "onAuthenticationFailed", ACTION_APP_LOCK_AUTH_FAILED, true)
            hookCallbackMethod(module, clazz, "onAuthenticationError", ACTION_APP_LOCK_AUTH_FAILED, true)
            log(module) { "callback hooked: ${clazz.name}" }
        }
    }

    /** 对 authenticate 传入的回调对象，按其实际类 hook 成功 / 失败方法 */
    private fun hookCallbackArgs(module: XposedModule, args: List<Any?>) {
        args.forEach { arg ->
            val cls = arg?.javaClass ?: return@forEach
            if (!cls.name.contains("Callback", true) && !cls.name.contains("Listener", true)) {
                return@forEach
            }
            hookCallbackMethod(module, cls, "onAuthenticationSucceeded", ACTION_APP_LOCK_AUTH_SUCCESS)
            hookCallbackMethod(module, cls, "onAuthenticationFailed", ACTION_APP_LOCK_AUTH_FAILED)
            hookCallbackMethod(module, cls, "onAuthenticationError", ACTION_APP_LOCK_AUTH_FAILED)
        }
    }

    private fun hookCallbackMethod(
        module: XposedModule,
        cls: Class<*>,
        methodName: String,
        action: String,
        onlyInAppLock: Boolean = false,
    ) {
        cls.declaredMethods.filter { it.name == methodName }.forEach { method ->
            synchronized(hookedMethods) {
                if (!hookedMethods.add(method)) return@forEach
            }
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                if (onlyInAppLock && !appLockForeground) return@intercept chain.proceed()
                val result = chain.proceed()
                // 生物识别错误码 5/10 表示用户取消或中止，按 STOP 处理
                val errorCode = chain.args.firstOrNull { it is Int } as? Int
                val effectiveAction = if (action == ACTION_APP_LOCK_AUTH_FAILED &&
                    (errorCode == 5 || errorCode == 10)
                ) {
                    ACTION_APP_LOCK_AUTH_STOP
                } else {
                    action
                }
                if (effectiveAction == ACTION_APP_LOCK_AUTH_STOP) {
                    appLockForeground = false
                }
                val context = HookUtils.getContext(cls.classLoader)
                if (context != null) {
                    sendBroadcast(context, effectiveAction)
                }
                log(module) { "biometric callback $methodName -> $effectiveAction" }
                result
            }
        }
    }

    // ---------- 隐藏验证页自带动效 ----------

    /**
     * 隐藏应用锁验证页中的装饰性解锁动效（扫描光圈、脉冲、浅色波纹等），
     * 保留按钮与输入控件，避免影响指纹/人脸/密码的正常操作。
     */
    private fun hideVerificationAnimations(activity: android.app.Activity) {
        if (!ConfigManager.getBoolean(PREF_HIDE_ORIGINAL_ANIM, true)) return
        val decor = activity.window?.decorView ?: return
        val handler = Handler(Looper.getMainLooper())
        val apply = Runnable {
            runCatching { hideAnimationViews(decor) }
        }
        // 页面可能分多次 inflate，重复几轮提高命中率
        listOf(60L, 300L, 800L).forEach { handler.postDelayed(apply, it) }
    }

    private fun hideAnimationViews(root: View) {
        val resources = root.resources
        val pending = ArrayDeque<View>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) pending.addLast(view.getChildAt(i))
            }
            if (view === root) continue

            val classKey = view.javaClass.name.lowercase()
            val idKey = if (view.id != View.NO_ID) {
                runCatching { resources.getResourceEntryName(view.id) }.getOrDefault("").lowercase()
            } else {
                ""
            }
            val matched = animKeyWords.any { it in classKey || it in idKey }
            if (!matched) continue

            if (view.isClickable) {
                // 可点击的动效（如指纹热区）只做淡出，保留可操作性
                view.alpha = 0.15f
            } else {
                view.visibility = View.GONE
            }
        }
    }

    // ---------- 工具 ----------

    private fun extractAppName(activity: android.app.Activity?): String? {
        activity ?: return null
        return try {
            val intent = activity.intent
            intent?.getStringExtra("package_name")
                ?: intent?.getStringExtra("target_package")
                ?: intent?.getStringExtra("pkg")
        } catch (_: Throwable) {
            null
        }
    }

    private fun sendBroadcast(
        context: Context,
        action: String,
        appName: String? = null,
        reason: String? = null,
    ) {
        try {
            val intent = Intent(action).apply {
                setPackage("com.android.systemui")
                if (appName != null) putExtra(EXTRA_APP_NAME, appName)
                if (reason != null) putExtra(EXTRA_REASON, reason)
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {
        }
    }

    private fun collectDeclaredClasses(root: Class<*>): List<Class<*>> {
        val result = ArrayList<Class<*>>()
        val pending = ArrayDeque<Class<*>>()
        root.declaredClasses.forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val clazz = pending.removeFirst()
            result.add(clazz)
            clazz.declaredClasses.forEach(pending::addLast)
        }
        return result
    }
}
