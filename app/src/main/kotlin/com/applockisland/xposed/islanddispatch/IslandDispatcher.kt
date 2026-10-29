package com.applockisland.xposed.islanddispatch

import android.content.Context
import com.applockisland.xposed.islanddispatch.definition.IslandRequest
import com.applockisland.xposed.islanddispatch.invoke.IslandDispatcherNotifier

/**
 * 超级岛通知分发器。
 * 模块内各 Hook 通过此对象向超级岛发送通知。
 */
object IslandDispatcher {

    fun post(context: Context, request: IslandRequest): Boolean =
        IslandDispatcherNotifier.post(context, request)

    fun cancel(context: Context, notifId: Int) {
        IslandDispatcherNotifier.cancel(context, notifId)
    }
}
