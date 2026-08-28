package com.xiaochuang.freeform.manager.services

import android.service.quicksettings.TileService

class QSResetAllWindow: TileService() {
    override fun onClick() {
        YAMFManagerProxy.resetAllWindow()
    }
}