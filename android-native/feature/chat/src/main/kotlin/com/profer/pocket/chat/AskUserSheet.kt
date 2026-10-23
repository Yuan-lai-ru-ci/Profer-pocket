package com.profer.pocket.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import com.profer.pocket.designsystem.component.sheetMaxHeight
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.model.AskQuestion

/**
 * Agent 追问表单：单选/多选 + 提交。
 *
 * answers 的 key 用问题原文（与桌面 AskUserBanner 一致），value 用选项 label 逗号拼接。
 * 不提供自由输入：手机上先保证「能回答」，自定义答案后续再加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AskUserSheet(
    questions: List<AskQuestion>,
    onSubmit: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selections = remember(questions) {
        questions.map { question -> mutableStateOf(emptySet<String>()) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // 弹层最高只到 86%，顶部不再需要状态栏内边距；底部由内容自己 navigationBarsPadding。
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sheetMaxHeight()
                .navigationBarsPadding()
                .padding(bottom = ProferSpacing.md),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Agent 需要你的输入",
                    style = ProferHeadingSerif,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "回答后会继续执行；与电脑端是同一个请求。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(ProferSpacing.lg),
            ) {
                itemsIndexed(questions) { index, question ->
                    val selected = selections[index]
                    Column(
                        modifier = Modifier.padding(horizontal = ProferSpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                    ) {
                        val header = question.header
                        if (!header.isNullOrBlank()) {
                            Text(
                                text = header,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        Text(
                            text = question.question,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        question.options.forEach { option ->
                            val isSelected = option.label in selected.value
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected.value = if (question.multiSelect) {
                                            if (isSelected) selected.value - option.label else selected.value + option.label
                                        } else {
                                            setOf(option.label)
                                        }
                                    },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = option.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        option.description?.takeIf { it.isNotBlank() }?.let { description ->
                                            Text(
                                                text = description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = "已选择",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val answers = buildMap {
                        questions.forEachIndexed { index, question ->
                            val chosen = selections[index].value
                            if (chosen.isNotEmpty()) {
                                put(question.question, chosen.joinToString(", "))
                            }
                        }
                    }
                    onSubmit(answers)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.md),
            ) {
                Text("提交回答")
            }
        }
    }
}
