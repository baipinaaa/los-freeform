package com.xiaochuang.freeform.manager.core.repository

import com.xiaochuang.freeform.common.model.AppInfo
import com.xiaochuang.freeform.manager.core.data.source.local.LocalDataSource
import com.xiaochuang.freeform.manager.core.domain.repository.IReyamfRepository
import com.xiaochuang.freeform.manager.utils.DataMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

class ReyamfRepository @Inject constructor(
    private val localDataSource: LocalDataSource
) : IReyamfRepository {
    override fun insertAppInfo(appInfo: AppInfo) {
        CoroutineScope(Dispatchers.IO).launch {
            localDataSource.insertAppInfo(DataMapper.appInfoToEntity(appInfo))
        }
    }

    override fun getAppInfoList(): Flow<List<AppInfo>> =
        flow {
            emit(
                localDataSource.getAllAppInfo().map {
                    DataMapper.appInfoEntityToModel(it)
                }
            )
        }.flowOn(Dispatchers.IO)

    override fun deleteAppInfo(appInfo: AppInfo) {
        localDataSource.deleteAppInfo(DataMapper.appInfoToEntity(appInfo))
    }
}