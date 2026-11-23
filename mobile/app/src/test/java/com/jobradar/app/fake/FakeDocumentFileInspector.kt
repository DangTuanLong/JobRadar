package com.jobradar.app.fake

import com.jobradar.app.core.network.AppResult
import com.jobradar.app.domain.model.SelectedDocument
import com.jobradar.app.domain.repository.DocumentFileInspector

class FakeDocumentFileInspector : DocumentFileInspector {

    private val resultsByUri = mutableMapOf<String, AppResult<SelectedDocument>>()

    fun stub(uriString: String, result: AppResult<SelectedDocument>) {
        resultsByUri[uriString] = result
    }

    override suspend fun inspect(uriString: String): AppResult<SelectedDocument> =
        resultsByUri[uriString] ?: AppResult.Error(
            code = "FILE_READ_ERROR",
            message = "No stubbed document for $uriString",
        )
}
