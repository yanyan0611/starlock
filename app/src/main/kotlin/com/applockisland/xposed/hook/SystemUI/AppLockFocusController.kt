package com.applockisland.xposed.hook.SystemUI

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.applockisland.R
import com.applockisland.xposed.ConfigManager
import com.applockisland.xposed.islanddispatch.IslandDispatcher
import com.applockisland.xposed.islanddispatch.definition.IslandRequest
import com.applockisland.xposed.islanddispatch.invoke.IslandDispatcherNotifier
import com.applockisland.xposed.logDebug
import com.applockisland.xposed.logError
import com.applockisland.xposed.utils.HookUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 应用锁解锁焦点通知控制器。
 *
 * 监听应用锁认证状态（认证中 / 成功 / 失败），并在超级岛上展示对应的解锁动画与状态。
 */
object AppLockFocusController {

    enum class UnlockState {
        AUTHENTICATING,
        SUCCESS,
        FAILED,
        STOPPED,
    }

    private const val TAG = "AppLockIsland[FocusController]"

    // 通知 ID 段，避免与系统其它通知冲突
    private const val NOTIF_ID = 0x414C4931  // "ALI1"

    // 动画时长参数
    private const val MIN_TERMINAL_VISIBLE_MS = 800L
    private const val TERMINAL_CANCEL_DELAY_MS = 1200L
    private const val AUTHENTICATING_TIMEOUT_MS = 15_000L
    // onPause 后等待成功回调的宽限时间
    private const val STOP_GRACE_MS = 800L

    // 配置 Key
    private const val PREF_ENABLED = "pref_app_lock_island_enabled"
    private const val PREF_SHOW_SUCCESS_ANIM = "pref_app_lock_show_success_anim"
    private const val PREF_HIDE_ORIGINAL_DIALOG = "pref_app_lock_hide_original_dialog"
    private const val PREF_KEEP_SUCCESS_UNTIL_DISMISS = "pref_app_lock_keep_success"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    @Volatile
    private var currentState = UnlockState.STOPPED

    /** 应用锁验证是否正在进行（供 SystemUI 侧浮窗拦截使用） */
    @Volatile
    var sessionActive = false
        private set

    @Volatile
    private var terminalCancel: Runnable? = null

    @Volatile
    private var authTimeout: Runnable? = null

    @Volatile
    private var terminalStartedAt = 0L

    @Volatile
    private var moduleContext: Context? = null

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    private var initialized = false

    fun init(module: XposedModule, param: PackageLoadedParam) {
        if (initialized) return
        // 模块加载时 Application 可能尚未创建，拿不到 Context；此时延迟到 Application.onCreate 再初始化
        val hostContext = HookUtils.getContext(param.defaultClassLoader)
        if (hostContext == null) {
            module.logDebug(TAG) { "host context unavailable at package load, deferring init" }
            return
        }
        initWithContext(module, hostContext)
    }

