package com.jobradar.app.core.network

import com.jobradar.app.BuildConfig
import com.jobradar.app.core.config.AppConfig
import com.jobradar.app.data.remote.JobRadarApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideAppConfig(): AppConfig = AppConfig(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        apiBaseUrl = BuildConfig.API_BASE_URL,
    )

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideSupabaseClient(config: AppConfig): SupabaseClient = createSupabaseClient(
        supabaseUrl = config.supabaseUrl,
        supabaseKey = config.supabasePublishableKey,
    ) {
        install(Auth)
        httpEngine = OkHttp.create()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(NetworkConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NetworkConstants.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .apply {
                if (BuildConfig.DEBUG) {
                    val logging = HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactHeader(NetworkConstants.HEADER_AUTHORIZATION)
                    }
                    addInterceptor(logging)
                }
            }
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json,
        config: AppConfig,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(config.normalizedApiBaseUrl)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory(NetworkConstants.JSON_MEDIA_TYPE.toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideJobRadarApiService(retrofit: Retrofit): JobRadarApiService =
        retrofit.create(JobRadarApiService::class.java)
}
