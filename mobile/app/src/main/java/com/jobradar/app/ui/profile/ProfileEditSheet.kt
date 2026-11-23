package com.jobradar.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jobradar.app.R
import com.jobradar.app.domain.model.SkillLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditSheet(
    draft: ProfileEditDraft,
    onDismiss: () -> Unit,
    onFullNameChange: (String) -> Unit,
    onHeadlineChange: (String) -> Unit,
    onYearsChange: (String) -> Unit,
    onSummaryChange: (String) -> Unit,
    onGithubChange: (String) -> Unit,
    onPortfolioChange: (String) -> Unit,
    onLinkedinChange: (String) -> Unit,
    onNewSkillNameChange: (String) -> Unit,
    onNewSkillLevelChange: (SkillLevel) -> Unit,
    onAddSkill: () -> Unit,
    onRemoveSkill: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val fieldsEnabled = !draft.isSaving

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.profile_edit_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
                value = draft.fullName,
                onValueChange = onFullNameChange,
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text(text = stringResource(R.string.profile_edit_full_name)) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.headline,
                onValueChange = onHeadlineChange,
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text(text = stringResource(R.string.profile_edit_headline)) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.yearsOfExperienceText,
                onValueChange = onYearsChange,
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text(text = stringResource(R.string.profile_edit_years)) },
                isError = draft.yearsError != null,
                supportingText = draft.yearsError?.let { resId ->
                    { Text(text = stringResource(resId)) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.profileSummary,
                onValueChange = onSummaryChange,
                enabled = fieldsEnabled,
                minLines = 3,
                maxLines = 5,
                label = { Text(text = stringResource(R.string.profile_edit_summary)) },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.githubUrl,
                onValueChange = onGithubChange,
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text(text = stringResource(R.string.profile_edit_github)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.portfolioUrl,
                onValueChange = onPortfolioChange,
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text(text = stringResource(R.string.profile_edit_portfolio)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.linkedinUrl,
                onValueChange = onLinkedinChange,
                enabled = fieldsEnabled,
                singleLine = true,
                label = { Text(text = stringResource(R.string.profile_edit_linkedin)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.profile_edit_skills_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.newSkillName,
                    onValueChange = onNewSkillNameChange,
                    enabled = fieldsEnabled,
                    singleLine = true,
                    placeholder = { Text(text = stringResource(R.string.profile_edit_add_skill_hint)) },
                    isError = draft.skillInputError != null,
                    supportingText = draft.skillInputError?.let { resId ->
                        { Text(text = stringResource(resId)) }
                    },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onAddSkill,
                    enabled = fieldsEnabled,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.profile_edit_add_skill_btn))
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SkillLevel.entries.forEach { level ->
                    FilterChip(
                        selected = draft.newSkillLevel == level,
                        onClick = { onNewSkillLevelChange(level) },
                        enabled = fieldsEnabled,
                        label = { Text(text = stringResource(skillLevelLabelRes(level))) },
                    )
                }
            }

            if (draft.skills.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    draft.skills.forEach { skill ->
                        InputChip(
                            selected = skill.isGithubVerified,
                            onClick = { if (fieldsEnabled) onRemoveSkill(skill.name) },
                            enabled = fieldsEnabled,
                            label = { Text(text = skill.name) },
                            leadingIcon = if (skill.isGithubVerified) {
                                {
                                    Icon(
                                        imageVector = Icons.Outlined.Verified,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.profile_edit_remove_skill_cd),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = fieldsEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.profile_edit_cancel_btn))
                }
                Button(
                    onClick = onSave,
                    enabled = fieldsEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    if (draft.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.profile_edit_saving))
                    } else {
                        Text(text = stringResource(R.string.profile_edit_save_btn))
                    }
                }
            }
        }
    }
}
