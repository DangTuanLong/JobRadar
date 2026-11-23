package com.jobradar.app.ui.profile

import androidx.annotation.StringRes
import com.jobradar.app.R
import com.jobradar.app.domain.model.SkillItem

object ProfileInputValidator {
    const val MIN_YEARS_OF_EXPERIENCE = 0.0
    const val MAX_YEARS_OF_EXPERIENCE = 60.0

    fun parseYearsOfExperience(raw: String): Double? {
        val normalized = raw.trim().replace(',', '.')
        if (normalized.isEmpty()) return 0.0
        val parsed = normalized.toDoubleOrNull() ?: return null
        if (parsed.isNaN() || parsed.isInfinite()) return null
        return if (parsed in MIN_YEARS_OF_EXPERIENCE..MAX_YEARS_OF_EXPERIENCE) {
            parsed
        } else {
            null
        }
    }

    @StringRes
    fun validateNewSkill(
        rawName: String,
        existingSkills: List<SkillItem>,
    ): Int? {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return R.string.profile_error_skill_empty
        val isDuplicate = existingSkills.any { it.name.trim().equals(trimmed, ignoreCase = true) }
        return if (isDuplicate) R.string.profile_error_skill_duplicate else null
    }
}
