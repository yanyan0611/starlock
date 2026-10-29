package com.applockisland.xposed.hook.XMSF

import com.applockisland.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 移除焦点通知白名单签名校验。
 *
 * 作用域：com.xiaomi.xmsf（小米服务框架 / XMSF）
 *
 * HyperOS 的焦点通知（超级岛）会校验通知来源签名，第三方模块发出的焦点通知
 * 会被判为非法而不显示。这里 hook [com.xiaomi.xms.auth.AuthSession.b(error)]：
 * 当 error 不为 null（校验失败）时，把错误码字段 `a` 置 0 并触发成功回调 `h()`，
 * 从而使模块发出的超级岛通知能够正常展示。
 *
 * 该逻辑参照 HyperIsland 项目的 UnlockFocusAuthHook 实现。
 */
object UnlockFocusAuthHook : BaseHook() {

    private const val TAG = "AppLockIsland[FocusAuth]"
    private const val AUTH_SESSION_CLASS = "com.xiaomi.xms.auth.AuthSession"

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        hookAuthSession(module, param.defaultClassLoader)
    }

    private fun hookAuthSession(module: XposedModule, classLoader: ClassLoader) {
        val authSessionClass = runCatching { classLoader.loadClass(AUTH_SESSION_CLASS) }
            .getOrElse {
                logError(module, "class not found: $AUTH_SESSION_CLASS (${it.message})")
                return
            }

        val targetMethod = authSessionClass.declaredMethods
            .firstOrNull { it.name == "b" && it.parameterCount == 1 }
            ?: run {
                logError(module, "method b(error) not found in $AUTH_SESSION_CLASS")
                return
            }

        targetMethod.isAccessible = true
        module.hook(targetMethod).intercept { chain ->
            val error = chain.args.getOrNull(0)
            if (error == null) return@intercept chain.proceed()

            try {
                val originalCode = getIntField(error, "a")
                setField(error, "a", 0)
                val successResult = callMethod(chain.thisObject, "h")
                log(module) { "focus auth bypassed, original errorCode=$originalCode" }
                // 跳过原校验失败逻辑
                successResult
            } catch (e: Throwable) {
                logError(module, "focus auth bypass failed: ${e.message}")
                chain.proceed()
            }
        }
        log(module) { "hooked $AUTH_SESSION_CLASS.b(error)" }
    }

    private fun getIntField(instance: Any, fieldName: String): Int {
        var c: Class<*>? = instance.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                return (f.get(instance) as? Int) ?: 0
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return 0
    }

    private fun setField(instance: Any, fieldName: String, value: Any?) {
        var c: Class<*>? = instance.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(instance, value)
                return
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
    }

    private fun callMethod(instance: Any?, methodName: String): Any? {
        var c: Class<*>? = instance?.javaClass ?: return null
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(methodName)
                m.isAccessible = true
                return m.invoke(instance)
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return null
    }
}
