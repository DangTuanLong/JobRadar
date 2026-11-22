package com.jobradar.app.domain.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.BackendHealth

interface HealthRepository {
    suspend fun checkHealth(): AppResult<BackendHealth>
}
