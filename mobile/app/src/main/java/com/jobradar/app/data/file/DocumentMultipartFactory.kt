package com.jobradar.app.data.file

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.SelectedDocument
import okhttp3.MultipartBody

/**
 * Converts a validated [SelectedDocument] into a streaming OkHttp [MultipartBody.Part].
 */
fun interface DocumentMultipartFactory {
    suspend fun createPart(
        document: SelectedDocument,
        partName: String,
    ): AppResult<MultipartBody.Part>
}
