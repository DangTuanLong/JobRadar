package com.jobradar.app.data.di

import com.jobradar.app.core.network.SessionTokenProvider
import com.jobradar.app.data.repository.AuthRepositoryImpl
import com.jobradar.app.data.repository.HealthRepositoryImpl
import com.jobradar.app.domain.repository.AuthRepository
import com.jobradar.app.domain.repository.HealthRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindSessionTokenProvider(impl: AuthRepositoryImpl): SessionTokenProvider

    @Binds
    @Singleton
    abstract fun bindHealthRepository(impl: HealthRepositoryImpl): HealthRepository
}
