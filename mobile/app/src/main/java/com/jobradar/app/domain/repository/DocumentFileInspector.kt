package com.jobradar.app.domain.repository

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.SelectedDocument

interface DocumentFileInspector {
    suspend fun inspect(uriString: String): AppResult<SelectedDocument>
}
