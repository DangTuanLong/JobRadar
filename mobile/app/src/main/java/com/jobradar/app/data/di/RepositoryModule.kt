package com.jobradar.app.data.di

import com.jobradar.app.core.network.SessionTokenProvider
import com.jobradar.app.data.file.DocumentFileHelper
import com.jobradar.app.data.file.DocumentMultipartFactory
import com.jobradar.app.data.local.AnalysisPreferencesDataStore
import com.jobradar.app.data.repository.AnalysisRepositoryImpl
import com.jobradar.app.data.repository.AuthRepositoryImpl
import com.jobradar.app.data.repository.HealthRepositoryImpl
import com.jobradar.app.data.repository.MatchRepositoryImpl
import com.jobradar.app.data.repository.ProfileRepositoryImpl
import com.jobradar.app.data.repository.SupabaseAuthGateway
import com.jobradar.app.data.repository.SupabaseAuthGatewayImpl
import com.jobradar.app.domain.repository.AnalysisRepository
import com.jobradar.app.domain.repository.AnalysisSessionStorage
import com.jobradar.app.domain.repository.AuthRepository
import com.jobradar.app.domain.repository.DocumentFileInspector
import com.jobradar.app.domain.repository.HealthRepository
import com.jobradar.app.domain.repository.MatchRepository
import com.jobradar.app.domain.repository.ProfileRepository
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
    abstract fun bindSupabaseAuthGateway(impl: SupabaseAuthGatewayImpl): SupabaseAuthGateway

    @Binds
    @Singleton
    abstract fun bindAnalysisSessionStorage(impl: AnalysisPreferencesDataStore): AnalysisSessionStorage

    @Binds
    @Singleton
    abstract fun bindSessionTokenProvider(impl: AuthRepositoryImpl): SessionTokenProvider

    @Binds
    @Singleton
    abstract fun bindHealthRepository(impl: HealthRepositoryImpl): HealthRepository

    @Binds
    @Singleton
    abstract fun bindDocumentFileInspector(impl: DocumentFileHelper): DocumentFileInspector

    @Binds
    @Singleton
    abstract fun bindDocumentMultipartFactory(impl: DocumentFileHelper): DocumentMultipartFactory

    @Binds
    @Singleton
    abstract fun bindAnalysisRepository(impl: AnalysisRepositoryImpl): AnalysisRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindMatchRepository(impl: MatchRepositoryImpl): MatchRepository
}
