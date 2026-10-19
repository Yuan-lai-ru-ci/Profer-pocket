package com.profer.pocket.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferCodeTextStyle
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras

/**
 * 输入区上方的紧凑审批条：常驻但不占空间，点击进入完整弹层。
 * 阻断性决策需要随时可进入，但不该常驻吃掉消息区。
 */
@Composable
fun ProferPermissionPromptBar(
    title: String,
    summary: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAction)
                .heightIn(min = 48.dp)
                .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
        ) {
            ProferStatusDot(color = ProferThemeExtras.semantic.warning, size = 6.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = summary,
                    style = ProferCodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = ProferSpacing.sm),
            )
        }
    }
}

/**
 * 完整审批弹层：命令、说明、三种决定。
 * 内容可滚动，2.0× 字号下也不会把主界面压没。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProferPermissionSheet(
    title: String,
    description: String,
    command: String?,
    contextLabel: String?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
    onAlwaysAllow: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ProferSpacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
            ) {
                ProferStatusDot(color = ProferThemeExtras.semantic.warning, size = 7.dp)
                Text(
                    text = contextLabel ?: "来自 Profer",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = title,
                style = ProferHeadingSerif,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (command != null) {
                ProferCodeBlock(code = command, language = "shell", wrap = true)
            }

            Button(
                onClick = onAllow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = "允许一次",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (onAlwaysAllow != null) {
                OutlinedButton(
                    onClick = onAlwaysAllow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = CircleShape,
                ) {
                    Text(text = "本会话始终允许", style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(
                onClick = onDeny,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Text(
                    text = "拒绝",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
