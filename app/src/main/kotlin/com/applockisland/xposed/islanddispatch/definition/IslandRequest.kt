package com.applockisland.xposed.islanddispatch.definition

import android.app.Notification
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews

/**
 * 超级岛通知请求数据类。
 * 封装所有需要传递给超级岛 / 焦点通知的展示参数。
 */
data class IslandRequest(
    val title: String,
    val content: String,
    val icon: Icon? = null,
    val focusIcon: Icon? = null,
    val rightIcon: Icon? = null,
    val notifId: Int = IslandDispatchContract.NOTIF_ID,
    val timeoutSecs: Int = 5,
    val firstFloat: Boolean = true,
    val enableFloat: Boolean = true,
    val showNotification: Boolean = true,
    val sourcePackage: String? = null,
    val sourceChannelId: String? = null,
    val dismissIsland: Boolean = false,
    val isOngoing: Boolean = false,
    val actions: List<Notification.Action> = emptyList(),
    val showIslandIcon: Boolean = true,
    val clearBeforePost: Boolean = false,
    val islandOnly: Boolean = false,
    val updatable: Boolean = false,
    val islandEnabled: Boolean = true,
    val focusTitle: String? = null,
    val focusContent: String? = null,
    val focusRemoteViews: RemoteViews? = null,
    val focusIslandExpandRemoteViews: RemoteViews? = null,
    val highlightColor: String? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("title", title)
        putString("content", content)
        if (icon != null) putParcelable("icon", icon)
        if (focusIcon != null) putParcelable("focusIcon", focusIcon)
        if (rightIcon != null) putParcelable("rightIcon", rightIcon)
        putInt("notifId", notifId)
        putInt("timeoutSecs", timeoutSecs)
        putBoolean("firstFloat", firstFloat)
        putBoolean("enableFloat", enableFloat)
        putBoolean("showNotification", showNotification)
        if (sourcePackage != null) putString("sourcePackage", sourcePackage)
        if (sourceChannelId != null) putString("sourceChannelId", sourceChannelId)
        putBoolean("dismissIsland", dismissIsland)
        putBoolean("isOngoing", isOngoing)
        if (actions.isNotEmpty()) putParcelableArray("actions", actions.toTypedArray())
        putBoolean("showIslandIcon", showIslandIcon)
        putBoolean("clearBeforePost", clearBeforePost)
        putBoolean("islandOnly", islandOnly)
        putBoolean("updatable", updatable)
        putBoolean("islandEnabled", islandEnabled)
        if (focusTitle != null) putString("focusTitle", focusTitle)
        if (focusContent != null) putString("focusContent", focusContent)
        if (focusRemoteViews != null) putParcelable("focusRemoteViews", focusRemoteViews)
        if (focusIslandExpandRemoteViews != null)
            putParcelable("focusIslandExpandRemoteViews", focusIslandExpandRemoteViews)
        if (highlightColor != null) putString("highlightColor", highlightColor)
    }

    companion object {
        fun fromBundle(b: Bundle) = IslandRequest(
            title = b.getString("title", ""),
            content = b.getString("content", ""),
            icon = iconFromBundle(b, "icon"),
            focusIcon = iconFromBundle(b, "focusIcon"),
            rightIcon = iconFromBundle(b, "rightIcon"),
            notifId = b.getInt("notifId", IslandDispatchContract.NOTIF_ID),
            timeoutSecs = b.getInt("timeoutSecs", 5),
            firstFloat = b.getBoolean("firstFloat", true),
            enableFloat = b.getBoolean("enableFloat", true),
            showNotification = b.getBoolean("showNotification", true),
            sourcePackage = b.getString("sourcePackage"),
            sourceChannelId = b.getString("sourceChannelId"),
            dismissIsland = b.getBoolean("dismissIsland", false),
            isOngoing = b.getBoolean("isOngoing", false),
            actions = actionsFromBundle(b),
            showIslandIcon = b.getBoolean("showIslandIcon", true),
            clearBeforePost = b.getBoolean("clearBeforePost", false),
            islandOnly = b.getBoolean("islandOnly", false),
            updatable = b.getBoolean("updatable", false),
            islandEnabled = b.getBoolean("islandEnabled", true),
            focusTitle = b.getString("focusTitle"),
            focusContent = b.getString("focusContent"),
            focusRemoteViews = remoteViewsFromBundle(b, "focusRemoteViews"),
            focusIslandExpandRemoteViews = remoteViewsFromBundle(b, "focusIslandExpandRemoteViews"),
            highlightColor = b.getString("highlightColor"),
        )

        private fun iconFromBundle(b: Bundle, key: String): Icon? =
            if (Build.VERSION.SDK_INT >= 33) b.getParcelable(key, Icon::class.java)
            else @Suppress("DEPRECATION") b.getParcelable(key)

        private fun actionsFromBundle(b: Bundle): List<Notification.Action> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                b.getParcelableArray("actions", Notification.Action::class.java)?.toList()
                    ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                (b.getParcelableArray("actions") as? Array<*>)
                    ?.filterIsInstance<Notification.Action>() ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        private fun remoteViewsFromBundle(b: Bundle, key: String): RemoteViews? =
            if (Build.VERSION.SDK_INT >= 33) b.getParcelable(key, RemoteViews::class.java)
            else @Suppress("DEPRECATION") b.getParcelable(key)
    }
}