    fun initWithContext(module: XposedModule, context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            moduleContext = createModuleContext(context.applicationContext ?: context)
            this.module = module
            IslandDispatcherNotifier.errorLogger = { msg, e ->
                module.logError(TAG, "$msg: ${e.message}")
            }
            logDebug("init: moduleContext=${moduleContext != null}")
            initialized = true
        }
    }

    private fun logDebug(message: () -> String) {
        module?.logDebug(TAG, message)
    }

    private fun logDebug(message: String) {
        module?.logDebug(TAG) { message }
    }

    /** 通过 createPackageContext 创建包含模块资源的 Context */
    private fun createModuleContext(hostContext: Context): Context {
        return try {
            hostContext.createPackageContext(
                "com.applockisland",
                Context.CONTEXT_IGNORE_SECURITY,
            )
        } catch (_: Throwable) {
            hostContext
        }
    }

    /** 应用锁开始认证 */
    fun onAuthenticating(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext ?: context
        runOnMain {
            synchronized(stateLock) {
                if (!force && !isEnabled()) {
                    logDebug { "onAuthenticating ignored: disabled" }
                    return@synchronized
                }
                // 已经在提示"正在解锁"时不重复发通知（同一轮验证只发一次）
                if (!force && currentState == UnlockState.AUTHENTICATING) {
                    return@synchronized
                }
                cancelTerminalRemoval()
                cancelAuthTimeout()
                currentState = UnlockState.AUTHENTICATING
                sessionActive = true
                val posted = postAuthenticatingNotification(appContext)
                logDebug { "onAuthenticating: posted=$posted" }
                scheduleAuthTimeout(appContext)
            }
        }
    }

    /** 应用锁解锁成功 */
    fun onSuccess(context: Context, appName: String? = null, force: Boolean = false) {
        val appContext = context.applicationContext ?: context
        runOnMain {
            synchronized(stateLock) {
                if (!force && !isEnabled()) {
                    logDebug { "onSuccess ignored: disabled" }
                    return@synchronized
                }
                cancelTerminalRemoval()
                cancelAuthTimeout()
                currentState = UnlockState.SUCCESS
                terminalStartedAt = SystemClock.uptimeMillis()
                val posted = postSuccessNotification(appContext, appName)
                logDebug { "onSuccess: app=$appName, posted=$posted" }
                if (shouldKeepSuccessUntilDismiss()) {
                    // 保持成功状态直到手动取消或超时
                    scheduleTerminalRemoval(appContext, TERMINAL_CANCEL_DELAY_MS * 3)
                } else {
                    scheduleTerminalRemoval(appContext)
                }
            }
        }
    }

    /** 应用锁解锁失败 */
    fun onFailed(context: Context, reason: String? = null, force: Boolean = false) {
        val appContext = context.applicationContext ?: context
        runOnMain {
            synchronized(stateLock) {
                if (!force && !isEnabled()) return@synchronized
                cancelTerminalRemoval()
                cancelAuthTimeout()
                currentState = UnlockState.FAILED
                terminalStartedAt = SystemClock.uptimeMillis()
                val posted = postFailedNotification(appContext, reason)
                logDebug { "onFailed: reason=$reason, posted=$posted" }
                scheduleTerminalRemoval(appContext)
            }
        }
    }

    /** 认证结束（取消） */
    fun onStopped(context: Context) {
        val appContext = context.applicationContext ?: context
        runOnMain {
            synchronized(stateLock) {
                if (currentState == UnlockState.SUCCESS || currentState == UnlockState.FAILED) {
                    return@synchronized
                }
                cancelAuthTimeout()
                // 验证页 onPause 往往先于"成功"回调到达，留宽限时间，避免太快导致成功状态被提前撤掉
                scheduleTerminalRemoval(appContext, STOP_GRACE_MS)
            }
        }
    }

    // ---------- 通知构建 ----------

    private fun postAuthenticatingNotification(context: Context): Boolean {
        val icon = Icon.createWithResource(getModuleContext(context), R.drawable.ic_unlock_authenticating)
        val request = IslandRequest(
            title = "正在解锁",
            content = "应用锁验证中…",
            icon = icon,
            focusIcon = icon,
            notifId = NOTIF_ID,
            timeoutSecs = (AUTHENTICATING_TIMEOUT_MS / 1000L).toInt(),
            firstFloat = false,
            enableFloat = false,
            showNotification = false,
            islandOnly = true,
            isOngoing = true,
            updatable = true,
            focusTitle = "正在解锁",
            focusContent = "应用锁验证中…",
        )
        return IslandDispatcher.post(context, request)
    }

    private fun postSuccessNotification(context: Context, appName: String?): Boolean {
        val icon = Icon.createWithResource(getModuleContext(context), R.drawable.ic_unlock_success)
        val title = if (appName.isNullOrEmpty()) "已解锁" else "已解锁 $appName"
        val request = IslandRequest(
            title = title,
            content = "应用锁已解除",
            icon = icon,
            focusIcon = icon,
            notifId = NOTIF_ID,
            timeoutSecs = 2,
            firstFloat = false,
            enableFloat = false,
            showNotification = false,
            islandOnly = true,
            updatable = true,
            focusTitle = title,
            focusContent = "应用锁已解除",
            highlightColor = "#4CAF50",
        )
        return IslandDispatcher.post(context, request)
    }

    private fun postFailedNotification(context: Context, reason: String?): Boolean {
        val icon = Icon.createWithResource(getModuleContext(context), R.drawable.ic_unlock_failed)
        val content = reason ?: "验证失败，请重试"
        val request = IslandRequest(
            title = "解锁失败",
            content = content,
            icon = icon,
            focusIcon = icon,
            notifId = NOTIF_ID,
            timeoutSecs = 2,
            firstFloat = true,
            enableFloat = true,
            showNotification = false,
            islandOnly = true,
            updatable = true,
            focusTitle = "解锁失败",
            focusContent = content,
            highlightColor = "#F44336",
        )
        return IslandDispatcher.post(context, request)
    }

    private fun cancelNotification(context: Context) {
        IslandDispatcher.cancel(context, NOTIF_ID)
    }

    // ---------- 定时器 ----------

    private fun scheduleTerminalRemoval(context: Context, delayMs: Long = TERMINAL_CANCEL_DELAY_MS) {
        cancelTerminalRemoval()
        val runnable = Runnable {
            synchronized(stateLock) {
                currentState = UnlockState.STOPPED
                sessionActive = false
                cancelNotification(context)
            }
        }
        terminalCancel = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelTerminalRemoval() {
        terminalCancel?.let { mainHandler.removeCallbacks(it) }
        terminalCancel = null
    }

    private fun scheduleAuthTimeout(context: Context) {
        cancelAuthTimeout()
        val runnable = Runnable {
            synchronized(stateLock) {
                if (currentState == UnlockState.AUTHENTICATING) {
                    currentState = UnlockState.STOPPED
                    sessionActive = false
                    cancelNotification(context)
                }
            }
        }
        authTimeout = runnable
        mainHandler.postDelayed(runnable, AUTHENTICATING_TIMEOUT_MS)
    }

    private fun cancelAuthTimeout() {
        authTimeout?.let { mainHandler.removeCallbacks(it) }
        authTimeout = null
    }

    // ---------- 配置 ----------

    private fun isEnabled(): Boolean =
        ConfigManager.getBoolean(PREF_ENABLED, true)

    private fun shouldKeepSuccessUntilDismiss(): Boolean =
        ConfigManager.getBoolean(PREF_KEEP_SUCCESS_UNTIL_DISMISS, false)

    // ---------- 工具 ----------

    private fun getModuleContext(context: Context): Context {
        return moduleContext ?: context
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
