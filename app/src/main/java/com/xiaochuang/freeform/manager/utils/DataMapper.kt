package com.xiaochuang.freeform.manager.utils

import com.xiaochuang.freeform.common.model.AppInfo
import com.xiaochuang.freeform.manager.core.data.source.local.room.entity.AppInfoEntity

object DataMapper {
    fun appInfoToEntity(appInfo: AppInfo): AppInfoEntity =
        AppInfoEntity(
            appInfo.activityInfo,
            appInfo.userId,
            appInfo.userName
        )

    fun appInfoEntityToModel(appInfoEntity: AppInfoEntity): AppInfo =
        AppInfo(
            appInfoEntity.activityInfo,
            appInfoEntity.userId,
            appInfoEntity.userName
        )
}