package com.applockisland.xposed.hook.SystemUI

import android.content.Context
import com.applockisland.xposed.hook.BaseHook
import com.applockisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 监听 SystemUI 中的生物识别（指纹 / 人脸）认证流程，识别出应用锁场景并驱动超级岛动画。
 *
 * Hook 点：
 * 1. BiometricPrompt.authenticate —— 检测是否为应用锁验证（通过标题/副标题特征）
 * 2. 人脸认证回调（onAuthenticationSucceeded / Failed / Error）
 * 3. 指纹认证回调
 */
object AppLockBiometricHook : BaseHook() {

    private const val TAG = "AppLockIsland[BiometricHook]"

    // 应用锁相关标题关键字（MIUI / HyperOS）
    private val APP_LOCK_KEYWORDS = listOf(
        "应用锁",
        "App lock",
        "applock",
        "验证",
        "指纹验证",
        "面部验证",
        "解锁",
    )

    private val hookedMethods = Collections.newSetFromMap(WeakHashMap<Method, Boolean>())

    @Volatile
    private var currentAppName: String? = null

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        if (param.packageName != "com.android.systemui") return

        // 模块加载早于 Application 创建，多数情况下此处拿不到 Context。
        // 因此 hook Application.onCreate，等宿主 Application 就绪后再注册接收器并初始化控制器。
        hookApplicationCreate(module, param)

        // 若此刻已有 Context，直接完成初始化
        val context = HookUtils.getContext(param.defaultClassLoader)
        if (context != null) {
            onApplicationReady(module, context)
        } else {
            log(module) { "context unavailable at package load, waiting Application.onCreate" }
        }

