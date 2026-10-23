package com.profer.pocket.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import com.profer.pocket.designsystem.theme.ProferSpacing

/**
 * 空态：一句人话解释为什么空 + 一个明确的下一步。
 */
@Composable
fun ProferEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = ProferSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(18.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
        } else {
            ProferMark(size = 52.dp)
        }
        Text(
            text = title,
            style = ProferHeadingSerif,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = ProferSpacing.lg),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = ProferSpacing.sm)
                .widthIn(max = 300.dp),
        )
        if (primaryLabel != null && onPrimary != null) {
            Box(modifier = Modifier.padding(top = ProferSpacing.lg)) {
                androidx.compose.material3.Button(
                    onClick = onPrimary,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text(text = primaryLabel)
                }
            }
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) {
                Text(text = secondaryLabel)
            }
        }
    }
}
