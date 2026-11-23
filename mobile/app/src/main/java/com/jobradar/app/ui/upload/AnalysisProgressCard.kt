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
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobradar.app.R
import com.jobradar.app.domain.model.AnalysisJobStatus

private const val UPLOADING_PROGRESS = 0.08f
private const val PERCENT_MAX = 100f

private data class AnalysisStageItem(
    val status: AnalysisJobStatus,
    @param:StringRes val labelRes: Int,
)

private val ANALYSIS_STAGES = listOf(
    AnalysisStageItem(AnalysisJobStatus.PENDING, R.string.upload_step_pending),
    AnalysisStageItem(AnalysisJobStatus.EXTRACTING, R.string.upload_step_extracting),
    AnalysisStageItem(AnalysisJobStatus.ENRICHING, R.string.upload_step_enriching),
    AnalysisStageItem(AnalysisJobStatus.MATCHING, R.string.upload_step_matching),
)

@Composable
fun AnalysisProgressCard(
    phase: UploadCvPhase,
    elapsedSeconds: Int,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeStatus = when (phase) {
        UploadCvPhase.Uploading -> AnalysisJobStatus.PENDING
        is UploadCvPhase.Tracking -> phase.job.status
        else -> AnalysisJobStatus.PENDING
    }
    val progressFraction = when (phase) {
        UploadCvPhase.Uploading -> UPLOADING_PROGRESS
        is UploadCvPhase.Tracking -> (phase.job.progressPercent / PERCENT_MAX).coerceIn(0f, 1f)
        else -> 0f
    }
    val progressPercent = (progressFraction * PERCENT_MAX).toInt()
    val statusNote = when (phase) {
        UploadCvPhase.Uploading -> stringResource(R.string.upload_status_uploading)
        is UploadCvPhase.Tracking -> phase.job.currentStepMessage
        else -> null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.upload_progress_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.upload_progress_percent, progressPercent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.upload_elapsed_label, formatElapsedTime(elapsedSeconds)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!statusNote.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            StageTimelineList(activeStatus = activeStatus)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancelClick) {
                    Text(text = stringResource(R.string.upload_action_cancel_tracking))
                }
            }
        }
    }
}

@Composable
private fun StageTimelineList(
    activeStatus: AnalysisJobStatus,
    modifier: Modifier = Modifier,
) {
    val activeRank = stageRank(activeStatus)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ANALYSIS_STAGES.forEach { stage ->
            val rank = stageRank(stage.status)
            val isCompleted = rank < activeRank
            val isCurrent = rank == activeRank
            val icon = when {
                isCompleted -> Icons.Outlined.CheckCircleOutline
                isCurrent -> Icons.Outlined.Autorenew
                else -> Icons.Outlined.RadioButtonUnchecked
            }
            val tint = when {
                isCompleted -> MaterialTheme.colorScheme.tertiary
                isCurrent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
            val textColor = if (isCompleted || isCurrent) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(stage.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
            }
        }
    }
}

private fun stageRank(status: AnalysisJobStatus): Int = when (status) {
    AnalysisJobStatus.PENDING -> 0
    AnalysisJobStatus.EXTRACTING -> 1
    AnalysisJobStatus.ENRICHING -> 2
    AnalysisJobStatus.MATCHING -> 3
    AnalysisJobStatus.COMPLETED -> 4
    AnalysisJobStatus.FAILED -> 0
}