        hookBiometricPrompt(module, param.defaultClassLoader)
        hookFaceAuthentication(module, param.defaultClassLoader)
        hookFingerprintAuthentication(module, param.defaultClassLoader)
    }

    @Volatile
    private var appReady = false

    /** 宿主 Application 就绪后：初始化控制器并注册跨进程事件接收器 */
    fun onApplicationReady(module: XposedModule, context: Context) {
        if (appReady) return
        synchronized(this) {
            if (appReady) return
            AppLockFocusController.initWithContext(module, context)
            val registered = AppLockEventReceiver.register(context)
            log(module) { "Application ready, event receiver registered=$registered" }
            appReady = true
        }
    }

    private fun hookApplicationCreate(module: XposedModule, param: PackageLoadedParam) {
        val appClass = runCatching {
            param.defaultClassLoader.loadClass("android.app.Application")
        }.getOrNull() ?: return
        val onCreate = runCatching { appClass.getDeclaredMethod("onCreate") }.getOrNull() ?: return
        onCreate.isAccessible = true
        module.hook(onCreate).intercept { chain ->
            val result = chain.proceed()
            (chain.thisObject as? Context)?.let { onApplicationReady(module, it) }
            result
        }
        log(module) { "Application.onCreate hook installed" }
    }

    /** 设置当前正在解锁的应用名称（由 SecurityCenter Hook 通过广播/共享状态设置） */
    fun setCurrentAppName(name: String?) {
        currentAppName = name
    }

    // ---------- BiometricPrompt ----------

    private fun hookBiometricPrompt(module: XposedModule, classLoader: ClassLoader) {
        // Framework BiometricPrompt
        runCatching {
            val clazz = classLoader.loadClass("android.hardware.biometrics.BiometricPrompt")
            hookAuthenticateMethod(module, clazz, classLoader)
        }.onFailure {
            log(module) { "framework BiometricPrompt not found: ${it.message}" }
        }

        // AndroidX BiometricPrompt
        runCatching {
            val clazz = classLoader.loadClass("androidx.biometric.BiometricPrompt")
            hookAuthenticateMethod(module, clazz, classLoader)
        }.onFailure {
            log(module) { "androidx BiometricPrompt not found: ${it.message}" }
        }
    }

    private fun hookAuthenticateMethod(
        module: XposedModule,
        clazz: Class<*>,
        classLoader: ClassLoader,
    ) {
        clazz.declaredMethods
            .filter { it.name == "authenticate" }
            .forEach { method ->
                synchronized(hookedMethods) {
                    if (!hookedMethods.add(method)) return@forEach
                }
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    // 尝试从 PromptInfo 中提取标题，判断是否为应用锁
                    val isAppLock = isAppLockPrompt(chain.args)
                    if (isAppLock) {
                        val context = HookUtils.getContext(classLoader)
                        if (context != null) {
                            log(module) { "app lock biometric prompt detected" }
                            AppLockFocusController.onAuthenticating(context)
                        }
                    }
                    chain.proceed()
                }
            }
        log(module) { "hooked BiometricPrompt.authenticate in ${clazz.name}" }
    }

    private fun isAppLockPrompt(args: List<Any?>): Boolean {
        for (arg in args) {
            val obj = arg ?: continue
            val cls = obj.javaClass
            // BiometricPrompt.PromptInfo 或 BiometricPrompt$PromptInfo
            if (cls.name.contains("PromptInfo", ignoreCase = true)) {
                val title = getStringField(obj, "mTitle") ?: getStringField(obj, "title") ?: ""
                val subtitle = getStringField(obj, "mSubtitle") ?: getStringField(obj, "subtitle") ?: ""
                val description = getStringField(obj, "mDescription") ?: getStringField(obj, "description") ?: ""
                val combined = "$title $subtitle $description"
                if (APP_LOCK_KEYWORDS.any { combined.contains(it, ignoreCase = true) }) {
                    return true
                }
            }
        }
        return false
    }

    private fun getStringField(obj: Any, name: String): String? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj) as? String
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    // ---------- 人脸认证 ----------

    private fun hookFaceAuthentication(module: XposedModule, classLoader: ClassLoader) {
        val monitorClassNames = listOf(
            "com.android.keyguard.KeyguardUpdateMonitor",
            "com.android.systemui.keyguard.KeyguardUpdateMonitor",
        )

        val successMethods = setOf(
            "handleFaceAuthenticated",
            "onFaceAuthenticated",
        )
        val failedMethods = setOf(
            "handleFaceAuthFailed",
            "onFaceAuthFailed",
            "handleFaceAuthenticationFailed",
            "handleFaceAuthFailure",
            "onFaceAuthenticationFailed",
            "onFaceAuthFailure",
        )
        val errorMethods = setOf(
            "handleFaceError",
            "onFaceAuthError",
        )
        val stopMethods = setOf(
            "stopListeningForFace",
            "cancelFaceAuth",
        )

        monitorClassNames.forEach { className ->
            val clazz = runCatching { classLoader.loadClass(className) }.getOrNull()
                ?: return@forEach

            hookFaceCallbackMethods(module, clazz, successMethods, true)
            hookFaceCallbackMethods(module, clazz, failedMethods, false)
            hookFaceCallbackMethods(module, clazz, errorMethods, false)
            hookFaceCallbackMethods(module, clazz, stopMethods, null)

            // 同时 hook 内部的 FaceManager.AuthenticationCallback 子类
            hookFaceAuthenticationCallbacks(module, clazz, classLoader)

            log(module) { "face auth hooked: ${clazz.name}" }
        }
    }

    private fun hookFaceCallbackMethods(
        module: XposedModule,
        clazz: Class<*>,
        names: Set<String>,
        success: Boolean?,
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
                    val context = HookUtils.getContext(clazz.classLoader) ?: return@intercept result
                    if (success == true) {
                        AppLockFocusController.onSuccess(context, currentAppName)
                    } else if (success == false) {
                        AppLockFocusController.onFailed(context)
                    } else {
                        AppLockFocusController.onStopped(context)
                    }
                    result
                }
            }
    }

    private fun hookFaceAuthenticationCallbacks(
        module: XposedModule,
        monitorClass: Class<*>,
        classLoader: ClassLoader,
    ) {
        collectDeclaredClasses(monitorClass).forEach { callbackClass ->
            val superName = callbackClass.superclass?.name.orEmpty()
            val isFaceCallback = superName.contains("FaceManager\$AuthenticationCallback") ||
                callbackClass.name.contains("face", ignoreCase = true)
            if (!isFaceCallback) return@forEach

            hookFaceCallbackMethods(module, callbackClass, setOf("onAuthenticationSucceeded"), true)
            hookFaceCallbackMethods(module, callbackClass, setOf("onAuthenticationFailed"), false)
            hookFaceCallbackMethods(module, callbackClass, setOf("onAuthenticationError"), false)
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

    // ---------- 指纹认证 ----------

    private fun hookFingerprintAuthentication(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val clazz = classLoader.loadClass("com.android.systemui.biometrics.AuthController")
            hookFingerprintCallback(module, clazz, classLoader)
        }.onFailure {
            log(module) { "AuthController not found: ${it.message}" }
        }

        // 备选：hook FingerprintManager 认证回调
        runCatching {
            val clazz = classLoader.loadClass("android.hardware.fingerprint.FingerprintManager")
            hookFingerprintCallback(module, clazz, classLoader)
        }.onFailure {
            log(module) { "FingerprintManager not found: ${it.message}" }
        }
    }

    private fun hookFingerprintCallback(
        module: XposedModule,
        clazz: Class<*>,
        classLoader: ClassLoader,
    ) {
        // AuthController 中的 onAuthenticated / onAuthenticationFailed
        val successNames = setOf("onAuthenticated", "onAuthenticationSucceeded")
        val failedNames = setOf("onAuthenticationFailed", "onAuthError")

        hookFaceCallbackMethods(module, clazz, successNames, true)
        hookFaceCallbackMethods(module, clazz, failedNames, false)
    }
}
