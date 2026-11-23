package com.jobradar.app.ui.upload

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobradar.app.R
import com.jobradar.app.domain.model.SelectedDocument
import com.jobradar.app.domain.validator.DocumentFileValidator
import java.util.Locale

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024L
private const val SECONDS_PER_MINUTE = 60

@Composable
fun DocumentPickerCard(
    title: String,
    hint: String,
    document: SelectedDocument?,
    @StringRes errorRes: Int?,
    enabled: Boolean,
    onPickClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (document == null) {
                OutlinedButton(
                    onClick = onPickClick,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.upload_action_pick_file))
                }
            } else {
                SelectedDocumentRow(
                    document = document,
                    enabled = enabled,
                    onPickClick = onPickClick,
                    onClearClick = onClearClick,
                )
            }

            if (errorRes != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(errorRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SelectedDocumentRow(
    document: SelectedDocument,
    enabled: Boolean,
    onPickClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = if (document.mimeType == DocumentFileValidator.MIME_PDF) {
                Icons.Outlined.PictureAsPdf
            } else {
                Icons.Outlined.Description
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFileSize(document.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onClearClick,
                enabled = enabled,
            ) {
                Text(text = stringResource(R.string.upload_action_remove_file))
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(
                onClick = onPickClick,
                enabled = enabled,
            ) {
                Text(text = stringResource(R.string.upload_action_change_file))
            }
        }
    }
}

fun formatFileSize(sizeBytes: Long): String = when {
    sizeBytes < BYTES_PER_KB -> "$sizeBytes B"
    sizeBytes < BYTES_PER_MB -> "${sizeBytes / BYTES_PER_KB} KB"
    else -> String.format(Locale.US, "%.2f MB", sizeBytes.toDouble() / BYTES_PER_MB)
}

fun formatElapsedTime(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val mins = safeSeconds / SECONDS_PER_MINUTE
    val secs = safeSeconds % SECONDS_PER_MINUTE
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
