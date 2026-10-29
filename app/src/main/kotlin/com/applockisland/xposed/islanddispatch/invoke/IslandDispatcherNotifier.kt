package com.applockisland.xposed.islanddispatch.invoke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import com.applockisland.R
import com.applockisland.xposed.islanddispatch.definition.IslandDispatchContract
import com.applockisland.xposed.islanddispatch.definition.IslandRequest
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo

/**
 * 负责将 [IslandRequest] 构建为超级岛通知并通过 NotificationManager 发出。
 * 基于 hyperisland_kit 库构建 HyperIslandNotification。
 */
internal object IslandDispatcherNotifier {

    /** 由调用方注入的错误日志回调（写入 Xposed 日志，便于真机排查）。 */
    @Volatile
    var errorLogger: ((String, Throwable) -> Unit)? = null

    private val channelLock = Any()

    @Volatile
    private var channelReady = false

    fun ensureChannels(context: Context) {
        if (channelReady) return
        synchronized(channelLock) {
            if (channelReady) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                IslandDispatchContract.CHANNEL_ID,
                IslandDispatchContract.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
            channelReady = true
        }
    }

    fun post(context: Context, request: IslandRequest): Boolean {
        ensureChannels(context)
        return try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false

            val appIcon = request.icon ?: Icon.createWithResource(context, R.drawable.ic_island)
            val focusIcon = request.focusIcon ?: appIcon
            val focusTitle = request.focusTitle ?: request.title
            val focusContent = request.focusContent ?: request.content

            // 使用 hyperisland_kit 构建超级岛通知
            val island = HyperIslandNotification.Builder(context, "app_lock_island", focusTitle)

            // 添加图片资源
            island.addPicture(HyperPicture("key_island_icon", appIcon))
            island.addPicture(HyperPicture("key_focus_icon", focusIcon))

            // 设置焦点通知文本
            island.setIconTextInfo(
                /* picKey = */ "key_focus_icon",
                /* title = */ focusTitle,
                /* content = */ focusContent,
            )

            // 浮动配置
            island.setIslandFirstFloat(request.firstFloat)
            island.setEnableFloat(request.enableFloat)
            island.setShowNotification(request.showNotification)

            // 岛屿配置
            island.setIslandConfig(
                /* islandPriority = */ 0,
                /* islandTimeout = */ request.timeoutSecs,
                /* dismissIsland = */ request.dismissIsland,
                /* maxSize = */ false,
                /* highlightColor = */ request.highlightColor,
                /* expandedTime = */ null,
                /* needCloseAnimation = */ true,
            )

            // 小岛
            island.setSmallIsland("key_island_icon")

            // 大岛
            val bigIslandLeft = ImageTextInfoLeft(
                type = 1,
                picInfo = PicInfo(type = 1, pic = "key_island_icon"),
                textInfo = TextInfo(
                    title = request.title,
                    content = request.content,
                ),
            )
            val bigIslandRight = ImageTextInfoRight(
                type = 2,
                textInfo = TextInfo(
                    title = request.content,
                ),
            )
            island.setBigIslandInfo(
                left = bigIslandLeft,
                right = bigIslandRight,
            )

            if (request.focusRemoteViews != null) {
                island.setCustomRemoteView(request.focusRemoteViews)
            }

            // 构建通知 extras
            val notificationExtras = island.buildResourceBundle()
            val jsonParam = island.buildJsonParam()
            notificationExtras.putString("miui.focus.param", jsonParam)

            // 构建标准 Android Notification
            val publicVersion = Notification.Builder(context, IslandDispatchContract.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_island)
                .setContentTitle(IslandDispatchContract.CHANNEL_NAME)
                .setContentText("")
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()

            val visibility = if (request.showNotification) {
                Notification.VISIBILITY_PRIVATE
            } else {
                Notification.VISIBILITY_SECRET
            }

            val notif = Notification.Builder(context, IslandDispatchContract.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_island)
                .setContentTitle(focusTitle)
                .setContentText(focusContent)
                .setVisibility(visibility)
                .setPublicVersion(publicVersion)
                .setAutoCancel(true)
                .apply {
                    if (request.isOngoing) setOngoing(true)
                }
                .build()

            notif.extras.putAll(notificationExtras)

            nm.notify(request.notifId, notif)
            true
        } catch (e: Throwable) {
            errorLogger?.invoke("post notification failed (id=${request.notifId})", e)
            false
        }
    }

    fun cancel(context: Context, notifId: Int) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(notifId)
        } catch (_: Throwable) {
        }
    }
}
