package com.jobradar.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents a validated or inspected local document selected via Storage Access Framework.
 * Stores [uriString] rather than android.net.Uri so domain and ViewModel layers stay JVM-testable.
 */
@Immutable
data class SelectedDocument(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
)
