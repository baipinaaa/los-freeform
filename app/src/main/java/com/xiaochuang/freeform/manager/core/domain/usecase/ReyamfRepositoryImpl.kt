package com.xiaochuang.freeform.manager.core.domain.usecase

import com.xiaochuang.freeform.common.model.AppInfo
import com.xiaochuang.freeform.manager.core.domain.repository.IReyamfRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ReyamfRepositoryImpl @Inject constructor(
    private val iReyamfRepository: IReyamfRepository
) : ReyamfRepositoryUseCase {
    override fun insertAppInfo(appInfo: AppInfo) =
        iReyamfRepository.insertAppInfo(appInfo)

    override fun getAppInfoList(): Flow<List<AppInfo>> =
        iReyamfRepository.getAppInfoList()

    override fun deleteAppInfo(appInfo: AppInfo) =
        iReyamfRepository.deleteAppInfo(appInfo)
}