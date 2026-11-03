package com.profer.pocket.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.component.ProferAdaptiveSheet
import com.profer.pocket.designsystem.component.sheetMaxHeight
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras
import com.profer.pocket.model.ReasoningOption

/**
 * 思考强度选择。
 *
 * 档位集合由电脑端按「渠道 + 模型」算好后下发，手机不自造档位：
 * 同一个「高」在不同模型上可能落成不同请求值，甚至有的模型只有「关闭 / 高」两档。
 * 第一项固定是「跟随预设」，选中它等于清除会话覆盖，与桌面同一套语义。
 *
 * Agent 运行中服务端会拒绝切换，这里直接禁用并说明原因，避免点了才报错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThinkingPickerSheet(
    options: List<ReasoningOption>,
    /** 应该勾选的档位；null = 跟随预设。 */
    selectedValue: String?,
    /** 当前值来源，如「跟随预设「高」」。 */
    sourceLabel: String,
    loading: Boolean,
    streaming: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ProferAdaptiveSheet(onDismissRequest = onDismiss) {
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
                    text = "思考强度",
                    style = ProferHeadingSerif,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (loading) "正在读取该模型的档位…" else "当前：$sourceLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }

            if (streaming) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.xs),
                    shape = MaterialTheme.shapes.small,
                    color = ProferThemeExtras.semantic.warningContainer,
                ) {
                    Text(
                        text = "正在生成，本轮结束后才能切换思考强度。",
                        style = MaterialTheme.typography.labelMedium,
                        color = ProferThemeExtras.semantic.onWarningContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }

            val enabled = !streaming && !loading
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                items(options, key = { it.value ?: "default" }) { option ->
                    ReasoningRow(
                        option = option,
                        selected = option.value == selectedValue,
                        enabled = enabled,
                        onClick = { onSelect(option.value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningRow(
    option: ReasoningOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.055f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 说明只给语义不直观的项（「跟随预设」继承到什么档、「关闭」意味着什么）。
            option.hint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.75f else 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "当前档位",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
