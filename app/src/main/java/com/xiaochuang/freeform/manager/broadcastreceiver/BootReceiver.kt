package com.xiaochuang.freeform.manager.broadcastreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiaochuang.freeform.application
import com.xiaochuang.freeform.common.gson
import com.xiaochuang.freeform.common.model.Config
import com.xiaochuang.freeform.manager.applist.AppListWindow
import com.xiaochuang.freeform.manager.services.YAMFManagerProxy
import com.xiaochuang.freeform.manager.sidebar.Action
import com.xiaochuang.freeform.manager.sidebar.SidebarMenuService
import com.xiaochuang.freeform.manager.sidebar.SidebarService

class BootReceiver : BroadcastReceiver() {

    lateinit var config: Config

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            config = gson.fromJson(YAMFManagerProxy.configJson, Config::class.java)

            if (config.launchSideBarAtBoot) {
                application.startService(Intent(application, SidebarService::class.java))
            }
        }
    }
}