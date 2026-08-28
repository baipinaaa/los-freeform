package com.xiaochuang.freeform.manager.core.domain.repository

import com.xiaochuang.freeform.common.model.AppInfo
import kotlinx.coroutines.flow.Flow

interface IReyamfRepository {
    fun insertAppInfo(appInfo: AppInfo)

    fun getAppInfoList(): Flow<List<AppInfo>>

    fun deleteAppInfo(appInfo: AppInfo)
}