package com.profer.pocket.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.profer.pocket.designsystem.adaptive.rememberProferWindowSize

/**
 * 弹层容器：手机是底部弹层，平板/折叠展开是居中对话框。
 *
 * 底部弹层在 800dp 宽的屏上会被拉成一条横贯全屏的板子：按钮 500dp 宽、
 * 内容稀疏、还贴着屏幕底边。平板改用四周留白的居中卡片，宽度 420–560dp，
 * 高度不超过 86% 屏高，内容自己滚动。
 *
 * 只替换容器，内部结构（滚动区 + 固定动作区）由调用方保持原样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProferAdaptiveSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 560.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    val window = rememberProferWindowSize()
    // 横屏短屏（360dp 高）也走居中弹窗：底部弹层只能分到一条窄带，选项要来回滚。
    if (window.isTwoPane || (window.isShort && window.isLandscape)) {
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = modifier
                    // 窄横屏（例如 480dp 半折叠）不能硬撑 420dp 下限，至少给两侧各留 16dp。
                    .widthIn(min = minOf(420.dp, window.width - 32.dp), max = maxWidth)
                    .heightIn(max = screenHeight * 0.86f),
                shape = MaterialTheme.shapes.extraLarge,
                color = containerColor,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth(), content = content)
            }
        }
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = containerColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // 弹层最高只到 86%，顶部不需要状态栏内边距；底部由内容自己 navigationBarsPadding。
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 0.dp), content = content)
    }
}
