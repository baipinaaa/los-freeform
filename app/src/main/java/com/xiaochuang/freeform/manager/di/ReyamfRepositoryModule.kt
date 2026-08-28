package com.xiaochuang.freeform.manager.di

import com.xiaochuang.freeform.manager.core.domain.repository.IReyamfRepository
import com.xiaochuang.freeform.manager.core.repository.ReyamfRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class ReyamfRepositoryModule {
    @Binds
    abstract fun provideAppRepository(
        reyamfRepository: ReyamfRepository
    ): IReyamfRepository
}