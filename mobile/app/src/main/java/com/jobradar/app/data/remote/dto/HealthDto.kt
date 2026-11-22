package com.jobradar.app.data.remote.dto

import com.jobradar.app.domain.model.BackendHealth
import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(
    val status: String,
    val environment: String? = null,
)

fun HealthDto.toDomain(): BackendHealth = BackendHealth(
    status = status,
    environment = environment,
)
