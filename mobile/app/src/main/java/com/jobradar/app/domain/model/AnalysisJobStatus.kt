package com.jobradar.app.domain.model

enum class AnalysisJobStatus {
    PENDING,
    EXTRACTING,
    ENRICHING,
    MATCHING,
    COMPLETED,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED

    companion object {
        fun fromWireValue(raw: String): AnalysisJobStatus {
            val normalized = raw.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: PENDING
        }
    }
}
